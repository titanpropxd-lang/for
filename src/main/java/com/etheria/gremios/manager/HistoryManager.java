package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Historial de asedios (historial.yml). */
public class HistoryManager {
    public static class Entry {
        public long time;
        public String territory, attacker, defender, result;
        public long stolen;
    }

    private final GremiosPlugin plugin;
    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public HistoryManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "historial.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = y.getConfigurationSection("entradas");
        if (root == null) return;
        for (String k : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(k);
            if (s == null) continue;
            Entry e = new Entry();
            e.time = s.getLong("tiempo");
            e.territory = s.getString("territorio", "?");
            e.attacker = s.getString("atacante", "?");
            e.defender = s.getString("defensor", "?");
            e.result = s.getString("resultado", "?");
            e.stolen = s.getLong("botin");
            entries.add(e);
        }
    }

    public void add(String territory, String attacker, String defender, String result, long stolen) {
        Entry e = new Entry();
        e.time = System.currentTimeMillis();
        e.territory = territory;
        e.attacker = attacker;
        e.defender = defender;
        e.result = result;
        e.stolen = stolen;
        entries.add(e);
        int max = plugin.getConfig().getInt("historial.max-entradas", 100);
        while (entries.size() > max) entries.remove(0);
        save();
    }

    /** Últimas n entradas (más recientes primero); si name != null, solo donde participe ese gremio. */
    public List<Entry> recent(String name, int n) {
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < n; i--) {
            Entry e = entries.get(i);
            if (name == null || name.equalsIgnoreCase(e.attacker) || name.equalsIgnoreCase(e.defender)) out.add(e);
        }
        return out;
    }

    public String line(Entry e) {
        String date = new SimpleDateFormat("dd/MM HH:mm").format(new Date(e.time));
        String res = switch (e.result) {
            case "ATACANTE" -> "§cvictoria atacante";
            case "DEFENSOR" -> "§avictoria defensor";
            default -> "§7cancelado";
        };
        return "§8" + date + " §6" + e.attacker + " §7→ §a" + e.defender + " §8(" + e.territory + ") " + res
                + (e.stolen > 0 ? " §8| §ebotín " + e.stolen : "");
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("entradas");
        int i = 0;
        for (Entry e : entries) {
            ConfigurationSection s = root.createSection(String.valueOf(i++));
            s.set("tiempo", e.time);
            s.set("territorio", e.territory);
            s.set("atacante", e.attacker);
            s.set("defensor", e.defender);
            s.set("resultado", e.result);
            s.set("botin", e.stolen);
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("No se pudo guardar historial.yml: " + ex.getMessage());
        }
    }
}
