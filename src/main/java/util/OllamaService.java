package util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serviço de integração com o motor local do Ollama.
 * <p>
 * Localiza o executável, instala o motor, lista, transfere e remove modelos de
 * linguagem. Todos os métodos são bloqueantes e devem ser chamados fora do fio
 * da interface (por exemplo, dentro de uma {@code javafx.concurrent.Task}).
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.2
 */
public final class OllamaService {

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(OllamaService.class.getName());

    /** Tempo máximo, em minutos, para uma instalação ou transferência. */
    private static final long LONG_TASK_TIMEOUT_MINUTES = 60;

    /** Tempo máximo, em segundos, para comandos rápidos como {@code ollama list}. */
    private static final long QUICK_TASK_TIMEOUT_SECONDS = 20;

    /** Limiar de memória, em GB, acima do qual são sugeridos modelos maiores. */
    private static final long HIGH_MEMORY_GB = 16;

    /** Limiar de memória, em GB, acima do qual são sugeridos modelos médios. */
    private static final long MID_MEMORY_GB = 8;

    /**
     * Construtor privado: esta é uma classe utilitária e não deve ser instanciada.
     */
    private OllamaService() {
        // Classe utilitária.
    }

    /**
     * Procura o executável do Ollama nos caminhos habituais do sistema operativo.
     *
     * @return o caminho absoluto do executável, ou {@code null} se não for encontrado
     */
    public static String findOllamaExecutable() {
        for (String path : buildCandidatePaths()) {
            if (path != null && new File(path).canExecute()) {
                return path;
            }
        }
        LOGGER.fine("Executável do Ollama não encontrado nos caminhos conhecidos.");
        return null;
    }

