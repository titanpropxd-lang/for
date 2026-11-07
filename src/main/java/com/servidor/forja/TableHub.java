package com.servidor.forja;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Menú que se abre al usar una mesa/estación vanilla: [usar la mesa normal] + [fabricar con sus recetas].
 * La mesa de crafteo ofrece tres opciones: normal · materias primas · objetos.
 */
public final class TableHub implements InventoryHolder {
    private static final Map<String, Material> ICONOS = Map.ofEntries(
            Map.entry("yunque", Material.ANVIL), Map.entry("forja", Material.BLAST_FURNACE), Map.entry("armeria", Material.LOOM),
            Map.entry("carpinteria", Material.FLETCHING_TABLE), Map.entry("joyeria", Material.SMITHING_TABLE),
            Map.entry("alquimia", Material.BREWING_STAND), Map.entry("cocina", Material.SMOKER),
            Map.entry("cartografia", Material.CARTOGRAPHY_TABLE), Map.entry("canteria", Material.STONECUTTER),
            Map.entry("afilado", Material.GRINDSTONE), Map.entry("compostera", Material.COMPOSTER));

    private ForjaPlugin plugin;
    private Inventory inv;
    private final Map<Integer, Runnable> actions = new HashMap<>();

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public static void open(ForjaPlugin plugin, Player p, Location table, MesaDef def) {
        if (!plugin.professions.ready(p)) {
            plugin.lang.send(p, "general.cargando");
            return;
        }
        TableHub h = new TableHub();
        h.plugin = plugin;
        String key = def.crafting() ? "mesa" : def.estacion();
        Component fallback = plugin.lang.get("gui.mesa.titulo");
        h.inv = Bukkit.createInventory(h, 27, plugin.guiStyle.title("hub:" + key, fallback));
        if (!plugin.guiStyle.custom()) for (int i = 0; i < 27; i++) h.inv.setItem(i, Gui.pane(Material.BLACK_STAINED_GLASS_PANE));
        final Location center = table.clone().add(0.5, 0.5, 0.5);
        final Runnable back = () -> open(plugin, p, table, def);
        if (def.crafting()) {
            h.add(11, Gui.icon(Material.CRAFTING_TABLE, plugin.lang.raw("gui.mesa.normal"),
                    "<gray>Crafteos de siempre (3x3).", "", "<yellow>Clic para abrir"), () -> openVanilla(p, table, "workbench"));
            h.add(13, Gui.icon(Material.IRON_INGOT, plugin.lang.raw("gui.mesa.materias"),
                    "<gray>Lingotes, placas, cuero, hilo, seda,", "<gray>madera noble, gemas, moldes...", "",
                    "<gray>Recetas disponibles: <white>" + available(plugin, p, ForgeMenu.virtualRecipes(plugin, true)), "", "<yellow>Clic para abrir"),
                    () -> ForgeMenu.open(plugin, p, "mesa_materiales", center, back));
            h.add(15, Gui.icon(Material.IRON_CHESTPLATE, plugin.lang.raw("gui.mesa.objetos"),
                    "<gray>Armas, armaduras, mochilas, vendas,", "<gray>elixires, comida, joyas, herramientas...", "",
                    "<gray>Recetas disponibles: <white>" + available(plugin, p, ForgeMenu.virtualRecipes(plugin, false)), "", "<yellow>Clic para abrir"),
                    () -> ForgeMenu.open(plugin, p, "mesa_objetos", center, back));
        } else {
            Material blockIcon = Material.matchMaterial(def.block());
            if (!def.vanilla().isBlank()) {
                h.add(11, Gui.icon(blockIcon == null ? Material.CRAFTING_TABLE : blockIcon,
                        plugin.lang.raw("gui.mesa.usar_normal", "mesa", plugin.lang.raw("mesa_nombre." + def.block())),
                        "<gray>La función de siempre.", "", "<yellow>Clic para abrir"), () -> openVanilla(p, table, def.vanilla()));
            }
            h.add(15, Gui.icon(ICONOS.getOrDefault(def.estacion(), Material.ANVIL),
                    plugin.lang.raw("gui.mesa.fabricar", "estacion", plugin.lang.raw("estacion." + def.estacion())),
                    "<gray>Recetas disponibles: <white>" + available(plugin, p, plugin.recipes.forStation(def.estacion())), "",
                    "<yellow>Clic para abrir"), () -> ForgeMenu.open(plugin, p, def.estacion(), center, back));
        }
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    private void add(int slot, ItemStack icon, Runnable action) {
        inv.setItem(slot, icon);
        actions.put(slot, action);
    }

    private static String available(ForjaPlugin plugin, Player p, List<Recipe> all) {
        RaceService.Info ri = plugin.races.get(p);
        int ok = 0;
        for (Recipe r : all) {
            if (plugin.professions.unlocked(p, r) && plugin.races.eligibility(ri, r) != RaceService.Elig.DENIED) ok++;
        }
        return ok + "/" + all.size();
    }

    /** Abre la interfaz vanilla de una mesa (la fuerza aunque el bloque ya no sea exactamente ese). */
    static void openVanilla(Player p, Location loc, String kind) {
        switch (kind) {
            case "workbench" -> p.openWorkbench(loc, true);
            case "anvil" -> p.openAnvil(loc, true);
            case "loom" -> p.openLoom(loc, true);
            case "cartography" -> p.openCartographyTable(loc, true);
            case "grindstone" -> p.openGrindstone(loc, true);
            case "smithing" -> p.openSmithingTable(loc, true);
            case "stonecutter" -> p.openStonecutter(loc, true);
            case "contenedor" -> {
                BlockState st = loc.getBlock().getState();
                if (st instanceof Container c) p.openInventory(c.getInventory());
            }
            default -> { }
        }
    }

    public void click(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv || !(e.getWhoClicked() instanceof Player p)) return;
        Runnable r = actions.get(e.getRawSlot());
        if (r != null) Bukkit.getScheduler().runTask(plugin, () -> plugin.safe(p, "menú de mesa", r));
    }
}
