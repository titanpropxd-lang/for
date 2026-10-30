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
    private static final String[] ITEM_FILES = {"materiales", "armas", "armaduras", "joyas", "consumibles", "herramientas", "estaciones"};
    private static final String[] RECIPE_FILES = {"forja", "yunque", "armeria", "carpinteria", "joyeria", "alquimia", "cocina"};

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
    public final Map<UUID, ForgeMenu> menus = new ConcurrentHashMap<>();
    private final List<NamespacedKey> vanillaRecipes = new ArrayList<>();

    public static ForjaPlugin get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        ensureResources();
        ensureSecret();
        Keys.init(this);

        lang = new Lang(this);
        lang.load();
        races = new RaceService(this);
        races.load();
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
        getLogger().info("Forja activo: " + items.all().size() + " items, " + recipes.all().size() + " recetas, "
                + stations.count() + " estaciones registradas.");
    }

    @Override
    public void onDisable() {
        for (ForgeMenu m : new ArrayList<>(menus.values())) m.cancelSession(null);
        menus.clear();
        if (accessories != null) for (Player p : Bukkit.getOnlinePlayers()) accessories.clear(p);
        if (db != null) db.close();
    }

    public void reloadAll() {
        reloadConfig();
        ensureSecret();
        lang.load();
        races.load();
        items.load();
        recipes.load();
        registerBootstrapRecipes();
        for (Player p : Bukkit.getOnlinePlayers()) accessories.apply(p);
    }

    private void ensureResources() {
        getDataFolder().mkdirs();
        List<String> files = new ArrayList<>(List.of("lang_es.yml", "razas.yml"));
        for (String n : ITEM_FILES) files.add("items/" + n + ".yml");
        for (String n : RECIPE_FILES) files.add("recipes/" + n + ".yml");
        for (String f : files) if (!new File(getDataFolder(), f).exists()) saveResource(f, false);
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
