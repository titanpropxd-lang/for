package com.etheria.gremios.model;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Un gremio. Los datos se guardan en guilds.yml. */
public class Guild {
    public static final int MAX_LEVEL = 20;

    public static class MemberData {
        public String name;
        public Rank rank;
        public long joinedAt;
        public long duesPending;

        public MemberData(String name, Rank rank) {
            this.name = name;
            this.rank = rank;
            this.joinedAt = System.currentTimeMillis();
        }
    }

    public final String id;
    public String name;
    public UUID leader;
    public final Map<UUID, MemberData> members = new LinkedHashMap<>();
    public final Map<UUID, String> requests = new LinkedHashMap<>();

    public String territoryId;
    public int territoryLevel; // 0 = sin territorio, 1 pueblo, 2 fortaleza, 3 reinado

    public int level = 1;
    public long xp;
    public long bank;

    public long createdAt = System.currentTimeMillis();
    public long nextUpkeep;
    public int upkeepMissed;
    public long dues; // cuota por miembro y periodo

    public int upGuard;
    public int upCore;
    public int upCapacity;

    public long protectedUntil;
    public long attackCooldownUntil;

    public final Set<String> allies = new HashSet<>();
    public final Set<String> allyRequestsIn = new HashSet<>();
    public final Deque<String> bankLog = new ArrayDeque<>();

    public Guild(String id, String name, UUID leader) {
        this.id = id;
        this.name = name;
        this.leader = leader;
    }

    public MemberData member(UUID u) {
        return members.get(u);
    }

    public Rank rankOf(UUID u) {
        MemberData m = members.get(u);
        return m == null ? null : m.rank;
    }

    public boolean has(UUID u) {
        return members.containsKey(u);
    }

    public boolean hasTerritory() {
        return territoryId != null && territoryLevel > 0;
    }

    public void log(String line) {
        bankLog.addFirst(line);
        while (bankLog.size() > 15) bankLog.removeLast();
    }

    public static String levelName(int territoryLevel) {
        return switch (territoryLevel) {
            case 1 -> "Pueblo";
            case 2 -> "Fortaleza";
            case 3 -> "Reinado";
            default -> "Sin territorio";
        };
    }
}
