package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Items de asedio: unidades (cañón, catapulta) y munición. */
public final class SiegeItems {
    /** Material base de TODOS los items del pack. Poco usado por otros packs para evitar choques de overrides. */
    public static final Material BASE = Material.KNOWLEDGE_BOOK;
    public static final int CMD_BLANK = 7400;
    public static final int CMD_CANNON = 7401;
    public static final int CMD_CATAPULT = 7402;
    public static final int CMD_RAM = 7403;
    public static final int CMD_TURRET = 7404;
    public static final int CMD_NODE = 7420;

    private SiegeItems() {}

    public enum UnitType {
        CANON("Cañón", CMD_CANNON, "canon"),
        CATAPULTA("Catapulta", CMD_CATAPULT, "catapulta"),
        ARIETE("Ariete", CMD_RAM, "ariete");

        public final String display;
        public final int cmd;
        public final String cfg;

        UnitType(String display, int cmd, String cfg) {
            this.display = display;
            this.cmd = cmd;
            this.cfg = cfg;
        }
    }

    public enum Ammo {
        BALA_HIERRO("Bola de Hierro", 7411, UnitType.CANON, 40, 1.8, 1.8, 6, false, 1.25),
        BALA_EXPLOSIVA("Bola Explosiva", 7412, UnitType.CANON, 110, 3.2, 3.5, 12, false, 0.8),
        BALA_INCENDIARIA("Bola Incendiaria", 7413, UnitType.CANON, 50, 2.5, 2.0, 6, true, 1.0),
        PIEDRA_CATAPULTA("Piedra de Catapulta", 7414, UnitType.CATAPULTA, 120, 3.5, 3.5, 10, false, 0.85),
        PIEDRA_FUEGO("Piedra en Llamas", 7415, UnitType.CATAPULTA, 80, 3.5, 3.0, 8, true, 1.0);

        public final String display;
        public final int cmd;
        public final UnitType unit;
        private final double node, radius, blocks, player, range;
        public final boolean fire;

        Ammo(String display, int cmd, UnitType unit, double node, double radius, double blocks, double player, boolean fire, double range) {
            this.display = display;
            this.cmd = cmd;
            this.unit = unit;
            this.node = node;
            this.radius = radius;
            this.blocks = blocks;
            this.player = player;
            this.fire = fire;
            this.range = range;
        }

        public double range(FileConfiguration c) {
            return c.getDouble("asedio.municion." + name() + ".alcance", range);
        }

        public double node(FileConfiguration c) {
            return c.getDouble("asedio.municion." + name() + ".nodo", node);
        }

        public double radius(FileConfiguration c) {
            return c.getDouble("asedio.municion." + name() + ".radio", radius);
        }

        public double blocks(FileConfiguration c) {
            return c.getDouble("asedio.municion." + name() + ".bloques", blocks);
        }

        public double player(FileConfiguration c) {
            return c.getDouble("asedio.municion." + name() + ".jugador", player);
        }
    }

    public static ItemStack unitItem(GremiosPlugin pl, UnitType t) {
        return new ItemBuilder(BASE)
                .name("§6" + t.display + " de Asedio")
                .lore("§7Clic derecho en el suelo para colocarla.",
                        "§7Clic derecho sobre ella con munición para cargarla.",
                        "§7Clic derecho sobre ella (sin munición) para apuntar.",
                        "§7Clic izquierdo para disparar (el ariete golpea puertas y no usa munición).",
                        "§7Agáchate + clic para recogerla.",
                        "",
                        "§8Solo hace daño durante un asedio.")
                .cmd(t.cmd)
                .tag(pl.key("siege_item"), t.name())
                .build();
    }

    public static ItemStack ammoItem(GremiosPlugin pl, Ammo a, int amount) {
        return new ItemBuilder(BASE, amount)
                .name("§e" + a.display)
                .lore("§7Munición para: §f" + a.unit.display,
                        "§7Daño al Nodo: §c" + (int) a.node(pl.getConfig()),
                        "§7Radio: §f" + a.radius(pl.getConfig()))
                .cmd(a.cmd)
                .tag(pl.key("siege_item"), "AMMO_" + a.name())
                .build();
    }

    /** Modelo de la torre de ballesta según su nivel (1 = base, 2 y 3 = reforzadas). */
    public static int turretCmd(int level) {
        return level >= 3 ? 7406 : level == 2 ? 7405 : CMD_TURRET;
    }

    /** Item sin nombre para las pantallas (ItemDisplay) que dibujan el modelo 3D. */
    public static ItemStack displayItem(int cmd) {
        ItemStack it = new ItemStack(BASE);
        org.bukkit.inventory.meta.ItemMeta m = it.getItemMeta();
        m.setCustomModelData(cmd);
        it.setItemMeta(m);
        return it;
    }

    public static String tag(GremiosPlugin pl, ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(pl.key("siege_item"), PersistentDataType.STRING);
    }

    public static UnitType unitTypeOf(GremiosPlugin pl, ItemStack it) {
        String t = tag(pl, it);
        if (t == null || t.startsWith("AMMO_")) return null;
        try {
            return UnitType.valueOf(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static Ammo ammoOf(GremiosPlugin pl, ItemStack it) {
        String t = tag(pl, it);
        if (t == null || !t.startsWith("AMMO_")) return null;
        try {
            return Ammo.valueOf(t.substring(5));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
