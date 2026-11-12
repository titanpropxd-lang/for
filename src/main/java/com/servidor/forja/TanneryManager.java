package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Curtiduría física. El bastidor de curtidor (estación "curtideria") deja de ser un menú de crafteo:
 *
 *  1. Clic derecho con una piel cruda        -> se extiende sobre la tabla de trabajo (hasta 4 pieles iguales).
 *  2. Clic derecho con una herramienta       -> descarnado: se quitan los restos de carne y grasa (varios golpes).
 *  3. Clic derecho con otra herramienta      -> raspado y limpieza (varios golpes).
 *  4. Clic derecho con cal viva              -> las pieles pasan a la cuba de cal (se espera).
 *  5. Clic derecho con tanino vegetal        -> cuba de tanino (se espera).
 *  6. Clic derecho con la mano vacía         -> se cuelgan en el bastidor para secarse (se espera).
 *  7. Clic derecho con la mano vacía         -> se recoge el cuero, con la calidad que da tu nivel de curtidor.
 *
 * Los baños (cal viva y tanino) se preparan con el menú: agáchate con la mano vacía en el bastidor.
 * Los visuales son Display entities no persistentes, recreadas desde la base de datos.
 */
public final class TanneryManager implements Listener {
    private static final int EMPTY = 0, RAW = 1, FLESHED = 2, SCRAPED = 3, LIMING = 4, LIMED = 5, TANNING = 6, TANNED = 7, DRYING = 8, DONE = 9;
    private static final int TICK = 10;
    private static final Quaternionf FLAT = new Quaternionf().rotateX((float) -Math.PI / 2);
    private static final Map<String, Integer> HIDE_LEVEL = Map.of(
            "mat_piel_lobo", 1, "mat_piel_bovina", 1, "mat_piel_ovina", 1,
            "mat_piel_zorro", 2, "mat_piel_caprina", 2, "mat_piel_felina", 3, "mat_piel_equina", 4);

    private static final class Tannery {
        final String world;
        final int x, y, z;
        int stage = EMPTY;
        String hide;
        int count;
        int hits;
        int ticks;
        final List<Entity> visuals = new ArrayList<>();

        Tannery(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean isEmpty() {
            return stage == EMPTY && count == 0;
        }

        Location loc() {
            return new Location(Bukkit.getWorld(world), x + 0.5, y + 1.0, z + 0.5);
        }
    }

    private final ForjaPlugin plugin;
    private final Map<String, Tannery> map = new ConcurrentHashMap<>();
    private final Random rnd = new Random();
    private BukkitTask task;

