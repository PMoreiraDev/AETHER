package util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TemporalParser — resolve linguagem temporal natural para datas estruturadas.
 * <p>
 * Camada <b>defensiva e determinística</b> do lado do Java. O modelo (Ollama)
 * também resolve datas, mas este parser garante que datas relativas ambíguas
 * ou malformadas nunca se transformam em datas inventadas: se não houver
 * contexto suficiente, devolve {@link Outcome#NONE} ou {@link Outcome#AMBIGUOUS}.
 * </p>
 * <p>
 * Suporta PT-PT e EN: hoje/today, amanhã/tomorrow, ontem/yesterday, dias da
 * semana (com/sem "próxima/next"), "dia N", "N de mês", "daqui a N
 * dias/semanas", "próxima semana/mês", horas "às 15h" / "15:00", e combinações.
 * </p>
 * <p>
 * <b>Timezone:</b> o "agora" é sempre recebido como parâmetro. O parser nunca
 * assume uma timezone arbitrária — quem chama fornece o {@code today} no fuso
 * correto. Os carateres não-ASCII usam escapes Unicode para serem independentes
 * da codificação do compilador.
 * </p>
 *
 * @author AETHER
 */
public final class TemporalParser {

    /** Resultado da resolução temporal. */
    public static final class Result {
        public final Outcome outcome;
        public final LocalDate date;
        public final LocalTime time;
        public final String note;

        private Result(Outcome outcome, LocalDate date, LocalTime time, String note) {
            this.outcome = outcome;
            this.date = date;
            this.time = time;
            this.note = note == null ? "" : note;
        }

        /** Data + hora resolvidas, ou vazio se não foi possível. */
        public Optional<LocalDateTime> asDateTime() {
            if (outcome == Outcome.RESOLVED && date != null) {
                return Optional.of(LocalDateTime.of(date, time != null ? time : LocalTime.MIDNIGHT));
            }
            return Optional.empty();
        }

        static Result none() { return new Result(Outcome.NONE, null, null, ""); }
        static Result ambiguous(String note) { return new Result(Outcome.AMBIGUOUS, null, null, note); }
        static Result ambiguousWithTime(LocalTime time, String note) {
            return new Result(Outcome.AMBIGUOUS, null, time, note);
        }
        static Result resolved(LocalDate d, LocalTime t) { return new Result(Outcome.RESOLVED, d, t, ""); }
    }

    /** Desfecho da resolução. */
    public enum Outcome {
        /** Resolvido com confiança. */
        RESOLVED,
        /** Parece temporal mas não há contexto suficiente para fixar uma data. */
        AMBIGUOUS,
        /** Não contém informação temporal reconhecível. */
        NONE
    }

    private TemporalParser() {
        // Classe de utilitário estático.
    }

    // Non-ASCII constantes (escapes Unicode — independente da codificação).
    private static final String A_TIL = "\u00e3";       // ã
    private static final String A_GRAVE = "\u00e0";     // à
    private static final String A_ACUTE = "\u00e1";     // á
    private static final String O_ACUTE = "\u00f3";     // ó
    private static final String CEDIL = "\u00e7";       // ç
    private static final String E_CIRC = "\u00ea";      // ê

    private static final Pattern TIME_PT = Pattern.compile(
            "(?:" + A_GRAVE + "s?|as|a|por volta das)\\s*(\\d{1,2})\\s*h(?:ora)?s?\\s*(\\d{2})?(?:min)?\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_GENERIC = Pattern.compile(
            "\\b(\\d{1,2}):(\\d{2})\\b");
    private static final Pattern IN_N_UNITS = Pattern.compile(
            "(?:daqui a|in|em)\\s+(\\d+)\\s*(dia|dias|day|days|semana|semanas|week|weeks|m"
                    + E_CIRC + "s|mes|month|months)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DAY_N = Pattern.compile(
            "(?:dia|day)\\s+(\\d{1,2})(?:\\s+de\\s+([a-z" + CEDIL + A_ACUTE + A_GRAVE
                    + "]+))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern N_OF_MONTH = Pattern.compile(
            "(\\d{1,2})\\s+de\\s+([a-z" + CEDIL + A_ACUTE + A_GRAVE + "]+)",
            Pattern.CASE_INSENSITIVE);

    /** Resolve a data/hora implícita no texto, relativamente a {@code today}. */
    public static Result resolve(String text, LocalDate today, Locale locale) {
        if (text == null || text.isBlank()) return Result.none();
        String t = text.trim();
        LocalTime time = extractTime(t);
        String lower = t.toLowerCase(Locale.ROOT);

        // "hoje" / "today"
        if (containsWord(lower, "hoje", "today")) {
            return Result.resolved(today, time);
        }
        // "amanhã" / "tomorrow"
        if (containsWord(lower, "amanh" + A_TIL, "amanha", "amanh", "tomorrow")) {
            return Result.resolved(today.plusDays(1), time);
        }
        // "ontem" / "yesterday"
        if (containsWord(lower, "ontem", "yesterday")) {
            return Result.resolved(today.minusDays(1), time);
        }

        // "daqui a N dias/semanas/meses"
        Matcher inN = IN_N_UNITS.matcher(lower);
        if (inN.find()) {
            int n = parseInt(inN.group(1), 1);
            String unit = inN.group(2).toLowerCase(Locale.ROOT);
            long days = daysForUnit(unit, n);
            if (days > 0) {
                return Result.resolved(today.plusDays(days), time);
            }
        }

        // "próxima semana" / "next week"
        if (containsPhrase(lower, "pr" + O_ACUTE + "xima semana", "proxima semana", "next week")) {
            return Result.resolved(today.plusWeeks(1), time);
        }
        // "próximo mês" / "next month"
        if (containsPhrase(lower, "pr" + O_ACUTE + "ximo m" + E_CIRC + "s", "proximo mes",
                "next month", "no pr" + O_ACUTE + "ximo mes")) {
            return Result.resolved(today.plusMonths(1), time);
        }

        // dia da semana (com ou sem "próxima/next")
        DayOfWeek dow = findWeekday(lower);
        if (dow != null) {
            LocalDate resolved = nextWeekday(today, dow, hasNextQualifier(lower));
            return Result.resolved(resolved, time);
        }

        // "dia N [de mês]"
        Matcher dayN = DAY_N.matcher(t);
        if (dayN.find()) {
            int day = parseInt(dayN.group(1), -1);
            if (day >= 1 && day <= 31) {
                Month month = dayN.group(2) != null ? parseMonth(dayN.group(2)) : null;
                if (dayN.group(2) != null && month == null) {
                    return Result.ambiguous("Mês não reconhecido: " + dayN.group(2));
                }
                LocalDate candidate = resolveDayMonth(today, day, month);
                if (candidate != null) return Result.resolved(candidate, time);
            }
        }

        // "N de mês" (ex.: "14 de outubro")
        Matcher nom = N_OF_MONTH.matcher(t);
        if (nom.find()) {
            int day = parseInt(nom.group(1), -1);
            Month month = parseMonth(nom.group(2));
            if (day >= 1 && day <= 31 && month != null) {
                LocalDate candidate = resolveDayMonth(today, day, month);
                if (candidate != null) return Result.resolved(candidate, time);
            }
            if (month == null) {
                return Result.ambiguous("Mês não reconhecido: " + nom.group(2));
            }
        }

        // Só hora, sem data → ambíguo (não inventamos o dia), mas a hora é preservada.
        if (time != null) {
            return Result.ambiguousWithTime(time, "Hora encontrada mas sem data: usa a próxima ocorrência?");
        }

        return Result.none();
    }

    /** Resolve num fuso específico (não assume timezone arbitrária). */
    public static Result resolve(String text, ZoneId zone, Locale locale) {
        LocalDate today = LocalDate.now(zone);
        return resolve(text, today, locale);
    }

    // ------------------------------------------------------------------
    // Internos
    // ------------------------------------------------------------------

    private static LocalTime extractTime(String text) {
        Matcher m = TIME_PT.matcher(text);
        if (m.find()) {
            int h = parseInt(m.group(1), -1);
            int min = m.group(2) != null ? parseInt(m.group(2), 0) : 0;
            if (h >= 0 && h <= 23 && min >= 0 && min <= 59) return LocalTime.of(h, min);
        }
        m = TIME_GENERIC.matcher(text);
        if (m.find()) {
            int h = parseInt(m.group(1), -1);
            int min = parseInt(m.group(2), 0);
            if (h >= 0 && h <= 23 && min >= 0 && min <= 59) return LocalTime.of(h, min);
        }
        return null;
    }

    private static boolean containsWord(String lower, String... words) {
        for (String w : words) {
            if (lower.contains(w)) return true;
        }
        return false;
    }

    private static boolean containsPhrase(String lower, String... phrases) {
        for (String p : phrases) {
            if (lower.contains(p)) return true;
        }
        return false;
    }

    private static boolean hasNextQualifier(String lower) {
        return lower.contains("pr" + O_ACUTE + "xima") || lower.contains("proxima") || lower.contains("next");
    }

    private static DayOfWeek findWeekday(String lower) {
        // Aliases sem diacríticos para robustez; o mês/dia da semana em PT usa
        // a-grave apenas em "sábado".
        String[][] table = {
            {"segunda", "seg", "monday", "mon"},
            {"ter" + CEDIL + "a", "terca", "tuesday", "tue"},
            {"quarta", "wednesday", "wed"},
            {"quinta", "thursday", "thu"},
            {"sexta", "friday", "fri"},
            {"s" + A_ACUTE + "bado", "sabado", "saturday", "sat"},
            {"domingo", "sunday", "sun"}
        };
        for (int i = 0; i < table.length; i++) {
            for (String alias : table[i]) {
                if (lower.contains(alias)) return DayOfWeek.of(i + 1);
            }
        }
        return null;
    }

    /** Próxima ocorrência do dia da semana. Com qualificador "próxima", é sempre
     *  a próxima semana; sem qualificador, é a próxima ocorrência estritamente
     *  depois de hoje (se hoje for esse dia, vai para a semana seguinte). */
    private static LocalDate nextWeekday(LocalDate today, DayOfWeek target, boolean nextQualifier) {
        int todayDow = today.getDayOfWeek().getValue();
        int targetDow = target.getValue();
        int diff = (targetDow - todayDow + 7) % 7;
        if (nextQualifier) {
            diff += 7; // "próxima terça" → sempre semana seguinte
        }
        if (diff == 0) {
            // hoje é esse dia da semana e sem "próxima" → semana seguinte
            // (determinístico e seguro; não assumimos "hoje").
            diff = 7;
        }
        return today.plusDays(diff);
    }

    private static long daysForUnit(String unit, int n) {
        if (unit.startsWith("dia") || unit.startsWith("day")) return n;
        if (unit.startsWith("semana") || unit.startsWith("week")) return 7L * n;
        if (unit.startsWith("m" + E_CIRC) || unit.startsWith("mes") || unit.startsWith("month")) {
            return 30L * n;
        }
        return 0;
    }

    private static LocalDate resolveDayMonth(LocalDate today, int day, Month month) {
        if (month == null) {
            // "dia 14" sem mês → mês atual se o dia ainda não passou, senão próximo mês.
            LocalDate candidateThisMonth = safeDate(today.getYear(), today.getMonthValue(), day);
            if (candidateThisMonth != null && !candidateThisMonth.isBefore(today)) {
                return candidateThisMonth;
            }
            // próximo mês (mesmo dia)
            LocalDate next = today.plusMonths(1);
            LocalDate candidateNextMonth = safeDate(next.getYear(), next.getMonthValue(), day);
            return candidateNextMonth;
        }
        int year = today.getYear();
        LocalDate candidate = safeDate(year, month.getValue(), day);
        if (candidate == null) return null; // dia inválido (ex.: 31 fev)
        if (candidate.isBefore(today)) {
            candidate = safeDate(year + 1, month.getValue(), day);
        }
        return candidate;
    }

    private static LocalDate safeDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (java.time.DateTimeException e) {
            return null;
        }
    }

    private static Month parseMonth(String s) {
        if (s == null) return null;
        String lower = s.toLowerCase(Locale.ROOT).trim();
        // Tabela explícita mês→aliases (não depende da posição no array).
        String[][] table = {
            {"janeiro", "january", "jan"},
            {"fevereiro", "february", "feb"},
            {"mar" + CEDIL + "o", "marco", "march", "mar"},
            {"abril", "april", "apr"},
            {"maio", "may"},
            {"junho", "june", "jun"},
            {"julho", "july", "jul"},
            {"agosto", "august", "aug"},
            {"setembro", "september", "sep"},
            {"outubro", "october", "oct"},
            {"novembro", "november", "nov"},
            {"dezembro", "december", "dec"}
        };
        for (int i = 0; i < table.length; i++) {
            for (String alias : table[i]) {
                if (lower.equals(alias) || lower.startsWith(alias)) {
                    return Month.of(i + 1);
                }
            }
        }
        return null;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null || s.isBlank()) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Formata uma data resolvida para ISO (para campos das entidades). */
    public static String toIsoDate(LocalDate d) {
        return d != null ? DateTimeFormatter.ISO_LOCAL_DATE.format(d) : "";
    }

    /** Formata data+hora para ISO. */
    public static String toIsoDateTime(LocalDateTime dt) {
        return dt != null ? DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(dt) : "";
    }

    /** String legível do "agora" num fuso, para incluir no prompt do modelo. */
    public static String nowDescription(ZoneId zone) {
        ZonedDateTime z = ZonedDateTime.now(zone);
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm (EEEE, z)").format(z);
    }
}
