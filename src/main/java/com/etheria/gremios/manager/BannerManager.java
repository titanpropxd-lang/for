package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Territory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Rotatable;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.NamespacedKey;
import org.bukkit.DyeColor;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Bandera de gremio (la elige el líder) y su lugar visible en el territorio. */
public class BannerManager {
    private final GremiosPlugin plugin;
    private final File file;
    private final Map<String, ItemStack> banners = new HashMap<>();
    private final NamespacedKey kFlag;
    private final Map<String, double[]> positions = new HashMap<>(); // territorio -> x,y,z,yaw

    public BannerManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "banderas.yml");
        this.kFlag = plugin.key("bandera_modelo");
        load();
    }

    public ItemStack of(Guild g) {
        ItemStack it = g == null ? null : banners.get(g.id);
        return it == null ? null : it.clone();
    }

    public boolean isBanner(ItemStack it) {
        return it != null && Tag.ITEMS_BANNERS.isTagged(it.getType());
    }

    public void set(Guild g, ItemStack banner) {
        ItemStack one = banner.clone();
        one.setAmount(1);
        banners.put(g.id, one);
        save();
        refreshGuild(g);
    }

    public void remove(Guild g) {
        banners.remove(g.id);
        save();
        refreshGuild(g);
    }

    public void setPos(Territory t, Location l) {
        positions.put(t.id, new double[]{l.getBlockX(), l.getBlockY(), l.getBlockZ(), l.getYaw()});
        save();
        refresh(t);
    }

    public boolean hasPos(Territory t) {
        return positions.containsKey(t.id);
    }

    public void refreshGuild(Guild g) {
        if (g == null || g.territoryId == null) return;
        Territory t = plugin.territories.get(g.territoryId);
        if (t != null) refresh(t);
    }

    /** Coloca la bandera del dueño (o quita la que hubiera si no hay dueño). */
    public void refresh(Territory t) {
        double[] p = positions.get(t.id);
        if (p == null) return;
        World w = t.bukkitWorld();
        if (w == null) return;
        Block b = w.getBlockAt((int) p[0], (int) p[1], (int) p[2]);
        Guild o = plugin.guilds.byId(t.ownerGuildId);
        boolean model = plugin.usePack() && plugin.getConfig().getBoolean("modelos.bandera", true);
        if (model) {
            if (Tag.BANNERS.isTagged(b.getType())) b.setType(Material.AIR, false);
            flagModel(t, w, p, o);
            return;
        }
        flagModel(t, w, p, null); // quita el modelo si estaba
        if (o == null) {
            if (Tag.BANNERS.isTagged(b.getType())) b.setType(Material.AIR, false);
            return;
        }
        ItemStack it = banners.get(o.id);
        Material mat = it == null ? Material.WHITE_BANNER : standing(it.getType());
        BlockData bd = mat.createBlockData();
        if (bd instanceof Rotatable rot) rot.setRotation(face(p[3]));
        b.setBlockData(bd, false);
        if (it != null && it.getItemMeta() instanceof BannerMeta bm && b.getState() instanceof Banner st) {
            st.setPatterns(bm.getPatterns());
            st.update(true, false);
        }
    }

    /** Mástil 3D del resourcepack: color de tela = color base de la bandera; emblema = color de su primera capa. */
    private void flagModel(Territory t, World w, double[] p, Guild owner) {
        Location l = new Location(w, p[0] + 0.5, p[1], p[2] + 0.5, (float) p[3] + 180f, 0f);
        l.getChunk().load();
        ItemDisplay cur = null;
        for (Entity e : w.getNearbyEntities(l, 2, 4, 2)) {
            if (!t.id.equals(e.getPersistentDataContainer().get(kFlag, PersistentDataType.STRING)) || !(e instanceof ItemDisplay d)) continue;
            if (cur == null) cur = d;
            else d.remove();
        }
        if (owner == null) {
            if (cur != null) cur.remove();
            return;
        }
        ItemStack it = banners.get(owner.id);
        int base = DyeColor.WHITE.ordinal(), emblem = DyeColor.GRAY.ordinal();
        if (it != null) {
            String n = it.getType().name();
            try {
                base = DyeColor.valueOf(n.replace("_BANNER", "")).ordinal();
            } catch (IllegalArgumentException ignored) {
            }
            if (it.getItemMeta() instanceof BannerMeta bm && !bm.getPatterns().isEmpty())
                emblem = bm.getPatterns().get(0).getColor().ordinal();
            else emblem = base == DyeColor.BLACK.ordinal() ? DyeColor.WHITE.ordinal() : DyeColor.BLACK.ordinal();
        }
        final int cmd = 7500 + base * 16 + emblem;
        final float sc = 1.5f;
        if (cur == null) {
            cur = w.spawn(l, ItemDisplay.class, d -> {
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(new Transformation(new Vector3f(0f, 0.5f * sc, 0f), new AxisAngle4f(0f, 0f, 0f, 1f),
                        new Vector3f(sc, sc, sc), new AxisAngle4f(0f, 0f, 0f, 1f)));
                d.setViewRange(2.5f);
                d.setBillboard(Display.Billboard.FIXED);
                d.setPersistent(true);
                d.getPersistentDataContainer().set(kFlag, PersistentDataType.STRING, t.id);
            });
        }
        cur.setItemStack(SiegeItems.displayItem(cmd));
        cur.setRotation(l.getYaw(), 0f);
    }

    public void refreshLater(Territory t) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> refresh(t), 5L);
    }

    public void refreshAll() {
        for (Territory t : plugin.territories.all()) refresh(t);
    }

    private static Material standing(Material m) {
        // los items de banner (X_BANNER) coinciden con el bloque de pie
        return m;
    }

    private static BlockFace face(double yaw) {
        BlockFace[] f = {BlockFace.NORTH, BlockFace.NORTH_NORTH_EAST, BlockFace.NORTH_EAST, BlockFace.EAST_NORTH_EAST, BlockFace.EAST,
                BlockFace.EAST_SOUTH_EAST, BlockFace.SOUTH_EAST, BlockFace.SOUTH_SOUTH_EAST, BlockFace.SOUTH, BlockFace.SOUTH_SOUTH_WEST,
                BlockFace.SOUTH_WEST, BlockFace.WEST_SOUTH_WEST, BlockFace.WEST, BlockFace.WEST_NORTH_WEST, BlockFace.NORTH_WEST, BlockFace.NORTH_NORTH_WEST};
        double a = ((yaw % 360) + 360 + 180) % 360; // yaw 180 = norte
        return f[(int) Math.round(a / 22.5) % 16];
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection g = y.createSection("gremios");
        for (Map.Entry<String, ItemStack> e : banners.entrySet()) g.set(e.getKey(), e.getValue());
        ConfigurationSection pos = y.createSection("posiciones");
        for (Map.Entry<String, double[]> e : positions.entrySet()) pos.set(e.getKey(), List.of(e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]));
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar banderas.yml: " + ex.getMessage());
        }
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection g = y.getConfigurationSection("gremios");
        if (g != null) for (String k : g.getKeys(false)) {
            ItemStack it = g.getItemStack(k);
            if (it != null) banners.put(k, it);
        }
        ConfigurationSection pos = y.getConfigurationSection("posiciones");
        if (pos != null) for (String k : pos.getKeys(false)) {
            List<Double> l = pos.getDoubleList(k);
            if (l.size() == 4) positions.put(k, new double[]{l.get(0), l.get(1), l.get(2), l.get(3)});
        }
    }
}
