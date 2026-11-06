package com.servidor.forja;

import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Catálogo de items y construcción de ItemStack. */
public final class ItemRegistry {
    private final ForjaPlugin plugin;
    private final Map<String, CustomItem> items = new LinkedHashMap<>();
    private boolean warnedTrim = false;

    public ItemRegistry(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        items.clear();
        File dir = new File(plugin.getDataFolder(), "items");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            ConfigurationSection sec = YamlConfiguration.loadConfiguration(f).getConfigurationSection("items");
            if (sec == null) continue;
            for (String id : sec.getKeys(false)) {
                try {
                    items.put(id, CustomItem.parse(id, sec.getConfigurationSection(id)));
                } catch (Exception e) {
                    plugin.getLogger().warning("Item inválido " + id + " (" + f.getName() + "): " + e.getMessage());
                }
            }
        }
    }

    public CustomItem get(String id) {
        return items.get(id);
    }

    public Collection<CustomItem> all() {
        return items.values();
    }

    public String idOf(ItemStack it) {
        if (it == null || it.getType().isAir() || !it.hasItemMeta()) return null;
        return it.getItemMeta().getPersistentDataContainer().get(Keys.ITEM_ID, PersistentDataType.STRING);
    }

    public CustomItem of(ItemStack it) {
        String id = idOf(it);
        return id == null ? null : items.get(id);
    }

    public boolean isCustom(ItemStack it) {
        return idOf(it) != null;
    }

    // ------------------------------------------------------------------ helpers de formato

    public static String rarityTag(String r) {
        return switch (r) {
            case "poco_comun" -> "<green>";
            case "raro" -> "<aqua>";
            case "epico" -> "<light_purple>";
            case "legendario" -> "<gold>";
            default -> "<white>";
        };
    }

    public static String rarityName(String r) {
        return switch (r) {
            case "poco_comun" -> "Poco común";
            case "raro" -> "Raro";
            case "epico" -> "Épico";
            case "legendario" -> "Legendario";
            default -> "Común";
        };
    }

    static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 0.0001 ? String.format(Locale.US, "%.0f", v) : String.format(Locale.US, "%.1f", v);
    }

    public double statMult(Quality q, int plus, double extra) {
        FileConfiguration c = plugin.getConfig();
        List<Double> mult = c.getDoubleList("calidad.mult_stats");
        double qm = q.ordinal() < mult.size() ? mult.get(q.ordinal()) : 1.0;
        return qm * (1.0 + plus * c.getDouble("mejora.bonus_stat_por_nivel", 0.04)) * extra;
    }

    private double durMult(Quality q) {
        List<Double> mult = plugin.getConfig().getDoubleList("calidad.mult_durabilidad");
        return q.ordinal() < mult.size() ? mult.get(q.ordinal()) : 1.0;
    }

    // ------------------------------------------------------------------ construcción

    /** Item "básico" (admin /forja dar, drops de loot): calidad común, sin forjador. */
    public ItemStack basic(String id, int amount) {
        CustomItem ci = items.get(id);
        if (ci == null) return new ItemStack(Material.BARRIER);
        return create(ci, amount, Quality.COMUN, 0, 1.0, null, null, null);
    }

    public ItemStack create(CustomItem ci, int amount, Quality q, int plus, double extra,
                            String forger, UUID forgerId, ItemStack prev) {
        if (ci.isRpg()) return plugin.rpg.preview(ci, amount); // vista previa: el item real lo entrega el datapack RPG
        ItemStack it = new ItemStack(ci.base, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setCustomModelData(ci.cmd);
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(Keys.ITEM_ID, PersistentDataType.STRING, ci.id);
        for (Map.Entry<String, Object> e : ci.customData.entrySet()) {
            if (e.getKey().equals("rz_item")) continue;
            pdc.set(new NamespacedKey(plugin, e.getKey()), PersistentDataType.STRING, String.valueOf(e.getValue()));
        }
        if (ci.categoria.equals("mochila")) {   // cada mochila es única y no apila
            pdc.set(Keys.BAG_ID, PersistentDataType.STRING, UUID.randomUUID().toString());
            pdc.set(Keys.BAG_DATA, PersistentDataType.STRING, "");
            m.setMaxStackSize(1);
        }
        double mult = 1.0;
        if (ci.usesQuality()) {
            pdc.set(Keys.QUALITY, PersistentDataType.INTEGER, q.ordinal());
            pdc.set(Keys.PLUS, PersistentDataType.INTEGER, plus);
            pdc.set(Keys.EXTRA, PersistentDataType.DOUBLE, extra);
            mult = statMult(q, plus, extra);
            if (forger != null && forgerId != null) {
                pdc.set(Keys.FORGER, PersistentDataType.STRING, forger);
                pdc.set(Keys.FORGER_UUID, PersistentDataType.STRING, forgerId.toString());
                pdc.set(Keys.SIG, PersistentDataType.STRING, sign(ci.id, q.ordinal(), plus, forgerId.toString()));
            }
        }
        // buff (piedras de afilar / aceites) heredado
        if (prev != null && prev.hasItemMeta()) {
            PersistentDataContainer pp = prev.getItemMeta().getPersistentDataContainer();
            String bt = pp.get(Keys.BUFF_TYPE, PersistentDataType.STRING);
            Integer bu = pp.get(Keys.BUFF_USES, PersistentDataType.INTEGER);
            Double bv = pp.get(Keys.BUFF_VAL, PersistentDataType.DOUBLE);
            if (bt != null && bu != null && bu > 0) {
                pdc.set(Keys.BUFF_TYPE, PersistentDataType.STRING, bt);
                pdc.set(Keys.BUFF_USES, PersistentDataType.INTEGER, bu);
                pdc.set(Keys.BUFF_VAL, PersistentDataType.DOUBLE, bv == null ? 0.0 : bv);
            }
        }

        // nombre
        String color = ci.usesQuality() ? q.tag : rarityTag(ci.rareza);
        String nm = color + ci.nombre + (plus > 0 ? " <yellow>+" + plus : "");
        m.displayName(Lang.line(nm));
        m.lore(buildLore(ci, q, plus, mult, pdc));

        m.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ARMOR_TRIM, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_UNBREAKABLE);
        applyAttributes(m, ci, mult);

        if (m instanceof Damageable d && ci.base.getMaxDurability() > 0) {
            Integer max = maxDurability(ci, q);
            if (max != null) d.setMaxDamage(max);
        }
        if (m instanceof ArmorMeta am && ci.trimPattern != null) {
            applyTrim(am, ci);
        }
        if (m instanceof PotionMeta pm && ci.categoria.equals("consumible")) applyPotion(pm, ci);
        it.setItemMeta(m);

        if (prev != null) copyState(prev, it);
        return it;
    }

    private void copyState(ItemStack prev, ItemStack now) {
        if (!prev.getEnchantments().isEmpty()) now.addUnsafeEnchantments(prev.getEnchantments());
        ItemMeta pm = prev.getItemMeta();
        ItemMeta nm = now.getItemMeta();
        if (pm instanceof Damageable pd && nm instanceof Damageable nd && prev.getType().getMaxDurability() > 0) {
            int pmax = pd.hasMaxDamage() ? pd.getMaxDamage() : prev.getType().getMaxDurability();
            int nmax = nd.hasMaxDamage() ? nd.getMaxDamage() : now.getType().getMaxDurability();
            if (pmax > 0 && nmax > 0) {
                double frac = pd.getDamage() / (double) pmax;
                nd.setDamage((int) Math.min(nmax - 1, Math.round(frac * nmax)));
                now.setItemMeta(nm);
            }
        }
    }

    /**
     * Pociones de Forja: los efectos temporales se guardan como efectos NATIVOS de la poción (los aplica el propio
     * juego al beberla, aunque falle cualquier listener) y el color se pone en blanco para que no tiña la textura.
     */
    private void applyPotion(PotionMeta pm, CustomItem ci) {
        pm.setColor(Color.WHITE);
        for (Map.Entry<String, Object> e : ci.efectos.entrySet()) {
            PotionEffectType t = ItemUseListener.effectType(e.getKey());
            Object v = e.getValue();
            if (t != null && v instanceof List<?> l && l.size() >= 2 && l.get(0) instanceof Number s && l.get(1) instanceof Number a) {
                pm.addCustomEffect(new PotionEffect(t, Math.max(1, s.intValue() * 20), a.intValue()), true);
            }
        }
    }

    private void applyTrim(ArmorMeta am, CustomItem ci) {
        try {
            NamespacedKey pk = NamespacedKey.fromString(ci.trimPattern);
            NamespacedKey mk = NamespacedKey.fromString(plugin.getConfig().getString("trim.material", "rpgarm:rpg"));
            TrimPattern pat = pk == null ? null : Registry.TRIM_PATTERN.get(pk);
            TrimMaterial mat = mk == null ? null : Registry.TRIM_MATERIAL.get(mk);
            if (pat != null && mat != null) {
                am.setTrim(new ArmorTrim(mat, pat));
            } else if (!warnedTrim) {
                warnedTrim = true;
                plugin.getLogger().warning("No se encontró el trim " + ci.trimPattern + " o el material " + plugin.getConfig().getString("trim.material")
                        + ". ¿Está cargado el datapack de Forja? Las armaduras saldrán sin trim visual.");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error aplicando trim a " + ci.id + ": " + e.getMessage());
        }
    }

    private void applyAttributes(ItemMeta m, CustomItem ci, double mult) {
        String name = ci.base.name();
        if (ci.categoria.equals("arma") && ci.atributos.containsKey("dano") && ci.base != Material.ARROW) {
            double dmg = ci.attr("dano") * mult;
            double spd = ci.attr("vel_ataque");
            m.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey(plugin, "item_dmg"), round(dmg - 1.0), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, new AttributeModifier(
                    new NamespacedKey(plugin, "item_spd"), round(spd - 4.0), AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        } else if (ci.categoria.equals("herramienta") && (name.endsWith("_AXE") || name.endsWith("_HOE") || name.endsWith("_SHOVEL") || name.endsWith("_PICKAXE"))) {
            m.addAttributeModifier(Attribute.GENERIC_ATTACK_DAMAGE, new AttributeModifier(
                    new NamespacedKey(plugin, "item_dmg"), 1.5, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
            m.addAttributeModifier(Attribute.GENERIC_ATTACK_SPEED, new AttributeModifier(
                    new NamespacedKey(plugin, "item_spd"), -2.4, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.MAINHAND));
        } else if (ci.categoria.equals("armadura")) {
            EquipmentSlotGroup g = slotOf(name);
            if (g != null) {
                m.addAttributeModifier(Attribute.GENERIC_ARMOR, new AttributeModifier(
                        new NamespacedKey(plugin, "item_armor"), round(ci.attr("defensa") * mult), AttributeModifier.Operation.ADD_NUMBER, g));
                double tough = ci.attr("dureza") * mult;
                if (tough > 0) {
                    m.addAttributeModifier(Attribute.GENERIC_ARMOR_TOUGHNESS, new AttributeModifier(
                            new NamespacedKey(plugin, "item_tough"), round(tough), AttributeModifier.Operation.ADD_NUMBER, g));
                }
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static EquipmentSlotGroup slotOf(String materialName) {
        if (materialName.endsWith("_HELMET")) return EquipmentSlotGroup.HEAD;
        if (materialName.endsWith("_CHESTPLATE")) return EquipmentSlotGroup.CHEST;
        if (materialName.endsWith("_LEGGINGS")) return EquipmentSlotGroup.LEGS;
        if (materialName.endsWith("_BOOTS")) return EquipmentSlotGroup.FEET;
        return null;
    }

    private Integer maxDurability(CustomItem ci, Quality q) {
        FileConfiguration c = plugin.getConfig();
        double base;
        if (ci.categoria.equals("herramienta")) base = ci.durabilidad;
        else if (c.isSet("durabilidad.item." + ci.id)) base = c.getDouble("durabilidad.item." + ci.id);
        else if (ci.categoria.equals("armadura") && ci.estilo != null) {
            String pieza = ci.id.contains("_") ? ci.id.substring(0, ci.id.indexOf('_')) : ci.id;
            base = c.getDouble("durabilidad.estilo." + ci.estilo, 0) * c.getDouble("durabilidad.pieza." + pieza, 1.0);
        } else if (ci.categoria.equals("arma") && ci.tier != null) base = c.getDouble("durabilidad.tier." + ci.tier, 0);
        else return null;
        if (base <= 0) return null;
        return (int) Math.max(1, Math.round(base * (ci.categoria.equals("herramienta") ? 1.0 : durMult(q))));
    }

    // ------------------------------------------------------------------ lore

    private List<Component> buildLore(CustomItem ci, Quality q, int plus, double mult, PersistentDataContainer pdc) {
        Lang L = plugin.lang;
        List<Component> lore = new ArrayList<>();
        for (String l : ci.lore) lore.add(Lang.line("<gray>" + l));
        lore.add(Lang.line(""));
        if (ci.categoria.equals("arma") && ci.atributos.containsKey("dano")) {
            lore.add(Lang.line(L.raw("item.dano", "v", fmt(ci.attr("dano") * mult), "vel", fmt(ci.attr("vel_ataque")))));
        } else if (ci.categoria.equals("arma") && ci.atributos.containsKey("dano_proyectil")) {
            lore.add(Lang.line(L.raw("item.dano_proyectil", "v", fmt(ci.attr("dano_proyectil") * mult))));
        } else if (ci.atributos.containsKey("mult_dano")) {
            lore.add(Lang.line(L.raw("item.mult_dano", "v", fmt((ci.attr("mult_dano") - 1) * 100))));
        }
        if (ci.categoria.equals("armadura")) {
            lore.add(Lang.line(L.raw("item.defensa", "v", fmt(ci.attr("defensa") * mult), "d", fmt(ci.attr("dureza") * mult))));
        }
        if (ci.categoria.equals("arma") && ci.atributos.containsKey("dureza") && !ci.atributos.containsKey("dano")) {
            lore.add(Lang.line(L.raw("item.escudo", "v", fmt(ci.attr("dureza")))));
        }
        if (ci.maestria != null) lore.add(Lang.line(L.raw("item.maestria", "m", ci.maestria.replace('_', ' '))));
        if (ci.categoria.equals("joya")) {
            lore.add(Lang.line(L.raw("item.slot", "s", ci.slot == null ? "?" : ci.slot)));
            for (Map.Entry<String, Object> e : ci.pasivo.entrySet()) {
                if (e.getValue() instanceof Number n) lore.add(Lang.line(L.raw("pasivo." + e.getKey(), "v", fmt(n.doubleValue() * mult))));
                else lore.add(Lang.line(L.raw("pasivo." + e.getKey(), "v", "")));
            }
        }
        if (ci.categoria.equals("consumible")) {
            for (Map.Entry<String, Object> e : ci.efectos.entrySet()) {
                Object v = e.getValue();
                if (v instanceof List<?> lst && lst.size() >= 2) {
                    lore.add(Lang.line(L.raw("efecto." + e.getKey(), "seg", lst.get(0), "nivel", ((Number) lst.get(1)).intValue() + 1)));
                } else if (v instanceof Number n) {
                    lore.add(Lang.line(L.raw("efecto." + e.getKey(), "v", fmt(n.doubleValue()))));
                } else if (v instanceof List<?> lst2) {
                    lore.add(Lang.line(L.raw("efecto." + e.getKey(), "v", String.join(", ", lst2.stream().map(o -> String.valueOf(o)).toList()))));
                }
            }
        }
        if (ci.categoria.equals("mochila")) lore.add(Lang.line(L.raw("item.capacidad", "n", (int) ci.attr("slots"))));
        if (ci.categoria.equals("herramienta")) {
            for (Map.Entry<String, Object> e : ci.bonus.entrySet()) {
                lore.add(Lang.line(L.raw("bonus." + e.getKey(), "v", fmt(((Number) e.getValue()).doubleValue()))));
            }
            lore.add(Lang.line(L.raw("item.durabilidad_herramienta", "v", ci.durabilidad)));
        }
        if (ci.usesQuality()) {
            lore.add(Lang.line(L.raw("item.calidad", "c", q.tag + q.display)));
            if (plus > 0) lore.add(Lang.line(L.raw("item.mejora", "n", plus)));
        }
        String bt = pdc.get(Keys.BUFF_TYPE, PersistentDataType.STRING);
        Integer bu = pdc.get(Keys.BUFF_USES, PersistentDataType.INTEGER);
        if (bt != null && bu != null && bu > 0) lore.add(Lang.line(L.raw("buff." + bt, "usos", bu)));
        if (ci.oficio != null && !ci.categoria.equals("estacion")) {
            Profession p = Profession.fromId(ci.oficio);
            if (p != null) lore.add(Lang.line(L.raw("item.oficio", "o", p.display, "n", ci.nivelReq)));
        }
        if (!ci.raza.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String r : ci.raza) names.add(plugin.races.name(r));
            lore.add(Lang.line(L.raw("item.raza", "r", String.join(", ", names))));
        }
        String fn = pdc.get(Keys.FORGER, PersistentDataType.STRING);
        if (fn != null) lore.add(Lang.line(L.raw("item.forjador", "f", fn)));
        lore.add(Lang.line(rarityTag(ci.rareza) + rarityName(ci.rareza)));
        return lore;
    }

    // ------------------------------------------------------------------ lectura / reconstrucción

    public Quality qualityOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return Quality.COMUN;
        Integer q = it.getItemMeta().getPersistentDataContainer().get(Keys.QUALITY, PersistentDataType.INTEGER);
        return q == null ? Quality.COMUN : Quality.of(q);
    }

    public int plusOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return 0;
        Integer p = it.getItemMeta().getPersistentDataContainer().get(Keys.PLUS, PersistentDataType.INTEGER);
        return p == null ? 0 : p;
    }

    /** Reconstruye el item con otro nivel de mejora conservando calidad, forjador, encantamientos y desgaste. */
    public ItemStack rebuild(ItemStack it, int newPlus) {
        CustomItem ci = of(it);
        if (ci == null) return it;
        if (ci.isRpg()) return plugin.rpg.rebuild(it, newPlus);
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        Double extra = pdc.get(Keys.EXTRA, PersistentDataType.DOUBLE);
        String fn = pdc.get(Keys.FORGER, PersistentDataType.STRING);
        String fu = pdc.get(Keys.FORGER_UUID, PersistentDataType.STRING);
        UUID uid = null;
        try {
            if (fu != null) uid = UUID.fromString(fu);
        } catch (IllegalArgumentException ignored) {
        }
        return create(ci, it.getAmount(), qualityOf(it), newPlus, extra == null ? 1.0 : extra, fn, uid, it);
    }

    /** Pasivos de una joya ya escalados por calidad. */
    public Map<String, Double> passives(ItemStack it) {
        Map<String, Double> out = new HashMap<>();
        CustomItem ci = of(it);
        if (ci == null || !ci.categoria.equals("joya")) return out;
        double mult = statMult(qualityOf(it), plusOf(it), 1.0);
        for (Map.Entry<String, Object> e : ci.pasivo.entrySet()) {
            if (e.getValue() instanceof Number n) out.put(e.getKey(), n.doubleValue() * mult);
            else if (Boolean.TRUE.equals(e.getValue())) out.put(e.getKey(), 1.0);
        }
        return out;
    }

    // ------------------------------------------------------------------ firma anti-falsificación

    String sign(String id, int q, int plus, String uuid) {
        try {
            String secret = plugin.getConfig().getString("seguridad.secreto", "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] h = mac.doFinal((id + "|" + q + "|" + plus + "|" + uuid).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** true si el "Forjado por X" fue generado por este servidor (firma válida). */
    public boolean isAuthentic(ItemStack it) {
        CustomItem ci = of(it);
        if (ci == null) return false;
        PersistentDataContainer pdc = it.getItemMeta().getPersistentDataContainer();
        String sig = pdc.get(Keys.SIG, PersistentDataType.STRING);
        String fu = pdc.get(Keys.FORGER_UUID, PersistentDataType.STRING);
        if (sig == null || fu == null) return false;
        return sig.equals(sign(ci.id, qualityOf(it).ordinal(), plusOf(it), fu));
    }
}
