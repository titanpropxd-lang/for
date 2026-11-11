package com.servidor.forja;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Locale;

/** Efectos exclusivamente visuales: no modifica daño, alcance, cooldown ni atributos de las armas. */
public final class ForjaVisualEffects implements Listener {
    private final ForjaPlugin plugin;
    private final BetterModelBridge betterModel;
    private final java.util.Map<String,Long> stationCooldown = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<java.util.UUID,Long> weaponModelCooldown = new java.util.concurrent.ConcurrentHashMap<>();

    public ForjaVisualEffects(ForjaPlugin plugin) {
        this.plugin = plugin;
        this.betterModel = new BetterModelBridge(plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWeaponUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        CustomItem weapon = plugin.items.of(item);
        if (weapon == null || !weapon.isWeapon()) return;
        show(weapon, event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWeaponHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        CustomItem weapon = plugin.items.of(player.getInventory().getItemInMainHand());
        if (weapon == null || !weapon.isWeapon()) return;
        show(weapon, player, true);
    }

    private void show(CustomItem weapon, Player player, boolean impact) {
        String id = (weapon.id + " " + weapon.nombre + " " + String.join(" ", weapon.lore)).toLowerCase(Locale.ROOT);
        String family = family(id);
        Location center = impact ? player.getLocation().add(player.getLocation().getDirection().multiply(0.8)).add(0, 0.8, 0)
                : player.getLocation().add(0, 0.15, 0);
        switch (family) {
            case "fuego" -> fire(center, impact);
            case "hielo" -> ice(center, impact);
            case "tierra" -> earth(center, impact);
            case "sombra" -> shadow(center, impact);
            case "arcano" -> arcane(center, impact);
            case "viento" -> wind(center, impact);
            default -> steel(center, impact);
        }
        // BetterModel es opcional. Si el modelo está instalado y cargado se muestra como prop 3D;
        // las partículas siguen funcionando como fallback y no cambian el combate.
        long now=System.currentTimeMillis();
        Long previous=weaponModelCooldown.get(player.getUniqueId());
        if(previous==null || now-previous>350L) {
            weaponModelCooldown.put(player.getUniqueId(),now);
            betterModel.spawn("forja_fx_" + (weapon.isRpg() ? weapon.id : family), center, 28);
        }
    }


    /** Invocado por estaciones y procesos físicos; mantiene intactas las mecánicas. */
    public void stationEffect(Location location, String family) {
        if (location == null || location.getWorld() == null) return;
        String key = location.getWorld().getUID()+":"+location.getBlockX()+":"+location.getBlockY()+":"+location.getBlockZ()+":"+family;
        long now = System.currentTimeMillis();
        Long previous = stationCooldown.get(key);
        if (previous == null || now - previous > 1100L) {
            stationCooldown.put(key, now);
            betterModel.spawn("forja_fx_" + family, location, 24);
        }
        switch (family) {
            case "fuego" -> location.getWorld().spawnParticle(Particle.FLAME, location, 8, .3, .2, .3, .02);
            case "hielo" -> location.getWorld().spawnParticle(Particle.SNOWFLAKE, location, 8, .3, .2, .3, .02);
            case "tierra" -> location.getWorld().spawnParticle(Particle.BLOCK, location, 8, .3, .2, .3, .08, Material.STONE.createBlockData());
            case "arcano" -> location.getWorld().spawnParticle(Particle.ENCHANT, location, 10, .3, .2, .3, .3);
            default -> location.getWorld().spawnParticle(Particle.CLOUD, location, 6, .3, .2, .3, .01);
        }
    }

    private String family(String id) {
        if (has(id,"fuego","fire","llama","brasas","dragon","dracon")) return "fuego";
        if (has(id,"hielo","ice","escarcha","glacial","cristalino")) return "hielo";
        if (has(id,"tierra","roca","earth","terremoto","piedra","montaña")) return "tierra";
        if (has(id,"sombra","oscuro","dark","vampiro","sangre","abismo")) return "sombra";
        if (has(id,"viento","aire","wind","tormenta","rayo","trueno","storm")) return "viento";
        if (has(id,"arcano","amethyst","amatista","magia","runa","elfo","triton")) return "arcano";
        return "acero";
    }
    private boolean has(String s, String... terms) { for (String t:terms) if (s.contains(t)) return true; return false; }

    private void fire(Location l, boolean hit) {
        World w=l.getWorld(); if(w==null)return;
        w.spawnParticle(Particle.FLAME,l,hit?28:16,.35,.25,.35,.04);
        w.spawnParticle(Particle.LAVA,l,hit?5:2,.25,.15,.25,0);
        ring(l, 1.25, Particle.FLAME, 36);
        w.playSound(l, hit?Sound.ENTITY_BLAZE_HURT:Sound.ITEM_FIRECHARGE_USE,.55f,hit?1.25f:.9f);
    }
    private void ice(Location l, boolean hit) {
        World w=l.getWorld(); if(w==null)return;
        w.spawnParticle(Particle.SNOWFLAKE,l,hit?24:14,.45,.3,.45,.02);
        w.spawnParticle(Particle.END_ROD,l,hit?12:6,.3,.25,.3,.015);
        ring(l,1.15,Particle.SNOWFLAKE,32);
        w.playSound(l,Sound.BLOCK_GLASS_BREAK,.5f,1.65f);
    }
    private void earth(Location l, boolean hit) {
        World w=l.getWorld(); if(w==null)return;
        BlockData stone=Material.DEEPSLATE.createBlockData();
        w.spawnParticle(Particle.BLOCK,l,hit?38:22,.55,.25,.55,.18,stone);
        w.spawnParticle(Particle.CLOUD,l,hit?16:8,.4,.1,.4,.04);
        // Fragmentos simulados con partículas BLOCK: nunca cambia bloques del mundo.
        for(int i=0;i<8;i++) { double a=Math.PI*2*i/8; Location p=l.clone().add(Math.cos(a)*.8,.25,Math.sin(a)*.8); w.spawnParticle(Particle.BLOCK,p,5,.08,.12,.08,.12,Material.COBBLESTONE.createBlockData()); }
        liftFragments(l, hit ? 7 : 4);
        w.playSound(l,Sound.BLOCK_STONE_BREAK,.7f,.75f);
    }
    private void shadow(Location l, boolean hit) {
        World w=l.getWorld(); if(w==null)return;
        w.spawnParticle(Particle.DRAGON_BREATH,l,hit?24:14,.4,.3,.4,.025);
        w.spawnParticle(Particle.SOUL,l,hit?16:8,.35,.25,.35,.03);
        ring(l,1.1,Particle.SOUL,32);
        w.playSound(l,Sound.ENTITY_ENDERMAN_TELEPORT,.45f,.65f);
    }
    private void arcane(Location l, boolean hit) {
        World w=l.getWorld(); if(w==null)return;
        w.spawnParticle(Particle.ENCHANT,l,hit?40:24,.5,.4,.5,.8);
        w.spawnParticle(Particle.WITCH,l,hit?14:8,.3,.25,.3,.02);
        ring(l,1.35,Particle.END_ROD,40);
        w.playSound(l,Sound.BLOCK_AMETHYST_BLOCK_CHIME,.55f,1.5f);
    }
    private void wind(Location l, boolean hit) {
        World w=l.getWorld(); if(w==null)return;
        w.spawnParticle(Particle.CLOUD,l,hit?22:12,.45,.25,.45,.12);
        w.spawnParticle(Particle.CRIT,l,hit?22:12,.35,.25,.35,.15);
        ring(l,1.0,Particle.SWEEP_ATTACK,24);
        w.playSound(l,Sound.ENTITY_PHANTOM_FLAP,.45f,1.5f);
    }
    private void steel(Location l, boolean hit) {
        World w=l.getWorld(); if(w==null)return;
        w.spawnParticle(Particle.CRIT,l,hit?20:10,.3,.25,.3,.12);
        w.spawnParticle(Particle.ELECTRIC_SPARK,l,hit?10:5,.25,.2,.25,.02);
        w.playSound(l,Sound.ITEM_TRIDENT_HIT,.35f,1.7f);
    }

    /** Fragmentos BlockDisplay temporales que se elevan; nunca reemplazan ni rompen bloques reales. */
    private void liftFragments(Location center, int amount) {
        World world=center.getWorld(); if(world==null)return;
        for(int i=0;i<amount;i++) {
            final int index=i;
            double angle=(Math.PI*2*index)/amount;
            Location origin=center.clone().add(Math.cos(angle)*(.45+(index%2)*.22),-.15,Math.sin(angle)*(.45+(index%2)*.22));
            BlockDisplay display=world.spawn(origin,BlockDisplay.class,d->{
                d.setBlock((index%2==0?Material.COBBLESTONE:Material.DEEPSLATE).createBlockData());
                d.setInvulnerable(true); d.setPersistent(false); d.setGravity(false);
                float scale=.22f+(index%3)*.07f;
                d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(-scale/2,-scale/2,-scale/2),new org.joml.Quaternionf(),new org.joml.Vector3f(scale,scale,scale),new org.joml.Quaternionf()));
            });
            final int[] age={0};
            org.bukkit.scheduler.BukkitTask task=Bukkit.getScheduler().runTaskTimer(plugin,()->{
                age[0]++;
                if(!display.isValid()||age[0]>=12){display.remove();return;}
                display.teleport(origin.clone().add(0,age[0]*.075,0));
            },1L,1L);
            Bukkit.getScheduler().runTaskLater(plugin,task::cancel,13L);
        }
    }

    private void ring(Location c,double radius,Particle particle,int points) {
        World w=c.getWorld(); if(w==null)return;
        for(int i=0;i<points;i++) { double a=Math.PI*2*i/points; w.spawnParticle(particle,c.clone().add(Math.cos(a)*radius,.06,Math.sin(a)*radius),1,0,0,0,0); }
    }
}
