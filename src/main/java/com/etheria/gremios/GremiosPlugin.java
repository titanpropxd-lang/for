package com.etheria.gremios;

import com.etheria.gremios.command.GremioAdminCommand;
import com.etheria.gremios.command.GremioCommand;
import com.etheria.gremios.command.GremioXpCommand;
import com.etheria.gremios.command.TerritorioCommand;
import com.etheria.gremios.gui.ExtraGuis;
import com.etheria.gremios.gui.Guis;
import com.etheria.gremios.gui.Layouts;
import com.etheria.gremios.gui.MenuManager;
import com.etheria.gremios.gui.PromptManager;
import com.etheria.gremios.listener.CombatListener;
import com.etheria.gremios.listener.PlayerListener;
import com.etheria.gremios.listener.ProtectionListener;
import com.etheria.gremios.manager.CurrencyManager;
import com.etheria.gremios.manager.BannerManager;
import com.etheria.gremios.manager.CinematicManager;
import com.etheria.gremios.manager.BoardManager;
import com.etheria.gremios.manager.DynmapHook;
import com.etheria.gremios.manager.ModelManager;
import com.etheria.gremios.manager.EventManager;
import com.etheria.gremios.manager.GateManager;
import com.etheria.gremios.manager.MissionManager;
import com.etheria.gremios.manager.TurretManager;
import com.etheria.gremios.manager.ZoneManager;
import com.etheria.gremios.listener.ZoneListener;
import com.etheria.gremios.manager.GuildManager;
import com.etheria.gremios.manager.HistoryManager;
import com.etheria.gremios.manager.NpcManager;
import com.etheria.gremios.manager.RaidManager;
import com.etheria.gremios.manager.RepairManager;
import com.etheria.gremios.manager.SiegeManager;
import com.etheria.gremios.manager.SnapshotManager;
import com.etheria.gremios.manager.TerritoryManager;
import com.etheria.gremios.placeholder.GremioExpansion;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** GremiosGUI: gremios, territorios, asedios, banco, impuestos y menús con resourcepack. */
public class GremiosPlugin extends JavaPlugin {
    public CurrencyManager currency;
    public SnapshotManager snapshots;
    public NpcManager npcs;
    public SiegeManager siege;
    public TerritoryManager territories;
    public GuildManager guilds;
    public RaidManager raids;
    public HistoryManager history;
    public GateManager gates;
    public TurretManager turrets;
    public BannerManager banners;
    public EventManager events;
    public ZoneManager zones;
    public CinematicManager cinematic;
    public MissionManager missions;
    public BoardManager board;
    public ModelManager models;
    public DynmapHook dynmap;
    public RepairManager repair;
    public Layouts layouts;
    public MenuManager menus;
    public PromptManager prompts;
    public Guis guis;
    public ExtraGuis extraGuis;

    /** Jugadores admin que ignoran la protección de territorios. */
    public final Set<UUID> bypass = new HashSet<>();

    public NamespacedKey key(String k) {
        return new NamespacedKey(this, k);
    }

