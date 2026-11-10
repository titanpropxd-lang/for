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
    public AccessoryManager accessories;
    public RpgBridge rpg;
    public GuiStyle guiStyle;
    public BackpackManager backpacks;
    public WorkbenchListener workbenches;
    public final Map<UUID, ForgeMenu> menus = new ConcurrentHashMap<>();
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
        stations = new StationManager(this);
        stations.load();
        accessories = new AccessoryManager(this);
        accessories.startTask();

        Bukkit.getPluginManager().registerEvents(new GuiListener(this), this);
        Bukkit.getPluginManager().registerEvents(new StationListener(this), this);
        workbenches = new WorkbenchListener(this);
        workbenches.load();
        Bukkit.getPluginManager().registerEvents(workbenches, this);
        backpacks = new BackpackManager(this);
        Bukkit.getPluginManager().registerEvents(backpacks, this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new ItemUseListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

        ForjaCommand fc = new ForjaCommand(this);
        PluginCommand c = getCommand("forja");
        if (c != null) { c.setExecutor(fc); c.setTabCompleter(fc); }
        PluginCommand co = getCommand("oficios");
        if (co != null) co.setExecutor((s, cmd, l, a) -> {
            if (s instanceof Player p) OficiosMenu.open(this, p);
            else s.sendMessage("Solo jugadores.");
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
        for (ForgeMenu m : new ArrayList<>(menus.values())) m.cancelSession(null);
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
