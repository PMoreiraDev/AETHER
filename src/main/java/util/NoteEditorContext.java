package util;

import domain.entities.Note;

/**
 * Ponte simples entre o shell (que carrega vistas no contentArea) e o
 * {@link controller.NoteEditorController}. Permite passar a nota a editar sem
 * acoplar o controlador do editor ao {@code DashboardController}.
 *
 * <p>O fluxo é: o emissor (header "+ Add note" ou um cartão de nota) cria ou
 * seleciona uma {@link Note}, coloca-a aqui via {@link #setPending(Note)}, e
 * pede ao shell para carregar a vista do editor. O editor consome a nota
 * pendente em {@link controller.NoteEditorController#initialize}.</p>
 *
 * @author AETHER
 */
public final class NoteEditorContext {

    private static volatile Note pending;

    private NoteEditorContext() {
        // Classe utilitária.
    }

    /** Define a nota pendente a editar. */
    public static void setPending(Note note) {
        pending = note;
    }

    /**
     * Consome a nota pendente (devolve-a e limpa a referência). Se não houver
     * nota pendente, devolve uma nota nova vazia.
     */
    public static Note consumePending() {
        Note note = pending;
        pending = null;
        return note != null ? note : new Note();
    }
}
