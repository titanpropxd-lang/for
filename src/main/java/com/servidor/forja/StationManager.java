package com.servidor.forja;

import org.bukkit.block.Block;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemDisplay.ItemDisplayTransform;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Registro persistente de bloques que son estaciones de trabajo (evita que cualquier yunque sirva). */
public final class StationManager {
    private final ForjaPlugin plugin;
    private final Map<String, String> map = new ConcurrentHashMap<>();
    private NamespacedKey visualKey;

    public StationManager(ForjaPlugin plugin) {
        this.plugin = plugin;
        this.visualKey = new NamespacedKey(plugin, "station_visual");
    }

    private static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private static String key(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    public void load() {
        map.clear();
        try {
            Map<String, String> m = plugin.db.query(c -> {
                Map<String, String> out = new HashMap<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT world,x,y,z,type FROM stations")) {
                    while (rs.next()) out.put(key(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)), rs.getString(5));
                }
                return out;
            }).join();
            map.putAll(m);
            // Reconstruye el modelo en chunks que ya estén cargados; los ItemDisplay
            // existentes se conservan en el propio mundo y no se duplican.
            for (org.bukkit.World world : Bukkit.getWorlds()) {
                for (String k : map.keySet()) {
                    String[] a = k.split(";", 4);
                    if (a.length != 4 || !a[0].equals(world.getName())) continue;
                    int x = Integer.parseInt(a[1]), z = Integer.parseInt(a[3]);
                    if (world.isChunkLoaded(x >> 4, z >> 4)) ensureVisual(world.getBlockAt(x, Integer.parseInt(a[2]), z));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "No se pudieron cargar las estaciones", e);
        }
    }

    public int count() {
        return map.size();
    }

    public String typeAt(Block b) {
        return map.get(key(b));
    }

    public Map<String, String> snapshot() {
        return new HashMap<>(map);
    }

    public void add(Block b, String type) {
        map.put(key(b), type);
        if (isModeled(type)) ensureVisual(b);
        if (type != null && type.startsWith("fundicion") && plugin.foundry != null) plugin.foundry.ensure(b, type);
        if ("curtideria".equals(type) && plugin.tannery != null) plugin.tannery.ensure(b);
        final String w = b.getWorld().getName();
        final int x = b.getX(), y = b.getY(), z = b.getZ();
        plugin.db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO stations(world,x,y,z,type) VALUES(?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET type=excluded.type")) {
                ps.setString(1, w);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setString(5, type);
                ps.executeUpdate();
            }
        });
    }

    public void remove(Block b) {
        if (map.remove(key(b)) == null) return;
        removeVisual(b);
        if (plugin.foundry != null) plugin.foundry.clear(b);
        if (plugin.tannery != null) plugin.tannery.clear(b);
        if (plugin.stationModels != null) plugin.stationModels.remove(b);
        final String w = b.getWorld().getName();
        final int x = b.getX(), y = b.getY(), z = b.getZ();
        plugin.db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM stations WHERE world=? AND x=? AND y=? AND z=?")) {
                ps.setString(1, w);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.executeUpdate();
            }
        });
    }

    private boolean isModeled(String type) { return type != null && plugin.items.get("estacion_" + type) != null; }

    /** Elimina el modelo antiguo (ItemDisplay) cuando la estación pasa a dibujarse con BetterModel. */
    public void hideLegacyVisual(Block b) {
        removeVisual(b);
    }

    /** Modelos 3D de estaciones anclados al bloque físico. */
    private void ensureVisual(Block b) {
        String type = typeAt(b);
        if (!isModeled(type)) return;
        if (plugin.stationModels != null && plugin.stationModels.handles(type)) return; // lo dibuja BetterModel
        String id = key(b);
        for (Entity entity : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 1.2, 2.0, 1.2)) {
            if (entity instanceof ItemDisplay display && id.equals(display.getPersistentDataContainer().get(visualKey, PersistentDataType.STRING))) return;
        }
        ItemDisplay display = b.getWorld().spawn(b.getLocation().add(0.5, 0.25, 0.5), ItemDisplay.class, d -> {
            d.setItemStack(plugin.items.basic("estacion_" + type, 1));
            d.setItemDisplayTransform(ItemDisplayTransform.FIXED);
            d.setPersistent(true);
            d.setInvulnerable(true);
            d.getPersistentDataContainer().set(visualKey, PersistentDataType.STRING, id);
            float scale = type.equals("fundicion_t2") ? 1.25f : (type.equals("curtideria") ? 1.15f : 1.0f);
            d.setTransformation(new Transformation(new Vector3f(0, 0, 0), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    private void removeVisual(Block b) {
        String id = key(b);
        for (Entity entity : b.getWorld().getNearbyEntities(b.getLocation().add(0.5, 0.5, 0.5), 1.5, 2.5, 1.5)) {
            if (entity instanceof ItemDisplay display && id.equals(display.getPersistentDataContainer().get(visualKey, PersistentDataType.STRING))) display.remove();
        }
    }
}
