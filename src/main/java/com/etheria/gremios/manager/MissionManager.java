package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import java.time.LocalDate;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

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
 * Misiones de gremio dentro del plugin. La XP (y coronas opcionales) van al gremio del jugador.
 * Tipos: ENTREGAR (objetos), MATAR (mobs), MINAR (bloques). Repetición: UNICA, DIARIA, SEMANAL.
 */
public class MissionManager implements Listener {
    public enum Type { ENTREGAR, MATAR, MINAR }

    public static class Mission {
        public final String id;
        public String name;
        public Type type = Type.ENTREGAR;
        public String target = "COBBLESTONE";
        public int amount = 64;
        public long rewardXp = 100;
        public long rewardCrowns = 0;
        public String repeat = "DIARIA";
        public boolean enabled = true;

        public Mission(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Material material() {
            Material m = Material.matchMaterial(target);
            return m == null ? Material.PAPER : m;
        }

        public String targetDisplay() {
            return target.toLowerCase(Locale.ROOT).replace('_', ' ');
        }
    }

    public static class Prog {
        public int count;
        public long completedAt;
    }

    private final GremiosPlugin plugin;
    private final File defFile, progFile;
    private final Map<String, Mission> missions = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Prog>> progress = new HashMap<>();
    /** Completadas hoy por jugador: fecha + contador (antiexploit de entregas). */
    private final Map<UUID, String> dayOf = new HashMap<>();
    private final Map<UUID, Integer> dayCount = new HashMap<>();
    private final NamespacedKey kPlaced, kNoQuest;

    public MissionManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.defFile = new File(plugin.getDataFolder(), "misiones.yml");
        this.progFile = new File(plugin.getDataFolder(), "misiones-progreso.yml");
        this.kPlaced = plugin.key("colocados");
        this.kNoQuest = plugin.key("no_quest");
        load();
    }

    public java.util.Collection<Mission> all() {
        return missions.values();
    }

