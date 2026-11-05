package com.etheria.gremios.model;

import org.bukkit.Location;

import java.util.LinkedHashSet;
import java.util.Set;

/** Definición de un evento de territorio (la configura el admin). */
public class EventDef {
    public enum Type { KOTH, JEFE }

    public final String id;
    public String name;
    public Type type = Type.KOTH;
    public String world;
    public int minX, minY, minZ, maxX, maxY, maxZ;
    public boolean hasZone;
    /** Duración máxima del evento (minutos). */
    public int durationMin = 30;
    /** KOTH: segundos acumulados que un gremio debe mantener la zona para ganar. */
    public int holdSeconds = 300;
    /** JEFE: vida del jefe. */
    public double bossHp = 2000;
    public long rewardXp = 500;
    public long rewardCrowns = 0;
    /** Cada cuántos minutos se lanza solo (0 = solo manual). */
    public int intervalMin = 0;
    public long nextStart = 0;
    public int minGuildLevel = 1;
    /** Ids de gremios permitidos (vacío = todos). */
    public final Set<String> allowed = new LinkedHashSet<>();
    public boolean enabled = true;

    public EventDef(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean contains(Location l) {
        if (!hasZone || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        return l.getBlockX() >= minX && l.getBlockX() <= maxX && l.getBlockY() >= minY && l.getBlockY() <= maxY
                && l.getBlockZ() >= minZ && l.getBlockZ() <= maxZ;
    }
}
