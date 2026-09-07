package persistence;

import domain.UserProfile;
import util.AtomicWrites;
import util.VaultFileWatcher;
import util.VaultIndex;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * UserVaultSync — sincroniza o PERFIL DO UTILIZADOR com a pasta {@code User/}
 * do Vault Obsidian (spec #12, #14).
 * <p>
 * O perfil estruturado (SQLite) é a fonte canónica; o Vault é a representação
 * durável e legível pelo utilizador. Em cada alteração aceite do perfil
 * (conversa, edição manual, aprovação de proposta), esta classe reescreve os
 * ficheiros de {@code AETHER-Vault/User/}:
 * </p>
 * <pre>
 *   Profile.md              — visão geral (nome, data de nascimento, localização, ocupação, about)
 *   Personal Information.md — dados pessoais (nome, data de nascimento, localização)
 *   Studies.md              — estudos
 *   Career.md               — ocupação + experiência
 *   Skills.md               — competências
 *   Interests.md            — interesses
 *   Goals.md                — objetivos
 *   Preferences.md          — preferências
 *   Projects.md             — projetos
 *   Work Style.md           — estilo de trabalho
 *   Inferred Context.md     — contexto inferido pela IA (bullets)
 *   Timeline.md             — histórico temporal de alterações (spec #23)
 * </pre>
 * <p>
 * <b>SEMÂNTICA MERGE-READ-WRITE (spec #13):</b> o AETHER NUNCA destrói dados do
 * Obsidian. Antes de escrever, lê o ficheiro existente, atualiza APENAS as
 * chaves de frontmatter que conhece (as que correspondem a campos do perfil),
 * PRESERVA todas as chaves desconhecidas (escritas à mão pelo utilizador) e
 * preserva integralmente o corpo do documento (notas manuais do utilizador).
 * Escritas atómicas (AtomicWrites) + supressão do watcher
 * (VaultFileWatcher.markInternalWrite) + invalidação do índice.
 * </p>
 *
 * @author AETHER
 */
public final class UserVaultSync {

    private static final Logger LOGGER = Logger.getLogger(UserVaultSync.class.getName());

    public static final String USER_DIR = "User";

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private UserVaultSync() {
        // Utilitário estático.
    }

    /**
     * Sincroniza a pasta {@code User/} com o perfil atual.
     *
     * @param profile o perfil canónico do utilizador (SQLite)
     * @param reason   motivo da sincronização (log; ex.: "AI_ACCEPT",
     *                 "MANUAL_EDIT", "APP_START")
     * @return {@code true} se todos os ficheiros foram escritos com sucesso
     */
    public static boolean syncProfile(UserProfile profile, String reason) {
        if (profile == null) return false;
        try {
            VaultManager.initializeVault();
        } catch (RuntimeException e) {
            LOGGER.warning("UserVaultSync: vault init falhou: " + e.getMessage());
            return false;
        }
        Path userDir = VaultManager.getVaultPath().resolve(USER_DIR);
        boolean ok = true;
        ok &= syncOverview(userDir, profile);
        ok &= syncPersonalInformation(userDir, profile);
        ok &= syncFieldFile(userDir, "Studies.md", "Studies", profile,
                Map.of("studies", profile.getStudies()),
                "Estudos, cursos e formação.");
        ok &= syncFieldFile(userDir, "Career.md", "Career", profile,
                Map.of("occupation", profile.getOccupation(),
                        "experience", profile.getExperience()),
                "Carreira e experiência profissional.");
        ok &= syncFieldFile(userDir, "Skills.md", "Skills", profile,
                Map.of("skills", profile.getSkills()),
                "Competências e tecnologias.");
        ok &= syncFieldFile(userDir, "Interests.md", "Interests", profile,
                Map.of("interests", profile.getInterests()),
                "Interesses pessoais.");
        ok &= syncFieldFile(userDir, "Goals.md", "Goals", profile,
                Map.of("goals", profile.getObjectives()),
                "Objetivos e ambições.");
        ok &= syncFieldFile(userDir, "Preferences.md", "Preferences", profile,
                Map.of("preferences", profile.getPreferences()),
                "Preferências de trabalho e comunicação.");
        ok &= syncFieldFile(userDir, "Projects.md", "Projects", profile,
                Map.of("projects", profile.getProjects()),
                "Projetos do utilizador.");
        ok &= syncFieldFile(userDir, "Work Style.md", "Work Style", profile,
                Map.of("work_style", profile.getWorkStyle()),
                "Estilo de trabalho.");
        ok &= syncInferredContext(userDir, profile);
        ok &= ensureTimeline(userDir);
        VaultIndex.getInstance().invalidate();
        LOGGER.fine("UserVaultSync: pasta User/ sincronizada (" + reason + ").");
        return ok;
    }

    // ------------------------------------------------------------------
    // Ficheiros
    // ------------------------------------------------------------------

    /** Profile.md — visão geral. */
    private static boolean syncOverview(Path dir, UserProfile p) {
        String birth = p.getBirthDate() == null ? "" : p.getBirthDate().toString();
        return syncFieldFile(dir, "Profile.md", "Profile", p,
                Map.of("full_name", orEmpty(p.getFullName()),
                        "preferred_name", orEmpty(p.getPreferredName()),
                        "birthday", birth,
                        "location", orEmpty(p.getLocation()),
                        "occupation", orEmpty(p.getOccupation()),
                        "profile_photo", orEmpty(p.getProfilePhotoPath())),
                orEmpty(p.getAbout()));
    }

    /** Personal Information.md — dados pessoais. */
    private static boolean syncPersonalInformation(Path dir, UserProfile p) {
        String birth = p.getBirthDate() == null ? "" : p.getBirthDate().toString();
        return syncFieldFile(dir, "Personal Information.md", "Personal Information", p,
                Map.of("full_name", orEmpty(p.getFullName()),
                        "preferred_name", orEmpty(p.getPreferredName()),
                        "birthday", birth,
                        "location", orEmpty(p.getLocation())),
                "Dados pessoais do utilizador. Editáveis à mão no Obsidian; " +
                        "campos desconhecidos são preservados pelo AETHER.");
    }

    /** Inferred Context.md — bullets de contexto inferido (não limpa bullets manuais). */
    private static boolean syncInferredContext(Path dir, UserProfile p) {
        return syncFieldFile(dir, "Inferred Context.md", "Inferred Context", p,
                Map.of("inferred_context", orEmpty(p.getInferredContext())),
                orEmpty(p.getInferredContext()));
    }

    /**
     * MERGE-READ-WRITE de um ficheiro User/:
     *  1. lê o ficheiro existente (se houver);
     *  2. preserva TODAS as chaves de frontmatter desconhecidas e o corpo;
     *  3. atualiza apenas as chaves conhecidas (campos do perfil);
     *  4. escrita atómica + supressão do watcher.
     *
     * @param dir            pasta User/
     * @param fileName       nome do ficheiro (ex.: "Studies.md")
     * @param title          título H1 do documento (para ficheiros novos)
     * @param profile        perfil (para id do utilizador)
     * @param knownKeys      chaves de frontmatter conhecidas → valor atual
     * @param newBodyDefault corpo a usar quando o ficheiro é criado de raiz
     */
    private static boolean syncFieldFile(Path dir, String fileName, String title,
                                         UserProfile profile,
                                         Map<String, String> knownKeys,
                                         String newBodyDefault) {
        Path file = dir.resolve(fileName);
        try {
            Files.createDirectories(dir);
            String content = Files.exists(file) ? Files.readString(file) : "";
            String[] parts = splitFrontmatter(content);
            String body = parts[1];

            // Frontmatter: LinkedHashMap preserva a ordem existente e as
            // chaves desconhecidas; adiciona/atualiza apenas as conhecidas.
            java.util.LinkedHashMap<String, String> fm = parseOrdered(parts[0]);

            // Garante o id do utilizador e o tipo (chaves geridas pelo AETHER).
            fm.put("id", "user");
            fm.put("type", "user");

            // O ficheiro novo ganha um corpo por defeito; o existente PRESERVA
            // o corpo atual (notas manuais do utilizador nunca são reescritas).
            String bodyToWrite = content.isBlank() ? defaultBody(title, newBodyDefault) : body;

            for (Map.Entry<String, String> e : knownKeys.entrySet()) {
                fm.put(e.getKey(), yamlValue(e.getValue()));
            }

            StringBuilder out = new StringBuilder();
            out.append("---\n");
            for (Map.Entry<String, String> e : fm.entrySet()) {
                out.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
            out.append("---\n");
            out.append(bodyToWrite);
            String finalContent = out.toString();

            // Supressão antes E depois: se o evento do watcher disparar cedo
            // (entre a leitura e a escrita), a marca posterior ainda cobre a
            // janela; o pior caso é uma invalidação extra (inofensiva).
            VaultFileWatcher.markInternalWrite(file);
            AtomicWrites.write(file, finalContent);
            VaultFileWatcher.markInternalWrite(file);
            return true;
        } catch (IOException | UncheckedIOException e) {
            LOGGER.warning("UserVaultSync: falha ao sincronizar " + fileName + ": " + e.getMessage());
            return false;
        }
    }

    /** Garante que Timeline.md existe (spec #14: 12 ficheiros em User/). */
    private static boolean ensureTimeline(Path dir) {
        Path file = dir.resolve("Timeline.md");
        if (Files.exists(file)) return true;
        try {
            Files.createDirectories(dir);
            VaultFileWatcher.markInternalWrite(file);
            AtomicWrites.write(file, defaultBody("Timeline",
                    "Histórico temporal do perfil do utilizador (append-only)."));
            VaultFileWatcher.markInternalWrite(file);
            return true;
        } catch (IOException e) {
            LOGGER.warning("UserVaultSync: falha ao criar Timeline.md: " + e.getMessage());
            return false;
        }
    }

    /** Timeline.md — histórico temporal (append-only; nunca reescreve). */
    public static boolean appendTimeline(String entryLine) {
        if (entryLine == null || entryLine.isBlank()) return false;
        try {
            VaultManager.initializeVault();
        } catch (RuntimeException e) {
            return false;
        }
        Path dir = VaultManager.getVaultPath().resolve(USER_DIR);
        Path file = dir.resolve("Timeline.md");
        try {
            Files.createDirectories(dir);
            String content = Files.exists(file) ? Files.readString(file) : "";
            // APPEND-ONLY (spec #23): preserva o conteúdo integral e acrescenta
            // a nova linha no fim. Nunca se reescreve/apaga histórico.
            StringBuilder sb = new StringBuilder();
            if (content.isBlank()) {
                sb.append(defaultBody("Timeline",
                        "Histórico temporal do perfil do utilizador (append-only)."));
            } else {
                sb.append(content);
            }
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append(entryLine).append('\n');
            VaultFileWatcher.markInternalWrite(file);
            AtomicWrites.write(file, sb.toString());
            VaultFileWatcher.markInternalWrite(file);
            return true;
        } catch (IOException e) {
            LOGGER.warning("UserVaultSync: falha ao escrever Timeline.md: " + e.getMessage());
            return false;
        }
    }

    /**
     * Constrói a linha de timeline para uma transição aceite (spec #23):
     * mantém o valor antigo visível ("Amarante → Porto").
     */
    public static String timelineLine(String field, String label, String newValue,
                                      String previousValue, String sourceType,
                                      LocalDateTime timestamp) {
        String ts = timestamp == null ? LocalDateTime.now().format(TS) : timestamp.format(TS);
        String prev = previousValue == null || previousValue.isBlank() ? "(vazio)" : previousValue;
        String change = newValue.equals(previousValue)
                ? "\"" + newValue + "\""
                : "\"" + prev + "\" → \"" + newValue + "\"";
        return "- " + ts + " — " + label + ": " + change + " (fonte: " + sourceType + ")";
    }

    // ------------------------------------------------------------------
    // Helpers de frontmatter
    // ------------------------------------------------------------------

    /** Divide "---\n...\n---\nbody" em {frontmatterBlock, body}. */
    private static String[] splitFrontmatter(String content) {
        String fmBlock = "";
        String body = "";
        if (content != null && content.startsWith("---\n")) {
            int end = content.indexOf("\n---", 4);
            if (end >= 0) {
                fmBlock = content.substring(0, end + 4); // inclui o "---" final
                int bodyStart = end + 4;
                while (bodyStart < content.length()
                        && (content.charAt(bodyStart) == '\n' || content.charAt(bodyStart) == '\r')) {
                    bodyStart++;
                }
                body = content.substring(bodyStart);
            } else {
                body = content;
            }
        } else if (content != null) {
            body = content;
        }
        return new String[]{fmBlock, body};
    }

    /** Parser ordenado de frontmatter (preserva ordem + chaves desconhecidas). */
    private static java.util.LinkedHashMap<String, String> parseOrdered(String fmBlock) {
        java.util.LinkedHashMap<String, String> fm = new java.util.LinkedHashMap<>();
        if (fmBlock == null || fmBlock.isBlank()) return fm;
        for (String line : fmBlock.split("\n")) {
            String l = line.trim();
            if (l.equals("---")) continue;
            int colon = l.indexOf(':');
            if (colon > 0) {
                String key = l.substring(0, colon).trim();
                String value = l.substring(colon + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                fm.put(key, value);
            }
        }
        return fm;
    }

    /**
     * Valor YAML seguro: vazio vazio; valores com dois pontos, cardinal ou
     * aspas vão entre aspas duplas com aspas internas escapadas.
     */
    private static String yamlValue(String v) {
        if (v == null) v = "";
        v = v.replace("\r", "").replace("\n", " ").trim();
        if (v.isEmpty()) return "\"\"";
        boolean needsQuotes = v.contains(":") || v.contains("#") || v.contains("\"")
                || v.startsWith("*") || v.startsWith("-") || v.startsWith("[")
                || v.startsWith("{") || v.startsWith("'") || v.startsWith("!");
        if (needsQuotes) {
            return "\"" + v.replace("\"", "\\\"") + "\"";
        }
        return v;
    }

    private static String defaultBody(String title, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        if (value != null && !value.isBlank()) {
            sb.append(value.trim()).append('\n');
        }
        return sb.toString();
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Lista os ficheiros da pasta User/ (para diagnóstico/testes). */
    public static List<String> listUserFiles() {
        Path dir = VaultManager.getVaultPath().resolve(USER_DIR);
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (var stream = Files.list(dir)) {
            stream.filter(f -> f.toString().endsWith(".md"))
                    .forEach(f -> out.add(f.getFileName().toString()));
        } catch (IOException e) {
            LOGGER.warning("UserVaultSync: falha ao listar User/: " + e.getMessage());
        }
        return out;
    }
}
