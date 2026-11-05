package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.stream.Collectors;

/** GUI de una estación de trabajo: forjar, mejorar, reparar y desguazar. */
public final class ForgeMenu implements InventoryHolder {
    public enum Mode { FORJAR, MEJORAR, REPARAR, DESGUAZAR }

    private enum View { LISTA, DETALLE, PROGRESO }

    private static final class Session {
        Recipe recipe;
        ForgeService.Cost cost;
        Map<String, Integer> consumed;
        int total, elapsed;
        BukkitTask task;
    }

    private static final int INFO = 4, CAT = 6, BACK = 36, ACTION = 40, INFOBTN = 42, PREV = 45, PAGE = 49, NEXT = 53;
    private static final int[] ING = {19, 20, 21, 22, 23, 24, 25};

    private final ForjaPlugin plugin;
    private final ForgeService forge;
    private final Player player;
    private final String station;
    private final Profession prof;
    /** true en las "mesas virtuales" (mesa_materiales / mesa_objetos): mezclan recetas de varias estaciones. */
    private final boolean virtual;
    private final Location anchor;
    private Inventory inv;
    private Mode mode = Mode.FORJAR;
    private View view = View.LISTA;
    private int page = 0, cat = 0, selIdx = -1;
    private Recipe selected;
    private ItemStack selSnap;
    private final Map<Integer, Recipe> recipeSlots = new HashMap<>();
    private final Map<Integer, Integer> itemSlots = new HashMap<>();
    private Session session;

    private ForgeMenu(ForjaPlugin plugin, Player player, String station, Location anchor) {
        this.plugin = plugin;
        this.forge = plugin.forge;
        this.player = player;
        this.station = station;
        this.prof = Profession.fromStation(station);
        this.virtual = station.startsWith("mesa_");
        this.anchor = anchor;
    }

    /** Abre la GUI de una estación. anchor = ubicación de la estación (para interrumpir al alejarse). */
    public static void open(ForjaPlugin plugin, Player p, String station, Location anchor) {
        Profession pr = Profession.fromStation(station);
        boolean virt = station.startsWith("mesa_");
        if (pr == null && !virt) {
            plugin.lang.send(p, "estacion.desconocida", "e", station);
            return;
        }
        if (!p.hasPermission("forja.usar") || (pr != null && !p.hasPermission(pr.permission()))) {
            plugin.lang.send(p, "general.sin_permiso");
            return;
        }
        if (!plugin.professions.ready(p)) {
            plugin.lang.send(p, "general.cargando");
            return;
        }
        ForgeMenu old = plugin.menus.get(p.getUniqueId());
        if (old != null) old.cancelSession(null);
        ForgeMenu m = new ForgeMenu(plugin, p, station, anchor != null ? anchor : p.getLocation());
        m.inv = Bukkit.createInventory(m, 54, plugin.lang.get("gui.titulo", "estacion", plugin.lang.raw("estacion." + station)));
        plugin.menus.put(p.getUniqueId(), m);
        m.render();
        p.openInventory(m.inv);
        p.playSound(p.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.6f, 1.3f);
    }

