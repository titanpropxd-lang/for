package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

public final class RecipeRegistry {
    private final ForjaPlugin plugin;
    private final Map<String, Recipe> recipes = new LinkedHashMap<>();

    public RecipeRegistry(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        recipes.clear();
        File dir = new File(plugin.getDataFolder(), "recipes");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) {
            ConfigurationSection sec = YamlConfiguration.loadConfiguration(f).getConfigurationSection("recetas");
            if (sec == null) continue;
            for (String id : sec.getKeys(false)) {
                try {
                    Recipe r = Recipe.parse(id, sec.getConfigurationSection(id));
                    validate(r);
                    recipes.put(id, r);
                } catch (Exception e) {
                    plugin.getLogger().warning("Receta inválida " + id + " (" + f.getName() + "): " + e.getMessage());
                }
            }
        }
    }

    private void validate(Recipe r) {
        if (plugin.items.get(r.resultado) == null) throw new IllegalArgumentException("resultado desconocido: " + r.resultado);
        List<String> keys = new ArrayList<>(r.ing.keySet());
        keys.addAll(r.noConsumir);
        for (String k : keys) {
            if (k.equals("fuel")) continue;
            if (k.startsWith("vanilla:")) {
                if (Material.matchMaterial(k.substring(8)) == null) throw new IllegalArgumentException("material vanilla inválido: " + k);
            } else if (plugin.items.get(k) == null) {
                throw new IllegalArgumentException("ingrediente desconocido: " + k);
            }
        }
    }

    public Collection<Recipe> all() {
        return recipes.values();
    }

    public Recipe get(String id) {
        return recipes.get(id);
    }

    public Recipe byResult(String resultId) {
        for (Recipe r : recipes.values()) if (r.resultado.equals(resultId)) return r;
        return null;
    }

    public List<Recipe> forStation(String station) {
        List<Recipe> out = new ArrayList<>();
        for (Recipe r : recipes.values()) if (r.estacion.equals(station)) out.add(r);
        return out;
    }
}
