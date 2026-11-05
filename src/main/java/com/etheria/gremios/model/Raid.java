package com.etheria.gremios.model;

import org.bukkit.boss.BossBar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Estado de un raid (asedio) sobre un territorio. */
public class Raid {
    public enum State { SCHEDULED, ACTIVE, LOOT, RESET }

    public final String territoryId;
    public final String attackerId;
    public final String defenderId;
    public State state = State.SCHEDULED;

    public long declaredAt;
    public long startAt;
    public long endAt;
    public long lootEndAt;
    public int postponements;
    /** Raid de prueba de administrador: sin gremios, sin dueño, no se guarda. */
    public boolean test;

    public double nodeHp;
    public double nodeMax;
    public long lastDamage;
    public long shieldUntil;
    public long windowStart;
    public double windowDamage;

    /** Gremio defensor (nombre) guardado por si el gremio desaparece durante el saqueo. */
    public String defenderName;
    public String attackerName;

    // --- transitorio (no se guarda) ---
    public UUID nodeEntity;
    public UUID nodeDisplay;
    public int notices;
    public final List<UUID> guards = new ArrayList<>();
    public BossBar bar;
    public long lastGuardTick;

    public Raid(String territoryId, String attackerId, String defenderId) {
        this.territoryId = territoryId;
        this.attackerId = attackerId;
        this.defenderId = defenderId;
    }
}
