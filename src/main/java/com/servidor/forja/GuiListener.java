package com.servidor.forja;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

public final class GuiListener implements Listener {
    private final ForjaPlugin plugin;

    public GuiListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof ForgeMenu fm) fm.click(e);
        else if (h instanceof TableHub th) th.click(e);
        else if (h instanceof OficiosMenu) e.setCancelled(true);
        else if (h instanceof AccessoryManager.Holder ah) plugin.accessories.click(e, ah);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof ForgeMenu || h instanceof OficiosMenu || h instanceof TableHub || h instanceof AccessoryManager.Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        InventoryHolder h = e.getInventory().getHolder();
        if (h instanceof ForgeMenu fm) fm.closed();
        else if (h instanceof AccessoryManager.Holder ah && e.getPlayer() instanceof Player p) plugin.accessories.closed(p, ah);
    }
}
