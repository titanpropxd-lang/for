package com.servidor.forja;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;

/** Definición de un item del catálogo (items/*.yml). */
public final class CustomItem {
    public String id, nombre, categoria, rareza, tier, oficio, maestria, estilo, trimPattern, slot;
    public Material base;
    public int cmd, nivelReq, valor, linaje, durabilidad;
    public List<String> lore = new ArrayList<>();
    public List<String> raza = new ArrayList<>();
    public Map<String, Object> atributos = new HashMap<>();
    public Map<String, Object> pasivo = new HashMap<>();
    public Map<String, Object> efectos = new HashMap<>();
    public Map<String, Object> bonus = new HashMap<>();
    public Map<String, Object> customData = new LinkedHashMap<>();

    public static CustomItem parse(String id, ConfigurationSection s) {
        CustomItem c = new CustomItem();
        c.id = id;
        c.base = Material.matchMaterial(s.getString("base", ""));
        if (c.base == null) throw new IllegalArgumentException("base inválida: " + s.getString("base"));
        c.nombre = s.getString("nombre", id);
        c.categoria = s.getString("categoria", "material");
        c.cmd = s.getInt("cmd");
        c.lore = s.getStringList("lore");
        c.rareza = s.getString("rareza", "comun");
        c.tier = s.getString("tier");
        c.oficio = s.getString("oficio");
        c.nivelReq = s.getInt("nivel_req", 1);
        c.raza = s.getStringList("raza");
        c.maestria = s.getString("maestria");
        c.estilo = s.getString("estilo");
        c.trimPattern = s.getString("trim_pattern");
        c.slot = s.getString("slot");
        c.valor = s.getInt("valor", 1);
        c.linaje = s.getInt("linaje", 0);
        c.durabilidad = s.getInt("durabilidad", 0);
        c.atributos = map(s, "atributos");
        c.pasivo = map(s, "pasivo");
        c.efectos = map(s, "efectos");
        c.bonus = map(s, "bonus");
        c.customData = map(s, "custom_data");
        return c;
    }

    private static Map<String, Object> map(ConfigurationSection s, String key) {
        ConfigurationSection sec = s.getConfigurationSection(key);
        return sec == null ? new LinkedHashMap<>() : new LinkedHashMap<>(sec.getValues(false));
    }

    /** Armas/armaduras/joyas con calidad (la munición no tiene). */
    public boolean usesQuality() {
        return (categoria.equals("arma") || categoria.equals("armadura") || categoria.equals("joya")) && base != Material.ARROW;
    }

    /** Armas y armaduras que se pueden mejorar/reparar. */
    public boolean isEquipment() {
        return (categoria.equals("arma") || categoria.equals("armadura")) && base != Material.ARROW;
    }

    public static double dbl(Map<String, Object> m, String k, double def) {
        Object o = m.get(k);
        return o instanceof Number n ? n.doubleValue() : def;
    }

    public double attr(String k) {
        return dbl(atributos, k, 0);
    }
}
