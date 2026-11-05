package com.etheria.gremios.model;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;

/** Un territorio reclamado por el administrador y comprable por gremios. */
public class Territory {
    public final String id;
    public String name;
    public String world;
    public int minX, minY, minZ, maxX, maxY, maxZ;

    /** Punto de salida / aparición (null = automático). */
    public double[] spawn; // x,y,z,yaw
    /** Posición del núcleo durante un raid (null = centro). */
    public double[] node;  // x,y,z
    /** Posiciones de NPCs por rol: x,y,z,yaw. */
    public final Map<String, double[]> npcPos = new LinkedHashMap<>();
    /** UUID de la entidad NPC vigente por rol. */
    public final Map<String, String> npcUuid = new LinkedHashMap<>();

    public long price = 64;
    public long priceFort = -1;
    public long priceRein = -1;

    public String ownerGuildId;
    public boolean resetting;

    public Territory(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public boolean contains(Location l) {
        if (l == null || l.getWorld() == null || !l.getWorld().getName().equals(world)) return false;
        return contains(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public long volume() {
        return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
    }

    public Location center() {
        World w = bukkitWorld();
        return new Location(w, (minX + maxX) / 2.0 + 0.5, (minY + maxY) / 2.0, (minZ + maxZ) / 2.0 + 0.5);
    }

    public boolean free() {
        return ownerGuildId == null;
    }

    public String shortSize() {
        return (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1);
    }
}
