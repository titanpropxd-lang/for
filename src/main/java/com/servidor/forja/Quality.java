package com.servidor.forja;

public enum Quality {
    TOSCA("Tosca", "<gray>"),
    COMUN("Común", "<white>"),
    BUENA("Buena", "<green>"),
    EXCELENTE("Excelente", "<aqua>"),
    MAESTRA("Maestra", "<gold>");

    public final String display;
    public final String tag;

    Quality(String display, String tag) {
        this.display = display;
        this.tag = tag;
    }

    public static Quality of(int i) {
        Quality[] v = values();
        return v[Math.max(0, Math.min(v.length - 1, i))];
    }
}
