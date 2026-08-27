package domain;

import java.util.Arrays;

/**
 * Categorias de ocupação predefinidas do AETHER.
 * <p>
 * Cada constante tem um nome de exibição amigável, usado tanto na interface como
 * na conversão de texto livre através de {@link #fromDisplayName(String)}. A
 * constante {@link #OTHER} funciona como categoria de recurso para ocupações
 * personalizadas escritas pelo utilizador.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public enum Occupation {

    /** Estudante. */
    STUDENT("Student"),

    /** Programador ou engenheiro de software. */
    DEVELOPER("Developer"),

    /** Designer. */
    DESIGNER("Designer"),

    /** Empreendedor. */
    ENTREPRENEUR("Entrepreneur"),

    /** Criador de conteúdos. */
    CREATOR("Content Creator"),

    /** Qualquer ocupação não coberta pelas categorias anteriores. */
    OTHER("Other");

    /** Nome apresentado ao utilizador na interface. */
    private final String displayName;

    /**
     * Cria uma categoria de ocupação.
     *
     * @param displayName o nome a apresentar na interface
     */
    Occupation(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Devolve o nome de exibição da ocupação.
     *
     * @return o nome apresentado na interface
     */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Converte texto livre na constante correspondente.
     * <p>
     * A comparação ignora maiúsculas/minúsculas e espaços nas extremidades.
     * Texto vazio, nulo ou sem correspondência resulta em {@link #OTHER}.
     * </p>
     *
     * @param text o texto a converter; pode ser {@code null} ou vazio
     * @return a constante correspondente, ou {@link #OTHER} se não houver correspondência
     */
    public static Occupation fromDisplayName(String text) {
        if (text == null || text.isBlank()) {
            return OTHER;
        }
        return Arrays.stream(values())
                .filter(occupation -> occupation.displayName.equalsIgnoreCase(text.trim()))
                .findFirst()
                .orElse(OTHER);
    }

    /**
     * Devolve o nome de exibição como representação textual da constante.
     *
     * @return o nome de exibição
     */
    @Override
    public String toString() {
        return displayName;
    }
}
