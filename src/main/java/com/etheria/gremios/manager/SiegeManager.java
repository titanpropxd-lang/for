package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.SiegeItems.Ammo;
import com.etheria.gremios.manager.SiegeItems.UnitType;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Rank;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Slime;
import org.bukkit.entity.Snowball;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Cañones, catapultas, munición, proyectiles y destrucción de bloques durante raids. */
public class SiegeManager {
    public static class Unit {
        public UUID slime;
        public UUID display;
        public UnitType type;
        public String guildId;
        public double hp;
        public Ammo ammo;
        public int ammoCount;
        public long lastFire;
    }

    private final GremiosPlugin plugin;
    private final Map<UUID, Unit> units = new HashMap<>();
    private final Set<UUID> flying = new HashSet<>();

    /** Proyectil propio: se simula tick a tick (trayectoria exacta, impacto fiable). */
    private static class Shot {
        Location pos;
        Vector vel;
        Ammo ammo;
        String gid;
        UUID shooter;
        UUID ownUnit;
        BlockDisplay visual;
        int age;
    }

    private final List<Shot> shots = new ArrayList<>();
    /** jugador -> unidad (slime) que está apuntando */
    private final Map<UUID, UUID> controlling = new HashMap<>();
    private final List<NamespacedKey> recipeKeys = new ArrayList<>();

    public final NamespacedKey kUnit, kGuild, kHp, kAmmo, kAmmoN, kDisplay, kProj, kProjGuild, kDisp;

