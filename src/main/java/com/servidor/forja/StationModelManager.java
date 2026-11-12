package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Modelos 3D (BetterModel) de las estaciones de trabajo. Cada estación colocada muestra el modelo
 * "forja_estacion_&lt;tipo&gt;" (carpeta bettermodel/Forja/models) con animación de reposo en bucle y una animación
 * "work" que se dispara al usar la estación. Se accede a BetterModel por reflexión: si no está instalado, o el
 * modelo no existe, la estación conserva el modelo antiguo (ItemDisplay con el modelo del resource pack).
 */
public final class StationModelManager {
    private static final Set<String> TYPES = Set.of("alquimia", "armeria", "carpinteria", "cocina", "curtideria",
            "forja", "fundicion_t1", "fundicion_t2", "joyeria", "yunque");

    private static final class Entry {
        final Object tracker;
        final Set<UUID> viewers = new HashSet<>();
        long lastWork;

        Entry(Object tracker) {
            this.tracker = tracker;
        }
    }

    private final ForjaPlugin plugin;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, Float> facing = new ConcurrentHashMap<>();
    private BukkitTask task;
    private boolean broken;
    private int syncCounter;

    // referencias reflexivas
    private Class<?> bmClass, adapterClass, platformLocationClass, platformPlayerClass;
    private Method adaptLocation, adaptPlayer;

    public StationModelManager(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean enabled() {
        return !broken && plugin.getConfig().getBoolean("estaciones_bettermodel.activa", true)
                && Bukkit.getPluginManager().getPlugin("BetterModel") != null;
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private static String modelKey(String type) {
        return "forja_estacion_" + type;
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void start() {
        loadFacing();
        if (!enabled()) {
            if (Bukkit.getPluginManager().getPlugin("BetterModel") == null) {
                plugin.getLogger().info("BetterModel no está instalado: las estaciones usan el modelo del resource pack.");
            }
            return;
        }
        try {
            bmClass = Class.forName("kr.toxicity.model.api.BetterModel");
            adapterClass = Class.forName("kr.toxicity.model.api.bukkit.platform.BukkitAdapter");
            platformLocationClass = Class.forName("kr.toxicity.model.api.platform.PlatformLocation");
            platformPlayerClass = Class.forName("kr.toxicity.model.api.platform.PlatformPlayer");
            adaptLocation = adapterClass.getMethod("adapt", Location.class);
            adaptPlayer = adapterClass.getMethod("adapt", Player.class);
        } catch (Exception e) {
            broken = true;
            plugin.getLogger().log(Level.WARNING, "No se pudo enlazar con BetterModel; las estaciones usan el modelo del resource pack.", e);
            return;
        }
        // El primer intento se hace unos segundos después: BetterModel carga sus modelos al arrancar.
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 100L, 20L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        for (Entry e : entries.values()) closeTracker(e);
        entries.clear();
        saveFacing();
    }

    // ------------------------------------------------------------------ orientación (se guarda aparte)

    private File facingFile() {
        return new File(plugin.getDataFolder(), "station_facing.yml");
    }

    private void loadFacing() {
        facing.clear();
        File f = facingFile();
        if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        for (String k : y.getKeys(false)) facing.put(k.replace('|', ';'), (float) y.getDouble(k));
    }

    private void saveFacing() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<String, Float> e : facing.entrySet()) y.set(e.getKey().replace(';', '|'), e.getValue());
        try {
            y.save(facingFile());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "No se pudo guardar la orientación de las estaciones", ex);
        }
    }

    private boolean facingLoaded;

    /** Giro (grados) con que se colocó la estación; también lo usa el modelo clásico (ItemDisplay). */
    public float yawOf(Block b) {
        if (!facingLoaded) { facingLoaded = true; loadFacing(); }
        return facing.getOrDefault(key(b), 0f) + (float) plugin.getConfig().getDouble("estaciones_modelo.giro", 0);
    }

    /** Orienta el modelo hacia el jugador que coloca la estación (múltiplos de 90 grados). */
    public void setFacing(Block b, float playerYaw) {
        float yaw = Math.round((playerYaw + 180f) / 90f) * 90f;
        facing.put(key(b), yaw);
        saveFacing();
    }

    // ------------------------------------------------------------------ API

    /** ¿Esta estación se dibuja con un modelo BetterModel que ya está disponible? */
    public boolean handles(String type) {
        if (type == null || !TYPES.contains(type) || !enabled() || bmClass == null) return false;
        return renderer(type) != null;
    }

