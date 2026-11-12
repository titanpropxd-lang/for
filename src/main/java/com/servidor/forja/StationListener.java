package com.servidor.forja;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public final class StationListener implements Listener {
    /** Cuando se cargan las entidades de un chunk se reconstruyen los modelos de sus estaciones (brillo, posición y giro). */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        plugin.stations.refreshChunk(e.getWorld(), e.getChunk().getX(), e.getChunk().getZ());
    }

    private final ForjaPlugin plugin;

    public StationListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        CustomItem ci = plugin.items.of(e.getItemInHand());
        if (ci == null || !ci.categoria.equals("estacion")) return;
        if (!e.getPlayer().hasPermission("forja.usar")) {
            e.setCancelled(true);
            plugin.lang.send(e.getPlayer(), "general.sin_permiso");
            return;
        }
        String type = ci.id.substring("estacion_".length());
        if (plugin.stationModels != null) plugin.stationModels.setFacing(e.getBlockPlaced(), e.getPlayer().getLocation().getYaw());
        plugin.stations.add(e.getBlockPlaced(), type);
        plugin.lang.send(e.getPlayer(), "estacion.colocada", "e", plugin.lang.raw("estacion." + type));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        String type = plugin.stations.typeAt(e.getBlock());
        if (type == null) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        if (plugin.foundry != null) plugin.foundry.dropContents(e.getBlock());
        if (plugin.tannery != null) plugin.tannery.dropContents(e.getBlock());
        plugin.stations.remove(e.getBlock());
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE) {
            Location l = e.getBlock().getLocation().add(0.5, 0.5, 0.5);
            l.getWorld().dropItemNaturally(l, plugin.items.basic("estacion_" + type, 1));
        }
        plugin.lang.send(e.getPlayer(), "estacion.retirada");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        // con mesas.modo = todas, las abre WorkbenchListener (cualquier bloque); aquí solo el modo antiguo "registradas"
        if (!"registradas".equalsIgnoreCase(plugin.getConfig().getString("mesas.modo", "todas"))) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        String type = plugin.stations.typeAt(b);
        if (type == null) return;
        Player p = e.getPlayer();
        if (p.isSneaking()) {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand != null && !hand.getType().isAir()) return; // permite colocar bloques agachado con algo en la mano
        }
        // validar que el bloque siga siendo el de la estación
        CustomItem ci = plugin.items.get("estacion_" + type);
        if (ci != null && b.getType() != ci.base) {
            plugin.stations.remove(b);
            return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        ForgeMenu.open(plugin, p, type, b.getLocation().add(0.5, 0.5, 0.5));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        if (plugin.getConfig().getBoolean("estaciones.proteger_explosiones", true))
            e.blockList().removeIf(b -> plugin.stations.typeAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        if (plugin.getConfig().getBoolean("estaciones.proteger_explosiones", true))
            e.blockList().removeIf(b -> plugin.stations.typeAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) if (plugin.stations.typeAt(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) if (plugin.stations.typeAt(b) != null) { e.setCancelled(true); return; }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFall(EntityChangeBlockEvent e) {
        if (plugin.stations.typeAt(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void protectStationModel(EntityDamageEvent e) {
        if (e.getEntity() instanceof ItemDisplay display && display.getPersistentDataContainer().has(new org.bukkit.NamespacedKey(plugin, "station_visual"), org.bukkit.persistence.PersistentDataType.STRING)) e.setCancelled(true);
    }
}
