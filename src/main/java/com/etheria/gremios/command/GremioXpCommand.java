package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * /gremioxp dar &lt;jugador&gt; &lt;cantidad&gt; [rep]
 * Pensado para recompensas de BeautyQuests (consola). "rep" marca misiones repetibles
 * (cuentan para el tope diario de repetibles).
 */
public class GremioXpCommand implements CommandExecutor, TabCompleter {
    private final GremiosPlugin plugin;

    public GremioXpCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof org.bukkit.command.ConsoleCommandSender) && !sender.hasPermission("gremios.admin")) {
            sender.sendMessage("§cSin permiso.");
            return true;
        }
        if (args.length < 3 || !args[0].equalsIgnoreCase("dar")) {
            sender.sendMessage("§cUso: /gremioxp dar <jugador> <cantidad> [rep]");
            return true;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            sender.sendMessage("§cJugador no conectado.");
            return true;
        }
        long amount;
        try {
            amount = Long.parseLong(args[2]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cCantidad inválida.");
            return true;
        }
        boolean rep = args.length > 3 && args[3].equalsIgnoreCase("rep");
        String err = plugin.guilds.questXp(p, amount, rep);
        if (err != null) plugin.getLogger().info("gremioxp: " + p.getName() + " " + err + " (sin XP de gremio).");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) out.add("dar");
        else if (args.length == 2) for (Player p : Bukkit.getOnlinePlayers()) out.add(p.getName());
        else if (args.length == 4) out.add("rep");
        return out;
    }
}
