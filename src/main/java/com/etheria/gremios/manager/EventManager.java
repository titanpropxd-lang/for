package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.EventDef;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Ravager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Eventos de territorio definidos por el admin:
 *  - KOTH: los gremios pelean por mantener una zona; gana quien acumule el tiempo exigido.
 *  - JEFE: un jefe aparece en la zona; el botín se reparte entre gremios según el daño.
 * El admin elige zona, duración, tiempos, recompensas, gremios permitidos y frecuencia.
 */
public class EventManager implements Listener {
    public static class Active {
        public EventDef def;
        public long startAt, endAt;
        public final Map<String, Integer> hold = new LinkedHashMap<>();
        public final Map<String, Double> damage = new LinkedHashMap<>();
        public BossBar bar;
        public UUID boss;
        public String status = "";
    }

    private final GremiosPlugin plugin;
    private final File file;
    private final Map<String, EventDef> defs = new LinkedHashMap<>();
    private final Map<String, Active> active = new HashMap<>();
    private final NamespacedKey kBoss;

    public EventManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "eventos.yml");
        this.kBoss = plugin.key("event_boss");
        load();
    }

    public Collection<EventDef> all() {
        return defs.values();
    }

    public EventDef get(String id) {
        return id == null ? null : defs.get(id.toLowerCase(Locale.ROOT));
    }

    public Active running(EventDef d) {
        return active.get(d.id);
    }

    public Collection<Active> runningAll() {
        return active.values();
    }

    // ------------------------------------------------------------------ crear / editar

    public String create(String id, EventDef.Type type, String name) {
        id = id.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]{1,24}")) return "Id inválido (letras, números, _ y -).";
        if (defs.containsKey(id)) return "Ya existe un evento con ese id.";
        EventDef d = new EventDef(id, name);
        d.type = type;
        defs.put(id, d);
        save();
        return null;
    }

    public boolean delete(String id) {
        EventDef d = get(id);
        if (d == null) return false;
        Active a = active.get(d.id);
        if (a != null) finish(a, null, "El evento fue eliminado.");
        defs.remove(d.id);
        save();
        return true;
    }

    public String setZone(EventDef d, Location a, Location b) {
        if (a == null || b == null) return "Selecciona la zona con la varita (pos 1 y pos 2).";
        if (a.getWorld() != b.getWorld()) return "Las dos posiciones deben estar en el mismo mundo.";
        d.world = a.getWorld().getName();
        d.minX = Math.min(a.getBlockX(), b.getBlockX());
        d.maxX = Math.max(a.getBlockX(), b.getBlockX());
        d.minY = Math.min(a.getBlockY(), b.getBlockY());
        d.maxY = Math.max(a.getBlockY(), b.getBlockY());
        d.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        d.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        d.hasZone = true;
        save();
        return null;
    }

    /** Cambia un campo por nombre (lo usan el comando y el menú). Devuelve error o null. */
    public String setField(EventDef d, String field, String value) {
        try {
            switch (field.toLowerCase(Locale.ROOT)) {
                case "nombre" -> d.name = value;
                case "tipo" -> d.type = EventDef.Type.valueOf(value.toUpperCase(Locale.ROOT));
                case "duracion" -> d.durationMin = Math.max(1, Math.min(1440, Integer.parseInt(value)));
                case "mantener" -> d.holdSeconds = Math.max(5, Math.min(86400, Integer.parseInt(value)));
                case "xp" -> d.rewardXp = Math.max(0, Long.parseLong(value));
                case "coronas" -> d.rewardCrowns = Math.max(0, Long.parseLong(value));
                case "cada" -> {
                    d.intervalMin = Math.max(0, Integer.parseInt(value));
                    d.nextStart = d.intervalMin > 0 ? System.currentTimeMillis() + d.intervalMin * 60_000L : 0;
                }
                case "nivel" -> d.minGuildLevel = Math.max(1, Math.min(Guild.MAX_LEVEL, Integer.parseInt(value)));
                case "jefe" -> d.bossHp = Math.max(20, Math.min(100000, Double.parseDouble(value)));
                case "activo" -> d.enabled = value.equalsIgnoreCase("si") || value.equalsIgnoreCase("true");
                case "gremios" -> {
                    d.allowed.clear();
                    if (!value.equalsIgnoreCase("todos")) {
                        for (String n : value.split(",")) {
                            Guild g = plugin.guilds.byName(n.trim());
                            if (g == null) return "Gremio no encontrado: " + n.trim();
                            d.allowed.add(g.id);
                        }
                    }
                }
                default -> {
                    return "Campo desconocido: " + field;
                }
            }
        } catch (IllegalArgumentException ex) {
            return "Valor inválido: " + value;
        }
        save();
        return null;
    }

    // ------------------------------------------------------------------ iniciar / terminar

    public boolean eligible(EventDef d, Guild g) {
        if (g == null) return false;
        if (g.level < d.minGuildLevel) return false;
        return d.allowed.isEmpty() || d.allowed.contains(g.id);
    }

    public String start(EventDef d) {
        if (!d.hasZone) return "El evento no tiene zona (usa la varita y 'zona').";
        if (active.containsKey(d.id)) return "El evento ya está en marcha.";
        World w = Bukkit.getWorld(d.world);
        if (w == null) return "El mundo '" + d.world + "' no está cargado.";
        Active a = new Active();
        a.def = d;
        a.startAt = System.currentTimeMillis();
        a.endAt = a.startAt + d.durationMin * 60_000L;
        a.bar = Bukkit.createBossBar("", d.type == EventDef.Type.KOTH ? BarColor.YELLOW : BarColor.PURPLE, BarStyle.SEGMENTED_10);
        active.put(d.id, a);
        if (d.type == EventDef.Type.JEFE) {
            String err = spawnBoss(a, w);
            if (err != null) {
                active.remove(d.id);
                a.bar.removeAll();
                return err;
            }
        }
        if (d.intervalMin > 0) d.nextStart = System.currentTimeMillis() + d.intervalMin * 60_000L;
        String who = d.allowed.isEmpty() ? "todos los gremios (nivel " + d.minGuildLevel + "+)" : "gremios invitados";
        String what = d.type == EventDef.Type.KOTH
                ? "Mantén la zona durante §f" + Msg.dur(d.holdSeconds * 1000L) + "§7 para ganar."
                : "¡Derrotad al jefe entre todos! El botín se reparte según el daño.";
        Bukkit.broadcastMessage(Msg.PREFIX + "§6§l⚑ EVENTO: §e" + d.name + " §7| " + what + " §8| §7Participan: " + who
                + " §8| §7Duración: §f" + Msg.dur(d.durationMin * 60_000L) + " §8| §7Zona: §f" + d.world + " " + (d.minX + d.maxX) / 2 + ", " + (d.minZ + d.maxZ) / 2);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Guild g = plugin.guilds.of(p);
            if (eligible(d, g)) {
                p.sendTitle("§6§l⚑ " + d.name, "§7¡El evento ha comenzado!", 10, 60, 20);
                p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
            }
        }
        save();
        return null;
    }

    public String stop(EventDef d) {
        Active a = active.get(d.id);
        if (a == null) return "El evento no está en marcha.";
        finish(a, null, "El evento fue cancelado por un administrador.");
        return null;
    }

    private String spawnBoss(Active a, World w) {
        EventDef d = a.def;
        double cx = (d.minX + d.maxX + 1) / 2.0, cz = (d.minZ + d.maxZ + 1) / 2.0;
        int y = Math.min(d.maxY, Math.max(d.minY, w.getHighestBlockYAt((int) cx, (int) cz) + 1));
        Location l = new Location(w, cx, y, cz);
        l.getChunk().addPluginChunkTicket(plugin);
        Ravager r = w.spawn(l, Ravager.class, e -> {
            e.setCustomName("§5§l" + d.name);
            e.setCustomNameVisible(true);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setCanJoinRaid(false);
            var hp = e.getAttribute(Attribute.GENERIC_MAX_HEALTH);
            if (hp != null) hp.setBaseValue(d.bossHp);
            e.setHealth(Math.min(d.bossHp, e.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue()));
            e.getPersistentDataContainer().set(kBoss, PersistentDataType.STRING, d.id);
        });
        a.boss = r.getUniqueId();
        return null;
    }

    private void finish(Active a, Guild winner, String reason) {
        EventDef d = a.def;
        active.remove(d.id);
        if (a.bar != null) a.bar.removeAll();
        if (a.boss != null) {
            Entity e = Bukkit.getEntity(a.boss);
            if (e != null) {
                e.getLocation().getChunk().removePluginChunkTicket(plugin);
                e.remove();
            }
        }
        if (reason != null) Bukkit.broadcastMessage(Msg.PREFIX + "§6⚑ " + d.name + "§7: " + reason);
        save();
    }

    private void reward(Guild g, long xp, long crowns, String why) {
        if (g == null) return;
        if (xp > 0) plugin.guilds.addXp(g, xp);
        if (crowns > 0) {
            g.bank += crowns;
            g.log("§a+" + Msg.num(crowns) + " §7evento");
        }
        plugin.guilds.broadcast(g, "§6⚑ " + why + " §7Recompensa: §e+" + xp + " XP" + (crowns > 0 ? "§7 y §e" + plugin.currency.fmt(crowns) : "") + "§7.");
        plugin.guilds.save();
    }

    // ------------------------------------------------------------------ bucle (cada segundo)

    public void tick() {
        long now = System.currentTimeMillis();
        for (EventDef d : defs.values()) {
            if (d.enabled && d.intervalMin > 0 && d.hasZone && !active.containsKey(d.id) && d.nextStart > 0 && now >= d.nextStart) {
                String err = start(d);
                if (err != null) {
                    d.nextStart = now + d.intervalMin * 60_000L;
                    plugin.getLogger().warning("Evento '" + d.id + "' no pudo iniciarse: " + err);
                }
            }
        }
        for (Active a : new ArrayList<>(active.values())) {
            if (a.def.type == EventDef.Type.KOTH) tickKoth(a, now);
            else tickBoss(a, now);
        }
    }

    private void tickKoth(Active a, long now) {
        EventDef d = a.def;
        Map<String, List<Player>> inside = new HashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (p.isDead() || !d.contains(p.getLocation())) continue;
            Guild g = plugin.guilds.of(p);
            if (!eligible(d, g)) continue;
            inside.computeIfAbsent(g.id, k -> new ArrayList<>()).add(p);
        }
        String holder = null;
        if (inside.size() == 1) {
            holder = inside.keySet().iterator().next();
            a.hold.merge(holder, 1, Integer::sum);
            a.status = "§aControla: §6" + plugin.guilds.byId(holder).name;
        } else if (inside.size() > 1) a.status = "§cEn disputa";
        else a.status = "§7Nadie en la zona";
        String leaderId = null;
        int best = 0;
        for (Map.Entry<String, Integer> e : a.hold.entrySet()) if (e.getValue() > best) {
            best = e.getValue();
            leaderId = e.getKey();
        }
        Guild lg = leaderId == null ? null : plugin.guilds.byId(leaderId);
        a.bar.setProgress(Math.max(0, Math.min(1, best / (double) d.holdSeconds)));
        a.bar.setTitle("§6⚑ " + d.name + " §8| " + a.status + " §8| §fLíder: §e" + (lg == null ? "—" : lg.name + " " + best + "/" + d.holdSeconds + "s") + " §8| §7" + Msg.dur(Math.max(0, a.endAt - now)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Guild g = plugin.guilds.of(p);
            if (eligible(d, g) && p.getWorld().getName().equals(d.world)) a.bar.addPlayer(p);
            else a.bar.removePlayer(p);
        }
        if (holder != null) {
            for (Player p : inside.get(holder)) Msg.actionbar(p, "§6Controlas la zona: §f" + a.hold.get(holder) + "§7/§f" + d.holdSeconds + "s");
        }
        if (lg != null && best >= d.holdSeconds) {
            winKoth(a, lg);
            return;
        }
        if (now >= a.endAt) {
            if (lg != null) winKoth(a, lg);
            else finish(a, null, "Se acabó el tiempo sin ganador.");
        }
    }

    private void winKoth(Active a, Guild winner) {
        EventDef d = a.def;
        Bukkit.broadcastMessage(Msg.PREFIX + "§6§l⚑ ¡" + winner.name + " §6§lha ganado el evento §e" + d.name + "§6§l!");
        reward(winner, d.rewardXp, d.rewardCrowns, "¡Ganasteis el evento " + d.name + "!");
        long pct = plugin.getConfig().getLong("eventos.participacion-pct", 20);
        if (pct > 0) {
            for (Map.Entry<String, Integer> e : a.hold.entrySet()) {
                if (e.getKey().equals(winner.id) || e.getValue() <= 0) continue;
                reward(plugin.guilds.byId(e.getKey()), d.rewardXp * pct / 100, d.rewardCrowns * pct / 100, "Participación en " + d.name + ".");
            }
        }
        finish(a, winner, null);
    }

    private void tickBoss(Active a, long now) {
        EventDef d = a.def;
        Entity e = a.boss == null ? null : Bukkit.getEntity(a.boss);
        if (e instanceof LivingEntity le && !le.isDead()) {
            a.bar.setProgress(Math.max(0, Math.min(1, le.getHealth() / d.bossHp)));
            // que no se aleje de la zona
            if (!d.contains(le.getLocation())) {
                World w = le.getWorld();
                le.teleport(new Location(w, (d.minX + d.maxX + 1) / 2.0, le.getLocation().getY(), (d.minZ + d.maxZ + 1) / 2.0));
            }
        }
        a.bar.setTitle("§5⚑ " + d.name + " §8| §fJefe: §c" + (e instanceof LivingEntity l2 ? (int) Math.max(0, l2.getHealth()) : 0) + "§7/" + (int) d.bossHp + " §8| §7" + Msg.dur(Math.max(0, a.endAt - now)));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Guild g = plugin.guilds.of(p);
            if (eligible(d, g) && p.getWorld().getName().equals(d.world)) a.bar.addPlayer(p);
            else a.bar.removePlayer(p);
        }
        if (now >= a.endAt) finish(a, null, "El jefe resistió. Sin recompensas.");
    }

    // ------------------------------------------------------------------ jefe: daño y muerte

    private Active bossActive(Entity e) {
        String id = e.getPersistentDataContainer().get(kBoss, PersistentDataType.STRING);
        return id == null ? null : active.get(id);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBossDamage(EntityDamageByEntityEvent e) {
        Active a = bossActive(e.getEntity());
        if (a == null) return;
        Entity dm = e.getDamager();
        Player p = dm instanceof Player pl ? pl : (dm instanceof Projectile pr && pr.getShooter() instanceof Player sp ? sp : null);
        if (p == null) return;
        Guild g = plugin.guilds.of(p);
        if (!eligible(a.def, g)) return;
        a.damage.merge(g.id, e.getFinalDamage(), Double::sum);
    }

    @EventHandler
    public void onBossDeath(EntityDeathEvent e) {
        Active a = bossActive(e.getEntity());
        if (a == null) return;
        e.getDrops().clear();
        e.setDroppedExp(0);
        EventDef d = a.def;
        double total = 0;
        for (double v : a.damage.values()) total += v;
        Bukkit.broadcastMessage(Msg.PREFIX + "§5§l⚑ ¡El jefe de §e" + d.name + " §5§lha caído!");
        if (total > 0) {
            for (Map.Entry<String, Double> en : a.damage.entrySet()) {
                double share = en.getValue() / total;
                reward(plugin.guilds.byId(en.getKey()), Math.round(d.rewardXp * share), Math.round(d.rewardCrowns * share),
                        "Contribuisteis " + Math.round(share * 100) + "% del daño al jefe de " + d.name + ".");
            }
        }
        a.boss = null;
        finish(a, null, null);
    }

    public void shutdown() {
        for (Active a : new ArrayList<>(active.values())) finish(a, null, null);
    }

    // ------------------------------------------------------------------ persistencia

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("eventos");
        for (EventDef d : defs.values()) {
            ConfigurationSection s = root.createSection(d.id);
            s.set("nombre", d.name);
            s.set("tipo", d.type.name());
            s.set("zona", d.hasZone);
            s.set("mundo", d.world);
            s.set("min", List.of(d.minX, d.minY, d.minZ));
            s.set("max", List.of(d.maxX, d.maxY, d.maxZ));
            s.set("duracion", d.durationMin);
            s.set("mantener", d.holdSeconds);
            s.set("vidaJefe", d.bossHp);
            s.set("xp", d.rewardXp);
            s.set("coronas", d.rewardCrowns);
            s.set("cada", d.intervalMin);
            s.set("proximo", d.nextStart);
            s.set("nivelMin", d.minGuildLevel);
            s.set("permitidos", new ArrayList<>(d.allowed));
            s.set("activo", d.enabled);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar eventos.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("eventos");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                EventDef d = new EventDef(id, s.getString("nombre", id));
                d.type = EventDef.Type.valueOf(s.getString("tipo", "KOTH"));
                d.hasZone = s.getBoolean("zona");
                d.world = s.getString("mundo");
                List<Integer> mn = s.getIntegerList("min"), mx = s.getIntegerList("max");
                if (mn.size() == 3 && mx.size() == 3) {
                    d.minX = mn.get(0);
                    d.minY = mn.get(1);
                    d.minZ = mn.get(2);
                    d.maxX = mx.get(0);
                    d.maxY = mx.get(1);
                    d.maxZ = mx.get(2);
                }
                d.durationMin = s.getInt("duracion", 30);
                d.holdSeconds = s.getInt("mantener", 300);
                d.bossHp = s.getDouble("vidaJefe", 2000);
                d.rewardXp = s.getLong("xp", 500);
                d.rewardCrowns = s.getLong("coronas", 0);
                d.intervalMin = s.getInt("cada", 0);
                d.nextStart = s.getLong("proximo", 0);
                d.minGuildLevel = s.getInt("nivelMin", 1);
                d.allowed.addAll(s.getStringList("permitidos"));
                d.enabled = s.getBoolean("activo", true);
                if (d.intervalMin > 0 && d.nextStart < System.currentTimeMillis()) d.nextStart = System.currentTimeMillis() + 60_000L;
                defs.put(id, d);
            } catch (Exception ex) {
                plugin.getLogger().warning("Evento '" + id + "' dañado en eventos.yml: " + ex.getMessage());
            }
        }
    }
}
