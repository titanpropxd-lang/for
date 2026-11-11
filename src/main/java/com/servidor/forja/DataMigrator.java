package com.servidor.forja;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Mantiene plugins/Forja/ al día sin depender de Bukkit:
 * - copia los archivos de datos que falten (según data_files.txt del jar);
 * - si el jar trae datos nuevos (data_version.txt distinto), hace una copia de seguridad (backup-fecha/) y los sustituye;
 * - aparta los archivos items/ y recipes/ de versiones antiguas que ya no existen; los que empiezan por "custom_" no se tocan.
 */
public final class DataMigrator {
    public interface Source {
        /** Contenido del recurso del jar o null si no existe. */
        byte[] read(String path) throws IOException;
    }

    private final File folder;
    private final Logger log;
    private final Source src;

    public DataMigrator(File folder, Logger log, Source src) {
        this.folder = folder;
        this.log = log;
        this.src = src;
    }

    public void run(boolean overwriteAllowed) {
        try {
            Path base = folder.toPath();
            Files.createDirectories(base);
            byte[] idx = src.read("data_files.txt");
            if (idx == null) {
                log.warning("El jar no trae data_files.txt: no se pueden copiar los datos.");
                return;
            }
            List<String> files = new ArrayList<>();
            for (String l : new String(idx, StandardCharsets.UTF_8).split("\\R")) if (!l.isBlank()) files.add(l.trim());
            byte[] vb = src.read("data_version.txt");
            String jarVer = vb == null ? null : new String(vb, StandardCharsets.UTF_8).trim();
            Path verFile = base.resolve("data_version.txt");
            String have = Files.exists(verFile) ? Files.readString(verFile).trim() : "";
            boolean existing = false;
            for (String f : files) if (Files.exists(base.resolve(f))) existing = true;
            boolean update = jarVer != null && existing && !jarVer.equals(have);
            boolean overwrite = false;
            if (update) {
                if (overwriteAllowed) {
                    backup(base, files);
                    overwrite = true;
                } else {
                    log.warning("Hay datos nuevos (items/recetas/lang) pero actualizacion.sobrescribir_datos=false: se mantienen los antiguos "
                            + "y pueden faltar items, recetas o textos de esta versión.");
                }
            }
            for (String f : files) {
                Path t = base.resolve(f);
                if (Files.exists(t) && !overwrite) continue;
                byte[] data = src.read(f);
                if (data == null) {
                    log.warning("Falta en el jar: " + f);
                    continue;
                }
                Files.createDirectories(t.getParent());
                Files.write(t, data);
            }
            if (jarVer != null && (!update || overwrite)) Files.writeString(verFile, jarVer + "\n");
        } catch (IOException e) {
            log.warning("No se pudieron actualizar los datos de Forja: " + e.getMessage());
        }
    }

    private void backup(Path base, List<String> files) throws IOException {
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        Path bk = base.resolve("backup-" + stamp);
        int n = 0;
        for (String f : files) {
            Path from = base.resolve(f);
            if (!Files.exists(from)) continue;
            Path to = bk.resolve(f);
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
            n++;
        }
        for (String dir : new String[]{"items", "recipes"}) {
            File[] ls = new File(folder, dir).listFiles((d, name) -> name.endsWith(".yml"));
            if (ls == null) continue;
            for (File f : ls) {
                String rel = dir + "/" + f.getName();
                if (files.contains(rel) || f.getName().startsWith("custom_")) continue;
                Path to = bk.resolve(rel);
                Files.createDirectories(to.getParent());
                Files.move(f.toPath(), to);
                n++;
            }
        }
        log.warning("Datos actualizados a la versión nueva. Copia de seguridad de los anteriores (" + n + " archivos) en "
                + bk.getFileName() + "/ . Si habías editado items/recetas, recupéralos de ahí (o ponlos en archivos custom_*.yml).");
    }
}
