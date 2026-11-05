package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.SiegeItems;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /gremioadmin ... */
public class GremioAdminCommand implements CommandExecutor, TabCompleter {
    private final GremiosPlugin plugin;

    public GremioAdminCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        if (!s.hasPermission("gremios.admin")) {
            s.sendMessage("§cSin permiso.");
            return true;
        }
        if (args.length == 0) {
            help(s);
            return true;
        }
        Player p = s instanceof Player pl ? pl : null;
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "panel" -> {
                if (p == null) s.sendMessage("§cSolo jugadores.");
                else plugin.guis.admin(p);
            }
            case "moneda" -> currency(s, p, args);
            case "permitir" -> {
                OfflinePlayer t = target(s, args, 1);
                if (t == null) return true;
                plugin.guilds.setCanCreate(t.getUniqueId(), true);
                s.sendMessage("§a" + t.getName() + " §7puede fundar un gremio.");
                Player on = t.getPlayer();
                if (on != null) Msg.to(on, "§aUn administrador te ha dado permiso para fundar un gremio. Usa §f/gremio§a.");
            }
            case "quitar" -> {
                OfflinePlayer t = target(s, args, 1);
                if (t == null) return true;
                plugin.guilds.setCanCreate(t.getUniqueId(), false);
                s.sendMessage("§7Permiso retirado a " + t.getName() + ".");
            }
            case "bypass" -> {
                if (p == null) {
                    s.sendMessage("§cSolo jugadores.");
                    return true;
                }
                if (!plugin.bypass.remove(p.getUniqueId())) {
                    plugin.bypass.add(p.getUniqueId());
                    Msg.to(p, "§aBypass de protección ACTIVADO.");
                } else Msg.to(p, "§7Bypass de protección desactivado.");
            }
            case "evento", "eventos" -> new EventoCommand(plugin).run(s, args);
            case "zona", "zonas" -> new ZonaCommand(plugin).run(s, args);
            case "mision", "misiones" -> new MisionCommand(plugin).run(s, args);
            case "tablon" -> new TablonCommand(plugin).run(s, args);
            case "diagnostico", "diag" -> new DiagnosticCommand(plugin).run(s);
            case "dar" -> give(s, args);
            case "raid" -> raid(s, args);
            case "xp" -> {
                Guild g = guild(s, args, 1);
                if (g == null) return true;
                long n = num(s, args, 2);
                if (n < 0) return true;
                plugin.guilds.addXp(g, n);
                plugin.guilds.save();
                s.sendMessage("§a+" + n + " XP a " + g.name + " (nivel " + g.level + ").");
            }
            case "nivel" -> {
                Guild g = guild(s, args, 1);
                if (g == null) return true;
                long n = num(s, args, 2);
                if (n < 1 || n > Guild.MAX_LEVEL) {
                    s.sendMessage("§cNivel 1-" + Guild.MAX_LEVEL);
                    return true;
                }
                g.level = (int) n;
                g.xp = 0;
                plugin.guilds.save();
                s.sendMessage("§aNivel de " + g.name + " = " + n);
            }
            case "banco" -> {
                Guild g = guild(s, args, 1);
                if (g == null) return true;
                long n;
                try {
                    n = Long.parseLong(args.length > 2 ? args[2] : "x");
                } catch (NumberFormatException ex) {
                    s.sendMessage("§cCantidad inválida (puede ser negativa).");
                    return true;
                }
                g.bank = Math.max(0, g.bank + n);
                g.log("§d" + (n >= 0 ? "+" : "") + n + " §7admin");
                plugin.guilds.save();
                s.sendMessage("§aBanco de " + g.name + ": " + g.bank);
            }
            case "disolver" -> {
                Guild g = guild(s, args, 1);
                if (g == null) return true;
                String n = g.name;
                plugin.guilds.disband(g);
                Bukkit.broadcastMessage(Msg.PREFIX + "§7El gremio §e" + n + " §7ha sido disuelto por un administrador.");
            }
            case "reload" -> {
                plugin.reloadConfig();
                s.sendMessage("§aConfiguración recargada. (Para cambios en layouts.yml/recursos reinicia el servidor.)");
            }
            default -> help(s);
        }
        return true;
    }

    private void currency(CommandSender s, Player p, String[] args) {
        String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (a) {
            case "set" -> {
                if (p == null) {
                    s.sendMessage("§cSolo jugadores.");
                    return;
                }
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (hand == null || hand.getType().isAir()) {
                    Msg.to(p, "§cSostén el objeto que será la moneda.");
                    return;
                }
                plugin.currency.set(hand);
                Msg.to(p, "§aMoneda definida: §f" + plugin.currency.name() + "§a. Se compara el objeto exacto (nombre, modelo y datos).");
            }
            case "dar" -> {
                if (p == null || !plugin.currency.isSet()) {
                    s.sendMessage("§cDefine la moneda primero (y usa esto como jugador).");
                    return;
                }
                long n = num(s, args, 2);
                if (n <= 0) return;
                plugin.currency.give(p, n);
                Msg.to(p, "§aRecibiste §e" + plugin.currency.fmt(n) + "§a.");
            }
            default -> s.sendMessage(plugin.currency.isSet() ? "§7Moneda actual: §f" + plugin.currency.name() + " §7(" + plugin.currency.material() + ")"
                    : "§cNo hay moneda definida. Sostén el objeto y usa §f/gremioadmin moneda set§c.");
        }
    }

    private void give(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage("§cUso: /gremioadmin dar <jugador> <canon|catapulta|MUNICION> [cantidad]");
            return;
        }
        Player t = Bukkit.getPlayerExact(args[1]);
        if (t == null) {
            s.sendMessage("§cJugador no conectado.");
            return;
        }
        int n = 1;
        if (args.length > 3) {
            try {
                n = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
            }
        }
        String id = args[2].toUpperCase(Locale.ROOT);
        ItemStack it = null;
        if (id.equals("CANON") || id.equals("CAÑON") || id.equals("CAÑÓN")) it = SiegeItems.unitItem(plugin, SiegeItems.UnitType.CANON);
        else if (id.equals("CATAPULTA")) it = SiegeItems.unitItem(plugin, SiegeItems.UnitType.CATAPULTA);
        else {
            try {
                it = SiegeItems.ammoItem(plugin, SiegeItems.Ammo.valueOf(id), n);
                n = 1;
            } catch (IllegalArgumentException ex) {
                s.sendMessage("§cOpciones: canon, catapulta, " + Arrays.toString(SiegeItems.Ammo.values()));
                return;
            }
        }
        for (int i = 0; i < n; i++) for (ItemStack o : t.getInventory().addItem(it.clone()).values()) t.getWorld().dropItemNaturally(t.getLocation(), o);
        s.sendMessage("§aEntregado.");
    }

    private void raid(CommandSender s, String[] args) {
        String a = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (a) {
            case "forzar" -> {
                if (args.length < 4) {
                    s.sendMessage("§cUso: /gremioadmin raid forzar <gremioAtacante> <territorio>");
                    return;
                }
                Guild g = plugin.guilds.byName(args[2]);
                Territory t = plugin.territories.get(args[3]);
                if (g == null || t == null) {
                    s.sendMessage("§cGremio o territorio no encontrado.");
                    return;
                }
                String err = plugin.raids.forceStart(g, t);
                s.sendMessage(err == null ? "§aAsedio iniciado." : "§c" + err);
            }
            case "prueba" -> {
                if (args.length < 3) {
                    s.sendMessage("§cUso: /gremioadmin raid prueba <territorio> [minutos]  (territorio sin dueño)");
                    return;
                }
                Territory t = plugin.territories.get(args[2]);
                if (t == null) {
                    s.sendMessage("§cTerritorio no encontrado.");
                    return;
                }
                int min = 20;
                if (args.length > 3) {
                    try {
                        min = Math.max(1, Math.min(240, Integer.parseInt(args[3])));
                    } catch (NumberFormatException ex) {
                        s.sendMessage("§cMinutos inválidos.");
                        return;
                    }
                }
                String err = plugin.raids.startTest(t, min);
                s.sendMessage(err == null ? "§aRaid de prueba iniciada. Usa §f/gremioadmin dar <tú> canon§a, coloca el arma, apunta (clic derecho) y dispara (clic izquierdo)." : "§c" + err);
            }
            case "terminar" -> {
                if (args.length < 4) {
                    s.sendMessage("§cUso: /gremioadmin raid terminar <territorio> <atacante|defensor|saqueo|cancelar>");
                    return;
                }
                Territory t = plugin.territories.get(args[2]);
                if (t == null) {
                    s.sendMessage("§cTerritorio no encontrado.");
                    return;
                }
                String err = plugin.raids.forceEnd(t, args[3]);
                s.sendMessage(err == null ? "§aHecho." : "§c" + err);
            }
            case "historial" -> {
                var list = plugin.history.recent(args.length > 2 ? args[2] : null, 15);
                if (list.isEmpty()) s.sendMessage("§7Sin asedios en el historial.");
                for (var e : list) s.sendMessage(plugin.history.line(e));
            }
            case "lista" -> {
                if (plugin.raids.all().isEmpty()) s.sendMessage("§7No hay asedios.");
                for (var r : plugin.raids.all()) s.sendMessage("§e" + r.territoryId + " §7" + r.attackerName + " → " + r.defenderName + " §8| " + r.state);
            }
            default -> s.sendMessage("§c/gremioadmin raid <prueba|forzar|terminar|lista|historial> ...");
        }
    }

    private OfflinePlayer target(CommandSender s, String[] args, int idx) {
        if (args.length <= idx) {
            s.sendMessage("§cFalta el jugador.");
            return null;
        }
        Player on = Bukkit.getPlayerExact(args[idx]);
        if (on != null) return on;
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(args[idx]);
        if (off == null) s.sendMessage("§cJugador desconocido (debe haber entrado al servidor).");
        return off;
    }

    private Guild guild(CommandSender s, String[] args, int idx) {
        if (args.length <= idx) {
            s.sendMessage("§cFalta el gremio.");
            return null;
        }
        Guild g = plugin.guilds.byName(args[idx]);
        if (g == null) s.sendMessage("§cGremio no encontrado.");
        return g;
    }

    private long num(CommandSender s, String[] args, int idx) {
        try {
            return Long.parseLong(args[idx]);
        } catch (Exception ex) {
            s.sendMessage("§cNúmero inválido.");
            return -1;
        }
    }

    private void help(CommandSender s) {
        s.sendMessage("§6/gremioadmin §emoneda set §7(objeto en la mano) | info | dar <n>");
        s.sendMessage("§6/gremioadmin §epermitir|quitar <jugador> §7- permiso para fundar gremio");
        s.sendMessage("§6/gremioadmin §ebypass §7- ignora protecciones");
        s.sendMessage("§6/gremioadmin §edar <jugador> <canon|catapulta|MUNICION> [n]");
        s.sendMessage("§6/gremioadmin §eraid prueba|forzar|terminar|lista ...");
        s.sendMessage("§6/gremioadmin §exp|nivel|banco <gremio> <n> §7| §edisolver <gremio> §7| §epanel §7| §ereload");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("gremios.admin")) return out;
        if (args.length == 1) {
            for (String x : List.of("panel", "moneda", "permitir", "quitar", "bypass", "dar", "raid", "xp", "nivel", "banco", "disolver", "reload", "evento", "zona", "mision", "tablon", "diagnostico")) {
                if (x.startsWith(args[0].toLowerCase())) out.add(x);
            }
        } else if (args.length == 2) {
            String s = args[0].toLowerCase();
            if (s.equals("moneda")) out.addAll(List.of("set", "info", "dar"));
            else if (s.equals("evento")) out.addAll(EventoCommand.SUBS);
            else if (s.equals("zona")) out.addAll(ZonaCommand.SUBS);
            else if (s.equals("mision")) out.addAll(MisionCommand.SUBS);
            else if (s.equals("tablon")) out.addAll(TablonCommand.SUBS);
            else if (s.equals("raid")) out.addAll(List.of("prueba", "forzar", "terminar", "lista", "historial"));
            else if (s.equals("permitir") || s.equals("quitar") || s.equals("dar")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            } else if (s.equals("xp") || s.equals("nivel") || s.equals("banco") || s.equals("disolver")) {
                for (Guild g : plugin.guilds.all()) if (g.name.toLowerCase().startsWith(args[1].toLowerCase())) out.add(g.name);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("dar")) {
            out.addAll(List.of("canon", "catapulta"));
            for (SiegeItems.Ammo a : SiegeItems.Ammo.values()) out.add(a.name());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("raid")) {
            if (args[1].equalsIgnoreCase("forzar")) for (Guild g : plugin.guilds.all()) out.add(g.name);
            else for (Territory t : plugin.territories.all()) out.add(t.id);
        } else if (args.length == 4 && args[0].equalsIgnoreCase("raid")) {
            if (args[1].equalsIgnoreCase("forzar") || args[1].equalsIgnoreCase("prueba") || args[1].equalsIgnoreCase("terminar")) for (Territory t : plugin.territories.all()) out.add(t.id);
            else out.addAll(List.of("atacante", "defensor", "saqueo", "cancelar"));
        }
        return out;
    }
}