    private Object renderer(String type) {
        try {
            Object opt = bmClass.getMethod("model", String.class).invoke(null, modelKey(type));
            if (opt instanceof Optional<?> o && o.isPresent()) return o.get();
        } catch (Exception e) {
            fail("buscar el modelo " + modelKey(type), e);
        }
        return null;
    }

    private void fail(String what, Exception e) {
        if (broken) return;
        broken = true;
        plugin.getLogger().log(Level.WARNING, "BetterModel falló al " + what + ". Las estaciones vuelven al modelo del resource pack.", e);
        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Entry en : entries.values()) closeTracker(en);
            entries.clear();
        });
    }

    /** Dispara la animación indicada ("work") en el modelo de la estación (con un mínimo de 2 s entre disparos). */
    public void play(Block b, String animation) {
        Entry e = entries.get(key(b));
        if (e == null) return;
        long now = System.currentTimeMillis();
        if (now - e.lastWork < 2000) return;
        e.lastWork = now;
        try {
            e.tracker.getClass().getMethod("animate", String.class).invoke(e.tracker, animation);
        } catch (Exception ex) {
            // animación inexistente en este modelo: no es un error grave
        }
    }

    public boolean hasTracker(Block b) {
        return entries.containsKey(key(b));
    }

    public void remove(Block b) {
        Entry e = entries.remove(key(b));
        if (e != null) closeTracker(e);
        facing.remove(key(b));
    }

    private void closeTracker(Entry e) {
        try {
            e.tracker.getClass().getMethod("close").invoke(e.tracker);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ simulación

    private void tick() {
        if (!enabled()) return;
        boolean doSync = (syncCounter++ % 5) == 0; // cada ~5 s se comprueban estaciones nuevas o cargadas
        double range = Math.max(8, plugin.getConfig().getDouble("estaciones_bettermodel.rango", 48));
        double r2 = range * range;
        try {
            if (doSync) syncStations();
            for (Map.Entry<String, Entry> en : entries.entrySet()) {
                Entry e = en.getValue();
                String[] a = en.getKey().split(";", 4);
                World w = Bukkit.getWorld(a[0]);
                if (w == null) continue;
                int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    closeTracker(e);
                    entries.remove(en.getKey());
                    continue;
                }
                Location center = new Location(w, x + 0.5, y + 0.5, z + 0.5);
                Set<UUID> near = new HashSet<>();
                for (Player p : w.getPlayers()) {
                    if (p.getLocation().distanceSquared(center) > r2) continue;
                    near.add(p.getUniqueId());
                    if (e.viewers.add(p.getUniqueId())) {
                        Object pp = adaptPlayer.invoke(null, p);
                        for (Method m : e.tracker.getClass().getMethods()) {
                            if (m.getName().equals("spawn") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(pp)) {
                                m.invoke(e.tracker, pp);
                                break;
                            }
                        }
                    }
                }
                e.viewers.retainAll(near);
            }
        } catch (Exception ex) {
            fail("actualizar los modelos de estación", ex);
        }
    }

    private void syncStations() throws Exception {
        for (Map.Entry<String, String> st : plugin.stations.snapshot().entrySet()) {
            String type = st.getValue();
            if (!TYPES.contains(type) || entries.containsKey(st.getKey())) continue;
            String[] a = st.getKey().split(";", 4);
            World w = Bukkit.getWorld(a[0]);
            if (w == null) continue;
            int x = Integer.parseInt(a[1]), y = Integer.parseInt(a[2]), z = Integer.parseInt(a[3]);
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Object renderer = renderer(type);
            if (renderer == null) continue; // modelo aún no cargado o inexistente: se mantiene el modelo antiguo
            float yaw = facing.getOrDefault(st.getKey(), 0f) + (float) plugin.getConfig().getDouble("estaciones_bettermodel.giro_extra", 0);
            Object ploc = adaptLocation.invoke(null, new Location(w, x + 0.5, y, z + 0.5, yaw, 0f));
            Object tracker = null;
            for (Method m : renderer.getClass().getMethods()) {
                if (m.getName().equals("create") && m.getParameterCount() == 1 && m.getParameterTypes()[0].isInstance(ploc)) {
                    tracker = m.invoke(renderer, ploc);
                    break;
                }
            }
            if (tracker == null) continue;
            entries.put(st.getKey(), new Entry(tracker));
            try {
                tracker.getClass().getMethod("animate", String.class).invoke(tracker, "idle");
            } catch (Exception ignored) {
            }
            plugin.stations.hideLegacyVisual(w.getBlockAt(x, y, z)); // el modelo antiguo (ItemDisplay) sobra
        }
    }
}
