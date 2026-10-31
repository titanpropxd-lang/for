package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.*;

/** /oficios: niveles, XP y próximos desbloqueos. */
public final class OficiosMenu implements InventoryHolder {
    private Inventory inv;

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public static void open(ForjaPlugin plugin, Player p) {
        if (!plugin.professions.ready(p)) {
            plugin.lang.send(p, "general.cargando");
            return;
        }
        OficiosMenu m = new OficiosMenu();
        m.inv = Bukkit.createInventory(m, 27, plugin.lang.get("gui.oficios_titulo"));
        for (int i = 0; i < 27; i++) m.inv.setItem(i, Gui.pane(Material.BLACK_STAINED_GLASS_PANE));
        PlayerData pd = plugin.professions.get(p);
        int[] slots = {10, 11, 12, 14, 15, 16};
        int k = 0;
        for (Profession pr : Profession.values()) {
            int lvl = pd.level(pr), xp = pd.xp(pr), max = plugin.professions.maxLevel(), need = plugin.professions.xpNeeded(lvl);
            List<String> lore = new ArrayList<>();
            lore.add("<gray>Nivel: <white>" + lvl + "/" + max);
            lore.add(lvl >= max ? "<gold>¡Nivel máximo!" : Gui.bar(xp, need, 20) + " <gray>" + xp + "/" + need);
            lore.add("");
            List<Recipe> next = new ArrayList<>();
            for (Recipe r : plugin.recipes.all()) if (r.prof == pr && r.nivel > lvl) next.add(r);
            next.sort(Comparator.comparingInt((Recipe r) -> r.nivel).thenComparing(r -> r.id));
            if (next.isEmpty()) {
                lore.add("<green>Todas las recetas desbloqueadas");
            } else {
                lore.add("<yellow>Próximos desbloqueos:");
                for (int i = 0; i < Math.min(4, next.size()); i++) {
                    CustomItem ci = plugin.items.get(next.get(i).resultado);
                    lore.add(" <gray>nv " + next.get(i).nivel + ": <white>" + (ci == null ? next.get(i).id : ci.nombre));
                }
            }
            List<Integer> mins = plugin.getConfig().getIntegerList("calidad.nivel_min");
            for (int i = 0; i < mins.size(); i++) {
                if (mins.get(i) > lvl) {
                    lore.add("<yellow>Calidad " + Quality.of(i).tag + Quality.of(i).display + " <yellow>a nivel " + mins.get(i));
                    break;
                }
            }
            m.inv.setItem(slots[k++], Gui.icon(pr.icon, pr.tag + pr.display, lore));
        }
        RaceService.Info ri = plugin.races.get(p);
        m.inv.setItem(22, Gui.icon(Material.BOOK, "<gold>Resumen",
                "<gray>Objetos forjados: <white>" + pd.total(),
                ri.has() ? "<gray>Raza: <white>" + plugin.races.name(ri.key()) + " <gray>nv " + ri.level() : "<red>Sin raza"));
        p.openInventory(m.inv);
    }
}