    public Mission get(String id) {
        return id == null ? null : missions.get(id.toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ admin

    public String create(String id, Type type, String target, int amount, long xp, String repeat, String name) {
        id = id.toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_\\-]{1,24}")) return "Id inválido (letras, números, _ y -).";
        if (missions.containsKey(id)) return "Ya existe una misión con ese id.";
        if (type == Type.MATAR) {
            try {
                EntityType.valueOf(target.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return "Mob desconocido: " + target + " (ej: ZOMBIE, SKELETON, CREEPER).";
            }
        } else if (Material.matchMaterial(target) == null) return "Material desconocido: " + target + " (ej: COBBLESTONE, OAK_LOG).";
        if (amount < 1) return "La cantidad debe ser al menos 1.";
        Mission m = new Mission(id, name);
        m.type = type;
        m.target = target.toUpperCase(Locale.ROOT);
        m.amount = amount;
        m.rewardXp = Math.max(0, xp);
        m.repeat = repeat.toUpperCase(Locale.ROOT);
        missions.put(id, m);
        save();
        return null;
    }

    public boolean delete(String id) {
        boolean r = missions.remove(id.toLowerCase(Locale.ROOT)) != null;
        if (r) save();
        return r;
    }

    // ------------------------------------------------------------------ estado por jugador

    private Prog prog(UUID u, Mission m) {
        return progress.computeIfAbsent(u, k -> new HashMap<>()).computeIfAbsent(m.id, k -> new Prog());
    }

    private long cooldownMs(Mission m) {
        return switch (m.repeat) {
            case "UNICA" -> Long.MAX_VALUE;
            case "SEMANAL" -> 7L * 24 * 3600 * 1000;
            default -> 24L * 3600 * 1000;
        };
    }

    /** Milisegundos que faltan para poder repetirla (0 = disponible, -1 = no se repite). */
    public long waitMs(Player p, Mission m) {
        Prog pr = prog(p.getUniqueId(), m);
        if (pr.completedAt == 0) return 0;
        long cd = cooldownMs(m);
        if (cd == Long.MAX_VALUE) return -1;
        return Math.max(0, pr.completedAt + cd - System.currentTimeMillis());
    }

    public int count(Player p, Mission m) {
        return prog(p.getUniqueId(), m).count;
    }

    private boolean active(Player p, Mission m) {
        return m.enabled && plugin.guilds.of(p) != null && waitMs(p, m) == 0 && completedToday(p) < dailyCap();
    }

    public int dailyCap() {
        return Math.max(1, plugin.getConfig().getInt("misiones.max-completadas-por-dia", 12));
    }

    public int completedToday(Player p) {
        String today = LocalDate.now().toString();
        if (!today.equals(dayOf.get(p.getUniqueId()))) return 0;
        return dayCount.getOrDefault(p.getUniqueId(), 0);
    }

    private void bumpToday(Player p) {
        String today = LocalDate.now().toString();
        if (!today.equals(dayOf.get(p.getUniqueId()))) {
            dayOf.put(p.getUniqueId(), today);
            dayCount.put(p.getUniqueId(), 0);
        }
        dayCount.merge(p.getUniqueId(), 1, Integer::sum);
    }

    private void add(Player p, Mission m, int n) {
        Prog pr = prog(p.getUniqueId(), m);
        pr.count = Math.min(m.amount, pr.count + n);
        if (pr.count >= m.amount) complete(p, m, pr);
        else if (m.type != Type.ENTREGAR && (pr.count % Math.max(1, m.amount / 5) == 0))
            Msg.actionbar(p, "§6" + m.name + "§7: §f" + pr.count + "§7/§f" + m.amount);
    }

    private void complete(Player p, Mission m, Prog pr) {
        Guild g = plugin.guilds.of(p);
        pr.count = 0;
        pr.completedAt = System.currentTimeMillis();
        bumpToday(p);
        Msg.to(p, "§a✔ Misión completada: §f" + m.name);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        if (g != null) {
            if (m.rewardXp > 0) plugin.guilds.questXp(p, m.rewardXp, !m.repeat.equals("UNICA"));
            if (m.rewardCrowns > 0) {
                g.bank += m.rewardCrowns;
                g.log("§a+" + Msg.num(m.rewardCrowns) + " §7misión " + m.name);
                plugin.guilds.save();
            }
        }
        savePlayers();
    }

    /** ENTREGAR: toma del inventario los objetos que falten. Devuelve cuántos tomó. */
    public int deliver(Player p, Mission m) {
        if (m.type != Type.ENTREGAR || !active(p, m)) return 0;
        Prog pr = prog(p.getUniqueId(), m);
        int need = m.amount - pr.count;
        Material mat = m.material();
        int taken = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it == null || it.getType() != mat || taken >= need) continue;
            // no aceptar objetos con nombre/encantamientos
            if (it.hasItemMeta() && (it.getItemMeta().hasDisplayName() || it.getItemMeta().hasEnchants())) continue;
            int take = Math.min(it.getAmount(), need - taken);
            it.setAmount(it.getAmount() - take);
            taken += take;
        }
        if (taken > 0) add(p, m, taken);
        return taken;
    }

    // ------------------------------------------------------------------ listeners

    // --- antiexploit: bloques colocados por jugadores (se guardan en el PDC del chunk)

    private static long pack(org.bukkit.block.Block b) {
        return ((long) (b.getY() + 64) << 8) | ((long) (b.getX() & 15) << 4) | (b.getZ() & 15);
    }

    private boolean isMinarTarget(Material t) {
        for (Mission m : missions.values()) if (m.type == Type.MINAR && m.material() == t) return true;
        return false;
    }

