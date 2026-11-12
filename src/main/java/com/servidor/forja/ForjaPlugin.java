package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ForjaPlugin extends JavaPlugin {
    private static ForjaPlugin instance;

    public Lang lang;
    public Database db;
    public ItemRegistry items;
    public RecipeRegistry recipes;
    public RaceService races;
    public ProfessionManager professions;
    public ForgeService forge;
    public StationManager stations;
    public FoundryManager foundry;
    private final java.util.Map<java.util.UUID, String> lastStationMsg = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID, Long> lastStationMsgAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Mensajes de las estaciones físicas (fundición, curtiduría). Por defecto van al CHAT y no a la action bar, porque la
     * action bar la ocupa constantemente el HUD de poderes de razas y se pisan ("mensajes.estaciones_canal": chat|actionbar|titulo).
     */
    public void stationMessage(Player p, net.kyori.adventure.text.Component msg) {
        String canal = getConfig().getString("mensajes.estaciones_canal", "chat").toLowerCase(java.util.Locale.ROOT);
        switch (canal) {
            case "actionbar" -> p.sendActionBar(msg);
            case "titulo" -> p.showTitle(net.kyori.adventure.title.Title.title(net.kyori.adventure.text.Component.empty(), msg,
                    net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1800), java.time.Duration.ofMillis(300))));
            default -> {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(msg);
                long now = System.currentTimeMillis();
                String prev = lastStationMsg.get(p.getUniqueId());
                Long at = lastStationMsgAt.get(p.getUniqueId());
                if (plain.equals(prev) && at != null && now - at < 1500) return; // evita repetir el mismo estado en bucle
                lastStationMsg.put(p.getUniqueId(), plain);
                lastStationMsgAt.put(p.getUniqueId(), now);
                p.sendMessage(msg);
            }
        }
    }
    public TanneryManager tannery;
    public StationModelManager stationModels;
    public CurtiduriaManager curtiduria;
    public AccessoryManager accessories;
    public RpgBridge rpg;
    public GuiStyle guiStyle;
    public BackpackManager backpacks;
    public WorkbenchListener workbenches;
    public ForjaVisualEffects visuals;
    public final Map<UUID, ForgeMenu> menus = new ConcurrentHashMap<>();
    public final Set<UUID> adminMode = ConcurrentHashMap.newKeySet();
    public boolean isAdminMode(Player player) { return player != null && player.hasPermission("forja.admin") && adminMode.contains(player.getUniqueId()); }
    private final List<NamespacedKey> vanillaRecipes = new ArrayList<>();

    public static ForjaPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        mergeConfigDefaults();
        ensureResources();
        ensureSecret();
        Keys.init(this);

        lang = new Lang(this);
        lang.load();
        guiStyle = new GuiStyle(this);
        guiStyle.load();
        races = new RaceService(this);
        races.load();
        rpg = new RpgBridge(this);
        items = new ItemRegistry(this);
        items.load();
        recipes = new RecipeRegistry(this);
        recipes.load();

        db = new Database(this);
        try {
            db.open();
        } catch (Exception e) {
            getLogger().severe("No se pudo abrir la base de datos: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        professions = new ProfessionManager(this);
        forge = new ForgeService(this);
        curtiduria = new CurtiduriaManager(this);
        stationModels = new StationModelManager(this);   // antes que las estaciones: su orientación se usa al dibujarlas
        stations = new StationManager(this);
        stations.load();
        stationModels.start();
        foundry = new FoundryManager(this);
        foundry.load();
        foundry.syncStations();
        Bukkit.getPluginManager().registerEvents(foundry, this);
        tannery = new TanneryManager(this);
        tannery.load();
        tannery.syncStations();
        Bukkit.getPluginManager().registerEvents(tannery, this);
        accessories = new AccessoryManager(this);
        accessories.startTask();

        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new StationListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CastingListener(this), this);
        Bukkit.getPluginManager().registerEvents(new HideDropListener(this), this);
        workbenches = new WorkbenchListener(this);
        workbenches.load();
        Bukkit.getPluginManager().registerEvents(workbenches, this);
        backpacks = new BackpackManager(this);
        Bukkit.getPluginManager().registerEvents(backpacks, this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ItemUseListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);
        visuals = new ForjaVisualEffects(this);
        Bukkit.getPluginManager().registerEvents(visuals, this);

        ForjaCommand fc = new ForjaCommand(this);
        PluginCommand c = getCommand("forja");
        if (c != null) { c.setExecutor(fc); c.setTabCompleter(fc); }
        PluginCommand cat = getCommand("armas");
        if (cat != null) { cat.setExecutor(fc); cat.setTabCompleter(fc); }
        Bukkit.getPluginManager().registerEvents(new CatalogMenu.Events(), this);
        PluginCommand co = getCommand("oficios");
        if (co != null) co.setExecutor((s, cmd, l, a) -> {
            if (s instanceof Player p) OficiosMenu.open(this, p);
            else s.sendMessage("Solo jugadores.");
            return true;
        });
        PluginCommand cg = getCommand("grimorio");
        if (cg != null) cg.setExecutor((sender, command, label, args) -> {
            if (sender instanceof Player p) GrimorioMenu.open(this, p);
            else sender.sendMessage("Solo jugadores.");
            return true;
        });
        PluginCommand ca = getCommand("accesorios");
        if (ca != null) ca.setExecutor((s, cmd, l, a) -> {
            if (s instanceof Player p) accessories.open(p);
            else s.sendMessage("Solo jugadores.");
            return true;
        });

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new ForjaExpansion(this).register();
            getLogger().info("PlaceholderAPI detectado: placeholders %forja_*% registrados.");
        }
        registerBootstrapRecipes();

        for (Player p : Bukkit.getOnlinePlayers()) professions.load(p); // /reload
        if (workbenches.count() == 0) getLogger().warning("No hay ningún bloque configurado en mesas.bloques: las mesas no abrirán menú.");
        getLogger().info("Forja activo: " + items.all().size() + " items, " + recipes.all().size() + " recetas, "
                + stations.count() + " estaciones registradas.");
    }

    @Override
    public void onDisable() {
        if (backpacks != null) backpacks.closeAll();
        if (foundry != null) foundry.shutdown();
        if (tannery != null) tannery.shutdown();
        if (stationModels != null) stationModels.shutdown();
        for (ForgeMenu m : new ArrayList<>(menus.values())) {
            if (!m.suspendForDisconnect()) m.cancelSession(null);
        }
        menus.clear();
        if (accessories != null) for (Player p : Bukkit.getOnlinePlayers()) accessories.clear(p);
        if (db != null) db.close();
    }

    public void reloadAll() {
        reloadConfig();
        mergeConfigDefaults();
        ensureSecret();
        lang.load();
        guiStyle.load();
        workbenches.load();
        races.load();
        items.load();
        recipes.load();
        registerBootstrapRecipes();
        for (Player p : Bukkit.getOnlinePlayers()) accessories.apply(p);
    }

    /** Ejecuta la apertura de un menú capturando cualquier error: lo registra completo en consola y avisa al jugador. */
    public void safe(Player p, String what, Runnable r) {
        try {
            r.run();
        } catch (Exception ex) {
            getLogger().log(java.util.logging.Level.SEVERE, "Error abriendo " + what, ex);
            lang.send(p, "mesa.error", "e", Lang.esc(ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage())));
        }
    }

    /** Añade a config.yml las claves nuevas de esta versión sin tocar tus valores. */
    private void mergeConfigDefaults() {
        org.bukkit.configuration.Configuration def = getConfig().getDefaults();
        if (def == null) return;
        int missing = 0;
        for (String path : def.getKeys(true)) if (!getConfig().contains(path, true)) missing++;
        if (missing > 0) {
            getConfig().options().copyDefaults(true);
            saveConfig();
            getLogger().info("config.yml actualizado: se añadieron " + missing + " claves nuevas (tus valores no se tocan).");
        }
    }

    /** Copia los datos que falten y migra los antiguos (con copia de seguridad) cuando el plugin trae datos nuevos. Ver DataMigrator. */
    private void ensureResources() {
        new DataMigrator(getDataFolder(), getLogger(), path -> {
            try (java.io.InputStream in = getResource(path)) {
                return in == null ? null : in.readAllBytes();
            }
        }).run(getConfig().getBoolean("actualizacion.sobrescribir_datos", true));
    }

    private void ensureSecret() {
        if (getConfig().getString("seguridad.secreto", "").isBlank()) {
            getConfig().set("seguridad.secreto", UUID.randomUUID() + "-" + UUID.randomUUID());
            saveConfig();
        }
    }

    /** Recetas de crafteo vanilla SOLO para obtener las estaciones iniciales (ingredientes vanilla). */
    private void registerBootstrapRecipes() {
        for (NamespacedKey k : vanillaRecipes) Bukkit.removeRecipe(k);
        vanillaRecipes.clear();
        ConfigurationSection s = getConfig().getConfigurationSection("recetas_vanilla");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            try {
                CustomItem ci = items.get(id);
                if (ci == null) { getLogger().warning("recetas_vanilla: item desconocido " + id); continue; }
                List<String> shape = s.getStringList(id + ".forma");
                NamespacedKey key = new NamespacedKey(this, "craft_" + id);
                ShapedRecipe sr = new ShapedRecipe(key, items.basic(id, 1));
                sr.shape(shape.toArray(new String[0]));
                ConfigurationSection ing = s.getConfigurationSection(id + ".ingredientes");
                if (ing == null) continue;
                for (String ch : ing.getKeys(false)) {
                    Material m = Material.matchMaterial(ing.getString(ch, ""));
                    if (m == null) throw new IllegalArgumentException("material inválido " + ing.getString(ch));
                    sr.setIngredient(ch.charAt(0), m);
                }
                Bukkit.addRecipe(sr);
                vanillaRecipes.add(key);
            } catch (Exception e) {
                getLogger().warning("recetas_vanilla." + id + ": " + e.getMessage());
            }
        }
    }
}
