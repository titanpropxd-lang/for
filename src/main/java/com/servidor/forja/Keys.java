package com.servidor.forja;

import org.bukkit.NamespacedKey;

/** Claves PDC usadas en los items (namespace = "forja"). */
public final class Keys {
    public static NamespacedKey ITEM_ID, QUALITY, PLUS, FORGER, FORGER_UUID, SIG, EXTRA,
            BUFF_TYPE, BUFF_VAL, BUFF_USES, ARROW;

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
    }
}
