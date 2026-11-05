package com.etheria.gremios.listener;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Rank;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Protección de territorios y avisos de entrada/salida. */
public class ProtectionListener implements Listener {
    private final GremiosPlugin plugin;
    private final Map<UUID, String> lastTerr = new HashMap<>();

    public ProtectionListener(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    /** ¿Puede este jugador modificar (o abrir cofres de) este territorio ahora mismo? */
    public boolean allowed(Player p, Territory t, boolean container) {
        UUID u = p.getUniqueId();
        if (plugin.bypass.contains(u)) return true;
        Raid r = plugin.raids.get(t.id);
        Guild pg = plugin.guilds.of(p);
        if (r != null && r.state == Raid.State.LOOT) return pg != null && pg.id.equals(r.attackerId);
        if (r != null && r.state == Raid.State.RESET) return false;
        if (t.resetting || t.ownerGuildId == null) return false;
        if (pg == null || !pg.id.equals(t.ownerGuildId)) return false;
        Rank rk = pg.rankOf(u);
        if (rk == Rank.RECLUTA) {
            return container ? plugin.getConfig().getBoolean("proteccion.reclutas-cofres", true)
                    : plugin.getConfig().getBoolean("proteccion.reclutas-construyen", false);
        }
        return true;
    }

    private void deny(Player p, Territory t) {
        Guild o = plugin.guilds.byId(t.ownerGuildId);
        Raid r = plugin.raids.get(t.id);
        String why;
        if (r != null && r.state == Raid.State.ACTIVE) why = "§cEn un asedio solo los cañones y catapultas dañan las defensas.";
        else if (o == null) why = "§cEste territorio está protegido.";
        else why = "§cTerritorio de §6" + o.name + "§c.";
        Msg.actionbar(p, why);
    }

    // ------------------------------------------------------------------ bloques

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Territory t = plugin.territories.at(e.getBlock().getLocation());
        if (t == null) return;
        if (!allowed(e.getPlayer(), t, false)) {
            e.setCancelled(true);
            deny(e.getPlayer(), t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        Territory t = plugin.territories.at(e.getBlock().getLocation());
        if (t == null) return;
        if (!allowed(e.getPlayer(), t, false)) {
            e.setCancelled(true);
            deny(e.getPlayer(), t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent e) {
        Territory t = plugin.territories.at(e.getBlock().getLocation());
        if (t != null && !allowed(e.getPlayer(), t, false)) {
            e.setCancelled(true);
            deny(e.getPlayer(), t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent e) {
        Territory t = plugin.territories.at(e.getBlock().getLocation());
        if (t != null && !allowed(e.getPlayer(), t, false)) {
            e.setCancelled(true);
            deny(e.getPlayer(), t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        Block b = e.getClickedBlock();
        if (b == null) return;
        Territory t = plugin.territories.at(b.getLocation());
        if (t == null) return;
        Player p = e.getPlayer();
        if (e.getAction() == Action.PHYSICAL) {
            Material m = b.getType();
            if ((m == Material.FARMLAND || m == Material.TURTLE_EGG || m == Material.SNIFFER_EGG) && !allowed(p, t, false)) {
                e.setCancelled(true);
            }
            return;
        }
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!b.getType().isInteractable()) return;
        if (!allowed(p, t, true)) {
            e.setUseInteractedBlock(Event.Result.DENY);
            deny(p, t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        Entity en = e.getRightClicked();
        if (!(en instanceof Hanging) && !(en instanceof ArmorStand)) return;
        Territory t = plugin.territories.at(en.getLocation());
        if (t != null && !allowed(e.getPlayer(), t, true)) {
            e.setCancelled(true);
            deny(e.getPlayer(), t);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakByEntityEvent e) {
        Territory t = plugin.territories.at(e.getEntity().getLocation());
        if (t == null) return;
        Entity remover = e.getRemover();
        if (remover instanceof Projectile pr && pr.getShooter() instanceof Entity s) remover = s;
        if (remover instanceof Player p) {
            if (!allowed(p, t, false)) {
                e.setCancelled(true);
                deny(p, t);
            }
        } else {
            e.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ explosiones y fuego

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.territories.at(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> plugin.territories.at(b.getLocation()) != null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent e) {
        Territory t = plugin.territories.at(e.getBlock().getLocation());
        if (t == null) return;
        Player p = e.getPlayer();
        if (p == null || !allowed(p, t, false)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        if (plugin.territories.at(e.getBlock().getLocation()) != null) e.setCancelled(true);
    }

    // ------------------------------------------------------------------ entrada / salida

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Location f = e.getFrom();
        Location to = e.getTo();
        if (to == null) return;
        if (f.getBlockX() == to.getBlockX() && f.getBlockY() == to.getBlockY() && f.getBlockZ() == to.getBlockZ()) return;
        Player p = e.getPlayer();
        Territory t = plugin.territories.at(to);
        String id = t == null ? null : t.id;
        String last = lastTerr.get(p.getUniqueId());
        if ((id == null && last == null) || (id != null && id.equals(last))) return;
        if (id == null) lastTerr.remove(p.getUniqueId());
        else lastTerr.put(p.getUniqueId(), id);
        if (t != null) {
            Guild o = plugin.guilds.byId(t.ownerGuildId);
            Raid r = plugin.raids.get(t.id);
            String owner = o == null ? "§aLibre" : "§6" + o.name;
            String extra = r != null && r.state == Raid.State.ACTIVE ? " §4⚔ ASEDIO" : "";
            Msg.actionbar(p, "§7Entras en §f" + t.name + " §8| " + owner + extra);
        } else if (last != null) {
            Territory lt = plugin.territories.get(last);
            Msg.actionbar(p, "§7Sales de §f" + (lt == null ? "el territorio" : lt.name));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastTerr.remove(e.getPlayer().getUniqueId());
    }
}
