package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** Persistencia de procesos de curtición: se guardan al iniciar y durante el progreso. */
public final class CurtiduriaManager {
    public record Job(String recipe, String world, int x, int y, int z, int total, int elapsed, Map<String,Integer> consumed) {}
    private final ForjaPlugin plugin;

    public CurtiduriaManager(ForjaPlugin plugin) { this.plugin = plugin; }

    public void save(UUID uuid, Recipe recipe, Location anchor, int total, int elapsed, Map<String,Integer> consumed) {
        if (anchor == null || anchor.getWorld() == null) return;
        String encoded = encode(consumed);
        String world = anchor.getWorld().getName();
        int x=anchor.getBlockX(), y=anchor.getBlockY(), z=anchor.getBlockZ();
        plugin.db.run(c -> {
            try (PreparedStatement ps=c.prepareStatement("INSERT INTO curturing_jobs(uuid,recipe,world,x,y,z,total,elapsed,consumed) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET recipe=excluded.recipe,world=excluded.world,x=excluded.x,y=excluded.y,z=excluded.z,total=excluded.total,elapsed=excluded.elapsed,consumed=excluded.consumed")) {
                ps.setString(1,uuid.toString()); ps.setString(2,recipe.id); ps.setString(3,world); ps.setInt(4,x); ps.setInt(5,y); ps.setInt(6,z); ps.setInt(7,total); ps.setInt(8,elapsed); ps.setString(9,encoded); ps.executeUpdate();
            }
        });
    }

    public void updateProgress(UUID uuid, int elapsed) {
        plugin.db.run(c -> { try (PreparedStatement ps=c.prepareStatement("UPDATE curturing_jobs SET elapsed=? WHERE uuid=?")) { ps.setInt(1,elapsed); ps.setString(2,uuid.toString()); ps.executeUpdate(); } });
    }

    public void delete(UUID uuid) {
        plugin.db.run(c -> { try (PreparedStatement ps=c.prepareStatement("DELETE FROM curturing_jobs WHERE uuid=?")) { ps.setString(1,uuid.toString()); ps.executeUpdate(); } });
    }

    /** Borrado confirmado antes de entregar el resultado o devolver ingredientes (evita duplicados tras un crash). */
    public void deleteSync(UUID uuid) {
        try {
            plugin.db.query(c -> { try (PreparedStatement ps=c.prepareStatement("DELETE FROM curturing_jobs WHERE uuid=?")) { ps.setString(1,uuid.toString()); ps.executeUpdate(); } return null; }).join();
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE,"No se pudo cerrar el trabajo persistente de curtición",ex);
        }
    }

    public void restore(ForgeMenu menu) {
        Player player=menu.player();
        menu.setRestoring(true);
        plugin.db.query(c -> {
            try (PreparedStatement ps=c.prepareStatement("SELECT recipe,world,x,y,z,total,elapsed,consumed FROM curturing_jobs WHERE uuid=?")) {
                ps.setString(1,player.getUniqueId().toString());
                try (ResultSet rs=ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new Job(rs.getString(1),rs.getString(2),rs.getInt(3),rs.getInt(4),rs.getInt(5),rs.getInt(6),rs.getInt(7),decode(rs.getString(8)));
                }
            }
        }).whenComplete((job,error) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (error != null) { plugin.getLogger().log(Level.SEVERE,"No se pudo recuperar el proceso de curtición",error); menu.setRestoring(false); return; }
            if (!player.isOnline() || plugin.menus.get(player.getUniqueId()) != menu) return;
            if (job == null) { menu.setRestoring(false); return; }
            if (!menu.restoreJob(job)) {
                menu.setRestoring(false);
                if (plugin.recipes.get(job.recipe()) == null) {
                    plugin.forge.refund(player, job.consumed());
                    deleteSync(player.getUniqueId());
                    plugin.lang.send(player,"curtiduria.proceso_no_recuperable");
                } else {
                    plugin.lang.send(player,"curtiduria.proceso_en_otro_bastidor");
                }
            }
        }));
    }

    private String encode(Map<String,Integer> map) {
        StringBuilder b=new StringBuilder();
        for (Map.Entry<String,Integer> e:map.entrySet()) { if (b.length()>0) b.append(';'); b.append(e.getKey()).append('=').append(e.getValue()); }
        return b.toString();
    }
    private Map<String,Integer> decode(String raw) {
        Map<String,Integer> out=new LinkedHashMap<>();
        if (raw==null || raw.isBlank()) return out;
        for (String part:raw.split(";")) { int i=part.lastIndexOf('='); if (i<=0) continue; try { out.put(part.substring(0,i),Integer.parseInt(part.substring(i+1))); } catch(NumberFormatException ignored) {} }
        return out;
    }
}
