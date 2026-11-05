package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Zonas de recursos (mina, tala, cantera...). Las conquista un gremio O un jugador sin gremio manteniéndose
 * dentro sin que haya otro bando. El dueño recibe recursos cada X minutos y los recoge hablando con el aldeano de la zona.
 */
public class ZoneManager {
    public static class Zone {
        public final String id;
        public String name, kind = "MINA", world;
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public boolean hasArea;
        public double[] npc; // x,y,z,yaw
        public UUID npcUuid;
        /** Recursos por ciclo (los elige el admin). */
        public final List<ItemStack> produce = new ArrayList<>();
        /** Almacén acumulado (mismo orden que produce). */
        public final List<Long> stock = new ArrayList<>();
        public int intervalMin = 30;
        public int maxCycles = 8;
        public int captureSeconds = 120;
        /** "G:<idGremio>" o "P:<uuid>" o null. */
        public String owner;
        public String ownerName;
        public long nextProduce;
        /** Desde cuándo la tiene el dueño actual (sube de nivel con el tiempo). */
        public long ownedSince;
        /** Hasta cuándo no se puede empezar a conquistar (anti ping-pong tras una captura). */
        public long protectedUntil;
        public int announcedLevel = 1;

        // transitorio
        public String capturer;
        public int progress;
        public BossBar bar;

        public Zone(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public boolean contains(Location l) {
            if (!hasArea || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
            return l.getBlockX() >= minX && l.getBlockX() <= maxX && l.getBlockY() >= minY && l.getBlockY() <= maxY
                    && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
        }
    }

    private final GremiosPlugin plugin;
    private final File file;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final NamespacedKey kZone;

    public ZoneManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zonas.yml");
        this.kZone = plugin.key("zone_npc");
        load();
    }

    public java.util.Collection<Zone> all() {
        return zones.values();
    }

    public Zone get(String id) {
        return id == null ? null : zones.get(id.toLowerCase(Locale.ROOT));
    }

    public NamespacedKey key() {
        return kZone;
    }

    public Zone zoneOfNpc(Entity e) {
        String id = e.getPersistentDataContainer().get(kZone, PersistentDataType.STRING);
        return id == null ? null : zones.get(id);
    }

    // ------------------------------------------------------------------ nivel de zona

    public int maxLevel() {
        return Math.max(1, plugin.getConfig().getInt("zonas.nivel-maximo", 5));
    }

    /** Nivel según el tiempo que lleva en manos del dueño actual. */
    public int levelOf(Zone z) {
        if (z.owner == null || z.ownedSince <= 0) return 1;
        double hours = (System.currentTimeMillis() - z.ownedSince) / 3_600_000.0;
        double per = Math.max(0.01, plugin.getConfig().getDouble("zonas.horas-por-nivel", 6));
        return Math.max(1, Math.min(maxLevel(), 1 + (int) (hours / per)));
    }

    public double multOf(int level) {
        List<Double> l = plugin.getConfig().getDoubleList("zonas.multiplicador-por-nivel");
        if (l.isEmpty()) return 1.0 + (level - 1) * 0.25;
        return l.get(Math.max(0, Math.min(l.size() - 1, level - 1)));
    }

    /** Cantidad producida por ciclo de un recurso, según el nivel actual. */
    public int perCycle(Zone z, int i) {
        return Math.max(1, (int) Math.round(z.produce.get(i).getAmount() * multOf(levelOf(z))));
    }

    // ------------------------------------------------------------------ bandos

    public String factionOf(Player p) {
        Guild g = plugin.guilds.of(p);
        return g != null ? "G:" + g.id : "P:" + p.getUniqueId();
    }

    public String factionName(String f) {
        if (f == null) return "Nadie";
        if (f.startsWith("G:")) {
            Guild g = plugin.guilds.byId(f.substring(2));
            return g == null ? "Gremio desaparecido" : g.name;
        }
        try {
            String n = Bukkit.getOfflinePlayer(UUID.fromString(f.substring(2))).getName();
            return n == null ? "Jugador" : n;
        } catch (IllegalArgumentException e) {
            return "Jugador";
        }
    }

