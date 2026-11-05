package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;

/** /gremioadmin tablon crear|borrar|lista */
public class TablonCommand {
    public static final List<String> SUBS = List.of("crear", "borrar", "lista");
    private final GremiosPlugin plugin;

    public TablonCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    public void run(CommandSender s, String[] a) {
        String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        switch (act) {
            case "crear" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("§cSolo jugadores.");
                    return;
                }
                if (a.length < 3) {
                    s.sendMessage("§6/gremioadmin tablon crear <id> §7(en tu posición)");
                    return;
                }
                String err = plugin.board.create(a[2], p.getLocation());
                s.sendMessage(err == null ? "§aTablón creado." : "§c" + err);
            }
            case "borrar" -> {
                if (a.length < 3) {
                    s.sendMessage("§6/gremioadmin tablon borrar <id>");
                    return;
                }
                s.sendMessage(plugin.board.delete(a[2]) ? "§aTablón borrado." : "§cNo existe.");
            }
            case "lista" -> {
                if (plugin.board.all().isEmpty()) s.sendMessage("§7No hay tablones.");
                for (var b : plugin.board.all())
                    s.sendMessage("§e" + b.id + " §7" + b.world + " " + (int) b.x + " " + (int) b.y + " " + (int) b.z);
            }
            default -> s.sendMessage("§6/gremioadmin tablon §ecrear <id> | borrar <id> | lista");
        }
    }
}
