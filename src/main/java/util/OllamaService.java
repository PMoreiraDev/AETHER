package util;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

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

    /** Número de tentativas (a 500ms cada) a aguardar que o servidor arranque. */
    private static final int SERVER_START_ATTEMPTS = 30;

    /**
     * Durante quanto tempo o Ollama mantém o modelo carregado em memória após
     * a última utilização. Sem isto, o valor por omissão do Ollama (5 minutos)
     * força a recarregar o modelo do disco a cada pausa mais longa na
     * conversa — para modelos de 7B-14B isso pode demorar dezenas de segundos.
     * Mantê-lo carregado durante a sessão elimina essa espera repetida.
     */
    private static final String KEEP_ALIVE = "30m";

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
        // Honour a user-configured override (executable or containing folder).
        String override = session.UserSession.getInstance().getAppSettings().getOllamaPathOverride();
        if (override != null && !override.isBlank()) {
            String resolved = resolveOllamaFromOverride(override);
            if (resolved != null) {
                return resolved;
            }
            LOGGER.fine("Ollama override inválido, a pesquisar caminhos conhecidos.");
        }

        for (String path : buildCandidatePaths()) {
            if (path != null && new File(path).canExecute()) {
                return path;
            }
        }
        LOGGER.fine("Executável do Ollama não encontrado nos caminhos conhecidos.");
        return null;
    }

    /**
     * Validates whether a given path resolves to a usable Ollama executable,
     * WITHOUT falling back to auto-detected paths. Used by the Settings screen
     * to tell the user whether their chosen override is valid.
     *
     * @param candidate the path to validate (executable or containing folder)
     * @return {@code true} if the path resolves to an executable Ollama binary
     */
    public static boolean isValidOllamaPath(String candidate) {
        return resolveOllamaFromOverride(candidate) != null;
    }

    /**
     * Resolves the Ollama executable from a user-provided override. Accepts
     * either a direct executable path or a folder containing the
     * {@code ollama} / {@code ollama.exe} binary.
     *
     * @param override the path given by the user
     * @return the resolved executable path, or {@code null} if invalid
     */
    private static String resolveOllamaFromOverride(String override) {
        if (override == null || override.isBlank()) {
            return null;
        }
        java.nio.file.Path p = java.nio.file.Paths.get(override).toAbsolutePath().normalize();

        // Direct executable path.
        if (java.nio.file.Files.isRegularFile(p) && p.toFile().canExecute()) {
            return p.toString();
        }

        // Folder containing the ollama binary.
        if (java.nio.file.Files.isDirectory(p)) {
            String binaryName = SystemInfo.isWindows() ? "ollama.exe" : "ollama";
            java.nio.file.Path binary = p.resolve(binaryName);
            if (java.nio.file.Files.isRegularFile(binary) && binary.toFile().canExecute()) {
                return binary.toString();
            }
        }
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
            if (SystemInfo.isMac()) {
                return installOnMac();
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
     * Instala o Ollama em macOS.
     * <p>
     * O script oficial de instalação ({@code install.sh}) é feito para Linux
     * e recusa-se a instalar em macOS. Por isso, em macOS é usado primeiro o
     * Homebrew, quando disponível — o método oficialmente suportado para
     * instalar o Ollama por linha de comandos neste sistema. Se o Homebrew
     * não estiver instalado ou a instalação por essa via falhar, tenta-se
     * ainda assim o script genérico como última hipótese (inofensivo se
     * falhar, já que apenas devolve {@code false}).
     * </p>
     *
     * @return {@code true} se o executável ficar disponível após a instalação
     * @throws IOException se a execução do comando falhar
     * @throws InterruptedException se o fio atual for interrompido durante a espera
     */
    private static boolean installOnMac() throws IOException, InterruptedException {
        if (isHomebrewAvailable()) {
            boolean ok = run(LONG_TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES,
                    "sh", "-c", "brew install ollama");
            if (ok && waitForExecutable()) {
                return true;
            }
            LOGGER.warning("Instalação via Homebrew falhou; a tentar o script genérico.");
        } else {
            LOGGER.info("Homebrew não encontrado; a tentar o script de instalação genérico.");
        }
        return installWithShellScript();
    }

    /**
     * Verifica se o Homebrew está disponível neste Mac.
     *
     * @return {@code true} se o executável do {@code brew} for encontrado
     */
    private static boolean isHomebrewAvailable() {
        for (String path : List.of("/opt/homebrew/bin/brew", "/usr/local/bin/brew")) {
            if (new File(path).canExecute()) {
                return true;
            }
        }
        return false;
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
     * Explica a causa mais provável de uma falha do {@link #chat}, verificando
     * pela ordem: executável instalado, servidor a responder, modelo
     * transferido.
     * <p>
     * Chamado apenas quando {@link #chat} devolve {@code null}, para dar ao
     * utilizador uma mensagem acionável em vez de um erro genérico. Cada
     * verificação repete uma chamada já feita por {@link #chat}, pelo que deve
     * ser invocado fora do fio da interface, tal como os restantes métodos
     * desta classe.
     * </p>
     *
     * @param modelId o identificador do modelo que se tentou usar
     * @return uma mensagem em inglês explicando a causa provável da falha
     */
    public static String diagnose(String modelId) {
        if (findOllamaExecutable() == null) {
            return "Ollama isn't installed on this device — no executable was found in "
                    + "the usual install locations.";
        }
        if (!ensureServerRunning()) {
            return "The Ollama server couldn't be started. Try running 'ollama serve' "
                    + "manually in a terminal and check for errors there.";
        }
        if (modelId != null && !modelId.isBlank() && !isModelDownloaded(modelId)) {
            return "The model '" + modelId + "' isn't downloaded on this device. "
                    + "Run 'ollama pull " + modelId + "' or redo the setup.";
        }
        return "The Ollama server is running and the model is installed, but the "
                + "request still failed. Check that no firewall or VPN is blocking "
                + "localhost:11434.";
    }

    /**
     * Resolve o modelo a usar de facto: mantém o modelo preferido se este já
     * estiver transferido, ou usa o primeiro modelo instalado como
     * alternativa automática quando o preferido não existe no disco.
     * <p>
     * Evita que o utilizador tenha de ir ao Terminal só porque o modelo
     * guardado nas definições da aplicação não corresponde ao que foi de
     * facto transferido — por exemplo, se escolheu outro modelo depois do
     * setup inicial, ou se removeu o modelo original entretanto.
     * </p>
     *
     * @param preferredModelId o modelo configurado nas definições da aplicação
     * @return o modelo preferido, um substituto já instalado, ou {@code null}
     *         se não houver nenhum modelo transferido neste dispositivo
     */
    public static String resolveAvailableModel(String preferredModelId) {
        if (preferredModelId != null && !preferredModelId.isBlank()
                && isModelDownloaded(preferredModelId)) {
            return preferredModelId;
        }
        List<String> installed = getInstalledModels();
        return installed.isEmpty() ? null : installed.get(0);
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
     * Transfere um modelo, reportando o progresso real da transferência.
     * <p>
     * Ao contrário de {@link #downloadModel(String)} (que corre {@code ollama
     * pull} como processo externo e só devolve um resultado no fim), este
     * método usa diretamente a API de streaming do Ollama em
     * {@code POST /api/pull}. O Ollama transfere o modelo por camadas e
     * devolve uma linha JSON por cada atualização, cada uma com o total de
     * bytes da camada atual e quantos já foram transferidos — é isso que
     * permite mostrar uma barra de progresso real em vez de um indicador
     * indeterminado.
     * </p>
     *
     * @param modelId o identificador do modelo a transferir
     * @param onProgress chamado com a fração transferida da camada atual (0.0
     *                    a 1.0) sempre que o Ollama reportar progresso; pode
     *                    ser {@code null}. Chamado a partir do fio que invocar
     *                    este método — se for a interface, o chamador deve
     *                    marshal para o fio da UI (ex.: {@code Task.updateProgress}
     *                    dentro de {@code call()} já trata disso).
     * @return {@code true} se a transferência terminar com sucesso
     */
    public static boolean downloadModel(String modelId, DoubleConsumer onProgress) {
        if (modelId == null || modelId.isBlank()) {
            return false;
        }
        if (!ensureServerRunning()) {
            LOGGER.warning("Transferência ignorada: o servidor do Ollama não está disponível.");
            return false;
        }

        try {
            String body = "{\"model\":\"" + escapeJson(modelId) + "\",\"stream\":true}";

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/pull"))
                    .timeout(java.time.Duration.ofMinutes(LONG_TASK_TIMEOUT_MINUTES))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<Stream<String>> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                LOGGER.warning(() -> "Ollama API (pull) devolveu HTTP " + response.statusCode());
                return false;
            }

            boolean[] success = {false};
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> {
                    if (line == null || line.isBlank()) {
                        return;
                    }
                    if (line.contains("\"error\"")) {
                        LOGGER.warning(() -> "Ollama devolveu um erro a transferir '" + modelId + "': " + line);
                        return;
                    }
                    if ("success".equals(extractStringField(line, "status"))) {
                        success[0] = true;
                    }
                    if (onProgress != null) {
                        Long total = extractLongField(line, "total");
                        Long completed = extractLongField(line, "completed");
                        if (total != null && total > 0 && completed != null) {
                            onProgress.accept(Math.min(1.0, (double) completed / (double) total));
                        }
                    }
                });
            }
            return success[0] || isModelDownloaded(modelId);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Falha ao transferir o modelo '" + modelId + "'.", e);
            return false;
        }
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
     * Lista os modelos instalados, consultando o endpoint HTTP {@code /api/tags}
     * do Ollama e lendo apenas o campo {@code name} de cada objeto em
     * {@code models}. Ao contrário da análise da tabela de {@code ollama list}
     * (sensível a largura de terminal, cabeçalho, stderr misturado), o endpoint
     * devolve JSON estruturado: só aparecem modelos realmente instalados,
     * nunca tokens fantasmas de outras colunas.
     * <p>
     * A leitura é limitada por {@link #QUICK_TASK_TIMEOUT_SECONDS}.
     *
     * @return os nomes dos modelos locais (possivelmente uma lista vazia), ou
     *         {@link Optional#empty()} se a verificação não pôde ser concluída
     */
    public static Optional<List<String>> tryGetInstalledModels() {
        if (!ensureServerRunning()) {
            LOGGER.fine("Listagem ignorada: o servidor do Ollama não está disponível.");
            return Optional.empty();
        }
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(QUICK_TASK_TIMEOUT_SECONDS))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/tags"))
                    .timeout(java.time.Duration.ofSeconds(QUICK_TASK_TIMEOUT_SECONDS))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warning(() -> "Ollama /api/tags devolveu HTTP " + response.statusCode());
                return Optional.empty();
            }
            List<String> models = parseModelNames(response.body());
            LOGGER.fine(() -> "Modelos instalados (HTTP /api/tags): " + models);
            return Optional.of(models);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao listar os modelos do Ollama via /api/tags.", e);
            return Optional.empty();
        }
    }

    /**
     * Analisa a resposta JSON de {@code /api/tags} e devolve os nomes dos
     * modelos instalados. Lê apenas o campo {@code name} (com fallback para
     * {@code model}) de cada objeto dentro do array {@code models}.
     * <p>
     * Ao contrário da análise da tabela de {@code ollama list}, só são aceites
     * nomes que pareçam identificadores válidos de modelo — tokens soltos de
     * outras colunas ou texto de erro nunca são interpretados como modelos.
     * É isto que elimina entradas fantasmas como um suposto modelo "as".
     *
     * @param json o corpo da resposta de {@code /api/tags}
     * @return os nomes dos modelos (lista vazia se nenhum ou inválido)
     */
    static List<String> parseModelNames(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) {
            return out;
        }
        String array = extractArrayAfterKey(json, "models");
        if (array == null) {
            return out;
        }
        for (String obj : splitTopLevelObjects(array)) {
            String raw = extractFirstStringField(obj, "name");
            if (raw == null || raw.isBlank()) {
                raw = extractFirstStringField(obj, "model");
            }
            if (raw == null || raw.isBlank()) {
                continue;
            }
            final String name = raw.trim();
            if (isValidModelName(name)) {
                out.add(name);
            } else {
                LOGGER.fine(() -> "Nome de modelo ignorado (inválido): " + name);
            }
        }
        return out;
    }

    /**
     * Localiza o conteúdo do array JSON que se segue à chave {@code "key"}.
     *
     * @param json o JSON completo
     * @param key o nome da chave cujo valor é um array
     * @return o conteúdo entre {@code [} e {@code ]}, ou {@code null}
     */
    private static String extractArrayAfterKey(String json, String key) {
        String quotedKey = "\"" + key + "\"";
        int kidx = indexOfOutsideStrings(json, quotedKey);
        if (kidx < 0) {
            return null;
        }
        int i = kidx + quotedKey.length();
        while (i < json.length()
                && (json.charAt(i) == ' ' || json.charAt(i) == ':'
                || json.charAt(i) == '\t' || json.charAt(i) == '\n'
                || json.charAt(i) == '\r')) {
            i++;
        }
        if (i >= json.length() || json.charAt(i) != '[') {
            return null;
        }
        int start = i + 1;
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        for (int j = start; j < json.length(); j++) {
            char c = json.charAt(j);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '[') depth++;
            else if (c == ']') {
                if (depth == 0) {
                    return json.substring(start, j);
                }
                depth--;
            }
        }
        return null;
    }

    /** Divide o conteúdo de um array JSON em substrings de objetos de topo. */
    private static List<String> splitTopLevelObjects(String arrayContent) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        boolean escape = false;
        int objStart = -1;
        for (int i = 0; i < arrayContent.length(); i++) {
            char c = arrayContent.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) objStart = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objStart >= 0) {
                    out.add(arrayContent.substring(objStart, i + 1));
                    objStart = -1;
                }
            }
        }
        return out;
    }

    /** Extrai o valor string do primeiro campo {@code "key":"value"} de um objeto. */
    private static String extractFirstStringField(String obj, String key) {
        String quotedKey = "\"" + key + "\"";
        int kidx = indexOfOutsideStrings(obj, quotedKey);
        if (kidx < 0) {
            return null;
        }
        int i = kidx + quotedKey.length();
        while (i < obj.length()
                && (obj.charAt(i) == ' ' || obj.charAt(i) == ':'
                || obj.charAt(i) == '\t' || obj.charAt(i) == '\n'
                || obj.charAt(i) == '\r')) {
            i++;
        }
        if (i >= obj.length() || obj.charAt(i) != '"') {
            return null;
        }
        int start = i + 1;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int j = start; j < obj.length(); j++) {
            char c = obj.charAt(j);
            if (escape) {
                escape = false;
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'r' -> sb.append('\r');
                    default -> sb.append(c);
                }
                continue;
            }
            if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Procura uma chave apenas fora de strings JSON. */
    private static int indexOfOutsideStrings(String json, String quotedKey) {
        boolean inString = false;
        boolean escape = false;
        for (int i = 0; i <= json.length() - quotedKey.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (inString) {
                if (c == '\\') escape = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') {
                if (json.startsWith(quotedKey, i)) {
                    return i;
                }
                inString = true;
            }
        }
        return -1;
    }

    /**
     * Valida se um nome parece um identificador real de modelo do Ollama
     * (ex.: {@code qwen2.5:14b}, {@code llama3.2:3b}). Rejeita tokens vazios,
     * com espaços ou com caracteres estranhos provenientes de parsing acidental.
     *
     * @param name o nome a validar
     * @return {@code true} se for um identificador plausível
     */
    static boolean isValidModelName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.matches("^[a-zA-Z0-9][a-zA-Z0-9._\\-]*(:[a-zA-Z0-9._\\-]+)?$");
    }

    /**
     * Normaliza o identificador de um modelo para efeitos de comparação.
     * <p>
     * O Ollama acrescenta a etiqueta {@code :latest} aos modelos transferidos
     * sem versão explícita (por exemplo, {@code ollama pull mistral} aparece como
     * {@code mistral:latest}). Sem esta normalização, o mesmo modelo seria
     * considerado dois modelos diferentes.
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
        options.put("qwen2.5:14b", "Qwen 2.5 (14B) — high capability local model");
        options.put("llama3.2:3b", "Llama 3.2 (3B) — fast and lightweight");
        options.put("mistral:7b", "Mistral (7B) — balanced general-purpose model");
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
     * Envia um prompt ao modelo local do Ollama e devolve a resposta.
     * <p>
     * Usa a API REST do Ollama em {@code http://localhost:11434/api/generate}.
     * O método é bloqueante e deve ser chamado fora do fio da interface.
     * </p>
     *
     * @param modelId o identificador do modelo (ex.: {@code llama3.2:1b})
     * @param prompt  o texto enviado ao modelo
     * @return a resposta do modelo, ou {@code null} se falhar
     */
    public static String chat(String modelId, String prompt) {
        if (modelId == null || modelId.isBlank() || prompt == null || prompt.isBlank()) {
            return null;
        }

        if (!ensureServerRunning()) {
            LOGGER.warning("Não foi possível contactar o modelo: o servidor do Ollama não está disponível.");
            return null;
        }

        try {
            String body = "{"
                    + "\"model\":\"" + escapeJson(modelId) + "\","
                    + "\"prompt\":\"" + escapeJson(prompt) + "\","
                    + "\"stream\":false"
                    + "}";

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/generate"))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOGGER.warning(() -> "Ollama API devolveu HTTP " + response.statusCode());
                return null;
            }

            return extractResponse(response.body());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao comunicar com o Ollama.", e);
            return null;
        }
    }

    /**
     * Extrai o campo {@code response} de um JSON devolvido pelo Ollama.
     *
     * @param json o JSON completo
     * @return o texto da resposta, ou {@code null} se não for encontrado
     */
    private static String extractResponse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        // Procura pelo campo "response":"..."
        String key = "\"response\":";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        // O valor pode começar com aspas
        if (start < json.length() && json.charAt(start) == '"') {
            start++;
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\' && i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    switch (next) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        default -> sb.append(next);
                    }
                    i++;
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * Extrai o valor em string de um campo simples {@code "chave":"valor"} de
     * uma linha JSON, sem depender de nenhuma biblioteca de parsing.
     *
     * @param json a linha JSON a analisar
     * @param key o nome do campo
     * @return o valor do campo, ou {@code null} se não existir
     */
    private static String extractStringField(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = json.indexOf('"', start);
        return end < 0 ? null : json.substring(start, end);
    }

    /**
     * Extrai o valor numérico de um campo simples {@code "chave":123} de uma
     * linha JSON, sem depender de nenhuma biblioteca de parsing.
     *
     * @param json a linha JSON a analisar
     * @param key o nome do campo
     * @return o valor do campo, ou {@code null} se não existir ou não for numérico
     */
    private static Long extractLongField(String json, String key) {
        String marker = "\"" + key + "\":";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return null;
        }
        int start = idx + marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) {
            end++;
        }
        if (end == start) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Envia um prompt ao modelo local do Ollama em modo de streaming,
     * entregando cada fragmento de texto assim que chega, em vez de esperar
     * pela resposta completa.
     * <p>
     * É esta a variante que a vista de chat deve usar: com {@code stream:
     * false} (usado em {@link #chat}), a interface fica às escuras — sem
     * qualquer sinal de progresso — até o modelo terminar de gerar a resposta
     * inteira, o que em modelos maiores (7B-14B) pode demorar dezenas de
     * segundos. Em streaming, o primeiro fragmento chega em pouco tempo e o
     * texto vai aparecendo à medida que é gerado, tal como no ChatGPT ou no
     * `ollama run` na consola — a resposta não fica mais rápida a gerar-se,
     * mas deixa de parecer bloqueada.
     * </p>
     * <p>
     * Também define {@code keep_alive} para {@link #KEEP_ALIVE}, para que o
     * Ollama mantenha o modelo carregado em memória entre mensagens em vez de
     * o libertar ao fim de 5 minutos (valor por omissão) — evitando o atraso
     * de o recarregar do disco a meio de uma conversa.
     * </p>
     *
     * @param modelId o identificador do modelo (ex.: {@code llama3.2:1b})
     * @param prompt o texto enviado ao modelo
     * @param onToken chamado com cada fragmento de texto assim que chega;
     *                chamado a partir do fio que invocar este método
     * @return {@code true} se pelo menos um fragmento tiver sido recebido
     */
    public static boolean chatStream(String modelId, String prompt, Consumer<String> onToken) {
        return chatStream(modelId, prompt, null, onToken);
    }

    /**
     * Envia um prompt ao modelo local do Ollama em modo de streaming, com um
     * prompt de sistema opcional, entregando cada fragmento de texto assim que
     * chega, em vez de esperar pela resposta completa.
     * <p>
     * O prompt de sistema ({@code system}) é usado para fornecer contexto ao
     * modelo — por exemplo, o contexto central do utilizador. Quando não
     * {@code null} nem vazio, é incluído no pedido como o campo {@code system}
     * da API do Ollama, permitindo que o modelo personalize as respostas com
     * base nessa informação.
     * </p>
     *
     * @param modelId o identificador do modelo (ex.: {@code llama3.2:1b})
     * @param prompt o texto enviado ao modelo
     * @param system o prompt de sistema (contexto); pode ser {@code null}
     * @param onToken chamado com cada fragmento de texto assim que chega;
     *                chamado a partir do fio que invocar este método
     * @return {@code true} se pelo menos um fragmento tiver sido recebido
     */
    public static boolean chatStream(String modelId, String prompt, String system, Consumer<String> onToken) {
        if (modelId == null || modelId.isBlank() || prompt == null || prompt.isBlank()) {
            return false;
        }
        if (!ensureServerRunning()) {
            LOGGER.warning("Não foi possível contactar o modelo: o servidor do Ollama não está disponível.");
            return false;
        }

        try {
            StringBuilder body = new StringBuilder();
            body.append("{");
            body.append("\"model\":\"").append(escapeJson(modelId)).append("\",");
            body.append("\"prompt\":\"").append(escapeJson(prompt)).append("\",");
            if (system != null && !system.isBlank()) {
                body.append("\"system\":\"").append(escapeJson(system)).append("\",");
            }
            body.append("\"stream\":true,");
            body.append("\"keep_alive\":\"").append(KEEP_ALIVE).append("\"");
            body.append("}");

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/generate"))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            java.net.http.HttpResponse<Stream<String>> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                LOGGER.warning(() -> "Ollama API devolveu HTTP " + response.statusCode());
                return false;
            }

            boolean[] receivedAny = {false};
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> {
                    if (line == null || line.isBlank()) {
                        return;
                    }
                    String chunk = extractResponse(line);
                    if (chunk != null && !chunk.isEmpty()) {
                        receivedAny[0] = true;
                        onToken.accept(chunk);
                    }
                });
            }
            return receivedAny[0];
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao comunicar com o Ollama (streaming).", e);
            return false;
        }
    }

    /**
     * Uma mensagem de conversa com papel (system/user/assistant) e conteúdo.
     * Usada para enviar o histórico da conversa ao endpoint /api/chat do
     * Ollama, que suporta memória entre turnos (ao contrário do /api/generate,
     * que trata cada mensagem isoladamente).
     */
    public static final class ChatMessage {
        private final String role;
        private final String content;

        public ChatMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public String getRole() {
            return role;
        }

        public String getContent() {
            return content;
        }
    }

    /**
     * Constrói o objeto JSON para uma mensagem de chat (role + content),
     * já com o conteúdo escapado.
     */
    private static String toJsonMessage(String role, String content) {
        return "{\"role\":\"" + escapeJson(role)
                + "\",\"content\":\"" + escapeJson(content) + "\"}";
    }

    /**
     * Constrói o corpo JSON para um pedido ao endpoint /api/chat do Ollama.
     * <p>
     * O prompt de sistema (se presente) é a primeira mensagem
     * ({@code role=system}), seguido do histórico de turnos user/assistant na
     * ordem correta. Visível para testes unitários (sem rede).
     *
     * @param modelId  identificador do modelo
     * @param messages histórico (user/assistant)
     * @param system   prompt de sistema; pode ser {@code null} ou vazio
     * @return o JSON do pedido
     */
    public static String buildChatRequestBody(String modelId, List<ChatMessage> messages,
                                       String system) {
        StringBuilder msgs = new StringBuilder();
        boolean first = true;
        if (system != null && !system.isBlank()) {
            msgs.append(toJsonMessage("system", system));
            first = false;
        }
        for (ChatMessage m : messages) {
            if (m == null || m.getContent() == null || m.getContent().isBlank()) {
                continue;
            }
            if (!first) {
                msgs.append(",");
            }
            msgs.append(toJsonMessage(m.getRole(), m.getContent()));
            first = false;
        }
        return "{\"model\":\"" + escapeJson(modelId) + "\","
                + "\"messages\":[" + msgs + "],"
                + "\"stream\":true,"
                + "\"keep_alive\":\"" + KEEP_ALIVE + "\"}";
    }

    /**
     * Chamada não-streaming ao /api/chat com histórico completo. Devolve a
     * resposta completa do assistente numa só string. Usada para passagens onde
     * é preciso obter uma resposta estruturada (ex.: extração de ações) em vez
     * de streaming token-a-token.
     *
     * @param modelId  identificador do modelo Ollama
     * @param messages histórico da conversa (user/assistant)
     * @param system   prompt de sistema; pode ser {@code null} ou vazio
     * @return a resposta completa, ou {@code null} se não houver resposta
     */
    public static String chatComplete(String modelId, List<ChatMessage> messages, String system) {
        return chatComplete(modelId, messages, system, false);
    }

    /**
     * Chamada não-streaming ao /api/chat com histórico completo e, opcionalmente,
     * o formato JSON forçado ({@code "format":"json"}). Quando {@code jsonFormat}
     * é {@code true}, pede-se ao modelo que devolva JSON válido — útil para a
     * extração estruturada de ações. Mesmo assim, o chamador deve validar o
     * resultado defensivamente: o modelo pode devolver JSON imperfeito.
     *
     * @param modelId    identificador do modelo Ollama
     * @param messages   histórico da conversa (user/assistant)
     * @param system     prompt de sistema; pode ser {@code null} ou vazio
     * @param jsonFormat {@code true} para forçar {@code "format":"json"}
     * @return a resposta completa, ou {@code null} se não houver resposta
     */
    /**
     * Aplica as opções de extração estruturada ao corpo do pedido: força
     * {@code stream:false}, adiciona {@code format:json} quando solicitado, e
     * injeta um bloco {@code options} com {@code num_predict} generoso.
     * <p>
     * <b>Root cause do truncamento:</b> sem o bloco {@code options} explícito,
     * o Ollama aplica o seu {@code num_predict} por defeito (historicamente
     * 128 tokens em muitas versões), o que truncava o JSON após 1-2 entidades —
     * por isso só as primeiras (geralmente PERSON) chegavam ao parser.
     * </p>
     */
    public static String applyExtractionOptions(String body, boolean jsonFormat) {
        if (body == null || body.isEmpty()) return body;
        body = body.replace("\"stream\":true", "\"stream\":false");
        if (jsonFormat && !body.contains("\"format\":\"json\"")) {
            body = body.substring(0, body.length() - 1) + ",\"format\":\"json\"}";
        }
        String options = jsonFormat
                ? ",\"options\":{\"num_predict\":8192,\"temperature\":0.1}"
                : ",\"options\":{\"num_predict\":4096}";
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1) + options + "}";
        }
        return body;
    }

    public static String chatComplete(String modelId, List<ChatMessage> messages,
                                      String system, boolean jsonFormat) {
        return chatComplete(modelId, messages, system, jsonFormat, 5);
    }

    /**
     * Variante com timeout configurável (em minutos). Útil para a análise de
     * notas, que pode ser cancelada pelo utilizador e deve falhar mais cedo.
     */
    public static String chatComplete(String modelId, List<ChatMessage> messages,
                                      String system, boolean jsonFormat, long timeoutMinutes) {
        if (modelId == null || modelId.isBlank() || messages == null || messages.isEmpty()) {
            return null;
        }
        if (!ensureServerRunning()) {
            LOGGER.warning("Não foi possível contactar o modelo: o servidor do Ollama não está disponível.");
            return null;
        }
        try {
            String body = buildChatRequestBody(modelId, messages, system);
            body = applyExtractionOptions(body, jsonFormat);
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/chat"))
                    .timeout(java.time.Duration.ofMinutes(timeoutMinutes))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOGGER.warning(() -> "Ollama API (chat complete) devolveu HTTP " + response.statusCode());
                return null;
            }
            return extractChatContent(response.body());
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                LOGGER.info("Análise cancelada pelo utilizador (thread interrompida).");
            } else {
                LOGGER.log(Level.WARNING, "Falha ao comunicar com o Ollama (chat complete).", e);
            }
            return null;
        }
    }

    /**
     * Envia uma conversa completa (com histórico) ao Ollama via /api/chat e
     * faz streaming dos tokens da resposta.
     * <p>
     * Ao contrário do {@link #chatStream(String, String, String, Consumer)}
     * (que usa /api/generate e trata cada mensagem isoladamente), este método
     * envia o histórico completo de turnos user/assistant, pelo que a IA
     * consegue recordar o que foi dito antes na mesma conversa.
     * <p>
     * O prompt de sistema é enviado como a primeira mensagem com
     * {@code role=system}, seguido das mensagens de histórico. O histórico é
     * da responsabilidade do chamador (deve ser limitado a um número razoável
     * de turnos para não inflar o contexto).
     *
     * @param modelId  identificador do modelo Ollama
     * @param messages histórico da conversa (user/assistant), sem o system
     * @param system   prompt de sistema (instruções + contexto); pode ser
     *                 {@code null} ou vazio
     * @param onToken  callback chamado para cada token recebido
     * @return {@code true} se foi recebida pelo menos alguma resposta
     */
    public static boolean chatStream(String modelId, List<ChatMessage> messages,
                                     String system, Consumer<String> onToken) {
        if (modelId == null || modelId.isBlank()
                || messages == null || messages.isEmpty()) {
            return false;
        }
        if (!ensureServerRunning()) {
            LOGGER.warning("Não foi possível contactar o modelo: o servidor do Ollama não está disponível.");
            return false;
        }

        try {
            String body = buildChatRequestBody(modelId, messages, system);

            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .build();

            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/chat"))
                    .timeout(java.time.Duration.ofMinutes(5))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();

            java.net.http.HttpResponse<Stream<String>> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                LOGGER.warning(() -> "Ollama API (chat) devolveu HTTP " + response.statusCode());
                return false;
            }

            boolean[] receivedAny = {false};
            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> {
                    if (line == null || line.isBlank()) {
                        return;
                    }
                    String chunk = extractChatContent(line);
                    if (chunk != null && !chunk.isEmpty()) {
                        receivedAny[0] = true;
                        onToken.accept(chunk);
                    }
                });
            }
            return receivedAny[0];
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Falha ao comunicar com o Ollama (chat).", e);
            return false;
        }
    }

    /**
     * Extrai o conteúdo ({@code message.content}) de uma linha NDJSON do
     * endpoint /api/chat. Cada linha tem o formato:
     * <pre>{"message":{"role":"assistant","content":"..."},"done":false}</pre>
     *
     * @param json uma linha NDJSON do /api/chat
     * @return o texto do conteúdo, ou {@code null} se não existir
     */
    private static String extractChatContent(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String key = "\"content\":\"";
        int idx = json.indexOf(key);
        if (idx < 0) {
            return null;
        }
        int start = idx + key.length();
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
                i++;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escapa aspas e barras para usar numa string JSON.
     *
     * @param text o texto a escapar
     * @return o texto escapado
     */
    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "");
    }

    /**
     * Garante que o servidor do Ollama está em execução, iniciando-o em
     * segundo plano se necessário.
     * <p>
     * A instalação do Ollama não arranca automaticamente o servidor em todos
     * os sistemas (por exemplo, quando não corre como serviço/menu bar app),
     * pelo que o AETHER tem de o iniciar explicitamente com {@code ollama
     * serve} antes de conversar com o modelo. O processo é lançado de forma
     * independente (a sua saída é descartada) e o método aguarda até
     * {@link #SERVER_START_ATTEMPTS} tentativas que o servidor responda.
     * </p>
     *
     * @return {@code true} se o servidor já estiver, ou passar a ficar, acessível
     */
    public static boolean ensureServerRunning() {
        if (isServerRunning()) {
            return true;
        }

        String ollamaPath = findOllamaExecutable();
        if (ollamaPath == null) {
            LOGGER.warning("Não é possível iniciar o servidor: o Ollama não está instalado.");
            return false;
        }

        try {
            ProcessBuilder builder = new ProcessBuilder(ollamaPath, "serve");
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Falha ao iniciar o servidor do Ollama.", e);
            return false;
        }

        return waitForServerReady();
    }

    /**
     * Aguarda repetidamente que o servidor do Ollama comece a responder.
     *
     * @return {@code true} se o servidor responder dentro do tempo previsto
     */
    private static boolean waitForServerReady() {
        for (int attempt = 0; attempt < SERVER_START_ATTEMPTS; attempt++) {
            if (isServerRunning()) {
                return true;
            }
            try {
                TimeUnit.MILLISECONDS.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return isServerRunning();
    }

    /**
     * Verifica se o servidor do Ollama está acessível.
     *
     * @return {@code true} se o Ollama estiver a responder
     */
    public static boolean isServerRunning() {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(3))
                    .build();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://localhost:11434/api/tags"))
                    .timeout(java.time.Duration.ofSeconds(3))
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
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