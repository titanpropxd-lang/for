package com.servidor.forja;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scoreboard.Objective;

import java.util.*;

/**
 * Puente con el datapack RPG del servidor.
 * Las armas y armaduras NO las construye este plugin: se piden a las funciones del propio datapack
 * (rpg:give/m<N> {cmd:..} y rpg:give/a<A>_c<C>_<pieza>) para que lleven EXACTAMENTE su custom_data,
 * habilidades, maná, maestrías y atributos. Después se les añade calidad, refuerzo y forjador.
 */
public final class RpgBridge {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ForjaPlugin plugin;

    public RpgBridge(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ scoreboard

    public int score(String objective, String entry) {
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(objective);
        return o == null ? 0 : o.getScore(entry).getScore();
    }

    public boolean hasFreeSlot(Player p) {
        for (ItemStack it : p.getInventory().getStorageContents()) if (it == null || it.getType().isAir()) return true;
        return false;
    }

    /** Nivel de XP que exige el datapack (si #gate está activo) o -1 si no hay bloqueo. */
    public int gateNeeded(Player p, CustomItem ci) {
        if (!plugin.getConfig().getBoolean("rpg.respetar_gate", true)) return -1;
        if (score("rpg_cfg", "#gate") != 1) return -1;
        int need = ci.rpgInt("nivel_xp", 0);
        return p.getLevel() < need ? need : -1;
    }

    // ------------------------------------------------------------------ estadísticas

    public double mult(Quality q, int plus) {
        List<Double> l = plugin.getConfig().getDoubleList("rpg.calidad_mult");
        double qm = q.ordinal() < l.size() ? l.get(q.ordinal()) : 1.0;
        return qm * (1.0 + plus * plugin.getConfig().getDouble("rpg.bonus_refuerzo", 0.015));
    }

    private double durMult(Quality q) {
        List<Double> l = plugin.getConfig().getDoubleList("rpg.calidad_durabilidad");
        return q.ordinal() < l.size() ? l.get(q.ordinal()) : 1.0;
    }

    // ------------------------------------------------------------------ vista previa (GUI)

    public ItemStack preview(CustomItem ci, int amount) {
        ItemStack it = new ItemStack(ci.base, Math.max(1, amount));
        ItemMeta m = it.getItemMeta();
        m.setCustomModelData(ci.cmd);
        m.displayName(Lang.line("<" + ci.color + ">" + Lang.esc(ci.nombre)));
        List<Component> lore = new ArrayList<>();
        boolean first = true;
        for (String l : ci.lore) {
            lore.add(Lang.line((first ? ItemRegistry.rarityTag(ci.rareza) : "<gray>") + Lang.esc(l)));
            first = false;
        }
        lore.add(Lang.line(""));
        if (ci.categoria.equals("rpg_arma")) {
            lore.add(Lang.line(plugin.lang.raw("item.dano", "v", ItemRegistry.fmt(ci.attr("dano")), "vel", ItemRegistry.fmt(ci.attr("vel_ataque")))));
        } else {
            lore.add(Lang.line(plugin.lang.raw("item.defensa", "v", ItemRegistry.fmt(ci.attr("defensa")), "d", ItemRegistry.fmt(ci.attr("dureza")))));
        }
        m.lore(lore);
        it.setItemMeta(m);
        return it;
    }

    // ------------------------------------------------------------------ entrega

    private static ItemStack[] copy(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }

    /**
     * Ejecuta la función del datapack como el jugador y personaliza el item recién entregado.
     * Devuelve el item (ya en el inventario) o null si algo falló (el llamador devuelve los materiales).
     */
    public ItemStack give(Player p, CustomItem ci, Quality q, int plus, String forger, UUID forgerId) {
        ItemStack[] before = copy(p.getInventory().getStorageContents());
        String n = p.getName();
        String cmd;
        if (ci.rpgBool("macro")) {
            int off = ci.rpgBool("color") ? score("rpg_off", n) : 0;
            cmd = "execute as " + n + " at " + n + " run function " + ci.rpgStr("funcion") + " {cmd:" + (ci.cmd + off) + "}";
        } else {
            int colores = Math.max(1, ci.rpgInt("colores", 1));
            int c = Math.max(0, Math.min(colores - 1, score("rpg_color", n)));
            cmd = "execute as " + n + " at " + n + " run function " + ci.rpgStr("funcion").replace("{c}", String.valueOf(c));
        }
        if (plugin.getConfig().getBoolean("general.debug", false)) plugin.getLogger().info("RPG> " + cmd);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        ItemStack[] after = p.getInventory().getStorageContents();
        for (int i = 0; i < after.length; i++) {
            ItemStack a = after[i];
            if (a == null || a.getType().isAir() || a.getType() != ci.base) continue;
            if (before[i] != null && before[i].equals(a)) continue;
            ItemStack done = personalize(a, ci, q, plus, forger, forgerId);
            p.getInventory().setItem(i, done);
            return done;
        }
        plugin.getLogger().warning("No se detectó el item entregado por '" + cmd + "'. ¿Existe la función y está cargado el datapack rpg?");
        return null;
    }

    private ItemStack personalize(ItemStack st, CustomItem ci, Quality q, int plus, String forger, UUID fid) {
        ItemMeta m = st.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        pdc.set(Keys.ITEM_ID, PersistentDataType.STRING, ci.id);
        pdc.set(Keys.QUALITY, PersistentDataType.INTEGER, q.ordinal());
        pdc.set(Keys.PLUS, PersistentDataType.INTEGER, plus);
        pdc.set(Keys.EXTRA, PersistentDataType.DOUBLE, 1.0);
        if (forger != null && fid != null) {
            pdc.set(Keys.FORGER, PersistentDataType.STRING, forger);
            pdc.set(Keys.FORGER_UUID, PersistentDataType.STRING, fid.toString());
            pdc.set(Keys.SIG, PersistentDataType.STRING, plugin.items.sign(ci.id, q.ordinal(), plus, fid.toString()));
        }
        Component dn = m.displayName();
        pdc.set(Keys.RPG_NAME, PersistentDataType.STRING, dn == null ? "<white>" + Lang.esc(ci.nombre) : MM.serialize(dn));
        if (m instanceof Damageable d && d.hasMaxDamage()) pdc.set(Keys.RPG_MAXDMG, PersistentDataType.INTEGER, d.getMaxDamage());
        pdc.set(Keys.RPG_LORE_N, PersistentDataType.INTEGER, 0);
        st.setItemMeta(m);
        return apply(st, ci, q, plus);
    }

    /** Reaplica atributos, durabilidad, nombre y lore según calidad/mejora (idempotente). */
    private ItemStack apply(ItemStack st, CustomItem ci, Quality q, int plus) {
        ItemMeta m = st.getItemMeta();
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        double mult = mult(q, plus);
        if (ci.categoria.equals("rpg_arma")) {
            scale(m, Attribute.GENERIC_ATTACK_DAMAGE, Keys.RPG_BASE_DMG, mult);
        } else {
            scale(m, Attribute.GENERIC_ARMOR, Keys.RPG_BASE_ARMOR, mult);
            scale(m, Attribute.GENERIC_ARMOR_TOUGHNESS, Keys.RPG_BASE_TOUGH, mult);
        }
        Integer bmax = pdc.get(Keys.RPG_MAXDMG, PersistentDataType.INTEGER);
        if (bmax != null && m instanceof Damageable d) {
            d.setMaxDamage((int) Math.max(1, Math.round(bmax * durMult(q))));
        }
        String base = pdc.get(Keys.RPG_NAME, PersistentDataType.STRING);
        if (base == null) base = "<white>" + Lang.esc(ci.nombre);
        m.displayName(Lang.line(base + " <dark_gray>[" + q.tag + q.display + "<dark_gray>]" + (plus > 0 ? " <yellow>+" + plus : "")));

        List<Component> lore = m.lore() == null ? new ArrayList<>() : new ArrayList<>(m.lore());
        int n = pdc.getOrDefault(Keys.RPG_LORE_N, PersistentDataType.INTEGER, 0);
        for (int i = 0; i < n && !lore.isEmpty(); i++) lore.remove(lore.size() - 1);
        List<Component> extra = new ArrayList<>();
        Lang L = plugin.lang;
        extra.add(Lang.line(""));
        extra.add(Lang.line(L.raw("item.calidad", "c", q.tag + q.display)));
        if (plus > 0) extra.add(Lang.line(L.raw("item.mejora", "n", plus)));
        String bt = pdc.get(Keys.BUFF_TYPE, PersistentDataType.STRING);
        Integer bu = pdc.get(Keys.BUFF_USES, PersistentDataType.INTEGER);
        if (bt != null && bu != null && bu > 0) extra.add(Lang.line(L.raw("buff." + bt, "usos", bu)));
        String fn = pdc.get(Keys.FORGER, PersistentDataType.STRING);
        if (fn != null) extra.add(Lang.line(L.raw("item.forjador", "f", fn)));
        lore.addAll(extra);
        m.lore(lore);
        pdc.set(Keys.RPG_LORE_N, PersistentDataType.INTEGER, extra.size());
        st.setItemMeta(m);
        return st;
    }

    private void scale(ItemMeta m, Attribute a, NamespacedKey baseKey, double mult) {
        Collection<AttributeModifier> mods = m.getAttributeModifiers(a);
        if (mods == null || mods.isEmpty()) return;
        List<AttributeModifier> list = new ArrayList<>(mods);
        PersistentDataContainer pdc = m.getPersistentDataContainer();
        Double base = pdc.get(baseKey, PersistentDataType.DOUBLE);
        if (base == null) {
            base = list.get(0).getAmount();
            pdc.set(baseKey, PersistentDataType.DOUBLE, base);
        }
        AttributeModifier o = list.get(0);
        m.removeAttributeModifier(a);
        m.addAttributeModifier(a, new AttributeModifier(o.getKey(), Math.round(base * mult * 100.0) / 100.0, o.getOperation(), o.getSlotGroup()));
        for (int i = 1; i < list.size(); i++) m.addAttributeModifier(a, list.get(i));
    }

    /** Copia del item con otro nivel de refuerzo (conserva calidad, forjador, desgaste y encantamientos). */
    public ItemStack rebuild(ItemStack it, int newPlus) {
        CustomItem ci = plugin.items.of(it);
        if (ci == null || !ci.isRpg()) return it;
        ItemStack c = it.clone();
        ItemMeta m = c.getItemMeta();
        m.getPersistentDataContainer().set(Keys.PLUS, PersistentDataType.INTEGER, newPlus);
        c.setItemMeta(m);
        return apply(c, ci, plugin.items.qualityOf(it), newPlus);
    }
}
