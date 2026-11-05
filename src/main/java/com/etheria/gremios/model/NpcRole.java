package com.etheria.gremios.model;

import org.bukkit.entity.Villager;

/** Roles de los NPCs que aparecen al comprar un territorio. */
public enum NpcRole {
    ALCALDE("§6Alcalde del Gremio", 1, Villager.Profession.CARTOGRAPHER),
    INTENDENTE("§eIntendente", 1, Villager.Profession.LEATHERWORKER),
    MAESTRO("§bMaestro de Obras", 1, Villager.Profession.MASON),
    HERRERO("§cHerrero de Asedio", 2, Villager.Profession.WEAPONSMITH),
    CAPITAN("§aCapitán de la Guardia", 2, Villager.Profession.ARMORER),
    EMBAJADOR("§dEmbajador", 3, Villager.Profession.CLERIC);

    public final String display;
    public final int minLevel;
    public final Villager.Profession profession;

    NpcRole(String display, int minLevel, Villager.Profession profession) {
        this.display = display;
        this.minLevel = minLevel;
        this.profession = profession;
    }

    public String key() {
        return name().toLowerCase();
    }

    public static NpcRole parse(String s) {
        if (s == null) return null;
        for (NpcRole r : values()) if (r.name().equalsIgnoreCase(s)) return r;
        return null;
    }
}
