package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Rank;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Vindicator;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Asedios: declaración, aviso, nodo, guardias, victoria, saqueo y restauración. */
public class RaidManager {
    private final GremiosPlugin plugin;
    private final File file;
    private final Map<String, Raid> raids = new LinkedHashMap<>();

    public final NamespacedKey kNode;
    public final NamespacedKey kNodeDisp;
    public final NamespacedKey kGuard;

    public RaidManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "raids.yml");
        this.kNode = plugin.key("raid_node");
        this.kNodeDisp = plugin.key("raid_node_disp");
        this.kGuard = plugin.key("raid_guard");
    }

    // ------------------------------------------------------------------ consulta

    public Collection<Raid> all() {
        return raids.values();
    }

    public Raid get(String territoryId) {
        return territoryId == null ? null : raids.get(territoryId);
    }

    /** Cualquier asedio (programado, activo, saqueo) en el que participe el gremio. */
    public Raid of(Guild g) {
        if (g == null) return null;
        for (Raid r : raids.values()) {
            if (r.attackerId.equals(g.id) || r.defenderId.equals(g.id)) return r;
        }
        return null;
    }

    /** Id del "bando" de un jugador: su gremio, o el bando de pruebas si es admin sin gremio. */
    public static final String TEST = "__prueba__";

    public String sideOf(Player p) {
        boolean admin = p.hasPermission("gremios.admin");
        if (admin) {
            for (Raid r : raids.values()) if (r.test && r.state == Raid.State.ACTIVE) return TEST;
        }
        Guild g = plugin.guilds.of(p);
        if (g != null) return g.id;
        return admin ? TEST : null;
    }

    public Raid activeRaidOfSide(String side) {
        if (side == null) return null;
        for (Raid r : raids.values()) {
            if (r.state == Raid.State.ACTIVE && (r.attackerId.equals(side) || r.defenderId.equals(side))) return r;
        }
        return null;
    }

    public Raid activeRaidOf(Guild g) {
        if (g == null) return null;
        for (Raid r : raids.values()) {
            if (r.state == Raid.State.ACTIVE && (r.attackerId.equals(g.id) || r.defenderId.equals(g.id))) return r;
        }
        return null;
    }

    public Raid raidOfNode(Entity e) {
        String tid = e.getPersistentDataContainer().get(kNode, PersistentDataType.STRING);
        return tid == null ? null : raids.get(tid);
    }

    public boolean isNode(Entity e) {
        return e.getPersistentDataContainer().has(kNode, PersistentDataType.STRING);
    }

    public boolean isGuard(Entity e) {
        return e.getPersistentDataContainer().has(kGuard, PersistentDataType.STRING);
    }

    public Location nodeLocation(Raid r) {
        if (r.nodeEntity != null) {
            Entity e = Bukkit.getEntity(r.nodeEntity);
            if (e != null) return e.getLocation();
        }
        Territory t = plugin.territories.get(r.territoryId);
        return t == null ? null : plugin.territories.nodeLoc(t);
    }

    private long minutes(String path, long def) {
        return plugin.getConfig().getLong(path, def) * 60_000L;
    }

    private long hours(String path, long def) {
        return plugin.getConfig().getLong(path, def) * 3_600_000L;
    }

    // ------------------------------------------------------------------ declarar

    public String declare(Player p, Territory t) {
        Guild a = plugin.guilds.of(p);
        if (a == null) return "Necesitas un gremio.";
        Rank need = Rank.parse(plugin.getConfig().getString("raid.rango-minimo-declarar", "MARISCAL"), Rank.MARISCAL);
        Rank mine = a.rankOf(p.getUniqueId());
        if (mine == null || !mine.atLeast(need)) return "Solo " + need.display + " o superior puede declarar un asedio.";
        Guild d = plugin.guilds.byId(t.ownerGuildId);
        if (d == null) return "Ese territorio no tiene dueño.";
        if (d == a) return "Es tu propio territorio.";
        if (plugin.guilds.isAllied(a, d)) return "No puedes atacar a un aliado.";
        if (plugin.getConfig().getBoolean("raid.requiere-territorio", true) && !a.hasTerritory()) return "Tu gremio necesita un territorio para declarar asedios.";
        int minMembers = plugin.getConfig().getInt("raid.min-miembros-atacante", 2);
        if (a.members.size() < minMembers) return "Tu gremio necesita al menos " + minMembers + " miembros.";
        if (get(t.id) != null || t.resetting) return "Ese territorio ya está en un asedio.";
        if (of(a) != null) return "Tu gremio ya participa en un asedio.";
        if (of(d) != null) return "Ese gremio ya está en otro asedio.";
        long now = System.currentTimeMillis();
        if (d.protectedUntil > now) return "Ese gremio está protegido durante " + Msg.dur(d.protectedUntil - now) + ".";
        if (a.attackCooldownUntil > now) return "Tu gremio debe esperar " + Msg.dur(a.attackCooldownUntil - now) + " para atacar de nuevo.";
        if (d.territoryLevel < plugin.getConfig().getInt("raid.nivel-minimo-objetivo", 1)) return "Ese territorio no se puede atacar todavía.";
        long cost = plugin.getConfig().getLong("raid.costo-declarar", 16);
        if (a.bank < cost) return "El banco de tu gremio necesita " + plugin.currency.fmt(cost) + " para declarar el asedio.";

        a.bank -= cost;
        a.log("§6-" + Msg.num(cost) + " §7declarar asedio");
        Raid r = new Raid(t.id, a.id, d.id);
        r.attackerName = a.name;
        r.defenderName = d.name;
        r.declaredAt = now;
        r.startAt = scheduleStart(now + minutes("raid.aviso-minutos", 720));
        raids.put(t.id, r);
        save();
        plugin.guilds.save();

        String when = Msg.dur(r.startAt - now);
        plugin.guilds.broadcast(d, "§c⚔ §6" + a.name + " §cha declarado un asedio sobre tu territorio. Comienza en §f" + when + "§c. ¡Preparad las defensas!");
        plugin.guilds.broadcast(a, "§a⚔ Asedio declarado contra §6" + d.name + "§a. Comienza en §f" + when + "§a.");
        return null;
    }

    public String cancelDeclared(Player p) {
        Guild a = plugin.guilds.of(p);
        if (a == null) return "Necesitas un gremio.";
        Raid r = null;
        for (Raid x : raids.values()) if (x.attackerId.equals(a.id) && x.state == Raid.State.SCHEDULED) r = x;
        if (r == null) return "Tu gremio no tiene ningún asedio programado.";
        Rank mine = a.rankOf(p.getUniqueId());
        if (mine == null || !mine.atLeast(Rank.MARISCAL)) return "Solo Mariscal o superior puede cancelar el asedio.";
        cancelRaid(r, "El gremio atacante canceló el asedio.");
        return null;
    }

    private long scheduleStart(long ms) {
        if (!plugin.getConfig().getBoolean("raid.ventana.activo", false)) return ms;
        int ini = plugin.getConfig().getInt("raid.ventana.inicio-hora", 18);
        int fin = plugin.getConfig().getInt("raid.ventana.fin-hora", 23);
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault());
        int h = z.getHour();
        boolean inside = ini <= fin ? (h >= ini && h < fin) : (h >= ini || h < fin);
        if (inside) return ms;
        ZonedDateTime next = z.withHour(ini).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(z)) next = next.plusDays(1);
        return next.toInstant().toEpochMilli();
    }

    // ------------------------------------------------------------------ inicio

    private void tryStart(Raid r) {
        Guild a = plugin.guilds.byId(r.attackerId);
        Guild d = plugin.guilds.byId(r.defenderId);
        Territory t = plugin.territories.get(r.territoryId);
        if (a == null || d == null || t == null || !r.defenderId.equals(t.ownerGuildId)) {
            cancelRaid(r, "El asedio se canceló: ya no es válido.");
            return;
        }
        int minDef = plugin.getConfig().getInt("raid.min-defensores-online", 1);
        int minAtt = plugin.getConfig().getInt("raid.min-atacantes-online", 1);
        if (plugin.guilds.online(d) < minDef || plugin.guilds.online(a) < minAtt) {
            int max = plugin.getConfig().getInt("raid.max-posposiciones", 2);
            if (r.postponements >= max) {
                cancelRaid(r, "El asedio se canceló: no había suficientes jugadores conectados.");
            } else {
                r.postponements++;
                r.startAt = System.currentTimeMillis() + minutes("raid.posponer-minutos", 30);
                String m = "§eEl asedio se pospone " + Msg.dur(r.startAt - System.currentTimeMillis()) + " (faltan jugadores conectados).";
                plugin.guilds.broadcast(a, m);
                plugin.guilds.broadcast(d, m);
                save();
            }
            return;
        }
        start(r);
    }

    private void start(Raid r) {
        Territory t = plugin.territories.get(r.territoryId);
        Guild a = plugin.guilds.byId(r.attackerId);
        Guild d = plugin.guilds.byId(r.defenderId);
        long now = System.currentTimeMillis();
        r.state = Raid.State.ACTIVE;
        r.endAt = now + minutes("raid.duracion-minutos", 75);
        double base = plugin.getConfig().getDouble("raid.nodo.vida", 1500);
        double per = plugin.getConfig().getDouble("mejoras.nucleo.vida-por-nivel", 150);
        r.nodeMax = base + d.upCore * per;
        r.nodeHp = r.nodeMax;
        r.lastDamage = now;
        r.shieldUntil = 0;
        spawnNode(r, t);
        spawnGuards(r, t, d);
        ensureBar(r);
        String msg = "§4§l⚔ ¡ASEDIO! §6" + a.name + " §cataca el territorio de §6" + d.name + "§c.";
        Bukkit.broadcastMessage(Msg.PREFIX + msg);
        if (plugin.getConfig().getBoolean("raid.cinematica.activa", true)) {
            plugin.cinematic.play(t, plugin.territories.nodeLoc(t), a.name, d.name, plugin.guilds.onlineMembers(a), plugin.guilds.onlineMembers(d));
        } else {
            for (Player p : plugin.guilds.onlineMembers(a)) {
                p.sendTitle("§c§l¡A LA GUERRA!", "§7Rompe el Nodo de " + d.name, 10, 70, 20);
                p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
            }
            for (Player p : plugin.guilds.onlineMembers(d)) {
                p.sendTitle("§c§l¡ASEDIO!", "§7Defended el Nodo", 10, 70, 20);
                p.playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 1f, 1f);
            }
        }
        save();
    }

    public String forceStart(Guild attacker, Territory t) {
        Guild d = plugin.guilds.byId(t.ownerGuildId);
        if (d == null) return "Ese territorio no tiene dueño.";
        if (attacker == d) return "El atacante no puede ser el dueño.";
        if (get(t.id) != null || t.resetting) return "Ese territorio ya está en un asedio.";
        Raid r = new Raid(t.id, attacker.id, d.id);
        r.attackerName = attacker.name;
        r.defenderName = d.name;
        r.declaredAt = System.currentTimeMillis();
        r.startAt = r.declaredAt;
        raids.put(t.id, r);
        start(r);
        return null;
    }

    /** Raid de prueba: territorio sin dueño, sin gremios. Los admins atacan; al terminar se restaura si hay snapshot. */
    public String startTest(Territory t, int minutes) {
        if (t.ownerGuildId != null) return "Ese territorio tiene dueño. La prueba es solo para territorios libres.";
        if (get(t.id) != null || t.resetting) return "Ese territorio ya está en un asedio o restaurándose.";
        if (plugin.territories.nodeLoc(t) == null) return "El territorio no tiene mundo válido.";
        Raid r = new Raid(t.id, TEST, "__sin_defensor__");
        r.test = true;
        r.attackerName = "Pruebas";
        r.defenderName = t.name;
        long now = System.currentTimeMillis();
        r.declaredAt = now;
        r.startAt = now;
        r.state = Raid.State.ACTIVE;
        r.endAt = now + minutes * 60_000L;
        r.nodeMax = plugin.getConfig().getDouble("raid.nodo.vida", 1500);
        r.nodeHp = r.nodeMax;
        r.lastDamage = now;
        raids.put(t.id, r);
        spawnNode(r, t);
        ensureBar(r);
        if (plugin.getConfig().getBoolean("raid.cinematica.activa", true) && plugin.getConfig().getBoolean("raid.cinematica.en-pruebas", false)) {
            List<Player> admins = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("gremios.admin")) admins.add(p);
            plugin.cinematic.play(t, plugin.territories.nodeLoc(t), "Pruebas", t.name, admins, new ArrayList<>());
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("gremios.admin")) Msg.to(p, "§d[Prueba] §7Raid de prueba iniciada en §f" + t.name + "§7 (" + minutes + " min). Rompe el Nodo.");
        }
        return null;
    }

    private void endTest(Raid r, String msg) {
        Territory t = plugin.territories.get(r.territoryId);
        cleanupActive(r);
        raids.remove(r.territoryId);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("gremios.admin")) Msg.to(p, "§d[Prueba] §7" + msg);
        }
        plugin.gates.restoreAll(t);
        if (t != null && plugin.territories.isDamaged(t)) {
            if (plugin.snapshots.has(t)) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("gremios.admin")) Msg.to(p, "§d[Prueba] §7Restaurando el terreno desde el snapshot...");
                }
                plugin.territories.resetTerritory(t, null);
            } else {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.hasPermission("gremios.admin")) Msg.to(p, "§d[Prueba] §eNo hay snapshot: haz §f/territorio snapshot " + t.id + " §eantes de probar para poder restaurar.");
                }
            }
        }
    }

    public String forceEnd(Territory t, String result) {
        Raid r = get(t.id);
        if (r == null) return "No hay asedio en ese territorio.";
        switch (result.toLowerCase()) {
            case "atacante" -> {
                if (r.state != Raid.State.ACTIVE) return "El asedio no está activo.";
                attackerWins(r);
            }
            case "defensor" -> {
                if (r.state != Raid.State.ACTIVE) return "El asedio no está activo.";
                defenderWins(r);
            }
            case "saqueo" -> {
                if (r.state != Raid.State.LOOT) return "No está en fase de saqueo.";
                endLoot(r);
            }
            default -> cancelRaid(r, "Un administrador canceló el asedio.");
        }
        return null;
    }

    // ------------------------------------------------------------------ nodo

    private void spawnNode(Raid r, Territory t) {
        Location loc = plugin.territories.nodeLoc(t);
        if (loc == null) return;
        World w = loc.getWorld();
        Chunk c = loc.getChunk();
        c.addPluginChunkTicket(plugin);
        final String tid = t.id;
        Slime s = w.spawn(loc, Slime.class, e -> {
            e.setSize(3);
            e.setAI(false);
            e.setInvisible(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setPersistent(false);
            e.setRemoveWhenFarAway(false);
            e.setCustomName("§c§lNodo del Territorio");
            e.setCustomNameVisible(false);
            e.getPersistentDataContainer().set(kNode, PersistentDataType.STRING, tid);
        });
        ItemDisplay d = w.spawn(loc, ItemDisplay.class, e -> {
            e.setItemStack(SiegeItems.displayItem(SiegeItems.CMD_NODE));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTransformation(new Transformation(new Vector3f(0f, 1.6f, 0f), new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(3.2f, 3.2f, 3.2f), new AxisAngle4f(0f, 0f, 0f, 1f)));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(3f);
            e.setPersistent(false);
            e.getPersistentDataContainer().set(kNodeDisp, PersistentDataType.STRING, tid);
        });
        r.nodeEntity = s.getUniqueId();
        r.nodeDisplay = d.getUniqueId();
    }

    private void removeNode(Raid r) {
        if (r.nodeEntity != null) {
            Entity e = Bukkit.getEntity(r.nodeEntity);
            if (e != null) {
                Chunk c = e.getLocation().getChunk();
                e.remove();
                c.removePluginChunkTicket(plugin);
            }
        }
        if (r.nodeDisplay != null) {
            Entity e = Bukkit.getEntity(r.nodeDisplay);
            if (e != null) e.remove();
        }
        r.nodeEntity = null;
        r.nodeDisplay = null;
    }

    public void damageNode(Raid r, double dmg, Player src) {
        if (r.state != Raid.State.ACTIVE || dmg <= 0) return;
        long now = System.currentTimeMillis();
        Location nl = nodeLocation(r);
        if (now < r.shieldUntil) {
            if (src != null) Msg.actionbar(src, "§bEl Nodo está protegido por un escudo (" + Msg.dur(r.shieldUntil - now) + ")");
            if (nl != null) nl.getWorld().spawnParticle(Particle.ENCHANT, nl.clone().add(0, 1.5, 0), 20, 0.6, 0.6, 0.6, 0.5);
            return;
        }
        r.nodeHp -= dmg;
        r.lastDamage = now;
        if (now - r.windowStart > 10_000L) {
            r.windowStart = now;
            r.windowDamage = 0;
        }
        r.windowDamage += dmg;
        if (r.windowDamage >= plugin.getConfig().getDouble("raid.nodo.escudo-umbral", 300)) {
            r.shieldUntil = now + plugin.getConfig().getLong("raid.nodo.escudo-segundos", 8) * 1000L;
            r.windowDamage = 0;
            r.windowStart = now;
            Guild a = plugin.guilds.byId(r.attackerId);
            if (a != null) plugin.guilds.broadcast(a, "§b¡El Nodo activa su escudo! Espera unos segundos.");
            if (nl != null) nl.getWorld().playSound(nl, Sound.BLOCK_BEACON_ACTIVATE, 1.5f, 1.2f);
        }
        if (nl != null) {
            nl.getWorld().spawnParticle(Particle.CRIT, nl.clone().add(0, 1.5, 0), 15, 0.5, 0.7, 0.5, 0.3);
            nl.getWorld().playSound(nl, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1f, 0.7f);
        }
        if (src != null) Msg.actionbar(src, "§cNodo: §f" + (int) Math.max(0, r.nodeHp) + "§7/§f" + (int) r.nodeMax);
        updateBar(r, now);
        if (r.nodeHp <= 0) attackerWins(r);
    }

    // ------------------------------------------------------------------ guardias

    private void spawnGuards(Raid r, Territory t, Guild d) {
        List<Integer> list = plugin.getConfig().getIntegerList("raid.guardias.por-nivel");
        int base = list.size() >= d.territoryLevel && d.territoryLevel > 0 ? list.get(d.territoryLevel - 1) : 0;
        int n = base > 0 ? base + d.upGuard * 2 : 0;
        World w = t.bukkitWorld();
        if (w == null) return;
        Location c = t.center();
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (int i = 0; i < n; i++) {
            int x = (int) c.getX() + rnd.nextInt(-6, 7);
            int z = (int) c.getZ() + rnd.nextInt(-6, 7);
            x = Math.max(t.minX, Math.min(t.maxX, x));
            z = Math.max(t.minZ, Math.min(t.maxZ, z));
            int y = Math.min(t.maxY, w.getHighestBlockYAt(x, z) + 1);
            Location l = new Location(w, x + 0.5, y, z + 0.5);
            final String tid = t.id;
            Vindicator v = w.spawn(l, Vindicator.class, e -> {
                e.setCustomName("§aGuardia del Gremio");
                e.setCustomNameVisible(true);
                e.setPersistent(false);
                e.setRemoveWhenFarAway(false);
                e.setCanJoinRaid(false);
                e.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
                e.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                e.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE));
                e.getEquipment().setHelmetDropChance(0f);
                e.getEquipment().setChestplateDropChance(0f);
                e.getEquipment().setItemInMainHandDropChance(0f);
                e.getPersistentDataContainer().set(kGuard, PersistentDataType.STRING, tid);
            });
            r.guards.add(v.getUniqueId());
        }
    }

    private void tickGuards(Raid r) {
        Guild a = plugin.guilds.byId(r.attackerId);
        if (a == null) return;
        List<Player> attackers = plugin.guilds.onlineMembers(a);
        for (UUID id : new ArrayList<>(r.guards)) {
            Entity e = Bukkit.getEntity(id);
            if (!(e instanceof org.bukkit.entity.Mob m) || m.isDead()) {
                if (e == null || e.isDead()) r.guards.remove(id);
                continue;
            }
            LivingEntity tgt = m.getTarget();
            if (tgt != null && !tgt.isDead()) continue;
            Player best = null;
            double bd = 24 * 24;
            for (Player p : attackers) {
                if (p.getWorld() != m.getWorld()) continue;
                if (p.getGameMode() != org.bukkit.GameMode.SURVIVAL) continue;
                double dd = p.getLocation().distanceSquared(m.getLocation());
                if (dd < bd) {
                    bd = dd;
                    best = p;
                }
            }
            if (best != null) m.setTarget(best);
        }
    }

    private void removeGuards(Raid r) {
        for (UUID id : r.guards) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        r.guards.clear();
    }

    // ------------------------------------------------------------------ barra

    private void ensureBar(Raid r) {
        if (r.bar == null) {
            r.bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10);
        }
        updateBar(r, System.currentTimeMillis());
    }

    private void updateBar(Raid r, long now) {
        if (r.bar == null) return;
        double frac = r.nodeMax <= 0 ? 0 : Math.max(0, Math.min(1, r.nodeHp / r.nodeMax));
        r.bar.setProgress(frac);
        String shield = now < r.shieldUntil ? " §b[ESCUDO]" : "";
        r.bar.setTitle("§c⚔ " + r.attackerName + " §7→ §a" + r.defenderName + " §8| §fNodo §c" + (int) Math.max(0, r.nodeHp) + "§7/" + (int) r.nodeMax
                + " §8| §e" + Msg.dur(r.endAt - now) + shield);
        if (r.test) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.hasPermission("gremios.admin")) r.bar.addPlayer(p);
            return;
        }
        Guild a = plugin.guilds.byId(r.attackerId);
        Guild d = plugin.guilds.byId(r.defenderId);
        for (Player p : plugin.guilds.onlineMembers(a)) r.bar.addPlayer(p);
        for (Player p : plugin.guilds.onlineMembers(d)) r.bar.addPlayer(p);
    }

    // ------------------------------------------------------------------ bucle

    public void tick() {
        long now = System.currentTimeMillis();
        for (Raid r : new ArrayList<>(raids.values())) {
            switch (r.state) {
                case SCHEDULED -> {
                    long rem = r.startAt - now;
                    if (rem <= 3_600_000L && (r.notices & 1) == 0) {
                        r.notices |= 1;
                        notifyBoth(r, "§eEl asedio comienza en §f1 hora§e.");
                    }
                    if (rem <= 600_000L && (r.notices & 2) == 0) {
                        r.notices |= 2;
                        notifyBoth(r, "§6El asedio comienza en §f10 minutos§6.");
                    }
                    if (rem <= 60_000L && (r.notices & 4) == 0) {
                        r.notices |= 4;
                        notifyBoth(r, "§c¡El asedio comienza en §f1 minuto§c!");
                    }
                    if (now >= r.startAt) tryStart(r);
                }
                case ACTIVE -> tickActive(r, now);
                case LOOT -> {
                    if (now >= r.lootEndAt) endLoot(r);
                }
                default -> {
                }
            }
        }
    }

    private void notifyBoth(Raid r, String msg) {
        plugin.guilds.broadcast(plugin.guilds.byId(r.attackerId), msg);
        plugin.guilds.broadcast(plugin.guilds.byId(r.defenderId), msg);
    }

    private long guardTick;

    private void tickActive(Raid r, long now) {
        Territory t = plugin.territories.get(r.territoryId);
        if (t == null || (!r.test && (plugin.guilds.byId(r.attackerId) == null || plugin.guilds.byId(r.defenderId) == null))) {
            cancelRaid(r, "El asedio terminó: ya no es válido.");
            return;
        }
        if (r.nodeEntity == null || Bukkit.getEntity(r.nodeEntity) == null) spawnNode(r, t);
        // regeneración del nodo cuando no hay combate
        long wait = plugin.getConfig().getLong("raid.nodo.regen-espera-segundos", 30) * 1000L;
        if (now - r.lastDamage > wait && r.nodeHp < r.nodeMax) {
            r.nodeHp = Math.min(r.nodeMax, r.nodeHp + plugin.getConfig().getDouble("raid.nodo.regen-por-segundo", 1.5));
        }
        ensureBar(r);
        if (now - guardTick >= 2000) {
            guardTick = now;
            tickGuards(r);
        }
        Location nl = nodeLocation(r);
        if (nl != null && nl.getWorld() != null && System.currentTimeMillis() % 3 == 0) {
            nl.getWorld().spawnParticle(Particle.END_ROD, nl.clone().add(0, 1.6, 0), 4, 0.5, 0.8, 0.5, 0.01);
        }
        if (now >= r.endAt) defenderWins(r);
    }

    private void cleanupActive(Raid r) {
        removeNode(r);
        removeGuards(r);
        if (r.bar != null) {
            r.bar.removeAll();
            r.bar = null;
        }
    }

    // ------------------------------------------------------------------ resultados

    private void attackerWins(Raid r) {
        if (r.state != Raid.State.ACTIVE) return;
        if (r.test) {
            endTest(r, "¡Nodo destruido! Prueba terminada.");
            return;
        }
        Territory t = plugin.territories.get(r.territoryId);
        Guild a = plugin.guilds.byId(r.attackerId);
        Guild d = plugin.guilds.byId(r.defenderId);
        cleanupActive(r);
        long now = System.currentTimeMillis();
        r.state = Raid.State.LOOT;
        r.lootEndAt = now + minutes("raid.saqueo-minutos", 12);
        long stolen = 0;
        if (d != null) {
            stolen = d.bank * plugin.getConfig().getLong("raid.saqueo-banco-pct", 25) / 100L;
            d.bank -= stolen;
            d.log("§c-" + Msg.num(stolen) + " §7saqueado");
            d.protectedUntil = now + hours("raid.proteccion-tras-raid-horas", 6);
        }
        if (a != null) {
            a.bank += stolen;
            if (stolen > 0) a.log("§a+" + Msg.num(stolen) + " §7botín");
            a.attackCooldownUntil = now + hours("raid.cooldown-atacante-horas", 12);
            plugin.guilds.addXp(a, plugin.getConfig().getLong("raid.xp-victoria", 800));
        }
        if (t != null) plugin.territories.release(t, false, null);
        plugin.history.add(r.territoryId, r.attackerName, r.defenderName, "ATACANTE", stolen);
        plugin.guilds.save();
        Bukkit.broadcastMessage(Msg.PREFIX + "§6" + r.attackerName + " §aha conquistado el territorio de §6" + r.defenderName + "§a. ¡Tiene §f" + Msg.dur(r.lootEndAt - now) + " §apara saquear!");
        if (a != null) {
            plugin.guilds.broadcast(a, "§6¡Victoria! Podéis abrir cofres y llevároslo todo. Botín del banco: §e" + plugin.currency.fmt(stolen) + "§6.");
            for (Player p : plugin.guilds.onlineMembers(a)) p.sendTitle("§6§l¡VICTORIA!", "§7Saquead el territorio", 10, 70, 20);
        }
        if (d != null) {
            plugin.guilds.broadcast(d, "§4Vuestro Nodo ha caído. Habéis perdido el territorio y parte del banco.");
            for (Player p : plugin.guilds.onlineMembers(d)) p.sendTitle("§4§lDERROTA", "§7Habéis perdido el territorio", 10, 70, 20);
        }
        save();
    }

    private void defenderWins(Raid r) {
        if (r.state != Raid.State.ACTIVE) return;
        if (r.test) {
            endTest(r, "Se acabó el tiempo. Prueba terminada.");
            return;
        }
        Territory t = plugin.territories.get(r.territoryId);
        Guild a = plugin.guilds.byId(r.attackerId);
        Guild d = plugin.guilds.byId(r.defenderId);
        cleanupActive(r);
        long now = System.currentTimeMillis();
        if (a != null) a.attackCooldownUntil = now + hours("raid.cooldown-atacante-horas", 12);
        if (d != null) {
            d.protectedUntil = now + hours("raid.proteccion-tras-raid-horas", 6);
            plugin.guilds.addXp(d, plugin.getConfig().getLong("raid.xp-defensa", 400));
        }
        raids.remove(r.territoryId);
        plugin.history.add(r.territoryId, r.attackerName, r.defenderName, "DEFENSOR", 0);
        if (plugin.getConfig().getBoolean("puertas.reparar-al-terminar", true)) plugin.gates.restoreAll(t);
        boolean damaged = t != null && plugin.territories.isDamaged(t);
        if (t != null) plugin.territories.saveDamage(t);
        plugin.guilds.save();
        Bukkit.broadcastMessage(Msg.PREFIX + "§a" + r.defenderName + " §7ha resistido el asedio de §c" + r.attackerName + "§7.");
        if (d != null) {
            plugin.guilds.broadcast(d, "§a¡Habéis resistido! " + (damaged ? "El territorio está dañado: habla con el §bMaestro de Obras §apara repararlo." : ""));
            for (Player p : plugin.guilds.onlineMembers(d)) p.sendTitle("§a§l¡DEFENDIDO!", "§7El asedio ha fracasado", 10, 70, 20);
        }
        if (a != null) plugin.guilds.broadcast(a, "§cEl asedio ha fracasado: se acabó el tiempo.");
        save();
    }

    private void endLoot(Raid r) {
        if (r.state != Raid.State.LOOT) return;
        Territory t = plugin.territories.get(r.territoryId);
        r.state = Raid.State.RESET;
        save();
        Bukkit.broadcastMessage(Msg.PREFIX + "§7Termina el saqueo. El territorio §f" + (t == null ? "" : t.name) + " §7se restaura...");
        if (t == null) {
            raids.remove(r.territoryId);
            save();
            return;
        }
        plugin.territories.resetTerritory(t, () -> {
            raids.remove(r.territoryId);
            plugin.gates.restoreAll(t);
            plugin.banners.refresh(t);
            save();
            Bukkit.broadcastMessage(Msg.PREFIX + "§aEl territorio §f" + t.name + " §avuelve a estar disponible.");
        });
    }

    public void cancelRaid(Raid r, String reason) {
        if (r.test) {
            endTest(r, "Prueba cancelada.");
            return;
        }
        boolean wasLoot = r.state == Raid.State.LOOT || r.state == Raid.State.RESET;
        if (!wasLoot) {
            plugin.history.add(r.territoryId, r.attackerName, r.defenderName, "CANCELADO", 0);
            plugin.gates.restoreAll(plugin.territories.get(r.territoryId));
        }
        cleanupActive(r);
        raids.remove(r.territoryId);
        notifyBoth(r, "§e" + reason);
        if (wasLoot) {
            Territory t = plugin.territories.get(r.territoryId);
            if (t != null && t.ownerGuildId == null) plugin.territories.resetTerritory(t, null);
        }
        save();
    }

    public void onGuildGone(Guild g) {
        for (Raid r : new ArrayList<>(raids.values())) {
            if (!r.attackerId.equals(g.id) && !r.defenderId.equals(g.id)) continue;
            if (r.state == Raid.State.SCHEDULED || r.state == Raid.State.ACTIVE) cancelRaid(r, "El asedio se canceló: un gremio se disolvió.");
        }
    }

    // ------------------------------------------------------------------ persistencia

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("raids");
        for (Raid r : raids.values()) {
            if (r.test) continue;
            ConfigurationSection s = root.createSection(r.territoryId);
            s.set("attacker", r.attackerId);
            s.set("defender", r.defenderId);
            s.set("attackerName", r.attackerName);
            s.set("defenderName", r.defenderName);
            s.set("state", r.state.name());
            s.set("declaredAt", r.declaredAt);
            s.set("startAt", r.startAt);
            s.set("endAt", r.endAt);
            s.set("lootEndAt", r.lootEndAt);
            s.set("postponements", r.postponements);
            s.set("nodeHp", r.nodeHp);
            s.set("nodeMax", r.nodeMax);
            s.set("notices", r.notices);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar raids.yml: " + e.getMessage());
        }
    }

    public void restore() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("raids");
        if (root == null) return;
        for (String tid : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(tid);
            if (s == null) continue;
            try {
                Raid r = new Raid(tid, s.getString("attacker"), s.getString("defender"));
                r.attackerName = s.getString("attackerName", r.attackerId);
                r.defenderName = s.getString("defenderName", r.defenderId);
                r.state = Raid.State.valueOf(s.getString("state", "SCHEDULED"));
                r.declaredAt = s.getLong("declaredAt");
                r.startAt = s.getLong("startAt");
                r.endAt = s.getLong("endAt");
                r.lootEndAt = s.getLong("lootEndAt");
                r.postponements = s.getInt("postponements");
                r.nodeHp = s.getDouble("nodeHp");
                r.nodeMax = s.getDouble("nodeMax");
                r.notices = s.getInt("notices");
                r.lastDamage = System.currentTimeMillis();
                if (r.state == Raid.State.RESET) {
                    // el territorio tiene resetting=true y se reanuda solo
                    continue;
                }
                raids.put(tid, r);
                if (r.state == Raid.State.ACTIVE) ensureBar(r);
            } catch (Exception ex) {
                plugin.getLogger().warning("Raid '" + tid + "' dañado en raids.yml: " + ex.getMessage());
            }
        }
    }

    public void shutdown() {
        raids.values().removeIf(x -> {
            if (!x.test) return false;
            cleanupActive(x);
            return true;
        });
        for (Raid r : raids.values()) {
            if (r.state == Raid.State.ACTIVE) {
                removeNode(r);
                removeGuards(r);
                if (r.bar != null) r.bar.removeAll();
                r.bar = null;
            }
        }
        save();
    }
}
