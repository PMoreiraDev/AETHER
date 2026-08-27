package session;

import domain.UserProfile;

/**
 * Gestor de sessão Singleton.
 * <p>
 * Mantém os dados globais em memória enquanto a aplicação está em execução.
 * O perfil do utilizador é acessível através de {@link #getUserProfile()}.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public final class UserSession {

    /** Instância única da sessão, criada de forma preguiçosa. */
    private static volatile UserSession instance;

    /** Perfil do utilizador ativo nesta sessão; nunca {@code null}. */
    private UserProfile userProfile;

    /**
     * Construtor privado para impedir instanciação externa.
     */
    private UserSession() {
        this.userProfile = new UserProfile();
    }

    /**
     * Devolve a instância única da sessão. Cria-a se ainda não existir.
     *
     * @return a instância única de UserSession
     */
    public static synchronized UserSession getInstance() {
        if (instance == null) {
            instance = new UserSession();
        }
        return instance;
    }

    /**
     * Devolve o perfil de utilizador da sessão atual.
     *
     * @return o perfil de utilizador (nunca {@code null})
     */
    public UserProfile getUserProfile() {
        return userProfile;
    }

    /**
     * Substitui o perfil da sessão por um novo perfil vazio.
     * <p>
     * Usado quando o utilizador escolhe ignorar o passo de perfil no onboarding.
     * </p>
     */
    public void resetUserProfile() {
        this.userProfile = new UserProfile();
    }
}