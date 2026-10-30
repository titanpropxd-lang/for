package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.List;
import java.util.Map;

/** Consumibles, vendas, piedras de afilar/aceites, munición especial y robo de vida. */
public final class ItemUseListener implements Listener {
    private final ForjaPlugin plugin;

    public ItemUseListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    private static PotionEffectType effectType(String key) {
        return switch (key) {
            case "regeneracion" -> PotionEffectType.REGENERATION;
            case "fuerza" -> PotionEffectType.STRENGTH;
            case "velocidad" -> PotionEffectType.SPEED;
            case "vision_nocturna" -> PotionEffectType.NIGHT_VISION;
            case "absorcion" -> PotionEffectType.ABSORPTION;
            case "respirar_agua" -> PotionEffectType.WATER_BREATHING;
            case "resistencia" -> PotionEffectType.RESISTANCE;
            case "saturacion" -> PotionEffectType.SATURATION;
            case "gracia_delfin" -> PotionEffectType.DOLPHINS_GRACE;
            default -> null;
        };
    }

    private static PotionEffectType removable(String key) {
        return switch (key) {
            case "veneno" -> PotionEffectType.POISON;
            case "marchitar" -> PotionEffectType.WITHER;
            case "lentitud" -> PotionEffectType.SLOWNESS;
            default -> null;
        };
    }

    private void heal(Player p, double amount) {
        AttributeInstance mh = p.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        double max = mh == null ? 20 : mh.getValue();
        p.setHealth(Math.min(max, p.getHealth() + amount));
    }

    private void applyEffects(Player p, CustomItem ci) {
        for (Map.Entry<String, Object> e : ci.efectos.entrySet()) {
            String k = e.getKey();
            Object v = e.getValue();
            if (k.equals("curacion") && v instanceof Number n) {
                heal(p, n.doubleValue());
            } else if (k.equals("quitar") && v instanceof List<?> l) {
                for (Object o : l) {
                    PotionEffectType t = removable(String.valueOf(o));
                    if (t != null) p.removePotionEffect(t);
                }
            } else if (v instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number s && l.get(1) instanceof Number a) {
                PotionEffectType t = effectType(k);
                if (t != null) p.addPotionEffect(new PotionEffect(t, Math.max(1, s.intValue() * 20), a.intValue()));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent e) {
        CustomItem ci = plugin.items.of(e.getItem());
        if (ci == null || !ci.categoria.equals("consumible")) return;
        applyEffects(e.getPlayer(), ci);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack it = e.getItem();
        CustomItem ci = plugin.items.of(it);
        if (ci == null || !ci.categoria.equals("consumible")) return;
        Player p = e.getPlayer();
        if (ci.efectos.containsKey("duracion_usos")) {
            e.setCancelled(true);
            applyBuff(p, ci);
        } else if (ci.base == Material.PAPER && ci.efectos.containsKey("curacion")) {
            e.setCancelled(true);
            if (p.getCooldown(Material.PAPER) > 0) return;
            applyEffects(p, ci);
            it.setAmount(it.getAmount() - 1);
            p.setCooldown(Material.PAPER, 60);
            p.playSound(p.getLocation(), Sound.ITEM_ARMOR_EQUIP_LEATHER, 1f, 1f);
        } else if (ci.base == Material.GLASS_BOTTLE) {
            e.setCancelled(true);
        }
    }

    private void applyBuff(Player p, CustomItem ci) {
        ItemStack off = p.getInventory().getItemInOffHand();
        CustomItem w = plugin.items.of(off);
        if (w == null || !w.categoria.equals("arma") || !w.atributos.containsKey("dano")) {
            plugin.lang.send(p, "buff.necesita_arma");
            return;
        }
        String type;
        double val;
        if (ci.efectos.containsKey("dano_pct")) { type = "afilado"; val = CustomItem.dbl(ci.efectos, "dano_pct", 5); }
        else if (ci.efectos.containsKey("fuego_ticks")) { type = "fuego"; val = CustomItem.dbl(ci.efectos, "fuego_ticks", 80); }
        else if (ci.efectos.containsKey("veneno_seg")) { type = "veneno"; val = CustomItem.dbl(ci.efectos, "veneno_seg", 5); }
        else if (ci.efectos.containsKey("lentitud_seg")) { type = "escarcha"; val = CustomItem.dbl(ci.efectos, "lentitud_seg", 4); }
        else return;
        int uses = (int) CustomItem.dbl(ci.efectos, "duracion_usos", 20);
        ItemMeta m = off.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(Keys.BUFF_TYPE, PersistentDataType.STRING, type);
        pdc.set(Keys.BUFF_VAL, PersistentDataType.DOUBLE, val);
        pdc.set(Keys.BUFF_USES, PersistentDataType.INTEGER, uses);
        off.setItemMeta(m);
        p.getInventory().setItemInOffHand(plugin.items.rebuild(off, plugin.items.plusOf(off)));
        ItemStack hand = p.getInventory().getItemInMainHand();
        hand.setAmount(hand.getAmount() - 1);
        p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 0.8f, 1.4f);
        plugin.lang.send(p, "buff.aplicado");
    }

    @EventHandler(ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent e) {
        ItemStack cons = e.getConsumable();
        CustomItem ci = plugin.items.of(cons);
        if (ci != null && ci.base == Material.ARROW) {
            e.getProjectile().getPersistentDataContainer().set(Keys.ARROW, PersistentDataType.STRING, ci.id);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Projectile pr) {
            String aid = pr.getPersistentDataContainer().get(Keys.ARROW, PersistentDataType.STRING);
            if (aid != null) {
                double mult = plugin.getConfig().getDouble("flechas." + aid, 1.0);
                e.setDamage(e.getDamage() * mult);
            }
            return;
        }
        if (!(e.getDamager() instanceof Player p) || !(e.getEntity() instanceof LivingEntity victim)) return;
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (!plugin.items.isCustom(hand) || !hand.hasItemMeta()) return;
        PersistentDataContainer pdc = hand.getItemMeta().getPersistentDataContainer();
        String bt = pdc.get(Keys.BUFF_TYPE, PersistentDataType.STRING);
        Integer uses = pdc.get(Keys.BUFF_USES, PersistentDataType.INTEGER);
        Double val = pdc.get(Keys.BUFF_VAL, PersistentDataType.DOUBLE);
        if (bt == null || uses == null || uses <= 0 || val == null) return;
        switch (bt) {
            case "afilado" -> e.setDamage(e.getDamage() * (1.0 + val / 100.0));
            case "fuego" -> victim.setFireTicks(val.intValue());
            case "veneno" -> victim.addPotionEffect(new PotionEffect(PotionEffectType.POISON, val.intValue() * 20, 0));
            case "escarcha" -> victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, val.intValue() * 20, 1));
            default -> { }
        }
        ItemMeta m = hand.getItemMeta();
        m.getPersistentDataContainer().set(Keys.BUFF_USES, PersistentDataType.INTEGER, uses - 1);
        hand.setItemMeta(m);
        p.getInventory().setItemInMainHand(plugin.items.rebuild(hand, plugin.items.plusOf(hand)));
        if (uses - 1 <= 0) plugin.lang.send(p, "buff.agotado");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLifesteal(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        double pct = plugin.accessories.lifesteal(p);
        if (pct > 0) heal(p, e.getFinalDamage() * pct / 100.0);
    }
}
