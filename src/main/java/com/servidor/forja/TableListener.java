package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Al hacer clic derecho en una mesa de crafteo vanilla se abre el menú de Forja. */
public final class TableListener implements Listener {
    private final ForjaPlugin plugin;

    public TableListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || b.getType() != Material.CRAFTING_TABLE) return;
        if (!plugin.getConfig().getBoolean("mesa_crafteo.activada", true)) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("forja.mesa")) return;
        // agachado con algo en la mano: comportamiento vanilla (colocar bloques)
        if (p.isSneaking() && !p.getInventory().getItemInMainHand().getType().isAir()) return;
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        TableHub.open(plugin, p, b.getLocation());
    }
}
