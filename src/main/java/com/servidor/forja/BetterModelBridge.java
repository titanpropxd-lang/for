package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.logging.Level;

/** Adaptador opcional y sin dependencia de compilación para BetterModel 2.x. */
final class BetterModelBridge {
    private final ForjaPlugin plugin;
    private boolean warned;
    BetterModelBridge(ForjaPlugin plugin) { this.plugin=plugin; }

    void spawn(String key, Location location, long lifetime) {
        if (Bukkit.getPluginManager().getPlugin("BetterModel") == null) return;
        try {
            Class<?> api=Class.forName("kr.toxicity.model.api.BetterModel");
            Object optional=api.getMethod("model",String.class).invoke(null,key);
            if (!(optional instanceof Optional<?> opt) || opt.isEmpty()) return;
            Object renderer=opt.get();
            Class<?> adapter=Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            Object platformLocation=adapter.getMethod("adapt",Location.class).invoke(null,location);
            Object tracker=null;
            for(Method m:renderer.getClass().getMethods()) {
                if(m.getName().equals("create") && m.getParameterCount()==1) {
                    tracker=m.invoke(renderer,platformLocation); break;
                }
            }
            if(tracker==null)return;
            Class<?> platformPlayer=Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            Method adaptPlayer=platformPlayer.getMethod("adapt",Player.class);
            Method spawn=tracker.getClass().getMethod("spawn",Class.forName("kr.toxicity.model.api.platform.PlatformPlayer"));
            for(Player viewer:location.getWorld().getPlayers()) {
                if(viewer.getLocation().distanceSquared(location)>32*32)continue;
                spawn.invoke(tracker,adaptPlayer.invoke(null,viewer));
            }
            try { tracker.getClass().getMethod("animate",String.class).invoke(tracker,"spin"); } catch (NoSuchMethodException ignored) {}
            Object finalTracker=tracker;
            Bukkit.getScheduler().runTaskLater(plugin,()->{ try { finalTracker.getClass().getMethod("close").invoke(finalTracker); } catch(Exception ignored){} },Math.max(1,lifetime));
        } catch (ClassNotFoundException ignored) {
            if(!warned){plugin.getLogger().info("BetterModel no está disponible; se usarán efectos de partículas.");warned=true;}
        } catch (Exception ex) {
            if(!warned){plugin.getLogger().log(Level.WARNING,"No se pudo mostrar un prop BetterModel '"+key+"'. Se mantiene el efecto de partículas.",ex);warned=true;}
        }
    }
}
