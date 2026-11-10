package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Colada manual: metal líquido en mano principal + molde en mano secundaria. */
public final class CastingListener implements Listener {
    private static final Map<String, String> MOLDS = Map.of(
            "molde_espada", "hoja_espada", "molde_hacha", "cabeza_hacha",
            "molde_lanza", "punta_lanza", "molde_maza", "cabeza_maza",
            "molde_daga", "hoja_daga", "molde_pico", "cabeza_pico", "molde_placa", "placa");
    private static final String[] TIERS = {"cobre", "bronce", "hierro_forjado", "acero", "plata", "acero_negro", "mithril", "adamantita"};
    private final ForjaPlugin plugin;
    private final Map<UUID, Boolean> active = new ConcurrentHashMap<>();

    public CastingListener(ForjaPlugin plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPour(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("forja.usar")) return;
        String molten = plugin.items.idOf(player.getInventory().getItemInMainHand());
        String mold = plugin.items.idOf(player.getInventory().getItemInOffHand());
        if (molten == null || !molten.startsWith("metal_fundido_") || mold == null || !MOLDS.containsKey(mold)) return;
        String tier = molten.substring("metal_fundido_".length());
        boolean validTier = false;
        for (String known : TIERS) if (known.equals(tier)) validTier = true;
        if (!validTier) return;
        String output = "pieza_" + MOLDS.get(mold) + "_" + tier;
        if (plugin.items.get(output) == null) return;
        event.setCancelled(true);
        if (active.putIfAbsent(player.getUniqueId(), Boolean.TRUE) != null) {
            plugin.lang.send(player, "fundicion.ya_enfriando");
            return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getAmount() <= 1) player.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
        Location at = player.getLocation().clone();
        int ticks = tier.equals("mithril") || tier.equals("adamantita") ? 160 : tier.equals("acero_negro") || tier.equals("plata") ? 120 : 80;
        player.playSound(player.getLocation(), Sound.ITEM_BUCKET_EMPTY_LAVA, 0.8f, 0.8f);
        plugin.lang.send(player, "fundicion.vertido");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            active.remove(player.getUniqueId());
            ItemStack result = plugin.items.basic(output, 1);
            if (player.isOnline()) {
                plugin.forge.give(player, result);
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                plugin.lang.send(player, "fundicion.enfriado", "pieza", plugin.items.get(output).nombre);
            } else if (at.getWorld() != null) {
                at.getWorld().dropItemNaturally(at, result);
            }
        }, ticks);
    }
}