    public boolean isOwner(Player p, Zone z) {
        return z.owner != null && z.owner.equals(factionOf(p));
    }

    // ------------------------------------------------------------------ admin

    public String create(String id, String kind, String name) {
        id = id.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]{1,24}")) return "Id inválido (letras, números, _ y -).";
        if (zones.containsKey(id)) return "Ya existe una zona con ese id.";
        Zone z = new Zone(id, name);
        z.kind = kind.toUpperCase(Locale.ROOT);
        zones.put(id, z);
        save();
        return null;
    }

    public void delete(Zone z) {
        removeNpc(z);
        if (z.bar != null) z.bar.removeAll();
        zones.remove(z.id);
        save();
    }

    public String setArea(Zone z, Location a, Location b) {
        if (a == null || b == null) return "Selecciona la zona con la varita (pos 1 y pos 2).";
        if (a.getWorld() != b.getWorld()) return "Las dos posiciones deben estar en el mismo mundo.";
        z.world = a.getWorld().getName();
        z.minX = Math.min(a.getBlockX(), b.getBlockX());
        z.maxX = Math.max(a.getBlockX(), b.getBlockX());
        z.minY = Math.min(a.getBlockY(), b.getBlockY());
        z.maxY = Math.max(a.getBlockY(), b.getBlockY());
        z.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        z.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        z.hasArea = true;
        save();
        return null;
    }

