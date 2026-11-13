package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Moldes y piezas intermedias (hojas, cabezas, puntas, placas) NO son armas ni herramientas.
 * - Base nueva: iron_nugget (inerte). 
 * - Este guard cubre items VIEJOS (iron_sword/iron_axe/iron_pickaxe/iron_block con id pieza_*):
 *   los convierte a iron_nugget al entrar/abrir inventario/recoger y, mientras tanto,
 *   cancela daño, picado y colocación.
 */
public final class InertPieceGuard implements Listener {
    private final ForjaPlugin plugin;

    public InertPieceGuard(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean inert(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        String id = plugin.items.idOf(it);
        return id != null && (id.startsWith("pieza_hoja_") || id.startsWith("pieza_cabeza_"));
    }

    private boolean legacy(Material m) {
        return m == Material.IRON_SWORD || m == Material.IRON_AXE || m == Material.IRON_PICKAXE || m == Material.IRON_BLOCK;
    }

    private void fix(ItemStack it) {
        if (inert(it) && legacy(it.getType())) it.setType(Material.IRON_NUGGET);
    }

    private void fixAll(Player p) {
        for (ItemStack it : p.getInventory().getContents()) fix(it);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        fixAll(e.getPlayer());
    }

    @EventHandler
    public void onOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) fixAll(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent e) {
        fix(e.getItem().getItemStack());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && inert(p.getInventory().getItemInMainHand())) {
            e.setCancelled(true);
            fix(p.getInventory().getItemInMainHand());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDig(BlockDamageEvent e) {
        if (inert(e.getItemInHand())) {
            e.setCancelled(true);
            fix(e.getItemInHand());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (inert(e.getItemInHand())) e.setCancelled(true);
    }
}
