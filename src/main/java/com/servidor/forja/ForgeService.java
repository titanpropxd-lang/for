package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/** Lógica de forja: costes, materiales, calidad, mejora, reparación y desguace. */
public final class ForgeService {
    public static final class Cost {
        public final LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
        public final Set<String> keep = new HashSet<>();
        public RaceService.Elig elig = RaceService.Elig.OK;
    }

    public static final class ToolInfo {
        public double vel, cal, comb;
        public final List<Integer> slots = new ArrayList<>();
    }

    public static final class Result {
        public ItemStack item;
        public Quality quality = Quality.COMUN;
    }

    public static final class Plan {
        public final Cost cost = new Cost();
        public String deny;
        public ItemStack preview;
        public double chance = 100;
        public final List<String> info = new ArrayList<>();
        public Map<String, Integer> returns = new LinkedHashMap<>();
    }

    private final ForjaPlugin plugin;
    private final Random rnd = new Random();

    public ForgeService(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ inventario

    private boolean matches(ItemStack it, String key) {
        if (it == null || it.getType().isAir()) return false;
        String id = plugin.items.idOf(it);
        if (key.startsWith("vanilla:")) {
            if (id != null || it.hasItemMeta()) return false;
            Material m = Material.matchMaterial(key.substring(8));
            return m != null && it.getType() == m;
        }
        if (key.equals("fuel")) {
            return id == null && !it.hasItemMeta() && (it.getType() == Material.COAL || it.getType() == Material.CHARCOAL);
        }
        return key.equals(id);
    }

    public int count(Player p, String key) {
        int n = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) if (matches(it, key)) n += it.getAmount();
        return n;
    }

    public boolean has(Player p, Cost c) {
        for (Map.Entry<String, Integer> e : c.needed.entrySet()) {
            if (count(p, e.getKey()) < e.getValue()) return false;
        }
        return true;
    }

