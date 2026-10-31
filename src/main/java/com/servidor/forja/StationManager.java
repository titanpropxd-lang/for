package com.servidor.forja;

import org.bukkit.block.Block;

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

    public StationManager(ForjaPlugin plugin) {
        this.plugin = plugin;
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

    public void add(Block b, String type) {
        map.put(key(b), type);
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
}
