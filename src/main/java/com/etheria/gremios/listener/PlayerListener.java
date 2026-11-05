package com.etheria.gremios.listener;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Avisos al conectarse y gestión de las barras de asedio. */
public class PlayerListener implements Listener {
    private final GremiosPlugin plugin;

    public PlayerListener(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Guild g = plugin.guilds.of(p);
            // nombre actualizado en la lista de miembros
            if (g != null) {
                Guild.MemberData md = g.member(p.getUniqueId());
                if (md != null && !md.name.equals(p.getName())) {
                    md.name = p.getName();
                    plugin.guilds.save();
                }
                if (md != null && md.duesPending > 0) Msg.to(p, "§eTienes una cuota de gremio pendiente: §6" + md.duesPending + "§e (menú Impuestos).");
                Raid r = plugin.raids.of(g);
                if (r != null && r.state == Raid.State.ACTIVE && r.bar != null) r.bar.addPlayer(p);
                if (r != null && r.state == Raid.State.SCHEDULED) {
                    Msg.to(p, "§c⚔ Asedio programado en §f" + Msg.dur(r.startAt - System.currentTimeMillis()) + "§c.");
                }
                return;
            }
            if (plugin.guilds.getInvite(p.getUniqueId()) != null) {
                Msg.to(p, "§eTienes una invitación de gremio pendiente. Usa §f/gremio§e.");
            } else if (plugin.guilds.canCreate(p.getUniqueId())) {
                Msg.to(p, "§aPuedes fundar un gremio. Usa §f/gremio§a para elegir tu territorio.");
            }
        }, 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Guild g = plugin.guilds.of(e.getPlayer());
        if (g == null) return;
        Raid r = plugin.raids.of(g);
        if (r != null && r.bar != null) r.bar.removePlayer(e.getPlayer());
    }
}
