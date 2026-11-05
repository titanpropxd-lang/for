package com.servidor.forja;

import org.bukkit.NamespacedKey;

/** Claves PDC usadas en los items (namespace = "forja"). */
public final class Keys {
    public static NamespacedKey ITEM_ID, QUALITY, PLUS, FORGER, FORGER_UUID, SIG, EXTRA,
            BUFF_TYPE, BUFF_VAL, BUFF_USES, ARROW,
            RPG_NAME, RPG_MAXDMG, RPG_LORE_N, RPG_BASE_DMG, RPG_BASE_ARMOR, RPG_BASE_TOUGH;

    private Keys() {}

    public static void init(ForjaPlugin p) {
        ITEM_ID = new NamespacedKey(p, "rz_item");
        QUALITY = new NamespacedKey(p, "rz_quality");
        PLUS = new NamespacedKey(p, "rz_plus");
        FORGER = new NamespacedKey(p, "rz_forger");
        FORGER_UUID = new NamespacedKey(p, "rz_forger_uuid");
        SIG = new NamespacedKey(p, "rz_sig");
        EXTRA = new NamespacedKey(p, "rz_extra");
        BUFF_TYPE = new NamespacedKey(p, "rz_buff_type");
        BUFF_VAL = new NamespacedKey(p, "rz_buff_val");
        BUFF_USES = new NamespacedKey(p, "rz_buff_uses");
        ARROW = new NamespacedKey(p, "rz_arrow");
        RPG_NAME = new NamespacedKey(p, "rz_rpg_name");
        RPG_MAXDMG = new NamespacedKey(p, "rz_rpg_maxdmg");
        RPG_LORE_N = new NamespacedKey(p, "rz_rpg_lore_n");
        RPG_BASE_DMG = new NamespacedKey(p, "rz_rpg_base_dmg");
        RPG_BASE_ARMOR = new NamespacedKey(p, "rz_rpg_base_armor");
        RPG_BASE_TOUGH = new NamespacedKey(p, "rz_rpg_base_tough");
    }
}
