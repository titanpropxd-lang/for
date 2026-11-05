package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Tablones de misiones físicos: un aldeano (sin IA) + un texto flotante con las misiones activas. */
public class BoardManager {
    public static class Board {
        public String id, world;
        public double x, y, z;
        public float yaw;
        public UUID npc, text;
    }

    private final GremiosPlugin plugin;
    private final File file;
    private final NamespacedKey kBoard, kBoardText;
    private final Map<String, Board> boards = new LinkedHashMap<>();

    public BoardManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tablon.yml");
        this.kBoard = plugin.key("tablon");
        this.kBoardText = plugin.key("tablon_texto");
        load();
    }

    public java.util.Collection<Board> all() {
        return boards.values();
    }

    public Board boardOf(Entity e) {
        String id = e.getPersistentDataContainer().get(kBoard, PersistentDataType.STRING);
        return id == null ? null : boards.get(id);
    }

    public String create(String id, Location l) {
        id = id.toLowerCase();
        if (!id.matches("[a-z0-9_\\-]{1,24}")) return "Id inválido.";
        if (boards.containsKey(id)) return "Ya existe un tablón con ese id.";
        Board b = new Board();
        b.id = id;
        b.world = l.getWorld().getName();
        b.x = l.getX();
        b.y = l.getY();
        b.z = l.getZ();
        b.yaw = l.getYaw();
        boards.put(id, b);
        sync(b);
        save();
        return null;
    }

    public boolean delete(String id) {
        Board b = boards.remove(id.toLowerCase());
        if (b == null) return false;
        remove(b);
        save();
        return true;
    }

    private void remove(Board b) {
        World w = Bukkit.getWorld(b.world);
        if (w == null) return;
        Location l = new Location(w, b.x, b.y, b.z);
        l.getChunk().load();
        for (Entity e : w.getNearbyEntities(l, 3, 5, 3)) {
            String v = e.getPersistentDataContainer().get(kBoard, PersistentDataType.STRING);
            String t = e.getPersistentDataContainer().get(kBoardText, PersistentDataType.STRING);
            if (b.id.equals(v) || b.id.equals(t)) e.remove();
        }
    }

    /** Asegura exactamente un aldeano y un texto por tablón (elimina duplicados). */
    public void sync(Board b) {
        World w = Bukkit.getWorld(b.world);
        if (w == null) return;
        Location l = new Location(w, b.x, b.y, b.z, b.yaw, 0f);
        l.getChunk().load();
        l.getChunk().addPluginChunkTicket(plugin);
        Entity npc = null;
        TextDisplay txt = null;
        for (Entity e : w.getNearbyEntities(l, 3, 5, 3)) {
            if (e.isDead()) continue;
            if (b.id.equals(e.getPersistentDataContainer().get(kBoard, PersistentDataType.STRING))) {
                if (npc == null) npc = e;
                else e.remove();
            } else if (b.id.equals(e.getPersistentDataContainer().get(kBoardText, PersistentDataType.STRING)) && e instanceof TextDisplay td) {
                if (txt == null) txt = td;
                else e.remove();
            }
        }
        final String id = b.id;
        if (npc == null) {
            npc = w.spawn(l, Villager.class, v -> {
                v.setAI(false);
                v.setInvulnerable(true);
                v.setSilent(true);
                v.setPersistent(true);
                v.setRemoveWhenFarAway(false);
                v.setInvisible(plugin.models != null && plugin.models.enabled());
                v.setProfession(Villager.Profession.CARTOGRAPHER);
                v.setCustomName("§6§lTablón de misiones");
                v.setCustomNameVisible(true);
                v.getPersistentDataContainer().set(kBoard, PersistentDataType.STRING, id);
            });
        }
        b.npc = npc.getUniqueId();
        if (txt == null) {
            txt = w.spawn(l.clone().add(0, 2.55, 0), TextDisplay.class, t -> {
                t.setBillboard(Display.Billboard.CENTER);
                t.setPersistent(true);
                t.setViewRange(0.6f);
                t.getPersistentDataContainer().set(kBoardText, PersistentDataType.STRING, id);
            });
        }
        b.text = txt.getUniqueId();
        txt.setText(render());
    }

    private String render() {
        StringBuilder sb = new StringBuilder("§6§l⚑ Misiones de hoy\n");
        int n = 0;
        for (MissionManager.Mission m : plugin.missions.all()) {
            if (!m.enabled) continue;
            if (n++ >= 8) {
                sb.append("§7...");
                break;
            }
            String what = switch (m.type) {
                case ENTREGAR -> "Entrega ";
                case MATAR -> "Mata ";
                case MINAR -> "Mina ";
            };
            sb.append("§e").append(m.name).append(" §8» §f").append(what).append(m.amount).append("× ").append(m.targetDisplay())
                    .append(" §6+").append(m.rewardXp).append("xp\n");
        }
        if (n == 0) sb.append("§7No hay misiones activas.");
        else sb.append("§7Clic derecho al aldeano para ver las tuyas.");
        return sb.toString();
    }

    public void refresh() {
        for (Board b : boards.values()) {
            if (b.text == null) continue;
            Entity e = Bukkit.getEntity(b.text);
            if (e instanceof TextDisplay td) td.setText(render());
        }
    }

    public void syncAll() {
        for (Board b : boards.values()) sync(b);
    }

    /** Un aldeano de tablón cargado de más (chunk descargado/duplicado). */
    public void onLoaded(Villager v) {
        Board b = boardOf(v);
        if (b == null) return;
        if (b.npc != null && !b.npc.equals(v.getUniqueId())) {
            Entity o = Bukkit.getEntity(b.npc);
            if (o != null && !o.isDead()) {
                v.remove();
                return;
            }
        }
        b.npc = v.getUniqueId();
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("tablones");
        for (Board b : boards.values()) {
            ConfigurationSection s = root.createSection(b.id);
            s.set("mundo", b.world);
            s.set("x", b.x);
            s.set("y", b.y);
            s.set("z", b.z);
            s.set("yaw", b.yaw);
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar tablon.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("tablones");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Board b = new Board();
            b.id = id;
            b.world = s.getString("mundo");
            b.x = s.getDouble("x");
            b.y = s.getDouble("y");
            b.z = s.getDouble("z");
            b.yaw = (float) s.getDouble("yaw");
            boards.put(id, b);
        }
    }
}
