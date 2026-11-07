package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

import java.util.EnumMap;
import java.util.Map;

/** Clic derecho en una mesa/estación vanilla (config: mesas.bloques) -> menú de Forja. */
public final class WorkbenchListener implements Listener {
    private final ForjaPlugin plugin;
    private final Map<Material, MesaDef> defs = new EnumMap<>(Material.class);

    public WorkbenchListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        defs.clear();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("mesas.bloques");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            Material m = Material.matchMaterial(key);
            ConfigurationSection d = s.getConfigurationSection(key);
            if (m == null || d == null) {
                plugin.getLogger().warning("mesas.bloques: bloque desconocido o mal definido: " + key);
                continue;
            }
            if (!d.getBoolean("activada", true)) continue;
            defs.put(m, new MesaDef(key.toUpperCase(), d.getString("tipo", ""), d.getString("estacion", ""), d.getString("vanilla", "")));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (!"todas".equalsIgnoreCase(plugin.getConfig().getString("mesas.modo", "todas"))) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        MesaDef def = defs.get(b.getType());
        if (def == null) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("forja.mesa")) return;
        if (p.isSneaking()) {
            // agachado: atajo a la función vanilla (o colocar bloques si llevas algo en la mano)
            if (plugin.getConfig().getBoolean("mesas.atajo_agachado", true)) return;
            if (!p.getInventory().getItemInMainHand().getType().isAir()) return;
        }
        e.setUseInteractedBlock(Event.Result.DENY);
        e.setUseItemInHand(Event.Result.DENY);
        e.setCancelled(true);
        final org.bukkit.Location loc = b.getLocation();
        plugin.safe(p, "mesa " + b.getType(), () -> {
            if (def.crafting() || !def.vanilla().isBlank()) TableHub.open(plugin, p, loc, def);
            else ForgeMenu.open(plugin, p, def.estacion(), loc.clone().add(0.5, 0.5, 0.5), null);
        });
    }
}
