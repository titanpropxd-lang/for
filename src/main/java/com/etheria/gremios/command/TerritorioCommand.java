package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.NpcRole;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /territorio ... (administración de territorios). */
public class TerritorioCommand implements CommandExecutor, TabCompleter {
    private final GremiosPlugin plugin;

    public TerritorioCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("gremios.admin")) {
            sender.sendMessage("§cSin permiso.");
            return true;
        }
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        Player p = sender instanceof Player pl ? pl : null;
        switch (sub) {
            case "varita" -> {
                if (p == null) return need(sender);
                p.getInventory().addItem(plugin.territories.wand());
                Msg.to(p, "§aTienes la varita. Clic izquierdo = pos 1, clic derecho = pos 2.");
            }
            case "crear" -> {
                if (p == null) return need(sender);
                if (args.length < 3) {
                    Msg.to(p, "§cUso: /territorio crear <id> <nombre...>");
                    return true;
                }
                Location[] s = plugin.territories.sel(p.getUniqueId());
                String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                String err = plugin.territories.create(args[1], name, s[0], s[1]);
                if (err != null) {
                    Msg.to(p, "§c" + err);
                    return true;
                }
                Territory t = plugin.territories.get(args[1]);
                Msg.to(p, "§aTerritorio §f" + t.name + " §acreado (" + t.shortSize() + ", " + Msg.num(t.volume()) + " bloques). Precio: " + t.price);
                if (plugin.getConfig().getBoolean("territorio.snapshot-al-crear", true)) {
                    Msg.to(p, "§7Guardando el estado original (snapshot)... verás el progreso en la consola.");
                    plugin.snapshots.capture(t, e -> Msg.to(p, e == null ? "§aSnapshot de §f" + t.name + " §alisto. Ya se puede comprar." : "§cSnapshot fallido: " + e));
                } else {
                    Msg.to(p, "§eRecuerda hacer §f/territorio snapshot " + t.id + " §eantes de ponerlo a la venta.");
                }
            }
            case "borrar" -> {
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                if (plugin.raids.get(t.id) != null) {
                    sender.sendMessage("§cHay un asedio en curso.");
                    return true;
                }
                plugin.territories.delete(t);
                sender.sendMessage("§aTerritorio borrado.");
            }
            case "lista" -> {
                sender.sendMessage("§6Territorios (" + plugin.territories.all().size() + "):");
                for (Territory t : plugin.territories.all()) {
                    Guild o = plugin.guilds.byId(t.ownerGuildId);
                    sender.sendMessage("§e" + t.id + " §7- " + t.name + " §8| §f" + t.shortSize() + " §8| " + (o == null ? "§aLibre" : "§6" + o.name)
                            + " §8| snapshot: " + (plugin.snapshots.has(t) ? "§así" : "§cno") + " §8| precio " + t.price);
                }
            }
            case "info" -> {
                Territory t = null;
                if (args.length > 1) t = plugin.territories.get(args[1]);
                else if (p != null) t = plugin.territories.at(p.getLocation());
                if (t == null) {
                    sender.sendMessage("§cTerritorio no encontrado (di el id o ponte dentro).");
                    return true;
                }
                Guild o = plugin.guilds.byId(t.ownerGuildId);
                sender.sendMessage("§6" + t.name + " §7(id " + t.id + ")");
                sender.sendMessage("§7Mundo: §f" + t.world + " §7| §f" + t.minX + "," + t.minY + "," + t.minZ + " → " + t.maxX + "," + t.maxY + "," + t.maxZ);
                sender.sendMessage("§7Dueño: " + (o == null ? "§aLibre" : "§6" + o.name + " §7(" + Guild.levelName(o.territoryLevel) + ")"));
                sender.sendMessage("§7Precios: base §e" + t.price + " §7| fortaleza §e" + plugin.territories.upgradeCost(t, 2) + " §7| reinado §e" + plugin.territories.upgradeCost(t, 3));
                sender.sendMessage("§7Snapshot: " + (plugin.snapshots.has(t) ? "§así" : "§cno") + " §7| bloques dañados: §f" + plugin.territories.damageCount(t)
                        + " §7| restaurando: " + (t.resetting ? "§csí" : "§ano"));
                sender.sendMessage("§7Spawn: " + (t.spawn == null ? "automático" : "fijado") + " §7| Nodo: " + (t.node == null ? "centro" : "fijado") + " §7| NPCs fijados: §f" + t.npcPos.keySet());
            }
            case "precio" -> {
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                if (args.length < 3) {
                    sender.sendMessage("§cUso: /territorio precio <id> <base> [fortaleza] [reinado]");
                    return true;
                }
                try {
                    t.price = Math.max(0, Long.parseLong(args[2]));
                    if (args.length > 3) t.priceFort = Math.max(0, Long.parseLong(args[3]));
                    if (args.length > 4) t.priceRein = Math.max(0, Long.parseLong(args[4]));
                } catch (NumberFormatException ex) {
                    sender.sendMessage("§cNúmero inválido.");
                    return true;
                }
                plugin.territories.save();
                sender.sendMessage("§aPrecios actualizados.");
            }
            case "setspawn" -> {
                if (p == null) return need(sender);
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                Location l = p.getLocation();
                t.spawn = new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw()};
                plugin.territories.save();
                Msg.to(p, "§aSpawn del territorio fijado.");
            }
            case "setnodo" -> {
                if (p == null) return need(sender);
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                Location l = p.getLocation();
                t.node = new double[]{l.getX(), l.getY(), l.getZ()};
                plugin.territories.save();
                Msg.to(p, "§aPosición del Nodo fijada (junto a la fortaleza).");
            }
            case "npc" -> {
                if (p == null) return need(sender);
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                NpcRole r = args.length > 2 ? NpcRole.parse(args[2]) : null;
                if (r == null) {
                    Msg.to(p, "§cRol: " + Arrays.toString(NpcRole.values()).toLowerCase());
                    return true;
                }
                plugin.npcs.removeAll(t);
                Location l = p.getLocation();
                t.npcPos.put(r.key(), new double[]{l.getX(), l.getY(), l.getZ(), l.getYaw()});
                plugin.territories.save();
                if (t.ownerGuildId != null) plugin.npcs.sync(t);
                Msg.to(p, "§aPosición del NPC §f" + r.key() + " §afijada.");
            }
            case "snapshot" -> {
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                if (plugin.territories.busy(t) || t.ownerGuildId != null) {
                    sender.sendMessage("§cSolo se puede guardar un territorio libre y sin asedio.");
                    return true;
                }
                sender.sendMessage("§7Guardando snapshot de " + t.name + "...");
                plugin.snapshots.capture(t, e -> sender.sendMessage(e == null ? "§aSnapshot listo." : "§cError: " + e));
            }
            case "reset" -> {
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                if (plugin.raids.get(t.id) != null) {
                    sender.sendMessage("§cHay un asedio en curso.");
                    return true;
                }
                sender.sendMessage("§7Restaurando " + t.name + " (se libera si tenía dueño)...");
                if (t.ownerGuildId != null) plugin.territories.release(t, true, () -> sender.sendMessage("§aRestaurado."));
                else plugin.territories.resetTerritory(t, () -> sender.sendMessage("§aRestaurado."));
            }
            case "liberar" -> {
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                if (plugin.raids.get(t.id) != null) {
                    sender.sendMessage("§cHay un asedio en curso.");
                    return true;
                }
                plugin.territories.release(t, false, null);
                sender.sendMessage("§aTerritorio liberado (sin restaurar).");
            }
            case "tp" -> {
                if (p == null) return need(sender);
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                Location l = plugin.territories.spawn(t);
                if (l == null) Msg.to(p, "§cMundo no cargado.");
                else p.teleport(l);
            }
            case "setbandera" -> {
                if (p == null) return need(sender);
                Territory t = terr(sender, args, 1);
                if (t == null) return true;
                plugin.banners.setPos(t, p.getLocation());
                Msg.to(p, "§aLugar de la bandera del gremio fijado en tu posición.");
            }
            case "puerta" -> {
                if (p == null) return need(sender);
                gate(p, args);
            }
            default -> help(sender);
        }
        return true;
    }

    private void gate(Player p, String[] args) {
        String act = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (act) {
            case "crear" -> {
                if (args.length < 4) {
                    Msg.to(p, "§cUso: /territorio puerta crear <territorio> <id> [puerta|puente]  (selecciona con la varita, CON la puerta cerrada)");
                    return;
                }
                Territory t = plugin.territories.get(args[2]);
                if (t == null) {
                    Msg.to(p, "§cTerritorio no encontrado.");
                    return;
                }
                Location[] s = plugin.territories.sel(p.getUniqueId());
                String err = plugin.gates.create(t, args[3], args.length > 4 ? args[4] : "PUERTA", s[0], s[1]);
                Msg.to(p, err == null ? "§aPuerta §f" + args[3] + " §acreada. Los bloques seleccionados son su estado CERRADO." : "§c" + err);
            }
            case "borrar" -> {
                if (args.length < 4) {
                    Msg.to(p, "§cUso: /territorio puerta borrar <territorio> <id>");
                    return;
                }
                Territory t = plugin.territories.get(args[2]);
                Msg.to(p, t != null && plugin.gates.delete(t, args[3]) ? "§aPuerta borrada." : "§cNo encontrada.");
            }
            case "lista" -> {
                for (var g : plugin.gates.all()) Msg.to(p, "§e" + g.territory + "/" + g.id + " §7" + g.type + " §8| vida " + (int) g.hp + "/" + (int) g.maxHp + (g.open ? " §a[abierta]" : "") + (g.broken ? " §c[rota]" : ""));
            }
            case "abrir", "cerrar" -> {
                if (args.length < 4) {
                    Msg.to(p, "§cUso: /territorio puerta " + act + " <territorio> <id>");
                    return;
                }
                var g = plugin.gates.get(args[2], args[3]);
                if (g == null) {
                    Msg.to(p, "§cNo encontrada.");
                    return;
                }
                String err = plugin.gates.setOpen(g, act.equals("abrir"));
                Msg.to(p, err == null ? "§aHecho." : "§c" + err);
            }
            case "vida" -> {
                if (args.length < 5) {
                    Msg.to(p, "§cUso: /territorio puerta vida <territorio> <id> <vida>");
                    return;
                }
                var g = plugin.gates.get(args[2], args[3]);
                if (g == null) {
                    Msg.to(p, "§cNo encontrada.");
                    return;
                }
                try {
                    g.maxHp = Math.max(10, Double.parseDouble(args[4]));
                    g.hp = g.maxHp;
                    plugin.gates.save();
                    Msg.to(p, "§aVida de la puerta: " + (int) g.maxHp);
                } catch (NumberFormatException ex) {
                    Msg.to(p, "§cNúmero inválido.");
                }
            }
            case "control" -> {
                if (args.length < 4) {
                    Msg.to(p, "§cUso: /territorio puerta control <territorio> <id>  (mirando el bloque: palanca, botón...)");
                    return;
                }
                var g = plugin.gates.get(args[2], args[3]);
                var b = p.getTargetBlockExact(6);
                if (g == null || b == null) {
                    Msg.to(p, "§cPuerta no encontrada o no estás mirando un bloque.");
                    return;
                }
                g.control = new int[]{b.getX(), b.getY(), b.getZ()};
                plugin.gates.save();
                Msg.to(p, "§aBloque de control fijado: los miembros del gremio dueño lo usan con clic derecho.");
            }
            default -> Msg.to(p, "§c/territorio puerta <crear|borrar|lista|abrir|cerrar|vida|control> ...");
        }
    }

    private boolean need(CommandSender s) {
        s.sendMessage("§cSolo jugadores.");
        return true;
    }

    private Territory terr(CommandSender s, String[] args, int idx) {
        if (args.length <= idx) {
            s.sendMessage("§cFalta el id del territorio.");
            return null;
        }
        Territory t = plugin.territories.get(args[idx]);
        if (t == null) s.sendMessage("§cTerritorio '" + args[idx] + "' no encontrado.");
        return t;
    }

    private void help(CommandSender s) {
        s.sendMessage("§6/territorio §evarita §7- varita de selección");
        s.sendMessage("§6/territorio §ecrear <id> <nombre...> §7- con la selección de la varita");
        s.sendMessage("§6/territorio §eborrar|info|tp|snapshot|reset|liberar <id>");
        s.sendMessage("§6/territorio §eprecio <id> <base> [fortaleza] [reinado]");
        s.sendMessage("§6/territorio §esetspawn|setnodo <id> §7- en tu posición");
        s.sendMessage("§6/territorio §enpc <id> <rol> §7- posición de un NPC (alcalde, intendente, maestro, herrero, capitan, embajador)");
        s.sendMessage("§6/territorio §esetbandera <id> §7- dónde se ve la bandera del gremio (tu posición)");
        s.sendMessage("§6/territorio §epuerta <crear|borrar|lista|abrir|cerrar|vida|control> ...");
        s.sendMessage("§6/territorio §elista");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("gremios.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("varita", "crear", "borrar", "lista", "info", "precio", "setspawn", "setnodo", "npc", "snapshot", "reset", "liberar", "tp", "setbandera", "puerta")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("crear") && !args[0].equalsIgnoreCase("varita") && !args[0].equalsIgnoreCase("lista")) {
            for (Territory t : plugin.territories.all()) if (t.id.startsWith(args[1].toLowerCase())) out.add(t.id);
        } else if (args.length == 3 && args[0].equalsIgnoreCase("npc")) {
            for (NpcRole r : NpcRole.values()) if (r.key().startsWith(args[2].toLowerCase())) out.add(r.key());
        }
        return out;
    }
}
