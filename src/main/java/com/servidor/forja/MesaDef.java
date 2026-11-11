package com.servidor.forja;

/** Definición de un bloque-mesa: qué estación de recetas abre y cómo abrir su menú vanilla ("" si no tiene). */
public record MesaDef(String block, String tipo, String estacion, String vanilla) {
    public boolean crafting() {
        return "mesa".equals(tipo);
    }
}
