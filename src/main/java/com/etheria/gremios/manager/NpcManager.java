package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.NpcRole;
import com.etheria.gremios.model.Territory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.persistence.PersistentDataType;

/** NPCs del territorio (aldeanos sin IA con rol). No necesita Citizens. */
public class NpcManager {
    private final GremiosPlugin plugin;
    private final NamespacedKey kRole;
    private final NamespacedKey kTerr;

    public NpcManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.kRole = plugin.key("npc_role");
        this.kTerr = plugin.key("npc_terr");
    }

    public NpcRole roleOf(Entity e) {
        if (e == null) return null;
        String s = e.getPersistentDataContainer().get(kRole, PersistentDataType.STRING);
        return NpcRole.parse(s);
    }

    public Territory territoryOf(Entity e) {
        String s = e.getPersistentDataContainer().get(kTerr, PersistentDataType.STRING);
        return s == null ? null : plugin.territories.get(s);
    }

    public Location loc(Territory t, NpcRole r) {
        World w = t.bukkitWorld();
        if (w == null) return null;
        double[] p = t.npcPos.get(r.key());
        if (p != null && p.length >= 4) return new Location(w, p[0], p[1], p[2], (float) p[3], 0f);
        Location c = t.center();
        int idx = r.ordinal();
        double x = c.getX() + (idx - 2.5) * 2.0;
        double z = c.getZ() + 3.0;
        x = Math.max(t.minX + 0.5, Math.min(t.maxX + 0.5, x));
        z = Math.max(t.minZ + 0.5, Math.min(t.maxZ + 0.5, z));
        int y = w.getHighestBlockYAt((int) Math.floor(x), (int) Math.floor(z)) + 1;
        return new Location(w, x, y, z, 180f, 0f);
    }

    private Villager find(Territory t, NpcRole r) {
        Location l = loc(t, r);
        if (l == null) return null;
        l.getChunk().load();
        for (Entity e : l.getWorld().getNearbyEntities(l, 8, 8, 8)) {
            if (e instanceof Villager v && roleOf(v) == r) {
                String tid = v.getPersistentDataContainer().get(kTerr, PersistentDataType.STRING);
                if (t.id.equals(tid)) return v;
            }
        }
        return null;
    }

    private void ensure(Territory t, NpcRole r) {
        Location l = loc(t, r);
        if (l == null) return;
        Villager v = find(t, r);
        if (v != null) return;
        final String tid = t.id;
        Villager nv = l.getWorld().spawn(l, Villager.class, e -> {
            e.setAI(false);
            e.setInvulnerable(true);
            e.setSilent(true);
            e.setPersistent(true);
            e.setRemoveWhenFarAway(false);
            e.setCollidable(false);
            e.setInvisible(plugin.models != null && plugin.models.enabled());
            e.setProfession(r.profession);
            e.setCustomName(r.display);
            e.setCustomNameVisible(true);
            e.setVillagerLevel(5);
            e.getPersistentDataContainer().set(kRole, PersistentDataType.STRING, r.name());
            e.getPersistentDataContainer().set(kTerr, PersistentDataType.STRING, tid);
        });
        t.npcUuid.put(r.key(), nv.getUniqueId().toString());
    }

    private void remove(Territory t, NpcRole r) {
        Villager v = find(t, r);
        while (v != null) {
            v.remove();
            v = find(t, r);
        }
        t.npcUuid.remove(r.key());
    }

    /** Hace coincidir los NPCs del territorio con el nivel de su dueño. */
    public void sync(Territory t) {
        if (t.bukkitWorld() == null) return;
        Guild g = plugin.guilds.byId(t.ownerGuildId);
        int level = g == null ? 0 : g.territoryLevel;
        for (NpcRole r : NpcRole.values()) {
            if (level > 0 && r.minLevel <= level) ensure(t, r);
            else remove(t, r);
        }
        plugin.territories.save();
    }

    public void removeAll(Territory t) {
        if (t.bukkitWorld() == null) return;
        for (NpcRole r : NpcRole.values()) remove(t, r);
    }

    public void syncAll() {
        for (Territory t : plugin.territories.all()) {
            if (t.ownerGuildId != null && !t.resetting) sync(t);
        }
    }

    public int countLoaded() {
        int n = 0;
        for (World w : Bukkit.getWorlds()) for (Villager v : w.getEntitiesByClass(Villager.class)) if (roleOf(v) != null) n++;
        return n;
    }
}
