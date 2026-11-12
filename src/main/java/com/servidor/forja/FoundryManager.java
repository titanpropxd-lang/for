package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Metalurgia física. El horno de fundición (estación fundicion_t1 / fundicion_t2) deja de ser un menú:
 *
 *  1. Clic derecho con un lingote  -> el lingote entra en el crisol (se ve sobre el horno).
 *  2. Clic derecho con carbón      -> el horno se calienta; el crisol se pone al rojo y el metal se funde.
 *  3. Clic derecho con un molde    -> el molde se coloca físicamente junto al crisol.
 *  4. Clic derecho con la mano vacía -> se vierte el metal líquido en el molde.
 *  5. El metal se enfría (cambia de color) y aparece la pieza terminada.
 *  6. Clic derecho con la mano vacía -> se recoge la pieza con la mano.
 *
 * Todos los elementos visuales son Display entities NO persistentes (no se guardan en el chunk): se recrean desde el
 * estado guardado en la base de datos, de modo que nunca quedan entidades huérfanas ni duplicadas.
 */
public final class FoundryManager implements Listener {
    static final Map<String, String> MOLDS = Map.of(
            "molde_espada", "hoja_espada", "molde_hacha", "cabeza_hacha",
            "molde_lanza", "punta_lanza", "molde_maza", "cabeza_maza",
            "molde_daga", "hoja_daga", "molde_pico", "cabeza_pico", "molde_placa", "placa");
    private static final List<String> TIERS = List.of("cobre", "bronce", "hierro_forjado", "acero", "plata", "acero_negro", "mithril", "adamantita");
    private static final int FUEL_REF = 1600; // un carbón; sirve de referencia para el porcentaje que se muestra
    private static final int TICK = 5;

    private static final int IDLE = 0, POURING = 1, COOLING = 2, READY = 3;

    /** Estado de un horno físico. */
    private static final class Foundry {
        final String world;
        final int x, y, z;
        String station = "fundicion_t1";
        String tier;          // metal que contiene el crisol (null = vacío)
        int portions;         // lingotes/porciones en el crisol
        int fuel;             // ticks de combustible restantes
        int heat;             // ticks calentando
        boolean molten;
        String mold;          // id del molde colocado (null = ninguno)
        int phase = IDLE;
        int phaseTicks;       // ticks transcurridos en la fase actual (vertido/enfriamiento)
        String piece;         // id de la pieza que se está colando / lista
        int lastStage = -1;   // etapa visual de enfriamiento ya dibujada
        final List<Entity> visuals = new ArrayList<>();

        Foundry(String world, int x, int y, int z) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        boolean isEmpty() {
            return tier == null && portions == 0 && fuel == 0 && mold == null && phase == IDLE;
        }

        Location worldLoc() {
            return new Location(Bukkit.getWorld(world), x + 0.5, y + 1.0, z + 0.5);
        }
    }

    private final ForjaPlugin plugin;
    private final Map<String, Foundry> map = new ConcurrentHashMap<>();
    private BukkitTask task;

