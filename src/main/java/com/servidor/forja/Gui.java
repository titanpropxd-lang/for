package com.servidor.forja;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Utilidades de construcción de iconos de GUI. */
public final class Gui {
    private Gui() {}

    public static ItemStack icon(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Lang.line(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(Lang.line(s));
            meta.lore(l);
        }
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
        it.setItemMeta(meta);
        return it;
    }

    public static ItemStack icon(Material m, String name, List<String> lore) {
        return icon(m, name, lore.toArray(new String[0]));
    }

    public static ItemStack glow(ItemStack it) {
        ItemMeta m = it.getItemMeta();
        m.setEnchantmentGlintOverride(true);
        it.setItemMeta(m);
        return it;
    }

    public static ItemStack pane(Material m) {
        return icon(m, " ");
    }

    /** Añade líneas al final del lore de un item (copia). */
    public static ItemStack withLore(ItemStack base, List<String> extra) {
        ItemStack it = base.clone();
        ItemMeta m = it.getItemMeta();
        List<Component> l = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        for (String s : extra) l.add(Lang.line(s));
        m.lore(l);
        it.setItemMeta(m);
        return it;
    }

    public static String bar(int value, int max, int len) {
        int f = max <= 0 ? 0 : (int) Math.round(len * (value / (double) max));
        f = Math.max(0, Math.min(len, f));
        return "<green>" + "█".repeat(f) + "<dark_gray>" + "█".repeat(len - f);
    }
}
