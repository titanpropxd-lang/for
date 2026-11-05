package com.etheria.gremios.listener;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.ZoneManager;
import com.etheria.gremios.util.ItemBuilder;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.entity.Villager;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Aldeano de las zonas de recursos: abre el "cofre" de la zona. */
public class ZoneListener implements Listener {
    private final GremiosPlugin plugin;

    public ZoneListener(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    public static class ZoneChest implements InventoryHolder {
        public final String zoneId;
        private Inventory inv;

        public ZoneChest(String zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpc(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (plugin.board.boardOf(e.getRightClicked()) != null) {
            e.setCancelled(true);
            plugin.extraGuis.missions(e.getPlayer(), 0);
            return;
        }
        ZoneManager.Zone z = plugin.zones.zoneOfNpc(e.getRightClicked());
        if (z == null) return;
        e.setCancelled(true);
        open(e.getPlayer(), z);
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent e) {
        for (Entity en : e.getEntities()) if (en instanceof Villager v) {
            plugin.zones.onNpcLoaded(v);
            plugin.board.onLoaded(v);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent e) {
        if (plugin.zones.zoneOfNpc(e.getEntity()) != null || plugin.board.boardOf(e.getEntity()) != null) e.setCancelled(true);
    }

    public void open(Player p, ZoneManager.Zone z) {
        if (z.owner == null) {
            Msg.to(p, "§e" + z.name + " §7está libre. Mantente dentro de la zona §f" + z.captureSeconds + "s §7sin que haya otro bando para conquistarla.");
            return;
        }
        if (!plugin.zones.isOwner(p, z)) {
            Msg.to(p, "§e" + z.name + " §7pertenece a §6" + plugin.zones.factionName(z.owner) + "§7. Entra en la zona y mantente §f" + z.captureSeconds + "s §7para conquistarla.");
            return;
        }
        ZoneChest h = new ZoneChest(z.id);
        Inventory inv = Bukkit.createInventory(h, 27, "§8" + z.name + " §7- Recursos");
        h.inv = inv;
        render(inv, z);
        p.openInventory(inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 1f, 1f);
    }

    private void render(Inventory inv, ZoneManager.Zone z) {
        inv.clear();
        for (int i = 0; i < z.produce.size() && i < 18; i++) {
            ItemStack it = z.produce.get(i).clone();
            long stock = z.stock.get(i);
            it.setAmount((int) Math.max(1, Math.min(64, stock)));
            var meta = it.getItemMeta();
            List<String> lore = new ArrayList<>();
            lore.add("§7Almacén: §f" + stock + "§7/§f" + plugin.zones.cap(z, i));
            lore.add("§7Produce: §f+" + plugin.zones.perCycle(z, i) + " §7cada " + z.intervalMin + " min §e(nivel " + plugin.zones.levelOf(z) + ")");
            lore.add("");
            lore.add(stock > 0 ? "§aClic para recoger" : "§8Vacío");
            meta.setLore(lore);
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        inv.setItem(22, new ItemBuilder(Material.CHEST).name("§6Recoger todo")
                .lore("§7Total en almacén: §f" + plugin.zones.totalStock(z)).build());
        inv.setItem(26, new ItemBuilder(Material.BARRIER).name("§7Cerrar").build());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof ZoneChest h)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!(e.getWhoClicked() instanceof Player p)) return;
        ZoneManager.Zone z = plugin.zones.get(h.zoneId);
        if (z == null || !plugin.zones.isOwner(p, z)) {
            p.closeInventory();
            return;
        }
        int slot = e.getSlot();
        if (slot == 26) {
            p.closeInventory();
            return;
        }
        long got = 0;
        if (slot == 22) got = plugin.zones.collect(p, z, -1);
        else if (slot < z.produce.size()) got = plugin.zones.collect(p, z, slot);
        else return;
        if (got > 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
            Msg.actionbar(p, "§aRecogidos §f" + got + " §arecursos");
        } else if (plugin.zones.totalStock(z) > 0) {
            Msg.actionbar(p, "§cInventario lleno.");
        }
        render(e.getView().getTopInventory(), z);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ZoneChest) e.setCancelled(true);
    }
}
