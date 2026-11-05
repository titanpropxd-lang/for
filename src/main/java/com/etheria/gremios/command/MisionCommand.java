package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.MissionManager;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /gremioadmin mision ... */
public class MisionCommand {
    public static final List<String> SUBS = List.of("lista", "crear", "borrar", "coronas", "activa", "menu");

    private final GremiosPlugin plugin;

    public MisionCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    private void help(CommandSender s) {
        s.sendMessage("§6/gremioadmin mision §ecrear <id> <entregar|matar|minar> <objetivo> <cantidad> <xp> <unica|diaria|semanal> <nombre...>");
        s.sendMessage("§7  Ej: §fcrear piedra entregar COBBLESTONE 128 150 diaria Cantera del gremio");
        s.sendMessage("§7  Ej: §fcrear zombis matar ZOMBIE 30 200 diaria Limpieza de zombis");
        s.sendMessage("§6/gremioadmin mision §ecoronas <id> <n> §7(dinero extra al banco) | §eactiva <id> <si|no> | §eborrar <id> | §elista");
    }

    public void run(CommandSender s, String[] a) {
        String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (act) {
            case "lista" -> {
                if (plugin.missions.all().isEmpty()) s.sendMessage("§7No hay misiones.");
                for (var m : plugin.missions.all())
                    s.sendMessage("§e" + m.id + " §7" + m.name + " §8| §f" + m.type + " " + m.amount + "x " + m.target + " §8| §e" + m.rewardXp + "xp" + (m.rewardCrowns > 0 ? " +" + m.rewardCrowns + "c" : "") + " §8| " + m.repeat + (m.enabled ? "" : " §c[desactivada]"));
            }
            case "menu" -> {
                if (s instanceof Player p) plugin.extraGuis.missions(p, 0);
                else s.sendMessage("§cSolo jugadores.");
            }
            case "crear" -> {
                if (a.length < 9) {
                    help(s);
                    return;
                }
                MissionManager.Type type;
                try {
                    type = MissionManager.Type.valueOf(a[3].toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException ex) {
                    s.sendMessage("§cTipo: entregar, matar o minar.");
                    return;
                }
                int amount;
                long xp;
                try {
                    amount = Integer.parseInt(a[5]);
                    xp = Long.parseLong(a[6]);
                } catch (NumberFormatException ex) {
                    s.sendMessage("§cCantidad o XP inválidos.");
                    return;
                }
                String rep = a[7].toUpperCase(Locale.ROOT);
                if (!List.of("UNICA", "DIARIA", "SEMANAL").contains(rep)) {
                    s.sendMessage("§cRepetición: unica, diaria o semanal.");
                    return;
                }
                String err = plugin.missions.create(a[2], type, a[4], amount, xp, rep, String.join(" ", Arrays.copyOfRange(a, 8, a.length)));
                s.sendMessage(err == null ? "§aMisión creada." : "§c" + err);
            }
            case "borrar" -> s.sendMessage(a.length > 2 && plugin.missions.delete(a[2]) ? "§aBorrada." : "§cNo encontrada.");
            case "coronas", "activa" -> {
                var m = a.length > 3 ? plugin.missions.get(a[2]) : null;
                if (m == null) {
                    s.sendMessage("§cUso: /gremioadmin mision " + act + " <id> <valor>");
                    return;
                }
                try {
                    if (act.equals("coronas")) m.rewardCrowns = Math.max(0, Long.parseLong(a[3]));
                    else m.enabled = a[3].equalsIgnoreCase("si") || a[3].equalsIgnoreCase("true");
                } catch (NumberFormatException ex) {
                    s.sendMessage("§cNúmero inválido.");
                    return;
                }
                plugin.missions.save();
                s.sendMessage("§aActualizado.");
            }
            default -> help(s);
        }
    }
}