    /** Recetas de las mesas virtuales: de las estaciones permitidas en config, separadas en materias primas / objetos. */
    static List<Recipe> virtualRecipes(ForjaPlugin plugin, boolean raw) {
        Set<String> allowed = new HashSet<>(plugin.getConfig().getStringList("mesa_crafteo.estaciones"));
        Set<String> rawCats = new HashSet<>(plugin.getConfig().getStringList("mesa_crafteo.categorias_materias_primas"));
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : plugin.recipes.all()) {
            if (allowed.contains(r.estacion) && rawCats.contains(r.categoria) == raw) out.add(r);
        }
        return out;
    }

    private List<Recipe> sourceRecipes() {
        return virtual ? virtualRecipes(plugin, station.equals("mesa_materiales")) : plugin.recipes.forStation(station);
    }

    @Override
    public Inventory getInventory() {
        return inv;
    }

    // ------------------------------------------------------------------ modos

    private List<Mode> modes() {
        if (virtual) return List.of(Mode.FORJAR);
        switch (station) {
            case "forja":
            case "alquimia":
            case "cocina":
                return List.of(Mode.FORJAR);
            case "joyeria":
                return List.of(Mode.FORJAR, Mode.DESGUAZAR);
            default:
                return List.of(Mode.FORJAR, Mode.MEJORAR, Mode.REPARAR, Mode.DESGUAZAR);
        }
    }

    private Material tabIcon(Mode m) {
        return switch (m) {
            case FORJAR -> Material.ANVIL;
            case MEJORAR -> Material.NETHER_STAR;
            case REPARAR -> Material.GRINDSTONE;
            case DESGUAZAR -> Material.IRON_PICKAXE;
        };
    }

    // ------------------------------------------------------------------ render

    private void render() {
        inv.clear();
        recipeSlots.clear();
        itemSlots.clear();
        for (int i = 0; i < 54; i++) inv.setItem(i, Gui.pane(Material.BLACK_STAINED_GLASS_PANE));
        List<Mode> ms = modes();
        for (int i = 0; i < ms.size(); i++) {
            Mode m = ms.get(i);
            ItemStack it = Gui.icon(tabIcon(m), plugin.lang.raw("gui.modo." + m.name().toLowerCase(Locale.ROOT)));
            if (m == mode) Gui.glow(it);
            inv.setItem(i, it);
        }
        inv.setItem(INFO, infoIcon());
        if (virtual && view == View.LISTA) inv.setItem(8, Gui.icon(Material.ARROW, plugin.lang.raw("gui.mesa.volver")));
        switch (view) {
            case LISTA -> renderList();
            case DETALLE -> renderDetail();
            case PROGRESO -> renderProgress();
        }
    }

    private ItemStack infoIcon() {
        PlayerData pd = plugin.professions.get(player);
        if (virtual) {
            List<String> l = new ArrayList<>();
            for (Profession p : Profession.values()) l.add(p.tag + p.display + " <gray>nv <white>" + pd.level(p));
            return Gui.icon(Material.BOOK, plugin.lang.raw("gui.mesa.oficios"), l);
        }
        int lvl = pd.level(prof), xp = pd.xp(prof), max = plugin.professions.maxLevel(), need = plugin.professions.xpNeeded(lvl);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Nivel: <white>" + lvl + "/" + max);
        lore.add(lvl >= max ? "<gold>¡Nivel máximo!" : Gui.bar(xp, need, 20) + " <gray>" + xp + "/" + need);
        RaceService.Info ri = plugin.races.get(player);
        lore.add(ri.has() ? "<gray>Raza: <white>" + plugin.races.name(ri.key()) + " <gray>nv <white>" + ri.level()
                : "<red>Sin raza: solo recetas básicas");
        ForgeService.ToolInfo t = forge.tools(player, prof);
        if (t.vel > 0) lore.add("<gray>Herramientas: <green>-" + (int) t.vel + "% tiempo");
        if (t.cal > 0) lore.add("<gray>Herramientas: <green>+" + (int) t.cal + " calidad");
        return Gui.icon(prof.icon, prof.tag + prof.display, lore);
    }

    private List<String> categories() {
        return sourceRecipes().stream().map(r -> r.categoria).distinct().sorted().collect(Collectors.toList());
    }

    private List<Recipe> visibleRecipes() {
        RaceService.Info ri = plugin.races.get(player);
        List<String> cats = categories();
        String cf = cat == 0 || cat > cats.size() ? null : cats.get(cat - 1);
        return sourceRecipes().stream()
                .filter(r -> plugin.races.eligibility(ri, r) != RaceService.Elig.DENIED)
                .filter(r -> cf == null || r.categoria.equals(cf))
                .sorted(Comparator.comparingInt((Recipe r) -> plugin.professions.unlocked(player, r) ? 0 : 1)
                        .thenComparingInt(r -> r.nivel).thenComparing(r -> r.id))
                .collect(Collectors.toList());
    }

    private void renderList() {
        if (mode == Mode.FORJAR) {
            List<String> cats = categories();
            String cn = cat == 0 || cat > cats.size() ? "Todas" : cats.get(cat - 1);
            inv.setItem(CAT, Gui.icon(Material.HOPPER, plugin.lang.raw("gui.categoria", "c", cn), "<gray>Clic para cambiar de categoría"));
            List<Recipe> list = visibleRecipes();
            int pages = Math.max(1, (list.size() + 35) / 36);
            page = Math.max(0, Math.min(page, pages - 1));
            for (int i = 0; i < 36; i++) {
                int idx = page * 36 + i;
                if (idx >= list.size()) break;
                inv.setItem(9 + i, recipeIcon(list.get(idx)));
                recipeSlots.put(9 + i, list.get(idx));
            }
            pager(pages);
        } else {
            ItemStack[] c = player.getInventory().getStorageContents();
            List<Integer> idxs = new ArrayList<>();
            for (int i = 0; i < c.length; i++) if (eligible(c[i])) idxs.add(i);
            int pages = Math.max(1, (idxs.size() + 35) / 36);
            page = Math.max(0, Math.min(page, pages - 1));
            for (int i = 0; i < 36; i++) {
                int k = page * 36 + i;
                if (k >= idxs.size()) break;
                inv.setItem(9 + i, Gui.withLore(c[idxs.get(k)], List.of("", "<yellow>Clic para seleccionar")));
                itemSlots.put(9 + i, idxs.get(k));
            }
            if (idxs.isEmpty()) {
                inv.setItem(22, Gui.icon(Material.BARRIER, plugin.lang.raw("gui.sin_objetos"),
                        "<gray>Lleva el objeto en tu inventario."));
            }
            pager(pages);
        }
    }

    private void pager(int pages) {
        if (page > 0) inv.setItem(PREV, Gui.icon(Material.ARROW, "<yellow>« Anterior"));
        if (page < pages - 1) inv.setItem(NEXT, Gui.icon(Material.ARROW, "<yellow>Siguiente »"));
        inv.setItem(PAGE, Gui.icon(Material.PAPER, "<gray>Página " + (page + 1) + "/" + pages));
    }

    private boolean eligible(ItemStack st) {
        CustomItem ci = plugin.items.of(st);
        if (ci == null || ci.oficio == null || Profession.fromId(ci.oficio) != prof) return false;
        switch (mode) {
            case MEJORAR:
                return ci.isEquipment() && plugin.items.plusOf(st) < plugin.getConfig().getInt("mejora.max", 5);
            case REPARAR:
                return st.getItemMeta() instanceof Damageable d && d.getDamage() > 0 && st.getType().getMaxDurability() > 0;
            case DESGUAZAR:
                return !ci.categoria.equals("material") && !ci.categoria.equals("estacion") && plugin.recipes.byResult(ci.id) != null;
            default:
                return false;
        }
    }

    private ItemStack recipeIcon(Recipe r) {
        CustomItem ci = plugin.items.get(r.resultado);
        ItemStack base = plugin.items.create(ci, r.cantidad, Quality.COMUN, 0, 1.0, null, null, null);
        boolean ok = plugin.professions.unlocked(player, r);
        RaceService.Elig el = plugin.races.eligibility(plugin.races.get(player), r);
        List<String> ex = new ArrayList<>();
        ex.add("");
        if (virtual) ex.add("<gray>Oficio: <white>" + r.prof.display);
        ex.add("<gray>Nivel: " + (ok ? "<green>" : "<red>") + r.nivel + " <gray>| Tiempo: <white>" + r.tiempo + "s <gray>| XP: <white>" + r.xp);
        if (el == RaceService.Elig.FOREIGN) ex.add("<red>Raza ajena: más materiales y -1 calidad");
        ex.add(ok ? "<yellow>Clic para ver la receta" : "<red>Bloqueada hasta nivel " + r.nivel);
        return Gui.withLore(base, ex);
    }

    // ------------------------------------------------------------------ detalle

    private ForgeService.Plan plan(ItemStack cur) {
        return switch (mode) {
            case MEJORAR -> forge.planUpgrade(player, cur);
            case REPARAR -> forge.planRepair(player, cur);
            default -> forge.planSalvage(player, cur);
        };
    }

    private void renderDetail() {
        inv.setItem(BACK, Gui.icon(Material.ARROW, plugin.lang.raw("gui.volver")));
        if (mode == Mode.FORJAR) {
            Recipe r = selected;
            ForgeService.Cost cost = forge.cost(player, r);
            CustomItem ci = plugin.items.get(r.resultado);
            ItemStack res = plugin.items.create(ci, r.cantidad, Quality.COMUN, 0, 1.0, null, null, null);
            List<String> ex = new ArrayList<>();
            ex.add("");
            ex.add("<gray>Tiempo: <white>" + (forge.ticks(player, r) / 20.0) + "s <gray>| XP: <white>" + r.xp);
            if (ci.isRpg()) {
                ex.add("<gray>Se entrega con tus habilidades y tu <white>color de estilo<gray> (rpg_color).");
                int need = ci.rpgInt("nivel_xp", 0);
                if (need > 0) ex.add("<gray>Nivel de XP del datapack RPG: <white>" + need);
            }
            if (ci.usesQuality()) {
                double[] w = forge.chances(forge.score(player, r, r.estacion), plugin.professions.get(player).level(r.prof));
                ex.add("<gray>Probabilidad de calidad:");
                for (int i = 0; i < 5; i++) {
                    if (w[i] >= 0.5) ex.add(" " + Quality.of(i).tag + Quality.of(i).display + " <white>" + Math.round(w[i]) + "%");
                }
                if (cost.elig == RaceService.Elig.FOREIGN) ex.add("<red>Raza ajena: -1 nivel de calidad");
            }
            inv.setItem(13, Gui.withLore(res, ex));
            boolean all = drawIngredients(cost);
            boolean ok = all && plugin.professions.unlocked(player, r) && cost.elig != RaceService.Elig.DENIED;
            inv.setItem(ACTION, ok
                    ? Gui.icon(Material.ANVIL, plugin.lang.raw("gui.forjar"), "<gray>Clic para comenzar")
                    : Gui.icon(Material.BARRIER, plugin.lang.raw("gui.faltan")));
        } else {
            ItemStack cur = player.getInventory().getItem(selIdx);
            if (cur == null || !cur.equals(selSnap)) {
                view = View.LISTA;
                renderList();
                return;
            }
            ForgeService.Plan pl = plan(cur);
            inv.setItem(11, cur.clone());
            if (mode == Mode.DESGUAZAR) {
                List<String> l = new ArrayList<>();
                for (Map.Entry<String, Integer> e : pl.returns.entrySet()) l.add("<gray>≈ <white>" + e.getValue() + "x " + forge.nameOf(e.getKey()));
                if (l.isEmpty()) l.add("<gray>Poca cosa aprovechable");
                inv.setItem(15, Gui.icon(Material.CHEST, plugin.lang.raw("gui.recuperas"), l));
            } else if (pl.preview != null) {
                inv.setItem(15, pl.preview.clone());
            }
            boolean all = drawIngredients(pl.cost);
            if (!pl.info.isEmpty()) inv.setItem(INFOBTN, Gui.icon(Material.PAPER, plugin.lang.raw("gui.informacion"), pl.info));
            boolean ok = pl.deny == null && all;
            if (ok) {
                inv.setItem(ACTION, Gui.icon(mode == Mode.DESGUAZAR ? Material.TNT : Material.ANVIL,
                        plugin.lang.raw("gui.confirmar." + mode.name().toLowerCase(Locale.ROOT)), "<gray>Clic para confirmar"));
            } else {
                inv.setItem(ACTION, Gui.icon(Material.BARRIER, pl.deny != null ? plugin.lang.raw(pl.deny) : plugin.lang.raw("gui.faltan")));
            }
        }
    }

    /** Dibuja ingredientes con panel verde/rojo. Devuelve true si el jugador tiene todo. */
    private boolean drawIngredients(ForgeService.Cost cost) {
        boolean all = true;
        int i = 0;
        for (Map.Entry<String, Integer> e : cost.needed.entrySet()) {
            String k = e.getKey();
            int n = e.getValue();
            int have = forge.count(player, k);
            boolean ok = have >= n;
            all &= ok;
            if (i < ING.length) {
                ItemStack ic = Gui.withLore(forge.iconFor(k, n), List.of(
                        "<gray>Necesitas: <white>" + n + (cost.keep.contains(k) ? " <dark_gray>(no se consume)" : ""),
                        (ok ? "<green>" : "<red>") + "Tienes: " + have));
                inv.setItem(ING[i], ic);
                inv.setItem(ING[i] + 9, Gui.icon(ok ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                        (ok ? "<green>✔ " : "<red>✘ ") + forge.nameOf(k), "<gray>" + have + "/" + n));
            }
            i++;
        }
        return all;
    }

    private void renderProgress() {
        Session s = session;
        if (s == null) {
            view = View.DETALLE;
            renderDetail();
            return;
        }
        CustomItem ci = plugin.items.get(s.recipe.resultado);
        inv.setItem(13, plugin.items.create(ci, s.recipe.cantidad, Quality.COMUN, 0, 1.0, null, null, null));
        inv.setItem(ACTION, Gui.icon(Material.RED_CONCRETE, plugin.lang.raw("gui.cancelar"), "<gray>Recuperas los materiales"));
        updateBar();
    }

    private void updateBar() {
        Session s = session;
        if (s == null) return;
        int filled = (int) Math.floor(9.0 * s.elapsed / Math.max(1, s.total));
        int pct = (int) Math.round(100.0 * s.elapsed / Math.max(1, s.total));
        for (int i = 0; i < 9; i++) {
            inv.setItem(45 + i, i < filled
                    ? Gui.icon(Material.LIME_STAINED_GLASS_PANE, "<green>Forjando… " + pct + "%")
                    : Gui.icon(Material.GRAY_STAINED_GLASS_PANE, "<gray>Forjando… " + pct + "%"));
        }
    }

    // ------------------------------------------------------------------ clics

    public void click(InventoryClickEvent e) {
        e.setCancelled(true);
        if (e.getClickedInventory() == null || e.getClickedInventory() != inv) return;
        int slot = e.getRawSlot();
        List<Mode> ms = modes();
        if (slot >= 0 && slot < ms.size()) {
            if (session != null) return;
            mode = ms.get(slot);
            view = View.LISTA;
            page = 0;
            selected = null;
            selIdx = -1;
            render();
            return;
        }
        switch (view) {
            case LISTA -> clickList(slot);
            case DETALLE -> clickDetail(slot);
            case PROGRESO -> {
                if (slot == ACTION) {
                    cancelSession("forja.cancelada");
                    view = View.DETALLE;
                    render();
                }
            }
        }
    }

    private void clickList(int slot) {
        if (virtual && slot == 8) {
            final Location a = anchor;
            Bukkit.getScheduler().runTask(plugin, () -> TableHub.open(plugin, player, a == null ? null : a.getBlock().getLocation()));
            return;
        }
        if (slot == PREV && page > 0) { page--; render(); return; }
        if (slot == NEXT) { page++; render(); return; }
        if (slot == CAT && mode == Mode.FORJAR) {
            cat = (cat + 1) % (categories().size() + 1);
            page = 0;
            render();
            return;
        }
        if (mode == Mode.FORJAR) {
            Recipe r = recipeSlots.get(slot);
            if (r == null) return;
            if (!plugin.professions.unlocked(player, r)) {
                plugin.lang.send(player, "forja.bloqueada", "n", r.nivel);
                return;
            }
            selected = r;
            view = View.DETALLE;
            render();
        } else {
            Integer idx = itemSlots.get(slot);
            if (idx == null) return;
            ItemStack cur = player.getInventory().getItem(idx);
            if (cur == null) return;
            selIdx = idx;
            selSnap = cur.clone();
            view = View.DETALLE;
            render();
        }
    }

    private void clickDetail(int slot) {
        if (slot == BACK) {
            view = View.LISTA;
            render();
            return;
        }
        if (slot != ACTION) return;
        if (mode == Mode.FORJAR) {
            startForge();
            return;
        }
        switch (mode) {
            case MEJORAR -> forge.doUpgrade(player, selIdx, selSnap);
            case REPARAR -> forge.doRepair(player, selIdx, selSnap);
            default -> forge.doSalvage(player, selIdx, selSnap);
        }
        view = View.LISTA;
        render();
    }

    // ------------------------------------------------------------------ sesión de forja

    private void startForge() {
        Recipe r = selected;
        if (r == null) return;
        if (!plugin.professions.unlocked(player, r)) {
            plugin.lang.send(player, "forja.bloqueada", "n", r.nivel);
            return;
        }
        ForgeService.Cost cost = forge.cost(player, r);
        if (cost.elig == RaceService.Elig.DENIED) {
            plugin.lang.send(player, "forja.raza_denegada");
            return;
        }
        if (!forge.has(player, cost)) {
            plugin.lang.send(player, "forja.faltan");
            render();
            return;
        }
        if (!player.hasPermission(r.prof.permission())) {
            plugin.lang.send(player, "general.sin_permiso");
            return;
        }
        CustomItem rc = plugin.items.get(r.resultado);
        if (rc != null && rc.isRpg()) {
            if (!plugin.rpg.hasFreeSlot(player)) {
                plugin.lang.send(player, "forja.inventario_lleno");
                return;
            }
            int need = plugin.rpg.gateNeeded(player, rc);
            if (need >= 0) {
                plugin.lang.send(player, "forja.gate", "n", need);
                return;
            }
        }
        Session s = new Session();
        s.recipe = r;
        s.cost = cost;
        s.consumed = forge.take(player, cost);
        s.total = forge.ticks(player, r);
        session = s;
        view = View.PROGRESO;
        render();
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    private void tick() {
        Session s = session;
        if (s == null) return;
        if (!player.isOnline() || player.isDead() || player.getOpenInventory().getTopInventory().getHolder() != this) {
            cancelSession("forja.interrumpida");
            return;
        }
        double max = plugin.getConfig().getDouble("forja.distancia_max", 6.0);
        Location pl = player.getLocation();
        if (anchor != null && (pl.getWorld() != anchor.getWorld() || pl.distanceSquared(anchor) > max * max)) {
            cancelSession("forja.te_alejaste");
            player.closeInventory();
            return;
        }
        s.elapsed += 2;
        if (s.elapsed % 10 == 0) player.playSound(player.getLocation(), stationSound(), 0.5f, 1.0f + (s.elapsed % 20) / 40f);
        if (s.elapsed >= s.total) {
            finishSession();
            return;
        }
        updateBar();
    }

    private Sound stationSound() {
        return switch (station) {
            case "yunque" -> Sound.BLOCK_ANVIL_PLACE;
            case "mesa_materiales", "mesa_objetos" -> Sound.BLOCK_WOOD_PLACE;
            case "forja" -> Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE;
            case "armeria" -> Sound.BLOCK_WOOL_PLACE;
            case "carpinteria" -> Sound.BLOCK_WOOD_PLACE;
            case "joyeria" -> Sound.BLOCK_AMETHYST_BLOCK_CHIME;
            case "alquimia" -> Sound.BLOCK_BREWING_STAND_BREW;
            default -> Sound.BLOCK_SMOKER_SMOKE;
        };
    }

    private void finishSession() {
        Session s = session;
        session = null;
        if (s.task != null) s.task.cancel();
        ForgeService.Result res = forge.finish(player, s.recipe, s.cost, s.recipe.estacion);
        if (res == null) {
            forge.refund(player, s.consumed);
            plugin.lang.send(player, "forja.rpg_error");
            view = View.DETALLE;
            if (player.getOpenInventory().getTopInventory().getHolder() == this) render();
            return;
        }
        CustomItem ci = plugin.items.get(s.recipe.resultado);
        String q = ci.usesQuality() ? " <gray>[" + res.quality.tag + res.quality.display + "<gray>]" : "";
        plugin.lang.send(player, "forja.listo", "item", s.recipe.cantidad + "x " + ItemRegistry.rarityTag(ci.rareza) + ci.nombre + q);
        player.playSound(player.getLocation(),
                res.quality == Quality.MAESTRA ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.BLOCK_ANVIL_USE, 1f, 1f);
        view = View.DETALLE;
        if (player.getOpenInventory().getTopInventory().getHolder() == this) render();
    }

    /** Cancela la forja en curso devolviendo los materiales. */
    public void cancelSession(String msgKey) {
        Session s = session;
        if (s == null) return;
        session = null;
        if (s.task != null) s.task.cancel();
        forge.refund(player, s.consumed);
        if (msgKey != null) plugin.lang.send(player, msgKey);
    }

    public void closed() {
        cancelSession("forja.interrumpida");
        plugin.menus.remove(player.getUniqueId(), this);
    }
}