    public FoundryManager(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ utilidades

    private static String key(String world, int x, int y, int z) {
        return world + ";" + x + ";" + y + ";" + z;
    }

    private static String key(Block b) {
        return key(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private ConfigurationSection cfg() {
        return plugin.getConfig().getConfigurationSection("fundicion");
    }

    private int cfgInt(String path, int def) {
        return plugin.getConfig().getInt("fundicion." + path, def);
    }

    private int capacity() {
        return Math.max(1, cfgInt("capacidad", 4));
    }

    private boolean tierAllowed(String station, String tier) {
        List<String> allowed = "fundicion_t2".equals(station)
                ? plugin.getConfig().getStringList("fundicion.tiers_t2")
                : plugin.getConfig().getStringList("fundicion.tiers_t1");
        if (allowed.isEmpty()) allowed = "fundicion_t2".equals(station) ? TIERS : TIERS.subList(0, 4);
        return allowed.contains(tier);
    }

    /** Ticks de calentamiento necesarios para fundir el contenido actual del crisol. */
    private int heatNeeded(Foundry f) {
        int seconds = cfgInt("calentamiento_seg." + f.tier, 12);
        double speed = "fundicion_t2".equals(f.station) ? plugin.getConfig().getDouble("fundicion.velocidad_t2", 1.5) : 1.0;
        double load = 0.6 + 0.1 * f.portions;
        return Math.max(20, (int) (seconds * 20 * load / Math.max(0.1, speed)));
    }

    private int coolTicks(String tier) {
        return Math.max(20, cfgInt("enfriamiento_seg." + tier, 12) * 20);
    }

    private int pourTicks() {
        return Math.max(20, cfgInt("vertido_seg", 4) * 20);
    }

    private int fuelValue(ItemStack hand) {
        if (hand == null || hand.getType().isAir() || plugin.items.idOf(hand) != null) return 0;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("fundicion.combustible_ticks");
        if (s == null) return switch (hand.getType()) {
            case COAL, CHARCOAL -> FUEL_REF;
            case COAL_BLOCK -> FUEL_REF * 10;
            default -> 0;
        };
        return s.getInt(hand.getType().name(), 0);
    }

    private String metalName(String tier) {
        CustomItem ci = plugin.items.get("lingote_" + tier);
        return ci != null ? ci.nombre : tier;
    }

    private void bar(Player p, String key, Object... kv) {
        plugin.stationMessage(p, plugin.lang.get(key, kv));
    }

    // ------------------------------------------------------------------ ciclo de vida

    public void load() {
        map.clear();
        try {
            Map<String, Foundry> loaded = plugin.db.query(c -> {
                Map<String, Foundry> out = new LinkedHashMap<>();
                try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT world,x,y,z,data FROM foundry")) {
                    while (rs.next()) {
                        Foundry f = new Foundry(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                        decode(f, rs.getString(5));
                        out.put(key(f.world, f.x, f.y, f.z), f);
                    }
                }
                return out;
            }).join();
            map.putAll(loaded);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "No se pudo cargar el estado de las fundiciones", e);
        }
        if (task != null) task.cancel();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK, TICK);
        for (Foundry f : map.values()) {
            World w = Bukkit.getWorld(f.world);
            if (w != null && w.isChunkLoaded(f.x >> 4, f.z >> 4)) refresh(f);
        }
    }

    /** Se llama al desactivar el plugin: guarda todo y retira los visuales. */
    public void shutdown() {
        if (task != null) task.cancel();
        for (Foundry f : map.values()) {
            save(f);
            clearVisuals(f);
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        final String w = e.getWorld().getName();
        final int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        boolean any = false;
        for (Foundry f : map.values()) if (f.world.equals(w) && (f.x >> 4) == cx && (f.z >> 4) == cz) { any = true; break; }
        if (!any) return;
        // No se generan entidades dentro del propio evento de carga del chunk.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (Foundry f : map.values()) if (f.world.equals(w) && (f.x >> 4) == cx && (f.z >> 4) == cz) refresh(f);
        }, 5L);
    }

    // ------------------------------------------------------------------ persistencia

    private static String n(String s) {
        return s == null ? "-" : s;
    }

    private static String un(String s) {
        return s == null || s.equals("-") ? null : s;
    }

    private String encode(Foundry f) {
        return String.join("|", n(f.tier), String.valueOf(f.portions), String.valueOf(f.fuel), String.valueOf(f.heat),
                String.valueOf(f.molten), n(f.mold), String.valueOf(f.phase), String.valueOf(f.phaseTicks), n(f.piece), n(f.station));
    }

    private void decode(Foundry f, String raw) {
        try {
            String[] a = raw.split("\\|", -1);
            f.tier = un(a[0]);
            f.portions = Integer.parseInt(a[1]);
            f.fuel = Integer.parseInt(a[2]);
            f.heat = Integer.parseInt(a[3]);
            f.molten = Boolean.parseBoolean(a[4]);
            f.mold = un(a[5]);
            f.phase = Integer.parseInt(a[6]);
            f.phaseTicks = Integer.parseInt(a[7]);
            f.piece = un(a[8]);
            String st = un(a[9]);
            if (st != null) f.station = st;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Estado de fundición ilegible en " + f.world + " " + f.x + "," + f.y + "," + f.z + ": se reinicia", ex);
        }
    }

