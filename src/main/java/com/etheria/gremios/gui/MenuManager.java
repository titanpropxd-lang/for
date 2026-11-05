package com.etheria.gremios.gui;

import com.etheria.gremios.GremiosPlugin;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.InventoryMoveItemEvent;

import java.util.function.Consumer;

/** Gestiona los clics en los menús del plugin. */
public class MenuManager implements Listener {
    private final GremiosPlugin plugin;

    public MenuManager(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getView().getTopInventory()) return;
        Consumer<InventoryClickEvent> act = m.action(e.getSlot());
        if (act == null) return;
        if (e.getWhoClicked() instanceof Player p) p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.3f);
        try {
            act.accept(e);
        } catch (Exception ex) {
            plugin.getLogger().warning("Error en un menú (" + m.layout.id + "): " + ex);
            ex.printStackTrace();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
