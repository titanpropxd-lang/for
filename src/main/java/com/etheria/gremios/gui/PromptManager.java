package com.etheria.gremios.gui;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Pide texto por chat (nombre del gremio...) y gestiona el chat de gremio. */
public class PromptManager implements Listener {
    private final GremiosPlugin plugin;
    private final Map<UUID, Consumer<String>> prompts = new HashMap<>();

    public PromptManager(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    public void ask(Player p, String message, Consumer<String> handler) {
        prompts.put(p.getUniqueId(), handler);
        p.closeInventory();
        Msg.to(p, message);
        Msg.to(p, "§8Escribe §7cancelar §8para salir.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        final String msg = e.getMessage();
        Consumer<String> c = prompts.remove(p.getUniqueId());
        if (c != null) {
            e.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (msg.trim().equalsIgnoreCase("cancelar")) Msg.to(p, "§7Cancelado.");
                else c.accept(msg.trim());
            });
            return;
        }
        if (plugin.guilds.inGuildChat(p.getUniqueId())) {
            Guild g = plugin.guilds.of(p);
            if (g != null) {
                e.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> plugin.guilds.chat(g, p, msg));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }
}
