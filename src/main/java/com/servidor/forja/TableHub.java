package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.List;

/** Menú que sustituye a la mesa de crafteo: mesa normal · materias primas · objetos. */
public final class TableHub implements InventoryHolder {
    private static final int NORMAL = 11, MATERIAS = 13, OBJETOS = 15;

    private ForjaPlugin plugin;
    private Inventory inv;
    private Location table;

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public static void open(ForjaPlugin plugin, Player p, Location table) {
        if (!plugin.professions.ready(p)) {
            plugin.lang.send(p, "general.cargando");
            return;
        }
        TableHub h = new TableHub();
        h.plugin = plugin;
        h.table = table != null ? table : p.getLocation();
        h.inv = Bukkit.createInventory(h, 27, plugin.lang.get("gui.mesa.titulo"));
        for (int i = 0; i < 27; i++) h.inv.setItem(i, Gui.pane(Material.BLACK_STAINED_GLASS_PANE));
        h.inv.setItem(NORMAL, Gui.icon(Material.CRAFTING_TABLE, plugin.lang.raw("gui.mesa.normal"),
                "<gray>Crafteos de siempre (3x3).", "", "<yellow>Clic para abrir"));
        h.inv.setItem(MATERIAS, Gui.icon(Material.IRON_INGOT, plugin.lang.raw("gui.mesa.materias"),
                "<gray>Lingotes, placas, cuero, hilo, seda,", "<gray>madera noble, gemas, moldes...", "",
                "<gray>Recetas disponibles: <white>" + available(plugin, p, true), "", "<yellow>Clic para abrir"));
        h.inv.setItem(OBJETOS, Gui.icon(Material.IRON_CHESTPLATE, plugin.lang.raw("gui.mesa.objetos"),
                "<gray>Armas, armaduras, vendas, elixires,", "<gray>comida, joyas, herramientas...", "",
                "<gray>Recetas disponibles: <white>" + available(plugin, p, false), "", "<yellow>Clic para abrir"));
        p.openInventory(h.inv);
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.6f, 1.2f);
    }

    private static String available(ForjaPlugin plugin, Player p, boolean raw) {
        List<Recipe> all = ForgeMenu.virtualRecipes(plugin, raw);
        RaceService.Info ri = plugin.races.get(p);
        int ok = 0;
        for (Recipe r : all) {
            if (plugin.professions.unlocked(p, r) && plugin.races.eligibility(ri, r) != RaceService.Elig.DENIED) ok++;
        }
        return ok + "/" + all.size();
    }

    public void click(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() != inv || !(e.getWhoClicked() instanceof Player p)) return;
        final Location t = table;
        switch (e.getRawSlot()) {
            case NORMAL -> Bukkit.getScheduler().runTask(plugin, () -> p.openWorkbench(t, true));
            case MATERIAS -> Bukkit.getScheduler().runTask(plugin,
                    () -> ForgeMenu.open(plugin, p, "mesa_materiales", t.clone().add(0.5, 0.5, 0.5)));
            case OBJETOS -> Bukkit.getScheduler().runTask(plugin,
                    () -> ForgeMenu.open(plugin, p, "mesa_objetos", t.clone().add(0.5, 0.5, 0.5)));
            default -> { }
        }
    }
}
