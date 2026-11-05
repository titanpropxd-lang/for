package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Objective;

import java.io.File;
import java.util.*;

/** Lee la raza del jugador (marcadores rz_race, rz_lvl, rz_lin) y aplica bonificaciones de razas.yml. */
public final class RaceService {
    public record Info(int id, String key, int level, int lineage) {
        public boolean has() {
            return id > 0;
        }
    }

    public enum Elig { OK, FOREIGN, DENIED }

    private final ForjaPlugin plugin;
    private YamlConfiguration cfg = new YamlConfiguration();
    private final Map<Integer, String> idToKey = new HashMap<>();

    public RaceService(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "razas.yml");
        if (!f.exists()) plugin.saveResource("razas.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        idToKey.clear();
        ConfigurationSection s = cfg.getConfigurationSection("razas");
        if (s != null) for (String k : s.getKeys(false)) idToKey.put(s.getInt(k + ".id"), k);
    }

    private int score(String obj, Player p) {
        Objective o = Bukkit.getScoreboardManager().getMainScoreboard().getObjective(obj);
        return o == null ? 0 : o.getScore(p.getName()).getScore();
    }

    public Info get(Player p) {
        int id = score("rz_race", p);
        if (id <= 0) return new Info(0, "", 0, 0);
        return new Info(id, idToKey.getOrDefault(id, ""), score("rz_lvl", p), score("rz_lin", p));
    }

    public String name(String key) {
        return cfg.getString("razas." + key + ".nombre", key);
    }

    public Elig eligibility(Info r, Recipe rec) {
        if (rec.raza.isEmpty()) return Elig.OK;
        boolean own = r.has() && rec.raza.contains(r.key());
        if (rec.linaje > 0) return own && r.lineage() == rec.linaje ? Elig.OK : Elig.DENIED;
        if (own) return Elig.OK;
        FileConfiguration c = plugin.getConfig();
        if (!r.has()) return c.getBoolean("razas_ajenas.sin_raza_solo_basicas", true) ? Elig.DENIED : Elig.FOREIGN;
        return c.getBoolean("razas_ajenas.permitir", true) ? Elig.FOREIGN : Elig.DENIED;
    }

    /** Puntos de calidad extra por raza (oficio, estación, noche). */
    public double calidad(Info r, Profession prof, String station, Player p, CustomItem ci) {
        if (!r.has()) return 0;
        String base = "razas." + r.key();
        double v = cfg.getDouble(base + ".calidad_oficio." + prof.id, 0);
        v += cfg.getDouble(base + ".calidad_estacion." + station, 0);
        if (ci != null && ci.maestria != null) v += cfg.getDouble(base + ".calidad_tipos." + ci.maestria, 0);
        double noche = cfg.getDouble(base + ".bonus_noche", 0);
        if (noche > 0 && isNight(p.getWorld())) v += noche;
        return v;
    }

    public static boolean isNight(World w) {
        long t = w.getTime();
        return t >= 13000 && t <= 23000;
    }

    /** Fracción (0-1) de ahorro de un ingrediente concreto. */
    public double ahorro(Info r, String key) {
        if (!r.has()) return 0;
        double best = 0;
        ConfigurationSection s = cfg.getConfigurationSection("razas." + r.key() + ".ahorro");
        if (s == null) return 0;
        for (String tag : s.getKeys(false)) {
            if (matchesTag(tag, key)) best = Math.max(best, s.getDouble(tag));
        }
        return best;
    }

    private boolean matchesTag(String tag, String key) {
        if (tag.equals("todos")) return !key.equals("fuel");
        ConfigurationSection t = cfg.getConfigurationSection("etiquetas_material." + tag);
        if (t == null) return false;
        for (String pre : t.getStringList("prefijos")) if (key.startsWith(pre)) return true;
        return t.getStringList("ids").contains(key);
    }

    public double ahorroCombustible(Info r) {
        return r.has() ? cfg.getDouble("razas." + r.key() + ".ahorro_combustible", 0) : 0;
    }

    /** Multiplicador de daño/defensa extra para ciertas recetas (p. ej. orco con gran hacha en tiers bajos). */
    public double dmgMult(Info r, Recipe rec, CustomItem ci) {
        if (!r.has() || ci.isRpg()) return 1.0; // las armas RPG ya traen su propia afinidad racial
        ConfigurationSection s = cfg.getConfigurationSection("razas." + r.key() + ".bonus_dano");
        if (s == null) return 1.0;
        if (ci.tier != null && s.getStringList("tiers").contains(ci.tier) && ci.maestria != null && s.getStringList("tipos").contains(ci.maestria)) {
            return 1.0 + s.getDouble("pct", 0) / 100.0;
        }
        return 1.0;
    }

    /** Bonus de probabilidad de mejora (puntos porcentuales) por raza. */
    public double bonusMejora(Info r) {
        return r.has() ? cfg.getDouble("razas." + r.key() + ".bonus_mejora", 0) : 0;
    }

    /** Bonus extra de desguace (fracción). */
    public double bonusDesguace(Info r) {
        return r.has() ? cfg.getDouble("razas." + r.key() + ".bonus_desguace", 0) : 0;
    }
}
