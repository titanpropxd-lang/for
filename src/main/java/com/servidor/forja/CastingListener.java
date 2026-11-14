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
        if (plugin.visuals != null) plugin.visuals.stationEffect(at, "fuego");
        plugin.lang.send(player, "fundicion.vertido");
        // Efecto de colada y enfriamiento. Solo partículas: no altera el bloque ni el terreno.
        final int[] elapsed = {0};
        org.bukkit.scheduler.BukkitTask visual = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (at.getWorld() == null) return;
            elapsed[0] += 4;
            at.getWorld().spawnParticle(org.bukkit.Particle.LAVA, at.clone().add(0, 0.9, 0), 2, .18, .12, .18, 0);
            at.getWorld().spawnParticle(org.bukkit.Particle.SMOKE, at.clone().add(0, 1.0, 0), 4, .2, .15, .2, .01);
            if (elapsed[0] > ticks / 2) at.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, at.clone().add(0, .8, 0), 3, .18, .1, .18, .01);
            if (elapsed[0] >= ticks) { /* task is cancelled by the completion task below */ }
        }, 0L, 4L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            visual.cancel();
            active.remove(player.getUniqueId());
            ItemStack result = plugin.items.basic(output, 1);
            if (player.isOnline()) {
                plugin.forge.give(player, result);
                // Clima de la forja: con tormenta hay una probabilidad de que el rayo "temple" la pieza y salga una extra.
                org.bukkit.World cw = player.getWorld();
                if (plugin.getConfig().getBoolean("clima.activo", true) && cw.isThundering()
                        && java.util.concurrent.ThreadLocalRandom.current().nextDouble() < plugin.getConfig().getDouble("clima.tormenta_prob_especial", 0.10)) {
                    plugin.forge.give(player, plugin.items.basic(output, 1));
                    cw.spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, player.getLocation().add(0, 1.2, 0), 40, .4, .5, .4, .1);
                    player.playSound(player.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.6f, 1.6f);
                    plugin.lang.send(player, "fundicion.rayo");
                }
                player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                plugin.lang.send(player, "fundicion.enfriado", "pieza", plugin.items.get(output).nombre);
            } else if (at.getWorld() != null) {
                at.getWorld().dropItemNaturally(at, result);
            }
        }, ticks);
    }
}
