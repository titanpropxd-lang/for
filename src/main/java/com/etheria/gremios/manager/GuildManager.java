package com.etheria.gremios.manager;

import com.etheria.gremios.GremiosPlugin;
import com.etheria.gremios.model.Guild;
import com.etheria.gremios.model.Rank;
import com.etheria.gremios.model.Territory;
import com.etheria.gremios.util.Msg;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Gestión de gremios: miembros, XP, banco, invitaciones, alianzas y mantenimiento. */
public class GuildManager {
    public static class Invite {
        public final String guildId;
        public final UUID inviter;
        public final long expires;

        public Invite(String guildId, UUID inviter, long expires) {
            this.guildId = guildId;
            this.inviter = inviter;
            this.expires = expires;
        }
    }

    private static final Pattern NAME_OK = Pattern.compile("^[\\p{L}0-9_ ]{3,16}$");

    private final GremiosPlugin plugin;
    private final File guildsFile;
    private final File playersFile;

    private final Map<String, Guild> guilds = new LinkedHashMap<>();
    private final Map<UUID, String> playerGuild = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Set<UUID> creators = new HashSet<>();
    private final Map<UUID, long[]> daily = new HashMap<>(); // day, xp, repXp
    private final Set<UUID> guildChat = new HashSet<>();

    public GuildManager(GremiosPlugin plugin) {
        this.plugin = plugin;
        this.guildsFile = new File(plugin.getDataFolder(), "guilds.yml");
        this.playersFile = new File(plugin.getDataFolder(), "jugadores.yml");
        load();
    }

    // ------------------------------------------------------------------ consulta

    public Collection<Guild> all() {
        return guilds.values();
    }

    public Guild byId(String id) {
        return id == null ? null : guilds.get(id);
    }

    public Guild byName(String name) {
        if (name == null) return null;
        for (Guild g : guilds.values()) if (g.name.equalsIgnoreCase(name) || g.id.equalsIgnoreCase(name.replace(' ', '_'))) return g;
        return null;
    }

    public Guild of(UUID u) {
        String id = playerGuild.get(u);
        return id == null ? null : guilds.get(id);
    }

    public Guild of(Player p) {
        return p == null ? null : of(p.getUniqueId());
    }

    public Rank rankOf(Player p) {
        Guild g = of(p);
        return g == null ? null : g.rankOf(p.getUniqueId());
    }

    public boolean isAllied(Guild a, Guild b) {
        return a != null && b != null && a.allies.contains(b.id);
    }