    private void save(Foundry f) {
        final String w = f.world;
        final int x = f.x, y = f.y, z = f.z;
        if (f.isEmpty()) {
            plugin.db.run(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM foundry WHERE world=? AND x=? AND y=? AND z=?")) {
                    ps.setString(1, w);
                    ps.setInt(2, x);
                    ps.setInt(3, y);
                    ps.setInt(4, z);
                    ps.executeUpdate();
                }
            });
            return;
        }
        final String data = encode(f);
        plugin.db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO foundry(world,x,y,z,data) VALUES(?,?,?,?,?) ON CONFLICT(world,x,y,z) DO UPDATE SET data=excluded.data")) {
                ps.setString(1, w);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.setString(5, data);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------------ hooks desde las estaciones

    /** Registra el horno físico de una estación recién colocada (o ya existente) para que se vea el crisol. */
    public void ensure(Block b, String station) {
        Foundry f = map.computeIfAbsent(key(b), k -> new Foundry(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        f.station = station;
        refresh(f);
    }

    /** Tras cargar estaciones y estados: crea el horno físico de cada estación fundicion_* ya registrada. */
    public void syncStations() {
        for (Map.Entry<String, String> en : plugin.stations.snapshot().entrySet()) {
            if (!en.getValue().startsWith("fundicion")) continue;
            String[] a = en.getKey().split(";", 4);
            if (a.length != 4) continue;
            Foundry f = map.computeIfAbsent(en.getKey(), k -> {
                Foundry nf = new Foundry(a[0], Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3]));
                return nf;
            });
            f.station = en.getValue();
            World w = Bukkit.getWorld(f.world);
            if (w != null && w.isChunkLoaded(f.x >> 4, f.z >> 4) && f.visuals.isEmpty()) refresh(f);
        }
    }

    /** La estación se retira: devuelve lingotes, molde y pieza al suelo. Llamar antes de stations.remove. */
    public void dropContents(Block b) {
        Foundry f = map.get(key(b));
        if (f == null) return;
        Location at = b.getLocation().add(0.5, 0.8, 0.5);
        World w = b.getWorld();
        if (f.tier != null && f.portions > 0 && f.phase != POURING) {
            ItemStack ingot = plugin.items.basic("lingote_" + f.tier, f.portions);
            if (ingot != null) w.dropItemNaturally(at, ingot);
        }
        if (f.mold != null) {
            ItemStack mold = plugin.items.basic(f.mold, 1);
            if (mold != null) w.dropItemNaturally(at, mold);
        }
        if (f.phase == READY && f.piece != null) {
            ItemStack piece = plugin.items.basic(f.piece, 1);
            if (piece != null) w.dropItemNaturally(at, piece);
        }
    }

    /** Borra el estado (visuales + base de datos) de la estación en ese bloque. */
    public void clear(Block b) {
        Foundry f = map.remove(key(b));
        if (f == null) return;
        clearVisuals(f);
        f.tier = null;
        f.portions = 0;
        f.fuel = 0;
        f.mold = null;
        f.phase = IDLE;
        save(f); // vacío -> DELETE
    }

    // ------------------------------------------------------------------ interacción

    public void interact(Player p, Block b, String station) {
        Foundry f = map.computeIfAbsent(key(b), k -> new Foundry(b.getWorld().getName(), b.getX(), b.getY(), b.getZ()));
        f.station = station;
        ItemStack hand = p.getInventory().getItemInMainHand();
        boolean empty = hand == null || hand.getType().isAir();
        String id = empty ? null : plugin.items.idOf(hand);

        // --- lingote
        if (id != null && id.startsWith("lingote_")) {
            String tier = id.substring("lingote_".length());
            if (!TIERS.contains(tier)) { status(p, f); return; }
            addIngot(p, f, hand, tier);
            return;
        }
        // --- molde
        if (id != null && MOLDS.containsKey(id)) {
            placeMold(p, f, hand, id);
            return;
        }
        // --- combustible
        int fuelTicks = empty ? 0 : fuelValue(hand);
        if (fuelTicks > 0) {
            addFuel(p, f, hand, fuelTicks);
            return;
        }
        if (!empty) { status(p, f); return; }

        // --- mano vacía
        if (f.phase == READY) {
            pickUp(p, f);
            return;
        }
        if (p.isSneaking()) {
            if (f.mold != null && f.phase == IDLE) {
                removeMold(p, f);
            } else if (f.phase == IDLE) {
                // Aleaciones y refinado de lingotes siguen en el menú de la estación.
                final Location center = b.getLocation().add(0.5, 0.5, 0.5);
                final String st = station;
                plugin.safe(p, "menú de fundición", () -> ForgeMenu.open(plugin, p, st, center, null, "materiales"));
            } else {
                bar(p, "fundicion.fisica.molde_ocupado");
            }
            return;
        }
        if (f.phase == POURING || f.phase == COOLING) {
            bar(p, "fundicion.fisica.molde_ocupado");
            return;
        }
        tryPour(p, f);
    }

    private void addIngot(Player p, Foundry f, ItemStack hand, String tier) {
        if (!tierAllowed(f.station, tier)) {
            plugin.lang.send(p, "fundicion.fisica.metal_no_soportado", "metal", metalName(tier));
            return;
        }
        if (f.phase == POURING) { bar(p, "fundicion.fisica.molde_ocupado"); return; }
        if (f.tier != null && !f.tier.equals(tier) && f.portions > 0) {
            plugin.lang.send(p, "fundicion.fisica.metal_distinto");
            return;
        }
        if (f.molten) {
            plugin.lang.send(p, "fundicion.fisica.ya_liquido");
            return;
        }
        if (f.portions >= capacity()) {
            plugin.lang.send(p, "fundicion.fisica.crisol_lleno");
            return;
        }
        consumeOne(p, hand);
        f.tier = tier;
        f.portions++;
        f.heat = 0;
        f.lastStage = -1;
        p.playSound(f.worldLoc(), Sound.BLOCK_CHAIN_PLACE, 0.8f, 0.9f);
        bar(p, "fundicion.fisica.lingote_anadido", "n", f.portions, "max", capacity(), "metal", metalName(tier));
        refresh(f);
        save(f);
    }

    private void addFuel(Player p, Foundry f, ItemStack hand, int ticks) {
        if (f.fuel + ticks > FUEL_REF * 12) {
            bar(p, "fundicion.fisica.combustible_lleno");
            return;
        }
        consumeOne(p, hand);
        f.fuel += ticks;
        p.playSound(f.worldLoc(), Sound.BLOCK_FURNACE_FIRE_CRACKLE, 1.0f, 1.0f);
        bar(p, "fundicion.fisica.combustible_anadido");
        save(f);
    }

    private void placeMold(Player p, Foundry f, ItemStack hand, String moldId) {
        if (f.mold != null) {
            plugin.lang.send(p, "fundicion.fisica.molde_ya");
            return;
        }
        consumeOne(p, hand);
        f.mold = moldId;
        f.phase = IDLE;
        p.playSound(f.worldLoc(), Sound.BLOCK_ANVIL_PLACE, 0.5f, 1.6f);
        CustomItem ci = plugin.items.get(moldId);
        bar(p, "fundicion.fisica.molde_puesto", "molde", ci == null ? moldId : ci.nombre);
        refresh(f);
        save(f);
    }

    private void removeMold(Player p, Foundry f) {
        ItemStack mold = plugin.items.basic(f.mold, 1);
        f.mold = null;
        if (mold != null) plugin.forge.give(p, mold);
        p.playSound(f.worldLoc(), Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.2f);
        bar(p, "fundicion.fisica.molde_quitado");
        refresh(f);
        save(f);
    }

    private void tryPour(Player p, Foundry f) {
        if (f.tier == null || f.portions <= 0) { bar(p, "fundicion.fisica.sin_metal"); return; }
        if (!f.molten) { status(p, f); return; }
        if (f.mold == null) { bar(p, "fundicion.fisica.sin_molde"); return; }
        String output = "pieza_" + MOLDS.get(f.mold) + "_" + f.tier;
        CustomItem outItem = plugin.items.get(output);
        if (outItem == null) {
            plugin.lang.send(p, "fundicion.fisica.pieza_inexistente", "pieza", output);
            return;
        }
        f.portions--;
        f.piece = output;
        f.phase = POURING;
        f.phaseTicks = 0;
        f.lastStage = -1;
        if (f.portions <= 0) {   // se vació el crisol: queda frío y listo para otra carga
            f.portions = 0;
            f.molten = false;
            f.heat = 0;
        }
        p.playSound(f.worldLoc(), Sound.ITEM_BUCKET_EMPTY_LAVA, 0.9f, 0.8f);
        bar(p, "fundicion.fisica.vertiendo");
        refresh(f);
        save(f);
    }

    private void pickUp(Player p, Foundry f) {
        ItemStack piece = plugin.items.basic(f.piece, 1);
        CustomItem ci = plugin.items.get(f.piece);
        if (f.piece != null) plugin.professions.incStat(p.getUniqueId(), f.piece, 1); // %forja_forjado_<id>% para misiones
        f.phase = IDLE;
        f.phaseTicks = 0;
        String shownName = ci == null ? String.valueOf(f.piece) : ci.nombre;
        f.piece = null;
        if (f.portions <= 0) { f.tier = null; f.molten = false; f.heat = 0; }
        if (piece != null) plugin.forge.give(p, piece);
        p.playSound(f.worldLoc(), Sound.ENTITY_ITEM_PICKUP, 0.8f, 1.1f);
        bar(p, "fundicion.fisica.recogida", "pieza", shownName);
        refresh(f);
        save(f);
    }

    private void status(Player p, Foundry f) {
        if (f.tier == null || f.portions <= 0) {
            bar(p, f.fuel > 0 ? "fundicion.fisica.sin_metal" : "fundicion.fisica.vacio");
            return;
        }
        int need = Math.max(1, heatNeeded(f));
        int heatPct = f.molten ? 100 : Math.min(100, f.heat * 100 / need);
        int fuelPct = Math.min(100, f.fuel * 100 / FUEL_REF);
        bar(p, f.molten ? "fundicion.fisica.estado_liquido" : "fundicion.fisica.estado",
                "n", f.portions, "metal", metalName(f.tier), "calor", heatPct, "comb", fuelPct);
        if (!f.molten && f.fuel <= 0) bar(p, "fundicion.fisica.sin_combustible");
    }

    private static void consumeOne(Player p, ItemStack hand) {
        if (p.getGameMode() == org.bukkit.GameMode.CREATIVE) return;
        if (hand.getAmount() <= 1) p.getInventory().setItemInMainHand(null);
        else hand.setAmount(hand.getAmount() - 1);
    }

    // ------------------------------------------------------------------ simulación

    private void tick() {
        for (Map.Entry<String, Foundry> en : map.entrySet()) {
            Foundry f = en.getValue();
            World w = Bukkit.getWorld(f.world);
            if (w == null || !w.isChunkLoaded(f.x >> 4, f.z >> 4)) continue;
            Block b = w.getBlockAt(f.x, f.y, f.z);
            String type = plugin.stations.typeAt(b);
            if (type == null || !type.startsWith("fundicion")) {   // la estación ya no existe
                clearVisuals(f);
                map.remove(en.getKey());
                f.tier = null; f.portions = 0; f.fuel = 0; f.mold = null; f.phase = IDLE;
                save(f);
                continue;
            }
            try {
                step(f, w);
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Error simulando fundición en " + f.x + "," + f.y + "," + f.z, ex);
            }
        }
    }

    private void step(Foundry f, World w) {
        // Visuales perdidos (chunk recargado, entidad eliminada): se reconstruyen.
        boolean broken = false;
        for (Entity e : f.visuals) if (!e.isValid()) { broken = true; break; }
        if (broken || f.visuals.isEmpty()) refresh(f);

        Location top = new Location(w, f.x + 0.27, f.y + 1.5, f.z + 0.47);
        boolean changed = false;

        // calentamiento / mantenimiento del metal líquido
        if (f.portions > 0 && f.tier != null && f.fuel > 0 && f.phase != POURING) {
            f.fuel = Math.max(0, f.fuel - TICK);
            if (!f.molten) {
                f.heat += TICK;
                if (f.heat >= heatNeeded(f)) {
                    f.molten = true;
                    f.lastStage = -1;
                    w.playSound(top, Sound.BLOCK_LAVA_POP, 1.0f, 0.7f);
                    notifyNear(w, f, "fundicion.fisica.fundido");
                    refresh(f);
                    changed = true;
                }
            }
            if (f.fuel <= 0) {
                if (f.molten && f.portions > 0) {
                    f.molten = false;
                    f.heat = 0;
                    notifyNear(w, f, "fundicion.fisica.solidificado");
                    refresh(f);
                }
                changed = true;
            }
            heatEffects(w, f, top);
        } else if (f.fuel > 0 && f.phase != POURING) {
            // horno encendido con el crisol vacío: el combustible se gasta lentamente
            f.fuel = Math.max(0, f.fuel - TICK / 2);
            if (f.fuel % 40 == 0) w.spawnParticle(Particle.SMOKE, top, 2, .1, .05, .1, .005);
        }

        // vertido y enfriamiento
        if (f.phase == POURING) {
            f.phaseTicks += TICK;
            pourEffects(w, f);
            if (f.phaseTicks >= pourTicks()) {
                f.phase = COOLING;
                f.phaseTicks = 0;
                f.lastStage = -1;
                refresh(f);
                changed = true;
            }
        } else if (f.phase == COOLING) {
            f.phaseTicks += TICK;
            int total = coolTicks(tierOfPiece(f));
            int stage = Math.min(2, f.phaseTicks * 3 / Math.max(1, total));
            Location moldLoc = new Location(w, f.x + 0.75, f.y + 1.2, f.z + 0.5);
            if (f.phaseTicks % 20 < TICK) {
                w.spawnParticle(Particle.CLOUD, moldLoc, 2, .1, .05, .1, .005);
                w.playSound(moldLoc, Sound.BLOCK_FIRE_EXTINGUISH, 0.25f, 1.6f);
            }
            if (stage != f.lastStage) {
                f.lastStage = stage;
                refresh(f);
            }
            if (f.phaseTicks >= total) {
                f.phase = READY;
                f.phaseTicks = 0;
                w.playSound(moldLoc, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 1.4f);
                w.spawnParticle(Particle.END_ROD, moldLoc, 6, .15, .1, .15, .01);
                CustomItem ci = f.piece == null ? null : plugin.items.get(f.piece);
                notifyNear(w, f, "fundicion.fisica.listo", "pieza", ci == null ? String.valueOf(f.piece) : ci.nombre);
                refresh(f);
                changed = true;
            }
        } else if (f.phase == READY && f.phaseTicks++ % 40 == 0) {
            w.spawnParticle(Particle.END_ROD, new Location(w, f.x + 0.75, f.y + 1.3, f.z + 0.5), 1, .1, .05, .1, 0);
        }

        if (changed) save(f);
    }

    private String tierOfPiece(Foundry f) {
        if (f.piece == null) return f.tier == null ? "cobre" : f.tier;
        for (String t : TIERS) if (f.piece.endsWith("_" + t)) return t;
        return "cobre";
    }

    private void heatEffects(World w, Foundry f, Location top) {
        w.spawnParticle(Particle.FLAME, new Location(w, f.x + 0.5, f.y + 1.02, f.z + 0.5), 1, .35, .0, .35, .002);
        w.spawnParticle(Particle.SMOKE, top, 1, .1, .05, .1, .004);
        if (f.molten) {
            w.spawnParticle(Particle.LAVA, new Location(w, f.x + 0.27, f.y + 1.35, f.z + 0.47), 1, .08, .02, .08, 0);
        }
        if (f.heat % 60 < TICK) w.playSound(top, Sound.BLOCK_FURNACE_FIRE_CRACKLE, 0.5f, 1.0f);
    }

    private void pourEffects(World w, Foundry f) {
        // Chorro de metal desde el crisol hasta el molde
        double steps = 8;
        double x0 = f.x + 0.40, y0 = f.y + 1.45, z0 = f.z + 0.47;
        double x1 = f.x + 0.75, y1 = f.y + 1.10, z1 = f.z + 0.50;
        for (int i = 0; i <= steps; i++) {
            double t = i / steps;
            double arc = Math.sin(t * Math.PI) * 0.12;
            w.spawnParticle(Particle.FALLING_LAVA, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t + arc, z0 + (z1 - z0) * t, 1, 0, 0, 0, 0);
        }
        w.spawnParticle(Particle.LAVA, x1, y1 + 0.05, z1, 1, .08, .02, .08, 0);
        if (f.phaseTicks % 20 < TICK) w.playSound(new Location(w, x1, y1, z1), Sound.BLOCK_LAVA_AMBIENT, 0.6f, 1.2f);
    }

    private void notifyNear(World w, Foundry f, String key, Object... kv) {
        Location l = new Location(w, f.x + 0.5, f.y + 0.5, f.z + 0.5);
        for (Player p : w.getPlayers()) {
            if (p.getLocation().distanceSquared(l) <= 12 * 12) bar(p, key, kv);
        }
    }

    // ------------------------------------------------------------------ visuales

    private void clearVisuals(Foundry f) {
        for (Entity e : f.visuals) if (e.isValid()) e.remove();
        f.visuals.clear();
    }

    private static final Quaternionf FLAT = new Quaternionf().rotateX((float) -Math.PI / 2);

    private BlockDisplay blockDisplay(World w, double cx, double cy, double cz, float sx, float sy, float sz, BlockData data, boolean glow) {
        return w.spawn(new Location(w, cx, cy, cz), BlockDisplay.class, d -> {
            d.setBlock(data);
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setTransformation(new Transformation(new Vector3f(-sx / 2f, 0, -sz / 2f), new Quaternionf(), new Vector3f(sx, sy, sz), new Quaternionf()));
            if (glow) d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(0.6f);
        });
    }

    private ItemDisplay itemDisplay(World w, double cx, double cy, double cz, ItemStack item, float scale, float yawRad, boolean glow) {
        return w.spawn(new Location(w, cx, cy, cz), ItemDisplay.class, d -> {
            d.setItemStack(item);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            d.setPersistent(false);
            d.setInvulnerable(true);
            Quaternionf rot = new Quaternionf().rotateY(yawRad).mul(FLAT);
            d.setTransformation(new Transformation(new Vector3f(), rot, new Vector3f(scale, scale, scale), new Quaternionf()));
            if (glow) d.setBrightness(new Display.Brightness(15, 15));
            d.setViewRange(0.6f);
        });
    }

    /** Reconstruye todos los elementos visibles del horno a partir de su estado. */
    private void refresh(Foundry f) {
        clearVisuals(f);
        World w = Bukkit.getWorld(f.world);
        if (w == null || !w.isChunkLoaded(f.x >> 4, f.z >> 4)) return;
        double x = f.x, y = f.y + 1.0, z = f.z;

        // El crisol está siempre presente: es parte del horno físico.
        f.visuals.add(blockDisplay(w, x + 0.27, y, z + 0.47, 0.42f, 0.42f, 0.42f, Material.CAULDRON.createBlockData(), false));

        // Contenido del crisol
        if (f.portions > 0 && f.tier != null) {
            if (f.molten) {
                float h = 0.03f + 0.15f * f.portions / (float) capacity();
                f.visuals.add(blockDisplay(w, x + 0.27, y + 0.125, z + 0.47, 0.315f, h, 0.315f, Material.ORANGE_CONCRETE.createBlockData(), true));
            } else {
                ItemStack ingot = plugin.items.basic("lingote_" + f.tier, 1);
                if (ingot != null) {
                    for (int i = 0; i < f.portions; i++) {
                        f.visuals.add(itemDisplay(w, x + 0.27, y + 0.14 + 0.05 * i, z + 0.47, ingot, 0.3f, (float) (i * 0.6), false));
                    }
                }
            }
        }

        // Molde y metal colado
        if (f.mold != null) {
            ItemStack moldItem = plugin.items.basic(f.mold, 1);
            if (moldItem != null) f.visuals.add(itemDisplay(w, x + 0.75, y + 0.04, z + 0.5, moldItem, 0.55f, 0f, false));
            if (f.phase == POURING) {
                f.visuals.add(blockDisplay(w, x + 0.75, y + 0.07, z + 0.5, 0.30f, 0.04f, 0.30f, Material.ORANGE_CONCRETE.createBlockData(), true));
            } else if (f.phase == COOLING) {
                int stage = Math.max(0, f.lastStage);
                Material m = switch (stage) {
                    case 0 -> Material.ORANGE_CONCRETE;
                    case 1 -> Material.RED_TERRACOTTA;
                    default -> Material.GRAY_CONCRETE;
                };
                f.visuals.add(blockDisplay(w, x + 0.75, y + 0.07, z + 0.5, 0.30f, 0.04f, 0.30f, m.createBlockData(), stage == 0));
            } else if (f.phase == READY && f.piece != null) {
                ItemStack piece = plugin.items.basic(f.piece, 1);
                if (piece != null) f.visuals.add(itemDisplay(w, x + 0.75, y + 0.12, z + 0.5, piece, 0.6f, (float) Math.toRadians(35), false));
            }
        }
    }

    // ------------------------------------------------------------------ varios

    public int count() {
        return map.size();
    }
}
