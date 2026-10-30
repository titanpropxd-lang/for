package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Set;

/**
 * Anti-duplicación: los materiales/items custom no pueden entrar en crafteos vanilla,
 * yunque, herrería, hornos, pociones, mercaderes, mesas de mapa/telar, etc.
 */
public final class ProtectionListener implements Listener {
    private static final Set<InventoryType> BLOCKED = EnumSet.of(InventoryType.CARTOGRAPHY, InventoryType.LOOM,
            InventoryType.STONECUTTER, InventoryType.COMPOSTER, InventoryType.LECTERN, InventoryType.BEACON,
            InventoryType.SMITHING, InventoryType.ANVIL, InventoryType.GRINDSTONE, InventoryType.MERCHANT,
            InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER, InventoryType.BREWING);
    private static final Set<InventoryType> NO_MOVE_INTO = EnumSet.of(InventoryType.FURNACE, InventoryType.BLAST_FURNACE,
            InventoryType.SMOKER, InventoryType.BREWING, InventoryType.COMPOSTER);

    private final ForjaPlugin plugin;

    public ProtectionListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean custom(ItemStack it) {
        return plugin.items.isCustom(it);
    }

    private boolean anyCustom(ItemStack[] arr) {
        if (arr == null) return false;
        for (ItemStack it : arr) if (custom(it)) return true;
        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onCraft(PrepareItemCraftEvent e) {
        if (anyCustom(e.getInventory().getMatrix())) e.getInventory().setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onAnvil(PrepareAnvilEvent e) {
        if (anyCustom(e.getInventory().getContents())) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSmithing(PrepareSmithingEvent e) {
        if (anyCustom(e.getInventory().getContents())) e.setResult(null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSmelt(org.bukkit.event.inventory.FurnaceSmeltEvent e) {
        if (custom(e.getSource())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBurn(org.bukkit.event.inventory.FurnaceBurnEvent e) {
        if (custom(e.getFuel())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrew(org.bukkit.event.inventory.BrewEvent e) {
        if (custom(e.getContents().getIngredient()) || anyCustom(e.getContents().getStorageContents())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBrewFuel(org.bukkit.event.inventory.BrewingStandFuelEvent e) {
        if (custom(e.getFuel())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent e) {
        if (!custom(e.getItem())) return;
        Inventory dest = e.getDestination();
        if (NO_MOVE_INTO.contains(dest.getType())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getInventory();
        if (top.getHolder() instanceof ForgeMenu || top.getHolder() instanceof AccessoryManager.Holder) return;
        InventoryType t = top.getType();
        boolean blocked = BLOCKED.contains(t);
        boolean enchant = t == InventoryType.ENCHANTING;
        if (!blocked && !enchant) return;
        boolean topClicked = e.getClickedInventory() == top;
        ItemStack hot = e.getHotbarButton() >= 0 && e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) != null
                ? e.getWhoClicked().getInventory().getItem(e.getHotbarButton()) : null;
        if (blocked) {
            if (topClicked && (custom(e.getCursor()) || custom(hot))) e.setCancelled(true);
            else if (!topClicked && e.isShiftClick() && custom(e.getCurrentItem())) e.setCancelled(true);
        } else {
            // mesa de encantamientos: se pueden encantar items custom, pero el lapislázuli custom no vale como combustible
            boolean lapisCursor = custom(e.getCursor()) && e.getCursor().getType() == Material.LAPIS_LAZULI;
            boolean lapisHot = custom(hot) && hot.getType() == Material.LAPIS_LAZULI;
            boolean lapisShift = e.isShiftClick() && !topClicked && custom(e.getCurrentItem()) && e.getCurrentItem().getType() == Material.LAPIS_LAZULI;
            if ((topClicked && (lapisCursor || lapisHot)) || lapisShift) e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getInventory();
        if (!BLOCKED.contains(top.getType())) return;
        if (!custom(e.getOldCursor())) return;
        for (int raw : e.getRawSlots()) {
            if (raw < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
