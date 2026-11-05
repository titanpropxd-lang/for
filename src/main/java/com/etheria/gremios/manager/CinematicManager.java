package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Territory;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cinemática de inicio de asedio: títulos, sonidos, rayo visual y una cámara que orbita el Nodo.
 * La cámara usa modo espectador; el estado original (modo y posición) se guarda también en el jugador
 * para restaurarlo aunque el servidor se apague a mitad.
 */
public class CinematicManager implements Listener {
    private final GremiosPlugin plugin;
    private final NamespacedKey kSaved;
    private final Map<UUID, String> active = new HashMap<>();

    public CinematicManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.kSaved = plugin.key("cine_saved");
    }

    private String pack(Player p) {
        Location l = p.getLocation();
        return p.getGameMode().name() + ";" + l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch() + ";" + (p.isFlying() ? 1 : 0) + ";" + (p.getAllowFlight() ? 1 : 0);
    }

    private void restore(Player p) {
        String s = p.getPersistentDataContainer().get(kSaved, PersistentDataType.STRING);
        active.remove(p.getUniqueId());
        if (s == null) return;
        p.getPersistentDataContainer().remove(kSaved);
        try {
            String[] a = s.split(";");
            World w = Bukkit.getWorld(a[1]);
            p.setGameMode(GameMode.valueOf(a[0]));
            if (w != null) p.teleport(new Location(w, Double.parseDouble(a[2]), Double.parseDouble(a[3]), Double.parseDouble(a[4]), Float.parseFloat(a[5]), Float.parseFloat(a[6])));
            p.setAllowFlight(a.length > 8 && a[8].equals("1") || p.getGameMode() == GameMode.CREATIVE);
            p.setFlying(a.length > 7 && a[7].equals("1"));
        } catch (Exception ex) {
            p.setGameMode(GameMode.SURVIVAL);
        }
    }

    /** Reproduce la cinemática. attackers/defenders reciben mensajes distintos. */
    public void play(Territory t, Location node, String attackerName, String defenderName, Collection<Player> attackers, Collection<Player> defenders) {
        if (node == null || node.getWorld() == null) return;
        final boolean camera = plugin.getConfig().getBoolean("raid.cinematica.camara", true);
        final int ticks = Math.max(40, plugin.getConfig().getInt("raid.cinematica.segundos", 6) * 20);
        final List<Player> all = new ArrayList<>(attackers);
        for (Player p : defenders) if (!all.contains(p)) all.add(p);
        final World w = node.getWorld();
        final Location center = node.clone().add(0, 2, 0);
        final List<Player> cam = new ArrayList<>();
        if (camera) {
            for (Player p : all) {
                if (p.getWorld() != w || p.isDead() || active.containsKey(p.getUniqueId())) continue;
                if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
                String saved = pack(p);
                p.getPersistentDataContainer().set(kSaved, PersistentDataType.STRING, saved);
                active.put(p.getUniqueId(), saved);
                p.setGameMode(GameMode.SPECTATOR);
                cam.add(p);
            }
        }
        new BukkitRunnable() {
            int i = 0;

            @Override
            public void run() {
                if (i == 0) {
                    w.strikeLightningEffect(center);
                    for (Player p : all) {
                        p.sendTitle("§4§l⚔ ASEDIO", "§7" + t.name, 5, 40, 10);
                        p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1.2f, 1f);
                    }
                } else if (i == 40) {
                    for (Player p : all) {
                        p.sendTitle("§6" + attackerName, "§7contra §a" + defenderName, 5, 35, 10);
                        p.playSound(p.getLocation(), Sound.BLOCK_BELL_USE, 1.5f, 0.6f);
                    }
                    for (int k = 0; k < 24; k++) {
                        double a = k * Math.PI / 12;
                        w.spawnParticle(Particle.FLAME, center.clone().add(Math.cos(a) * 3, 0, Math.sin(a) * 3), 4, 0.05, 0.3, 0.05, 0.02);
                    }
                } else if (i == 80) {
                    for (Player p : attackers) {
                        p.sendTitle("§c§l¡ROMPE EL NODO!", "§7Cañones, arietes y espadas", 5, 35, 15);
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1f);
                    }
                    for (Player p : defenders) {
                        p.sendTitle("§a§l¡DEFENDED EL NODO!", "§7No dejéis que caiga", 5, 35, 15);
                        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 0.6f, 1f);
                    }
                }
                // cámara orbitando
                if (!cam.isEmpty() && i < ticks) {
                    double prog = i / (double) ticks;
                    double ang = prog * Math.PI * 1.6;
                    double radius = 16 - prog * 6;
                    double height = 5 + prog * 4;
                    Location c = center.clone().add(Math.cos(ang) * radius, height, Math.sin(ang) * radius);
                    double dx = center.getX() - c.getX(), dy = center.getY() - c.getY(), dz = center.getZ() - c.getZ();
                    c.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
                    c.setPitch((float) Math.toDegrees(-Math.atan2(dy, Math.sqrt(dx * dx + dz * dz))));
                    for (Player p : cam) if (p.isOnline() && active.containsKey(p.getUniqueId())) p.teleport(c);
                }
                i++;
                if (i >= Math.max(ticks, 100)) {
                    cancel();
                    for (Player p : cam) if (p.isOnline()) restore(p);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    public void restoreAll() {
        for (Player p : Bukkit.getOnlinePlayers()) if (p.getPersistentDataContainer().has(kSaved, PersistentDataType.STRING)) restore(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        restore(e.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (p.getPersistentDataContainer().has(kSaved, PersistentDataType.STRING)) Bukkit.getScheduler().runTaskLater(plugin, () -> restore(p), 5L);
    }
}