    private boolean wasPlaced(org.bukkit.block.Block b, boolean remove) {
        PersistentDataContainer pdc = b.getChunk().getPersistentDataContainer();
        long[] arr = pdc.get(kPlaced, PersistentDataType.LONG_ARRAY);
        if (arr == null) return false;
        long k = pack(b);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] != k) continue;
            if (remove) {
                long[] n = new long[arr.length - 1];
                System.arraycopy(arr, 0, n, 0, i);
                System.arraycopy(arr, i + 1, n, i, arr.length - i - 1);
                if (n.length == 0) pdc.remove(kPlaced);
                else pdc.set(kPlaced, PersistentDataType.LONG_ARRAY, n);
            }
            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!isMinarTarget(e.getBlock().getType())) return;
        PersistentDataContainer pdc = e.getBlock().getChunk().getPersistentDataContainer();
        long[] arr = pdc.get(kPlaced, PersistentDataType.LONG_ARRAY);
        long k = pack(e.getBlock());
        int max = plugin.getConfig().getInt("misiones.max-bloques-colocados-por-chunk", 2000);
        if (arr == null) arr = new long[0];
        for (long v : arr) if (v == k) return;
        if (arr.length >= max) { // se descarta el más antiguo
            long[] n = new long[arr.length];
            System.arraycopy(arr, 1, n, 0, arr.length - 1);
            n[n.length - 1] = k;
            pdc.set(kPlaced, PersistentDataType.LONG_ARRAY, n);
            return;
        }
        long[] n = java.util.Arrays.copyOf(arr, arr.length + 1);
        n[arr.length] = k;
        pdc.set(kPlaced, PersistentDataType.LONG_ARRAY, n);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Player p = e.getPlayer();
        Material t = e.getBlock().getType();
        // siempre se olvida el bloque colocado, aunque la misión no cuente
        boolean placed = wasPlaced(e.getBlock(), true);
        if (p.getGameMode() == GameMode.CREATIVE || placed) return;
        for (Mission m : missions.values()) {
            if (m.type == Type.MINAR && m.material() == t && active(p, m)) add(p, m, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        switch (e.getSpawnReason()) {
            case SPAWNER, SPAWNER_EGG, DISPENSE_EGG -> { }
            default -> { return; }
        }
        e.getEntity().getPersistentDataContainer().set(kNoQuest, PersistentDataType.BYTE, (byte) 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKill(EntityDeathEvent e) {
        Player p = e.getEntity().getKiller();
        if (p == null) return;
        if (e.getEntity().getPersistentDataContainer().has(kNoQuest, PersistentDataType.BYTE)) return;
        String t = e.getEntityType().name();
        for (Mission m : missions.values()) {
            if (m.type == Type.MATAR && m.target.equals(t) && active(p, m)) add(p, m, 1);
        }
    }

    // ------------------------------------------------------------------ persistencia

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("misiones");
        for (Mission m : missions.values()) {
            ConfigurationSection s = root.createSection(m.id);
            s.set("nombre", m.name);
            s.set("tipo", m.type.name());
            s.set("objetivo", m.target);
            s.set("cantidad", m.amount);
            s.set("xp", m.rewardXp);
            s.set("coronas", m.rewardCrowns);
            s.set("repeticion", m.repeat);
            s.set("activa", m.enabled);
        }
        try {
            y.save(defFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar misiones.yml: " + e.getMessage());
        }
    }

    public void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("jugadores");
        for (Map.Entry<UUID, Map<String, Prog>> en : progress.entrySet()) {
            ConfigurationSection s = root.createSection(en.getKey().toString());
            if (dayOf.containsKey(en.getKey())) {
                s.set("_dia", dayOf.get(en.getKey()));
                s.set("_n", dayCount.getOrDefault(en.getKey(), 0));
            }
            for (Map.Entry<String, Prog> p : en.getValue().entrySet()) {
                s.set(p.getKey() + ".n", p.getValue().count);
                s.set(p.getKey() + ".t", p.getValue().completedAt);
            }
        }
        try {
            y.save(progFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar misiones-progreso.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (defFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(defFile);
            ConfigurationSection root = y.getConfigurationSection("misiones");
            if (root != null) for (String id : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(id);
                if (s == null) continue;
                try {
                    Mission m = new Mission(id, s.getString("nombre", id));
                    m.type = Type.valueOf(s.getString("tipo", "ENTREGAR"));
                    m.target = s.getString("objetivo", "COBBLESTONE");
                    m.amount = s.getInt("cantidad", 1);
                    m.rewardXp = s.getLong("xp", 0);
                    m.rewardCrowns = s.getLong("coronas", 0);
                    m.repeat = s.getString("repeticion", "DIARIA");
                    m.enabled = s.getBoolean("activa", true);
                    missions.put(id, m);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Misión '" + id + "' dañada: " + ex.getMessage());
                }
            }
        }
        if (progFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(progFile);
            ConfigurationSection root = y.getConfigurationSection("jugadores");
            if (root != null) for (String u : root.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(u);
                    ConfigurationSection s = root.getConfigurationSection(u);
                    Map<String, Prog> map = new HashMap<>();
                    if (s.contains("_dia")) {
                        dayOf.put(id, s.getString("_dia"));
                        dayCount.put(id, s.getInt("_n"));
                    }
                    for (String mid : s.getKeys(false)) {
                        if (mid.startsWith("_")) continue;
                        Prog p = new Prog();
                        p.count = s.getInt(mid + ".n");
                        p.completedAt = s.getLong(mid + ".t");
                        map.put(mid, p);
                    }
                    progress.put(id, map);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
