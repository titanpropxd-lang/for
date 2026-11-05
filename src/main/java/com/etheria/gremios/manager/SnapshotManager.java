package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Territory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Guarda el estado original de un territorio ("snapshot") y lo restaura por partes
 * (con presupuesto de milisegundos por tick) para no bloquear el servidor.
 * Los cofres y demás contenedores se restauran VACÍOS.
 */
public class SnapshotManager {
    private static final int MAGIC = 0x534E4150;

    private final GremiosPlugin plugin;
    private final File dir;
    private final Set<String> working = new HashSet<>();

    public SnapshotManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "snapshots");
        dir.mkdirs();
    }

    public File fileOf(Territory t) {
        return new File(dir, t.id + ".snap");
    }

    public boolean has(Territory t) {
        return fileOf(t).exists();
    }

    public void delete(Territory t) {
        fileOf(t).delete();
    }

    public boolean isWorking(Territory t) {
        return working.contains(t.id);
    }

    private static boolean isContainerMaterial(Material m) {
        String n = m.name();
        return m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL || m == Material.HOPPER
                || m == Material.DROPPER || m == Material.DISPENSER || m == Material.FURNACE || m == Material.BLAST_FURNACE
                || m == Material.SMOKER || m == Material.BREWING_STAND || m == Material.CRAFTER || m == Material.CHISELED_BOOKSHELF
                || n.endsWith("SHULKER_BOX");
    }

    /** Presupuesto de ms por tick; baja solo si el servidor va justo de TPS. */
    private long budgetNanos() {
        double ms = plugin.getConfig().getDouble("snapshot.ms-por-tick", 30);
        if (plugin.getConfig().getBoolean("snapshot.adaptar-tps", true)) {
            double tps = 20.0;
            try {
                tps = Bukkit.getTPS()[0];
            } catch (Throwable ignored) {
            }
            if (tps < 15) ms *= 0.15;
            else if (tps < 18) ms *= 0.4;
            else if (tps < 19.3) ms *= 0.7;
        }
        double min = plugin.getConfig().getDouble("snapshot.ms-minimo", 4);
        return (long) (Math.max(min, ms) * 1_000_000d);
    }

    /** Carga (async) todos los chunks de la región y los mantiene cargados mientras dura la operación. */
    private void preload(Territory t, World w, Runnable then) {
        final List<int[]> chunks = new ArrayList<>();
        for (int cx = t.minX >> 4; cx <= t.maxX >> 4; cx++) {
            for (int cz = t.minZ >> 4; cz <= t.maxZ >> 4; cz++) chunks.add(new int[]{cx, cz});
        }
        final CompletableFuture<?>[] fs = new CompletableFuture<?>[chunks.size()];
        for (int i = 0; i < fs.length; i++) {
            int[] c = chunks.get(i);
            fs[i] = w.getChunkAtAsync(c[0], c[1]).thenAccept(ch -> ch.addPluginChunkTicket(plugin));
        }
        CompletableFuture.allOf(fs).whenComplete((v, ex) -> Bukkit.getScheduler().runTask(plugin, then));
    }

    private void releaseChunks(Territory t, World w) {
        for (int cx = t.minX >> 4; cx <= t.maxX >> 4; cx++) {
            for (int cz = t.minZ >> 4; cz <= t.maxZ >> 4; cz++) {
                if (w.isChunkLoaded(cx, cz)) w.getChunkAt(cx, cz).removePluginChunkTicket(plugin);
            }
        }
    }

    /** Avisa del progreso a los admins conectados (actionbar). */
    private void progress(String what, Territory t, int pct) {
        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("gremios.admin")) com.etheria.gremios.util.Msg.actionbar(p, "§d" + what + " §f" + t.name + " §7" + pct + "%");
        }
    }

    // ------------------------------------------------------------------ captura

    /** Captura el territorio. done recibe null si fue bien o un mensaje de error. */
    public void capture(Territory t, Consumer<String> done) {
        World w = t.bukkitWorld();
        if (w == null) {
            done.accept("El mundo '" + t.world + "' no está cargado.");
            return;
        }
        if (!working.add(t.id)) {
            done.accept("Ya hay una operación en curso para ese territorio.");
            return;
        }
        final int sx = t.maxX - t.minX + 1, sy = t.maxY - t.minY + 1, sz = t.maxZ - t.minZ + 1;
        final long vol = (long) sx * sy * sz;
        final short[] idx = new short[(int) vol];
        final Map<BlockData, Integer> pal = new HashMap<>();
        final List<String> palList = new ArrayList<>();
        preload(t, w, () -> new BukkitRunnable() {
            int i = 0;
            int lastPct = -1;

            @Override
            public void run() {
                final long budget = budgetNanos();
                long start = System.nanoTime();
                while (i < idx.length) {
                    int x = t.minX + (i % sx);
                    int z = t.minZ + ((i / sx) % sz);
                    int y = t.minY + (i / (sx * sz));
                    BlockData bd = w.getBlockAt(x, y, z).getBlockData();
                    Integer p = pal.get(bd);
                    if (p == null) {
                        if (palList.size() >= 32000) {
                            cancel();
                            working.remove(t.id);
                            releaseChunks(t, w);
                            done.accept("Demasiados tipos de bloque distintos en la región.");
                            return;
                        }
                        p = palList.size();
                        pal.put(bd, p);
                        palList.add(bd.getAsString());
                    }
                    idx[i] = (short) (int) p;
                    i++;
                    if ((i & 255) == 0 && System.nanoTime() - start > budget) break;
                }
                int pct = (int) (100L * i / idx.length);
                if (pct != lastPct) {
                    if (pct / 20 != Math.max(lastPct, 0) / 20) plugin.getLogger().info("Snapshot " + t.id + ": " + pct + "%");
                    lastPct = pct;
                    progress("Guardando", t, pct);
                }
                if (i >= idx.length) {
                    cancel();
                    releaseChunks(t, w);
                    // escribir a disco en otro hilo
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        String err = null;
                        try (DataOutputStream out = new DataOutputStream(new java.io.BufferedOutputStream(new GZIPOutputStream(new FileOutputStream(fileOf(t)))))) {
                            out.writeInt(MAGIC);
                            out.writeInt(t.minX);
                            out.writeInt(t.minY);
                            out.writeInt(t.minZ);
                            out.writeInt(sx);
                            out.writeInt(sy);
                            out.writeInt(sz);
                            out.writeInt(palList.size());
                            for (String s : palList) out.writeUTF(s);
                            ByteBuffer bb = ByteBuffer.allocate(idx.length * 2);
                            bb.asShortBuffer().put(idx);
                            out.write(bb.array());
                        } catch (IOException e) {
                            err = "Error al escribir el snapshot: " + e.getMessage();
                        }
                        final String fe = err;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            working.remove(t.id);
                            done.accept(fe);
                        });
                    });
                }
            }
        }.runTaskTimer(plugin, 1L, 1L));
    }

    // ------------------------------------------------------------------ restauración

    public void restore(Territory t, Runnable done) {
        World w = t.bukkitWorld();
        File f = fileOf(t);
        if (w == null || !f.exists()) {
            if (done != null) done.run();
            return;
        }
        if (!working.add(t.id)) {
            // ya se está restaurando: esperar a que acabe
            Bukkit.getScheduler().runTaskLater(plugin, () -> restore(t, done), 40L);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            final int[] hdr = new int[7];
            final String[] palStr;
            final short[] idx;
            try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new GZIPInputStream(new FileInputStream(f))))) {
                if (in.readInt() != MAGIC) throw new IOException("formato desconocido");
                for (int i = 0; i < 6; i++) hdr[i] = in.readInt();
                int pn = in.readInt();
                palStr = new String[pn];
                for (int i = 0; i < pn; i++) palStr[i] = in.readUTF();
                int vol = hdr[3] * hdr[4] * hdr[5];
                byte[] raw = in.readNBytes(vol * 2);
                idx = new short[vol];
                ByteBuffer.wrap(raw).asShortBuffer().get(idx);
            } catch (IOException e) {
                plugin.getLogger().warning("No se pudo leer el snapshot de " + t.id + ": " + e.getMessage());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    working.remove(t.id);
                    if (done != null) done.run();
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> preload(t, w, () -> runRestore(t, w, hdr, palStr, idx, done)));
        });
    }

    private void runRestore(Territory t, World w, int[] hdr, String[] palStr, short[] idx, Runnable done) {
        final int minX = hdr[0], minY = hdr[1], minZ = hdr[2], sx = hdr[3], sy = hdr[4], sz = hdr[5];
        final BlockData[] pal = new BlockData[palStr.length];
        final boolean[] cont = new boolean[palStr.length];

        new BukkitRunnable() {
            int i = 0;
            int lastPct = -1;

            @Override
            public void run() {
                final long budget = budgetNanos();
                long start = System.nanoTime();
                while (i < idx.length) {
                    int p = idx[i];
                    if (pal[p] == null) {
                        try {
                            pal[p] = Bukkit.createBlockData(palStr[p]);
                        } catch (IllegalArgumentException ex) {
                            pal[p] = Material.AIR.createBlockData();
                        }
                        cont[p] = isContainerMaterial(pal[p].getMaterial());
                    }
                    int x = minX + (i % sx);
                    int z = minZ + ((i / sx) % sz);
                    int y = minY + (i / (sx * sz));
                    Block b = w.getBlockAt(x, y, z);
                    BlockData cur = b.getBlockData();
                    if (!cur.equals(pal[p])) {
                        // vaciar contenedores que vamos a reemplazar para que no suelten items
                        if (isContainerMaterial(cur.getMaterial())) clearContainer(b);
                        b.setBlockData(pal[p], false);
                    }
                    if (cont[p]) clearContainer(b);
                    i++;
                    if ((i & 127) == 0 && System.nanoTime() - start > budget) break;
                }
                int pct = (int) (100L * i / idx.length);
                if (pct != lastPct) {
                    if (pct / 20 != Math.max(lastPct, 0) / 20) plugin.getLogger().info("Restaurando " + t.id + ": " + pct + "%");
                    lastPct = pct;
                    progress("Restaurando", t, pct);
                }
                if (i >= idx.length) {
                    cancel();
                    cleanEntities(t, w);
                    releaseChunks(t, w);
                    working.remove(t.id);
                    if (done != null) done.run();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private static void clearContainer(Block b) {
        BlockState st = b.getState();
        if (st instanceof Container c) {
            c.getInventory().clear();
        }
    }

    private void cleanEntities(Territory t, World w) {
        for (Entity e : w.getEntities()) {
            if ((e instanceof Item || e instanceof ExperienceOrb) && t.contains(e.getLocation())) e.remove();
        }
    }
}
