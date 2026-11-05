package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Rank;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /gremio ... para jugadores. */
public class GremioCommand implements CommandExecutor, TabCompleter {
    private final GremiosPlugin plugin;

    public GremioCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Solo jugadores.");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("menu")) {
            plugin.guis.openFor(p);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Guild g = plugin.guilds.of(p);
        switch (sub) {
            case "ayuda", "help" -> help(p);
            case "crear" -> {
                if (g != null) Msg.to(p, "§cYa tienes gremio.");
                else if (!plugin.guilds.canCreate(p.getUniqueId()) && !plugin.bypass.contains(p.getUniqueId()))
                    Msg.to(p, "§cNo tienes permiso para fundar un gremio. Pídeselo a un administrador.");
                else plugin.guis.found(p, 0);
            }
            case "info" -> info(p, args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null);
            case "invitar" -> {
                if (g == null) {
                    Msg.to(p, "§cNo tienes gremio.");
                    return true;
                }
                Rank r = g.rankOf(p.getUniqueId());
                if (r == null || !r.atLeast(Rank.OFICIAL)) {
                    Msg.to(p, "§cSolo Oficial o superior puede invitar.");
                    return true;
                }
                if (args.length < 2) {
                    plugin.guis.invite(p, 0);
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    Msg.to(p, "§cJugador no encontrado.");
                    return true;
                }
                String err = plugin.guilds.invite(g, p, t);
                if (err != null) Msg.to(p, "§c" + err);
            }
            case "aceptar" -> {
                String err = plugin.guilds.acceptInvite(p);
                if (err != null) Msg.to(p, "§c" + err);
                else Msg.to(p, "§aTe has unido al gremio.");
            }
            case "rechazar" -> {
                plugin.guilds.declineInvite(p);
                Msg.to(p, "§7Invitación rechazada.");
            }
            case "solicitar" -> {
                if (g != null) {
                    Msg.to(p, "§cYa tienes gremio.");
                    return true;
                }
                if (args.length < 2) {
                    Msg.to(p, "§cUso: /gremio solicitar <gremio>");
                    return true;
                }
                Guild t = plugin.guilds.byName(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                if (t == null) {
                    Msg.to(p, "§cEse gremio no existe.");
                    return true;
                }
                String err = plugin.guilds.request(t, p);
                Msg.to(p, err != null ? "§c" + err : "§aSolicitud enviada.");
            }
            case "salir" -> {
                if (g == null) Msg.to(p, "§cNo tienes gremio.");
                else plugin.guis.leave(p);
            }
            case "expulsar", "promover", "degradar" -> manage(p, g, sub, args);
            case "banco" -> {
                if (g == null) {
                    Msg.to(p, "§cNo tienes gremio.");
                    return true;
                }
                plugin.guis.bank(p);
            }
            case "depositar", "retirar" -> {
                if (g == null) {
                    Msg.to(p, "§cNo tienes gremio.");
                    return true;
                }
                long n;
                try {
                    n = Long.parseLong(args.length > 1 ? args[1] : "0");
                } catch (NumberFormatException ex) {
                    Msg.to(p, "§cCantidad inválida.");
                    return true;
                }
                if (n <= 0) {
                    Msg.to(p, "§cCantidad inválida.");
                    return true;
                }
                if (sub.equals("depositar")) {
                    if (!plugin.currency.isSet() || !plugin.currency.take(p, n)) {
                        Msg.to(p, "§cNo tienes suficientes monedas.");
                        return true;
                    }
                    plugin.guilds.deposit(g, p, n);
                    Msg.to(p, "§aDepositaste §e" + plugin.currency.fmt(n) + "§a.");
                } else {
                    Rank need = Rank.parse(plugin.getConfig().getString("banco.retiro-rango", "OFICIAL"), Rank.OFICIAL);
                    Rank mine = g.rankOf(p.getUniqueId());
                    if (mine == null || !mine.atLeast(need)) {
                        Msg.to(p, "§cSolo " + need.display + " o superior puede retirar.");
                        return true;
                    }
                    if (!plugin.guilds.withdraw(g, p, n)) {
                        Msg.to(p, "§cEl banco no tiene tanto.");
                        return true;
                    }
                    plugin.currency.give(p, n);
                    Msg.to(p, "§aRetiraste §e" + plugin.currency.fmt(n) + "§a.");
                }
            }
            case "chat" -> {
                if (g == null) {
                    Msg.to(p, "§cNo tienes gremio.");
                    return true;
                }
                boolean on = plugin.guilds.toggleChat(p.getUniqueId());
                Msg.to(p, on ? "§aChat de gremio activado." : "§7Chat de gremio desactivado.");
            }
            case "c" -> {
                if (g == null || args.length < 2) {
                    Msg.to(p, "§cUso: /gremio c <mensaje>");
                    return true;
                }
                plugin.guilds.chat(g, p, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            }
            case "top", "ranking" -> plugin.guis.ranking(p, 0);
            case "mapa" -> plugin.guis.territories(p, 0);
            case "tp" -> {
                Territory t = g == null ? null : plugin.territories.ownedBy(g);
                if (t == null) Msg.to(p, "§cTu gremio no tiene territorio.");
                else plugin.guis.teleportHome(p, t);
            }
            case "puerta" -> {
                Guild pg = g;
                var terr = pg == null ? null : plugin.territories.ownedBy(pg);
                if (terr == null) {
                    Msg.to(p, "§cTu gremio no tiene territorio.");
                    return true;
                }
                var gate = nearestGate(terr, p);
                if (gate == null) {
                    Msg.to(p, "§cNo hay ninguna puerta a menos de 10 bloques.");
                    return true;
                }
                String a2 = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "alternar";
                String err = a2.equals("abrir") ? plugin.gates.setOpen(gate, true) : a2.equals("cerrar") ? plugin.gates.setOpen(gate, false) : plugin.gates.toggle(gate);
                Msg.to(p, err == null ? "§6" + (gate.type.equals("PUENTE") ? "Puente " : "Puerta ") + (gate.open ? "§aabierto" : "§ccerrado") : "§c" + err);
            }
            case "asedio" -> raid(p, g, args);
            default -> help(p);
        }
        return true;
    }

    private void manage(Player p, Guild g, String sub, String[] args) {
        if (g == null) {
            Msg.to(p, "§cNo tienes gremio.");
            return;
        }
        Rank mine = g.rankOf(p.getUniqueId());
        if (mine == null || !mine.atLeast(Rank.OFICIAL)) {
            Msg.to(p, "§cSolo Oficial o superior.");
            return;
        }
        if (args.length < 2) {
            Msg.to(p, "§cUso: /gremio " + sub + " <jugador>");
            return;
        }
        UUID target = null;
        for (var en : g.members.entrySet()) if (en.getValue().name.equalsIgnoreCase(args[1])) target = en.getKey();
        if (target == null) {
            Msg.to(p, "§cEse jugador no está en tu gremio.");
            return;
        }
        Guild.MemberData td = g.member(target);
        if (td.rank.id >= mine.id) {
            Msg.to(p, "§cNo puedes gestionar a alguien de tu rango o superior.");
            return;
        }
        switch (sub) {
            case "expulsar" -> {
                plugin.guilds.removeMember(g, target);
                plugin.guilds.broadcast(g, "§c" + td.name + " §7fue expulsado del gremio.");
                Player t = Bukkit.getPlayer(target);
                if (t != null) Msg.to(t, "§cHas sido expulsado del gremio §6" + g.name + "§c.");
            }
            case "promover" -> {
                if (td.rank.up().id >= mine.id) {
                    Msg.to(p, "§cNo puedes ascender a ese rango.");
                    return;
                }
                td.rank = td.rank.up();
                plugin.guilds.save();
                plugin.guilds.broadcast(g, "§a" + td.name + " §7ahora es " + td.rank.colored() + "§7.");
            }
            default -> {
                td.rank = td.rank.down();
                plugin.guilds.save();
                plugin.guilds.broadcast(g, "§6" + td.name + " §7ahora es " + td.rank.colored() + "§7.");
            }
        }
    }

    private com.etheria.gremios.manager.GateManager.Gate nearestGate(com.etheria.gremios.model.Territory t, Player p) {
        com.etheria.gremios.manager.GateManager.Gate best = null;
        double bd = 100;
        for (var g : plugin.gates.of(t)) {
            var c = plugin.gates.center(g);
            if (c == null || c.getWorld() != p.getWorld()) continue;
            double d = c.distanceSquared(p.getLocation());
            if (d < bd) {
                bd = d;
                best = g;
            }
        }
        return best;
    }

    private void raid(Player p, Guild g, String[] args) {
        if (g == null) {
            Msg.to(p, "§cNo tienes gremio.");
            return;
        }
        String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "estado";
        switch (act) {
            case "declarar" -> {
                if (args.length < 3) {
                    Msg.to(p, "§cUso: /gremio asedio declarar <territorio>");
                    return;
                }
                Territory t = plugin.territories.get(args[2]);
                if (t == null) {
                    Msg.to(p, "§cTerritorio no encontrado.");
                    return;
                }
                String err = plugin.raids.declare(p, t);
                if (err != null) Msg.to(p, "§c" + err);
            }
            case "cancelar" -> {
                String err = plugin.raids.cancelDeclared(p);
                if (err != null) Msg.to(p, "§c" + err);
            }
            case "historial" -> {
                var list = plugin.history.recent(g.name, 10);
                if (list.isEmpty()) Msg.to(p, "§7Tu gremio aún no tiene asedios en el historial.");
                else {
                    Msg.to(p, "§6Últimos asedios de tu gremio:");
                    for (var e : list) p.sendMessage(plugin.history.line(e));
                }
            }
            default -> {
                Raid r = plugin.raids.of(g);
                if (r == null) {
                    Msg.to(p, "§7Tu gremio no participa en ningún asedio.");
                    return;
                }
                long now = System.currentTimeMillis();
                Msg.to(p, "§7" + r.attackerName + " §c→ §7" + r.defenderName + " §8| §f" + r.state.name());
                if (r.state == Raid.State.SCHEDULED) Msg.to(p, "§7Empieza en §f" + Msg.dur(r.startAt - now));
                if (r.state == Raid.State.ACTIVE) Msg.to(p, "§7Nodo §c" + (int) r.nodeHp + "§7/" + (int) r.nodeMax + " §8| quedan §f" + Msg.dur(r.endAt - now));
                if (r.state == Raid.State.LOOT) Msg.to(p, "§6Saqueo: §f" + Msg.dur(r.lootEndAt - now));
            }
        }
    }

    private void info(Player p, String name) {
        Guild g = name == null ? plugin.guilds.of(p) : plugin.guilds.byName(name);
        if (g == null) {
            Msg.to(p, "§cGremio no encontrado.");
            return;
        }
        Territory t = plugin.territories.ownedBy(g);
        Msg.raw(p, "§6§m      §r §e" + g.name + " §6§m      ");
        Msg.raw(p, "§7Nivel: §f" + g.level + " §8(" + Msg.num(g.xp) + "/" + Msg.num(plugin.guilds.xpNeeded(g.level)) + " XP)");
        Msg.raw(p, "§7Miembros: §f" + g.members.size() + "§7/§f" + plugin.guilds.maxMembers(g) + " §8(" + plugin.guilds.online(g) + " en línea)");
        Msg.raw(p, "§7Territorio: §f" + (t == null ? "ninguno" : t.name + " (" + Guild.levelName(g.territoryLevel) + ")"));
        Msg.raw(p, "§7Banco: §e" + plugin.currency.fmt(g.bank));
        StringBuilder sb = new StringBuilder();
        for (var en : g.members.entrySet()) {
            if (sb.length() > 0) sb.append("§7, ");
            sb.append(en.getValue().rank.color).append(en.getValue().name);
        }
        Msg.raw(p, "§7Miembros: " + sb);
    }

    private void help(Player p) {
        Msg.raw(p, "§6§m      §r §eGremios §6§m      ");
        Msg.raw(p, "§e/gremio §7- abre el menú");
        Msg.raw(p, "§e/gremio info [nombre] §7- información");
        Msg.raw(p, "§e/gremio invitar [jugador] §7| §e/gremio aceptar §7| §e/gremio rechazar");
        Msg.raw(p, "§e/gremio solicitar <gremio> §7- pedir unirte");
        Msg.raw(p, "§e/gremio salir §7| §e/gremio expulsar|promover|degradar <jugador>");
        Msg.raw(p, "§e/gremio banco §7| §e/gremio depositar|retirar <n>");
        Msg.raw(p, "§e/gremio chat §7| §e/gremio c <mensaje>");
        Msg.raw(p, "§e/gremio asedio declarar <territorio> §7| §ecancelar §7| §eestado");
        Msg.raw(p, "§e/gremio tp §7| §e/gremio top §7| §e/gremio mapa");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("menu", "ayuda", "crear", "info", "invitar", "aceptar", "rechazar", "solicitar", "salir", "expulsar", "promover",
                    "degradar", "banco", "depositar", "retirar", "chat", "c", "top", "mapa", "tp", "asedio", "puerta")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2) {
            String s = args[0].toLowerCase();
            if (s.equals("invitar")) {
                for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            } else if (s.equals("info") || s.equals("solicitar")) {
                for (Guild g : plugin.guilds.all()) if (g.name.toLowerCase().startsWith(args[1].toLowerCase())) out.add(g.name);
            } else if (s.equals("asedio")) {
                for (String a : List.of("declarar", "cancelar", "estado")) if (a.startsWith(args[1].toLowerCase())) out.add(a);
            } else if (sender instanceof Player p && (s.equals("expulsar") || s.equals("promover") || s.equals("degradar"))) {
                Guild g = plugin.guilds.of(p);
                if (g != null) for (var en : g.members.values()) if (en.name.toLowerCase().startsWith(args[1].toLowerCase())) out.add(en.name);
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("asedio") && args[1].equalsIgnoreCase("declarar")) {
            for (Territory t : plugin.territories.all()) if (t.id.startsWith(args[2].toLowerCase())) out.add(t.id);
        }
        return out;
    }
}