    public void setNpc(Zone z, Location l) {
        z.npc = new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw()};
        z.world = z.world == null ? l.getWorld().getName() : z.world;
        save();
        syncNpc(z);
    }

    /** Añade un recurso por ciclo (el objeto de la mano, con la cantidad indicada). */
    public void addProduce(Zone z, ItemStack item, int amount) {
        ItemStack one = item.clone();
        one.setAmount(1);
        // mismo objeto: sumar cantidad
        for (int i = 0; i < z.produce.size(); i++) {
            ItemStack e = z.produce.get(i);
            if (e.isSimilar(one)) {
                e.setAmount(Math.min(64, e.getAmount() + amount));
                save();
                return;
            }
        }
        one.setAmount(Math.max(1, Math.min(64, amount)));
        z.produce.add(one);
        z.stock.add(0L);
        save();
    }

    public void clearProduce(Zone z) {
        z.produce.clear();
        z.stock.clear();
        save();
    }

    public String setField(Zone z, String field, String value) {
        try {
            switch (field.toLowerCase(Locale.ROOT)) {
                case "nombre" -> z.name = value;
                case "tipo" -> z.kind = value.toUpperCase(Locale.ROOT);
                case "cada" -> z.intervalMin = Math.max(1, Math.min(10080, Integer.parseInt(value)));
                case "capacidad" -> z.maxCycles = Math.max(1, Math.min(1000, Integer.parseInt(value)));
                case "captura" -> z.captureSeconds = Math.max(10, Math.min(86400, Integer.parseInt(value)));
                default -> {
                    return "Campo desconocido: " + field;
                }
            }
        } catch (NumberFormatException e) {
            return "Número inválido: " + value;
        }
        save();
        syncNpc(z);
        return null;
    }

    public void release(Zone z) {
        z.owner = null;
        z.ownerName = null;
        z.capturer = null;
        z.progress = 0;
        z.ownedSince = 0;
        z.announcedLevel = 1;
        for (int i = 0; i < z.stock.size(); i++) z.stock.set(i, 0L);
        save();
        syncNpc(z);
    }

    // ------------------------------------------------------------------ NPC (aldeano)

    private void removeNpc(Zone z) {
        if (z.npcUuid != null) {
            Entity e = Bukkit.getEntity(z.npcUuid);
            if (e != null) e.remove();
        }
        z.npcUuid = null;
        if (z.npc != null && z.world != null) {
            World w = Bukkit.getWorld(z.world);
            if (w != null) {
                for (Entity e : w.getNearbyEntities(new Location(w, z.npc[0], z.npc[1], z.npc[2]), 2, 3, 2)) {
                    if (z.id.equals(e.getPersistentDataContainer().get(kZone, PersistentDataType.STRING))) e.remove();
                }
            }
        }
    }

    public String npcName(Zone z) {
        String owner = z.owner == null ? "§aLibre" : "§6" + factionName(z.owner) + " §e★" + levelOf(z);
        return "§e§l" + z.name + " §8| " + owner;
    }

    /** Aldeano de la zona: mantiene el chunk cargado, elimina duplicados y adopta el existente. */
    public void syncNpc(Zone z) {
        if (z.npc == null || z.world == null) return;
        World w = Bukkit.getWorld(z.world);
        if (w == null) return;
        Location l = new Location(w, z.npc[0], z.npc[1], z.npc[2], (float) z.npc[3], 0f);
        l.getChunk().load();
        l.getChunk().addPluginChunkTicket(plugin);
        Entity cur = null;
        for (Entity e : w.getNearbyEntities(l, 3, 4, 3)) {
            if (!z.id.equals(e.getPersistentDataContainer().get(kZone, PersistentDataType.STRING)) || e.isDead()) continue;
            if (cur == null) cur = e;
            else e.remove(); // duplicado
        }
        if (cur == null) {
            final String zid = z.id;
            cur = w.spawn(l, Villager.class, e -> {
                e.setAI(false);
                e.setInvulnerable(true);
                e.setSilent(true);
                e.setPersistent(true);
                e.setRemoveWhenFarAway(false);
                e.setCollidable(false);
                e.setInvisible(plugin.models != null && plugin.models.enabled());
                e.getPersistentDataContainer().set(kZone, PersistentDataType.STRING, zid);
            });
        }
        z.npcUuid = cur.getUniqueId();
        if (cur instanceof Villager v) {
            v.setProfession(switch (z.kind) {
                case "TALA" -> Villager.Profession.FLETCHER;
                case "CANTERA" -> Villager.Profession.MASON;
                case "GRANJA" -> Villager.Profession.FARMER;
                case "PESCA" -> Villager.Profession.FISHERMAN;
                default -> Villager.Profession.TOOLSMITH;
            });
            v.setCustomName(npcName(z));
            v.setCustomNameVisible(true);
        }
    }

    /** Un aldeano de zona se cargó (chunk): si ya hay otro vigente, este sobra. */
    public void onNpcLoaded(Villager v) {
        Zone z = zoneOfNpc(v);
        if (z == null) return;
        if (z.npcUuid != null && !z.npcUuid.equals(v.getUniqueId())) {
            Entity other = Bukkit.getEntity(z.npcUuid);
            if (other != null && !other.isDead()) {
                v.remove();
                return;
            }
        }
        z.npcUuid = v.getUniqueId();
    }

    public void syncAll() {
        for (Zone z : zones.values()) syncNpc(z);
    }

    // ------------------------------------------------------------------ producción y almacén

    public long cap(Zone z, int i) {
        return (long) perCycle(z, i) * z.maxCycles;
    }

    private void produceTick(Zone z, long now) {
        if (z.owner == null || z.produce.isEmpty()) return;
        if (z.nextProduce == 0) z.nextProduce = now + z.intervalMin * 60_000L;
        while (now >= z.nextProduce) {
            for (int i = 0; i < z.produce.size(); i++) {
                long add = perCycle(z, i);
                z.stock.set(i, Math.min(cap(z, i), z.stock.get(i) + add));
            }
            z.nextProduce += z.intervalMin * 60_000L;
        }
    }

    public long totalStock(Zone z) {
        long n = 0;
        for (long s : z.stock) n += s;
        return n;
    }

    /** Entrega al jugador lo que haya en el almacén. Devuelve cuántos objetos entregó. */
    public long collect(Player p, Zone z, int index) {
        long total = 0;
        for (int i = 0; i < z.produce.size(); i++) {
            if (index >= 0 && i != index) continue;
            long left = z.stock.get(i);
            while (left > 0) {
                ItemStack it = z.produce.get(i).clone();
                int n = (int) Math.min(left, it.getMaxStackSize());
                it.setAmount(n);
                var rest = p.getInventory().addItem(it);
                if (!rest.isEmpty()) {
                    int back = 0;
                    for (ItemStack r : rest.values()) back += r.getAmount();
                    left -= (n - back);
                    total += (n - back);
                    z.stock.set(i, left);
                    save();
                    return total;
                }
                left -= n;
                total += n;
            }
            z.stock.set(i, 0L);
        }
        save();
        return total;
    }

    // ------------------------------------------------------------------ conquista (cada segundo)

    public void tick() {
        long now = System.currentTimeMillis();
        for (Zone z : zones.values()) {
            if (!z.hasArea) continue;
            produceTick(z, now);
            if (z.owner != null) {
                int lv = levelOf(z);
                if (lv > z.announcedLevel) {
                    z.announcedLevel = lv;
                    syncNpc(z);
                    String m = "§6⛏ §e" + z.name + " §7sube al §enivel " + lv + " §7(×" + String.format("%.2f", multOf(lv)) + " producción).";
                    if (z.owner.startsWith("G:")) plugin.guilds.broadcast(plugin.guilds.byId(z.owner.substring(2)), m);
                    else {
                        try {
                            Player op = Bukkit.getPlayer(UUID.fromString(z.owner.substring(2)));
                            if (op != null) Msg.to(op, m);
                        } catch (IllegalArgumentException ignored) {
                        }
                    }
                    save();
                }
            }
            Map<String, List<Player>> inside = new HashMap<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
                if (p.isDead() || !z.contains(p.getLocation())) continue;
                inside.computeIfAbsent(factionOf(p), k -> new ArrayList<>()).add(p);
            }
            if (z.bar == null) z.bar = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SEGMENTED_10);
            // mostrar la barra solo a los que están dentro
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (z.contains(p.getLocation())) z.bar.addPlayer(p);
                else z.bar.removePlayer(p);
            }
            if (inside.isEmpty()) {
                if (z.progress > 0) z.progress = Math.max(0, z.progress - 1);
                if (z.progress == 0) z.capturer = null;
            } else if (inside.size() > 1) {
                z.bar.setColor(BarColor.RED);
                z.bar.setProgress(Math.max(0, Math.min(1, z.progress / (double) z.captureSeconds)));
                z.bar.setTitle("§c⚔ " + z.name + " §8| §cEn disputa");
                continue;
            } else {
                String f = inside.keySet().iterator().next();
                if (f.equals(z.owner)) {
                    // el dueño la defiende: el progreso rival baja rápido
                    z.progress = Math.max(0, z.progress - 3);
                    if (z.progress == 0) z.capturer = null;
                } else if (now < z.protectedUntil) {
                    for (Player p : inside.get(f)) Msg.actionbar(p, "§7La zona está protegida " + Msg.dur(z.protectedUntil - now) + " tras la última conquista.");
                } else {
                    if (z.capturer == null || !z.capturer.equals(f)) {
                        z.capturer = f;
                        z.progress = 0;
                    }
                    z.progress++;
                    for (Player p : inside.get(f)) Msg.actionbar(p, "§6Conquistando §f" + z.name + "§7: §e" + z.progress + "§7/§e" + z.captureSeconds + "s");
                    if (z.progress >= z.captureSeconds) capture(z, f, inside.get(f));
                }
            }
            z.bar.setColor(z.capturer == null ? BarColor.GREEN : BarColor.YELLOW);
            z.bar.setProgress(Math.max(0, Math.min(1, z.progress / (double) z.captureSeconds)));
            z.bar.setTitle("§e" + z.name + " §8| " + (z.owner == null ? "§aLibre" : "§6" + factionName(z.owner))
                    + (z.capturer != null ? " §8| §eConquista: " + factionName(z.capturer) : ""));
        }
    }

    private void capture(Zone z, String faction, List<Player> who) {
        String old = z.owner;
        z.owner = faction;
        z.ownerName = factionName(faction);
        z.capturer = null;
        z.progress = 0;
        z.ownedSince = System.currentTimeMillis();
        z.announcedLevel = 1;
        z.protectedUntil = System.currentTimeMillis() + plugin.getConfig().getLong("zonas.proteccion-tras-captura-segundos", 300) * 1000L;
        z.nextProduce = System.currentTimeMillis() + z.intervalMin * 60_000L;
        for (int i = 0; i < z.stock.size(); i++) z.stock.set(i, 0L);
        syncNpc(z);
        save();
        Bukkit.broadcastMessage(Msg.PREFIX + "§6⛏ " + z.ownerName + " §7ha conquistado §e" + z.name + (old != null ? " §7(antes de " + factionName(old) + ")" : "") + "§7.");
        for (Player p : who) p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        if (old != null && old.startsWith("G:")) plugin.guilds.broadcast(plugin.guilds.byId(old.substring(2)), "§c¡Habéis perdido §f" + z.name + "§c!");
    }

    public void shutdown() {
        for (Zone z : zones.values()) if (z.bar != null) z.bar.removeAll();
    }

    // ------------------------------------------------------------------ persistencia

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("zonas");
        for (Zone z : zones.values()) {
            ConfigurationSection s = root.createSection(z.id);
            s.set("nombre", z.name);
            s.set("tipo", z.kind);
            s.set("mundo", z.world);
            s.set("area", z.hasArea);
            s.set("min", List.of(z.minX, z.minY, z.minZ));
            s.set("max", List.of(z.maxX, z.maxY, z.maxZ));
            if (z.npc != null) s.set("npc", List.of(z.npc[0], z.npc[1], z.npc[2], z.npc[3]));
            s.set("cada", z.intervalMin);
            s.set("capacidad", z.maxCycles);
            s.set("captura", z.captureSeconds);
            s.set("dueno", z.owner);
            s.set("duenoNombre", z.ownerName);
            s.set("proxima", z.nextProduce);
            s.set("desde", z.ownedSince);
            s.set("protegidaHasta", z.protectedUntil);
            List<ItemStack> items = new ArrayList<>(z.produce);
            s.set("recursos", items);
            s.set("almacen", new ArrayList<>(z.stock));
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar zonas.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("zonas");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            try {
                Zone z = new Zone(id, s.getString("nombre", id));
                z.kind = s.getString("tipo", "MINA");
                z.world = s.getString("mundo");
                z.hasArea = s.getBoolean("area");
                List<Integer> mn = s.getIntegerList("min"), mx = s.getIntegerList("max");
                if (mn.size() == 3 && mx.size() == 3) {
                    z.minX = mn.get(0);
                    z.minY = mn.get(1);
                    z.minZ = mn.get(2);
                    z.maxX = mx.get(0);
                    z.maxY = mx.get(1);
                    z.maxZ = mx.get(2);
                }
                List<Double> n = s.getDoubleList("npc");
                if (n.size() == 4) z.npc = new double[]{n.get(0), n.get(1), n.get(2), n.get(3)};
                z.intervalMin = s.getInt("cada", 30);
                z.maxCycles = s.getInt("capacidad", 8);
                z.captureSeconds = s.getInt("captura", 120);
                z.owner = s.getString("dueno");
                z.ownerName = s.getString("duenoNombre");
                z.nextProduce = s.getLong("proxima");
                z.ownedSince = s.getLong("desde");
                z.protectedUntil = s.getLong("protegidaHasta");
                z.announcedLevel = levelOf(z);
                for (Object o : s.getList("recursos", new ArrayList<>())) if (o instanceof ItemStack it) z.produce.add(it);
                for (Object o : s.getList("almacen", new ArrayList<>())) z.stock.add(o instanceof Number num ? num.longValue() : 0L);
                while (z.stock.size() < z.produce.size()) z.stock.add(0L);
                zones.put(id, z);
            } catch (Exception ex) {
                plugin.getLogger().warning("Zona '" + id + "' dañada en zonas.yml: " + ex.getMessage());
            }
        }
    }
}
