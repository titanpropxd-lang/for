package com.servidor.forja;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Niveles/XP de oficios, banderas de recetas y estadísticas. Cache en memoria + SQLite asíncrono. */
public final class ProfessionManager {
    private final ForjaPlugin plugin;
    private final Map<UUID, PlayerData> data = new ConcurrentHashMap<>();
    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    public ProfessionManager(ForjaPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean ready(Player p) {
        return loaded.contains(p.getUniqueId());
    }

    public PlayerData get(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), PlayerData::new);
    }

    public PlayerData get(UUID id) {
        return data.computeIfAbsent(id, PlayerData::new);
    }

    public void load(Player p) {
        final UUID id = p.getUniqueId();
        plugin.db.query(c -> {
            PlayerData d = new PlayerData(id);
            try (PreparedStatement ps = c.prepareStatement("SELECT prof, level, xp FROM prof WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Profession pr = Profession.fromId(rs.getString(1));
                        if (pr != null) d.prof.put(pr, new int[]{rs.getInt(2), rs.getInt(3)});
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT recipe, flag FROM flags WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) d.flags.put(rs.getString(1), rs.getString(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT item, n FROM stats WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) d.stats.put(rs.getString(1), rs.getInt(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT data FROM acc WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) decodeAcc(rs.getString(1), d.accessories);
                }
            }
            return d;
        }).whenComplete((d, ex) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (ex != null) {
                plugin.getLogger().log(Level.SEVERE, "No se pudieron cargar los datos de " + id, ex);
                return;
            }
            data.put(id, d);
            loaded.add(id);
            Player online = Bukkit.getPlayer(id);
            if (online != null) plugin.accessories.apply(online);
        }));
    }

    public void unload(UUID id) {
        data.remove(id);
        loaded.remove(id);
    }

    // ---------------- XP / niveles ----------------

    public int maxLevel() {
        return plugin.getConfig().getInt("oficios.nivel_max", 50);
    }

    public int xpNeeded(int level) {
        return level * plugin.getConfig().getInt("oficios.xp_a", 25) + plugin.getConfig().getInt("oficios.xp_b", 50);
    }

    public void addXp(Player p, Profession pr, int amount) {
        if (amount <= 0) return;
        PlayerData d = get(p);
        int[] a = d.prof.computeIfAbsent(pr, k -> new int[]{1, 0});
        if (a[0] >= maxLevel()) return;
        a[1] += amount;
        int before = a[0];
        while (a[0] < maxLevel() && a[1] >= xpNeeded(a[0])) {
            a[1] -= xpNeeded(a[0]);
            a[0]++;
        }
        if (a[0] >= maxLevel()) a[1] = 0;
        saveProf(p.getUniqueId(), pr, a[0], a[1]);
        if (a[0] > before) levelUp(p, pr, before, a[0]);
        else p.sendActionBar(Lang.mm(pr.tag + pr.display + " <gray>+" + amount + " XP <dark_gray>(" + a[1] + "/" + xpNeeded(a[0]) + ")"));
    }

    private void levelUp(Player p, Profession pr, int from, int to) {
        List<String> nuevas = new ArrayList<>();
        for (Recipe r : plugin.recipes.all()) {
            if (r.prof == pr && r.nivel > from && r.nivel <= to) {
                CustomItem ci = plugin.items.get(r.resultado);
                nuevas.add(ci == null ? r.id : ci.nombre);
            }
        }
        Title t = Title.title(
                plugin.lang.get("oficio.subida_titulo", "oficio", pr.display, "nivel", to, "color", pr.tag),
                plugin.lang.get("oficio.subida_sub", "n", nuevas.size()),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600)));
        p.showTitle(t);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
        if (!nuevas.isEmpty()) {
            String lista = String.join(", ", nuevas.subList(0, Math.min(6, nuevas.size())));
            if (nuevas.size() > 6) lista += "…";
            plugin.lang.send(p, "oficio.desbloqueo", "lista", lista);
        }
        int[] a = get(p).prof.get(pr);
        float prog = to >= maxLevel() ? 1f : Math.min(1f, a[1] / (float) xpNeeded(to));
        final BossBar bar = BossBar.bossBar(
                plugin.lang.get("oficio.barra", "oficio", pr.display, "nivel", to),
                prog, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        p.showBossBar(bar);
        Bukkit.getScheduler().runTaskLater(plugin, () -> p.hideBossBar(bar), 100L);
    }

    public void setLevel(UUID id, Profession pr, int level) {
        PlayerData d = get(id);
        int lv = Math.max(1, Math.min(maxLevel(), level));
        d.prof.put(pr, new int[]{lv, 0});
        saveProf(id, pr, lv, 0);
    }

    private void saveProf(UUID id, Profession pr, int level, int xp) {
        plugin.db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO prof(uuid,prof,level,xp) VALUES(?,?,?,?) ON CONFLICT(uuid,prof) DO UPDATE SET level=excluded.level, xp=excluded.xp")) {
                ps.setString(1, id.toString());
                ps.setString(2, pr.id);
                ps.setInt(3, level);
                ps.setInt(4, xp);
                ps.executeUpdate();
            }
        });
    }

    // ---------------- recetas (banderas admin) ----------------

    /** flag "G" = concedida aunque falte nivel, "R" = revocada. */
    public void setFlag(UUID id, String recipe, String flag) {
        PlayerData d = get(id);
        if (flag == null) d.flags.remove(recipe);
        else d.flags.put(recipe, flag);
        plugin.db.run(c -> {
            if (flag == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM flags WHERE uuid=? AND recipe=?")) {
                    ps.setString(1, id.toString());
                    ps.setString(2, recipe);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO flags(uuid,recipe,flag) VALUES(?,?,?) ON CONFLICT(uuid,recipe) DO UPDATE SET flag=excluded.flag")) {
                    ps.setString(1, id.toString());
                    ps.setString(2, recipe);
                    ps.setString(3, flag);
                    ps.executeUpdate();
                }
            }
        });
    }

    public boolean unlocked(Player p, Recipe r) {
        PlayerData d = get(p);
        String f = d.flags.get(r.id);
        if ("R".equals(f)) return false;
        if ("G".equals(f)) return true;
        return d.level(r.prof) >= r.nivel;
    }

    // ---------------- estadísticas ----------------

    public void incStat(UUID id, String item, int n) {
        PlayerData d = get(id);
        int nv = d.stats.merge(item, n, Integer::sum);
        plugin.db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO stats(uuid,item,n) VALUES(?,?,?) ON CONFLICT(uuid,item) DO UPDATE SET n=excluded.n")) {
                ps.setString(1, id.toString());
                ps.setString(2, item);
                ps.setInt(3, nv);
                ps.executeUpdate();
            }
        });
    }

    // ---------------- accesorios ----------------

    public void saveAccessories(UUID id) {
        final String enc = encodeAcc(get(id).accessories);
        plugin.db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO acc(uuid,data) VALUES(?,?) ON CONFLICT(uuid) DO UPDATE SET data=excluded.data")) {
                ps.setString(1, id.toString());
                ps.setString(2, enc);
                ps.executeUpdate();
            }
        });
    }

    private static String encodeAcc(ItemStack[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(';');
            if (a[i] == null || a[i].getType().isAir()) sb.append('-');
            else sb.append(Base64.getEncoder().encodeToString(a[i].serializeAsBytes()));
        }
        return sb.toString();
    }

    private static void decodeAcc(String s, ItemStack[] out) {
        String[] parts = s.split(";", -1);
        for (int i = 0; i < out.length && i < parts.length; i++) {
            if (parts[i].equals("-") || parts[i].isEmpty()) continue;
            try {
                out[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(parts[i]));
            } catch (Exception ignored) {
                out[i] = null;
            }
        }
    }
}
