package session;

import domain.UserProfile;

/**
 * Gestor de sessão Singleton.
 * Mantém os dados globais em memória enquanto a aplicação está em execução.
 */
public class UserSession {

    // Instância única estática
    private static UserSession instance;

    // Dados da sessão atual
    private UserProfile userProfile;

    // Construtor privado para impedir instanciação externa
    private UserSession() {
        this.userProfile = new UserProfile();
    }

    /**
     * Devolve a instância única da sessão. Cria-a se ainda não existir.
     */
    public static synchronized UserSession getInstance() {
        if (instance == null) {
            instance = new UserSession();
        }
        return instance;
    }

    public UserProfile getUserProfile() {
        return userProfile;
    }

    /**
     * Limpa os dados do perfil (chamado quando o utilizador faz "Skip").
     */
    public void resetUserProfile() {
        this.userProfile = new UserProfile();
    }
}