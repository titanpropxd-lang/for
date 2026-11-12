package com.servidor.forja;

import org.bukkit.inventory.ItemStack;

import java.util.Base64;

/** Serializa listas de items a texto (base64 por item, separados por ';'; '-' = hueco vacío). */
public final class ItemCodec {
    private ItemCodec() {}

    public static String encode(ItemStack[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(';');
            if (a[i] == null || a[i].getType().isAir()) sb.append('-');
            else sb.append(Base64.getEncoder().encodeToString(a[i].serializeAsBytes()));
        }
        return sb.toString();
    }

    public static ItemStack[] decode(String s, int size) {
        ItemStack[] out = new ItemStack[size];
        if (s == null || s.isEmpty()) return out;
        String[] parts = s.split(";", -1);
        for (int i = 0; i < size && i < parts.length; i++) {
            if (parts[i].equals("-") || parts[i].isEmpty()) continue;
            try {
                out[i] = ItemStack.deserializeBytes(Base64.getDecoder().decode(parts[i]));
            } catch (Exception ignored) {
                out[i] = null;
            }
        }
        return out;
    }
}
