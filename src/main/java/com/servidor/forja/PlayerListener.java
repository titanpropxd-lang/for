package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class PlayerListener implements Listener {
    private final ForjaPlugin plugin;

    public PlayerListener(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.professions.load(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.adminMode.remove(p.getUniqueId());
        ForgeMenu m = plugin.menus.remove(p.getUniqueId());
        if (m != null && !m.suspendForDisconnect()) m.cancelSession(null);
        plugin.accessories.clear(p);
        plugin.professions.unload(p.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        ForgeMenu m = plugin.menus.get(e.getEntity().getUniqueId());
        if (m != null) m.cancelSession(null);
        if (plugin.getConfig().getBoolean("accesorios.caer_al_morir", false)) plugin.accessories.dropOnDeath(e.getEntity());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.accessories.apply(p), 2L);
    }
}
