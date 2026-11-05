package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puertas y puentes levadizos de fortaleza. Una puerta es una región de bloques ("cerrada" = bloques puestos).
 * Abrir = quitar los bloques; cerrar = volver a ponerlos. En un asedio tienen vida y se rompen con arietes y cañones.
 */
public class GateManager {
    public static class Gate {
        public String id, territory, world, type = "PUERTA";
        public int minX, minY, minZ, maxX, maxY, maxZ;
        public int[] control; // x,y,z del bloque de control (opcional)
        public double maxHp, hp;
        public boolean open, broken;
        public final List<String> palette = new ArrayList<>();
        public int[] idx;

        public String key() {
            return territory + ":" + id;
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    private final GremiosPlugin plugin;
    private final File file;
    private final Map<String, Gate> gates = new LinkedHashMap<>();

    public GateManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "puertas.yml");
        load();
    }

    public List<Gate> all() {
        return new ArrayList<>(gates.values());
    }

    public List<Gate> of(Territory t) {
        List<Gate> l = new ArrayList<>();
        for (Gate g : gates.values()) if (g.territory.equals(t.id)) l.add(g);
        return l;
    }

    public Gate get(String territory, String id) {
        return gates.get(territory + ":" + id.toLowerCase());
    }

    public Gate at(Block b) {
        for (Gate g : gates.values()) {
            if (g.world.equals(b.getWorld().getName()) && g.contains(b.getX(), b.getY(), b.getZ())) return g;
        }
        return null;
    }

    public Gate controlAt(Block b) {
        for (Gate g : gates.values()) {
            if (g.control != null && g.world.equals(b.getWorld().getName())
                    && g.control[0] == b.getX() && g.control[1] == b.getY() && g.control[2] == b.getZ()) return g;
        }
        return null;
    }

    // ------------------------------------------------------------------ crear / borrar

    public String create(Territory t, String id, String type, Location a, Location b) {
        if (a == null || b == null) return "Selecciona la puerta con la varita (pos 1 y pos 2).";
        if (a.getWorld() != b.getWorld() || !a.getWorld().getName().equals(t.world)) return "La selección debe estar en el mundo del territorio.";
        id = id.toLowerCase();
        if (gates.containsKey(t.id + ":" + id)) return "Ya existe una puerta con ese id en el territorio.";
        Gate g = new Gate();
        g.id = id;
        g.territory = t.id;
        g.world = t.world;
        g.type = type.equalsIgnoreCase("PUENTE") ? "PUENTE" : "PUERTA";
        g.minX = Math.min(a.getBlockX(), b.getBlockX());
        g.maxX = Math.max(a.getBlockX(), b.getBlockX());
        g.minY = Math.min(a.getBlockY(), b.getBlockY());
        g.maxY = Math.max(a.getBlockY(), b.getBlockY());
        g.minZ = Math.min(a.getBlockZ(), b.getBlockZ());
        g.maxZ = Math.max(a.getBlockZ(), b.getBlockZ());
        if (!t.contains(g.minX, g.minY, g.minZ) || !t.contains(g.maxX, g.maxY, g.maxZ)) return "La puerta debe estar dentro del territorio.";
        long vol = (long) (g.maxX - g.minX + 1) * (g.maxY - g.minY + 1) * (g.maxZ - g.minZ + 1);
        if (vol > 6000) return "La puerta es demasiado grande (máx. 6000 bloques).";
        World w = a.getWorld();
        g.idx = new int[(int) vol];
        Map<String, Integer> pal = new LinkedHashMap<>();
        int i = 0;
        for (int y = g.minY; y <= g.maxY; y++)
            for (int z = g.minZ; z <= g.maxZ; z++)
                for (int x = g.minX; x <= g.maxX; x++) {
                    String d = w.getBlockAt(x, y, z).getBlockData().getAsString();
                    Integer p = pal.get(d);
                    if (p == null) {
                        p = pal.size();
                        pal.put(d, p);
                        g.palette.add(d);
                    }
                    g.idx[i++] = p;
                }
        g.maxHp = plugin.getConfig().getDouble("puertas.vida-por-defecto", 600);
        g.hp = g.maxHp;
        gates.put(g.key(), g);
        save();
        return null;
    }

