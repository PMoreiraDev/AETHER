package domain;

import java.util.Arrays;

/**
 * Enumeração com as categorias de ocupação predefinidas do AETHER.
 */
public enum Occupation {
    STUDENT("Student"),
    DEVELOPER("Developer"),
    DESIGNER("Designer"),
    ENTREPRENEUR("Entrepreneur"),
    CREATOR("Content Creator"),
    OTHER("Other");

    private final String displayName;

    Occupation(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Converte o texto de um botão/tag na respetiva constante do Enum.
     * Se a tag for personalizada pelo utilizador, devolve OTHER.
     */
    public static Occupation fromDisplayName(String text) {
        if (text == null || text.isBlank()) {
            return OTHER;
        }
        return Arrays.stream(Occupation.values())
                .filter(occ -> occ.displayName.equalsIgnoreCase(text.trim()))
                .findFirst()
                .orElse(OTHER);
    }

    @Override
    public String toString() {
        return displayName;
    }
}