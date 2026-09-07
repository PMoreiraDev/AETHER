import org.junit.jupiter.api.Test;
import util.TemporalParser;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TemporalParserTest — valida a resolução defensiva de datas relativas do lado
 * do Java (sem Ollama). Garante que datas ambíguas NUNCA se transformam em datas
 * inventadas: ou resolvem ou devolvem AMBIGUOUS/NONE.
 */
class TemporalParserTest {

    // Domingo, 6 de setembro de 2026 (consistente com a data de execução).
    private final LocalDate today = LocalDate.of(2026, 9, 6);

    @Test
    void resolvesHojeAmanhaOntem() {
        assertEquals(today, TemporalParser.resolve("hoje", today, null).date);
        assertEquals(today.plusDays(1), TemporalParser.resolve("amanhã", today, null).date);
        assertEquals(today.minusDays(1), TemporalParser.resolve("ontem", today, null).date);
        assertEquals(today, TemporalParser.resolve("today", today, null).date);
        assertEquals(today.plusDays(1), TemporalParser.resolve("tomorrow", today, null).date);
    }

    @Test
    void resolvesWeekdayStrictlyAfterToday() {
        // "sexta-feira" num domingo → próxima sexta (6 dias depois) = 11 set.
        var r = TemporalParser.resolve("Vou viajar na sexta-feira", today, null);
        assertEquals(TemporalParser.Outcome.RESOLVED, r.outcome);
        assertEquals(LocalDate.of(2026, 9, 11), r.date);
    }

    @Test
    void resolvesProximaSemanaQualifier() {
        // "próxima terça" → terça da semana seguinte = 15 set.
        var r = TemporalParser.resolve("Reunião na próxima terça", today, null);
        assertEquals(TemporalParser.Outcome.RESOLVED, r.outcome);
        assertEquals(LocalDate.of(2026, 9, 15), r.date);
    }

    @Test
    void resolvesDiaN() {
        var r = TemporalParser.resolve("Prazo no dia 14", today, null);
        assertEquals(LocalDate.of(2026, 9, 14), r.date);
    }

    @Test
    void resolvesNDeMes() {
        var r = TemporalParser.resolve("Entrega a 14 de outubro", today, null);
        assertEquals(LocalDate.of(2026, 10, 14), r.date);
    }

    @Test
    void resolvesDaquiAN() {
        assertEquals(today.plusDays(3),
                TemporalParser.resolve("daqui a 3 dias", today, null).date);
        assertEquals(today.plusWeeks(2),
                TemporalParser.resolve("daqui a 2 semanas", today, null).date);
    }

    @Test
    void resolvesProximaSemanaAndMes() {
        assertEquals(today.plusWeeks(1),
                TemporalParser.resolve("próxima semana", today, null).date);
        assertEquals(today.plusMonths(1),
                TemporalParser.resolve("no próximo mês", today, null).date);
    }

    @Test
    void resolvesTimeAndDateTime() {
        var r = TemporalParser.resolve("às 15h", today, null);
        // Só hora, sem data → ambíguo (não inventamos o dia).
        assertEquals(TemporalParser.Outcome.AMBIGUOUS, r.outcome);
        assertEquals(LocalTime.of(15, 0), r.time);

        var r2 = TemporalParser.resolve("amanhã às 15h30", today, null);
        assertEquals(TemporalParser.Outcome.RESOLVED, r2.outcome);
        assertEquals(today.plusDays(1), r2.date);
        assertEquals(LocalTime.of(15, 30), r2.time);
        assertTrue(r2.asDateTime().isPresent());
    }

    @Test
    void returnsNoneForNonTemporalText() {
        assertEquals(TemporalParser.Outcome.NONE,
                TemporalParser.resolve("gosto de café", today, null).outcome);
        assertEquals(TemporalParser.Outcome.NONE,
                TemporalParser.resolve("", today, null).outcome);
        assertEquals(TemporalParser.Outcome.NONE,
                TemporalParser.resolve(null, today, null).outcome);
    }

    @Test
    void invalidDayOfMonthIsNotInvented() {
        // 31 de fevereiro não existe → NONE (não se fabrica data).
        var r = TemporalParser.resolve("a 31 de fevereiro", today, null);
        assertEquals(TemporalParser.Outcome.NONE, r.outcome);
    }

    @Test
    void unknownMonthIsAmbiguousNotInvented() {
        var r = TemporalParser.resolve("a 14 de smarch", today, null);
        assertEquals(TemporalParser.Outcome.AMBIGUOUS, r.outcome);
    }

    @Test
    void resolvesInTimezoneWithoutAssumingArbitraryOne() {
        // O "agora" vem do fuso fornecido — o parser nunca assume uma timezone.
        var r = TemporalParser.resolve("hoje", ZoneId.of("Europe/Lisbon"), null);
        assertEquals(TemporalParser.Outcome.RESOLVED, r.outcome);
        assertEquals(LocalDate.now(ZoneId.of("Europe/Lisbon")), r.date);
    }
}
