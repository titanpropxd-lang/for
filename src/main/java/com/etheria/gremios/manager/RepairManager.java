package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.TerritoryManager.DamageRecord;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Territory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Reparación de los bloques destruidos por asedios (Maestro de Obras). */
public class RepairManager {
    private final GremiosPlugin plugin;

    public RepairManager(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    public static class Sector {
        public final int cx, cz;
        public final List<DamageRecord> blocks = new ArrayList<>();

        public Sector(int cx, int cz) {
            this.cx = cx;
            this.cz = cz;
        }
    }

    public static class Result {
        public int repaired;
        public int remaining;
        public long paid;
        public String error;
    }

    /** Agrupa los bloques dañados por chunk (sectores). */
    public List<Sector> sectors(Territory t) {
        Map<Long, Sector> map = new LinkedHashMap<>();
        for (DamageRecord r : plugin.territories.damage(t).values()) {
            int cx = Math.floorDiv(r.x, 16);
            int cz = Math.floorDiv(r.z, 16);
            long k = ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
            map.computeIfAbsent(k, kk -> new Sector(cx, cz)).blocks.add(r);
        }
        List<Sector> l = new ArrayList<>(map.values());
        l.sort(Comparator.comparingInt((Sector s) -> s.cx).thenComparingInt(s -> s.cz));
        return l;
    }

    public Material itemFor(BlockData bd) {
        Material m = bd.getPlacementMaterial();
        if (m == null || m.isAir() || !m.isItem()) return null;
        return m;
    }

    /** Materiales necesarios para un sector (ordenados de más a menos). */
    public Map<Material, Integer> needs(List<DamageRecord> recs) {
        Map<Material, Integer> out = new TreeMap<>();
        for (DamageRecord r : recs) {
            try {
                Material m = itemFor(Bukkit.createBlockData(r.data));
                if (m != null) out.merge(m, 1, Integer::sum);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return out;
    }

    public long bankCost(int blocks) {
        int per = Math.max(1, plugin.getConfig().getInt("obras.bloques-por-corona", 25));
        return (blocks + per - 1) / per;
    }

    private int countItem(Player p, Material m) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (it != null && it.getType() == m && !it.hasItemMeta()) n += it.getAmount();
        }
        return n;
    }

    private boolean takeItem(Player p, Material m) {
        ItemStack[] cont = p.getInventory().getStorageContents();
        for (int i = 0; i < cont.length; i++) {
            ItemStack it = cont[i];
            if (it != null && it.getType() == m && !it.hasItemMeta()) {
                if (it.getAmount() <= 1) cont[i] = null;
                else it.setAmount(it.getAmount() - 1);
                p.getInventory().setStorageContents(cont);
                return true;
            }
        }
        return false;
    }

    /**
     * Repara bloques de un sector (o de todos si sector == null).
     * useBank: paga con Coronas del banco del gremio en lugar de materiales del inventario.
     */
    public Result repair(Player p, Territory t, Sector sector, boolean useBank) {
        Result res = new Result();
        Guild g = plugin.guilds.of(p);
        if (g == null || !g.id.equals(t.ownerGuildId)) {
            res.error = "Este territorio no es de tu gremio.";
            return res;
        }
        World w = t.bukkitWorld();
        if (w == null) {
            res.error = "El mundo no está cargado.";
            return res;
        }
        List<DamageRecord> list = new ArrayList<>();
        if (sector != null) list.addAll(sector.blocks);
        else for (Sector s : sectors(t)) list.addAll(s.blocks);
        int max = plugin.getConfig().getInt("obras.max-bloques-por-operacion", 4000);
        list.sort(Comparator.comparingInt((DamageRecord r) -> r.y));
        if (list.size() > max) list = new ArrayList<>(list.subList(0, max));

        if (useBank) {
            long cost = bankCost(list.size());
            if (g.bank < cost) {
                res.error = "El banco necesita " + plugin.currency.fmt(cost) + " para reparar " + list.size() + " bloques.";
                return res;
            }
            g.bank -= cost;
            g.log("§6-" + com.etheria.gremios.util.Msg.num(cost) + " §7obras");
            res.paid = cost;
        }
        for (DamageRecord r : list) {
            Block b = w.getBlockAt(r.x, r.y, r.z);
            BlockData bd;
            try {
                bd = Bukkit.createBlockData(r.data);
            } catch (IllegalArgumentException ex) {
                plugin.territories.removeDamage(t, r.x, r.y, r.z);
                continue;
            }
            if (b.getBlockData().equals(bd)) {
                plugin.territories.removeDamage(t, r.x, r.y, r.z);
                continue;
            }
            if (!useBank) {
                Material need = itemFor(bd);
                if (need != null && !takeItem(p, need)) {
                    res.remaining++;
                    continue;
                }
            }
            b.setBlockData(bd, false);
            plugin.territories.removeDamage(t, r.x, r.y, r.z);
            res.repaired++;
        }
        res.remaining = plugin.territories.damageCount(t);
        plugin.territories.saveDamage(t);
        plugin.guilds.save();
        return res;
    }
}
