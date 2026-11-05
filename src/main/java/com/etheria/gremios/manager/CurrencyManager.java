package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;

/** La moneda del servidor es el objeto que el admin elige con /gremioadmin moneda set. */
public class CurrencyManager {
    private final GremiosPlugin plugin;
    private final File file;
    private ItemStack currency;

    public CurrencyManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "moneda.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ItemStack it = y.getItemStack("item");
        if (it != null) {
            it.setAmount(1);
            currency = it;
        }
    }

    public boolean isSet() {
        return currency != null;
    }

    public void set(ItemStack held) {
        ItemStack c = held.clone();
        c.setAmount(1);
        currency = c;
        YamlConfiguration y = new YamlConfiguration();
        y.set("item", c);
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar moneda.yml: " + e.getMessage());
        }
    }

    public ItemStack template(int amount) {
        ItemStack c = currency.clone();
        c.setAmount(Math.max(1, Math.min(c.getMaxStackSize(), amount)));
        return c;
    }

    public String name() {
        if (currency == null) return "monedas";
        if (currency.hasItemMeta() && currency.getItemMeta().hasDisplayName()) {
            return currency.getItemMeta().getDisplayName();
        }
        String n = currency.getType().name().toLowerCase().replace('_', ' ');
        return n;
    }

    public Material material() {
        return currency == null ? Material.GOLD_INGOT : currency.getType();
    }

    public boolean isCurrency(ItemStack it) {
        return currency != null && it != null && !it.getType().isAir() && it.isSimilar(currency);
    }

    public long count(Player p) {
        if (currency == null) return 0;
        long n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (isCurrency(it)) n += it.getAmount();
        }
        return n;
    }

    /** Quita la cantidad indicada. Devuelve false (sin quitar nada) si no alcanza. */
    public boolean take(Player p, long amount) {
        if (currency == null || amount <= 0) return false;
        if (count(p) < amount) return false;
        PlayerInventory inv = p.getInventory();
        long left = amount;
        ItemStack[] cont = inv.getStorageContents();
        for (int i = 0; i < cont.length && left > 0; i++) {
            ItemStack it = cont[i];
            if (!isCurrency(it)) continue;
            int take = (int) Math.min(left, it.getAmount());
            if (take >= it.getAmount()) {
                cont[i] = null;
            } else {
                it.setAmount(it.getAmount() - take);
            }
            left -= take;
        }
        inv.setStorageContents(cont);
        return true;
    }

    /** Entrega monedas; lo que no cabe cae al suelo. */
    public void give(Player p, long amount) {
        if (currency == null || amount <= 0) return;
        int max = currency.getMaxStackSize();
        long left = amount;
        while (left > 0) {
            int n = (int) Math.min(max, left);
            HashMap<Integer, ItemStack> over = p.getInventory().addItem(template(n));
            for (ItemStack o : over.values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), o);
            }
            left -= n;
        }
    }

    public String fmt(long n) {
        return com.etheria.gremios.util.Msg.num(n) + " " + name();
    }
}