    public boolean delete(Territory t, String id) {
        Gate g = gates.remove(t.id + ":" + id.toLowerCase());
        if (g != null) save();
        return g != null;
    }

    public void deleteAll(Territory t) {
        gates.values().removeIf(g -> g.territory.equals(t.id));
        save();
    }

    // ------------------------------------------------------------------ abrir / cerrar

    private void setBlocks(Gate g, boolean present) {
        World w = Bukkit.getWorld(g.world);
        if (w == null) return;
        BlockData air = Material.AIR.createBlockData();
        BlockData[] pal = new BlockData[g.palette.size()];
        int i = 0;
        for (int y = g.minY; y <= g.maxY; y++)
            for (int z = g.minZ; z <= g.maxZ; z++)
                for (int x = g.minX; x <= g.maxX; x++) {
                    int p = g.idx[i++];
                    BlockData bd = air;
                    if (present) {
                        if (pal[p] == null) {
                            try {
                                pal[p] = Bukkit.createBlockData(g.palette.get(p));
                            } catch (IllegalArgumentException ex) {
                                pal[p] = air;
                            }
                        }
                        bd = pal[p];
                    }
                    Block b = w.getBlockAt(x, y, z);
                    if (!b.getBlockData().equals(bd)) b.setBlockData(bd, false);
                }
    }

    public String setOpen(Gate g, boolean open) {
        if (g.broken) return "La puerta está destrozada. Se reconstruye al terminar el asedio.";
        if (g.open == open) return null;
        g.open = open;
        setBlocks(g, !open);
        World w = Bukkit.getWorld(g.world);
        if (w != null) {
            Location c = new Location(w, (g.minX + g.maxX + 1) / 2.0, (g.minY + g.maxY + 1) / 2.0, (g.minZ + g.maxZ + 1) / 2.0);
            w.playSound(c, open ? Sound.BLOCK_IRON_DOOR_OPEN : Sound.BLOCK_IRON_DOOR_CLOSE, 1.4f, 0.7f);
            w.playSound(c, Sound.BLOCK_CHAIN_PLACE, 1.2f, 0.6f);
        }
        save();
        return null;
    }

    public String toggle(Gate g) {
        return setOpen(g, !g.open);
    }

    // ------------------------------------------------------------------ daño

    public void damage(Gate g, double dmg, Player src) {
        if (g.open || g.broken || dmg <= 0) return;
        g.hp -= dmg;
        World w = Bukkit.getWorld(g.world);
        Location c = center(g);
        if (w != null && c != null) {
            w.spawnParticle(Particle.BLOCK, c, 30, (g.maxX - g.minX + 1) / 2.0, (g.maxY - g.minY + 1) / 2.0, (g.maxZ - g.minZ + 1) / 2.0, 0.1, Material.OAK_PLANKS.createBlockData());
            w.playSound(c, Sound.ENTITY_ZOMBIE_ATTACK_WOODEN_DOOR, 1.5f, 0.7f);
        }
        if (src != null) Msg.actionbar(src, "§6" + (g.type.equals("PUENTE") ? "Puente" : "Puerta") + ": §c" + (int) Math.max(0, g.hp) + "§7/§f" + (int) g.maxHp);
        if (g.hp <= 0) {
            g.broken = true;
            setBlocks(g, false);
            if (w != null && c != null) {
                w.spawnParticle(Particle.EXPLOSION, c, 6, 1, 1, 1, 0);
                w.playSound(c, Sound.ENTITY_GENERIC_EXPLODE, 2f, 0.7f);
            }
            Territory t = plugin.territories.get(g.territory);
            Guild o = t == null ? null : plugin.guilds.byId(t.ownerGuildId);
            if (o != null) plugin.guilds.broadcast(o, "§c¡Han destrozado " + (g.type.equals("PUENTE") ? "el puente" : "la puerta") + " §f" + g.id + "§c!");
        }
        save();
    }

