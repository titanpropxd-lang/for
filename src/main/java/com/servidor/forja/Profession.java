package com.servidor.forja;

import org.bukkit.Material;

public enum Profession {
    HERRERIA("herreria", "Herrería", Material.ANVIL, "<gold>"),
    ARMERIA("armeria", "Armería ligera", Material.LEATHER_CHESTPLATE, "<yellow>"),
    CARPINTERIA("carpinteria", "Carpintería", Material.CROSSBOW, "<green>"),
    JOYERIA("joyeria", "Joyería", Material.AMETHYST_SHARD, "<light_purple>"),
    ALQUIMIA("alquimia", "Alquimia", Material.BREWING_STAND, "<aqua>"),
    COCINA("cocina", "Cocina", Material.COOKED_BEEF, "<red>"),
    CURTIDURIA("curtiduria", "Curtiduría", Material.LEATHER, "<gold>");

    public final String id;
    public final String display;
    public final Material icon;
    public final String tag;

    Profession(String id, String display, Material icon, String tag) {
        this.id = id;
        this.display = display;
        this.icon = icon;
        this.tag = tag;
    }

    public String permission() {
        return "forja.oficio." + id;
    }

    public static Profession fromId(String s) {
        if (s == null) return null;
        for (Profession p : values()) if (p.id.equalsIgnoreCase(s)) return p;
        return null;
    }

    /** Estación (yunque, forja, armeria...) -> oficio que usa. */
    public static Profession fromStation(String st) {
        if (st == null) return null;
        st = st.trim().toLowerCase(java.util.Locale.ROOT);
        if (st.startsWith("estacion_")) st = st.substring("estacion_".length());
        switch (st) {
            case "herreria":
                return HERRERIA;
            case "yunque":
            case "forja":
            case "fundicion_t1":
            case "fundicion_t2":
            case "canteria":
            case "afilado":
                return HERRERIA;
            case "cartografia":
                return JOYERIA;
            case "curtideria":
                return CURTIDURIA;
            case "compostera":
                return ALQUIMIA;
            default:
                return fromId(st);
        }
    }
}
