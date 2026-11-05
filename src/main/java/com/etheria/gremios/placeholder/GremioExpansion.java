package com.etheria.gremios.placeholder;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Raid;
import com.etheria.gremios.model.Territory;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Placeholders %gremio_*% para BeautyQuests, scoreboards, etc. */
public class GremioExpansion extends PlaceholderExpansion {
    private final GremiosPlugin plugin;

    public GremioExpansion(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() {
        return "gremio";
    }

    @Override
    public String getAuthor() {
        return "Etheria";
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
        Guild g = plugin.guilds.of(player.getUniqueId());
        String p = params.toLowerCase();
        switch (p) {
            case "tiene":
                return String.valueOf(g != null);
            case "nombre":
                return g == null ? "" : g.name;
            case "nivel":
                return g == null ? "0" : String.valueOf(g.level);
            case "territorio_nivel":
                return g == null ? "0" : String.valueOf(g.territoryLevel);
            case "territorio_nombre": {
                Territory t = g == null ? null : plugin.territories.ownedBy(g);
                return t == null ? "" : t.name;
            }
            case "rango_id": {
                if (g == null) return "0";
                var r = g.rankOf(player.getUniqueId());
                return r == null ? "0" : String.valueOf(r.id);
            }
            case "rango": {
                if (g == null) return "";
                var r = g.rankOf(player.getUniqueId());
                return r == null ? "" : r.display;
            }
            case "miembros":
                return g == null ? "0" : String.valueOf(g.members.size());
            case "banco":
                return g == null ? "0" : String.valueOf(g.bank);
            case "xp":
                return g == null ? "0" : String.valueOf(g.xp);
            case "xp_necesaria":
                return g == null ? "0" : String.valueOf(plugin.guilds.xpNeeded(g.level));
            case "en_raid": {
                Raid r = g == null ? null : plugin.raids.activeRaidOf(g);
                return String.valueOf(r != null);
            }
            case "dañado":
            case "danado": {
                Territory t = g == null ? null : plugin.territories.ownedBy(g);
                return String.valueOf(t != null && plugin.territories.isDamaged(t));
            }
            case "bloques_danados": {
                Territory t = g == null ? null : plugin.territories.ownedBy(g);
                return t == null ? "0" : String.valueOf(plugin.territories.damageCount(t));
            }
            case "aliados":
                return g == null ? "0" : String.valueOf(g.allies.size());
            default:
                return null;
        }
    }
}
