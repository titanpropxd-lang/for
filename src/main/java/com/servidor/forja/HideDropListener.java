package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.ThreadLocalRandom;

/** Añade pieles RPG sin reemplazar ni alterar los drops vanilla de las criaturas. */
public final class HideDropListener implements Listener {
    private final ForjaPlugin plugin;
    public HideDropListener(ForjaPlugin plugin) { this.plugin = plugin; }

    @EventHandler(ignoreCancelled = true)
    public void onDeath(EntityDeathEvent event) {
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("curtiduria.drops");
        if (root == null) return;
        ConfigurationSection drop = root.getConfigurationSection(event.getEntity().getType().name());
        if (drop == null) return;
        String id = drop.getString("item", "");
        if (plugin.items.get(id) == null) return;
        double chance = Math.max(0, Math.min(1, drop.getDouble("probabilidad", 0.5)));
        if (ThreadLocalRandom.current().nextDouble() > chance) return;
        int min = Math.max(1, drop.getInt("cantidad_min", 1));
        int max = Math.max(min, drop.getInt("cantidad_max", min));
        int amount = ThreadLocalRandom.current().nextInt(min, max + 1);
        ItemStack item = plugin.items.basic(id, amount);
        event.getEntity().getWorld().dropItemNaturally(event.getEntity().getLocation(), item);
    }
}
