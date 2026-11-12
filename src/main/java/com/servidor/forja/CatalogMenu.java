package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Catálogo de administración: /armas [filtro]. Muestra TODOS los objetos de Forja de la categoría elegida, con su skin real,
 * paginados. Clic izquierdo = 1 unidad, clic derecho = 8, Shift+clic = 64 (las armas y armaduras RPG se entregan de una en una
 * mientras haya hueco libre). Necesita el permiso forja.admin.
 */
public final class CatalogMenu implements InventoryHolder {
    static final List<String> FILTERS = List.of("armas", "armaduras", "herramientas", "joyas", "materiales", "consumibles", "estaciones", "todo");
    private static final int PAGE = 45;

    private final ForjaPlugin plugin;
    private final Player player;
    private final String filter;
    private final List<CustomItem> items;
    private final Inventory inventory;
    private int page;

    private CatalogMenu(ForjaPlugin plugin, Player player, String filter) {
        this.plugin = plugin;
        this.player = player;
        this.filter = filter;
        this.items = plugin.items.all().stream().filter(ci -> matches(filter, ci))
                .sorted(Comparator.comparing((CustomItem ci) -> ci.categoria).thenComparing(ci -> ci.nombre))
                .collect(Collectors.toList());
        this.inventory = Bukkit.createInventory(this, 54, Lang.mm("<dark_gray>✦ <gold>Catálogo: " + filter + " <gray>(" + items.size() + ") <dark_gray>✦"));
    }

    private static boolean matches(String filter, CustomItem ci) {
        String c = ci.categoria;
        return switch (filter) {
            case "armas" -> c.equals("arma") || c.equals("rpg_arma");
            case "armaduras" -> c.equals("armadura") || c.equals("rpg_armadura");
            case "herramientas" -> c.equals("herramienta");
            case "joyas" -> c.equals("joya");
            case "materiales" -> c.equals("material");
            case "consumibles" -> c.equals("consumible");
            case "estaciones" -> c.equals("estacion");
            default -> true;
        };
    }

    public static void open(ForjaPlugin plugin, Player p, String rawFilter) {
        if (!p.hasPermission("forja.admin")) {
            plugin.lang.send(p, "general.sin_permiso");
            return;
        }
        String f = rawFilter == null ? "armas" : rawFilter.toLowerCase(Locale.ROOT);
        if (!FILTERS.contains(f)) {
            p.sendMessage(Lang.mm("<yellow>Filtros: <white>" + String.join(", ", FILTERS)));
            return;
        }
        CatalogMenu m = new CatalogMenu(plugin, p, f);
        m.render();
        p.openInventory(m.inventory);
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    private void render() {
        inventory.clear();
        int pages = Math.max(1, (items.size() + PAGE - 1) / PAGE);
        page = Math.max(0, Math.min(page, pages - 1));
        int start = page * PAGE;
        for (int i = 0; i < PAGE && start + i < items.size(); i++) {
            CustomItem ci = items.get(start + i);
            inventory.setItem(i, Gui.iconOf(ci, Material.PAPER, "<white>" + ci.nombre, "<gray>ID: <white>" + ci.id,
                    "<gray>Categoría: <white>" + ci.categoria, "",
                    "<yellow>Clic: 1 <gray>· <yellow>Clic derecho: 8 <gray>· <yellow>Shift+clic: 64"));
        }
        for (int i = 45; i < 54; i++) inventory.setItem(i, Gui.pane(Material.BLACK_STAINED_GLASS_PANE));
        if (page > 0) inventory.setItem(45, Gui.icon(Material.ARROW, "<yellow>Página anterior"));
        inventory.setItem(49, Gui.icon(Material.BOOK, "<gold>Catálogo: " + filter,
                "<gray>Página " + (page + 1) + " / " + pages, "<gray>Objetos: <white>" + items.size(), "<gray>Otros filtros: /armas <white>" + String.join(", ", FILTERS)));
        if (page < pages - 1) inventory.setItem(53, Gui.icon(Material.ARROW, "<yellow>Página siguiente"));
    }

    private void click(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory().getHolder() != this) return;
        int slot = e.getRawSlot();
        if (slot == 45 && page > 0) { page--; render(); return; }
        if (slot == 53) { page++; render(); return; }
        if (slot < 0 || slot >= PAGE) return;
        int index = page * PAGE + slot;
        if (index >= items.size()) return;
        CustomItem ci = items.get(index);
        int n = e.getClick() == ClickType.RIGHT || e.getClick() == ClickType.SHIFT_RIGHT ? 8 : 1;
        if (e.isShiftClick()) n = 64;
        int given = ForjaCommand.giveItem(plugin, player, ci, n);
        player.sendMessage(Lang.mm("<gray>Recibes <white>" + given + "× <gold>" + Lang.esc(ci.nombre) + " <dark_gray>(" + ci.id + ")"));
    }

    /** Listener único (registrado una vez en ForjaPlugin) para todos los catálogos abiertos. */
    public static final class Events implements Listener {
        @EventHandler
        public void onClick(InventoryClickEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof CatalogMenu m) {
                if (e.getWhoClicked() != m.player) { e.setCancelled(true); return; }
                m.click(e);
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent e) {
            if (e.getView().getTopInventory().getHolder() instanceof CatalogMenu) e.setCancelled(true);
        }
    }
}
