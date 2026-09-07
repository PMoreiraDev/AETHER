package ai;

import domain.entities.AetherEntity;
import persistence.VaultManager;
import util.VaultIndex;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * DuplicateResolver — deteta entidades potencialmente duplicadas antes de uma
 * criação, consultando o {@link VaultIndex} (cache central).
 * <p>
 * A deteção é feita em código de aplicação, não no prompt do modelo — o modelo
 * nunca decide sozinho se deve reutilizar uma entidade existente. A comparação é
 * insensível a diacríticos e a capitalização.
 * </p>
 * <p>
 * Resultado:
 * <ul>
 *   <li>{@link Outcome#NO_DUPLICATE} — não há entidade semelhante; pode criar.</li>
 *   <li>{@link Outcome#POSSIBLE_DUPLICATE} — há uma entidade com nome parecido;
 *       a UI oferece "Usar existente / Criar mesmo assim / Cancelar".</li>
 *   <li>{@link Outcome#EXACT_DUPLICATE} — já existe uma entidade com o mesmo nome.</li>
 * </ul>
 * Não há casos especiais para nomes específicos (João Costa, AETHER, etc.).
 * </p>
 *
 * @author AETHER
 */
public final class DuplicateResolver {

    private DuplicateResolver() {
        // Classe de utilitário estático.
    }

    /** Resultado da resolução de duplicados. */
    public static final class Result {
        /** Desfecho da resolução. */
        public final Outcome outcome;
        /** Entidade existente semelhante, se aplicável. */
        public final AetherEntity existing;
        /** Lista de todas as entidades existentes com nome parecido. */
        public final List<AetherEntity> candidates;

        private Result(Outcome outcome, AetherEntity existing, List<AetherEntity> candidates) {
            this.outcome = outcome;
            this.existing = existing;
            this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** Desfecho da resolução de duplicados. */
    public enum Outcome {
        NO_DUPLICATE, POSSIBLE_DUPLICATE, EXACT_DUPLICATE
    }

    /**
     * Resolve duplicados para um nome/título e tipo de entidade.
     *
     * @param entityType tipo de entidade a criar
     * @param name        nome/título proposto
     * @return resultado da resolução
     */
    public static Result resolve(domain.entities.ContextEntityType entityType, String name) {
        if (name == null || name.isBlank()) {
            return new Result(Outcome.NO_DUPLICATE, null, List.of());
        }
        String normalized = normalize(name);
        List<AetherEntity> existing = entitiesOfType(entityType);
        List<AetherEntity> candidates = new ArrayList<>();
        boolean exact = false;
        AetherEntity exactMatch = null;
        for (AetherEntity e : existing) {
            String en = normalize(displayName(e));
            if (en.isEmpty()) continue;
            if (en.equals(normalized)) {
                exact = true;
                exactMatch = e;
                candidates.add(e);
            } else if (isSimilar(normalized, en)) {
                candidates.add(e);
            }
        }
        if (exact) {
            return new Result(Outcome.EXACT_DUPLICATE, exactMatch, candidates);
        }
        if (!candidates.isEmpty()) {
            return new Result(Outcome.POSSIBLE_DUPLICATE, candidates.get(0), candidates);
        }
        return new Result(Outcome.NO_DUPLICATE, null, List.of());
    }

    private static List<AetherEntity> entitiesOfType(domain.entities.ContextEntityType t) {
        // VaultIndex é a fonte central; faz fallback ao VaultManager se o index
        // estiver vazio (ex.: ainda não aquecido).
        return switch (t) {
            case PERSON -> new ArrayList<>(VaultIndex.getInstance().people());
            case PROJECT -> new ArrayList<>(VaultIndex.getInstance().projects());
            case EVENT -> new ArrayList<>(VaultIndex.getInstance().events());
            case TASK -> new ArrayList<>(VaultIndex.getInstance().tasks());
            case NOTE -> new ArrayList<>(VaultIndex.getInstance().notes());
            case PROFILE -> new ArrayList<>();
        };
    }

    private static String displayName(AetherEntity e) {
        if (e == null) return "";
        return switch (e) {
            case domain.entities.Person p -> p.getName() == null ? "" : p.getName();
            case domain.entities.Project p -> p.getName() == null ? "" : p.getName();
            case domain.entities.Event ev -> ev.getTitle() == null ? "" : ev.getTitle();
            case domain.entities.Task tk -> tk.getTitle() == null ? "" : tk.getTitle();
            case domain.entities.Note n -> n.getContent() == null ? "" : n.getContent();
            default -> "";
        };
    }

    /** Normaliza texto: minúsculas, sem diacríticos, sem espaços extra. */
    static String normalize(String text) {
        if (text == null) return "";
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return decomposed.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /** Heurística simples de semelhança: um contém o outro, ou distância pequena. */
    private static boolean isSimilar(String a, String b) {
        if (a.isBlank() || b.isBlank()) return false;
        if (a.contains(b) || b.contains(a)) {
            // Contenção só conta se o nome menor tiver >= 4 caracteres
            // (caso contrário "Al" coincide com "Alberto" sem ser duplicado).
            int minLen = Math.min(a.length(), b.length());
            return minLen >= 4;
        }
        // Distância de Levenshtein pequena para nomes curtos.
        int dist = levenshtein(a, b);
        int maxLen = Math.max(a.length(), b.length());
        return maxLen >= 5 && dist <= 2;
    }

    private static int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }
}
