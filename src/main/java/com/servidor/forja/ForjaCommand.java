package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/** /forja (admin) */
public final class ForjaCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("dar", "recargar", "nivel", "receta", "debug", "lista", "abrir");
    private static final List<String> STATIONS = List.of("yunque", "forja", "armeria", "carpinteria", "joyeria", "alquimia", "cocina", "cartografia", "canteria", "afilado", "compostera", "mesa");
    private final ForjaPlugin plugin;

    public ForjaCommand(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("forja.admin")) {
            plugin.lang.send(s, "general.sin_permiso");
            return true;
        }
        if (a.length == 0) {
            plugin.lang.send(s, "admin.ayuda");
            return true;
        }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "recargar" -> {
                plugin.reloadAll();
                plugin.lang.send(s, "admin.recargado", "items", plugin.items.all().size(), "recetas", plugin.recipes.all().size());
            }
            case "dar" -> dar(s, a);
            case "nivel" -> nivel(s, a);
            case "receta" -> receta(s, a);
            case "debug" -> debug(s);
            case "lista" -> lista(s, a);
            case "abrir" -> abrir(s, a);
            default -> plugin.lang.send(s, "admin.ayuda");
        }
        return true;
    }

    private void dar(CommandSender s, String[] a) {
        if (a.length < 3) { plugin.lang.send(s, "admin.uso_dar"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { plugin.lang.send(s, "admin.jugador_no"); return; }
        CustomItem ci = plugin.items.get(a[2]);
        if (ci == null) { plugin.lang.send(s, "admin.item_no", "id", a[2]); return; }
        int n = 1;
        if (a.length > 3) {
            try { n = Math.max(1, Math.min(2304, Integer.parseInt(a[3]))); } catch (NumberFormatException ex) { plugin.lang.send(s, "admin.numero_no"); return; }
        }
        if (ci.categoria.equals("mochila")) {   // una por una: cada mochila lleva su propio id
            for (int i = 0; i < n; i++) plugin.forge.give(t, plugin.items.basic(ci.id, 1));
            plugin.lang.send(s, "admin.dado", "n", n, "item", ci.nombre, "jugador", t.getName());
            return;
        }
        if (ci.isRpg()) {
            int given = 0;
            for (int i = 0; i < n; i++) {
                if (!plugin.rpg.hasFreeSlot(t) || plugin.rpg.give(t, ci, Quality.COMUN, 0, null, null) == null) break;
                given++;
            }
            plugin.lang.send(s, "admin.dado", "n", given, "item", ci.nombre, "jugador", t.getName());
            return;
        }
        int left = n;
        while (left > 0) {
            ItemStack probe = plugin.items.basic(ci.id, 1);
            int chunk = Math.min(left, Math.max(1, probe.getMaxStackSize()));
            plugin.forge.give(t, plugin.items.basic(ci.id, chunk));
            left -= chunk;
        }
        plugin.lang.send(s, "admin.dado", "n", n, "item", ci.nombre, "jugador", t.getName());
    }

    private void nivel(CommandSender s, String[] a) {
        if (a.length < 4) { plugin.lang.send(s, "admin.uso_nivel"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        Profession pr = Profession.fromId(a[2]);
        if (t == null) { plugin.lang.send(s, "admin.jugador_no"); return; }
        if (pr == null) { plugin.lang.send(s, "admin.oficio_no"); return; }
        try {
            plugin.professions.setLevel(t.getUniqueId(), pr, Integer.parseInt(a[3]));
        } catch (NumberFormatException ex) {
            plugin.lang.send(s, "admin.numero_no");
            return;
        }
        plugin.lang.send(s, "admin.nivel_ok", "jugador", t.getName(), "oficio", pr.display, "n", plugin.professions.get(t).level(pr));
    }

    private void receta(CommandSender s, String[] a) {
        if (a.length < 4) { plugin.lang.send(s, "admin.uso_receta"); return; }
        Player t = Bukkit.getPlayerExact(a[2]);
        if (t == null) { plugin.lang.send(s, "admin.jugador_no"); return; }
        Recipe r = plugin.recipes.get(a[3]);
        if (r == null) { plugin.lang.send(s, "admin.receta_no", "id", a[3]); return; }
        switch (a[1].toLowerCase(Locale.ROOT)) {
            case "dar" -> plugin.professions.setFlag(t.getUniqueId(), r.id, "G");
            case "quitar" -> plugin.professions.setFlag(t.getUniqueId(), r.id, "R");
            case "reset" -> plugin.professions.setFlag(t.getUniqueId(), r.id, null);
            default -> { plugin.lang.send(s, "admin.uso_receta"); return; }
        }
        plugin.lang.send(s, "admin.receta_ok", "receta", r.id, "jugador", t.getName(), "accion", a[1]);
    }

    private void debug(CommandSender s) {
        boolean on = !plugin.getConfig().getBoolean("general.debug", false);
        plugin.getConfig().set("general.debug", on);
        plugin.lang.send(s, on ? "admin.debug_on" : "admin.debug_off");
        if (s instanceof Player p) {
            ItemStack it = p.getInventory().getItemInMainHand();
            CustomItem ci = plugin.items.of(it);
            RaceService.Info ri = plugin.races.get(p);
            plugin.lang.send(p, "admin.debug_raza", "id", ri.id(), "key", ri.key(), "nivel", ri.level(), "linaje", ri.lineage());
            if (ci == null) {
                plugin.lang.send(p, "admin.debug_sin_item");
            } else {
                plugin.lang.send(p, "admin.debug_item", "id", ci.id, "cmd", ci.cmd, "calidad", plugin.items.qualityOf(it).display,
                        "plus", plugin.items.plusOf(it), "auth", plugin.items.isAuthentic(it));
            }
            plugin.lang.send(p, "admin.debug_mesas", "modo", plugin.getConfig().getString("mesas.modo", "todas"), "n", plugin.workbenches.count(),
                    "estilo", plugin.guiStyle.custom() ? "custom (resourcepack de Forja)" : "vanilla");
            org.bukkit.block.Block tb = p.getTargetBlockExact(6);
            if (tb != null) plugin.lang.send(p, "admin.debug_bloque", "b", tb.getType().name(), "m", plugin.workbenches.describe(tb.getType()));
            plugin.lang.send(p, "admin.debug_datos", "estaciones", plugin.stations.count(), "listo", plugin.professions.ready(p));
        }
    }

    private void lista(CommandSender s, String[] a) {
        String what = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "items";
        String filter = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : null;
        if (what.startsWith("rec")) {
            Map<String, List<String>> by = new TreeMap<>();
            for (Recipe r : plugin.recipes.all()) {
                if (filter != null && !r.estacion.equals(filter) && !r.oficio.equals(filter)) continue;
                by.computeIfAbsent(r.estacion, k -> new ArrayList<>()).add(r.id);
            }
            by.forEach((k, v) -> s.sendMessage(Lang.mm("<gold>" + k + " <gray>(" + v.size() + "): <white>" + String.join(", ", v))));
        } else {
            Map<String, List<String>> by = new TreeMap<>();
            for (CustomItem ci : plugin.items.all()) {
                if (filter != null && !ci.categoria.equals(filter)) continue;
                by.computeIfAbsent(ci.categoria, k -> new ArrayList<>()).add(ci.id);
            }
            by.forEach((k, v) -> s.sendMessage(Lang.mm("<gold>" + k + " <gray>(" + v.size() + "): <white>" + String.join(", ", v))));
        }
    }

    private void abrir(CommandSender s, String[] a) {
        if (a.length < 2) { plugin.lang.send(s, "admin.uso_abrir"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { plugin.lang.send(s, "admin.jugador_no"); return; }
        String st = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "yunque";
        if (st.equals("mesa")) {
            TableHub.open(plugin, t, t.getLocation().getBlock().getLocation(), new MesaDef("CRAFTING_TABLE", "mesa", "", "workbench"));
            return;
        }
        ForgeMenu.open(plugin, t, st, null);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        if (!s.hasPermission("forja.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (a.length == 1) out.addAll(SUBS);
        else {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (a.length == 2) {
                switch (sub) {
                    case "dar", "nivel", "abrir" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                    case "receta" -> out.addAll(List.of("dar", "quitar", "reset"));
                    case "lista" -> out.addAll(List.of("items", "recetas"));
                    default -> { }
                }
            } else if (a.length == 3) {
                switch (sub) {
                    case "dar" -> plugin.items.all().forEach(i -> out.add(i.id));
                    case "nivel" -> Arrays.stream(Profession.values()).forEach(p -> out.add(p.id));
                    case "receta" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                    case "abrir" -> out.addAll(STATIONS);
                    default -> { }
                }
            } else if (a.length == 4 && sub.equals("receta")) {
                plugin.recipes.all().forEach(r -> out.add(r.id));
            }
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        return out.stream().filter(x -> x.toLowerCase(Locale.ROOT).startsWith(last)).limit(80).collect(Collectors.toList());
    }
}
