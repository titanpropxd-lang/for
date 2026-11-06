package com.servidor.forja;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Estilo visual de los menús. El título del inventario es un carácter de la fuente "forja:gui" (del resourcepack)
 * que dibuja el fondo del menú. Sin el resourcepack se vería un carácter suelto: por eso hay gui.estilo = vanilla.
 */
public final class GuiStyle {
    private final ForjaPlugin plugin;
    private final Map<String, Integer> glyphs = new HashMap<>();
    private int shift = 0xF808;

    public GuiStyle(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        glyphs.clear();
        InputStream in = plugin.getResource("gui_glyphs.yml");
        if (in == null) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        ConfigurationSection s = y.getConfigurationSection("glyphs");
        if (s != null) for (String k : s.getKeys(false)) glyphs.put(k, s.getInt(k));
        shift = y.getInt("desplazamiento", 0xF808);
    }

    /** true si los menús usan el fondo personalizado (resourcepack de Forja instalado). */
    public boolean custom() {
        return "custom".equalsIgnoreCase(plugin.getConfig().getString("gui.estilo", "custom"));
    }

    public Component title(String key, Component fallback) {
        Integer g = glyphs.get(key);
        if (!custom() || g == null) return fallback;
        String text = String.valueOf((char) shift) + (char) g.intValue();
        return Component.text(text).font(Key.key("forja", "gui")).color(NamedTextColor.WHITE);
    }
}
