package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.ZoneManager.Zone;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /gremioadmin zona ... (zonas de recursos: mina, tala, cantera...). */
public class ZonaCommand {
    public static final List<String> SUBS = List.of("lista", "crear", "area", "npc", "recurso", "cada", "capacidad", "captura", "nombre", "tipo", "liberar", "borrar", "info", "menu");

    private final GremiosPlugin plugin;

    public ZonaCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    private void help(CommandSender s) {
        s.sendMessage("§6/gremioadmin zona §emenu §7- editor con menús");
        s.sendMessage("§6/gremioadmin zona §ecrear <id> <mina|tala|cantera|granja|...> <nombre...>");
        s.sendMessage("§6/gremioadmin zona §earea <id> §7- con la selección de la varita");
        s.sendMessage("§6/gremioadmin zona §enpc <id> §7- pone el aldeano donde estás");
        s.sendMessage("§6/gremioadmin zona §erecurso <id> agregar [cantidad] §7- el objeto de tu mano es lo que produce cada ciclo");
        s.sendMessage("§6/gremioadmin zona §erecurso <id> limpiar");
        s.sendMessage("§6/gremioadmin zona §ecada <id> <min> §7| §ecapacidad <id> <ciclos> §7| §ecaptura <id> <seg>");
        s.sendMessage("§6/gremioadmin zona §eliberar|borrar|info <id> §7| §elista");
    }

    private Zone need(CommandSender s, String[] a) {
        if (a.length < 3) {
            s.sendMessage("§cFalta el id de la zona.");
            return null;
        }
        Zone z = plugin.zones.get(a[2]);
        if (z == null) s.sendMessage("§cZona no encontrada.");
        return z;
    }

    public String line(Zone z) {
        return "§e" + z.id + " §7" + z.name + " §8| §f" + z.kind + " §8| " + (z.owner == null ? "§alibre" : "§6" + plugin.zones.factionName(z.owner))
                + " §8| §7cada " + z.intervalMin + "min, captura " + z.captureSeconds + "s §8| recursos: " + z.produce.size() + (z.hasArea ? "" : " §c(sin área)") + (z.npc == null ? " §c(sin aldeano)" : "");
    }

    public void run(CommandSender s, String[] a) {
        String act = a.length > 1 ? a[1].toLowerCase(Locale.ROOT) : "";
        Player p = s instanceof Player pl ? pl : null;
        switch (act) {
            case "lista" -> {
                if (plugin.zones.all().isEmpty()) s.sendMessage("§7No hay zonas. Crea una con /gremioadmin zona crear.");
                for (Zone z : plugin.zones.all()) s.sendMessage(line(z));
            }
            case "menu" -> {
                if (p == null) s.sendMessage("§cSolo jugadores.");
                else plugin.extraGuis.adminZones(p, 0);
            }
            case "crear" -> {
                if (a.length < 5) {
                    s.sendMessage("§cUso: /gremioadmin zona crear <id> <tipo> <nombre...>");
                    return;
                }
                String err = plugin.zones.create(a[2], a[3], String.join(" ", Arrays.copyOfRange(a, 4, a.length)));
                s.sendMessage(err == null ? "§aZona creada. Ahora: §f/gremioadmin zona area " + a[2].toLowerCase() + "§a, §fnpc§a y §frecurso agregar§a." : "§c" + err);
            }
            case "area" -> {
                Zone z = need(s, a);
                if (z == null) return;
                if (p == null) {
                    s.sendMessage("§cSolo jugadores.");
                    return;
                }
                Location[] sel = plugin.territories.sel(p.getUniqueId());
                String err = plugin.zones.setArea(z, sel[0], sel[1]);
                s.sendMessage(err == null ? "§aÁrea fijada." : "§c" + err);
            }
            case "npc" -> {
                Zone z = need(s, a);
                if (z == null) return;
                if (p == null) {
                    s.sendMessage("§cSolo jugadores.");
                    return;
                }
                plugin.zones.setNpc(z, p.getLocation());
                s.sendMessage("§aAldeano colocado donde estás.");
            }
            case "recurso" -> {
                Zone z = need(s, a);
                if (z == null) return;
                String sub = a.length > 3 ? a[3].toLowerCase(Locale.ROOT) : "";
                if (sub.equals("limpiar")) {
                    plugin.zones.clearProduce(z);
                    s.sendMessage("§aRecursos borrados.");
                } else if (sub.equals("agregar")) {
                    if (p == null) {
                        s.sendMessage("§cSolo jugadores.");
                        return;
                    }
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand == null || hand.getType().isAir()) {
                        s.sendMessage("§cSostén en la mano el objeto que producirá la zona.");
                        return;
                    }
                    int n = hand.getAmount();
                    if (a.length > 4) {
                        try {
                            n = Integer.parseInt(a[4]);
                        } catch (NumberFormatException ex) {
                            s.sendMessage("§cCantidad inválida.");
                            return;
                        }
                    }
                    plugin.zones.addProduce(z, hand, n);
                    s.sendMessage("§aAñadido: §f" + n + "x " + hand.getType().name() + " §apor ciclo.");
                } else s.sendMessage("§cUso: /gremioadmin zona recurso <id> <agregar [cantidad]|limpiar>");
            }
            case "cada", "capacidad", "captura", "nombre", "tipo" -> {
                Zone z = need(s, a);
                if (z == null) return;
                if (a.length < 4) {
                    s.sendMessage("§cFalta el valor.");
                    return;
                }
                String v = String.join(act.equals("nombre") ? " " : "", Arrays.copyOfRange(a, 3, a.length));
                String err = plugin.zones.setField(z, act, v);
                s.sendMessage(err == null ? "§aActualizado." : "§c" + err);
            }
            case "liberar" -> {
                Zone z = need(s, a);
                if (z == null) return;
                plugin.zones.release(z);
                s.sendMessage("§aZona liberada.");
            }
            case "borrar" -> {
                Zone z = need(s, a);
                if (z == null) return;
                plugin.zones.delete(z);
                s.sendMessage("§aZona borrada.");
            }
            case "info" -> {
                Zone z = need(s, a);
                if (z == null) return;
                s.sendMessage(line(z));
                for (int i = 0; i < z.produce.size(); i++)
                    s.sendMessage("§7 - §f" + z.produce.get(i).getAmount() + "x " + z.produce.get(i).getType().name() + " §8| almacén " + z.stock.get(i) + "/" + plugin.zones.cap(z, i));
            }
            default -> help(s);
        }
    }
}
