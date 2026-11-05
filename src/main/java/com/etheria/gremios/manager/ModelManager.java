package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.NpcRole;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Modelos 3D propios (del resourcepack) para los NPC: el aldeano queda invisible (es la "hitbox" del clic)
 * y un ItemDisplay con el modelo se coloca encima. No hace falta ModelEngine ni Blockbench.
 */
public class ModelManager {
    public static final int BOARD = 7446;
    public static final int GENERIC_WORKER = 7445;

    private final GremiosPlugin plugin;
    private final NamespacedKey kOwner, kZone, kBoard, kRole;

    public ModelManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.kOwner = plugin.key("modelo_de");
        this.kZone = plugin.key("zone_npc");
        this.kBoard = plugin.key("tablon");
        this.kRole = plugin.key("npc_role");
    }

    public boolean enabled() {
        return plugin.usePack() && plugin.getConfig().getBoolean("modelos.npc", true);
    }

    /** Modelo (custom model data) que le toca a este aldeano, o -1 si no es un NPC del plugin. */
    private int cmdOf(Villager v) {
        var pdc = v.getPersistentDataContainer();
        String role = pdc.get(kRole, PersistentDataType.STRING);
        if (role != null) {
            NpcRole r = NpcRole.parse(role);
            if (r != null) return 7430 + r.ordinal();
        }
        String zone = pdc.get(kZone, PersistentDataType.STRING);
        if (zone != null) {
            ZoneManager.Zone z = plugin.zones.get(zone);
            String kind = z == null ? "" : z.kind.toUpperCase();
            return switch (kind) {
                case "MINA" -> 7440;
                case "TALA" -> 7441;
                case "CANTERA" -> 7442;
                case "GRANJA" -> 7443;
                case "PESCA" -> 7444;
                default -> GENERIC_WORKER;
            };
        }
        if (pdc.has(kBoard, PersistentDataType.STRING)) return BOARD;
        return -1;
    }

    private float scaleOf(int cmd) {
        return cmd == BOARD ? 1.6f : 1.9f;
    }

    private ItemDisplay spawn(Villager v, int cmd) {
        Location l = v.getLocation();
        final float sc = scaleOf(cmd);
        return v.getWorld().spawn(l, ItemDisplay.class, d -> {
            d.setItemStack(SiegeItems.displayItem(cmd));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(0f, 0.5f * sc, 0f), new AxisAngle4f(0f, 0f, 0f, 1f),
                    new Vector3f(sc, sc, sc), new AxisAngle4f(0f, 0f, 0f, 1f)));
            d.setViewRange(1.5f);
            d.setBillboard(Display.Billboard.FIXED);
            d.setPersistent(false);
            d.setRotation(l.getYaw(), 0f);
            d.getPersistentDataContainer().set(kOwner, PersistentDataType.STRING, v.getUniqueId().toString());
        });
    }

    private boolean neighborsLoaded(Entity e) {
        World w = e.getWorld();
        int cx = e.getLocation().getBlockX() >> 4, cz = e.getLocation().getBlockZ() >> 4;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!w.isChunkLoaded(cx + dx, cz + dz)) return false;
        return true;
    }

    /** Mantiene un modelo por NPC, quita duplicados y huérfanos. */
    public void tick() {
        boolean on = enabled();
        for (World w : Bukkit.getWorlds()) {
            Map<UUID, ItemDisplay> byOwner = new HashMap<>();
            for (ItemDisplay d : w.getEntitiesByClass(ItemDisplay.class)) {
                String o = d.getPersistentDataContainer().get(kOwner, PersistentDataType.STRING);
                if (o == null) continue;
                try {
                    if (byOwner.putIfAbsent(UUID.fromString(o), d) != null) d.remove();
                } catch (IllegalArgumentException ex) {
                    d.remove();
                }
            }
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                int cmd = cmdOf(v);
                if (cmd < 0) continue;
                ItemDisplay d = byOwner.remove(v.getUniqueId());
                if (!on) {
                    v.setInvisible(false);
                    if (d != null) d.remove();
                    continue;
                }
                v.setInvisible(true);
                if (d == null || d.isDead()) {
                    spawn(v, cmd);
                    continue;
                }
                Location l = v.getLocation();
                if (d.getLocation().distanceSquared(l) > 0.0025 || Math.abs(d.getLocation().getYaw() - l.getYaw()) > 1f) {
                    d.teleport(l);
                    d.setRotation(l.getYaw(), 0f);
                }
                ItemStack cur = d.getItemStack();
                if (cur == null || !cur.hasItemMeta() || !cur.getItemMeta().hasCustomModelData() || cur.getItemMeta().getCustomModelData() != cmd)
                    d.setItemStack(SiegeItems.displayItem(cmd));
            }
            for (ItemDisplay d : byOwner.values()) if (neighborsLoaded(d)) d.remove();
        }
    }
}
