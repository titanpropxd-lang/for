package com.etheria.gremios.gui;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.SiegeItems;
import com.etheria.gremios.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Menú de inventario con arte del resourcepack. Se redibuja entero en cada refresh(). */
public class Menu implements InventoryHolder {
    public final GremiosPlugin plugin;
    public final Player player;
    public final Layouts.Layout layout;
    public int page;

    private Inventory inv;
    private final Map<Integer, Consumer<InventoryClickEvent>> actions = new HashMap<>();
    private final Consumer<Menu> renderer;

    public Menu(GremiosPlugin plugin, Player player, String layoutId, Consumer<Menu> renderer) {
        this.plugin = plugin;
        this.player = player;
        this.layout = plugin.layouts.get(layoutId);
        this.renderer = renderer;
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    public void open() {
        inv = Bukkit.createInventory(this, layout.rows * 9, plugin.layouts.title(layout, plugin.usePack()));
        refresh();
        player.openInventory(inv);
    }

    public Menu at(int page) {
        this.page = page;
        return this;
    }

    public void refresh() {
        inv.clear();
        actions.clear();
        renderer.accept(this);
    }

    public Consumer<InventoryClickEvent> action(int slot) {
        return actions.get(slot);
    }

    public void set(int slot, ItemStack it, Consumer<InventoryClickEvent> act) {
        if (slot < 0 || slot >= inv.getSize()) return;
        inv.setItem(slot, it);
        if (act != null) actions.put(slot, act);
    }

    public void cell(int x, int y, ItemStack it, Consumer<InventoryClickEvent> act) {
        set(y * 9 + x, it, act);
    }

    /** Rellena todos los huecos de un botón (placa) del layout. */
    public void button(String id, String name, List<String> lore, Consumer<InventoryClickEvent> act) {
        Layouts.Plaque pq = layout.plaques.get(id);
        if (pq == null) return;
        boolean pack = plugin.usePack();
        for (int slot : pq.slots) {
            ItemStack it;
            if (pack) {
                it = new ItemBuilder(SiegeItems.BASE).name(name).lore(lore).cmd(SiegeItems.CMD_BLANK).build();
            } else {
                it = new ItemBuilder(pq.mat).name(name).lore(lore).build();
            }
            set(slot, it, act);
        }
    }

    public void button(String id, String name, Consumer<InventoryClickEvent> act) {
        button(id, name, new ArrayList<>(), act);
    }

    /** Lista paginada en las casillas "grid" del layout. */
    public <T> void paged(List<T> data, Function<T, ItemStack> icon, BiConsumer<T, InventoryClickEvent> click) {
        List<Integer> g = layout.grid;
        int per = Math.max(1, g.size());
        int pages = Math.max(1, (data.size() + per - 1) / per);
        if (page >= pages) page = pages - 1;
        if (page < 0) page = 0;
        for (int i = 0; i < per; i++) {
            int idx = page * per + i;
            if (idx >= data.size()) break;
            final T d = data.get(idx);
            set(g.get(i), icon.apply(d), click == null ? null : e -> click.accept(d, e));
        }
        if (layout.plaques.containsKey("prev")) {
            Consumer<InventoryClickEvent> prevAct = null;
            Consumer<InventoryClickEvent> nextAct = null;
            if (page > 0) {
                prevAct = e -> {
                    page--;
                    refresh();
                };
            }
            if (page < pages - 1) {
                nextAct = e -> {
                    page++;
                    refresh();
                };
            }
            button("prev", "§7◀ Anterior", new ArrayList<>(), prevAct);
            button("next", "§7Siguiente ▶", new ArrayList<>(), nextAct);
            set(4 + 5 * 9, new ItemBuilder(SiegeItems.BASE).name("§7Página §f" + (page + 1) + "§7/§f" + pages).cmd(SiegeItems.CMD_BLANK).build(), null);
        }
    }
}