    public TanneryManager(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ configuración

    private static String key(String w, int x, int y, int z) {
        return w + ";" + x + ";" + y + ";" + z;
    }

    private static String key(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private int cfgInt(String path, int def) {
        return plugin.getConfig().getInt("curtiduria_fisica." + path, def);
    }

    private int capacity() {
        return Math.max(1, cfgInt("capacidad", 4));
    }

    private boolean matches(ItemStack hand, String listPath, List<String> defaults) {
        if (hand == null || hand.getType().isAir()) return false;
        List<String> l = plugin.getConfig().getStringList("curtiduria_fisica." + listPath);
        if (l.isEmpty()) l = defaults;
        return l.contains(hand.getType().name());
    }

    private boolean isFleshTool(ItemStack hand) {
        return matches(hand, "herramientas_descarnar", List.of("SHEARS", "STONE_SWORD", "IRON_SWORD", "STONE_AXE", "IRON_AXE"));
    }

    private boolean isScrapeTool(ItemStack hand) {
        return matches(hand, "herramientas_raspar", List.of("BRUSH", "STONE_SHOVEL", "IRON_SHOVEL", "STONE_HOE", "IRON_HOE"));
    }

    private int stageTicks(int stage) {
        return switch (stage) {
            case LIMING -> cfgInt("cal_seg", 30) * 20;
            case TANNING -> cfgInt("tanino_seg", 45) * 20;
            case DRYING -> cfgInt("secado_seg", 40) * 20;
            default -> 0;
        };
    }

    private String hideName(String id) {
        CustomItem ci = id == null ? null : plugin.items.get(id);
        return ci != null ? ci.nombre : String.valueOf(id);
    }

    private void bar(Player p, String key, Object... kv) {
        plugin.stationMessage(p, plugin.lang.get(key, kv));
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void load() {
        map.clear();
        try {
            Map<String, Tannery> loaded = plugin.db.query(c -> {
                Map<String, Tannery> out = new LinkedHashMap<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT world,x,y,z,data FROM tannery")) {
                    while (rs.next()) {
                        Tannery t = new Tannery(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                        decode(t, rs.getString(5));
                        out.put(key(t.world, t.x, t.y, t.z), t);
                    }
                }
                return out;
            }).join();
            map.putAll(loaded);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo cargar el estado de las curtidurías", e);
        }
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK, TICK);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Tannery t : map.values()) {
            save(t);
            clearVisuals(t);
        }
    }

    /** Crea el bastidor físico de cada estación "curtideria" ya registrada. */
    public void syncStations() {
        for (Map.Entry<String, String> en : plugin.stations.snapshot().entrySet()) {
            if (!en.getValue().equals("curtideria")) continue;
            String[] a = en.getKey().split(";", 4);
            if (a.length != 4) continue;
            Tannery t = map.computeIfAbsent(en.getKey(), k -> new Tannery(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
            World w = Bukkit.getWorld(t.world);
            if (w != null && w.isChunkLoaded(t.x >> 4, t.z >> 4) && t.visuals.isEmpty()) refresh(t);
        }
    }

    public void ensure(Block b) {
        Tannery t = map.computeIfAbsent(key(b), k -> new Tannery(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        refresh(t);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        final String w = e.getWorld().getName();
        final int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        boolean any = false;
        for (Tannery t : map.values()) if (t.world.equals(w) && (t.x >> 4) == cx && (t.z >> 4) == cz) { any = true; break; }
        if (!any) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Tannery t : map.values()) if (t.world.equals(w) && (t.x >> 4) == cx && (t.z >> 4) == cz) refresh(t);
        }, 5L);
    }

    // ------------------------------------------------------------------ persistencia

    private void decode(Tannery t, String raw) {
        try {
            String[] a = raw.split("\\|", -1);
            t.stage = Integer.parseInt(a[0]);
            t.hide = a[1].equals("-") ? null : a[1];
            t.count = Integer.parseInt(a[2]);
            t.hits = Integer.parseInt(a[3]);
            t.ticks = Integer.parseInt(a[4]);
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Estado de curtiduría ilegible en " + t.x + "," + t.y + "," + t.z + ": se reinicia", ex);
        }
    }

    private void save(Tannery t) {
        final String w = t.world;
        final int x = t.x, y = t.y, z = t.z;
        if (t.isEmpty()) {
            plugin.db.run(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM tannery WHERE world=? AND x=? AND y=? AND z=?")) {
                    ps.setString(1, w);
                    ps.setInt(2, x);
                    ps.setInt(3, y);
                    ps.setInt(4, z);
                    ps.executeUpdate();
                }
            });
            return;
        }
        final String data = t.stage + "|" + (t.hide == null ? "-" : t.hide) + "|" + t.count + "|" + t.hits + "|" + t.ticks;
        plugin.db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tannery(world,x,y,z,data) VALUES(?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET data=excluded.data")) {
                ps.setString(1, w);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setString(5, data);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ hooks de estación

    public void dropContents(Block b) {
        Tannery t = map.get(key(b));
        if (t == null || t.count <= 0) return;
        Location at = b.getLocation().add(0.5, 0.8, 0.5);
        ItemStack st = null;
        if (t.stage == DONE) st = leather(null, t.count);
        else if (t.stage == RAW && t.hide != null) st = plugin.items.basic(t.hide, t.count);
        else if (t.stage > RAW && t.stage < DONE) st = plugin.items.basic("piel_descarnada", t.count);
        if (st != null) b.getWorld().dropItemNaturally(at, st);
    }

    public void clear(Block b) {
        Tannery t = map.remove(key(b));
        if (t == null) return;
        clearVisuals(t);
        t.stage = EMPTY;
        t.count = 0;
        save(t);
    }

    // ------------------------------------------------------------------ interacción

    public void interact(Player p, Block b) {
        Tannery t = map.computeIfAbsent(key(b), k -> new Tannery(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        ItemStack hand = p.getInventory().getItemInMainHand();
        boolean empty = hand == null || hand.getType().isAir();
        String id = empty ? null : plugin.items.idOf(hand);

        // 1) pieles crudas
        if (id != null && id.startsWith("mat_piel_")) {
            addHide(p, t, hand, id);
            return;
        }
        // 2) descarnado / raspado
        if (!empty && id == null && (t.stage == RAW || t.stage == FLESHED)) {
            if (t.stage == RAW && isFleshTool(hand)) { work(p, t, hand, true); return; }
            if (t.stage == FLESHED && isScrapeTool(hand)) { work(p, t, hand, false); return; }
        }
        // 3) cubas
        if (id != null && id.equals("cal_viva")) {
            if (t.stage == SCRAPED) { startBath(p, t, hand, LIMING, "cal"); return; }
            status(p, t);
            return;
        }
        if (id != null && id.equals("tanino_vegetal")) {
            if (t.stage == LIMED) { startBath(p, t, hand, TANNING, "tanino"); return; }
            status(p, t);
            return;
        }
        if (!empty) { status(p, t); return; }

        // 4) mano vacía
        if (p.isSneaking()) {
            if (t.stage == EMPTY) {
                final Location center = b.getLocation().add(0.5, 0.5, 0.5);
                plugin.safe(p, "menú de curtiduría", () -> ForgeMenu.open(plugin, p, "curtideria", center, null));
            } else {
                status(p, t);
            }
            return;
        }
        switch (t.stage) {
            case TANNED -> {
                t.stage = DRYING;
                t.ticks = 0;
                p.playSound(t.loc(), Sound.BLOCK_WOOD_PLACE, 0.9f, 0.8f);
                bar(p, "curtiduria_fisica.colgando");
                refresh(t);
                save(t);
            }
            case DONE -> collect(p, t);
            default -> status(p, t);
        }
    }

    private void addHide(Player p, Tannery t, ItemStack hand, String id) {
        if (t.stage != EMPTY && t.stage != RAW) { bar(p, "curtiduria_fisica.ocupado"); return; }
        int need = HIDE_LEVEL.getOrDefault(id, 1);
        if (!p.hasPermission("forja.admin") && plugin.professions.get(p).level(Profession.CURTIDURIA) < need) {
            plugin.lang.send(p, "curtiduria_fisica.nivel", "nivel", need, "piel", hideName(id));
            return;
        }
        if (t.stage == RAW && !id.equals(t.hide)) { bar(p, "curtiduria_fisica.otra_piel"); return; }
        if (t.stage == RAW && t.hits > 0) { bar(p, "curtiduria_fisica.ya_trabajada"); return; }
        if (t.count >= capacity()) { bar(p, "curtiduria_fisica.mesa_llena"); return; }
        consumeOne(p, hand);
        t.stage = RAW;
        t.hide = id;
        t.count++;
        t.hits = 0;
        p.playSound(t.loc(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.9f, 1.0f);
        bar(p, "curtiduria_fisica.piel_puesta", "n", t.count, "max", capacity(), "piel", hideName(id));
        refresh(t);
        save(t);
    }

    private void work(Player p, Tannery t, ItemStack tool, boolean flesh) {
        int needed = Math.max(1, cfgInt(flesh ? "golpes_descarnar" : "golpes_raspar", flesh ? 6 : 5));
        t.hits++;
        wear(p, tool);
        Location at = t.loc().add(0.25, 0.15, 0);
        if (flesh) {
            p.playSound(at, Sound.ENTITY_SHEEP_SHEAR, 0.9f, 1.0f + rnd.nextFloat() * 0.3f);
            ItemStack bit = plugin.items.basic(t.hide, 1);
            at.getWorld().spawnParticle(Particle.ITEM, at, 6, .1, .05, .1, .03, bit);
            at.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, at, 1, .1, .05, .1, 0);
        } else {
            p.playSound(at, Sound.ITEM_BRUSH_BRUSHING_GENERIC, 0.9f, 1.0f);
            at.getWorld().spawnParticle(Particle.CLOUD, at, 3, .12, .02, .12, .01);
        }
        if (t.hits >= needed) {
            t.hits = 0;
            t.stage = flesh ? FLESHED : SCRAPED;
            p.playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.3f);
            bar(p, flesh ? "curtiduria_fisica.descarnada" : "curtiduria_fisica.raspada");
            refresh(t);
        } else {
            bar(p, flesh ? "curtiduria_fisica.descarnando" : "curtiduria_fisica.raspando", "n", t.hits, "max", needed);
        }
        save(t);
    }

    private void startBath(Player p, Tannery t, ItemStack hand, int stage, String bath) {
        consumeOne(p, hand);
        t.stage = stage;
        t.ticks = 0;
        p.playSound(t.loc(), Sound.ITEM_BUCKET_EMPTY, 0.8f, 1.1f);
        t.loc().getWorld().spawnParticle(Particle.SPLASH, t.loc().add(-0.23, 0.35, -0.03), 12, .15, .05, .15, .05);
        bar(p, "curtiduria_fisica.cuba_" + bath);
        refresh(t);
        save(t);
    }

    private void collect(Player p, Tannery t) {
        int n = t.count;
        ItemStack out = leather(p, n);
        t.stage = EMPTY;
        t.count = 0;
        t.hide = null;
        t.hits = 0;
        t.ticks = 0;
        plugin.forge.give(p, out);
        int xp = Math.max(0, cfgInt("xp_por_piel", 12)) * n;
        if (xp > 0) plugin.professions.addXp(p, Profession.CURTIDURIA, xp);
        p.playSound(t.loc(), Sound.ENTITY_VILLAGER_WORK_LEATHERWORKER, 1.0f, 1.0f);
        bar(p, "curtiduria_fisica.recogido", "n", n);
        refresh(t);
        save(t);
    }

    /** Cuero curtido con la calidad que corresponde al nivel de curtidor (sin jugador: calidad común). */
    private ItemStack leather(Player p, int n) {
        ItemStack st = plugin.items.basic("cuero_curtido", n);
        CustomItem ci = plugin.items.get("cuero_curtido");
        if (ci == null) return st;
        Quality q = Quality.COMUN;
        if (p != null) {
            int level = plugin.professions.get(p).level(Profession.CURTIDURIA);
            List<Integer> mins = plugin.getConfig().getIntegerList("calidad.nivel_min");
            int idx = 1;
            for (int i = 0; i < mins.size(); i++) if (mins.get(i) <= level) idx = i;
            double r = rnd.nextDouble();
            if (r < plugin.getConfig().getDouble("curtiduria_fisica.prob_calidad_baja", 0.15)) idx--;
            q = Quality.of(idx);
        }
        ItemMeta m = st.getItemMeta();
        m.getPersistentDataContainer().set(Keys.QUALITY, PersistentDataType.INTEGER, q.ordinal());
        m.displayName(Lang.line(q.tag + Lang.esc(ci.nombre)));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        if (m.lore() != null) lore.addAll(m.lore());
        lore.add(Lang.line("<gray>Calidad: " + q.tag + q.display));
        m.lore(lore);
        st.setItemMeta(m);
        return st;
    }

    private void status(Player p, Tannery t) {
        switch (t.stage) {
            case EMPTY -> bar(p, "curtiduria_fisica.vacia");
            case RAW -> bar(p, "curtiduria_fisica.estado_cruda", "n", t.count, "piel", hideName(t.hide));
            case FLESHED -> bar(p, "curtiduria_fisica.estado_descarnada");
            case SCRAPED -> bar(p, "curtiduria_fisica.estado_raspada");
            case LIMING, TANNING, DRYING -> {
                int total = Math.max(1, stageTicks(t.stage));
                bar(p, "curtiduria_fisica.estado_proceso", "proceso", t.stage == LIMING ? "Baño de cal" : t.stage == TANNING ? "Baño de tanino" : "Secado",
                        "pct", Math.min(100, t.ticks * 100 / total));
            }
            case LIMED -> bar(p, "curtiduria_fisica.estado_limed");
            case TANNED -> bar(p, "curtiduria_fisica.estado_tanned");
            case DONE -> bar(p, "curtiduria_fisica.estado_listo", "n", t.count);
            default -> { }
        }
    }

    private static void consumeOne(Player p, ItemStack hand) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    private static void wear(Player p, ItemStack tool) {
        if (p.getGameMode() == GameMode.CREATIVE) return;
        ItemMeta m = tool.getItemMeta();
        if (m instanceof Damageable d && tool.getType().getMaxDurability() > 0) {
            d.setDamage(d.getDamage() + 1);
            tool.setItemMeta(m);
            if (d.getDamage() >= tool.getType().getMaxDurability()) {
                p.getInventory().setItemInMainHand(null);
                p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
            }
        }
    }

    // ------------------------------------------------------------------ simulación

    private void tick() {
        for (Map.Entry<String, Tannery> en : map.entrySet()) {
            Tannery t = en.getValue();
            World w = Bukkit.getWorld(t.world);
            if (w == null || !w.isChunkLoaded(t.x >> 4, t.z >> 4)) continue;
            String type = plugin.stations.typeAt(w.getBlockAt(t.x, t.y, t.z));
            if (!"curtideria".equals(type)) {
                clearVisuals(t);
                map.remove(en.getKey());
                t.stage = EMPTY;
                t.count = 0;
                save(t);
                continue;
            }
            try {
                step(t, w);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Error simulando curtiduría en " + t.x + "," + t.y + "," + t.z, ex);
            }
        }
    }

    private void step(Tannery t, World w) {
        boolean broken = t.visuals.isEmpty();
        for (Entity e : t.visuals) if (!e.isValid()) { broken = true; break; }
        if (broken) refresh(t);

        if (t.stage == LIMING || t.stage == TANNING || t.stage == DRYING) {
            t.ticks += TICK;
            Location vat = new Location(w, t.x + 0.27, t.y + 1.5, t.z + 0.47);
            Location rack = new Location(w, t.x + 0.75, t.y + 1.5, t.z + 0.5);
            if (t.ticks % 40 < TICK) {
                if (t.stage == DRYING) w.spawnParticle(Particle.FALLING_DRIPSTONE_WATER, rack.clone().add(0, -0.15, 0), 1, .08, .0, .08, 0);
                else {
                    w.spawnParticle(Particle.BUBBLE_POP, vat.clone().add(0, -0.1, 0), 3, .1, .02, .1, 0);
                    w.playSound(vat, Sound.BLOCK_BREWING_STAND_BREW, 0.15f, 1.3f);
                }
            }
            if (t.ticks >= stageTicks(t.stage)) {
                int next = t.stage == LIMING ? LIMED : t.stage == TANNING ? TANNED : DONE;
                t.stage = next;
                t.ticks = 0;
                w.playSound(t.loc(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.1f);
                String msg = next == LIMED ? "curtiduria_fisica.cal_lista" : next == TANNED ? "curtiduria_fisica.tanino_listo" : "curtiduria_fisica.seca";
                Location l = new Location(w, t.x + 0.5, t.y + 0.5, t.z + 0.5);
                for (Player pl : w.getPlayers()) if (pl.getLocation().distanceSquared(l) <= 144) bar(pl, msg);
                refresh(t);
                save(t);
            }
        } else if (t.stage == DONE && t.ticks++ % 8 == 0) {
            w.spawnParticle(Particle.HAPPY_VILLAGER, new Location(w, t.x + 0.75, t.y + 1.45, t.z + 0.5), 1, .1, .1, .1, 0);
        }
    }

    // ------------------------------------------------------------------ visuales

    private void clearVisuals(Tannery t) {
        for (Entity e : t.visuals) if (e.isValid()) e.remove();
        t.visuals.clear();
    }

    private BlockDisplay block(World w, double cx, double cy, double cz, float sx, float sy, float sz, BlockData d) {
        return w.spawn(new Location(w, cx, cy, cz), BlockDisplay.class, e -> {
            e.setBlock(d);
            e.setPersistent(false);
            e.setInvulnerable(true);
            e.setTransformation(new Transformation(new Vector3f(-sx / 2f, 0, -sz / 2f), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf()));
            e.setViewRange(0.6f);
        });
    }

    private ItemDisplay item(World w, double cx, double cy, double cz, ItemStack it, float scale, float yaw, boolean flat) {
        return w.spawn(new Location(w, cx, cy, cz), ItemDisplay.class, e -> {
            e.setItemStack(it);
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            e.setPersistent(false);
            e.setInvulnerable(true);
            Quaternionf rot = new Quaternionf().rotateY(yaw);
            if (flat) rot.mul(FLAT);
            e.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(scale, scale, scale), new Quaternionf()));
            e.setBrightness(new Display.Brightness(12, 15));
            e.setViewRange(0.6f);
        });
    }

    private void refresh(Tannery t) {
        clearVisuals(t);
        World w = Bukkit.getWorld(t.world);
        if (w == null || !w.isChunkLoaded(t.x >> 4, t.z >> 4)) return;
        double x = t.x, y = t.y + 1.0, z = t.z;

        // Cuba (siempre presente)
        t.visuals.add(block(w, x + 0.27, y, z + 0.47, 0.42f, 0.42f, 0.42f, Material.CAULDRON.createBlockData()));
        if (t.stage == LIMING || t.stage == LIMED || t.stage == TANNING || t.stage == TANNED) {
            boolean lime = t.stage == LIMING || t.stage == LIMED;
            t.visuals.add(block(w, x + 0.27, y + 0.125, z + 0.47, 0.315f, 0.14f, 0.315f,
                    (lime ? Material.WHITE_CONCRETE : Material.BROWN_CONCRETE).createBlockData()));
            ItemStack inside = plugin.items.basic("piel_descarnada", 1);
            for (int i = 0; i < Math.min(t.count, 3); i++) {
                t.visuals.add(item(w, x + 0.27, y + 0.275 + 0.01 * i, z + 0.47, inside, 0.3f, (float) (i * 0.9), true));
            }
        }

        // Zona de trabajo / bastidor de secado
        boolean rack = t.stage == DRYING || t.stage == DONE;
        if (!rack) {
            t.visuals.add(block(w, x + 0.75, y, z + 0.5, 0.42f, 0.04f, 0.5f, Material.OAK_PLANKS.createBlockData()));
            if (t.stage >= RAW && t.stage <= SCRAPED && t.hide != null) {
                String shown = t.stage == RAW ? t.hide : "piel_descarnada";
                ItemStack hide = plugin.items.basic(shown, 1);
                for (int i = 0; i < t.count; i++) {
                    t.visuals.add(item(w, x + 0.75, y + 0.06 + 0.025 * i, z + 0.5, hide, 0.5f, (float) Math.toRadians(90 + i * 15), true));
                }
            }
        } else {
            BlockData wood = Material.OAK_PLANKS.createBlockData();
            t.visuals.add(block(w, x + 0.75, y, z + 0.28, 0.05f, 0.5f, 0.05f, wood));
            t.visuals.add(block(w, x + 0.75, y, z + 0.72, 0.05f, 0.5f, 0.05f, wood));
            t.visuals.add(block(w, x + 0.75, y + 0.47, z + 0.5, 0.05f, 0.05f, 0.5f, wood));
            ItemStack shown = t.stage == DONE ? plugin.items.basic("cuero_curtido", 1) : plugin.items.basic("piel_descarnada", 1);
            t.visuals.add(item(w, x + 0.75, y + 0.26, z + 0.5, shown, 0.5f, (float) Math.toRadians(90), false));
        }
    }

    public int count() {
        return map.size();
    }
}
