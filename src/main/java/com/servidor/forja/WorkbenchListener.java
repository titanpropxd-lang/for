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

/** Clic derecho en cualquier bloque reconocido de mesas.bloques o estación registrada -> hub de Forja. */
public final class WorkbenchListener implements Listener {
    private final ForjaPlugin plugin;
    private final Map<Material, MesaDef> defs = new EnumMap<>(Material.class);

    public WorkbenchListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    private static final String[][] DEFAULTS = {{"CRAFTING_TABLE", "mesa", "", "workbench"}, {"ANVIL", "", "yunque", "anvil"},
            {"CHIPPED_ANVIL", "", "yunque", "anvil"}, {"DAMAGED_ANVIL", "", "yunque", "anvil"}, {"BLAST_FURNACE", "", "forja", "contenedor"},
            {"FURNACE", "", "forja", "contenedor"}, {"SMOKER", "", "cocina", "contenedor"}, {"LOOM", "", "armeria", "loom"},
            {"FLETCHING_TABLE", "", "carpinteria", ""}, {"SMITHING_TABLE", "", "joyeria", "smithing"}, {"BREWING_STAND", "", "alquimia", "contenedor"},
            {"CARTOGRAPHY_TABLE", "", "cartografia", "cartography"}, {"STONECUTTER", "", "canteria", "stonecutter"},
            {"GRINDSTONE", "", "afilado", "grindstone"}, {"COMPOSTER", "", "compostera", ""}};

    public int count() {
        return defs.size();
    }

    /** Texto para /forja debug: qué menú abriría este bloque. */
    public String describe(Material m) {
        MesaDef d = defs.get(m);
        if (d == null) return "ninguno (no está en mesas.bloques o está desactivado)";
        return (d.crafting() ? "mesa de crafteo" : d.estacion()) + " · diseño " + plugin.guiStyle.layout("hub:" + (d.crafting() ? "mesa" : d.estacion()), "?");
    }

    public void load() {
        defs.clear();
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("mesas.bloques");
        if (s != null) loadFrom(s);
        if (defs.isEmpty()) {   // config antigua sin la sección (Bukkit devuelve una sección vacía): se usan las mesas por defecto
            plugin.getLogger().warning("mesas.bloques no está en tu config.yml (o está vacío): se usan las mesas por defecto.");
            for (String[] d : DEFAULTS) defs.put(Material.matchMaterial(d[0]), new MesaDef(d[0], d[1], d[2], d[3]));
        }
    }

    private void loadFrom(ConfigurationSection s) {
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

    /** Función vanilla asociada a una estación colocable de Forja. */
    private static String vanillaFor(String station) {
        return switch (station == null ? "" : station.toLowerCase(java.util.Locale.ROOT)) {
            case "yunque" -> "anvil";
            case "forja" -> "contenedor";
            case "armeria" -> "loom";
            case "joyeria" -> "smithing";
            case "alquimia" -> "contenedor";
            case "cocina" -> "contenedor";
            case "cartografia" -> "cartography";
            case "canteria" -> "stonecutter";
            case "afilado" -> "grindstone";
            default -> "";
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        // Las estaciones colocables de Forja tienen prioridad sobre las mesas vanilla;
        // solo se interceptan bloques guardados en StationManager, nunca barriles normales.
        String registered = plugin.stations.typeAt(b);
        if (registered != null) {
            CustomItem stationItem = plugin.items.get("estacion_" + registered);
            if (stationItem == null || b.getType() != stationItem.base) {
                plugin.stations.remove(b);
                return;
            }
            Player p = e.getPlayer();
            if (!p.hasPermission("forja.mesa")) return;
            if (p.isSneaking() && plugin.getConfig().getBoolean("mesas.atajo_agachado", true)) return;
            e.setUseInteractedBlock(Event.Result.DENY);
            e.setUseItemInHand(Event.Result.DENY);
            e.setCancelled(true);
            MesaDef stationDef = new MesaDef(b.getType().name(), "", registered, vanillaFor(registered));
            plugin.safe(p, "estación registrada " + registered, () -> TableHub.open(plugin, p, b.getLocation(), stationDef));
            return;
        }
        MesaDef def = defs.get(b.getType());
        if (def == null) return;
        // Si una configuración antigua contiene una estación sin identificador, no bloqueamos
        // el bloque vanilla: dejamos que Minecraft abra su interfaz habitual.
        if (!def.crafting() && (def.estacion() == null || def.estacion().isBlank())) return;
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
            TableHub.open(plugin, p, loc, def);
        });
    }
}