    public boolean usePack() {
        return getConfig().getBoolean("gui.usar-resourcepack", true);
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();

        currency = new CurrencyManager(this);
        snapshots = new SnapshotManager(this);
        npcs = new NpcManager(this);
        siege = new SiegeManager(this);
        territories = new TerritoryManager(this);
        guilds = new GuildManager(this);
        history = new HistoryManager(this);
        gates = new GateManager(this);
        turrets = new TurretManager(this);
        banners = new BannerManager(this);
        events = new EventManager(this);
        zones = new ZoneManager(this);
        cinematic = new CinematicManager(this);
        missions = new MissionManager(this);
        board = new BoardManager(this);
        models = new ModelManager(this);
        raids = new RaidManager(this);
        repair = new RepairManager(this);
        layouts = new Layouts(this);
        menus = new MenuManager(this);
        prompts = new PromptManager(this);
        guis = new Guis(this);
        extraGuis = new ExtraGuis(this);

        Bukkit.getPluginManager().registerEvents(menus, this);
        Bukkit.getPluginManager().registerEvents(events, this);
        Bukkit.getPluginManager().registerEvents(missions, this);
        Bukkit.getPluginManager().registerEvents(cinematic, this);
        Bukkit.getPluginManager().registerEvents(new ZoneListener(this), this);
        Bukkit.getPluginManager().registerEvents(prompts, this);
        Bukkit.getPluginManager().registerEvents(new ProtectionListener(this), this);
        Bukkit.getPluginManager().registerEvents(new CombatListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerListener(this), this);

        GremioCommand gc = new GremioCommand(this);
        reg("gremio", gc, gc);
        TerritorioCommand tc = new TerritorioCommand(this);
        reg("territorio", tc, tc);
        GremioAdminCommand ac = new GremioAdminCommand(this);
        reg("gremioadmin", ac, ac);
        GremioXpCommand xc = new GremioXpCommand(this);
        reg("gremioxp", xc, xc);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new GremioExpansion(this).register();
            getLogger().info("PlaceholderAPI detectado: placeholders %gremio_*% registrados.");
        } else {
            getLogger().warning("PlaceholderAPI no está instalado: no habrá placeholders %gremio_*% para BeautyQuests.");
        }

        siege.registerRecipes();

        // tareas
        Bukkit.getScheduler().runTaskTimer(this, () -> raids.tick(), 40L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> siege.tickProjectiles(), 20L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, () -> guilds.tick(), 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> turrets.tick(), 40L, 10L);
        Bukkit.getScheduler().runTaskTimer(this, () -> events.tick(), 60L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> zones.tick(), 80L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, () -> board.refresh(), 200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, () -> models.tick(), 60L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, () -> territories.flushDirty(), 600L, 600L);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            guilds.saveAll();
            territories.saveAll();
            raids.save();
            zones.save();
            missions.savePlayers();
        }, 6000L, 6000L);
        Bukkit.getScheduler().runTaskTimer(this, () -> npcs.syncAll(), 12000L, 12000L);

        // arranque diferido (cuando los mundos ya están cargados)
        Bukkit.getScheduler().runTaskLater(this, () -> {
            territories.resumeResets();
            raids.restore();
            siege.rescan();
            turrets.rescan();
            zones.syncAll();
            board.syncAll();
            banners.refreshAll();
            npcs.syncAll();
            if (getConfig().getBoolean("dynmap.activo", true)) {
                dynmap = new DynmapHook(this);
                if (!dynmap.init()) dynmap = null;
            }
            getLogger().info("GremiosGUI listo: " + guilds.all().size() + " gremios, " + territories.all().size() + " territorios.");
            if (!currency.isSet()) getLogger().warning("Aún no hay moneda definida: usa /gremioadmin moneda set con el objeto en la mano.");
        }, 60L);

        getLogger().info("GremiosGUI " + getDescription().getVersion() + " activado.");
    }

    private void reg(String name, org.bukkit.command.CommandExecutor ex, org.bukkit.command.TabCompleter tab) {
        PluginCommand c = getCommand(name);
        if (c == null) {
            getLogger().warning("Comando no declarado en plugin.yml: " + name);
            return;
        }
        c.setExecutor(ex);
        if (tab != null && ex != tab) c.setTabCompleter(tab);
        else if (ex instanceof org.bukkit.command.TabCompleter tc) c.setTabCompleter(tc);
    }

    @Override
    public void onDisable() {
        if (raids != null) raids.shutdown();
        if (siege != null) siege.shutdown();
        if (events != null) events.shutdown();
        if (zones != null) zones.shutdown();
        if (cinematic != null) cinematic.restoreAll();
        if (missions != null) missions.savePlayers();
        if (guilds != null) guilds.saveAll();
        if (territories != null) territories.saveAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof com.etheria.gremios.gui.Menu) p.closeInventory();
        }
    }
}
