package com.etheria.gremios.gui;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.RepairManager;
import com.etheria.gremios.manager.SiegeItems;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.NpcRole;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Rank;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.ItemBuilder;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Todos los menús del plugin. */
public class Guis {
    private final GremiosPlugin plugin;
    private final Map<UUID, Long> tpCooldown = new HashMap<>();

    public Guis(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ utilidades

    private ItemStack it(Material m, String name, List<String> lore) {
        return new ItemBuilder(m).name(name).lore(lore).build();
    }

    private ItemStack it(Material m, String name, String... lore) {
        return new ItemBuilder(m).name(name).lore(lore).build();
    }

    private ItemStack blank(String name, List<String> lore) {
        return new ItemBuilder(SiegeItems.BASE).name(name).lore(lore).cmd(SiegeItems.CMD_BLANK).build();
    }

    private ItemStack head(UUID u, String name, List<String> lore) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return new ItemBuilder(Material.PLAYER_HEAD).name(name).lore(lore).owner(op).build();
    }

    private Menu menu(Player p, String layout, java.util.function.Consumer<Menu> r) {
        return new Menu(plugin, p, layout, r);
    }

    private boolean rankOk(Player p, Rank min) {
        Rank r = plugin.guilds.rankOf(p);
        return r != null && r.atLeast(min);
    }

    private void msg(Player p, String s) {
        Msg.to(p, s);
    }

    private String yn(boolean b) {
        return b ? "§aSí" : "§cNo";
    }

    private Material levelMat(int level) {
        return switch (level) {
            case 1 -> Material.BELL;
            case 2 -> Material.STONE_BRICKS;
            case 3 -> Material.GOLD_BLOCK;
            default -> Material.GRASS_BLOCK;
        };
    }

    // ------------------------------------------------------------------ entrada

    public void openFor(Player p) {
        Guild g = plugin.guilds.of(p);
        if (g != null) main(p);
        else if (plugin.guilds.canCreate(p.getUniqueId()) || plugin.bypass.contains(p.getUniqueId())) found(p, 0);
        else noGuild(p);
    }

    public void openForNpc(Player p, NpcRole role, Territory t) {
        Guild g = plugin.guilds.of(p);
        if (g == null || !g.id.equals(t.ownerGuildId)) {
            Guild owner = plugin.guilds.byId(t.ownerGuildId);
            msg(p, "§cEste " + role.display + " §cpertenece a " + (owner == null ? "otro gremio" : "§6" + owner.name) + "§c.");
            return;
        }
        switch (role) {
            case ALCALDE -> main(p);
            case INTENDENTE -> bank(p);
            case MAESTRO -> works(p, 0);
            case HERRERO -> shop(p, 0);
            case CAPITAN -> upgrades(p);
            case EMBAJADOR -> alliances(p, 0);
        }
    }

    // ------------------------------------------------------------------ sin gremio

    public void noGuild(Player p) {
        menu(p, "singremio", m -> {
            Guild g = plugin.guilds.of(p);
            if (g != null) {
                main(p);
                return;
            }
            m.button("gremios", "§9Gremios", List.of("§7Mira los gremios del servidor", "§7y envía una solicitud."), e -> ranking(p, 0));
            var inv = plugin.guilds.getInvite(p.getUniqueId());
            List<String> l = new ArrayList<>();
            if (inv == null) l.add("§7No tienes invitaciones.");
            else {
                Guild ig = plugin.guilds.byId(inv.guildId);
                l.add("§7Te invita: §6" + (ig == null ? "?" : ig.name));
                l.add("§aClic para aceptar");
                l.add("§cClic derecho para rechazar");
            }
            m.button("invitaciones", "§aInvitaciones", l, e -> {
                if (plugin.guilds.getInvite(p.getUniqueId()) == null) return;
                if (e.getClick() == ClickType.RIGHT) {
                    plugin.guilds.declineInvite(p);
                    msg(p, "§7Invitación rechazada.");
                    m.refresh();
                } else {
                    String err = plugin.guilds.acceptInvite(p);
                    if (err != null) msg(p, "§c" + err);
                    else main(p);
                }
            });
            m.button("territorios", "§3Territorios", List.of("§7Mapa de territorios del servidor."), e -> territories(p, 0));
            m.button("cerrar", "§cCerrar", e -> p.closeInventory());
        }).open();
    }

    // ------------------------------------------------------------------ fundar / reclamar territorio

    public void found(Player p, int page) {
        menu(p, "fundar", m -> {
            Guild g = plugin.guilds.of(p);
            List<Territory> list = new ArrayList<>();
            for (Territory t : plugin.territories.all()) {
                if (t.free() && !plugin.territories.busy(t) && plugin.snapshots.has(t)) list.add(t);
            }
            list.sort(Comparator.comparingLong((Territory t) -> t.price));
            m.paged(list, t -> {
                List<String> l = new ArrayList<>();
                l.add("§7Tamaño: §f" + t.shortSize());
                l.add("§7Precio: §e" + plugin.currency.fmt(t.price));
                l.add("§7Empiezas como: §aPueblo");
                l.add("");
                l.add(g == null ? "§aClic para fundar tu gremio aquí" : "§aClic para reclamarlo con el banco");
                return it(Material.GRASS_BLOCK, "§6" + t.name, l);
            }, (t, e) -> {
                if (g == null) askFound(p, t);
                else claimForGuild(p, t);
            });
            m.button("volver", "§7Volver", ev -> {
                if (plugin.guilds.of(p) != null) main(p);
                else noGuild(p);
            });
            m.button("gremios", "§9Gremios", ev -> ranking(p, 0));
        }).at(page).open();
    }

    private void askFound(Player p, Territory t) {
        if (!plugin.currency.isSet()) {
            msg(p, "§cEl administrador aún no ha definido la moneda.");
            return;
        }
        if (!plugin.guilds.canCreate(p.getUniqueId()) && !plugin.bypass.contains(p.getUniqueId())) {
            msg(p, "§cNo tienes permiso para fundar un gremio.");
            return;
        }
        if (plugin.currency.count(p) < t.price) {
            msg(p, "§cNecesitas §e" + plugin.currency.fmt(t.price) + " §cen el inventario.");
            return;
        }
        plugin.prompts.ask(p, "§eEscribe el §6nombre de tu gremio §ees decir, 3-16 caracteres:", name -> {
            String err = plugin.guilds.validName(name);
            if (err != null) {
                msg(p, "§c" + err);
                return;
            }
            if (plugin.guilds.of(p) != null) {
                msg(p, "§cYa tienes gremio.");
                return;
            }
            if (!t.free() || plugin.territories.busy(t)) {
                msg(p, "§cEse territorio ya no está disponible.");
                return;
            }
            if (!plugin.currency.take(p, t.price)) {
                msg(p, "§cTe faltan monedas.");
                return;
            }
            plugin.guilds.setCanCreate(p.getUniqueId(), false);
            Guild g = plugin.guilds.create(name, p, t);
            Bukkit.broadcastMessage(Msg.PREFIX + "§6" + p.getName() + " §7ha fundado el gremio §e" + g.name + " §7en §f" + t.name + "§7.");
            p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
            main(p);
        });
    }

