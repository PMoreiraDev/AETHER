package util;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * I18n — sistema central de localização do AETHER (P0.5).
 * <p>
 * Lê mensagens de {@code resources/i18n/messages_<locale>.properties}. Suporta
 * PT-PT e English. A preferência de idioma persiste entre reinícios via
 * {@link AetherPreferences}. Em vez de espalhar strings traduzidas pelos
 * controllers, estes chamam {@code I18n.tr("key")}.
 * </p>
 * <p>
 * <b>Estado:</b> fundação implementada e testável. A migração completa de todas
 * as strings de UI (FXML, dialogs, onboarding) para chaves i18n fica como P1 —
 * não está concluída para a Alpha.
 * </p>
 *
 * @author AETHER
 */
public final class I18n {

    private static final String BASE_NAME = "i18n.messages";
    private static volatile Locale current = parseLocale(AetherPreferences.getLanguage());

    private I18n() {
        // Classe de utilitário estático.
    }

    /** Devolve o locale ativo. */
    public static Locale getLocale() {
        return current;
    }

    /** Define o locale ativo e persiste a preferência. */
    public static void setLocale(Locale locale) {
        if (locale == null) return;
        current = locale;
        AetherPreferences.setLanguage(locale.toLanguageTag());
    }

    /**
     * Devolve o {@link ResourceBundle} do locale ativo, para passar ao
     * {@link javafx.fxml.FXMLLoader} e resolver referências {@code %key} nos FXML.
     *
     * @return o bundle de mensagens do locale atual
     */
    public static ResourceBundle getBundle() {
        return ResourceBundle.getBundle(BASE_NAME, current);
    }

    /**
     * Traduz uma chave para o locale ativo, com fallback para a chave original
     * se não existir tradução.
     *
     * @param key a chave de tradução
     * @param args argumentos opcionais para formatação
     * @return a mensagem traduzida
     */
    public static String tr(String key, Object... args) {
        if (key == null) return "";
        ResourceBundle bundle = ResourceBundle.getBundle(BASE_NAME, current);
        String message;
        try {
            message = bundle.getString(key);
        } catch (Exception e) {
            message = key;
        }
        if (args != null && args.length > 0) {
            return MessageFormat.format(message, args);
        }
        return message;
    }

    private static Locale parseLocale(String tag) {
        if (tag == null || tag.isBlank()) return new Locale("pt", "PT");
        String[] parts = tag.replace("_", "-").split("-");
        if (parts.length >= 2) return new Locale(parts[0], parts[1]);
        return new Locale(parts[0]);
    }
}