    /**
     * Constrói a lista de caminhos onde o Ollama é normalmente instalado.
     *
     * @return os caminhos candidatos, na ordem de prioridade de pesquisa
     */
    private static List<String> buildCandidatePaths() {
        List<String> paths = new ArrayList<>();
        String home = System.getProperty("user.home", "");

        if (SystemInfo.isWindows()) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                paths.add(localAppData + "\\Programs\\Ollama\\ollama.exe");
            }
            paths.add("C:\\Program Files\\Ollama\\ollama.exe");
            paths.add("C:\\Program Files (x86)\\Ollama\\ollama.exe");
        } else {
            paths.add("/usr/local/bin/ollama");
            paths.add("/usr/bin/ollama");
            paths.add("/bin/ollama");
            paths.add("/opt/homebrew/bin/ollama");
            if (!home.isEmpty()) {
                paths.add(home + "/.local/bin/ollama");
            }
        }
        return paths;
    }

    /**
     * Transfere e instala o Ollama de acordo com o sistema operativo.
     * <p>
     * Em Windows a instalação é feita através do instalador oficial; nos
     * restantes sistemas é usado o script oficial de instalação.
     * </p>
     *
     * @return {@code true} se, no fim, o executável do Ollama estiver disponível
     */
    public static boolean installOllama() {
        try {
            if (SystemInfo.isWindows()) {
                return installOnWindows();
            }
            return installWithShellScript();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Instalação do Ollama interrompida.");
            return false;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Falha ao instalar o Ollama.", e);
            return false;
        }
    }

    /**
     * Executa a instalação em Windows: transfere o instalador e corre-o.
     *
     * @return {@code true} se o executável ficar disponível após a instalação
     * @throws IOException se a transferência ou execução falhar
     * @throws InterruptedException se o fio atual for interrompido durante a espera
     */
    private static boolean installOnWindows() throws IOException, InterruptedException {
        String tempDir = System.getenv("TEMP");
        if (tempDir == null || tempDir.isBlank()) {
            tempDir = System.getProperty("java.io.tmpdir", ".");
        }
        String installerPath = tempDir + File.separator + "OllamaSetup.exe";

        boolean downloaded = run(LONG_TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES,
                "powershell", "-NoProfile", "-Command",
                "Invoke-WebRequest -Uri 'https://ollama.com/download/OllamaSetup.exe' -OutFile '"
                        + installerPath + "'");

        if (!downloaded || !new File(installerPath).exists()) {
            LOGGER.severe("Não foi possível transferir o instalador do Ollama.");
            return false;
        }

        run(LONG_TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES, installerPath);
        return waitForExecutable();
    }

    /**
     * Executa o script oficial de instalação em sistemas Unix.
     *
     * @return {@code true} se o executável ficar disponível após a instalação
     * @throws IOException se a execução do script falhar
     * @throws InterruptedException se o fio atual for interrompido durante a espera
     */
    private static boolean installWithShellScript() throws IOException, InterruptedException {
        boolean ok = run(LONG_TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES,
                "sh", "-c", "curl -fsSL https://ollama.com/install.sh | sh");
        return ok && waitForExecutable();
    }

    /**
     * Aguarda que o executável do Ollama apareça no sistema após a instalação.
     *
     * @return {@code true} se o executável for encontrado dentro do tempo previsto
     * @throws InterruptedException se o fio atual for interrompido durante a espera
     */
    private static boolean waitForExecutable() throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (findOllamaExecutable() != null) {
                return true;
            }
            TimeUnit.SECONDS.sleep(1);
        }
        return false;
    }

    /**
     * Verifica se um modelo específico já se encontra transferido localmente.
     *
     * @param modelId o identificador do modelo (ex.: {@code llama3.2:1b})
     * @return {@code true} se o modelo estiver disponível no disco
     */
    public static boolean isModelDownloaded(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        String target = normalizeModelId(modelId);
        return getInstalledModels().stream()
                .map(OllamaService::normalizeModelId)
                .anyMatch(target::equals);
    }

    /**
     * Transfere um modelo através do comando {@code ollama pull}.
     *
     * @param modelId o identificador do modelo a transferir
     * @return {@code true} se a transferência terminar com sucesso
     */
    public static boolean downloadModel(String modelId) {
        return runOllamaCommand(LONG_TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES, "pull", modelId);
    }

    /**
     * Remove um modelo previamente transferido, através de {@code ollama rm}.
     *
     * @param modelId o identificador do modelo a remover (ex.: {@code llama3.2:1b})
     * @return {@code true} se a remoção terminar com sucesso
     */
    public static boolean uninstallModel(String modelId) {
        return runOllamaCommand(QUICK_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS, "rm", modelId);
    }

    /**
     * Executa um subcomando do Ollama, validando primeiro os pré-requisitos.
     *
     * @param timeout o tempo máximo de espera
     * @param unit a unidade do tempo máximo
     * @param arguments os argumentos a passar ao executável do Ollama
     * @return {@code true} se o comando terminar com código de saída zero
     */
    private static boolean runOllamaCommand(long timeout, TimeUnit unit, String... arguments) {
        String ollamaPath = findOllamaExecutable();
        if (ollamaPath == null) {
            LOGGER.warning("Comando ignorado: o Ollama não está instalado.");
            return false;
        }
        if (arguments.length < 2 || arguments[1] == null || arguments[1].isBlank()) {
            LOGGER.warning("Comando ignorado: identificador de modelo inválido.");
            return false;
        }

        List<String> command = new ArrayList<>();
        command.add(ollamaPath);
        command.addAll(List.of(arguments));

        try {
            return run(timeout, unit, command.toArray(new String[0]));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Comando do Ollama interrompido.");
            return false;
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Falha ao executar o comando do Ollama.", e);
            return false;
        }
    }

    /**
     * Lista os modelos instalados, analisando a saída de {@code ollama list}.
     * <p>
     * A leitura da saída é feita num fio auxiliar e limitada por
     * {@link #QUICK_TASK_TIMEOUT_SECONDS}. Sem essa separação, um
     * {@code ollama list} que não responda (motor a arrancar, daemon encravado)
     * bloquearia para sempre em {@code readLine()}, porque o tempo limite do
     * processo só seria avaliado depois de a leitura terminar.
     * </p>
     *
     * @return os nomes dos modelos locais; lista vazia se não houver nenhum,
     *         se o tempo limite for excedido ou em caso de erro
     * @see #tryGetInstalledModels()
     */
    public static List<String> getInstalledModels() {
        return tryGetInstalledModels().orElseGet(List::of);
    }

    /**
     * Lista os modelos instalados, distinguindo "nenhum modelo" de "não foi
     * possível verificar".
     * <p>
     * É esta a variante que a interface deve usar: um {@link Optional} vazio
     * significa que a verificação falhou ou excedeu o tempo limite, o que permite
     * mostrar um erro em vez de afirmar incorretamente que não há modelos
     * instalados.
     * </p>
     *
     * @return os nomes dos modelos locais (possivelmente uma lista vazia), ou
     *         {@link Optional#empty()} se a verificação não pôde ser concluída
     */
    public static Optional<List<String>> tryGetInstalledModels() {
        String ollamaPath = findOllamaExecutable();
        if (ollamaPath == null) {
            LOGGER.fine("Listagem ignorada: o Ollama não está instalado.");
            return Optional.empty();
        }

        ProcessBuilder builder = new ProcessBuilder(ollamaPath, "list");
        builder.redirectErrorStream(true);

        Process process = null;
        try {
            process = builder.start();
            return readModelNames(process);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warning("Listagem de modelos interrompida.");
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Falha ao listar os modelos do Ollama.", e);
        } finally {
            destroy(process);
        }
        return Optional.empty();
    }

    /**
     * Lê os nomes dos modelos da saída de um processo, dentro do tempo limite.
     *
     * @param process o processo de {@code ollama list} já iniciado
     * @return os nomes lidos, ou {@link Optional#empty()} se o tempo limite for excedido
     * @throws InterruptedException se o fio atual for interrompido durante a espera
     */
    private static Optional<List<String>> readModelNames(Process process) throws InterruptedException {
        List<String> models = Collections.synchronizedList(new ArrayList<>());

        Thread reader = new Thread(() -> collectModelNames(process, models), "aether-ollama-list-reader");
        reader.setDaemon(true);
        reader.start();

        long timeoutMillis = TimeUnit.SECONDS.toMillis(QUICK_TASK_TIMEOUT_SECONDS);
        reader.join(timeoutMillis);

        if (reader.isAlive()) {
            LOGGER.warning(() -> "Tempo limite de " + QUICK_TASK_TIMEOUT_SECONDS
                    + "s excedido ao listar os modelos: processo terminado.");
            process.destroyForcibly();
            reader.interrupt();
            return Optional.empty();
        }

        process.waitFor(QUICK_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        synchronized (models) {
            return Optional.of(List.copyOf(models));
        }
    }

    /**
     * Consome a saída de {@code ollama list} e acumula os nomes dos modelos.
     * <p>
     * Executado num fio auxiliar: eventuais falhas de leitura são registadas e
     * nunca propagadas, porque o chamador trata a ausência de resultados.
     * </p>
     *
     * @param process o processo cuja saída deve ser lida
     * @param models a lista onde acumular os nomes encontrados
     */
    private static void collectModelNames(Process process, List<String> models) {
        try (BufferedReader input = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {

            String line;
            boolean isHeader = true;
            while ((line = input.readLine()) != null) {
                if (isHeader) {
                    isHeader = false;
                    continue;
                }
                String name = extractModelName(line);
                if (name != null) {
                    models.add(name);
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Falha ao ler a saída de 'ollama list'.", e);
        }
    }

    /**
     * Normaliza o identificador de um modelo para efeitos de comparação.
     * <p>
     * O {@code ollama list} acrescenta a etiqueta {@code :latest} aos modelos
     * transferidos sem versão explícita (por exemplo, {@code ollama pull mistral}
     * aparece como {@code mistral:latest}). Sem esta normalização, o mesmo modelo
     * seria considerado dois modelos diferentes.
     * </p>
     *
     * @param modelId o identificador a normalizar; pode ser {@code null}
     * @return o identificador em minúsculas e sem a etiqueta {@code :latest},
     *         ou uma cadeia vazia se a entrada for {@code null} ou vazia
     */
    public static String normalizeModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "";
        }
        String normalized = modelId.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(":latest")
                ? normalized.substring(0, normalized.length() - ":latest".length())
                : normalized;
    }

    /**
     * Extrai o nome do modelo de uma linha da tabela devolvida por {@code ollama list}.
     *
     * @param line a linha a analisar
     * @return o nome do modelo, ou {@code null} se a linha estiver vazia
     */
    private static String extractModelName(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        String[] tokens = line.trim().split("\\s+");
        return tokens.length > 0 && !tokens[0].isEmpty() ? tokens[0] : null;
    }

    /**
     * Recomenda modelos compatíveis com a memória física do sistema.
     * <p>
     * A ordem de inserção é preservada: o primeiro elemento é a recomendação
     * principal apresentada por omissão na interface.
     * </p>
     *
     * @return um mapa de identificador do modelo para descrição legível
     */
    public static Map<String, String> getRecommendedModels() {
        Map<String, String> options = new LinkedHashMap<>();
        long ramGb = SystemInfo.getTotalRamGb();

        if (ramGb == SystemInfo.UNKNOWN_RAM) {
            options.put("llama3.2:1b", "Llama 3.2 (1B) — safe default");
            return options;
        }

        if (ramGb >= HIGH_MEMORY_GB) {
            options.put("llama3.2:3b", "Llama 3.2 (3B) — fast and capable (recommended)");
            options.put("mistral", "Mistral (7B) — strong at reasoning and code");
        } else if (ramGb >= MID_MEMORY_GB) {
            options.put("llama3.2:1b", "Llama 3.2 (1B) — very fast and lightweight (recommended)");
            options.put("gemma:2b", "Gemma (2B) — light and efficient");
        } else {
            options.put("qwen:0.5b", "Qwen (0.5B) — ultra light, best fit for this machine");
        }
        return options;
    }

    /**
     * Executa um processo externo e aguarda a sua conclusão.
     * <p>
     * A saída é encaminhada para os fluxos da aplicação, o que permite acompanhar
     * o progresso de transferências longas na consola.
     * </p>
     *
     * @param timeout o tempo máximo de espera
     * @param unit a unidade do tempo máximo
     * @param command o comando e respetivos argumentos
     * @return {@code true} se o processo terminar com código de saída zero
     * @throws IOException se o processo não puder ser iniciado
     * @throws InterruptedException se o fio atual for interrompido durante a espera
     */
    private static boolean run(long timeout, TimeUnit unit, String... command)
            throws IOException, InterruptedException {

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.inheritIO();

        Process process = builder.start();
        try {
            if (!process.waitFor(timeout, unit)) {
                LOGGER.warning(() -> "Tempo limite excedido para o comando: " + command[0]);
                process.destroy();
                return false;
            }
            return process.exitValue() == 0;
        } finally {
            destroy(process);
        }
    }

    /**
     * Termina um processo se este ainda estiver em execução.
     *
     * @param process o processo a terminar; ignorado se for {@code null}
     */
    private static void destroy(Process process) {
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            // Dá uma janela curta para o encerramento normal antes de forçar.
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}