    private void claimForGuild(Player p, Territory t) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return;
        if (g.hasTerritory()) {
            msg(p, "§cTu gremio ya tiene territorio.");
            return;
        }
        if (!rankOk(p, Rank.MARISCAL)) {
            msg(p, "§cSolo Mariscal o superior puede reclamar territorio.");
            return;
        }
        if (g.bank < t.price) {
            msg(p, "§cEl banco necesita §e" + plugin.currency.fmt(t.price) + "§c.");
            return;
        }
        confirm(p, List.of("§7Reclamar §6" + t.name, "§7Coste del banco: §e" + plugin.currency.fmt(t.price)), () -> {
            Guild gg = plugin.guilds.of(p);
            if (gg == null || gg.hasTerritory() || !t.free() || plugin.territories.busy(t) || gg.bank < t.price) {
                msg(p, "§cYa no es posible.");
                return;
            }
            gg.bank -= t.price;
            gg.log("§6-" + Msg.num(t.price) + " §7territorio");
            plugin.territories.claim(t, gg);
            plugin.guilds.broadcast(gg, "§aVuestro gremio ha reclamado §6" + t.name + "§a.");
            main(p);
        }, () -> found(p, 0));
    }

    // ------------------------------------------------------------------ principal

    public void main(Player p) {
        menu(p, "main", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            Rank rk = g.rankOf(p.getUniqueId());
            Territory t = plugin.territories.ownedBy(g);
            long now = System.currentTimeMillis();
            List<String> info = new ArrayList<>();
            info.add("§7Nivel: §e" + g.level + (g.level >= Guild.MAX_LEVEL ? " §6(MÁX)" : " §8(" + Msg.num(g.xp) + "/" + Msg.num(plugin.guilds.xpNeeded(g.level)) + " XP)"));
            info.add("§7Tu rango: " + (rk == null ? "?" : rk.colored()));
            info.add("§7Miembros: §f" + g.members.size() + "§7/§f" + plugin.guilds.maxMembers(g) + " §8(" + plugin.guilds.online(g) + " en línea)");
            info.add("§7Territorio: §f" + (t == null ? "ninguno" : t.name + " §8(" + Guild.levelName(g.territoryLevel) + ")"));
            info.add("§7Banco: §e" + plugin.currency.fmt(g.bank));
            if (g.hasTerritory()) info.add("§7Mantenimiento en: §f" + Msg.dur(g.nextUpkeep - now) + (g.upkeepMissed > 0 ? " §c(impagos: " + g.upkeepMissed + ")" : ""));
            if (g.protectedUntil > now) info.add("§bProtegido: §f" + Msg.dur(g.protectedUntil - now));
            Raid r = plugin.raids.of(g);
            if (r != null) {
                info.add("");
                switch (r.state) {
                    case SCHEDULED -> info.add("§c⚔ Asedio en §f" + Msg.dur(r.startAt - now) + " §7(" + r.attackerName + " → " + r.defenderName + ")");
                    case ACTIVE -> info.add("§4⚔ ASEDIO EN CURSO §7- quedan §f" + Msg.dur(r.endAt - now));
                    case LOOT -> info.add("§6Fase de saqueo: §f" + Msg.dur(r.lootEndAt - now));
                    default -> {
                    }
                }
            }
            if (t != null && plugin.territories.isDamaged(t)) info.add("§c⚠ Territorio dañado: §f" + plugin.territories.damageCount(t) + " bloques");
            m.cell(4, 0, it(Material.WHITE_BANNER, "§6§l" + g.name, info), null);

            m.button("miembros", "§aMiembros", List.of("§7" + g.members.size() + " miembros", "§7Gestiona rangos y expulsiones."), e -> members(p, 0));
            m.button("banco", "§6Banco", List.of("§7Saldo: §e" + plugin.currency.fmt(g.bank)), e -> bank(p));
            m.button("mejoras", "§9Mejoras", List.of("§7Territorio, guardia, núcleo y capacidad."), e -> upgrades(p));
            m.button("guerra", "§cGuerra", List.of("§7Declara asedios y mira el estado."), e -> war(p, 0));
            List<String> tl = new ArrayList<>();
            if (t != null) {
                tl.add("§7" + t.name + " §8(" + Guild.levelName(g.territoryLevel) + ")");
                tl.add("§aClic: teletransportarte");
            } else {
                tl.add("§7Tu gremio no tiene territorio.");
                tl.add("§aClic: elegir uno");
            }
            m.button("territorio", "§2Territorio", tl, e -> {
                if (plugin.guilds.of(p) == null) return;
                if (t != null) teleportHome(p, t);
                else found(p, 0);
            });
            m.button("alianzas", "§5Alianzas", List.of("§7Aliados: §f" + g.allies.size()), e -> alliances(p, 0));
            m.button("impuestos", "§6Impuestos", List.of("§7Mantenimiento y cuotas."), e -> taxes(p));
            m.button("ranking", "§eRanking", List.of("§7Los mejores gremios."), e -> ranking(p, 0));
            m.button("mapa", "§3Territorios", List.of("§7Mapa de territorios."), e -> territories(p, 0));
            m.button("solicitudes", "§9Solicitudes", List.of("§7Pendientes: §f" + g.requests.size()), e -> {
                if (!rankOk(p, Rank.OFICIAL)) {
                    msg(p, "§cSolo Oficial o superior.");
                    return;
                }
                requests(p, 0);
            });
            m.button("invitar", "§aInvitar", List.of("§7Invita a jugadores sin gremio."), e -> {
                if (!rankOk(p, Rank.OFICIAL)) {
                    msg(p, "§cSolo Oficial o superior.");
                    return;
                }
                invite(p, 0);
            });
            List<String> ol = new ArrayList<>();
            if (t != null && plugin.territories.isDamaged(t)) ol.add("§cBloques dañados: §f" + plugin.territories.damageCount(t));
            else ol.add("§7Sin daños.");
            ol.add("§7Habla con el Maestro de Obras para reparar.");
            m.button("obras", "§6Obras", ol, e -> works(p, 0));
            m.button("salir", "§cSalir del gremio", List.of("§7Abandonas el gremio."), e -> leave(p));
            m.button("misiones", "§6Misiones", List.of("§7Misiones de gremio: la XP va a tu gremio.", "", "§8Chat de gremio: /gremio chat"), e -> plugin.extraGuis.missions(p, 0));
            m.button("cerrar", "§7Cerrar", e -> p.closeInventory());
        }).open();
    }

    public void teleportHome(Player p, Territory t) {
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("territorio.teleport-espera-segundos", 30) * 1000L;
        Long last = tpCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd) {
            msg(p, "§cEspera " + Msg.dur(cd - (now - last)) + ".");
            return;
        }
        Raid r = plugin.raids.get(t.id);
        if (r != null && r.state == Raid.State.ACTIVE && !plugin.guilds.of(p).id.equals(r.defenderId)) {
            msg(p, "§cNo puedes teletransportarte durante el asedio.");
            return;
        }
        Location l = plugin.territories.spawn(t);
        if (l == null) {
            msg(p, "§cEl mundo del territorio no está cargado.");
            return;
        }
        tpCooldown.put(p.getUniqueId(), now);
        p.closeInventory();
        p.teleport(l);
        msg(p, "§aTeletransportado a §6" + t.name + "§a.");
    }

    public void leave(Player p) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return;
        boolean leader = g.leader.equals(p.getUniqueId());
        if (leader && g.members.size() > 1) {
            msg(p, "§cEres el líder: transfiere el liderazgo antes de salir (Miembros → gestionar).");
            return;
        }
        if (plugin.raids.of(g) != null) {
            msg(p, "§cNo puedes salir ni disolver durante un asedio.");
            return;
        }
        List<String> l = leader ? List.of("§cSois solo tú: el gremio se DISOLVERÁ.", "§7Perderás el territorio y el banco.")
                : List.of("§7Dejarás el gremio §6" + g.name + "§7.");
        confirm(p, l, () -> {
            Guild gg = plugin.guilds.of(p);
            if (gg == null) return;
            if (gg.leader.equals(p.getUniqueId()) && gg.members.size() <= 1) {
                String n = gg.name;
                plugin.guilds.disband(gg);
                Bukkit.broadcastMessage(Msg.PREFIX + "§7El gremio §e" + n + " §7se ha disuelto.");
            } else {
                plugin.guilds.removeMember(gg, p.getUniqueId());
                plugin.guilds.broadcast(gg, "§c" + p.getName() + " §7abandonó el gremio.");
            }
            p.closeInventory();
        }, () -> main(p));
    }

    // ------------------------------------------------------------------ miembros

    public void members(Player p, int page) {
        menu(p, "miembros", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            List<Map.Entry<UUID, Guild.MemberData>> list = new ArrayList<>(g.members.entrySet());
            list.sort((a, b) -> b.getValue().rank.id != a.getValue().rank.id ? Integer.compare(b.getValue().rank.id, a.getValue().rank.id)
                    : a.getValue().name.compareToIgnoreCase(b.getValue().name));
            boolean manage = rankOk(p, Rank.OFICIAL);
            m.paged(list, en -> {
                Guild.MemberData md = en.getValue();
                Player on = Bukkit.getPlayer(en.getKey());
                List<String> l = new ArrayList<>();
                l.add("§7Rango: " + md.rank.colored());
                l.add("§7Estado: " + (on != null ? "§aen línea" : "§8desconectado"));
                l.add("§7Miembro desde hace: §f" + Msg.dur(System.currentTimeMillis() - md.joinedAt));
                if (md.duesPending > 0) l.add("§cCuota pendiente: §e" + md.duesPending);
                if (manage && !en.getKey().equals(p.getUniqueId())) {
                    l.add("");
                    l.add("§eClic para gestionar");
                }
                return head(en.getKey(), md.rank.color + md.name, l);
            }, (en, e) -> {
                if (!manage || en.getKey().equals(p.getUniqueId())) return;
                memberActions(p, en.getKey());
            });
            m.button("volver", "§7Volver", e -> main(p));
            m.button("invitar", "§aInvitar", e -> {
                if (!manage) {
                    msg(p, "§cSolo Oficial o superior.");
                    return;
                }
                invite(p, 0);
            });
        }).at(page).open();
    }

    private void memberActions(Player p, UUID target) {
        menu(p, "miembro_accion", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null || !g.has(target)) {
                members(p, 0);
                return;
            }
            Rank mine = g.rankOf(p.getUniqueId());
            Guild.MemberData td = g.member(target);
            m.cell(4, 0, head(target, td.rank.color + td.name, List.of("§7Rango: " + td.rank.colored())), null);
            boolean canUp = td.rank != Rank.LIDER && td.rank.up().id < mine.id && td.rank.up() != td.rank;
            boolean canDown = td.rank.id < mine.id && td.rank != Rank.RECLUTA;
            boolean canKick = td.rank.id < mine.id;
            boolean leader = mine == Rank.LIDER;
            m.button("ascender", "§aAscender", List.of("§7→ " + (canUp ? td.rank.up().colored() : "§8no disponible")), e -> {
                if (!canUp) return;
                td.rank = td.rank.up();
                plugin.guilds.save();
                plugin.guilds.broadcast(g, "§a" + td.name + " §7ahora es " + td.rank.colored() + "§7.");
                m.refresh();
            });
            m.button("degradar", "§6Degradar", List.of("§7→ " + (canDown ? td.rank.down().colored() : "§8no disponible")), e -> {
                if (!canDown) return;
                td.rank = td.rank.down();
                plugin.guilds.save();
                plugin.guilds.broadcast(g, "§6" + td.name + " §7ahora es " + td.rank.colored() + "§7.");
                m.refresh();
            });
            m.button("expulsar", "§cExpulsar", List.of(canKick ? "§7Echa al jugador del gremio." : "§8no disponible"), e -> {
                if (!canKick) return;
                confirm(p, List.of("§7Expulsar a §c" + td.name), () -> {
                    Guild gg = plugin.guilds.of(p);
                    if (gg == null || !gg.has(target)) return;
                    plugin.guilds.removeMember(gg, target);
                    plugin.guilds.broadcast(gg, "§c" + td.name + " §7fue expulsado del gremio.");
                    Player t = Bukkit.getPlayer(target);
                    if (t != null) msg(t, "§cHas sido expulsado del gremio §6" + gg.name + "§c.");
                    members(p, 0);
                }, () -> memberActions(p, target));
            });
            m.button("lider", "§6Nuevo líder", List.of(leader ? "§7Entregas el liderazgo." : "§8Solo el líder"), e -> {
                if (!leader) return;
                confirm(p, List.of("§7Transferir el liderazgo a §6" + td.name, "§cTú pasarás a Mariscal."), () -> {
                    Guild gg = plugin.guilds.of(p);
                    if (gg == null || !gg.has(target) || !gg.leader.equals(p.getUniqueId())) return;
                    gg.leader = target;
                    gg.member(target).rank = Rank.LIDER;
                    gg.member(p.getUniqueId()).rank = Rank.MARISCAL;
                    plugin.guilds.save();
                    plugin.guilds.broadcast(gg, "§6" + td.name + " §7es el nuevo líder del gremio.");
                    members(p, 0);
                }, () -> memberActions(p, target));
            });
            m.button("volver", "§7Volver", e -> members(p, 0));
        }).open();
    }

    // ------------------------------------------------------------------ solicitudes e invitaciones

    public void requests(Player p, int page) {
        menu(p, "solicitudes", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            List<Map.Entry<UUID, String>> list = new ArrayList<>(g.requests.entrySet());
            m.paged(list, en -> head(en.getKey(), "§f" + en.getValue(), List.of("§aClic: aceptar", "§cClic derecho: rechazar")), (en, e) -> {
                if (!rankOk(p, Rank.OFICIAL)) return;
                if (e.getClick() == ClickType.RIGHT) {
                    plugin.guilds.denyRequest(g, en.getKey());
                } else {
                    String err = plugin.guilds.acceptRequest(g, en.getKey());
                    if (err != null) msg(p, "§c" + err);
                }
                m.refresh();
            });
            m.button("volver", "§7Volver", e -> main(p));
        }).at(page).open();
    }

    public void invite(Player p, int page) {
        menu(p, "invitar", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            List<Player> list = new ArrayList<>();
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (o.equals(p) || plugin.guilds.of(o) != null) continue;
                list.add(o);
            }
            m.paged(list, o -> head(o.getUniqueId(), "§f" + o.getName(), List.of("§aClic: invitar")), (o, e) -> {
                String err = plugin.guilds.invite(g, p, o);
                if (err != null) msg(p, "§c" + err);
            });
            m.button("volver", "§7Volver", e -> main(p));
        }).at(page).open();
    }

    // ------------------------------------------------------------------ banco

    public void bank(Player p) {
        menu(p, "banco", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            List<String> l = new ArrayList<>();
            l.add("§7Saldo: §e" + plugin.currency.fmt(g.bank));
            l.add("§7En tu inventario: §f" + (plugin.currency.isSet() ? plugin.currency.count(p) : 0));
            l.add("§7Retirar: §f" + Rank.parse(plugin.getConfig().getString("banco.retiro-rango", "OFICIAL"), Rank.OFICIAL).display + "+");
            if (!g.bankLog.isEmpty()) {
                l.add("");
                l.add("§8Últimos movimientos:");
                int n = 0;
                for (String s : g.bankLog) {
                    if (n++ >= 8) break;
                    l.add("§8- " + s);
                }
            }
            m.cell(4, 0, it(Material.GOLD_BLOCK, "§6§lBanco de " + g.name, l), null);
            m.button("dep1", "§aDepositar 1", e -> deposit(p, 1, m));
            m.button("dep10", "§aDepositar 10", e -> deposit(p, 10, m));
            m.button("dep64", "§aDepositar 64", e -> deposit(p, 64, m));
            m.button("depall", "§aDepositar todo", e -> deposit(p, -1, m));
            m.button("ret1", "§cRetirar 1", e -> withdraw(p, 1, m));
            m.button("ret10", "§cRetirar 10", e -> withdraw(p, 10, m));
            m.button("ret64", "§cRetirar 64", e -> withdraw(p, 64, m));
            m.button("retall", "§cRetirar todo", e -> withdraw(p, -1, m));
            m.button("volver", "§7Volver", e -> main(p));
        }).open();
    }

    private void deposit(Player p, long amount, Menu m) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return;
        if (!plugin.currency.isSet()) {
            msg(p, "§cLa moneda no está definida.");
            return;
        }
        long have = plugin.currency.count(p);
        if (amount < 0) amount = have;
        if (amount <= 0 || have < amount) {
            msg(p, "§cNo tienes suficientes monedas.");
            return;
        }
        if (!plugin.currency.take(p, amount)) return;
        plugin.guilds.deposit(g, p, amount);
        msg(p, "§aDepositaste §e" + plugin.currency.fmt(amount) + "§a.");
        m.refresh();
    }

    private void withdraw(Player p, long amount, Menu m) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return;
        Rank need = Rank.parse(plugin.getConfig().getString("banco.retiro-rango", "OFICIAL"), Rank.OFICIAL);
        if (!rankOk(p, need)) {
            msg(p, "§cSolo " + need.display + " o superior puede retirar.");
            return;
        }
        if (amount < 0) amount = g.bank;
        if (amount <= 0 || g.bank < amount) {
            msg(p, "§cEl banco no tiene tanto.");
            return;
        }
        if (!plugin.guilds.withdraw(g, p, amount)) return;
        plugin.currency.give(p, amount);
        msg(p, "§aRetiraste §e" + plugin.currency.fmt(amount) + "§a.");
        m.refresh();
    }

    // ------------------------------------------------------------------ mejoras

    private long upgradeCost(String key, int current) {
        return plugin.getConfig().getLong("mejoras." + key + ".costo-base", 32) * (current + 1);
    }

    public void upgrades(Player p) {
        menu(p, "mejoras", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            Territory t = plugin.territories.ownedBy(g);
            m.cell(4, 0, it(Material.ANVIL, "§9§lMejoras de " + g.name, "§7Banco: §e" + plugin.currency.fmt(g.bank), "§7Nivel de gremio: §f" + g.level,
                    "§7Territorio: §f" + Guild.levelName(g.territoryLevel), "", "§8Las mejoras se pagan del banco", "§8(Mariscal o superior)."), null);

            // territorio
            List<String> tl = new ArrayList<>();
            if (t == null) tl.add("§cNecesitas un territorio.");
            else if (g.territoryLevel >= 3) tl.add("§6Ya es un Reinado: nivel máximo.");
            else {
                int next = g.territoryLevel + 1;
                long cost = plugin.territories.upgradeCost(t, next);
                int minLvl = plugin.territories.upgradeMinGuildLevel(next);
                tl.add("§7Siguiente: §a" + Guild.levelName(next));
                tl.add("§7Coste: §e" + plugin.currency.fmt(cost));
                tl.add("§7Nivel de gremio: §f" + minLvl + (g.level >= minLvl ? " §a✔" : " §c✘"));
                tl.add("§7Banco: " + (g.bank >= cost ? "§a✔" : "§c✘"));
                tl.add(next == 2 ? "§8Desbloquea: Herrero, Capitán, taller" : "§8Desbloquea: Embajador y 40 miembros");
                tl.add("");
                tl.add("§aClic para mejorar");
            }
            m.button("territorio_up", "§6Mejorar territorio", tl, e -> upgradeTerritory(p));
            upgradeButton(m, p, "guardia", "§aGuardia", "upGuard", "Más guardias durante los asedios (+2 por nivel).");
            upgradeButton(m, p, "nucleo", "§9Núcleo", "upCore", "Más vida del Nodo (+" + plugin.getConfig().getInt("mejoras.nucleo.vida-por-nivel", 150) + " por nivel).");
            m.button("bandera", "§dBandera del gremio", List.of("§7Elige el estandarte que ondea", "§7sobre tu territorio."), e -> plugin.extraGuis.banner(p));
            m.button("torres", "§6Torres de Ballesta", List.of("§7Defensa automática en asedios.", "§7Compra y mejora."), e -> plugin.extraGuis.turrets(p, 0));
            upgradeButton(m, p, "capacidad", "§5Capacidad", "upCapacity", "Más miembros (+" + plugin.getConfig().getInt("mejoras.capacidad.miembros-por-nivel", 5) + " por nivel).");
            m.button("volver", "§7Volver", e -> main(p));
        }).open();
    }

    private int upLevel(Guild g, String field) {
        return switch (field) {
            case "upGuard" -> g.upGuard;
            case "upCore" -> g.upCore;
            default -> g.upCapacity;
        };
    }

    private void upgradeButton(Menu m, Player p, String plaque, String name, String field, String desc) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return;
        String cfg = switch (field) {
            case "upGuard" -> "guardia";
            case "upCore" -> "nucleo";
            default -> "capacidad";
        };
        int cur = upLevel(g, field);
        int max = plugin.getConfig().getInt("mejoras." + cfg + ".max", 5);
        List<String> l = new ArrayList<>();
        l.add("§7" + desc);
        l.add("§7Nivel: §f" + cur + "§7/§f" + max);
        if (cur < max) {
            l.add("§7Coste: §e" + plugin.currency.fmt(upgradeCost(cfg, cur)));
            if (!cfg.equals("capacidad") && g.territoryLevel < 2) l.add("§cRequiere Fortaleza.");
            l.add("§aClic para mejorar");
        } else l.add("§6Máximo.");
        m.button(plaque, name, l, e -> {
            Guild gg = plugin.guilds.of(p);
            if (gg == null) return;
            if (!rankOk(p, Rank.MARISCAL)) {
                msg(p, "§cSolo Mariscal o superior.");
                return;
            }
            int c = upLevel(gg, field);
            if (c >= max) return;
            if (!cfg.equals("capacidad") && gg.territoryLevel < 2) {
                msg(p, "§cRequiere un territorio de nivel Fortaleza.");
                return;
            }
            long cost = upgradeCost(cfg, c);
            if (gg.bank < cost) {
                msg(p, "§cEl banco necesita §e" + plugin.currency.fmt(cost) + "§c.");
                return;
            }
            gg.bank -= cost;
            gg.log("§6-" + Msg.num(cost) + " §7mejora " + cfg);
            switch (field) {
                case "upGuard" -> gg.upGuard++;
                case "upCore" -> gg.upCore++;
                default -> gg.upCapacity++;
            }
            plugin.guilds.save();
            plugin.guilds.broadcast(gg, "§aMejora §6" + cfg + " §asubida al nivel " + (c + 1) + ".");
            m.refresh();
        });
    }

    private void upgradeTerritory(Player p) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return;
        Territory t = plugin.territories.ownedBy(g);
        if (t == null || g.territoryLevel >= 3) return;
        if (!rankOk(p, Rank.MARISCAL)) {
            msg(p, "§cSolo Mariscal o superior.");
            return;
        }
        if (plugin.raids.of(g) != null) {
            msg(p, "§cNo puedes mejorar durante un asedio.");
            return;
        }
        int next = g.territoryLevel + 1;
        long cost = plugin.territories.upgradeCost(t, next);
        int minLvl = plugin.territories.upgradeMinGuildLevel(next);
        if (g.level < minLvl) {
            msg(p, "§cTu gremio necesita nivel " + minLvl + ".");
            return;
        }
        if (g.bank < cost) {
            msg(p, "§cEl banco necesita §e" + plugin.currency.fmt(cost) + "§c.");
            return;
        }
        confirm(p, List.of("§7Mejorar a §a" + Guild.levelName(next), "§7Coste: §e" + plugin.currency.fmt(cost)), () -> {
            Guild gg = plugin.guilds.of(p);
            if (gg == null || gg.territoryLevel != next - 1 || gg.bank < cost) return;
            gg.bank -= cost;
            gg.log("§6-" + Msg.num(cost) + " §7mejora territorio");
            plugin.territories.upgrade(gg);
            Bukkit.broadcastMessage(Msg.PREFIX + "§6" + gg.name + " §7ha ascendido su territorio a §a" + Guild.levelName(gg.territoryLevel) + "§7.");
            upgrades(p);
        }, () -> upgrades(p));
    }

    // ------------------------------------------------------------------ impuestos

    public void taxes(Player p) {
        menu(p, "impuestos", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            Guild.MemberData me = g.member(p.getUniqueId());
            long now = System.currentTimeMillis();
            List<String> l = new ArrayList<>();
            if (g.hasTerritory()) {
                l.add("§7Mantenimiento: §e" + plugin.currency.fmt(plugin.guilds.upkeepCost(g)) + " §7cada §f" + plugin.getConfig().getDouble("mantenimiento.dias", 7.0) + " días");
                l.add("§7Próximo cobro: §f" + Msg.dur(g.nextUpkeep - now));
                l.add("§7Banco: §e" + plugin.currency.fmt(g.bank));
                l.add("§7Impagos: " + (g.upkeepMissed > 0 ? "§c" : "§a") + g.upkeepMissed + "§7/§f" + plugin.getConfig().getInt("mantenimiento.impagos-max", 3));
            } else l.add("§7Sin territorio: no hay mantenimiento.");
            l.add("");
            l.add("§7Cuota por miembro: §e" + g.dues);
            l.add("§7Tu cuota pendiente: " + (me != null && me.duesPending > 0 ? "§c" + me.duesPending : "§a0"));
            m.cell(4, 0, it(Material.BOOK, "§6§lImpuestos", l), null);
            m.button("pagar", "§aPagar mi cuota", List.of("§7Pagas lo pendiente al banco."), e -> {
                Guild gg = plugin.guilds.of(p);
                if (gg == null) return;
                Guild.MemberData md = gg.member(p.getUniqueId());
                if (md == null || md.duesPending <= 0) {
                    msg(p, "§7No tienes cuota pendiente.");
                    return;
                }
                if (!plugin.currency.take(p, md.duesPending)) {
                    msg(p, "§cNo tienes suficientes monedas.");
                    return;
                }
                plugin.guilds.deposit(gg, p, md.duesPending);
                md.duesPending = 0;
                plugin.guilds.save();
                m.refresh();
            });
            m.button("cuota_mas", "§6Cuota +", List.of("§7Clic: +1  |  Mayús: +10"), e -> changeDues(p, e.getClick().isShiftClick() ? 10 : 1, m));
            m.button("cuota_menos", "§6Cuota -", List.of("§7Clic: -1  |  Mayús: -10"), e -> changeDues(p, e.getClick().isShiftClick() ? -10 : -1, m));
            m.button("mant", "§6Pagar mantenimiento", List.of("§7Salda un impago con el banco."), e -> {
                Guild gg = plugin.guilds.of(p);
                if (gg == null || !gg.hasTerritory()) return;
                if (gg.upkeepMissed <= 0) {
                    msg(p, "§7No hay impagos.");
                    return;
                }
                long cost = plugin.guilds.upkeepCost(gg);
                if (gg.bank < cost) {
                    msg(p, "§cEl banco necesita §e" + plugin.currency.fmt(cost) + "§c.");
                    return;
                }
                gg.bank -= cost;
                gg.upkeepMissed = Math.max(0, gg.upkeepMissed - 1);
                gg.log("§6-" + Msg.num(cost) + " §7mantenimiento atrasado");
                plugin.guilds.save();
                m.refresh();
            });
            m.button("volver", "§7Volver", e -> main(p));
        }).open();
    }

    private void changeDues(Player p, long delta, Menu m) {
        Guild g = plugin.guilds.of(p);
        if (g == null) return;
        if (!rankOk(p, Rank.MARISCAL)) {
            msg(p, "§cSolo Mariscal o superior.");
            return;
        }
        g.dues = Math.max(0, Math.min(10000, g.dues + delta));
        plugin.guilds.save();
        m.refresh();
    }

    // ------------------------------------------------------------------ guerra

    public void war(Player p, int page) {
        menu(p, "guerra", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            long now = System.currentTimeMillis();
            List<Territory> list = new ArrayList<>();
            for (Territory t : plugin.territories.all()) {
                if (t.ownerGuildId != null && !t.ownerGuildId.equals(g.id)) list.add(t);
            }
            m.paged(list, t -> {
                Guild d = plugin.guilds.byId(t.ownerGuildId);
                List<String> l = new ArrayList<>();
                l.add("§7Dueño: §6" + (d == null ? "?" : d.name));
                l.add("§7Nivel: §f" + (d == null ? "?" : Guild.levelName(d.territoryLevel)) + " §8| Gremio nivel " + (d == null ? "?" : d.level));
                l.add("§7Defensores en línea: §f" + (d == null ? 0 : plugin.guilds.online(d)));
                if (d != null) {
                    if (plugin.guilds.isAllied(g, d)) l.add("§aAliado");
                    else if (d.protectedUntil > now) l.add("§bProtegido: §f" + Msg.dur(d.protectedUntil - now));
                    Raid r = plugin.raids.get(t.id);
                    if (r != null) l.add("§cYa hay un asedio aquí (" + r.state.name().toLowerCase() + ")");
                }
                l.add("");
                l.add("§cClic para declarar asedio");
                return it(levelMat(d == null ? 0 : d.territoryLevel), "§6" + t.name, l);
            }, (t, e) -> {
                long cost = plugin.getConfig().getLong("raid.costo-declarar", 16);
                confirm(p, List.of("§7Declarar asedio a §6" + t.name, "§7Coste del banco: §e" + plugin.currency.fmt(cost),
                        "§7Empieza en §f" + Msg.dur(plugin.getConfig().getLong("raid.aviso-minutos", 720) * 60_000L)), () -> {
                    String err = plugin.raids.declare(p, t);
                    if (err != null) msg(p, "§c" + err);
                    war(p, 0);
                }, () -> war(p, 0));
            });
            m.button("volver", "§7Volver", e -> main(p));
            Raid mine = plugin.raids.of(g);
            List<String> sl = new ArrayList<>();
            if (mine == null) sl.add("§7Sin asedios.");
            else {
                sl.add("§7" + mine.attackerName + " §c→ §7" + mine.defenderName);
                switch (mine.state) {
                    case SCHEDULED -> sl.add("§7Empieza en §f" + Msg.dur(mine.startAt - now));
                    case ACTIVE -> sl.add("§4En curso §7- quedan §f" + Msg.dur(mine.endAt - now) + " §7| Nodo §c" + (int) mine.nodeHp + "§7/" + (int) mine.nodeMax);
                    case LOOT -> sl.add("§6Saqueo: §f" + Msg.dur(mine.lootEndAt - now));
                    default -> {
                    }
                }
                if (mine.state == Raid.State.SCHEDULED && mine.attackerId.equals(g.id)) sl.add("§cClic: cancelar asedio");
            }
            m.button("estado", "§cMi asedio", sl, e -> {
                Raid r = plugin.raids.of(g);
                if (r != null && r.state == Raid.State.SCHEDULED && r.attackerId.equals(g.id)) {
                    confirm(p, List.of("§7Cancelar el asedio contra §6" + r.defenderName, "§cNo se devuelve el coste."), () -> {
                        String err = plugin.raids.cancelDeclared(p);
                        if (err != null) msg(p, "§c" + err);
                        war(p, 0);
                    }, () -> war(p, 0));
                }
            });
        }).at(page).open();
    }

    // ------------------------------------------------------------------ taller de asedio

    public void shop(Player p, int page) {
        menu(p, "taller", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            List<String> ids = new ArrayList<>(List.of("CANON", "CATAPULTA", "ARIETE", "BALA_HIERRO", "BALA_EXPLOSIVA", "BALA_INCENDIARIA", "PIEDRA_CATAPULTA", "PIEDRA_FUEGO"));
            m.paged(ids, id -> {
                ItemStack base = shopItem(id);
                ItemStack icon = base.clone();
                var meta = icon.getItemMeta();
                List<String> lore = meta.getLore() == null ? new ArrayList<>() : new ArrayList<>(meta.getLore());
                lore.add("");
                lore.add("§7Precio: §e" + plugin.currency.fmt(shopPrice(id)));
                lore.add("§aClic para comprar");
                meta.setLore(lore);
                icon.setItemMeta(meta);
                return icon;
            }, (id, e) -> {
                Guild gg = plugin.guilds.of(p);
                if (gg == null || !rankOk(p, Rank.MIEMBRO)) {
                    msg(p, "§cLos reclutas no pueden comprar armas.");
                    return;
                }
                long price = shopPrice(id);
                if (!plugin.currency.take(p, price)) {
                    msg(p, "§cNecesitas §e" + plugin.currency.fmt(price) + "§c.");
                    return;
                }
                ItemStack give = shopItem(id);
                for (ItemStack o : p.getInventory().addItem(give).values()) p.getWorld().dropItemNaturally(p.getLocation(), o);
                msg(p, "§aComprado: §f" + (give.getItemMeta().getDisplayName()) + " §7x" + give.getAmount());
            });
            m.button("volver", "§7Volver", e -> main(p));
        }).at(page).open();
    }

    private ItemStack shopItem(String id) {
        if (id.equals("CANON")) return SiegeItems.unitItem(plugin, SiegeItems.UnitType.CANON);
        if (id.equals("CATAPULTA")) return SiegeItems.unitItem(plugin, SiegeItems.UnitType.CATAPULTA);
        if (id.equals("ARIETE")) return SiegeItems.unitItem(plugin, SiegeItems.UnitType.ARIETE);
        SiegeItems.Ammo a = SiegeItems.Ammo.valueOf(id);
        return SiegeItems.ammoItem(plugin, a, plugin.getConfig().getInt("asedio.cantidad-compra." + id, 4));
    }

    private long shopPrice(String id) {
        return plugin.getConfig().getLong("asedio.precios." + id, 16);
    }

    // ------------------------------------------------------------------ obras

    public void works(Player p, int page) {
        menu(p, "obras", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            Territory t = plugin.territories.ownedBy(g);
            if (t == null) {
                m.cell(4, 4, it(Material.BARRIER, "§cSin territorio", "§7Tu gremio no tiene territorio."), null);
                m.button("volver", "§7Volver", e -> main(p));
                return;
            }
            List<RepairManager.Sector> secs = plugin.repair.sectors(t);
            int total = plugin.territories.damageCount(t);
            m.cell(4, 4, it(Material.ANVIL, "§6§lMaestro de Obras", "§7Bloques dañados: §f" + total, "§7Sectores: §f" + secs.size(),
                    "§7Reparar con tu inventario: te pido los", "§7materiales de los bloques originales.", "§7Mayús+clic: pagar con el banco",
                    "§7(1 Corona cada §f" + plugin.getConfig().getInt("obras.bloques-por-corona", 25) + " §7bloques)."), null);
            m.paged(secs, s -> {
                List<String> l = new ArrayList<>();
                l.add("§7Bloques dañados: §f" + s.blocks.size());
                l.add("§7Zona: §fx " + (s.cx * 16) + " a " + (s.cx * 16 + 15) + ", z " + (s.cz * 16) + " a " + (s.cz * 16 + 15));
                l.add("");
                l.add("§7Materiales necesarios:");
                int n = 0;
                Map<Material, Integer> needs = plugin.repair.needs(s.blocks);
                for (Map.Entry<Material, Integer> en : needs.entrySet()) {
                    if (n++ >= 7) {
                        l.add("§8...");
                        break;
                    }
                    l.add("§8- §f" + en.getValue() + " §7" + en.getKey().name().toLowerCase().replace('_', ' '));
                }
                l.add("");
                l.add("§aClic: reparar con inventario");
                l.add("§6Mayús+clic: pagar §e" + plugin.currency.fmt(plugin.repair.bankCost(s.blocks.size())) + " §6del banco");
                return it(Material.BRICKS, "§6Sector " + s.cx + "," + s.cz, l);
            }, (s, e) -> doRepair(p, t, s, e.getClick().isShiftClick(), m));
            m.button("volver", "§7Volver", e -> main(p));
            m.button("todo", "§6Reparar todo", List.of("§7Clic: con tu inventario", "§7Mayús+clic: con el banco"), e -> doRepair(p, t, null, e.getClick().isShiftClick(), m));
        }).at(page).open();
    }

    private void doRepair(Player p, Territory t, RepairManager.Sector s, boolean bank, Menu m) {
        if (plugin.raids.get(t.id) != null) {
            msg(p, "§cNo se puede reparar durante un asedio.");
            return;
        }
        RepairManager.Result r = plugin.repair.repair(p, t, s, bank);
        if (r.error != null) {
            msg(p, "§c" + r.error);
            return;
        }
        msg(p, "§aReparados §f" + r.repaired + " §abloques." + (r.paid > 0 ? " Pagado: §e" + plugin.currency.fmt(r.paid) + "§a." : "") + (r.remaining > 0 ? " §7Quedan §f" + r.remaining + "§7." : ""));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 1f, 1f);
        m.refresh();
    }

    // ------------------------------------------------------------------ alianzas

    public void alliances(Player p, int page) {
        menu(p, "alianzas", m -> {
            Guild g = plugin.guilds.of(p);
            if (g == null) {
                p.closeInventory();
                return;
            }
            List<Guild> list = new ArrayList<>();
            for (Guild o : plugin.guilds.all()) if (o != g) list.add(o);
            list.sort(Comparator.comparing((Guild o) -> g.allies.contains(o.id) ? 0 : g.allyRequestsIn.contains(o.id) ? 1 : 2).thenComparing(o -> o.name));
            m.paged(list, o -> {
                List<String> l = new ArrayList<>();
                l.add("§7Nivel: §f" + o.level + " §8| Miembros: " + o.members.size());
                if (g.allies.contains(o.id)) {
                    l.add("§aAliado");
                    l.add("§cClic: romper alianza");
                    return it(Material.GREEN_BANNER, "§a" + o.name, l);
                }
                if (g.allyRequestsIn.contains(o.id)) {
                    l.add("§eTe propone una alianza");
                    l.add("§aClic: aceptar");
                    return it(Material.YELLOW_BANNER, "§e" + o.name, l);
                }
                if (o.allyRequestsIn.contains(g.id)) {
                    l.add("§7Propuesta enviada");
                    return it(Material.LIGHT_GRAY_BANNER, "§7" + o.name, l);
                }
                l.add("§aClic: proponer alianza");
                return it(Material.WHITE_BANNER, "§f" + o.name, l);
            }, (o, e) -> {
                if (!rankOk(p, Rank.MARISCAL)) {
                    msg(p, "§cSolo Mariscal o superior gestiona alianzas.");
                    return;
                }
                if (plugin.raids.of(g) != null || plugin.raids.of(o) != null) {
                    msg(p, "§cNo se puede durante un asedio.");
                    return;
                }
                if (g.allies.contains(o.id)) {
                    confirm(p, List.of("§7Romper la alianza con §6" + o.name), () -> {
                        plugin.guilds.breakAlly(g, o);
                        alliances(p, 0);
                    }, () -> alliances(p, 0));
                } else if (g.allyRequestsIn.contains(o.id)) {
                    plugin.guilds.acceptAlly(g, o);
                    m.refresh();
                } else if (!o.allyRequestsIn.contains(g.id)) {
                    String err = plugin.guilds.proposeAlly(g, o);
                    if (err != null) msg(p, "§c" + err);
                    m.refresh();
                }
            });
            m.button("volver", "§7Volver", e -> main(p));
        }).at(page).open();
    }

    // ------------------------------------------------------------------ ranking y mapa

    public void ranking(Player p, int page) {
        menu(p, "ranking", m -> {
            Guild mine = plugin.guilds.of(p);
            List<Guild> list = new ArrayList<>(plugin.guilds.all());
            list.sort(Comparator.comparingInt((Guild g) -> g.level).thenComparingLong(g -> g.xp).reversed());
            int[] pos = {0};
            m.paged(list, g -> {
                int rank = list.indexOf(g) + 1;
                List<String> l = new ArrayList<>();
                l.add("§7Nivel: §e" + g.level + " §8| Miembros: §f" + g.members.size());
                l.add("§7Territorio: §f" + Guild.levelName(g.territoryLevel));
                if (mine == null) {
                    l.add("");
                    l.add("§aClic: solicitar unirte");
                }
                Material mat = rank == 1 ? Material.GOLD_BLOCK : rank == 2 ? Material.IRON_BLOCK : rank == 3 ? Material.COPPER_BLOCK : Material.WHITE_BANNER;
                return it(mat, "§6#" + rank + " §f" + g.name, l);
            }, (g, e) -> {
                if (plugin.guilds.of(p) != null) return;
                confirm(p, List.of("§7Solicitar unirte a §6" + g.name), () -> {
                    String err = plugin.guilds.request(g, p);
                    msg(p, err != null ? "§c" + err : "§aSolicitud enviada.");
                    ranking(p, 0);
                }, () -> ranking(p, 0));
            });
            m.button("volver", "§7Volver", e -> {
                if (plugin.guilds.of(p) != null) main(p);
                else noGuild(p);
            });
        }).at(page).open();
    }

    public void territories(Player p, int page) {
        menu(p, "territorios", m -> {
            List<Territory> list = new ArrayList<>(plugin.territories.all());
            list.sort(Comparator.comparing((Territory t) -> t.name));
            m.paged(list, t -> {
                Guild o = plugin.guilds.byId(t.ownerGuildId);
                List<String> l = new ArrayList<>();
                l.add("§7Tamaño: §f" + t.shortSize());
                l.add("§7Dueño: " + (o == null ? "§aLibre" : "§6" + o.name));
                if (o != null) l.add("§7Nivel: §f" + Guild.levelName(o.territoryLevel));
                else l.add("§7Precio: §e" + plugin.currency.fmt(t.price));
                Raid r = plugin.raids.get(t.id);
                if (r != null) l.add("§cAsedio: " + r.state.name().toLowerCase());
                return it(o == null ? Material.GRASS_BLOCK : levelMat(o.territoryLevel), "§6" + t.name, l);
            }, null);
            m.button("volver", "§7Volver", e -> {
                if (plugin.guilds.of(p) != null) main(p);
                else noGuild(p);
            });
        }).at(page).open();
    }

    // ------------------------------------------------------------------ confirmar

    public void confirm(Player p, List<String> lore, Runnable yes, Runnable no) {
        menu(p, "confirmar", m -> {
            m.cell(4, 0, it(Material.PAPER, "§e¿Seguro?", lore), null);
            m.button("si", "§aConfirmar", e -> {
                p.closeInventory();
                yes.run();
            });
            m.button("no", "§cCancelar", e -> {
                if (no != null) no.run();
                else p.closeInventory();
            });
        }).open();
    }

    // ------------------------------------------------------------------ admin

    public void admin(Player p) {
        if (!p.hasPermission("gremios.admin")) {
            msg(p, "§cSin permiso.");
            return;
        }
        menu(p, "admin", m -> {
            m.button("terr", "§3Territorios", List.of("§7Lista, teleport y reinicio."), e -> adminTerritories(p, 0));
            m.button("raids", "§cAsedios", List.of("§7Asedios en curso."), e -> adminRaids(p, 0));
            m.button("gremios", "§9Gremios", List.of("§7Ranking completo."), e -> ranking(p, 0));
            m.button("zonas", "§6Zonas de recursos", List.of("§7Minas, talas, canteras...", "§7Conquistables por gremios y jugadores."), e -> plugin.extraGuis.adminZones(p, 0));
            m.button("eventos", "§6Eventos", List.of("§7KOTH y jefes: zona, tiempos y recompensas."), e -> plugin.extraGuis.adminEvents(p, 0));
            m.button("cerrar", "§7Cerrar", e -> p.closeInventory());
        }).open();
    }

    private void adminTerritories(Player p, int page) {
        menu(p, "admin_terr", m -> {
            List<Territory> list = new ArrayList<>(plugin.territories.all());
            m.paged(list, t -> {
                Guild o = plugin.guilds.byId(t.ownerGuildId);
                List<String> l = new ArrayList<>();
                l.add("§7id: §f" + t.id + " §8| " + t.shortSize());
                l.add("§7Dueño: " + (o == null ? "§aLibre" : "§6" + o.name));
                l.add("§7Precio: §e" + t.price);
                l.add("§7Snapshot: " + yn(plugin.snapshots.has(t)));
                l.add("§7Bloques dañados: §f" + plugin.territories.damageCount(t));
                l.add("");
                l.add("§aClic: teletransportarte");
                l.add("§cMayús+clic: reiniciar (restaura el original)");
                return it(o == null ? Material.GRASS_BLOCK : levelMat(o.territoryLevel), "§6" + t.name, l);
            }, (t, e) -> {
                if (e.getClick().isShiftClick()) {
                    confirm(p, List.of("§cReiniciar §6" + t.name, "§7Se libera y se restaura el terreno original."), () -> {
                        plugin.territories.release(t, true, () -> msg(p, "§aTerritorio restaurado."));
                        msg(p, "§7Restaurando...");
                    }, () -> adminTerritories(p, 0));
                } else {
                    Location l = plugin.territories.spawn(t);
                    if (l != null) {
                        p.closeInventory();
                        p.teleport(l);
                    }
                }
            });
            m.button("volver", "§7Volver", e -> admin(p));
        }).at(page).open();
    }

    private void adminRaids(Player p, int page) {
        menu(p, "admin_raids", m -> {
            List<Raid> list = new ArrayList<>(plugin.raids.all());
            long now = System.currentTimeMillis();
            m.paged(list, r -> {
                List<String> l = new ArrayList<>();
                l.add("§7" + r.attackerName + " §c→ §7" + r.defenderName);
                l.add("§7Estado: §f" + r.state.name());
                if (r.state == Raid.State.SCHEDULED) l.add("§7Empieza en §f" + Msg.dur(r.startAt - now));
                if (r.state == Raid.State.ACTIVE) l.add("§7Nodo §c" + (int) r.nodeHp + "§7/" + (int) r.nodeMax + " §8| quedan " + Msg.dur(r.endAt - now));
                l.add("");
                l.add("§aClic: teletransportarte");
                l.add("§cMayús+clic: cancelar asedio");
                return it(Material.IRON_SWORD, "§6" + r.territoryId, l);
            }, (r, e) -> {
                Territory t = plugin.territories.get(r.territoryId);
                if (t == null) return;
                if (e.getClick().isShiftClick()) {
                    plugin.raids.forceEnd(t, "cancelar");
                    m.refresh();
                } else {
                    Location l = plugin.territories.spawn(t);
                    if (l != null) {
                        p.closeInventory();
                        p.teleport(l);
                    }
                }
            });
            m.button("volver", "§7Volver", e -> admin(p));
        }).at(page).open();
    }
}
