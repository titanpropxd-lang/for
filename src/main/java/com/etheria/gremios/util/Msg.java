package com.etheria.gremios.util;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

/** Utilidades de mensajes. */
public final class Msg {
    public static final String PREFIX = "§6§l[Gremios] §r§7";

    private Msg() {}

    public static String c(String s) {
        return s == null ? "" : s.replace('&', '§');
    }

    public static void to(CommandSender s, String msg) {
        if (s != null) s.sendMessage(PREFIX + c(msg));
    }

    public static void raw(CommandSender s, String msg) {
        if (s != null) s.sendMessage(c(msg));
    }

    public static void actionbar(Player p, String msg) {
        if (p == null) return;
        p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(c(msg)));
    }

    public static String num(long n) {
        return String.format(Locale.US, "%,d", n).replace(',', '.');
    }

    /** Duración legible a partir de milisegundos. */
    public static String dur(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (d > 0) return d + "d " + h + "h";
        if (h > 0) return h + "h " + m + "m";
        if (m > 0) return m + "m " + sec + "s";
        return sec + "s";
    }

    public static String bar(double frac, int len, String fill, String empty) {
        if (frac < 0) frac = 0;
        if (frac > 1) frac = 1;
        int f = (int) Math.round(frac * len);
        StringBuilder sb = new StringBuilder();
        sb.append(fill);
        for (int i = 0; i < len; i++) {
            if (i == f) sb.append(empty);
            sb.append('|');
        }
        return sb.toString();
    }
}
