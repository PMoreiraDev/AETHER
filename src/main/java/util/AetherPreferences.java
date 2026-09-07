package util;

import java.util.logging.Level;
import java.util.logging.Logger;

import persistence.AetherPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * AetherPreferences — armazenamento simples de preferências da UI (idioma)
 * no diretório de dados do AETHER.
 * <p>
 * Usa um ficheiro de propriedades simples em vez de estender o schema do
 * SQLite, para evitar migrações de base de dados na Alpha. As preferências
 * persistem entre reinícios e são locais (nenhuma telemetria).
 * </p>
 *
 * @author AETHER
 */
public final class AetherPreferences {
    private static final Logger LOGGER = Logger.getLogger(AetherPreferences.class.getName());


    private static final Path PREFS_FILE = AetherPaths.dataDirectory().resolve("aether.prefs");
    private static final String KEY_LANGUAGE = "language";

    private AetherPreferences() {
        // Classe de utilitário estático.
    }

    /** Devolve "pt-PT" ou "en" (por defeito: pt-PT). */
    public static String getLanguage() {
        return get(KEY_LANGUAGE, "pt-PT");
    }

    /** Define o idioma ("pt-PT" ou "en"). Persiste imediatamente. */
    public static void setLanguage(String language) {
        if (language == null) return;
        set(KEY_LANGUAGE, language);
    }

    private static String get(String key, String def) {
        try {
            Properties p = load();
            return p.getProperty(key, def);
        } catch (Exception e) {
            return def;
        }
    }

    private static void set(String key, String value) {
        try {
            Properties p = load();
            p.setProperty(key, value);
            store(p);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "[AETHER] Could not persist preference " + key + ": " + e.getMessage());
        }
    }

    private static Properties load() throws IOException {
        Properties p = new Properties();
        if (Files.isRegularFile(PREFS_FILE)) {
            try (var in = Files.newInputStream(PREFS_FILE)) {
                p.load(in);
            }
        }
        return p;
    }

    private static void store(Properties p) throws IOException {
        Files.createDirectories(PREFS_FILE.getParent() != null ? PREFS_FILE.getParent() : Path.of("."));
        try (var out = Files.newOutputStream(PREFS_FILE)) {
            p.store(out, "AETHER preferences");
        }
    }
}
