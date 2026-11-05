package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Rank;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.ItemBuilder;
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
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Torres de ballesta: defensa automática del gremio dueño durante un asedio. Se compran con el banco y se mejoran. */
public class TurretManager {
    public static class Turret {
        public UUID slime;
        public UUID base, bow;
        public String guildId;
        public int level = 1;
        public double hp;
        public long lastShot;
    }

    private final GremiosPlugin plugin;
    private final Map<UUID, Turret> turrets = new HashMap<>();
    private final NamespacedKey kGuild, kLevel, kHp, kBase, kBow, kItemLevel, kPart;

    public TurretManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        kGuild = plugin.key("turret_guild");
        kLevel = plugin.key("turret_level");
        kHp = plugin.key("turret_hp");
        kBase = plugin.key("turret_base");
        kBow = plugin.key("turret_bow");
        kItemLevel = plugin.key("turret_item_level");
        kPart = plugin.key("turret_part");
    }

    // ------------------------------------------------------------------ config

    private double cfg(String path, int level, double def) {
        List<Double> l = plugin.getConfig().getDoubleList(path);
        if (l.isEmpty()) return def;
        return l.get(Math.max(0, Math.min(l.size() - 1, level - 1)));
    }

    public double maxHp(int level) {
        return cfg("torres.vida", level, 150);
    }

    public int cap(int territoryLevel) {
        return (int) cfg("torres.maximo-por-nivel-territorio", territoryLevel, 2);
    }

    public long buyPrice() {
        return plugin.getConfig().getLong("torres.precio", 120);
    }

    public long upgradeCost(int fromLevel) {
        List<Long> l = plugin.getConfig().getLongList("torres.mejora");
        if (fromLevel - 1 < 0 || fromLevel - 1 >= l.size()) return -1;
        return l.get(fromLevel - 1);
    }

    // ------------------------------------------------------------------ items

    public ItemStack item(int level) {
        return new ItemBuilder(Material.CROSSBOW)
                .name("§6Torre de Ballesta §7(nivel " + level + ")")
                .lore("§7Clic derecho en el suelo de tu territorio para colocarla.",
                        "§7Dispara sola a los atacantes durante un asedio.",
                        "§7Agáchate + clic derecho para recogerla.")
                .tag(plugin.key("siege_item"), "TURRET")
                .tag(kItemLevel, String.valueOf(level))
                .build();
    }

    public boolean isItem(ItemStack it) {
        return "TURRET".equals(SiegeItems.tag(plugin, it));
    }

    public int itemLevel(ItemStack it) {
        try {
            String s = it.getItemMeta().getPersistentDataContainer().get(kItemLevel, PersistentDataType.STRING);
            return s == null ? 1 : Math.max(1, Math.min(3, Integer.parseInt(s)));
        } catch (Exception e) {
            return 1;
        }
    }

    // ------------------------------------------------------------------ registro

    public Turret of(Entity e) {
        return e == null ? null : turrets.get(e.getUniqueId());
    }

    public List<Turret> of(Guild g) {
        List<Turret> l = new ArrayList<>();
        for (Turret t : turrets.values()) if (t.guildId.equals(g.id)) l.add(t);
        return l;
    }

    public int countIn(Guild g, Territory t) {
        int n = 0;
        for (Turret tu : of(g)) {
            Entity e = Bukkit.getEntity(tu.slime);
            if (e != null && t.contains(e.getLocation())) n++;
        }
        return n;
    }

    public void register(Slime s) {
        var pdc = s.getPersistentDataContainer();
        String gid = pdc.get(kGuild, PersistentDataType.STRING);
        if (gid == null) return;
        if (plugin.guilds.byId(gid) == null) {
            removeParts(s);
            s.remove();
            return;
        }
        Turret t = new Turret();
        t.slime = s.getUniqueId();
        t.guildId = gid;
        Integer lv = pdc.get(kLevel, PersistentDataType.INTEGER);
        t.level = lv == null ? 1 : lv;
        Double hp = pdc.get(kHp, PersistentDataType.DOUBLE);
        t.hp = hp == null ? maxHp(t.level) : hp;
        String b = pdc.get(kBase, PersistentDataType.STRING), w = pdc.get(kBow, PersistentDataType.STRING);
        try {
            t.base = b == null ? null : UUID.fromString(b);
            t.bow = w == null ? null : UUID.fromString(w);
        } catch (IllegalArgumentException ignored) {
        }
        turrets.put(t.slime, t);
    }

    public void rescan() {
        for (World w : Bukkit.getWorlds()) for (Slime s : w.getEntitiesByClass(Slime.class)) register(s);
        // pantallas huérfanas
        java.util.Set<UUID> keep = new java.util.HashSet<>();
        for (Turret t : turrets.values()) {
            if (t.base != null) keep.add(t.base);
            if (t.bow != null) keep.add(t.bow);
        }
        for (World w : Bukkit.getWorlds()) {
            for (Display d : w.getEntitiesByClass(Display.class)) {
                if (d.getPersistentDataContainer().has(kPart, PersistentDataType.STRING) && !keep.contains(d.getUniqueId())) d.remove();
            }
        }
    }

    private void removeParts(Entity s) {
        var pdc = s.getPersistentDataContainer();
        for (NamespacedKey k : new NamespacedKey[]{kBase, kBow}) {
            String id = pdc.get(k, PersistentDataType.STRING);
            if (id == null) continue;
            try {
                Entity d = Bukkit.getEntity(UUID.fromString(id));
                if (d != null) d.remove();
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save(Turret t) {
        Entity e = Bukkit.getEntity(t.slime);
        if (e == null) return;
        e.getPersistentDataContainer().set(kLevel, PersistentDataType.INTEGER, t.level);
        e.getPersistentDataContainer().set(kHp, PersistentDataType.DOUBLE, t.hp);
    }

    // ------------------------------------------------------------------ colocar / recoger / mejorar

    public String place(Player p, Location loc, int level) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return "Necesitas un gremio.";
        Rank r = g.rankOf(p.getUniqueId());
        if (r == null || r.id < Rank.MIEMBRO.id) return "Los reclutas no pueden colocar torres.";
        Territory t = plugin.territories.at(loc);
        if (t == null || !g.id.equals(t.ownerGuildId)) return "Solo puedes colocar torres dentro de tu territorio.";
        int cap = cap(g.territoryLevel);
        if (countIn(g, t) >= cap) return "Tu territorio solo admite " + cap + " torres (sube de nivel para más).";
        World w = loc.getWorld();
        final String gid = g.id;
        Slime s = w.spawn(loc, Slime.class, e -> {
            e.setSize(2);
            e.setAI(false);
            e.setInvisible(true);
            e.setSilent(true);
            e.setCollidable(false);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCustomName("§6Torre de Ballesta");
            e.setCustomNameVisible(false);
            e.getPersistentDataContainer().set(kGuild, PersistentDataType.STRING, gid);
        });
        final boolean pack = plugin.usePack();
        // con resourcepack: un solo modelo propio; sin él: base de dispensador + ballesta
        BlockDisplay base = pack ? null : w.spawn(loc, BlockDisplay.class, e -> {
            e.setBlock(Material.DISPENSER.createBlockData());
            e.setTransformation(new Transformation(new Vector3f(-0.4f, 0f, -0.4f), new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(0.8f, 1.0f, 0.8f), new AxisAngle4f(0f, 0f, 0f, 1f)));
            e.setViewRange(1.5f);
            e.setPersistent(true);
            e.getPersistentDataContainer().set(kPart, PersistentDataType.STRING, "1");
        });
        ItemDisplay bow = w.spawn(loc, ItemDisplay.class, e -> {
            e.setItemStack(pack ? SiegeItems.displayItem(SiegeItems.turretCmd(level)) : new ItemStack(Material.CROSSBOW));
            e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            float sc = pack ? 1.8f : 1.3f;
            e.setTransformation(new Transformation(new Vector3f(0f, pack ? 0.9f : 1.35f, 0f), new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(sc, sc, sc), new AxisAngle4f(0f, 0f, 0f, 1f)));
            e.setViewRange(1.5f);
            e.setTeleportDuration(2);
            e.setPersistent(true);
            e.getPersistentDataContainer().set(kPart, PersistentDataType.STRING, "1");
        });
        if (base != null) s.getPersistentDataContainer().set(kBase, PersistentDataType.STRING, base.getUniqueId().toString());
        s.getPersistentDataContainer().set(kBow, PersistentDataType.STRING, bow.getUniqueId().toString());
        Turret t2 = new Turret();
        t2.slime = s.getUniqueId();
        t2.base = base == null ? null : base.getUniqueId();
        t2.bow = bow.getUniqueId();
        t2.guildId = g.id;
        t2.level = level;
        t2.hp = maxHp(level);
        turrets.put(t2.slime, t2);
        save(t2);
        w.playSound(loc, Sound.BLOCK_ANVIL_PLACE, 1f, 1.2f);
        return null;
    }

    public void destroy(Turret t, boolean effects) {
        Entity s = Bukkit.getEntity(t.slime);
        if (s != null) {
            if (effects) {
                Location l = s.getLocation().add(0, 1, 0);
                l.getWorld().spawnParticle(Particle.EXPLOSION, l, 2, 0.3, 0.3, 0.3, 0);
                l.getWorld().playSound(l, Sound.ENTITY_GENERIC_EXPLODE, 1f, 1.2f);
            }
            removeParts(s);
            s.remove();
        }
        turrets.remove(t.slime);
    }

    public void pickup(Player p, Turret t) {
        if (!owns(p, t)) {
            Msg.to(p, "§cEsa torre no es de tu gremio.");
            return;
        }
        ItemStack it = item(t.level);
        for (ItemStack o : p.getInventory().addItem(it).values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
        destroy(t, false);
        Msg.to(p, "§aHas recogido la torre.");
    }

    public boolean owns(Player p, Turret t) {
        if (p.hasPermission("gremios.admin")) return true;
        Guild g = plugin.guilds.of(p);
        return g != null && g.id.equals(t.guildId);
    }

    public String upgrade(Player p, Turret t) {
        Guild g = plugin.guilds.byId(t.guildId);
        if (g == null || !owns(p, t)) return "Esa torre no es de tu gremio.";
        Rank r = g.rankOf(p.getUniqueId());
        if (r == null || !r.atLeast(Rank.MARISCAL)) return "Solo Mariscal o superior puede mejorar torres.";
        if (t.level >= 3) return "Ya está al nivel máximo.";
        long cost = upgradeCost(t.level);
        if (cost < 0) return "No hay mejora configurada.";
        if (g.bank < cost) return "El banco necesita " + plugin.currency.fmt(cost) + ".";
        g.bank -= cost;
        g.log("§6-" + Msg.num(cost) + " §7mejora de torre");
        t.level++;
        t.hp = maxHp(t.level);
        if (t.bow != null && Bukkit.getEntity(t.bow) instanceof ItemDisplay bd && plugin.usePack())
            bd.setItemStack(SiegeItems.displayItem(SiegeItems.turretCmd(t.level)));
        save(t);
        plugin.guilds.save();
        Entity e = Bukkit.getEntity(t.slime);
        if (e != null) e.getWorld().playSound(e.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.4f);
        return null;
    }

    public String buy(Player p) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return "Necesitas un gremio.";
        Rank r = g.rankOf(p.getUniqueId());
        if (r == null || !r.atLeast(Rank.MARISCAL)) return "Solo Mariscal o superior puede comprar torres.";
        if (!g.hasTerritory()) return "Tu gremio necesita un territorio.";
        long price = buyPrice();
        if (g.bank < price) return "El banco necesita " + plugin.currency.fmt(price) + ".";
        g.bank -= price;
        g.log("§6-" + Msg.num(price) + " §7torre de ballesta");
        for (ItemStack o : p.getInventory().addItem(item(1)).values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
        plugin.guilds.save();
        return null;
    }

    // ------------------------------------------------------------------ daño

    public void damage(Turret t, double dmg) {
        t.hp -= dmg;
        Entity e = Bukkit.getEntity(t.slime);
        if (e != null) e.getWorld().playSound(e.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.6f);
        if (t.hp <= 0) {
            Guild g = plugin.guilds.byId(t.guildId);
            if (g != null) plugin.guilds.broadcast(g, "§c¡Han destruido una torre de ballesta!");
            destroy(t, true);
        } else save(t);
    }

    public void damageNear(String oppId, Location loc, double dmg, double radius) {
        for (Turret t : new ArrayList<>(turrets.values())) {
            if (!t.guildId.equals(oppId)) continue;
            Entity e = Bukkit.getEntity(t.slime);
            if (e != null && e.getWorld() == loc.getWorld() && e.getLocation().distance(loc) <= radius) damage(t, dmg);
        }
    }

    // ------------------------------------------------------------------ disparo automático (cada 10 ticks)

    public void tick() {
        if (turrets.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Turret t : new ArrayList<>(turrets.values())) {
            Entity e = Bukkit.getEntity(t.slime);
            if (e == null) continue;
            Raid raid = plugin.raids.activeRaidOfSide(t.guildId);
            if (raid == null || raid.test || !raid.defenderId.equals(t.guildId)) continue;
            double wait = cfg("torres.espera-segundos", t.level, 2.0) * 1000;
            if (now - t.lastShot < wait) continue;
            double range = cfg("torres.alcance", t.level, 18);
            Location from = e.getLocation().add(0, 1.6, 0);
            Player best = null;
            double bd = range * range;
            for (Player pl : e.getWorld().getPlayers()) {
                if (pl.getGameMode() != GameMode.SURVIVAL && pl.getGameMode() != GameMode.ADVENTURE) continue;
                Guild pg = plugin.guilds.of(pl);
                if (pg == null || !pg.id.equals(raid.attackerId)) continue;
                Location to = pl.getLocation().add(0, 1.0, 0);
                double d = to.distanceSquared(from);
                if (d >= bd) continue;
                Vector dir = to.toVector().subtract(from.toVector());
                var rt = e.getWorld().rayTraceBlocks(from, dir.clone().normalize(), Math.sqrt(d), org.bukkit.FluidCollisionMode.NEVER, true);
                if (rt != null) continue; // sin línea de visión
                bd = d;
                best = pl;
            }
            if (best == null) continue;
            t.lastShot = now;
            shoot(t, from, best);
        }
    }

    private void shoot(Turret t, Location from, Player target) {
        Location to = target.getLocation().add(0, 1.0, 0);
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        dir.normalize();
        World w = from.getWorld();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(230, 200, 120), 0.8f);
        for (double d = 0.5; d < len; d += 0.6) w.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0, dust);
        w.playSound(from, Sound.ITEM_CROSSBOW_SHOOT, 1.4f, 1.0f);
        w.playSound(to, Sound.ENTITY_ARROW_HIT_PLAYER, 1f, 0.8f);
        target.damage(cfg("torres.dano", t.level, 4));
        if (t.bow != null) {
            Entity b = Bukkit.getEntity(t.bow);
            if (b != null) {
                float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
                float pitch = (float) Math.toDegrees(-Math.asin(dir.getY()));
                b.setRotation(yaw, pitch);
            }
        }
    }

    public void removeIn(Territory t) {
        for (Turret tu : new ArrayList<>(turrets.values())) {
            Entity e = Bukkit.getEntity(tu.slime);
            if (e != null && t.contains(e.getLocation())) destroy(tu, false);
        }
    }

    public void shutdown() {
        // las entidades persisten; nada que limpiar
    }
}
