package com.servidor.forja;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/**
 * Placeholders (PlaceholderAPI):
 * %forja_nivel_herreria%, %forja_xp_herreria%, %forja_xp_necesaria_herreria%,
 * %forja_total_forjados%, %forja_forjado_<id_item>%  (útil para BeautyQuests).
 */
public final class ForjaExpansion extends PlaceholderExpansion {
    private final ForjaPlugin plugin;

    public ForjaExpansion(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "forja";
    }

    @Override
    public String getAuthor() {
        return "Forja";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        if (player == null) return "";
        PlayerData d = plugin.professions.get(player.getUniqueId());
        String p = params.toLowerCase();
        if (p.equals("total_forjados")) return String.valueOf(d.total());
        if (p.startsWith("forjado_")) return String.valueOf(d.stats.getOrDefault(p.substring(8), 0));
        if (p.startsWith("nivel_")) {
            Profession pr = Profession.fromId(p.substring(6));
            return pr == null ? "" : String.valueOf(d.level(pr));
        }
        if (p.startsWith("xp_necesaria_")) {
            Profession pr = Profession.fromId(p.substring(13));
            return pr == null ? "" : String.valueOf(plugin.professions.xpNeeded(d.level(pr)));
        }
        if (p.startsWith("xp_")) {
            Profession pr = Profession.fromId(p.substring(3));
            return pr == null ? "" : String.valueOf(d.xp(pr));
        }
        return null;
    }
}
