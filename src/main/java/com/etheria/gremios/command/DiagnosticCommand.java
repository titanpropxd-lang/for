package com.etheria.gremios.command;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.manager.GateManager;
import com.etheria.gremios.manager.ZoneManager;
import com.etheria.gremios.model.EventDef;
import com.etheria.gremios.model.NpcRole;
import com.etheria.gremios.model.Territory;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

/** /gremioadmin diagnostico: revisa qué falta configurar. */
public class DiagnosticCommand {
    private final GremiosPlugin plugin;
    private final List<String> out = new ArrayList<>();
    private int bad, warn;

    public DiagnosticCommand(GremiosPlugin plugin) {
        this.plugin = plugin;
    }

    private void ok(String m) {
        out.add("§a ✔ §7" + m);
    }

    private void err(String m) {
        bad++;
        out.add("§c ✘ §f" + m);
    }

    private void warn(String m) {
        warn++;
        out.add("§e ! §7" + m);
    }

    private void head(String m) {
        out.add("§6§l" + m);
    }

    private boolean world(String w) {
        return w != null && Bukkit.getWorld(w) != null;
    }

    public void run(CommandSender s) {
        head("General");
        if (plugin.currency.isSet()) ok("Moneda configurada: " + plugin.currency.name());
        else err("No hay moneda. Sostén el objeto y usa §e/gremioadmin moneda set");
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) ok("PlaceholderAPI detectado");
        else warn("Sin PlaceholderAPI: BeautyQuests no podrá leer %gremio_*%");
        if (plugin.dynmap != null) ok("Dynmap enlazado");
        else warn("Dynmap no activo (opcional)");
        if (plugin.usePack()) warn("gui.usar-resourcepack=true: los jugadores necesitan el resourcepack aplicado");
        else ok("GUI en modo texto (sin resourcepack)");
        double tps = Bukkit.getTPS()[0];
        if (tps < 18) warn("TPS bajo: " + String.format("%.1f", tps));
        else ok("TPS " + String.format("%.1f", tps));

        head("Territorios (" + plugin.territories.all().size() + ")");
        if (plugin.territories.all().isEmpty()) warn("No hay territorios creados");
        for (Territory t : plugin.territories.all()) {
            List<String> p = new ArrayList<>();
            if (!world(t.world)) p.add("mundo '" + t.world + "' no existe");
            if (!plugin.snapshots.has(t)) p.add("sin snapshot (/territorio snapshot)");
            if (t.node == null) p.add("sin posición de nodo");
            if (t.spawn == null) p.add("sin spawn (usará el centro)");
            if (t.price <= 0) p.add("precio inválido");
            List<String> npcs = new ArrayList<>();
            for (NpcRole r : NpcRole.values()) if (!t.npcPos.containsKey(r.key())) npcs.add(r.key());
            if (!npcs.isEmpty()) p.add("sin NPC: " + String.join(",", npcs));
            if (t.resetting) p.add("está en reseteo");
            if (p.isEmpty()) ok(t.name);
            else err(t.name + ": " + String.join("; ", p));
        }

        head("Puertas (" + plugin.gates.all().size() + ")");
        for (GateManager.Gate g : plugin.gates.all()) {
            if (!world(g.world)) err("Puerta " + g.key() + ": mundo inexistente");
            else if (g.palette.isEmpty() && !g.open) warn("Puerta " + g.key() + ": sin bloques capturados");
            else ok("Puerta " + g.key());
        }

        head("Zonas de recursos (" + plugin.zones.all().size() + ")");
        for (ZoneManager.Zone z : plugin.zones.all()) {
            List<String> p = new ArrayList<>();
            if (!z.hasArea) p.add("sin área");
            if (z.npc == null) p.add("sin aldeano");
            if (z.produce.isEmpty()) p.add("sin recursos que producir");
            if (!world(z.world)) p.add("mundo inexistente");
            if (p.isEmpty()) ok(z.name + " (nivel " + plugin.zones.levelOf(z) + ")");
            else err(z.name + ": " + String.join("; ", p));
        }

        head("Eventos (" + plugin.events.all().size() + ")");
        for (EventDef e : plugin.events.all()) {
            List<String> p = new ArrayList<>();
            if (e.type == EventDef.Type.KOTH && !e.hasZone) p.add("KOTH sin zona");
            if (e.rewardXp <= 0 && e.rewardCrowns <= 0) p.add("sin recompensa");
            if (p.isEmpty()) ok(e.name + (e.enabled ? "" : " (desactivado)"));
            else err(e.name + ": " + String.join("; ", p));
        }

        head("Misiones y tablones");
        if (plugin.missions.all().isEmpty()) warn("No hay misiones (/gremioadmin mision crear)");
        else ok(plugin.missions.all().size() + " misiones, tope " + plugin.missions.dailyCap() + "/día por jugador");
        if (plugin.board.all().isEmpty()) warn("No hay tablón físico (/gremioadmin tablon crear <id>)");
        else ok(plugin.board.all().size() + " tablón(es)");

        int ents = 0;
        for (World w : Bukkit.getWorlds()) ents += w.getEntityCount();
        if (ents > 6000) warn("Muchas entidades cargadas: " + ents);

        s.sendMessage("§6§m                    §r §6Diagnóstico de Gremios §6§m                    ");
        for (String l : out) s.sendMessage(l);
        s.sendMessage(bad == 0 ? "§aTodo lo esencial está configurado." + (warn > 0 ? " §7(" + warn + " avisos)" : "")
                : "§c" + bad + " problema(s) §7y §e" + warn + " aviso(s).");
    }
}
