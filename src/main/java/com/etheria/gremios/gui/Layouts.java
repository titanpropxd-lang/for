package com.etheria.gremios.gui;

import com.etheria.gremios.GremiosPlugin;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lee layouts.yml (generado por tools/gen_layouts.py). El mismo archivo lo usa
 * tools/gen_pack.py para dibujar los fondos del resourcepack, así que botones y arte coinciden.
 */
public class Layouts {
    public static class Plaque {
        public final String id;
        public final List<Integer> slots = new ArrayList<>();
        public Material mat = Material.PAPER;
        public String label = "";

        public Plaque(String id) {
            this.id = id;
        }
    }

    public static class Layout {
        public final String id;
        public int rows = 6;
        public String title = "";
        /** Primer carácter de los 4 trozos del fondo (2x2) en la fuente gremios:gui. */
        public int glyphBase;
        public final List<Integer> grid = new ArrayList<>();
        public final Map<String, Plaque> plaques = new LinkedHashMap<>();

        public Layout(String id) {
            this.id = id;
        }
    }

    private final Map<String, Layout> menus = new LinkedHashMap<>();

    public Layouts(GremiosPlugin plugin) {
        InputStream in = plugin.getResource("layouts.yml");
        if (in == null) {
            plugin.getLogger().severe("Falta layouts.yml dentro del jar.");
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        ConfigurationSection root = y.getConfigurationSection("menus");
        if (root == null) return;
        int i = 0;
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) continue;
            Layout l = new Layout(id);
            l.rows = s.getInt("rows", 6);
            l.title = s.getString("title", id);
            l.glyphBase = 0xE000 + (i++) * 4;
            for (int slot : s.getIntegerList("grid")) l.grid.add(slot);
            ConfigurationSection ps = s.getConfigurationSection("plaques");
            if (ps != null) {
                for (String pid : ps.getKeys(false)) {
                    ConfigurationSection p = ps.getConfigurationSection(pid);
                    if (p == null) continue;
                    Plaque pq = new Plaque(pid);
                    int x = p.getInt("x"), yy = p.getInt("y"), w = p.getInt("w", 1), h = p.getInt("h", 1);
                    for (int dy = 0; dy < h; dy++) for (int dx = 0; dx < w; dx++) pq.slots.add((yy + dy) * 9 + x + dx);
                    Material m = Material.matchMaterial(p.getString("mat", "PAPER"));
                    pq.mat = m == null ? Material.PAPER : m;
                    pq.label = p.getString("label", pid);
                    l.plaques.put(pid, pq);
                }
            }
            menus.put(id, l);
        }
        plugin.getLogger().info("Layouts de menú cargados: " + menus.size());
    }

    public Layout get(String id) {
        Layout l = menus.get(id);
        if (l == null) throw new IllegalArgumentException("Layout desconocido: " + id);
        return l;
    }

    public Component title(Layout l, boolean pack) {
        if (!pack) return Component.text(l.title).color(NamedTextColor.GOLD);
        // El fondo se dibuja en 4 trozos de 256x256 px (2x2) porque una fuente no admite glifos mayores.
        //  = -8 px (empezar en el borde),  = -1 px (el glifo suma 1 px), 豈 = -256 px (volver al borde izquierdo).
        String s = "" + (char) l.glyphBase + "" + (char) (l.glyphBase + 1)
                + "豈" + (char) (l.glyphBase + 2) + "" + (char) (l.glyphBase + 3);
        return Component.text(s).color(NamedTextColor.WHITE).font(Key.key("gremios", "gui"));
    }

    public Map<String, Layout> all() {
        return menus;
    }
}
