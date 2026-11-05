package com.etheria.gremios.model;

/** Rangos internos del gremio. */
public enum Rank {
    RECLUTA(1, "Recluta", "§7"),
    MIEMBRO(2, "Miembro", "§f"),
    OFICIAL(3, "Oficial", "§a"),
    MARISCAL(4, "Mariscal", "§6"),
    LIDER(5, "Líder", "§c");

    public final int id;
    public final String display;
    public final String color;

    Rank(int id, String display, String color) {
        this.id = id;
        this.display = display;
        this.color = color;
    }

    public boolean atLeast(Rank other) {
        return this.id >= other.id;
    }

    public String colored() {
        return color + display;
    }

    public Rank up() {
        return fromId(Math.min(LIDER.id, id + 1));
    }

    public Rank down() {
        return fromId(Math.max(RECLUTA.id, id - 1));
    }

    public static Rank fromId(int id) {
        for (Rank r : values()) if (r.id == id) return r;
        return RECLUTA;
    }

    public static Rank parse(String s, Rank def) {
        if (s == null) return def;
        for (Rank r : values()) if (r.name().equalsIgnoreCase(s)) return r;
        return def;
    }
}
