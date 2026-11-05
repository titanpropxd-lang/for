package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.ItemBuilder;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Territorios: regiones, propiedad, daños de asedio, puntos de aparición. */
public class TerritoryManager {
    public static class DamageRecord {
        public final int x, y, z;
        public final String data;

        public DamageRecord(int x, int y, int z, String data) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.data = data;
        }
    }

    private final GremiosPlugin plugin;
    private final File file;
    private final File damageDir;

    private final Map<String, Territory> territories = new LinkedHashMap<>();
    private final Map<String, Map<Long, DamageRecord>> damage = new HashMap<>();
    private final Map<String, Boolean> damageDirty = new HashMap<>();
    private final Map<UUID, Location[]> selection = new HashMap<>();

    public TerritoryManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "territories.yml");
        this.damageDir = new File(plugin.getDataFolder(), "damage");
        damageDir.mkdirs();
        load();
    }

    // ------------------------------------------------------------------ consulta

    public Collection<Territory> all() {
        return territories.values();
    }

    public Territory get(String id) {
        return id == null ? null : territories.get(id.toLowerCase());
    }

    public Territory at(Location l) {
        if (l == null || l.getWorld() == null) return null;
        int x = l.getBlockX(), y = l.getBlockY(), z = l.getBlockZ();
        String w = l.getWorld().getName();
        for (Territory t : territories.values()) {
            if (t.world.equals(w) && t.contains(x, y, z)) return t;
        }
        return null;
    }

    public Territory ownedBy(Guild g) {
        return g == null ? null : get(g.territoryId);
    }

    public boolean busy(Territory t) {
        return t.resetting || plugin.raids.get(t.id) != null;
    }

    // ------------------------------------------------------------------ selección con varita

    public ItemStack wand() {
        return new ItemBuilder(Material.BLAZE_ROD)
                .name("§6Varita de Territorios")
                .lore("§7Clic izquierdo: posición 1", "§7Clic derecho: posición 2")
                .tag(plugin.key("wand"), "1")
                .glow()
                .build();
    }

    public boolean isWand(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(plugin.key("wand"), PersistentDataType.STRING);
    }

    public Location[] sel(UUID u) {
        return selection.computeIfAbsent(u, k -> new Location[2]);
    }

    // ------------------------------------------------------------------ creación

    /** Crea un territorio. Devuelve texto de error o null. */
    public String create(String id, String name, Location a, Location b) {
        id = id.toLowerCase();
        if (!id.matches("[a-z0-9_\\-]{2,24}")) return "El id debe tener 2-24 caracteres (a-z, 0-9, _ o -).";
        if (territories.containsKey(id)) return "Ya existe un territorio con ese id.";
        if (a == null || b == null || a.getWorld() == null || !a.getWorld().equals(b.getWorld())) return "Marca las dos posiciones con la varita en el mismo mundo.";
        Territory t = new Territory(id, name);
        t.world = a.getWorld().getName();
        t.minX = Math.min(a.getBlockX(), b.getBlockX());
        t.maxX = Math.max(a.getBlockX(), b.getBlockX());
        t.minY = Math.min(a.getBlockY(), b.getBlockY());
        t.maxY = Math.max(a.getBlockY(), b.getBlockY());
        t.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        t.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        long max = plugin.getConfig().getLong("territorio.max-volumen", 12_000_000L);
        if (t.volume() > max) return "El territorio es demasiado grande (" + Msg.num(t.volume()) + " bloques; máximo " + Msg.num(max) + ").";
        for (Territory o : territories.values()) {
            if (!o.world.equals(t.world)) continue;
            boolean overlap = t.minX <= o.maxX && t.maxX >= o.minX && t.minY <= o.maxY && t.maxY >= o.minY && t.minZ <= o.maxZ && t.maxZ >= o.minZ;
            if (overlap) return "Se solapa con el territorio '" + o.id + "'.";
        }
        t.price = plugin.getConfig().getLong("territorio.precio-defecto", 64);
        territories.put(id, t);
        save();
        return null;
    }

    public void delete(Territory t) {
        if (t.ownerGuildId != null) release(t, false, null);
        plugin.npcs.removeAll(t);
        territories.remove(t.id);
        damage.remove(t.id);
        new File(damageDir, t.id + ".dat").delete();
        plugin.snapshots.delete(t);
        save();
    }

    // ------------------------------------------------------------------ precios

    public long upgradeCost(Territory t, int toLevel) {
        if (toLevel == 2) return t.priceFort >= 0 ? t.priceFort : plugin.getConfig().getLong("territorio.mejora-fortaleza", 128);
        if (toLevel == 3) return t.priceRein >= 0 ? t.priceRein : plugin.getConfig().getLong("territorio.mejora-reinado", 512);
        return t.price;
    }

    public int upgradeMinGuildLevel(int toLevel) {
        return toLevel == 2 ? plugin.getConfig().getInt("territorio.nivel-gremio-fortaleza", 5)
                : plugin.getConfig().getInt("territorio.nivel-gremio-reinado", 12);
    }

    // ------------------------------------------------------------------ propiedad

    public void claim(Territory t, Guild g) {
        t.ownerGuildId = g.id;
        g.territoryId = t.id;
        g.territoryLevel = 1;
        g.nextUpkeep = System.currentTimeMillis() + plugin.guilds.periodMs();
        g.upkeepMissed = 0;
        plugin.npcs.sync(t);
        plugin.banners.refresh(t);
        save();
    }

    public void upgrade(Guild g) {
        Territory t = get(g.territoryId);
        if (t == null || g.territoryLevel >= 3) return;
        g.territoryLevel++;
        plugin.npcs.sync(t);
        plugin.guilds.save();
    }

    /** El gremio pierde el territorio por impagos. */
    public void abandon(Guild g) {
        Territory t = get(g.territoryId);
        if (t != null) release(t, true, null);
        else {
            g.territoryId = null;
            g.territoryLevel = 0;
        }
        plugin.guilds.save();
    }

    /** Libera el territorio; si reset, lo restaura desde el snapshot (vacía cofres). */
    public void release(Territory t, boolean reset, Runnable done) {
        Guild g = plugin.guilds.byId(t.ownerGuildId);
        if (g != null) {
            g.territoryId = null;
            g.territoryLevel = 0;
        }
        t.ownerGuildId = null;
        plugin.npcs.removeAll(t);
        plugin.siege.removeUnitsIn(t);
        plugin.turrets.removeIn(t);
        plugin.banners.refreshLater(t);
        clearDamage(t);
        plugin.guilds.save();
        if (reset) {
            resetTerritory(t, done);
        } else {
            save();
            if (done != null) done.run();
        }
    }

    /** Restaura el terreno desde el snapshot. */
    public void resetTerritory(Territory t, Runnable done) {
        if (!plugin.snapshots.has(t)) {
            plugin.getLogger().warning("El territorio " + t.id + " no tiene snapshot; no se puede restaurar.");
            t.resetting = false;
            save();
            if (done != null) done.run();
            return;
        }
        t.resetting = true;
        save();
        tpOut(t);
        plugin.snapshots.restore(t, () -> {
            t.resetting = false;
            plugin.gates.restoreAll(t);
            plugin.banners.refresh(t);
            save();
            if (done != null) done.run();
        });
    }

    public void resumeResets() {
        for (Territory t : territories.values()) {
            if (t.resetting) {
                plugin.getLogger().info("Reanudando restauración de " + t.id);
                resetTerritory(t, null);
            }
        }
    }

    // ------------------------------------------------------------------ ubicaciones

    public Location spawn(Territory t) {
        World w = t.bukkitWorld();
        if (w == null) return null;
        if (t.spawn != null) return new Location(w, t.spawn[0], t.spawn[1], t.spawn[2], (float) t.spawn[3], 0f);
        int x = t.maxX + 3;
        int z = (t.minZ + t.maxZ) / 2;
        int y = w.getHighestBlockYAt(x, z) + 1;
        return new Location(w, x + 0.5, y, z + 0.5);
    }

    public Location nodeLoc(Territory t) {
        World w = t.bukkitWorld();
        if (w == null) return null;
        if (t.node != null) return new Location(w, t.node[0], t.node[1], t.node[2]);
        Location c = t.center();
        int y = Math.min(t.maxY, w.getHighestBlockYAt(c.getBlockX(), c.getBlockZ()) + 1);
        return new Location(w, c.getX(), y, c.getZ());
    }

    public void tpOut(Territory t) {
        Location out = spawn(t);
        if (out == null) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.bypass.contains(p.getUniqueId())) continue;
            if (t.contains(p.getLocation())) {
                p.teleport(out);
                Msg.to(p, "§7Has sido sacado del territorio.");
            }
        }
    }

    // ------------------------------------------------------------------ daños de asedio

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFFL);
    }

    public Map<Long, DamageRecord> damage(Territory t) {
        return damage.computeIfAbsent(t.id, k -> new LinkedHashMap<>());
    }

    public int damageCount(Territory t) {
        return damage.getOrDefault(t.id, Map.of()).size();
    }

    public boolean isDamaged(Territory t) {
        return damageCount(t) > 0;
    }

    /** Registra un bloque destruido por asedio (conserva el original más antiguo). */
    public void recordDamage(Territory t, int x, int y, int z, String originalData) {
        Map<Long, DamageRecord> m = damage(t);
        long k = key(x, y, z);
        if (!m.containsKey(k)) m.put(k, new DamageRecord(x, y, z, originalData));
        damageDirty.put(t.id, true);
    }

    public void removeDamage(Territory t, int x, int y, int z) {
        damage(t).remove(key(x, y, z));
        damageDirty.put(t.id, true);
    }

    public void clearDamage(Territory t) {
        damage.remove(t.id);
        damageDirty.remove(t.id);
        new File(damageDir, t.id + ".dat").delete();
    }

    public void saveDamage(Territory t) {
        Map<Long, DamageRecord> m = damage.get(t.id);
        File f = new File(damageDir, t.id + ".dat");
        if (m == null || m.isEmpty()) {
            f.delete();
            damageDirty.remove(t.id);
            return;
        }
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new FileOutputStream(f)))) {
            out.writeInt(m.size());
            for (DamageRecord r : m.values()) {
                out.writeInt(r.x);
                out.writeInt(r.y);
                out.writeInt(r.z);
                out.writeUTF(r.data);
            }
            damageDirty.remove(t.id);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar daños de " + t.id + ": " + e.getMessage());
        }
    }

    private void loadDamage(Territory t) {
        File f = new File(damageDir, t.id + ".dat");
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new FileInputStream(f)))) {
            int n = in.readInt();
            Map<Long, DamageRecord> m = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int x = in.readInt(), y = in.readInt(), z = in.readInt();
                String d = in.readUTF();
                m.put(key(x, y, z), new DamageRecord(x, y, z, d));
            }
            damage.put(t.id, m);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo leer daños de " + t.id + ": " + e.getMessage());
        }
    }

    public void flushDirty() {
        for (String id : new ArrayList<>(damageDirty.keySet())) {
            Territory t = territories.get(id);
            if (t != null) saveDamage(t);
        }
    }

    // ------------------------------------------------------------------ persistencia

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("territories");
        for (Territory t : territories.values()) {
            ConfigurationSection s = root.createSection(t.id);
            s.set("name", t.name);
            s.set("world", t.world);
            s.set("min", List.of(t.minX, t.minY, t.minZ));
            s.set("max", List.of(t.maxX, t.maxY, t.maxZ));
            if (t.spawn != null) s.set("spawn", toList(t.spawn));
            if (t.node != null) s.set("node", toList(t.node));
            ConfigurationSection n = s.createSection("npcs");
            for (Map.Entry<String, double[]> e : t.npcPos.entrySet()) n.set(e.getKey(), toList(e.getValue()));
            ConfigurationSection nu = s.createSection("npcUuid");
            for (Map.Entry<String, String> e : t.npcUuid.entrySet()) nu.set(e.getKey(), e.getValue());
            s.set("price", t.price);
            s.set("priceFort", t.priceFort);
            s.set("priceRein", t.priceRein);
            s.set("owner", t.ownerGuildId);
            s.set("resetting", t.resetting);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar territories.yml: " + e.getMessage());
        }
    }

    private static List<Double> toList(double[] a) {
        List<Double> l = new ArrayList<>();
        for (double d : a) l.add(d);
        return l;
    }

    private static double[] toArr(List<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) a[i] = l.get(i);
        return a;
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("territories");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Territory t = new Territory(id, s.getString("name", id));
                t.world = s.getString("world");
                List<Integer> mn = s.getIntegerList("min");
                List<Integer> mx = s.getIntegerList("max");
                t.minX = mn.get(0);
                t.minY = mn.get(1);
                t.minZ = mn.get(2);
                t.maxX = mx.get(0);
                t.maxY = mx.get(1);
                t.maxZ = mx.get(2);
                if (s.isList("spawn")) t.spawn = toArr(s.getDoubleList("spawn"));
                if (s.isList("node")) t.node = toArr(s.getDoubleList("node"));
                ConfigurationSection n = s.getConfigurationSection("npcs");
                if (n != null) for (String r : n.getKeys(false)) t.npcPos.put(r, toArr(n.getDoubleList(r)));
                ConfigurationSection nu = s.getConfigurationSection("npcUuid");
                if (nu != null) for (String r : nu.getKeys(false)) t.npcUuid.put(r, nu.getString(r));
                t.price = s.getLong("price", 64);
                t.priceFort = s.getLong("priceFort", -1);
                t.priceRein = s.getLong("priceRein", -1);
                t.ownerGuildId = s.getString("owner");
                t.resetting = s.getBoolean("resetting");
                territories.put(id, t);
                loadDamage(t);
            } catch (Exception ex) {
                plugin.getLogger().warning("Territorio '" + id + "' dañado en territories.yml: " + ex.getMessage());
            }
        }
    }

    public void saveAll() {
        save();
        flushDirty();
    }
}