    /** Daño de explosión/impacto a las puertas cercanas del territorio de un asedio. */
    public void damageNear(Raid raid, Location loc, double dmg, double radius, Player src) {
        for (Gate g : gates.values()) {
            if (!g.territory.equals(raid.territoryId) || !g.world.equals(loc.getWorld().getName())) continue;
            double dx = Math.max(Math.max(g.minX - loc.getX(), 0), loc.getX() - (g.maxX + 1));
            double dy = Math.max(Math.max(g.minY - loc.getY(), 0), loc.getY() - (g.maxY + 1));
            double dz = Math.max(Math.max(g.minZ - loc.getZ(), 0), loc.getZ() - (g.maxZ + 1));
            if (Math.sqrt(dx * dx + dy * dy + dz * dz) <= radius) damage(g, dmg, src);
        }
    }

    public Location center(Gate g) {
        World w = Bukkit.getWorld(g.world);
        if (w == null) return null;
        return new Location(w, (g.minX + g.maxX + 1) / 2.0, (g.minY + g.maxY + 1) / 2.0, (g.minZ + g.maxZ + 1) / 2.0);
    }

    /** Reconstruye y cierra todas las puertas del territorio (fin de asedio / tras restaurar). */
    public void restoreAll(Territory t) {
        if (t == null) return;
        for (Gate g : of(t)) {
            g.broken = false;
            g.open = false;
            g.hp = g.maxHp;
            setBlocks(g, true);
        }
        save();
    }

    // ------------------------------------------------------------------ persistencia

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("puertas");
        int n = 0;
        for (Gate g : gates.values()) {
            ConfigurationSection s = root.createSection("p" + (n++));
            s.set("id", g.id);
            s.set("territorio", g.territory);
            s.set("mundo", g.world);
            s.set("tipo", g.type);
            s.set("min", List.of(g.minX, g.minY, g.minZ));
            s.set("max", List.of(g.maxX, g.maxY, g.maxZ));
            if (g.control != null) s.set("control", List.of(g.control[0], g.control[1], g.control[2]));
            s.set("vidaMax", g.maxHp);
            s.set("vida", g.hp);
            s.set("abierta", g.open);
            s.set("rota", g.broken);
            s.set("paleta", g.palette);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < g.idx.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(g.idx[i]);
            }
            s.set("indices", sb.toString());
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar puertas.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("puertas");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            try {
                Gate g = new Gate();
                g.id = s.getString("id");
                g.territory = s.getString("territorio");
                g.world = s.getString("mundo");
                g.type = s.getString("tipo", "PUERTA");
                List<Integer> mn = s.getIntegerList("min"), mx = s.getIntegerList("max");
                g.minX = mn.get(0);
                g.minY = mn.get(1);
                g.minZ = mn.get(2);
                g.maxX = mx.get(0);
                g.maxY = mx.get(1);
                g.maxZ = mx.get(2);
                List<Integer> c = s.getIntegerList("control");
                if (c.size() == 3) g.control = new int[]{c.get(0), c.get(1), c.get(2)};
                g.maxHp = s.getDouble("vidaMax", 600);
                g.hp = s.getDouble("vida", g.maxHp);
                g.open = s.getBoolean("abierta");
                g.broken = s.getBoolean("rota");
                g.palette.addAll(s.getStringList("paleta"));
                String[] parts = s.getString("indices", "").split(",");
                g.idx = new int[parts.length];
                for (int i = 0; i < parts.length; i++) g.idx[i] = Integer.parseInt(parts[i].trim());
                gates.put(g.key(), g);
            } catch (Exception ex) {
                plugin.getLogger().warning("Puerta '" + k + "' dañada en puertas.yml: " + ex.getMessage());
            }
        }
    }
}
