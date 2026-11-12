package com.servidor.forja;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

/** Manual interactivo del sistema: oficios, recetas, materiales, metales y armas raciales. */
public final class GrimorioMenu implements InventoryHolder {
    private enum Page { HOME, PROFESSIONS, RECIPES, MATERIALS, METALS, RACIAL }
    private final ForjaPlugin plugin;
    private final Player player;
    private final Inventory inventory;
    private Page page=Page.HOME;
    private Profession profession;
    private int offset=0;
    private Recipe selected;
    private boolean detailView=false;

    private GrimorioMenu(ForjaPlugin plugin,Player player) {
        this.plugin=plugin; this.player=player;
        this.inventory=Bukkit.createInventory(this,54,Lang.mm("<dark_gray>✦ <gold>Grimorio de Oficios <dark_gray>✦"));
    }
    public static void open(ForjaPlugin plugin,Player player) { GrimorioMenu menu=new GrimorioMenu(plugin,player); menu.render(); player.openInventory(menu.inventory); }
    @Override public Inventory getInventory(){return inventory;}

    private ItemStack icon(Material material,String name,String... lore) { return Gui.icon(material,name, lore); }
    private ItemStack iconOf(CustomItem ci,Material fallback,String name,String... lore) { return Gui.iconOf(ci,fallback,name,lore); }
    private void frame() { inventory.clear(); for(int i=0;i<54;i++) inventory.setItem(i,Gui.pane(Material.BLACK_STAINED_GLASS_PANE)); inventory.setItem(45,icon(Material.ARROW,"<yellow>Volver")); inventory.setItem(49,icon(Material.BOOK,"<gold>Grimorio de Forja","<gray>Guía de consulta del sistema")); inventory.setItem(53,icon(Material.BARRIER,"<red>Cerrar")); }
    private void render() {
        frame();
        switch(page) {
            case HOME -> {
                inventory.setItem(20,icon(Material.ANVIL,"<gold>Oficios","<gray>Progresión, niveles y estaciones"));
                inventory.setItem(22,icon(Material.WRITABLE_BOOK,"<aqua>Recetas","<gray>Consulta requisitos y materiales"));
                inventory.setItem(24,icon(Material.BUNDLE,"<green>Materiales y componentes","<gray>Pieles, piezas, gemas y recursos"));
                inventory.setItem(30,icon(Material.IRON_INGOT,"<gray>Metales y aleaciones","<gray>Fundición, moldes y enfriamiento"));
                inventory.setItem(32,icon(Material.DIAMOND_SWORD,"<light_purple>Armas raciales","<gray>Equipo restringido por raza y linaje"));
                inventory.setItem(4,icon(Material.ENCHANTED_BOOK,"<gold>Manual de Forja","<gray>Transforma materias primas en equipo.","<gray>Los procesos y requisitos se consultan aquí."));
            }
            case PROFESSIONS -> renderProfessions();
            case RECIPES -> renderRecipes();
            case MATERIALS -> renderMaterials();
            case METALS -> renderMetals();
            case RACIAL -> renderRacial();
        }
    }
    private void renderProfessions() {
        int slot=10;
        for(Profession p:Profession.values()) {
            PlayerData data=plugin.professions.get(player);
            inventory.setItem(slot,icon(p.icon,p.tag+p.display,"<gray>Nivel: <white>"+data.level(p),"<gray>Experiencia: <white>"+data.xp(p),"<yellow>Clic para ver recetas"));
            slot++; if(slot==17)slot=19;
        }
        inventory.setItem(4,icon(Material.BOOK,"<gold>Oficios","<gray>Tu progreso y las especialidades disponibles"));
    }
    private void renderRecipes() {
        List<Recipe> recipes=plugin.recipes.all().stream().filter(r->profession==null||r.prof==profession)
                .sorted(Comparator.comparing((Recipe r)->r.estacion).thenComparingInt(r->r.nivel).thenComparing(r->r.id)).collect(Collectors.toList());
        int start=offset*28, end=Math.min(recipes.size(),start+28),slot=10;
        for(int i=start;i<end;i++) {
            Recipe r=recipes.get(i); CustomItem result=plugin.items.get(r.resultado);
            boolean unlocked=plugin.isAdminMode(player) || plugin.professions.unlocked(player,r);
            inventory.setItem(slot++,iconOf(result,Material.PAPER,(unlocked?"<green>":"<red>")+r.id,
                    "<gray>Oficio: <white>"+r.oficio,"<gray>Estación: <white>"+r.estacion,"<gray>Nivel: <white>"+r.nivel,
                    unlocked?"<green>Disponible":"<red>Requisitos pendientes","<yellow>Clic para detalles"));
            if(slot==17)slot=19; if(slot==26)slot=28; if(slot==35)slot=37;
        }
        inventory.setItem(4,icon(Material.WRITABLE_BOOK,"<gold>Recetas · "+(profession==null?"Todas":profession.display),"<gray>Página "+(offset+1)+" / "+Math.max(1,(recipes.size()+27)/28)));
        if(offset>0)inventory.setItem(48,icon(Material.ARROW,"<yellow>Página anterior"));
        if(end<recipes.size())inventory.setItem(50,icon(Material.ARROW,"<yellow>Página siguiente"));
    }
    private void renderMaterials() {
        List<CustomItem> items=plugin.items.all().stream().filter(i->!i.isRpg() && !i.categoria.equals("estacion") && !i.isEquipment())
                .sorted(Comparator.comparing(i->i.nombre)).collect(Collectors.toList());
        int start=offset*28,end=Math.min(items.size(),start+28),slot=10;
        for(int i=start;i<end;i++) { CustomItem ci=items.get(i); inventory.setItem(slot++,iconOf(ci,Material.PAPER,"<white>"+ci.nombre,"<gray>ID: <white>"+ci.id,"<gray>Tipo: <white>"+ci.categoria,"<gray>Rareza: <white>"+ItemRegistry.rarityName(ci.rareza))); if(slot==17)slot=19;if(slot==26)slot=28;if(slot==35)slot=37; }
        inventory.setItem(4,icon(Material.BUNDLE,"<green>Materiales y componentes","<gray>Incluye materias primas, pieles y piezas"));
        if(offset>0)inventory.setItem(48,icon(Material.ARROW,"<yellow>Página anterior")); if(end<items.size())inventory.setItem(50,icon(Material.ARROW,"<yellow>Página siguiente"));
    }
    private void renderMetals() {
        String[] ids={"cobre","bronce","hierro_forjado","acero","plata","acero_negro","mithril","adamantita"};
        int[] slots={10,11,12,13,14,15,16,22};
        for(int i=0;i<ids.length;i++) {
            String id=ids[i]; CustomItem ingot=plugin.items.get("lingote_"+id), molten=plugin.items.get("metal_fundido_"+id);
            inventory.setItem(slots[i],iconOf(ingot,Material.IRON_INGOT,"<gold>"+id.replace('_',' '),
                    "<gray>Lingote: <white>"+(ingot==null?"No catalogado":ingot.nombre),
                    "<gray>Metal fundido: <white>"+(molten==null?"No catalogado":molten.nombre),
                    "<gray>Flujo: calentar → verter en molde → enfriar → obtener pieza"));
        }
        inventory.setItem(31,icon(Material.CAULDRON,"<aqua>Proceso de fundición","<gray>1. Obtén el metal y caliéntalo en el horno.","<gray>2. Sostén el metal fundido en la mano principal.","<gray>3. Sostén el molde en la secundaria.","<gray>4. Vierte y espera el enfriamiento.","<gray>5. Usa la pieza resultante en la estación indicada."));
    }
    private void renderRacial() {
        List<CustomItem> weapons=plugin.items.all().stream().filter(i->i.isRpg() && (i.categoria.contains("arma")||i.categoria.contains("armadura"))).sorted(Comparator.comparing(i->i.nombre)).collect(Collectors.toList());
        int start=offset*28,end=Math.min(weapons.size(),start+28),slot=10;
        for(int i=start;i<end;i++) { CustomItem ci=weapons.get(i); inventory.setItem(slot++,iconOf(ci,Material.PAPER,"<light_purple>"+ci.nombre,"<gray>Raza: <white>"+String.join(", ",ci.raza),"<gray>Linaje requerido: <white>"+ci.linaje,"<gray>Nivel: <white>"+ci.nivelReq)); if(slot==17)slot=19;if(slot==26)slot=28;if(slot==35)slot=37; }
        inventory.setItem(4,icon(Material.DIAMOND_SWORD,"<light_purple>Equipo racial","<gray>Las restricciones se mantienen según el sistema RPG."));
        if(offset>0)inventory.setItem(48,icon(Material.ARROW,"<yellow>Página anterior")); if(end<weapons.size())inventory.setItem(50,icon(Material.ARROW,"<yellow>Página siguiente"));
    }
    public void click(InventoryClickEvent e) {
        if(e.getWhoClicked()!=player)return; e.setCancelled(true); int s=e.getRawSlot(); if(s<0||s>=54)return;
        if(s==53){player.closeInventory();return;}
        if(s==45){ if(detailView){detailView=false;render();} else {page=Page.HOME;profession=null;offset=0;render();} return;}
        if(s==48&&offset>0){offset--;render();return;} if(s==50){offset++;render();return;}
        if(page==Page.HOME) {
            if(s==20){page=Page.PROFESSIONS;} else if(s==22){page=Page.RECIPES;profession=null;offset=0;} else if(s==24){page=Page.MATERIALS;offset=0;} else if(s==30){page=Page.METALS;} else if(s==32){page=Page.RACIAL;offset=0;} else return;
            render();return;
        }
        if(page==Page.PROFESSIONS) {
            int index=-1; if(s>=10&&s<=16)index=s-10; else if(s>=19&&s<=25)index=s-19+7;
            if(index>=0&&index<Profession.values().length){profession=Profession.values()[index];page=Page.RECIPES;offset=0;render();} return;
        }
        if(page==Page.RECIPES) {
            List<Recipe> list=plugin.recipes.all().stream().filter(r->profession==null||r.prof==profession).sorted(Comparator.comparing((Recipe r)->r.estacion).thenComparingInt(r->r.nivel).thenComparing(r->r.id)).collect(Collectors.toList());
            int index=slotIndex(s); if(index>=0&&offset*28+index<list.size()){Recipe r=list.get(offset*28+index); showRecipe(r);} return;
        }
    }
    private int slotIndex(int slot) { if(slot>=10&&slot<=16)return slot-10;if(slot>=19&&slot<=25)return slot-12;if(slot>=28&&slot<=34)return slot-14;if(slot>=37&&slot<=43)return slot-16;return -1; }
    private void showRecipe(Recipe r) {
        detailView=true; inventory.clear(); inventory.setItem(45,icon(Material.ARROW,"<yellow>Volver a recetas"));
        CustomItem out=plugin.items.get(r.resultado);
        inventory.setItem(22,iconOf(out,Material.PAPER,"<gold>"+r.id,"<gray>Resultado: <white>"+r.resultado+" x"+r.cantidad,"<gray>Oficio: <white>"+r.oficio,"<gray>Estación: <white>"+r.estacion,"<gray>Nivel: <white>"+r.nivel,"<gray>Tiempo: <white>"+r.tiempo+" s","<gray>Experiencia: <white>"+r.xp));
        int slot=10; for(Map.Entry<String,Integer> ing:r.ing.entrySet()) { if(slot>16)slot=19; inventory.setItem(slot++,iconOf(plugin.items.get(ing.getKey()),Material.PAPER,"<white>"+ing.getKey(),"<gray>Cantidad: <white>"+ing.getValue())); }
        boolean ok=plugin.isAdminMode(player) || plugin.professions.unlocked(player,r); inventory.setItem(31,icon(ok?Material.LIME_DYE:Material.BARRIER,ok?"<green>Requisitos cumplidos":"<red>Receta bloqueada","<gray>El grimorio informa de los requisitos; no concede permisos ni desbloqueos."));
    }
}
