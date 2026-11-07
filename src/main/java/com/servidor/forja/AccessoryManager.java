package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.*;

/** Slots de accesorios (/accesorios): 2 anillos, 1 amuleto, 1 broche. Aplican atributos y efectos pasivos. */
public final class AccessoryManager {
    public static final String[] SLOT_TYPES = {"anillo", "anillo", "amuleto", "broche"};
    private static final Attribute[] ATTRS = {Attribute.GENERIC_MAX_HEALTH, Attribute.GENERIC_ATTACK_DAMAGE,
            Attribute.GENERIC_MOVEMENT_SPEED, Attribute.GENERIC_LUCK, Attribute.GENERIC_ARMOR_TOUGHNESS,
            Attribute.GENERIC_ARMOR, Attribute.GENERIC_ATTACK_SPEED};

    public static final class Holder implements InventoryHolder {
        Inventory inv;
        UUID owner;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final ForjaPlugin plugin;
    private final Map<UUID, Set<String>> flags = new HashMap<>();

    public AccessoryManager(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void startTask() {
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Set<String> f = flags.get(p.getUniqueId());
                if (f == null || f.isEmpty()) continue;
                if (f.contains("vision_nocturna")) p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 260, 0, true, false, false));
                if (f.contains("respirar_agua")) p.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 260, 0, true, false, false));
            }
        }, 40L, 100L);
    }

    public void open(Player p) {
        if (!p.hasPermission("forja.accesorios")) {
            plugin.lang.send(p, "general.sin_permiso");
            return;
        }
        if (!plugin.professions.ready(p)) {
            plugin.lang.send(p, "general.cargando");
            return;
        }
        Holder h = new Holder();
        h.owner = p.getUniqueId();
        h.inv = Bukkit.createInventory(h, InventoryType.HOPPER, plugin.lang.get("gui.accesorios_titulo"));
        ItemStack[] acc = plugin.professions.get(p).accessories;
        for (int i = 0; i < 4; i++) h.inv.setItem(i, acc[i]);
        h.inv.setItem(4, Gui.icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>Anillo · Anillo · Amuleto · Broche"));
        p.openInventory(h.inv);
    }

    private boolean accepts(int slot, ItemStack it, ItemStack[] acc) {
        CustomItem ci = plugin.items.of(it);
        if (ci == null || !"joya".equals(ci.categoria) || ci.slot == null || !ci.slot.equals(SLOT_TYPES[slot])) return false;
        for (int i = 0; i < 4; i++) {
            if (i != slot && acc[i] != null && ci.id.equals(plugin.items.idOf(acc[i]))) return false;
        }
        return true;
    }

    public void click(InventoryClickEvent e, Holder h) {
        e.setCancelled(true);
        Player p = (Player) e.getWhoClicked();
        Inventory top = h.inv;
        ItemStack[] acc = plugin.professions.get(p).accessories;
        int raw = e.getRawSlot();
        boolean changed = false;
        if (e.getClickedInventory() == top) {
            if (raw < 0 || raw > 3) return;
            ItemStack cur = p.getItemOnCursor();
            ItemStack in = top.getItem(raw);
            boolean curEmpty = cur == null || cur.getType().isAir();
            if (curEmpty) {
                if (in != null && !in.getType().isAir()) {
                    p.setItemOnCursor(in);
                    top.setItem(raw, null);
                    changed = true;
                }
            } else {
                if (!accepts(raw, cur, acc)) {
                    plugin.lang.send(p, "accesorios.no_cabe");
                    return;
                }
                if (in == null || in.getType().isAir()) {
                    ItemStack one = cur.clone();
                    one.setAmount(1);
                    top.setItem(raw, one);
                    cur = cur.clone();
                    cur.setAmount(cur.getAmount() - 1);
                    p.setItemOnCursor(cur.getAmount() <= 0 ? null : cur);
                    changed = true;
                } else if (cur.getAmount() == 1) {
                    top.setItem(raw, cur.clone());
                    p.setItemOnCursor(in);
                    changed = true;
                }
            }
        } else if (e.isShiftClick() && e.getClickedInventory() != null) {
            ItemStack cur = e.getCurrentItem();
            if (cur == null || cur.getType().isAir()) return;
            for (int i = 0; i < 4; i++) {
                ItemStack in = top.getItem(i);
                if ((in == null || in.getType().isAir()) && accepts(i, cur, acc)) {
                    ItemStack one = cur.clone();
                    one.setAmount(1);
                    top.setItem(i, one);
                    if (cur.getAmount() <= 1) e.getClickedInventory().setItem(e.getSlot(), null);
                    else cur.setAmount(cur.getAmount() - 1);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) sync(p, top);
    }

    private void sync(Player p, Inventory top) {
        PlayerData pd = plugin.professions.get(p);
        for (int i = 0; i < 4; i++) {
            ItemStack it = top.getItem(i);
            pd.accessories[i] = (it == null || it.getType().isAir()) ? null : it.clone();
        }
        plugin.professions.saveAccessories(p.getUniqueId());
        apply(p);
        p.playSound(p.getLocation(), org.bukkit.Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1f);
    }

    public void closed(Player p, Holder h) {
        sync(p, h.inv);
    }

    // ------------------------------------------------------------------ efectos

    public Map<String, Double> totals(Player p) {
        Map<String, Double> t = new HashMap<>();
        for (ItemStack it : plugin.professions.get(p).accessories) {
            if (it == null) continue;
            for (Map.Entry<String, Double> e : plugin.items.passives(it).entrySet()) t.merge(e.getKey(), e.getValue(), Double::sum);
        }
        return t;
    }

    public void clear(Player p) {
        String ns = Keys.ITEM_ID.getNamespace();
        for (Attribute a : ATTRS) {
            AttributeInstance ai = p.getAttribute(a);
            if (ai == null) continue;
            for (AttributeModifier m : new ArrayList<>(ai.getModifiers())) {
                NamespacedKey k = m.getKey();
                if (k.getNamespace().equals(ns) && k.getKey().startsWith("acc_")) ai.removeModifier(m);
            }
        }
        flags.remove(p.getUniqueId());
    }

    public void apply(Player p) {
        clear(p);
        Map<String, Double> t = totals(p);
        Set<String> f = new HashSet<>();
        for (Map.Entry<String, Double> e : t.entrySet()) {
            double v = e.getValue();
            switch (e.getKey()) {
                case "max_vida" -> mod(p, Attribute.GENERIC_MAX_HEALTH, "acc_max_vida", v * 2.0, AttributeModifier.Operation.ADD_NUMBER);
                case "dano" -> mod(p, Attribute.GENERIC_ATTACK_DAMAGE, "acc_dano", v, AttributeModifier.Operation.ADD_NUMBER);
                case "velocidad_mov_pct" -> mod(p, Attribute.GENERIC_MOVEMENT_SPEED, "acc_vel", v / 100.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
                case "suerte" -> mod(p, Attribute.GENERIC_LUCK, "acc_suerte", v, AttributeModifier.Operation.ADD_NUMBER);
                case "dureza" -> mod(p, Attribute.GENERIC_ARMOR_TOUGHNESS, "acc_dureza", v, AttributeModifier.Operation.ADD_NUMBER);
                case "defensa" -> mod(p, Attribute.GENERIC_ARMOR, "acc_defensa", v, AttributeModifier.Operation.ADD_NUMBER);
                case "vel_ataque_pct" -> mod(p, Attribute.GENERIC_ATTACK_SPEED, "acc_vel_atk", v / 100.0, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
                case "vision_nocturna", "respirar_agua" -> f.add(e.getKey());
                default -> { }
            }
        }
        if (!f.isEmpty()) flags.put(p.getUniqueId(), f);
        AttributeInstance mh = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (mh != null && p.getHealth() > mh.getValue()) p.setHealth(mh.getValue());
    }

    private void mod(Player p, Attribute a, String key, double amount, AttributeModifier.Operation op) {
        AttributeInstance ai = p.getAttribute(a);
        if (ai == null || amount == 0) return;
        ai.addModifier(new AttributeModifier(new NamespacedKey(plugin, key), amount, op));
    }

    public double lifesteal(Player p) {
        return totals(p).getOrDefault("robo_vida_pct", 0.0);
    }

    /** Si caer_al_morir está activo, suelta los accesorios. */
    public void dropOnDeath(Player p) {
        PlayerData pd = plugin.professions.get(p);
        for (int i = 0; i < 4; i++) {
            if (pd.accessories[i] != null) {
                p.getWorld().dropItemNaturally(p.getLocation(), pd.accessories[i]);
                pd.accessories[i] = null;
            }
        }
        plugin.professions.saveAccessories(p.getUniqueId());
        apply(p);
    }
}