    public SiegeManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        kUnit = plugin.key("unit_type");
        kGuild = plugin.key("unit_guild");
        kHp = plugin.key("unit_hp");
        kAmmo = plugin.key("unit_ammo");
        kAmmoN = plugin.key("unit_ammo_n");
        kDisplay = plugin.key("unit_display");
        kProj = plugin.key("proj_ammo");
        kProjGuild = plugin.key("proj_guild");
        kDisp = plugin.key("unit_disp_tag");
    }

    // ------------------------------------------------------------------ registro

    public Unit unitOf(Entity e) {
        return e == null ? null : units.get(e.getUniqueId());
    }

    public int count(Guild g) {
        int n = 0;
        for (Unit u : units.values()) if (u.guildId.equals(g.id)) n++;
        return n;
    }

    public List<Unit> unitsOf(String guildId) {
        List<Unit> l = new ArrayList<>();
        for (Unit u : units.values()) if (u.guildId.equals(guildId)) l.add(u);
        return l;
    }

    public void register(Slime s) {
        var pdc = s.getPersistentDataContainer();
        String type = pdc.get(kUnit, PersistentDataType.STRING);
        if (type == null) return;
        try {
            Unit u = new Unit();
            u.slime = s.getUniqueId();
            u.type = UnitType.valueOf(type);
            u.guildId = pdc.get(kGuild, PersistentDataType.STRING);
            Double hp = pdc.get(kHp, PersistentDataType.DOUBLE);
            u.hp = hp == null ? maxHp(u.type) : hp;
            String a = pdc.get(kAmmo, PersistentDataType.STRING);
            u.ammo = a == null || a.isEmpty() ? null : Ammo.valueOf(a);
            Integer n = pdc.get(kAmmoN, PersistentDataType.INTEGER);
            u.ammoCount = n == null ? 0 : n;
            String d = pdc.get(kDisplay, PersistentDataType.STRING);
            u.display = d == null ? null : UUID.fromString(d);
            if (u.guildId == null || (!RaidManager.TEST.equals(u.guildId) && plugin.guilds.byId(u.guildId) == null)) {
                removeEntities(u);
                return;
            }
            units.put(u.slime, u);
        } catch (IllegalArgumentException ignored) {
        }
    }

    public void rescan() {
        for (World w : Bukkit.getWorlds()) {
            for (Slime s : w.getEntitiesByClass(Slime.class)) register(s);
        }
        // pantallas huérfanas
        Set<UUID> keep = new HashSet<>();
        for (Unit u : units.values()) if (u.display != null) keep.add(u.display);
        for (World w : Bukkit.getWorlds()) {
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                if (d.getPersistentDataContainer().has(kDisp, PersistentDataType.STRING) && !keep.contains(d.getUniqueId())) d.remove();
            }
        }
    }

    public void save(Unit u) {
        Entity e = Bukkit.getEntity(u.slime);
        if (e == null) return;
        var pdc = e.getPersistentDataContainer();
        pdc.set(kHp, PersistentDataType.DOUBLE, u.hp);
        pdc.set(kAmmo, PersistentDataType.STRING, u.ammo == null ? "" : u.ammo.name());
        pdc.set(kAmmoN, PersistentDataType.INTEGER, u.ammoCount);
    }

    public double maxHp(UnitType t) {
        return plugin.getConfig().getDouble("asedio." + t.cfg + ".vida", t == UnitType.CANON ? 120 : (t == UnitType.ARIETE ? 250 : 200));
    }

    // ------------------------------------------------------------------ colocar / recoger

    /** Bando del jugador para armas de asedio (gremio, o "pruebas" si es admin sin gremio). */
    public String sideOf(Player p) {
        return plugin.raids.sideOf(p);
    }

    public boolean owns(Player p, Unit u) {
        if (p.hasPermission("gremios.admin")) return true;
        String side = sideOf(p);
        return side != null && side.equals(u.guildId);
    }

    public String place(Player p, UnitType type, Location loc) {
        final String side = sideOf(p);
        if (side == null) return "Necesitas un gremio para usar armas de asedio.";
        if (!RaidManager.TEST.equals(side)) {
            Guild g = plugin.guilds.of(p);
            Rank r = g.rankOf(p.getUniqueId());
            if (r == null || r.id < Rank.MIEMBRO.id) return "Los reclutas no pueden colocar armas de asedio.";
            int max = plugin.getConfig().getInt("asedio.max-unidades-por-gremio", 6);
            if (count(g) >= max) return "Tu gremio ya tiene el máximo de armas de asedio (" + max + ").";
            Territory t = plugin.territories.at(loc);
            if (t != null && !g.id.equals(t.ownerGuildId)) return "No puedes colocar armas dentro de un territorio ajeno.";
        }
        World w = loc.getWorld();
        final float yaw = p.getLocation().getYaw();
        loc.setYaw(yaw);

        Slime s = w.spawn(loc, Slime.class, e -> {
            e.setSize(type == UnitType.CANON ? 2 : 3);
            e.setAI(false);
            e.setInvisible(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCustomName("§6" + type.display);
            e.setCustomNameVisible(false);
            var pdc = e.getPersistentDataContainer();
            pdc.set(kUnit, PersistentDataType.STRING, type.name());
            pdc.set(kGuild, PersistentDataType.STRING, side);
            pdc.set(kHp, PersistentDataType.DOUBLE, maxHp(type));
            pdc.set(kAmmo, PersistentDataType.STRING, "");
            pdc.set(kAmmoN, PersistentDataType.INTEGER, 0);
        });
        ItemDisplay d = spawnDisplay(loc, type, yaw);
        s.getPersistentDataContainer().set(kDisplay, PersistentDataType.STRING, d.getUniqueId().toString());
        Unit u = new Unit();
        u.slime = s.getUniqueId();
        u.display = d.getUniqueId();
        u.type = type;
        u.guildId = side;
        u.hp = maxHp(type);
        units.put(u.slime, u);
        w.playSound(loc, Sound.BLOCK_ANVIL_PLACE, 1f, 0.8f);
        return null;
    }

    private ItemDisplay spawnDisplay(Location loc, UnitType type, float yaw) {
        final float scale = type == UnitType.CANON ? 1.5f : (type == UnitType.ARIETE ? 2.0f : 2.2f);
        Location dl = loc.clone();
        dl.setYaw(yaw);
        dl.setPitch(0f);
        return loc.getWorld().spawn(dl, ItemDisplay.class, e -> {
            e.setItemStack(SiegeItems.displayItem(type.cmd));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            e.setTransformation(new Transformation(new Vector3f(0f, scale * 0.5f, 0f), new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(scale, scale, scale), new AxisAngle4f(0f, 0f, 0f, 1f)));
            e.setBrightness(new Display.Brightness(12, 12));
            e.setViewRange(1.5f);
            e.setPersistent(true);
            e.getPersistentDataContainer().set(kDisp, PersistentDataType.STRING, "1");
        });
    }

    private void removeEntities(Unit u) {
        Entity s = Bukkit.getEntity(u.slime);
        if (s != null) s.remove();
        if (u.display != null) {
            Entity d = Bukkit.getEntity(u.display);
            if (d != null) d.remove();
        }
    }

    public void destroy(Unit u, boolean effects) {
        Entity s = Bukkit.getEntity(u.slime);
        if (effects && s != null) {
            Location l = s.getLocation().add(0, 0.8, 0);
            l.getWorld().spawnParticle(Particle.EXPLOSION, l, 3, 0.4, 0.4, 0.4, 0);
            l.getWorld().spawnParticle(Particle.LARGE_SMOKE, l, 20, 0.4, 0.4, 0.4, 0.02);
            l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1f);
        }
        stopControlOf(u);
        removeEntities(u);
        units.remove(u.slime);
    }

    public void removeUnitsIn(Territory t) {
        for (Unit u : new ArrayList<>(units.values())) {
            Entity e = Bukkit.getEntity(u.slime);
            if (e != null && t.contains(e.getLocation())) destroy(u, false);
        }
    }

    public void removeUnitsOf(Guild g) {
        for (Unit u : new ArrayList<>(units.values())) if (u.guildId.equals(g.id)) destroy(u, false);
    }

    public void pickup(Player p, Unit u) {
        if (!owns(p, u)) {
            Msg.to(p, "§cEsa arma no es de tu gremio.");
            return;
        }
        stopControlOf(u);
        giveOrDrop(p, SiegeItems.unitItem(plugin, u.type));
        if (u.ammo != null && u.ammoCount > 0) giveOrDrop(p, SiegeItems.ammoItem(plugin, u.ammo, u.ammoCount));
        destroy(u, false);
        Msg.to(p, "§aHas recogido el arma.");
    }

    private void giveOrDrop(Player p, ItemStack it) {
        for (ItemStack o : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
    }

    // ------------------------------------------------------------------ cargar / disparar

    public void load(Player p, Unit u, ItemStack hand, Ammo a) {
        if (!owns(p, u)) {
            Msg.to(p, "§cEsa arma no es de tu gremio.");
            return;
        }
        if (u.type == UnitType.ARIETE) {
            Msg.to(p, "§cEl ariete no usa munición.");
            return;
        }
        if (a.unit != u.type) {
            Msg.to(p, "§cEsa munición no sirve para " + u.type.display.toLowerCase() + ".");
            return;
        }
        if (u.ammoCount > 0 && u.ammo != a) {
            Msg.to(p, "§cYa tiene otra munición cargada (§f" + u.ammo.display + "§c). Dispárala primero.");
            return;
        }
        int cap = plugin.getConfig().getInt("asedio.capacidad-municion", 4);
        int add = Math.min(hand.getAmount(), cap - u.ammoCount);
        if (add <= 0) {
            Msg.to(p, "§cEstá llena (" + cap + ").");
            return;
        }
        hand.setAmount(hand.getAmount() - add);
        u.ammo = a;
        u.ammoCount += add;
        save(u);
        p.playSound(p.getLocation(), Sound.BLOCK_PISTON_EXTEND, 1f, 1.5f);
        Msg.actionbar(p, "§e" + a.display + " §7cargada: §f" + u.ammoCount + "/" + cap);
    }

    public void status(Player p, Unit u) {
        int cap = plugin.getConfig().getInt("asedio.capacidad-municion", 4);
        Msg.to(p, "§6" + u.type.display + " §7| Vida: §c" + (int) u.hp + "/" + (int) maxHp(u.type)
                + " §7| Munición: §f" + (u.ammo == null || u.ammoCount == 0 ? "vacía" : u.ammo.display + " " + u.ammoCount + "/" + cap));
    }

    /** Golpe del ariete: daña puertas, el Nodo y unidades enemigas delante de él. Sin munición. */
    private void ram(Player p, Unit u, Entity ent, long now) {
        var cfg = plugin.getConfig();
        Raid raid = plugin.raids.activeRaidOfSide(u.guildId);
        if (raid == null || !raid.attackerId.equals(u.guildId)) {
            Msg.actionbar(p, "§cEl ariete solo funciona atacando durante un asedio.");
            return;
        }
        double reach = cfg.getDouble("asedio.ariete.alcance", 5.0);
        Vector dir = p.getLocation().getDirection().setY(0).normalize();
        Location from = ent.getLocation().add(0, 1.0, 0);
        World w = from.getWorld();
        u.lastFire = now;
        // animación: el modelo avanza y vuelve
        if (u.display != null) {
            Entity d = Bukkit.getEntity(u.display);
            if (d != null) {
                d.setRotation(p.getLocation().getYaw(), 0f);
                final Location back = d.getLocation().clone();
                d.teleport(back.clone().add(dir.clone().multiply(1.2)));
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (!d.isDead()) d.teleport(back);
                }, 6L);
            }
        }
        w.playSound(from, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.5f, 0.5f);
        w.playSound(from, Sound.BLOCK_WOOD_BREAK, 1.5f, 0.5f);
        var rt = w.rayTraceBlocks(from, dir, reach, FluidCollisionMode.NEVER, true);
        Location hit = rt != null && rt.getHitPosition() != null ? new Location(w, rt.getHitPosition().getX(), rt.getHitPosition().getY(), rt.getHitPosition().getZ()) : from.clone().add(dir.clone().multiply(reach));
        w.spawnParticle(Particle.CRIT, hit, 20, 0.4, 0.4, 0.4, 0.3);
        boolean did = false;
        if (rt != null && rt.getHitBlock() != null) {
            var gate = plugin.gates.at(rt.getHitBlock());
            if (gate != null && gate.territory.equals(raid.territoryId)) {
                plugin.gates.damage(gate, cfg.getDouble("asedio.ariete.dano-puerta", 90), p);
                did = true;
            }
        }
        Location nl = plugin.raids.nodeLocation(raid);
        if (nl != null && nl.getWorld() == w && nl.distance(from) <= reach + 2.5) {
            plugin.raids.damageNode(raid, cfg.getDouble("asedio.ariete.dano-nodo", 40), p);
            did = true;
        }
        for (Unit o : new ArrayList<>(units.values())) {
            if (!o.guildId.equals(raid.defenderId)) continue;
            Entity oe = Bukkit.getEntity(o.slime);
            if (oe != null && oe.getWorld() == w && oe.getLocation().distance(hit) <= 2.5) {
                damageUnit(o, 60, p);
                did = true;
            }
        }
        plugin.turrets.damageNear(raid.defenderId, hit, 60, 2.5);
        if (!did) Msg.actionbar(p, "§7El ariete no alcanza nada. Acércalo a la puerta.");
    }

    /** Dirección de disparo según hacia dónde mira el jugador. */
    public Vector aim(Player p, UnitType type) {
        if (type == UnitType.CANON) return p.getLocation().getDirection().normalize();
        // catapulta: apunta con la dirección horizontal y la altura de la mirada (arriba = tiro más alto)
        double yaw = Math.toRadians(p.getLocation().getYaw());
        double min = plugin.getConfig().getDouble("asedio.catapulta.angulo-min", 25);
        double max = plugin.getConfig().getDouble("asedio.catapulta.angulo-max", 70);
        double deg = Math.max(min, Math.min(max, -p.getLocation().getPitch() + 15));
        double a = Math.toRadians(deg);
        return new Vector(-Math.sin(yaw) * Math.cos(a), Math.sin(a), Math.cos(yaw) * Math.cos(a)).normalize();
    }

    private double speedOf(UnitType type, Ammo a) {
        double base = plugin.getConfig().getDouble("asedio." + type.cfg + ".velocidad", 2.0);
        return base * (a == null ? 1.0 : a.range(plugin.getConfig()));
    }

    private Location muzzle(Entity ent, Vector dir) {
        return ent.getLocation().add(0, 1.2, 0).add(dir.clone().multiply(1.4));
    }

    // ------------------------------------------------------------------ control (apuntar)

    public boolean isControlling(Player p) {
        return controlling.containsKey(p.getUniqueId());
    }

    public Unit controlledUnit(Player p) {
        UUID id = controlling.get(p.getUniqueId());
        return id == null ? null : units.get(id);
    }

    public void startControl(Player p, Unit u) {
        if (!owns(p, u)) {
            Msg.to(p, "§cEsa arma no es de tu gremio.");
            return;
        }
        controlling.entrySet().removeIf(en -> en.getValue().equals(u.slime));
        controlling.put(p.getUniqueId(), u.slime);
        p.playSound(p.getLocation(), Sound.BLOCK_PISTON_EXTEND, 1f, 0.8f);
        Msg.to(p, "§6Apuntando " + u.type.display.toLowerCase() + "§7: mueve la mirada para apuntar, §fclic izquierdo§7 para disparar, §fagacharte§7 para soltar.");
    }

    public void stopControl(Player p) {
        if (controlling.remove(p.getUniqueId()) != null) Msg.actionbar(p, "§7Has soltado el arma.");
    }

    private void stopControlOf(Unit u) {
        controlling.entrySet().removeIf(en -> en.getValue().equals(u.slime));
    }

    private void tickControl() {
        if (controlling.isEmpty()) return;
        for (Map.Entry<UUID, UUID> en : new ArrayList<>(controlling.entrySet())) {
            Player p = Bukkit.getPlayer(en.getKey());
            Unit u = units.get(en.getValue());
            Entity ent = u == null ? null : Bukkit.getEntity(u.slime);
            if (p == null || !p.isOnline() || ent == null || ent.getWorld() != p.getWorld() || ent.getLocation().distanceSquared(p.getLocation()) > 14 * 14) {
                controlling.remove(en.getKey());
                if (p != null) Msg.actionbar(p, "§7Has soltado el arma.");
                continue;
            }
            // girar el modelo hacia donde apunta el jugador
            if (u.display != null) {
                Entity d = Bukkit.getEntity(u.display);
                if (d != null) {
                    float pitch = u.type == UnitType.CANON ? Math.max(-35f, Math.min(15f, p.getLocation().getPitch())) : 0f;
                    d.setRotation(p.getLocation().getYaw(), pitch);
                }
            }
            if (u.type != UnitType.ARIETE) drawPreview(p, u, ent);
            long now = System.currentTimeMillis();
            double cd = plugin.getConfig().getDouble("asedio." + u.type.cfg + ".espera-segundos", 6);
            long wait = (long) (cd * 1000) - (now - u.lastFire);
            String ammo = u.type == UnitType.ARIETE ? "§7sin munición" : (u.ammo == null || u.ammoCount <= 0 ? "§csin munición" : "§e" + u.ammo.display + " §f" + u.ammoCount);
            Msg.actionbar(p, "§6" + u.type.display + " §8| " + ammo + " §8| " + (wait > 0 ? "§cRecargando " + String.format("%.1f", wait / 1000.0) + "s" : "§a¡Lista! Clic izquierdo = disparar"));
        }
    }

    /** Línea de puntos (solo la ve quien apunta) con la trayectoria del proyectil. */
    private void drawPreview(Player p, Unit u, Entity ent) {
        Vector dir = aim(p, u.type);
        double speed = speedOf(u.type, u.ammoCount > 0 ? u.ammo : null);
        Vector vel = dir.clone().multiply(speed);
        Location pos = muzzle(ent, dir);
        World w = pos.getWorld();
        Particle.DustOptions dot = new Particle.DustOptions(Color.fromRGB(255, 190, 60), 0.9f);
        for (int i = 0; i < 90; i++) {
            pos.add(vel);
            vel.setY(vel.getY() - 0.03);
            vel.multiply(0.99);
            if (!pos.getBlock().isPassable()) {
                p.spawnParticle(Particle.DUST, pos, 6, 0.25, 0.25, 0.25, 0, new Particle.DustOptions(Color.fromRGB(255, 60, 40), 1.6f));
                break;
            }
            if (i % 2 == 0) p.spawnParticle(Particle.DUST, pos, 1, 0, 0, 0, 0, dot);
        }
    }

    public void fire(Player p, Unit u) {
        if (!owns(p, u)) {
            Msg.to(p, "§cEsa arma no es de tu gremio.");
            return;
        }
        Entity ent = Bukkit.getEntity(u.slime);
        if (ent == null) return;
        long now = System.currentTimeMillis();
        double cd = plugin.getConfig().getDouble("asedio." + u.type.cfg + ".espera-segundos", 6);
        long wait = (long) (cd * 1000) - (now - u.lastFire);
        if (wait > 0) {
            Msg.actionbar(p, "§cRecargando... " + String.format("%.1f", wait / 1000.0) + "s");
            return;
        }
        if (u.type == UnitType.ARIETE) {
            ram(p, u, ent, now);
            return;
        }
        if (u.ammo == null || u.ammoCount <= 0) {
            Msg.actionbar(p, "§cSin munición. Cárgala con clic derecho sosteniendo munición en la mano.");
            return;
        }
        final Ammo a = u.ammo;
        final String gid = u.guildId;
        Vector dir = aim(p, u.type);
        double speed = speedOf(u.type, a);
        Location muzzle = muzzle(ent, dir);
        World w = muzzle.getWorld();
        Shot sh = new Shot();
        sh.pos = muzzle.clone();
        sh.vel = dir.clone().multiply(speed);
        sh.ammo = a;
        sh.gid = gid;
        sh.shooter = p.getUniqueId();
        sh.ownUnit = u.slime;
        sh.visual = spawnShotVisual(muzzle, a);
        shots.add(sh);
        w.spawnParticle(Particle.LARGE_SMOKE, muzzle, 18, 0.25, 0.25, 0.25, 0.03);
        w.spawnParticle(Particle.EXPLOSION, muzzle, 1, 0, 0, 0, 0);
        w.spawnParticle(Particle.FLAME, muzzle, 8, 0.1, 0.1, 0.1, 0.05);
        w.playSound(muzzle, u.type == UnitType.CANON ? Sound.ENTITY_GENERIC_EXPLODE : Sound.ENTITY_IRON_GOLEM_ATTACK, 1.2f, u.type == UnitType.CANON ? 1.4f : 0.6f);
        u.ammoCount--;
        if (u.ammoCount <= 0) {
            u.ammo = null;
            Msg.to(p, "§cSe acabó la munición. Cárgala con clic derecho sosteniendo munición.");
        } else if (u.ammoCount == 1) {
            Msg.to(p, "§eQueda 1 munición.");
        }
        u.lastFire = now;
        if (u.display != null) {
            Entity d = Bukkit.getEntity(u.display);
            if (d != null) d.setRotation(p.getLocation().getYaw(), u.type == UnitType.CANON ? Math.max(-35f, Math.min(15f, p.getLocation().getPitch())) : 0f);
        }
        save(u);
    }

    // ------------------------------------------------------------------ daño a unidades

    public void damageUnit(Unit u, double dmg, Player src) {
        u.hp -= dmg;
        Entity e = Bukkit.getEntity(u.slime);
        if (e != null) e.getWorld().playSound(e.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.6f);
        if (u.hp <= 0) {
            Guild g = plugin.guilds.byId(u.guildId);
            if (g != null) plugin.guilds.broadcast(g, "§c¡Han destruido tu " + u.type.display.toLowerCase() + "!");
            destroy(u, true);
        } else {
            save(u);
        }
    }

    // ------------------------------------------------------------------ proyectiles

    public boolean isSiegeProjectile(Entity e) {
        return e.getPersistentDataContainer().has(kProj, PersistentDataType.STRING);
    }

    private BlockDisplay spawnShotVisual(Location loc, Ammo a) {
        final Material m;
        final float size;
        switch (a) {
            case BALA_EXPLOSIVA -> {
                m = Material.TNT;
                size = 0.75f;
            }
            case BALA_INCENDIARIA -> {
                m = Material.MAGMA_BLOCK;
                size = 0.55f;
            }
            case PIEDRA_CATAPULTA -> {
                m = Material.COBBLESTONE;
                size = 0.95f;
            }
            case PIEDRA_FUEGO -> {
                m = Material.NETHERRACK;
                size = 0.9f;
            }
            default -> {
                m = Material.IRON_BLOCK;
                size = 0.5f;
            }
        }
        return loc.getWorld().spawn(loc, BlockDisplay.class, e -> {
            e.setBlock(m.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-size / 2f, -size / 2f, -size / 2f), new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(size, size, size), new AxisAngle4f(0f, 0f, 0f, 1f)));
            e.setBrightness(new Display.Brightness(15, 15));
            e.setViewRange(4f);
            e.setPersistent(false);
            e.setTeleportDuration(1);
        });
    }

    private void endShot(Shot sh) {
        if (sh.visual != null) sh.visual.remove();
        shots.remove(sh);
    }

    private boolean isShotTarget(Entity e, Shot sh, int age) {
        if (e instanceof BlockDisplay || e instanceof ItemDisplay) return false;
        if (e.getUniqueId().equals(sh.ownUnit)) return false;
        if (e instanceof Player pl) {
            if (pl.getUniqueId().equals(sh.shooter) && age < 15) return false;
            return pl.getGameMode() == GameMode.SURVIVAL || pl.getGameMode() == GameMode.ADVENTURE;
        }
        return plugin.raids.isNode(e) || units.containsKey(e.getUniqueId()) || plugin.raids.isGuard(e);
    }

    /** Se llama cada 2 ticks: avanza los proyectiles en 2 sub-pasos con detección de impacto. */
    public void tickProjectiles() {
        tickControl();
        for (Shot sh : new ArrayList<>(shots)) {
            World w = sh.pos.getWorld();
            boolean hit = false;
            Location impact = null;
            for (int step = 0; step < 2 && !hit; step++) {
                sh.age++;
                Vector v = sh.vel.clone();
                double len = v.length();
                if (len <= 0) break;
                var rt = w.rayTraceBlocks(sh.pos, v.clone().normalize(), len, FluidCollisionMode.NEVER, true);
                if (rt != null && rt.getHitPosition() != null) {
                    Vector hp = rt.getHitPosition().add(v.clone().normalize().multiply(0.25));
                    impact = new Location(w, hp.getX(), hp.getY(), hp.getZ());
                    hit = true;
                    break;
                }
                sh.pos.add(v);
                for (Entity e : w.getNearbyEntities(sh.pos, 1.3, 1.3, 1.3)) {
                    if (isShotTarget(e, sh, sh.age)) {
                        impact = sh.pos.clone();
                        hit = true;
                        break;
                    }
                }
                sh.vel.setY(sh.vel.getY() - 0.03);
                sh.vel.multiply(0.995);
                if (sh.pos.getY() < w.getMinHeight() || sh.age > 220) {
                    impact = sh.pos.clone();
                    hit = true;
                }
            }
            if (hit) {
                endShot(sh);
                impactAt(impact, sh.ammo, sh.gid, Bukkit.getPlayer(sh.shooter));
                continue;
            }
            if (sh.visual != null && !sh.visual.isDead()) sh.visual.teleport(sh.pos);
            w.spawnParticle(Particle.LARGE_SMOKE, sh.pos, 3, 0.08, 0.08, 0.08, 0.0);
            if (sh.ammo.fire) w.spawnParticle(Particle.FLAME, sh.pos, 3, 0.1, 0.1, 0.1, 0.01);
            else w.spawnParticle(Particle.SMOKE, sh.pos, 2, 0.05, 0.05, 0.05, 0.0);
        }
    }

    /** Compatibilidad: impacto de un proyectil vanilla etiquetado (ya no se usa para disparar). */
    public void impact(Projectile pr) {
        pr.remove();
    }

    public void impactAt(Location loc, Ammo a, String gid, Player shooter) {
        World w = loc.getWorld();
        var cfg = plugin.getConfig();
        double R = a.radius(cfg);
        w.spawnParticle(Particle.EXPLOSION, loc, 2, 0.3, 0.3, 0.3, 0);
        w.spawnParticle(Particle.LARGE_SMOKE, loc, 15, R / 3, R / 3, R / 3, 0.02);
        if (a.fire) w.spawnParticle(Particle.FLAME, loc, 25, R / 3, R / 3, R / 3, 0.04);
        w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.9f);
        impactFx(a, loc, R);

        if (gid == null) return;
        Raid raid = plugin.raids.activeRaidOfSide(gid);
        if (raid == null) return; // fuera de un asedio es solo espectáculo
        boolean attacker = raid.attackerId.equals(gid);
        String oppId = attacker ? raid.defenderId : raid.attackerId;
        applyImpact(raid, loc, a, gid, attacker, oppId, shooter, w, R);
    }

    @SuppressWarnings("unused")
    private void impactOld(Projectile pr) {
        String aid = pr.getPersistentDataContainer().get(kProj, PersistentDataType.STRING);
        String gid = pr.getPersistentDataContainer().get(kProjGuild, PersistentDataType.STRING);
        if (aid == null) return;
        flying.remove(pr.getUniqueId());
        Ammo a;
        try {
            a = Ammo.valueOf(aid);
        } catch (IllegalArgumentException e) {
            pr.remove();
            return;
        }
        Location loc = pr.getLocation();
        World w = loc.getWorld();
        pr.remove();
        var cfg = plugin.getConfig();
        double R = a.radius(cfg);
        if (gid == null) return;
        Raid raid = plugin.raids.activeRaidOfSide(gid);
        if (raid == null) return;
        boolean attacker = raid.attackerId.equals(gid);
        String oppId = attacker ? raid.defenderId : raid.attackerId;
        Player shooter = pr.getShooter() instanceof Player sp ? sp : null;
        applyImpact(raid, loc, a, gid, attacker, oppId, shooter, w, R);
    }

    private void applyImpact(Raid raid, Location loc, Ammo a, String gid, boolean attacker, String oppId, Player shooter, World w, double R) {
        var cfg = plugin.getConfig();
        if (attacker) {
            Location nl = plugin.raids.nodeLocation(raid);
            if (nl != null && nl.getWorld() == w && nl.distance(loc) <= R + 1.8) {
                plugin.raids.damageNode(raid, a.node(cfg), shooter);
                if (shooter != null) shooter.playSound(shooter.getLocation(), Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 1.2f);
            } else if (shooter != null) {
                Msg.actionbar(shooter, "§7Impacto fuera del Nodo.");
            }
        }
        for (Unit u : new ArrayList<>(units.values())) {
            if (!u.guildId.equals(oppId)) continue;
            Entity e = Bukkit.getEntity(u.slime);
            if (e != null && e.getWorld() == w && e.getLocation().distance(loc) <= R + 1.2) damageUnit(u, a.node(cfg) * 1.2, shooter);
        }
        for (Player pl : w.getPlayers()) {
            if (pl.getGameMode() != GameMode.SURVIVAL && pl.getGameMode() != GameMode.ADVENTURE) continue;
            Guild pg = plugin.guilds.of(pl);
            if (pg == null || !pg.id.equals(oppId)) continue;
            if (pl.getLocation().distance(loc) <= R + 0.6) {
                if (shooter != null) pl.damage(a.player(cfg), shooter);
                else pl.damage(a.player(cfg));
                if (a.fire) pl.setFireTicks(100);
            }
        }
        if (attacker) {
            plugin.gates.damageNear(raid, loc, a.node(cfg) * 0.7, R + 1.0, shooter);
            plugin.turrets.damageNear(oppId, loc, a.node(cfg), R + 1.2);
            for (Entity e : w.getNearbyEntities(loc, R + 0.6, R + 0.6, R + 0.6)) {
                if (plugin.raids.isGuard(e) && e instanceof LivingEntity le) le.damage(a.player(cfg) * 2.0);
            }
            blockDamage(raid, loc, a);
        }
    }

    /** Efectos propios de cada munición. */
    private void impactFx(Ammo a, Location loc, double R) {
        World w = loc.getWorld();
        switch (a) {
            case BALA_EXPLOSIVA -> {
                w.spawnParticle(Particle.EXPLOSION_EMITTER, loc, 1, 0, 0, 0, 0);
                w.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 2.2f, 0.6f);
            }
            case BALA_INCENDIARIA -> {
                w.spawnParticle(Particle.FLAME, loc, 40, R / 2, R / 3, R / 2, 0.05);
                w.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 1.6f, 0.7f);
            }
            case PIEDRA_CATAPULTA -> {
                w.spawnParticle(Particle.BLOCK, loc, 45, R / 2, R / 3, R / 2, 0.1, Material.STONE.createBlockData());
                w.playSound(loc, Sound.BLOCK_STONE_BREAK, 2f, 0.5f);
            }
            case PIEDRA_FUEGO -> {
                w.spawnParticle(Particle.BLOCK, loc, 30, R / 2, R / 3, R / 2, 0.1, Material.STONE.createBlockData());
                w.spawnParticle(Particle.FLAME, loc, 30, R / 2, R / 3, R / 2, 0.05);
                w.playSound(loc, Sound.ITEM_FIRECHARGE_USE, 1.6f, 0.6f);
            }
            default -> w.playSound(loc, Sound.BLOCK_ANVIL_LAND, 1.2f, 0.5f);
        }
    }

    private void blockDamage(Raid raid, Location loc, Ammo a) {
        Territory t = plugin.territories.get(raid.territoryId);
        if (t == null) return;
        var cfg = plugin.getConfig();
        double R = a.blocks(cfg);
        int ir = (int) Math.ceil(R);
        int max = cfg.getInt("asedio.max-bloques-por-impacto", 90);
        double maxBlast = cfg.getDouble("asedio.resistencia-maxima-bloque", 100);
        int cap = cfg.getInt("asedio.max-bloques-danados-por-territorio", 250000);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        World w = loc.getWorld();
        int bx = loc.getBlockX(), by = loc.getBlockY(), bz = loc.getBlockZ();
        int n = 0;
        outer:
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dy = -ir; dy <= ir; dy++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    if (d > R) continue;
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    if (!t.contains(x, y, z)) continue;
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir() || b.isLiquid()) continue;
                    if (m.getBlastResistance() > maxBlast) continue;
                    if (b.getState(false) instanceof Container) continue;
                    if (plugin.gates.at(b) != null) continue;
                    if (d > R * 0.6 && rnd.nextDouble() > 0.6) continue;
                    if (n >= max || plugin.territories.damageCount(t) >= cap) break outer;
                    plugin.territories.recordDamage(t, x, y, z, b.getBlockData().getAsString());
                    b.setType(Material.AIR, false);
                    n++;
                }
            }
        }
    }

    // ------------------------------------------------------------------ recetas

    public void registerRecipes() {
        if (!plugin.getConfig().getBoolean("asedio.crafteo", true)) return;
        shapeless("bala_hierro", SiegeItems.ammoItem(plugin, Ammo.BALA_HIERRO, 4), Material.IRON_INGOT, Material.IRON_INGOT, Material.GUNPOWDER);
        shapeless("bala_explosiva", SiegeItems.ammoItem(plugin, Ammo.BALA_EXPLOSIVA, 4), Material.IRON_INGOT, Material.GUNPOWDER, Material.GUNPOWDER, Material.TNT);
        shapeless("bala_incendiaria", SiegeItems.ammoItem(plugin, Ammo.BALA_INCENDIARIA, 4), Material.IRON_INGOT, Material.GUNPOWDER, Material.BLAZE_POWDER, Material.COAL);
        shapeless("piedra_catapulta", SiegeItems.ammoItem(plugin, Ammo.PIEDRA_CATAPULTA, 2), Material.STONE, Material.STONE, Material.COBBLESTONE, Material.COBBLESTONE);
        shapeless("piedra_fuego", SiegeItems.ammoItem(plugin, Ammo.PIEDRA_FUEGO, 2), Material.STONE, Material.STONE, Material.BLAZE_POWDER, Material.COAL);

        NamespacedKey k1 = plugin.key("recipe_canon");
        ShapedRecipe canon = new ShapedRecipe(k1, SiegeItems.unitItem(plugin, UnitType.CANON));
        canon.shape("III", "PBP", "WWW");
        canon.setIngredient('I', Material.IRON_INGOT);
        canon.setIngredient('P', Material.OAK_PLANKS);
        canon.setIngredient('B', Material.IRON_BLOCK);
        canon.setIngredient('W', Material.OAK_LOG);
        Bukkit.addRecipe(canon);
        recipeKeys.add(k1);

        NamespacedKey k3 = plugin.key("recipe_ariete");
        ShapedRecipe ram = new ShapedRecipe(k3, SiegeItems.unitItem(plugin, UnitType.ARIETE));
        ram.shape("III", "LLL", "P P");
        ram.setIngredient('I', Material.IRON_BLOCK);
        ram.setIngredient('L', Material.OAK_LOG);
        ram.setIngredient('P', Material.OAK_PLANKS);
        Bukkit.addRecipe(ram);
        recipeKeys.add(k3);

        NamespacedKey k2 = plugin.key("recipe_catapulta");
        ShapedRecipe cat = new ShapedRecipe(k2, SiegeItems.unitItem(plugin, UnitType.CATAPULTA));
        cat.shape("SPS", "PLP", "WWW");
        cat.setIngredient('S', Material.STICK);
        cat.setIngredient('P', Material.OAK_PLANKS);
        cat.setIngredient('L', Material.STRING);
        cat.setIngredient('W', Material.OAK_LOG);
        Bukkit.addRecipe(cat);
        recipeKeys.add(k2);
    }

    private void shapeless(String id, ItemStack result, Material... mats) {
        NamespacedKey k = plugin.key("recipe_" + id);
        ShapelessRecipe r = new ShapelessRecipe(k, result);
        for (Material m : mats) r.addIngredient(m);
        Bukkit.addRecipe(r);
        recipeKeys.add(k);
    }

    public void unregisterRecipes() {
        for (NamespacedKey k : recipeKeys) Bukkit.removeRecipe(k);
        recipeKeys.clear();
    }

    public void shutdown() {
        controlling.clear();
        for (Shot sh : new ArrayList<>(shots)) endShot(sh);
        for (Unit u : units.values()) save(u);
        unregisterRecipes();
    }
}
