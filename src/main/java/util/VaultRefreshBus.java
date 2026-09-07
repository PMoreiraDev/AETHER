package util;

import java.util.logging.Level;
import java.util.logging.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * VaultRefreshBus — canal simples de eventos "o vault mudou".
 * <p>
 * Centraliza a notificação de que entidades foram criadas, atualizadas ou
 * eliminadas (na app ou via alterações externas detetadas pelo
 * {@link VaultFileWatcher}). Os controladores de UI que mostram dados do vault
 * (calendário, listas, pesquisa) subscrevem e refrescam a sua vista quando
 * recebem o evento — sem ser necessário reiniciar a app.
 * </p>
 * <p>
 * É um barramento mínimo, não um novo index nem um serviço duplicado: só
 * retransmite o sinal de "algo mudou". Os dados continuam a vir do
 * {@link VaultIndex}. Os listeners são invocados de forma síncrona na thread
 * que publica; os controladores de UI devem envolver o refresh em
 * {@code Platform.runLater} se tocarem na cena.
 * </p>
 *
 * @author AETHER
 */
public final class VaultRefreshBus {
    private static final Logger LOGGER = Logger.getLogger(VaultRefreshBus.class.getName());


    /** Sinal de que tipo de mudança ocorreu. */
    public enum ChangeType {
        /** Criação de entidade. */
        CREATED,
        /** Atualização de entidade. */
        UPDATED,
        /** Eliminação de entidade. */
        DELETED,
        /** Alteração externa detetada pelo FileWatcher. */
        EXTERNAL
    }

    /** Evento de refresh. */
    public static final class VaultChangedEvent {
        /** Tipo de mudança. */
        public final ChangeType type;
        /** Tipo de entidade afetada, se conhecido. */
        public final String entityType;

        VaultChangedEvent(ChangeType type, String entityType) {
            this.type = type;
            this.entityType = entityType == null ? "" : entityType;
        }
    }

    private static final List<Consumer<VaultChangedEvent>> listeners = new CopyOnWriteArrayList<>();

    private VaultRefreshBus() {
        // Classe de utilitário estático.
    }

    /** Subscreve um listener. Devolve {@code false} se já estava subscrito. */
    public static boolean subscribe(Consumer<VaultChangedEvent> listener) {
        if (listener == null || listeners.contains(listener)) {
            return false;
        }
        listeners.add(listener);
        return true;
    }

    /** Cancela a subscrição de um listener. */
    public static boolean unsubscribe(Consumer<VaultChangedEvent> listener) {
        return listeners.remove(listener);
    }

    /** Publica um evento de mudança a todos os listeners. */
    public static void publish(ChangeType type, String entityType) {
        VaultChangedEvent event = new VaultChangedEvent(type, entityType);
        for (Consumer<VaultChangedEvent> l : listeners) {
            try {
                l.accept(event);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "[AETHER] A vault refresh listener failed: " + e.getMessage());
            }
        }
    }

    /** Publica um evento genérico de mudança. */
    public static void publish(ChangeType type) {
        publish(type, null);
    }

    /** Limpa todos os listeners (usado em testes). */
    static void clear() {
        listeners.clear();
    }
}
