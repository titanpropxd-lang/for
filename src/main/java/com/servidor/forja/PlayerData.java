package com.servidor.forja;

import org.bukkit.inventory.ItemStack;

import java.util.*;

public final class PlayerData {
    public final UUID uuid;
    public final EnumMap<Profession, int[]> prof = new EnumMap<>(Profession.class);
    public final Map<String, String> flags = new HashMap<>();
    public final Map<String, Integer> stats = new HashMap<>();
    public final ItemStack[] accessories = new ItemStack[4];

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
    }

    public int level(Profession p) {
        int[] a = prof.get(p);
        return a == null ? 1 : a[0];
    }

    public int xp(Profession p) {
        int[] a = prof.get(p);
        return a == null ? 0 : a[1];
    }

    public int total() {
        int t = 0;
        for (int v : stats.values()) t += v;
        return t;
    }
}
