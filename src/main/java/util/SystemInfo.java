package util;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Deteção de características do sistema anfitrião usadas pelo AETHER.
 * <p>
 * Centraliza a leitura de hardware (memória física e sistema operativo) num
 * único local, para que a interface e os serviços partilhem exatamente a mesma
 * informação em vez de a calcularem separadamente.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class SystemInfo {

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(SystemInfo.class.getName());

    /** Número de bytes num gibibyte, usado para converter a memória física. */
    private static final long BYTES_PER_GIB = 1024L * 1024L * 1024L;

    /** Valor devolvido quando a memória física não pode ser determinada. */
    public static final long UNKNOWN_RAM = -1L;

    /**
     * Construtor privado: esta é uma classe utilitária e não deve ser instanciada.
     */
    private SystemInfo() {
        // Classe utilitária.
    }

    /**
     * Devolve a memória física total do sistema, em gibibytes (GB).
     * <p>
     * A leitura usa a extensão {@code com.sun.management.OperatingSystemMXBean},
     * disponível no OpenJDK e no Oracle JDK. Em JVMs que não a exponham, o
     * método devolve {@link #UNKNOWN_RAM} em vez de falhar.
     * </p>
     *
     * @return a RAM total em GB, ou {@link #UNKNOWN_RAM} se não for possível determinar
     */
    public static long getTotalRamGb() {
        try {
            OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOsBean) {
                return sunOsBean.getTotalMemorySize() / BYTES_PER_GIB;
            }
            LOGGER.warning("A JVM não expõe a memória física total do sistema.");
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "Falha ao ler a memória física do sistema.", e);
        }
        return UNKNOWN_RAM;
    }

    /**
     * Indica se a aplicação está a correr em Windows.
     *
     * @return {@code true} se o sistema operativo for Windows
     */
    public static boolean isWindows() {
        return getOsName().contains("win");
    }

    /**
     * Indica se a aplicação está a correr em macOS.
     *
     * @return {@code true} se o sistema operativo for macOS
     */
    public static boolean isMac() {
        String os = getOsName();
        return os.contains("mac") || os.contains("darwin");
    }

    /**
     * Devolve o nome do sistema operativo em minúsculas.
     *
     * @return o nome do sistema operativo, ou string vazia se indisponível
     */
    private static String getOsName() {
        String os = System.getProperty("os.name");
        return os == null ? "" : os.toLowerCase();
    }
}
