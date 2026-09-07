import org.junit.jupiter.api.Test;
import util.AetherPreferences;
import util.I18n;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * I18nTest — valida a fundação de localização (PT-PT/English) do AETHER.
 */
class I18nTest {

    @Test
    void translatesKnownKeyInPortuguese() {
        I18n.setLocale(new Locale("pt", "PT"));
        assertEquals("Guardar", I18n.tr("common.save"));
        assertEquals("Cancelar", I18n.tr("common.cancel"));
    }

    @Test
    void translatesKnownKeyInEnglish() {
        I18n.setLocale(Locale.ENGLISH);
        assertEquals("Save", I18n.tr("common.save"));
        assertEquals("Cancel", I18n.tr("common.cancel"));
    }

    @Test
    void unknownKeyFallsBackToItself() {
        I18n.setLocale(new Locale("pt", "PT"));
        assertEquals("does.not.exist.key", I18n.tr("does.not.exist.key"));
    }

    @Test
    void localePersistsAcrossInstances() {
        I18n.setLocale(Locale.ENGLISH);
        // Re-read from preferences by constructing fresh via getLanguage.
        assertEquals("en", AetherPreferences.getLanguage());
        I18n.setLocale(new Locale("pt", "PT"));
        assertEquals("pt-PT", AetherPreferences.getLanguage());
    }
}
