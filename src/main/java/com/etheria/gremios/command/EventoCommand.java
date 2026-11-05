package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.EventDef;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.util.Msg;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /gremioadmin evento ... (eventos de territorio: KOTH y jefe). */
public class EventoCommand {
    public static final List<String> SUBS = List.of("lista", "crear", "zona", "nombre", "tipo", "duracion", "mantener", "xp", "coronas", "cada", "nivel",
            "gremios", "jefe", "iniciar", "parar", "borrar", "activo", "info", "menu");

    private final GremiosPlugin plugin;

    public EventoCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    private void help(CommandSender s) {
        s.sendMessage("§6/gremioadmin evento §emenu §7- editor con menús");
        s.sendMessage("§6/gremioadmin evento §ecrear <id> <koth|jefe> <nombre...>");
        s.sendMessage("§6/gremioadmin evento §ezona <id> §7- con la selección de la varita (/territorio varita)");
        s.sendMessage("§6/gremioadmin evento §eduracion <id> <min> §7| §emantener <id> <seg> §7(KOTH) | §ejefe <id> <vida>");
        s.sendMessage("§6/gremioadmin evento §exp|coronas <id> <n> §7| §ecada <id> <min> §7(0 = manual) | §enivel <id> <n>");
        s.sendMessage("§6/gremioadmin evento §egremios <id> <todos|gremio1,gremio2>");
        s.sendMessage("§6/gremioadmin evento §einiciar|parar|borrar|info|activo <id> [si|no] §7| §elista");
    }

    public void run(CommandSender s, String[] args) {
        // args[0] = "evento"
        String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        Player p = s instanceof Player pl ? pl : null;
        switch (act) {
            case "lista" -> {
                if (plugin.events.all().isEmpty()) s.sendMessage("§7No hay eventos. Crea uno con /gremioadmin evento crear.");
                for (EventDef d : plugin.events.all()) s.sendMessage(line(d));
            }
            case "menu" -> {
                if (p == null) s.sendMessage("§cSolo jugadores.");
                else plugin.extraGuis.adminEvents(p, 0);
            }
            case "crear" -> {
                if (args.length < 5) {
                    s.sendMessage("§cUso: /gremioadmin evento crear <id> <koth|jefe> <nombre...>");
                    return;
                }
                EventDef.Type type;
                try {
                    type = EventDef.Type.valueOf(args[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    s.sendMessage("§cTipo: koth o jefe.");
                    return;
                }
                String err = plugin.events.create(args[2], type, String.join(" ", Arrays.copyOfRange(args, 4, args.length)));
                s.sendMessage(err == null ? "§aEvento creado. Ahora define la zona con §f/gremioadmin evento zona " + args[2].toLowerCase() + " §a(varita)." : "§c" + err);
            }
            case "zona" -> {
                EventDef d = need(s, args);
                if (d == null) return;
                if (p == null) {
                    s.sendMessage("§cSolo jugadores.");
                    return;
                }
                Location[] sel = plugin.territories.sel(p.getUniqueId());
                String err = plugin.events.setZone(d, sel[0], sel[1]);
                s.sendMessage(err == null ? "§aZona del evento fijada." : "§c" + err);
            }
            case "iniciar" -> {
                EventDef d = need(s, args);
                if (d == null) return;
                String err = plugin.events.start(d);
                s.sendMessage(err == null ? "§aEvento iniciado." : "§c" + err);
            }
            case "parar" -> {
                EventDef d = need(s, args);
                if (d == null) return;
                String err = plugin.events.stop(d);
                s.sendMessage(err == null ? "§aEvento detenido." : "§c" + err);
            }
            case "borrar" -> {
                EventDef d = need(s, args);
                if (d == null) return;
                plugin.events.delete(d.id);
                s.sendMessage("§aEvento borrado.");
            }
            case "info" -> {
                EventDef d = need(s, args);
                if (d == null) return;
                s.sendMessage(line(d));
                s.sendMessage("§7Zona: §f" + (d.hasZone ? d.world + " " + d.minX + "," + d.minY + "," + d.minZ + " → " + d.maxX + "," + d.maxY + "," + d.maxZ : "sin definir"));
                StringBuilder sb = new StringBuilder();
                for (String id : d.allowed) {
                    Guild g = plugin.guilds.byId(id);
                    if (g != null) sb.append(g.name).append(' ');
                }
                s.sendMessage("§7Gremios: §f" + (d.allowed.isEmpty() ? "todos" : sb.toString().trim()) + " §8| §7Nivel mínimo: §f" + d.minGuildLevel);
            }
            case "nombre", "tipo", "duracion", "mantener", "xp", "coronas", "cada", "nivel", "gremios", "jefe", "activo" -> {
                EventDef d = need(s, args);
                if (d == null) return;
                if (args.length < 4) {
                    s.sendMessage("§cFalta el valor.");
                    return;
                }
                String value = String.join(act.equals("nombre") || act.equals("gremios") ? " " : "", Arrays.copyOfRange(args, 3, args.length));
                if (act.equals("gremios")) value = value.replace(" ", "");
                String err = plugin.events.setField(d, act, value);
                s.sendMessage(err == null ? "§aActualizado." : "§c" + err);
            }
            default -> help(s);
        }
    }

    private EventDef need(CommandSender s, String[] args) {
        if (args.length < 3) {
            s.sendMessage("§cFalta el id del evento.");
            return null;
        }
        EventDef d = plugin.events.get(args[2]);
        if (d == null) s.sendMessage("§cEvento no encontrado.");
        return d;
    }

    public String line(EventDef d) {
        var a = plugin.events.running(d);
        return "§e" + d.id + " §7" + d.name + " §8| §f" + d.type + " §8| " + (a != null ? "§aEN MARCHA" : (d.enabled ? "§7inactivo" : "§cdesactivado"))
                + " §8| §7" + d.durationMin + "min" + (d.type == EventDef.Type.KOTH ? ", mantener " + d.holdSeconds + "s" : ", jefe " + (int) d.bossHp + "hp")
                + " §8| §e" + d.rewardXp + "xp" + (d.rewardCrowns > 0 ? " +" + d.rewardCrowns + " coronas" : "")
                + " §8| §7cada " + (d.intervalMin == 0 ? "manual" : d.intervalMin + "min");
    }
}
