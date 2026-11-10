package com.servidor.forja;

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
 * Menú que se abre al usar una mesa/estación vanilla: hasta tres CUADROS GRANDES (3x3 casillas cada uno) que se pintan en el
 * fondo del menú: O = objetos custom · M = materiales · F = fundición (todo son materiales) · V = la mesa normal (vanilla).
 * La mesa de crafteo es "OMV": custom, materiales y mesa normal. Cualquier casilla del cuadro es clicable.
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
        String layout = plugin.guiStyle.layout("hub:" + key, def.crafting() ? "OMV" : (def.vanilla().isBlank() ? "O" : "OV"));
        boolean custom = plugin.guiStyle.custom();
        h.inv = Bukkit.createInventory(h, 27, plugin.guiStyle.title("hub:" + key, plugin.lang.get("gui.mesa.titulo")));
        if (!custom) for (int i = 0; i < 27; i++) h.inv.setItem(i, Gui.pane(Material.BLACK_STAINED_GLASS_PANE));
        final Location center = table.clone().add(0.5, 0.5, 0.5);
        final Runnable back = () -> open(plugin, p, table, def);
        int n = Math.min(3, layout.length());
        int[] cols = n >= 3 ? new int[]{0, 3, 6} : (n == 2 ? new int[]{1, 5} : new int[]{3});
        for (int i = 0; i < n; i++) {
            char c = layout.charAt(i);
            Runnable action = actionFor(plugin, p, table, def, c, center, back);
            ItemStack tip = tooltip(plugin, p, def, c, custom);
            for (int r = 0; r < 3; r++) {
                for (int k = 0; k < 3; k++) {
                    int slot = r * 9 + cols[i] + k;
                    h.actions.put(slot, action);                      // todo el cuadro es clicable
                    if (custom) h.inv.setItem(slot, tip.clone());     // tooltip en cada casilla del cuadro (el dibujo va en el fondo)
                }
            }
            if (!custom) h.inv.setItem(9 + cols[i] + 1, tip);         // sin resourcepack: icono normal en el centro del cuadro
        }
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    private static Runnable actionFor(ForjaPlugin plugin, Player p, Location table, MesaDef def, char c, Location center, Runnable back) {
        return switch (c) {
            case 'V' -> () -> openVanilla(p, table, def.crafting() ? "workbench" : def.vanilla());
            case 'O' -> def.crafting()
                    ? () -> ForgeMenu.open(plugin, p, "mesa_objetos", center, back)
                    : () -> ForgeMenu.open(plugin, p, def.estacion(), center, back, "objetos");
            case 'M' -> def.crafting()
                    ? () -> ForgeMenu.open(plugin, p, "mesa_materiales", center, back)
                    : () -> ForgeMenu.open(plugin, p, def.estacion(), center, back, "materiales");
            default -> () -> ForgeMenu.open(plugin, p, def.estacion(), center, back, null);
        };
    }

    private static List<Recipe> recipesFor(ForjaPlugin plugin, MesaDef def, char c) {
        if (def.crafting()) return ForgeMenu.virtualRecipes(plugin, c == 'M');
        String group = c == 'O' ? "objetos" : (c == 'M' ? "materiales" : null);
        return ForgeMenu.stationRecipes(plugin, def.estacion(), group);
    }

    private static String available(ForjaPlugin plugin, Player p, List<Recipe> all) {
        RaceService.Info ri = plugin.races.get(p);
        int ok = 0;
        for (Recipe r : all) {
            if (plugin.professions.unlocked(p, r) && plugin.races.eligibility(ri, r) != RaceService.Elig.DENIED) ok++;
        }
        return ok + "/" + all.size();
    }

    private static ItemStack tooltip(ForjaPlugin plugin, Player p, MesaDef def, char c, boolean custom) {
        String name;
        String[] lore;
        Material icon;
        String open = "<yellow>Clic para abrir";
        String stationName = def.crafting() ? "" : plugin.lang.raw("estacion." + def.estacion());
        if (c == 'V') {
            Material blockIcon = Material.matchMaterial(def.block());
            icon = blockIcon == null ? Material.CRAFTING_TABLE : blockIcon;
            name = plugin.lang.raw("gui.mesa.usar_normal", "mesa", plugin.lang.raw("mesa_nombre." + def.block()));
            lore = new String[]{"<gray>La función de siempre.", "", open};
        } else {
            String count = "<gray>Recetas disponibles: <white>" + available(plugin, p, recipesFor(plugin, def, c));
            if (c == 'O') {
                icon = def.crafting() ? Material.IRON_CHESTPLATE : ICONOS.getOrDefault(def.estacion(), Material.ANVIL);
                name = plugin.lang.raw("gui.mesa.caja_O");
                lore = def.crafting()
                        ? new String[]{"<gray>Todo lo custom: armas, armaduras,", "<gray>mochilas, vendas, elixires, joyas...", "", count, "", open}
                        : new String[]{"<gray>Objetos que se fabrican en", "<gray>" + stationName + ".", "", count, "", open};
            } else if (c == 'M') {
                icon = Material.IRON_INGOT;
                name = plugin.lang.raw("gui.mesa.caja_M");
                lore = new String[]{"<gray>Lingotes, placas, cuero, hilo, seda,", "<gray>madera noble, gemas, moldes...", "", count, "", open};
            } else {
                icon = ICONOS.getOrDefault(def.estacion(), Material.BLAST_FURNACE);
                name = plugin.lang.raw("gui.mesa.caja_F");
                lore = new String[]{"<gray>Fundición de lingotes y placas.", "", count, "", open};
            }
        }
        return custom ? Gui.invisible(name, lore) : Gui.icon(icon, name, lore);
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