    public boolean remove(Player p, String key, int n) {
        if (count(p, key) < n) return false;
        PlayerInventory inv = p.getInventory();
        ItemStack[] c = inv.getStorageContents();
        int left = n;
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack it = c[i];
            if (!matches(it, key)) continue;
            int t = Math.min(left, it.getAmount());
            if (t == it.getAmount()) c[i] = null;
            else it.setAmount(it.getAmount() - t);
            left -= t;
        }
        inv.setStorageContents(c);
        return true;
    }

    /** Consume los materiales del coste (salvo los "keep") y devuelve lo consumido. Llamar tras has(). */
    public Map<String, Integer> take(Player p, Cost c) {
        Map<String, Integer> consumed = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : c.needed.entrySet()) {
            if (c.keep.contains(e.getKey())) continue;
            if (remove(p, e.getKey(), e.getValue())) consumed.put(e.getKey(), e.getValue());
        }
        return consumed;
    }

    public void give(Player p, ItemStack s) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(s);
        for (ItemStack i : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), i);
    }

    public ItemStack stackFor(String key, int n) {
        if (key.equals("fuel")) return new ItemStack(Material.COAL, n);
        if (key.startsWith("vanilla:")) {
            Material m = Material.matchMaterial(key.substring(8));
            return new ItemStack(m == null ? Material.BARRIER : m, n);
        }
        return plugin.items.basic(key, n);
    }

    public void refund(Player p, Map<String, Integer> consumed) {
        for (Map.Entry<String, Integer> e : consumed.entrySet()) {
            int left = e.getValue();
            while (left > 0) {
                int n = Math.min(64, left);
                give(p, stackFor(e.getKey(), n));
                left -= n;
            }
        }
    }

    public String nameOf(String key) {
        if (key.equals("fuel")) return plugin.lang.raw("ingrediente.fuel");
        if (key.startsWith("vanilla:")) {
            String mat = key.substring(8);
            String s = plugin.lang.raw("vanilla." + mat);
            if (!s.equals("vanilla." + mat)) return s;
            String h = mat.toLowerCase(Locale.ROOT).replace('_', ' ');
            return Character.toUpperCase(h.charAt(0)) + h.substring(1);
        }
        CustomItem ci = plugin.items.get(key);
        return ci == null ? key : ci.nombre;
    }

    public ItemStack iconFor(String key, int n) {
        return stackFor(key, Math.max(1, Math.min(64, n)));
    }

    // ------------------------------------------------------------------ coste y herramientas

    public ToolInfo tools(Player p, Profession prof) {
        ToolInfo t = new ToolInfo();
        ItemStack[] c = p.getInventory().getStorageContents();
        for (int i = 0; i < c.length; i++) {
            CustomItem ci = plugin.items.of(c[i]);
            if (ci == null || !ci.categoria.equals("herramienta") || !prof.id.equals(ci.oficio)) continue;
            double v = CustomItem.dbl(ci.bonus, "vel_forja_pct", 0);
            double q = CustomItem.dbl(ci.bonus, "calidad_pct", 0);
            double f = CustomItem.dbl(ci.bonus, "ahorro_comb_pct", 0);
            boolean used = false;
            if (v > t.vel) { t.vel = v; used = true; }
            if (q > t.cal) { t.cal = q; used = true; }
            if (f > t.comb) { t.comb = f; used = true; }
            if (used) t.slots.add(i);
        }
        return t;
    }

    public Cost cost(Player p, Recipe r) {
        RaceService.Info ri = plugin.races.get(p);
        Cost c = new Cost();
        c.elig = plugin.races.eligibility(ri, r);
        ToolInfo t = tools(p, r.prof);
        double mult = plugin.getConfig().getDouble("razas_ajenas.mult_materiales", 1.5);
        for (Map.Entry<String, Integer> e : r.ing.entrySet()) {
            String k = e.getKey();
            int n = e.getValue();
            double sav = k.equals("fuel")
                    ? Math.min(0.9, plugin.races.ahorroCombustible(ri) + t.comb / 100.0)
                    : plugin.races.ahorro(ri, k);
            if (sav > 0) n = (int) Math.max(1, Math.round(n * (1 - sav)));
            if (c.elig == RaceService.Elig.FOREIGN) n = (int) Math.ceil(n * mult);
            c.needed.put(k, n);
        }
        for (String k : r.noConsumir) {
            c.needed.merge(k, 1, Math::max);
            c.keep.add(k);
        }
        return c;
    }

    public int ticks(Player p, Recipe r) {
        ToolInfo t = tools(p, r.prof);
        double mult = plugin.getConfig().getDouble("forja.tiempo_mult", 1.0);
        double sec = r.tiempo * mult * Math.max(0.2, 1.0 - t.vel / 100.0);
        return Math.max(20, (int) Math.round(sec * 20.0));
    }

    // ------------------------------------------------------------------ calidad

    public double score(Player p, Recipe r, String station) {
        FileConfiguration c = plugin.getConfig();
        RaceService.Info ri = plugin.races.get(p);
        int lvl = plugin.professions.get(p).level(r.prof);
        ToolInfo t = tools(p, r.prof);
        double s = lvl * c.getDouble("calidad.por_nivel_oficio", 1.2);
        s += t.cal;
        s += plugin.races.calidad(ri, r.prof, station, p);
        if (ri.has()) s += Math.floor(ri.level() / 5.0) * c.getDouble("calidad.por_5_niveles_raza", 2.0);
        s -= r.nivel * c.getDouble("calidad.dificultad_por_nivel_receta", 0.5);
        return s;
    }

    /** Probabilidades (%) de [Tosca, Común, Buena, Excelente, Maestra]. */
    public double[] chances(double s, int lvl) {
        FileConfiguration c = plugin.getConfig();
        String b = "calidad.pesos.";
        double[] w = new double[5];
        w[0] = Math.max(c.getDouble(b + "tosca_min", 5), c.getDouble(b + "tosca_base", 40) - c.getDouble(b + "tosca_por_score", 0.7) * s);
        w[1] = c.getDouble(b + "comun", 50);
        w[2] = Math.max(0, c.getDouble(b + "buena_base", 5) + c.getDouble(b + "buena_por_score", 0.45) * s);
        w[3] = Math.max(0, (s - c.getDouble(b + "excelente_inicio", 10)) * c.getDouble(b + "excelente_por_score", 0.3));
        w[4] = Math.max(0, (s - c.getDouble(b + "maestra_inicio", 30)) * c.getDouble(b + "maestra_por_score", 0.15));
        List<Integer> mins = c.getIntegerList("calidad.nivel_min");
        for (int i = 0; i < 5; i++) if (i < mins.size() && lvl < mins.get(i)) w[i] = 0;
        double sum = 0;
        for (double v : w) sum += v;
        if (sum <= 0) {
            w[1] = 1;
            sum = 1;
        }
        for (int i = 0; i < 5; i++) w[i] = w[i] * 100.0 / sum;
        return w;
    }

    public Quality roll(Player p, Recipe r, String station, RaceService.Elig elig) {
        int lvl = plugin.professions.get(p).level(r.prof);
        double[] w = chances(score(p, r, station), lvl);
        double x = rnd.nextDouble() * 100.0, acc = 0;
        int q = 1;
        for (int i = 0; i < 5; i++) {
            acc += w[i];
            if (x < acc) {
                q = i;
                break;
            }
        }
        if (elig == RaceService.Elig.FOREIGN) q = Math.max(0, q - plugin.getConfig().getInt("razas_ajenas.pasos_calidad", 1));
        return Quality.of(q);
    }

    // ------------------------------------------------------------------ forjar

    public Result finish(Player p, Recipe r, Cost cost, String station) {
        CustomItem ci = plugin.items.get(r.resultado);
        Result res = new Result();
        RaceService.Info ri = plugin.races.get(p);
        double extra = 1.0;
        if (ci.usesQuality()) {
            res.quality = roll(p, r, station, cost.elig);
            extra = plugin.races.dmgMult(ri, r, ci);
        }
        res.item = plugin.items.create(ci, r.cantidad, res.quality, 0, extra,
                ci.usesQuality() ? p.getName() : null, p.getUniqueId(), null);
        give(p, res.item.clone());
        wearTools(p, tools(p, r.prof));
        plugin.professions.addXp(p, r.prof, r.xp);
        plugin.professions.incStat(p.getUniqueId(), r.resultado, r.cantidad);

        FileConfiguration c = plugin.getConfig();
        if (r.xpRaza > 0 && ri.has() && c.getBoolean("forja.dar_xp_raza", true)) {
            int amt = (int) Math.min(c.getInt("forja.xp_raza_max_por_entrega", 5000),
                    Math.round(r.xpRaza * c.getDouble("forja.xp_raza_mult", 1.0)));
            if (amt > 0) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "scoreboard players add " + p.getName() + " rz_qxp " + amt);
            }
        }
        for (String cmd : c.getStringList("al_forjar_comandos")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd
                    .replace("{jugador}", p.getName()).replace("{item}", r.resultado)
                    .replace("{calidad}", res.quality.name().toLowerCase(Locale.ROOT)).replace("{oficio}", r.oficio));
        }
        return res;
    }

    private void wearTools(Player p, ToolInfo t) {
        PlayerInventory inv = p.getInventory();
        for (int slot : t.slots) {
            ItemStack st = inv.getItem(slot);
            if (st == null || !st.hasItemMeta()) continue;
            ItemMeta m = st.getItemMeta();
            if (m instanceof Damageable d) {
                int max = d.hasMaxDamage() ? d.getMaxDamage() : st.getType().getMaxDurability();
                d.setDamage(d.getDamage() + 1);
                st.setItemMeta(m);
                if (max > 0 && d.getDamage() >= max) {
                    inv.setItem(slot, null);
                    p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
                    plugin.lang.send(p, "forja.herramienta_rota");
                }
            }
        }
    }

    // ------------------------------------------------------------------ mejora / reparación / desguace

    public String upgradeMaterial(CustomItem ci) {
        FileConfiguration c = plugin.getConfig();
        String s = c.getString("mejora.material.item." + ci.id);
        if (s == null && ci.estilo != null) s = c.getString("mejora.material.estilo." + ci.estilo);
        if (s == null && ci.tier != null) s = c.getString("mejora.material.tier." + ci.tier);
        if (s == null) s = c.getString("mejora.material.defecto", "madera_noble");
        return s;
    }

    public Plan planUpgrade(Player p, ItemStack st) {
        Plan pl = new Plan();
        CustomItem ci = plugin.items.of(st);
        FileConfiguration c = plugin.getConfig();
        if (ci == null || !ci.isEquipment() || ci.oficio == null) { pl.deny = "mejora.no_valido"; return pl; }
        int plus = plugin.items.plusOf(st);
        if (plus >= c.getInt("mejora.max", 5)) { pl.deny = "mejora.maximo"; return pl; }
        int next = plus + 1;
        Profession prof = Profession.fromId(ci.oficio);
        int lvl = plugin.professions.get(p).level(prof);
        List<Integer> mins = c.getIntegerList("mejora.nivel_min_oficio");
        if (next - 1 < mins.size() && lvl < mins.get(next - 1)) {
            pl.deny = "mejora.nivel";
            pl.info.add("<red>Requiere " + prof.display + " nv " + mins.get(next - 1));
        }
        List<Integer> costs = c.getIntegerList("mejora.costes");
        int n = next - 1 < costs.size() ? costs.get(next - 1) : next * 2;
        pl.cost.needed.put(upgradeMaterial(ci), n);
        if (prof == Profession.HERRERIA && c.getInt("mejora.fuel", 1) > 0) pl.cost.needed.put("fuel", c.getInt("mejora.fuel", 1));
        List<Integer> probs = c.getIntegerList("mejora.prob");
        double base = next - 1 < probs.size() ? probs.get(next - 1) : 50;
        RaceService.Info ri = plugin.races.get(p);
        pl.chance = Math.max(5, Math.min(99, base + lvl * c.getDouble("mejora.prob_por_nivel_oficio", 0.3) + plugin.races.bonusMejora(ri)));
        pl.preview = plugin.items.rebuild(st, next);
        pl.info.add("<gray>Probabilidad de éxito: <white>" + ItemRegistry.fmt(pl.chance) + "%");
        pl.info.add("<gray>Si falla: <red>-" + c.getInt("mejora.fallo_baja_niveles", 1) + " nivel de mejora <gray>(no se destruye)");
        return pl;
    }

    public Plan planRepair(Player p, ItemStack st) {
        Plan pl = new Plan();
        CustomItem ci = plugin.items.of(st);
        FileConfiguration c = plugin.getConfig();
        if (ci == null || ci.oficio == null || !(st.getItemMeta() instanceof Damageable d) || st.getType().getMaxDurability() <= 0) {
            pl.deny = "reparar.no_valido";
            return pl;
        }
        int max = d.hasMaxDamage() ? d.getMaxDamage() : st.getType().getMaxDurability();
        if (d.getDamage() <= 0 || max <= 0) { pl.deny = "reparar.intacto"; return pl; }
        Profession prof = Profession.fromId(ci.oficio);
        int lvl = plugin.professions.get(p).level(prof);
        if (lvl * 2 < ci.nivelReq) {
            pl.deny = "reparar.nivel";
            pl.info.add("<red>Requiere " + prof.display + " nv " + (ci.nivelReq + 1) / 2);
        }
        double frac = d.getDamage() / (double) max;
        int n = Math.max(1, (int) Math.ceil(frac * c.getInt("reparar.max_materiales", 4)));
        String mat = ci.categoria.equals("herramienta") ? "lingote_cobre" : upgradeMaterial(ci);
        pl.cost.needed.put(mat, n);
        ItemStack prev = st.clone();
        ItemMeta pm = prev.getItemMeta();
        ((Damageable) pm).setDamage(0);
        prev.setItemMeta(pm);
        pl.preview = prev;
        pl.info.add("<gray>Desgaste: <white>" + Math.round(frac * 100) + "%");
        return pl;
    }

    private Map<String, Integer> salvageExpected(Player p, ItemStack st, CustomItem ci, boolean random) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Recipe r = plugin.recipes.byResult(ci.id);
        if (r == null) return out;
        FileConfiguration c = plugin.getConfig();
        double pct = c.getDouble("desguace.porcentaje", 0.5);
        if (ci.usesQuality()) pct += plugin.items.qualityOf(st).ordinal() * c.getDouble("desguace.bonus_por_calidad", 0.05);
        pct += plugin.races.bonusDesguace(plugin.races.get(p));
        pct = Math.min(0.95, pct);
        for (Map.Entry<String, Integer> e : r.ing.entrySet()) {
            if (e.getKey().equals("fuel")) continue;
            double x = e.getValue() * pct;
            int n = (int) Math.floor(x);
            if (random && rnd.nextDouble() < x - n) n++;
            else if (!random) n = (int) Math.round(x);
            if (n > 0) out.put(e.getKey(), n);
        }
        return out;
    }

    public Plan planSalvage(Player p, ItemStack st) {
        Plan pl = new Plan();
        CustomItem ci = plugin.items.of(st);
        if (ci == null || ci.oficio == null || ci.categoria.equals("material") || ci.categoria.equals("estacion")
                || plugin.recipes.byResult(ci.id) == null) {
            pl.deny = "desguace.no_valido";
            return pl;
        }
        pl.returns = salvageExpected(p, st, ci, false);
        pl.info.add("<gray>Recuperas aproximadamente el <white>" + Math.round(plugin.getConfig().getDouble("desguace.porcentaje", 0.5) * 100) + "%+ <gray>de los materiales.");
        pl.info.add("<red>El objeto se destruye.");
        ItemStack show = new ItemStack(Material.CHEST);
        pl.preview = show;
        return pl;
    }

    private boolean stillThere(Player p, int idx, ItemStack snap) {
        ItemStack cur = p.getInventory().getItem(idx);
        return cur != null && snap != null && cur.equals(snap);
    }

    public void doUpgrade(Player p, int idx, ItemStack snap) {
        if (!stillThere(p, idx, snap)) { plugin.lang.send(p, "accion.cambio"); return; }
        ItemStack cur = p.getInventory().getItem(idx);
        Plan pl = planUpgrade(p, cur);
        if (pl.deny != null) { plugin.lang.send(p, pl.deny); return; }
        if (!has(p, pl.cost)) { plugin.lang.send(p, "forja.faltan"); return; }
        take(p, pl.cost);
        int plus = plugin.items.plusOf(cur);
        CustomItem ci = plugin.items.of(cur);
        Profession prof = Profession.fromId(ci.oficio);
        if (rnd.nextDouble() * 100.0 < pl.chance) {
            p.getInventory().setItem(idx, plugin.items.rebuild(cur, plus + 1));
            p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.2f);
            plugin.lang.send(p, "mejora.exito", "n", plus + 1);
        } else {
            int np = Math.max(0, plus - plugin.getConfig().getInt("mejora.fallo_baja_niveles", 1));
            if (np != plus) p.getInventory().setItem(idx, plugin.items.rebuild(cur, np));
            p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
            plugin.lang.send(p, "mejora.fallo", "n", np);
        }
        plugin.professions.addXp(p, prof, plugin.getConfig().getInt("oficios.xp_mejorar", 10) * (plus + 1));
    }

    public void doRepair(Player p, int idx, ItemStack snap) {
        if (!stillThere(p, idx, snap)) { plugin.lang.send(p, "accion.cambio"); return; }
        ItemStack cur = p.getInventory().getItem(idx);
        Plan pl = planRepair(p, cur);
        if (pl.deny != null) { plugin.lang.send(p, pl.deny); return; }
        if (!has(p, pl.cost)) { plugin.lang.send(p, "forja.faltan"); return; }
        take(p, pl.cost);
        p.getInventory().setItem(idx, pl.preview);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1.4f);
        plugin.lang.send(p, "reparar.exito");
        CustomItem ci = plugin.items.of(cur);
        plugin.professions.addXp(p, Profession.fromId(ci.oficio), plugin.getConfig().getInt("oficios.xp_reparar", 6));
    }

    public void doSalvage(Player p, int idx, ItemStack snap) {
        if (!stillThere(p, idx, snap)) { plugin.lang.send(p, "accion.cambio"); return; }
        ItemStack cur = p.getInventory().getItem(idx);
        Plan pl = planSalvage(p, cur);
        if (pl.deny != null) { plugin.lang.send(p, pl.deny); return; }
        CustomItem ci = plugin.items.of(cur);
        Map<String, Integer> ret = salvageExpected(p, cur, ci, true);
        if (cur.getAmount() > 1) cur.setAmount(cur.getAmount() - 1);
        else p.getInventory().setItem(idx, null);
        refund(p, ret);
        p.playSound(p.getLocation(), Sound.BLOCK_GRINDSTONE_USE, 1f, 1f);
        plugin.lang.send(p, "desguace.exito");
        plugin.professions.addXp(p, Profession.fromId(ci.oficio), plugin.getConfig().getInt("oficios.xp_desguazar", 4));
    }
}
