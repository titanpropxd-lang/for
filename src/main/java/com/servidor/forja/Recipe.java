package com.servidor.forja;

import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/** Receta de forja (recipes/*.yml). Ingredientes: id custom, "vanilla:MATERIAL" o "fuel" (carbón). */
public final class Recipe {
    public String id, resultado, oficio, estacion, categoria;
    public Profession prof;
    public int cantidad, nivel, tiempo, xp, xpRaza, linaje;
    public LinkedHashMap<String, Integer> ing = new LinkedHashMap<>();
    public List<String> noConsumir = new ArrayList<>();
    public List<String> raza = new ArrayList<>();

    public static Recipe parse(String id, ConfigurationSection s) {
        Recipe r = new Recipe();
        r.id = id;
        r.resultado = s.getString("resultado", id);
        r.cantidad = Math.max(1, s.getInt("cantidad", 1));
        r.oficio = s.getString("oficio");
        r.prof = Profession.fromId(r.oficio);
        if (r.prof == null) throw new IllegalArgumentException("oficio inválido: " + r.oficio);
        r.estacion = s.getString("estacion", "yunque");
        r.nivel = s.getInt("nivel", 1);
        r.tiempo = s.getInt("tiempo", 3);
        r.xp = s.getInt("xp", 5);
        r.xpRaza = s.getInt("xp_raza", 0);
        r.categoria = s.getString("categoria", "Varios");
        r.linaje = s.getInt("linaje", 0);
        r.raza = s.getStringList("raza");
        r.noConsumir = s.getStringList("no_consumir");
        ConfigurationSection ing = s.getConfigurationSection("ingredientes");
        if (ing != null) {
            for (String k : ing.getKeys(false)) r.ing.put(k, ing.getInt(k));
        }
        return r;
    }
}