    public List<Player> onlineMembers(Guild g) {
        List<Player> out = new ArrayList<>();
        if (g == null) return out;
        for (UUID u : g.members.keySet()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    public int online(Guild g) {
        return onlineMembers(g).size();
    }

    public void broadcast(Guild g, String msg) {
        for (Player p : onlineMembers(g)) Msg.to(p, msg);
    }

    public List<Guild> top(int n) {
        List<Guild> l = new ArrayList<>(guilds.values());
        l.sort(Comparator.comparingInt((Guild g) -> g.level).thenComparingLong(g -> g.xp).reversed());
        return l.size() > n ? l.subList(0, n) : l;
    }

    // ------------------------------------------------------------------ permisos de creación

    public boolean canCreate(UUID u) {
        return creators.contains(u);
    }

    public void setCanCreate(UUID u, boolean v) {
        if (v) creators.add(u);
        else creators.remove(u);
        savePlayers();
    }

    // ------------------------------------------------------------------ creación / borrado

    public String validName(String name) {
        if (name == null || !NAME_OK.matcher(name).matches()) return "El nombre debe tener entre 3 y 16 caracteres (letras, números, espacios o _).";
        if (byName(name) != null) return "Ya existe un gremio con ese nombre.";
        return null;
    }

    public int maxMembers(Guild g) {
        List<Integer> base = plugin.getConfig().getIntegerList("miembros.max-por-nivel");
        int lvl = Math.max(1, g.territoryLevel);
        int b = base.size() >= lvl ? base.get(lvl - 1) : (lvl == 1 ? 8 : lvl == 2 ? 20 : 40);
        int per = plugin.getConfig().getInt("mejoras.capacidad.miembros-por-nivel", 5);
        return b + g.upCapacity * per + g.level / 5;
    }

    public Guild create(String name, Player leader, Territory t) {
        String id = name.toLowerCase().replace(' ', '_');
        Guild g = new Guild(id, name, leader.getUniqueId());
        g.members.put(leader.getUniqueId(), new Guild.MemberData(leader.getName(), Rank.LIDER));
        playerGuild.put(leader.getUniqueId(), g.id);
        guilds.put(g.id, g);
        long hours = plugin.getConfig().getLong("raid.proteccion-nuevo-gremio-horas", 24);
        g.protectedUntil = System.currentTimeMillis() + hours * 3600_000L;
        if (t != null) plugin.territories.claim(t, g);
        save();
        return g;
    }

    public void addMember(Guild g, UUID u, String name, Rank r) {
        g.members.put(u, new Guild.MemberData(name, r));
        g.requests.remove(u);
        playerGuild.put(u, g.id);
        invites.remove(u);
        save();
    }

    public void removeMember(Guild g, UUID u) {
        g.members.remove(u);
        playerGuild.remove(u);
        guildChat.remove(u);
        save();
    }

    public void disband(Guild g) {
        plugin.raids.onGuildGone(g);
        if (g.hasTerritory()) {
            Territory t = plugin.territories.get(g.territoryId);
            if (t != null) plugin.territories.release(t, true, null);
        }
        for (UUID u : new ArrayList<>(g.members.keySet())) {
            playerGuild.remove(u);
            guildChat.remove(u);
        }
        for (Guild o : guilds.values()) {
            o.allies.remove(g.id);
            o.allyRequestsIn.remove(g.id);
        }
        guilds.remove(g.id);
        save();
    }

    // ------------------------------------------------------------------ XP y niveles

    public long xpNeeded(int level) {
        return (long) level * plugin.getConfig().getLong("xp.por-nivel", 500) + plugin.getConfig().getLong("xp.base", 1000);
    }

    public void addXp(Guild g, long amount) {
        if (amount <= 0) return;
        if (g.level >= Guild.MAX_LEVEL) return;
        g.xp += amount;
        while (g.level < Guild.MAX_LEVEL && g.xp >= xpNeeded(g.level)) {
            g.xp -= xpNeeded(g.level);
            g.level++;
            for (Player p : onlineMembers(g)) {
                Msg.to(p, "§e¡Tu gremio ha subido al §6nivel " + g.level + "§e!");
                p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            }
        }
        if (g.level >= Guild.MAX_LEVEL) g.xp = 0;
    }

    /** Entrega XP de misión. Devuelve texto de error o null si fue bien. */
    public String questXp(Player p, long amount, boolean rep) {
        Guild g = of(p);
        if (g == null) return "no tiene gremio";
        long max = plugin.getConfig().getLong("xp.max-por-entrega", 2000);
        amount = Math.min(amount, max);
        if (amount <= 0) return "cantidad inválida";
        long today = LocalDate.now().toEpochDay();
        long[] d = daily.computeIfAbsent(p.getUniqueId(), k -> new long[]{today, 0, 0});
        if (d[0] != today) {
            d[0] = today;
            d[1] = 0;
            d[2] = 0;
        }
        long capTotal = plugin.getConfig().getLong("xp.tope-diario-jugador", 0);
        long capRep = plugin.getConfig().getLong("xp.tope-diario-repetibles", 600);
        if (rep && capRep > 0) {
            amount = Math.min(amount, Math.max(0, capRep - d[2]));
        }
        if (capTotal > 0) {
            amount = Math.min(amount, Math.max(0, capTotal - d[1]));
        }
        if (amount <= 0) {
            Msg.to(p, "§cYa alcanzaste el tope diario de XP de gremio por misiones.");
            return null;
        }
        d[1] += amount;
        if (rep) d[2] += amount;
        addXp(g, amount);
        Msg.raw(p, "§6✦ §e+" + Msg.num(amount) + " XP de gremio §7(misión)");
        savePlayers();
        save();
        return null;
    }

    // ------------------------------------------------------------------ invitaciones y solicitudes

    public Invite getInvite(UUID target) {
        Invite i = invites.get(target);
        if (i != null && i.expires < System.currentTimeMillis()) {
            invites.remove(target);
            return null;
        }
        return i;
    }

    public String invite(Guild g, Player inviter, Player target) {
        if (of(target) != null) return "Ese jugador ya pertenece a un gremio.";
        if (g.members.size() >= maxMembers(g)) return "El gremio está lleno (" + maxMembers(g) + " miembros). Mejora el territorio o la capacidad.";
        long ttl = plugin.getConfig().getLong("invitaciones.expira-segundos", 300) * 1000L;
        invites.put(target.getUniqueId(), new Invite(g.id, inviter.getUniqueId(), System.currentTimeMillis() + ttl));
        Msg.to(inviter, "§aInvitación enviada a §f" + target.getName() + "§a.");
        Msg.to(target, "§e" + inviter.getName() + " §7te invita al gremio §6" + g.name + "§7.");
        TextComponent yes = new TextComponent("  [ACEPTAR] ");
        yes.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        yes.setBold(true);
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gremio aceptar"));
        yes.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("Unirte a " + g.name)));
        TextComponent no = new TextComponent("[RECHAZAR]");
        no.setColor(net.md_5.bungee.api.ChatColor.RED);
        no.setBold(true);
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/gremio rechazar"));
        target.spigot().sendMessage(new ComponentBuilder("").append(yes).append(no).create());
        target.playSound(target.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        return null;
    }

    public String acceptInvite(Player p) {
        Invite i = getInvite(p.getUniqueId());
        if (i == null) return "No tienes invitaciones pendientes.";
        Guild g = byId(i.guildId);
        if (g == null) {
            invites.remove(p.getUniqueId());
            return "Ese gremio ya no existe.";
        }
        if (of(p) != null) return "Ya perteneces a un gremio.";
        if (g.members.size() >= maxMembers(g)) return "El gremio está lleno.";
        addMember(g, p.getUniqueId(), p.getName(), Rank.RECLUTA);
        broadcast(g, "§a" + p.getName() + " §7se unió al gremio.");
        return null;
    }

    public void declineInvite(Player p) {
        Invite i = invites.remove(p.getUniqueId());
        if (i != null) {
            Player inv = Bukkit.getPlayer(i.inviter);
            if (inv != null) Msg.to(inv, "§c" + p.getName() + " rechazó tu invitación.");
        }
    }

    public String request(Guild g, Player p) {
        if (of(p) != null) return "Ya perteneces a un gremio.";
        if (g.requests.containsKey(p.getUniqueId())) return "Ya enviaste una solicitud a ese gremio.";
        g.requests.put(p.getUniqueId(), p.getName());
        for (Player o : onlineMembers(g)) {
            Rank r = g.rankOf(o.getUniqueId());
            if (r != null && r.atLeast(Rank.OFICIAL)) Msg.to(o, "§e" + p.getName() + " §7solicita unirse al gremio. Revisa el menú de solicitudes.");
        }
        save();
        return null;
    }

    public String acceptRequest(Guild g, UUID u) {
        String name = g.requests.get(u);
        if (name == null) return "La solicitud ya no existe.";
        if (of(u) != null) {
            g.requests.remove(u);
            return "Ese jugador ya está en otro gremio.";
        }
        if (g.members.size() >= maxMembers(g)) return "El gremio está lleno.";
        addMember(g, u, name, Rank.RECLUTA);
        broadcast(g, "§a" + name + " §7fue aceptado en el gremio.");
        Player t = Bukkit.getPlayer(u);
        if (t != null) Msg.to(t, "§aTu solicitud para §6" + g.name + " §afue aceptada.");
        return null;
    }

    public void denyRequest(Guild g, UUID u) {
        g.requests.remove(u);
        save();
    }

    // ------------------------------------------------------------------ banco

    public void deposit(Guild g, Player p, long amount) {
        g.bank += amount;
        g.log("§a+" + Msg.num(amount) + " §7" + p.getName());
        save();
    }

    public boolean withdraw(Guild g, Player p, long amount) {
        if (g.bank < amount) return false;
        g.bank -= amount;
        g.log("§c-" + Msg.num(amount) + " §7" + p.getName());
        save();
        return true;
    }

    // ------------------------------------------------------------------ alianzas

    public String proposeAlly(Guild a, Guild b) {
        if (a == b) return "No puedes aliarte contigo mismo.";
        if (a.allies.contains(b.id)) return "Ya sois aliados.";
        if (a.allyRequestsIn.contains(b.id)) {
            // el otro ya nos había propuesto: aceptar
            return acceptAlly(a, b);
        }
        b.allyRequestsIn.add(a.id);
        broadcast(b, "§e" + a.name + " §7propone una alianza. Acéptala en el menú de Alianzas.");
        broadcast(a, "§7Propuesta de alianza enviada a §e" + b.name + "§7.");
        save();
        return null;
    }

    public String acceptAlly(Guild accepter, Guild proposer) {
        accepter.allyRequestsIn.remove(proposer.id);
        proposer.allyRequestsIn.remove(accepter.id);
        accepter.allies.add(proposer.id);
        proposer.allies.add(accepter.id);
        broadcast(accepter, "§aAhora sois aliados de §6" + proposer.name + "§a.");
        broadcast(proposer, "§aAhora sois aliados de §6" + accepter.name + "§a.");
        save();
        return null;
    }

    public void breakAlly(Guild a, Guild b) {
        a.allies.remove(b.id);
        b.allies.remove(a.id);
        broadcast(a, "§cLa alianza con §6" + b.name + " §cse ha roto.");
        broadcast(b, "§cLa alianza con §6" + a.name + " §cse ha roto.");
        save();
    }

    // ------------------------------------------------------------------ chat de gremio

    public boolean toggleChat(UUID u) {
        if (guildChat.remove(u)) return false;
        guildChat.add(u);
        return true;
    }

    public boolean inGuildChat(UUID u) {
        return guildChat.contains(u);
    }

    public void chat(Guild g, Player from, String msg) {
        String line = "§6[" + g.name + "] §7" + g.rankOf(from.getUniqueId()).display + " §f" + from.getName() + "§7: §f" + msg;
        for (Player p : onlineMembers(g)) p.sendMessage(line);
        plugin.getLogger().info("[GremioChat:" + g.name + "] " + from.getName() + ": " + msg);
    }

    // ------------------------------------------------------------------ mantenimiento

    /** Se llama cada minuto. */
    public void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Guild g : new ArrayList<>(guilds.values())) {
            if (!g.hasTerritory()) continue;
            if (g.nextUpkeep <= 0) {
                g.nextUpkeep = now + periodMs();
                changed = true;
                continue;
            }
            if (now < g.nextUpkeep) continue;
            // en raid no se cobra (se pospone un poco)
            if (plugin.raids.activeRaidOf(g) != null) continue;
            changed = true;
            g.nextUpkeep = now + periodMs();
            long cost = upkeepCost(g);
            if (g.dues > 0) {
                for (Guild.MemberData m : g.members.values()) m.duesPending += g.dues;
            }
            if (g.bank >= cost) {
                g.bank -= cost;
                g.upkeepMissed = 0;
                g.log("§6-" + Msg.num(cost) + " §7mantenimiento");
                broadcast(g, "§7Se pagó el mantenimiento del territorio: §e" + plugin.currency.fmt(cost) + "§7.");
            } else {
                g.upkeepMissed++;
                int max = plugin.getConfig().getInt("mantenimiento.impagos-max", 3);
                broadcast(g, "§c¡No hay fondos para el mantenimiento (" + plugin.currency.fmt(cost) + ")! Impago " + g.upkeepMissed + "/" + max + ". Deposita en el banco.");
                if (g.upkeepMissed >= max) {
                    broadcast(g, "§4Tu gremio perdió el territorio por impagos.");
                    plugin.territories.abandon(g);
                }
            }
        }
        // limpieza de invitaciones
        invites.entrySet().removeIf(e -> e.getValue().expires < now);
        if (changed) save();
    }

    public long periodMs() {
        return (long) (plugin.getConfig().getDouble("mantenimiento.dias", 7.0) * 86400_000L);
    }

    public long upkeepCost(Guild g) {
        List<Long> l = plugin.getConfig().getLongList("mantenimiento.costo-por-nivel");
        int lvl = Math.max(1, g.territoryLevel);
        if (l.size() >= lvl) return l.get(lvl - 1);
        return lvl == 1 ? 8 : lvl == 2 ? 24 : 64;
    }

    // ------------------------------------------------------------------ persistencia

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        ConfigurationSection root = y.createSection("guilds");
        for (Guild g : guilds.values()) {
            ConfigurationSection s = root.createSection(g.id);
            s.set("name", g.name);
            s.set("leader", g.leader.toString());
            s.set("level", g.level);
            s.set("xp", g.xp);
            s.set("bank", g.bank);
            s.set("created", g.createdAt);
            s.set("nextUpkeep", g.nextUpkeep);
            s.set("missed", g.upkeepMissed);
            s.set("dues", g.dues);
            s.set("upGuard", g.upGuard);
            s.set("upCore", g.upCore);
            s.set("upCapacity", g.upCapacity);
            s.set("protectedUntil", g.protectedUntil);
            s.set("attackCooldownUntil", g.attackCooldownUntil);
            s.set("territory", g.territoryId);
            s.set("territoryLevel", g.territoryLevel);
            ConfigurationSection m = s.createSection("members");
            for (Map.Entry<UUID, Guild.MemberData> e : g.members.entrySet()) {
                ConfigurationSection ms = m.createSection(e.getKey().toString());
                ms.set("name", e.getValue().name);
                ms.set("rank", e.getValue().rank.name());
                ms.set("joined", e.getValue().joinedAt);
                ms.set("dues", e.getValue().duesPending);
            }
            ConfigurationSection rq = s.createSection("requests");
            for (Map.Entry<UUID, String> e : g.requests.entrySet()) rq.set(e.getKey().toString(), e.getValue());
            s.set("allies", new ArrayList<>(g.allies));
            s.set("allyRequests", new ArrayList<>(g.allyRequestsIn));
            s.set("log", new ArrayList<>(g.bankLog));
        }
        try {
            y.save(guildsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar guilds.yml: " + e.getMessage());
        }
    }

    private void savePlayers() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> c = new ArrayList<>();
        for (UUID u : creators) c.add(u.toString());
        y.set("creators", c);
        ConfigurationSection d = y.createSection("daily");
        for (Map.Entry<UUID, long[]> e : daily.entrySet()) {
            ConfigurationSection s = d.createSection(e.getKey().toString());
            s.set("day", e.getValue()[0]);
            s.set("xp", e.getValue()[1]);
            s.set("rep", e.getValue()[2]);
        }
        try {
            y.save(playersFile);
        } catch (IOException e) {
            plugin.getLogger().warning("No se pudo guardar jugadores.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (guildsFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(guildsFile);
            ConfigurationSection root = y.getConfigurationSection("guilds");
            if (root != null) {
                for (String id : root.getKeys(false)) {
                    ConfigurationSection s = root.getConfigurationSection(id);
                    if (s == null) continue;
                    try {
                        Guild g = new Guild(id, s.getString("name", id), UUID.fromString(s.getString("leader")));
                        g.level = s.getInt("level", 1);
                        g.xp = s.getLong("xp");
                        g.bank = s.getLong("bank");
                        g.createdAt = s.getLong("created", System.currentTimeMillis());
                        g.nextUpkeep = s.getLong("nextUpkeep");
                        g.upkeepMissed = s.getInt("missed");
                        g.dues = s.getLong("dues");
                        g.upGuard = s.getInt("upGuard");
                        g.upCore = s.getInt("upCore");
                        g.upCapacity = s.getInt("upCapacity");
                        g.protectedUntil = s.getLong("protectedUntil");
                        g.attackCooldownUntil = s.getLong("attackCooldownUntil");
                        g.territoryId = s.getString("territory");
                        g.territoryLevel = s.getInt("territoryLevel");
                        ConfigurationSection m = s.getConfigurationSection("members");
                        if (m != null) {
                            for (String us : m.getKeys(false)) {
                                ConfigurationSection ms = m.getConfigurationSection(us);
                                if (ms == null) continue;
                                UUID u = UUID.fromString(us);
                                Guild.MemberData md = new Guild.MemberData(ms.getString("name", "?"), Rank.parse(ms.getString("rank"), Rank.RECLUTA));
                                md.joinedAt = ms.getLong("joined");
                                md.duesPending = ms.getLong("dues");
                                g.members.put(u, md);
                                playerGuild.put(u, g.id);
                            }
                        }
                        ConfigurationSection rq = s.getConfigurationSection("requests");
                        if (rq != null) for (String us : rq.getKeys(false)) g.requests.put(UUID.fromString(us), rq.getString(us));
                        g.allies.addAll(s.getStringList("allies"));
                        g.allyRequestsIn.addAll(s.getStringList("allyRequests"));
                        for (String l : s.getStringList("log")) g.bankLog.addLast(l);
                        guilds.put(id, g);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Gremio '" + id + "' dañado en guilds.yml: " + ex.getMessage());
                    }
                }
            }
        }
        if (playersFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(playersFile);
            for (String u : y.getStringList("creators")) {
                try {
                    creators.add(UUID.fromString(u));
                } catch (IllegalArgumentException ignored) {
                }
            }
            ConfigurationSection d = y.getConfigurationSection("daily");
            if (d != null) {
                for (String u : d.getKeys(false)) {
                    try {
                        ConfigurationSection s = d.getConfigurationSection(u);
                        if (s == null) continue;
                        daily.put(UUID.fromString(u), new long[]{s.getLong("day"), s.getLong("xp"), s.getLong("rep")});
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }
    }

    public void saveAll() {
        save();
        savePlayers();
    }
}
