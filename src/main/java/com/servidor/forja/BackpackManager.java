package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;

/**
 * Mochilas con inventario real. El contenido vive en el propio item (PDC) y se guarda tras cada clic.
 * Cada mochila tiene un id único; no se pueden meter mochilas ni shulkers dentro, ni mover/soltar la mochila abierta.
 */
public final class BackpackManager implements Listener {
    private static final int UI_SIZE = 27;

    public static final class Holder implements InventoryHolder {
        Inventory inv;
        String bagId;
        int slot;
        int size;
        boolean dropped;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final ForjaPlugin plugin;

    public BackpackManager(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    private static String bagIdOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.BAG_ID, PersistentDataType.STRING);
    }

    private boolean forbidden(ItemStack it) {
        if (it == null || it.getType().isAir()) return false;
        if (it.getType().name().endsWith("SHULKER_BOX")) return true;
        CustomItem ci = plugin.items.of(it);
        return ci != null && ci.categoria.equals("mochila");
    }

    private static Holder holderOf(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h ? h : null;
    }

    // ------------------------------------------------------------------ abrir

    public void open(Player p) {
        PlayerInventory pi = p.getInventory();
        ItemStack bag = pi.getItemInMainHand();
        CustomItem ci = plugin.items.of(bag);
        if (ci == null || !ci.categoria.equals("mochila") || holderOf(p) != null) return;
        ItemMeta m = bag.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        String id = pdc.get(Keys.BAG_ID, PersistentDataType.STRING);
        if (id == null) {   // mochila dada por otra vía sin id: se le asigna uno
            id = UUID.randomUUID().toString();
            pdc.set(Keys.BAG_ID, PersistentDataType.STRING, id);
            bag.setItemMeta(m);
            pi.setItemInMainHand(bag);
        }
        String data = pdc.get(Keys.BAG_DATA, PersistentDataType.STRING);
        int usable = Math.max(1, Math.min(UI_SIZE, (int) ci.attr("slots")));
        Holder h = new Holder();
        h.bagId = id;
        h.size = usable;
        h.slot = pi.getHeldItemSlot();
        h.inv = Bukkit.createInventory(h, UI_SIZE, plugin.guiStyle.title("mochila",
                plugin.lang.get("gui.mochila.titulo", "nombre", Lang.esc(ci.nombre))));
        ItemStack[] arr = ItemCodec.decode(data, usable);
        for (int i = 0; i < usable; i++) h.inv.setItem(i, arr[i]);
        ItemStack lock = Gui.icon(Material.GRAY_STAINED_GLASS_PANE, plugin.lang.raw("gui.mochila.bloqueada"));
        for (int i = usable; i < UI_SIZE; i++) h.inv.setItem(i, lock);
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.8f, 1.1f);
    }

    // ------------------------------------------------------------------ guardar

    private int findBag(PlayerInventory pi, Holder h) {
        if (h.slot >= 0 && h.slot <= 40 && h.bagId.equals(bagIdOf(pi.getItem(h.slot)))) return h.slot;
        for (int i = 0; i <= 40; i++) if (h.bagId.equals(bagIdOf(pi.getItem(i)))) return i;
        return -1;
    }

    private void save(Player p, Holder h) {
        if (h.dropped) return;
        ItemStack[] arr = new ItemStack[h.size];
        for (int i = 0; i < h.size; i++) arr[i] = h.inv.getItem(i);
        PlayerInventory pi = p.getInventory();
        int idx = findBag(pi, h);
        if (idx < 0) {   // la mochila ya no está con el jugador: el contenido se suelta para no perderlo ni duplicarlo
            for (ItemStack it : arr) if (it != null && !it.getType().isAir()) p.getWorld().dropItemNaturally(p.getLocation(), it);
            for (int i = 0; i < h.size; i++) h.inv.setItem(i, null);
            h.dropped = true;
            return;
        }
        ItemStack bag = pi.getItem(idx);
        ItemMeta m = bag.getItemMeta();
        m.getPersistentDataContainer().set(Keys.BAG_DATA, PersistentDataType.STRING, ItemCodec.encode(arr));
        bag.setItemMeta(m);
        pi.setItem(idx, bag);
    }

    public void closeAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Holder h = holderOf(p);
            if (h != null) {
                save(p, h);
                p.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------------ eventos

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        boolean top = e.getClickedInventory() == e.getInventory();
        int raw = e.getRawSlot();
        if (top && raw >= h.size) {   // casillas bloqueadas
            e.setCancelled(true);
            return;
        }
        ItemStack hot = e.getHotbarButton() >= 0 ? p.getInventory().getItem(e.getHotbarButton()) : null;
        boolean offhandSwap = e.getClick() == ClickType.SWAP_OFFHAND && forbidden(p.getInventory().getItemInOffHand());
        if ((top && (forbidden(e.getCursor()) || forbidden(hot))) || offhandSwap
                || (!top && e.isShiftClick() && forbidden(e.getCurrentItem()))) {
            e.setCancelled(true);
            return;
        }
        // la mochila abierta no se puede mover mientras está abierta
        if (!top && h.bagId.equals(bagIdOf(e.getCurrentItem()))) {
            e.setCancelled(true);
            return;
        }
        if (e.getClick() == ClickType.NUMBER_KEY && h.bagId.equals(bagIdOf(hot))) {
            e.setCancelled(true);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> save(p, h));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        boolean bad = forbidden(e.getOldCursor());
        for (int raw : e.getRawSlots()) {
            if (raw < UI_SIZE && (bad || raw >= h.size)) {
                e.setCancelled(true);
                return;
            }
        }
        if (e.getWhoClicked() instanceof Player p) Bukkit.getScheduler().runTask(plugin, () -> save(p, h));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof Holder h && e.getPlayer() instanceof Player p) save(p, h);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Holder h = holderOf(e.getPlayer());
        if (h != null) save(e.getPlayer(), h);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        Holder h = holderOf(e.getPlayer());
        if (h != null && h.bagId.equals(bagIdOf(e.getItemDrop().getItemStack()))) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent e) {
        if (holderOf(e.getPlayer()) != null) e.setCancelled(true);
    }
}
