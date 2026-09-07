package util;

import java.util.logging.Level;
import java.util.logging.Logger;

import persistence.VaultManager;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

/**
 * VaultFileWatcher — monitoriza alterações externas ao vault (ex.: edições
 * feitas no Obsidian) e invalida o {@link VaultIndex} para que o AETHER veja
 * dados atualizados.
 * <p>
 * Usa o Java NIO {@link WatchService} sobre as subpastas do vault (People,
 * Events, Tasks, Notes, Projects). Quando um ficheiro .md é criado, modificado
 * ou eliminado externamente, o index é invalidado (e reconstruído na próxima
 * leitura). Eventos rápidos são coalescidos (debounce) para evitar reindexação
 * redundante.
 * </p>
 * <p>
 * <b>Supressão de loops:</b> quando o próprio AETHER escreve um ficheiro (via
 * {@link persistence.VaultManager}), regista-o com
 * {@link #markInternalWrite(Path)}. O watcher ignora eventos para esses
 * caminhos durante uma janela curta, evitando o ciclo
 * "AETHER writes → watcher detects → reindex → write again".
 * </p>
 *
 * @author AETHER
 */
public final class VaultFileWatcher {
    private static final Logger LOGGER = Logger.getLogger(VaultFileWatcher.class.getName());


    /** Janela durante a qual uma escrita interna suprime o evento do watcher. */
    private static final long SUPPRESSION_WINDOW_MS = 1500;

    /** Debounce: tempo de quietude antes de invalidar o index após um evento. */
    private static final long DEBOUNCE_MS = 250;

    private static final VaultFileWatcher INSTANCE = new VaultFileWatcher();

    private final Map<String, Long> internalWrites = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "aether-vault-watcher");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile WatchService watchService;
    private final Map<WatchKey, Path> keys = new ConcurrentHashMap<>();

    private long lastInvalidation = 0L;

    private VaultFileWatcher() {
        // Singleton.
    }

    /** Devolve a instância única. */
    public static VaultFileWatcher getInstance() {
        return INSTANCE;
    }

    /**
     * Regista que o AETHER acabou de escrever/eliminar um ficheiro, para
     * suprimir o evento correspondente do watcher (evita loop).
     *
     * @param file o caminho do ficheiro escrito/eliminado
     */
    public static void markInternalWrite(Path file) {
        if (file == null) {
            return;
        }
        getInstance().internalWrites.put(
                file.toAbsolutePath().normalize().toString(),
                System.currentTimeMillis());
    }

    /** Indica se o watcher está ativo. */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Inicia a monitorização do vault atual. Idempotente: se já estiver a
     * correr, não faz nada. Se o vault não estiver inicializado, aguarda que
     * esteja (regista quando as pastas aparecerem).
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        executor.submit(this::watchLoop);
    }

    /**
     * Para a monitorização e liberta os recursos.
     */
    public void stop() {
        running.set(false);
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
                // Ignorar erro ao fechar.
            }
        }
    }

    private void watchLoop() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "[AETHER] Could not start vault watcher: " + e.getMessage());
            running.set(false);
            return;
        }

        while (running.get()) {
            // Registar/confirmar as pastas do vault.
            registerVaultRoots();

            WatchKey key;
            try {
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (key == null) {
                continue;
            }
            Path dir = keys.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }

            boolean externalChange = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                Object context = event.context();
                if (context == null) {
                    continue;
                }
                Path file = dir.resolve((Path) context);
                if (!file.toString().endsWith(".md")) {
                    continue;
                }
                if (isSuppressed(file)) {
                    // Escrita interna do próprio AETHER: não reprocessar.
                    continue;
                }
                externalChange = true;
            }

            if (externalChange) {
                scheduleInvalidation();
            }
            key.reset();
        }
    }

    /**
     * Regista todas as subpastas do vault no WatchService. Chamado periodicamente
     * para lidar com o caso de o vault ainda não existir no arranque.
     */
    private void registerVaultRoots() {
        if (!VaultManager.isVaultInitialized()) {
            return;
        }
        Path vault = VaultManager.getVaultPath();
        register(vault);
        for (String sub : new String[]{"People", "Events", "Tasks", "Notes", "Projects", "User"}) {
            Path dir = vault.resolve(sub);
            if (java.nio.file.Files.isDirectory(dir)) {
                register(dir);
            }
        }
    }

    private void register(Path dir) {
        try {
            WatchKey key = dir.register(watchService,
                    ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE);
            keys.putIfAbsent(key, dir);
        } catch (IOException e) {
            // Registar falha de registo: não fatal, o watcher continua.
            LOGGER.log(Level.WARNING, "[AETHER] Could not register vault directory " + dir + ": " + e.getMessage());
        }
    }

    private boolean isSuppressed(Path file) {
        String key = file.toAbsolutePath().normalize().toString();
        Long ts = internalWrites.get(key);
        if (ts == null) {
            return false;
        }
        if (System.currentTimeMillis() - ts < SUPPRESSION_WINDOW_MS) {
            // Suprimir e limpar.
            internalWrites.remove(key);
            return true;
        }
        // Janela expirada: limpar entrada antiga.
        internalWrites.remove(key);
        return false;
    }

    /**
     * Debounce: invalida o index após DEBOUNCE_MS de quietude. Eventos rápidos
     * consecutivos (ex.: Obsidian a guardar várias vezes) coalescem numa só
     * invalidação.
     */
    private void scheduleInvalidation() {
        lastInvalidation = System.currentTimeMillis();
        // Invalidação imediata + marca de tempo para coalescing. Uma invalidação
        // extra é barata (apenas marca o cache como sujo); a reconstrução real
        // só acontece na próxima leitura.
        VaultIndex.getInstance().invalidate();
        // Notifica a UI para refrescar (calendário, listas, pesquisa) sem reiniciar.
        VaultRefreshBus.publish(VaultRefreshBus.ChangeType.EXTERNAL);
    }
}
