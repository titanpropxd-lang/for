package com.etheria.gremios.util;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Constructor fluido de items para los menús. */
public final class ItemBuilder {
    private final ItemStack item;
    private final ItemMeta meta;
    private final List<String> lore = new ArrayList<>();

    public ItemBuilder(Material m) {
        this(m, 1);
    }

    public ItemBuilder(Material m, int amount) {
        this.item = new ItemStack(m, Math.max(1, Math.min(64, amount)));
        this.meta = item.getItemMeta();
    }

    public ItemBuilder name(String s) {
        if (meta != null) meta.setDisplayName(Msg.c(s));
        return this;
    }

    public ItemBuilder lore(String... lines) {
        for (String l : lines) lore.add(Msg.c(l));
        return this;
    }

    public ItemBuilder lore(List<String> lines) {
        for (String l : lines) lore.add(Msg.c(l));
        return this;
    }

    public ItemBuilder cmd(int id) {
        if (meta != null && id > 0) meta.setCustomModelData(id);
        return this;
    }

    public ItemBuilder glow() {
        if (meta != null) {
            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        return this;
    }

    public ItemBuilder owner(OfflinePlayer p) {
        if (meta instanceof SkullMeta sm && p != null) sm.setOwningPlayer(p);
        return this;
    }

    public ItemBuilder tag(org.bukkit.NamespacedKey key, String value) {
        if (meta != null) meta.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.STRING, value);
        return this;
    }

    public ItemStack build() {
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            if (!lore.isEmpty()) meta.setLore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : Arrays.asList(text.split(" "))) {
            if (line.length() + w.length() + 1 > width && line.length() > 0) {
                out.add(line.toString());
                line = new StringBuilder();
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        return out;
    }
}
