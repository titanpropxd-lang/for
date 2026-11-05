package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Territory;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/**
 * Dibuja los territorios en Dynmap (si está instalado) usando reflexión: no hace falta compilar contra su API.
 * Verde = libre, azul = con dueño, rojo = en asedio.
 */
public class DynmapHook {
    private final GremiosPlugin plugin;
    private Object markerApi;
    private Object set;
    private Class<?> cApi, cSet, cArea;
    private final Set<String> drawn = new HashSet<>();

    public DynmapHook(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean init() {
        try {
            Plugin dm = Bukkit.getPluginManager().getPlugin("dynmap");
            if (dm == null || !dm.isEnabled()) return false;
            ClassLoader cl = dm.getClass().getClassLoader();
            cApi = Class.forName("org.dynmap.markers.MarkerAPI", true, cl);
            cSet = Class.forName("org.dynmap.markers.MarkerSet", true, cl);
            cArea = Class.forName("org.dynmap.markers.AreaMarker", true, cl);
            markerApi = dm.getClass().getMethod("getMarkerAPI").invoke(dm);
            if (markerApi == null) return false;
            Method getSet = cApi.getMethod("getMarkerSet", String.class);
            set = getSet.invoke(markerApi, "gremios.territorios");
            if (set == null) {
                Method create = cApi.getMethod("createMarkerSet", String.class, String.class, java.util.Set.class, boolean.class);
                set = create.invoke(markerApi, "gremios.territorios", "Territorios", null, false);
            }
            if (set == null) return false;
            long period = Math.max(5, plugin.getConfig().getLong("dynmap.refrescar-segundos", 30)) * 20L;
            Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 100L, period);
            plugin.getLogger().info("Dynmap detectado: los territorios aparecerán en el mapa.");
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("No se pudo conectar con Dynmap: " + t);
            return false;
        }
    }

    public void refresh() {
        try {
            Set<String> now = new HashSet<>();
            Method find = cSet.getMethod("findAreaMarker", String.class);
            Method create = cSet.getMethod("createAreaMarker", String.class, String.class, boolean.class, String.class, double[].class, double[].class, boolean.class);
            for (Territory t : plugin.territories.all()) {
                Guild o = plugin.guilds.byId(t.ownerGuildId);
                Raid r = plugin.raids.get(t.id);
                boolean war = r != null && r.state == Raid.State.ACTIVE;
                String label = "<b>" + esc(t.name) + "</b><br>" + (o == null ? "Libre" : "Gremio: " + esc(o.name) + "<br>Nivel: " + o.territoryLevel)
                        + (war ? "<br><b style='color:red'>¡EN ASEDIO!</b>" : "");
                int color = war ? 0xFF2020 : (o == null ? 0x30C030 : 0x3070E0);
                double[] xs = {t.minX, t.maxX + 1};
                double[] zs = {t.minZ, t.maxZ + 1};
                String id = "terr_" + t.id;
                Object m = find.invoke(set, id);
                if (m == null) m = create.invoke(set, id, label, true, t.world, xs, zs, false);
                if (m == null) continue;
                Class<?> c = cArea;
                c.getMethod("setLabel", String.class, boolean.class).invoke(m, label, true);
                c.getMethod("setCornerLocations", double[].class, double[].class).invoke(m, xs, zs);
                c.getMethod("setFillStyle", double.class, int.class).invoke(m, 0.30, color);
                c.getMethod("setLineStyle", int.class, double.class, int.class).invoke(m, 2, 0.9, color);
                now.add(id);
            }
            for (String old : new HashSet<>(drawn)) {
                if (!now.contains(old)) {
                    Object m = find.invoke(set, old);
                    if (m != null) cArea.getMethod("deleteMarker").invoke(m);
                }
            }
            drawn.clear();
            drawn.addAll(now);
        } catch (Throwable t) {
            plugin.getLogger().warning("Error actualizando Dynmap: " + t);
        }
    }

    private static String esc(String s) {
        return s.replace("<", "&lt;").replace(">", "&gt;");
    }
}
