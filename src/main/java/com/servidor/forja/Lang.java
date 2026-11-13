package com.servidor.forja;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Mensajes en lang_es.yml (formato MiniMessage). Los placeholders son {nombre}. */
public final class Lang {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final ForjaPlugin plugin;
    private FileConfiguration cfg = new YamlConfiguration();

    public Lang(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        File f = new File(plugin.getDataFolder(), "lang_es.yml");
        if (!f.exists()) plugin.saveResource("lang_es.yml", false);
        cfg = YamlConfiguration.loadConfiguration(f);
        InputStream in = plugin.getResource("lang_es.yml");
        if (in != null) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    public String raw(String key, Object... kv) {
        String s = cfg.getString(key);   // (sin 2º argumento) consulta también los valores por defecto del jar
        if (s == null) s = key;
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", String.valueOf(kv[i + 1]));
        return s;
    }

    public Component get(String key, Object... kv) {
        return MM.deserialize(raw(key, kv));
    }

    public void send(CommandSender to, String key, Object... kv) {
        to.sendMessage(get("prefijo").append(get(key, kv)));
    }

    public static String esc(String s) {
        return MM.escapeTags(s);
    }

    public static Component mm(String s) {
        return MM.deserialize(s);
    }

    /** Componente sin cursiva (para nombres y lore). */
    public static Component line(String s) {
        return MM.deserialize("<!italic>" + s);
    }
}
