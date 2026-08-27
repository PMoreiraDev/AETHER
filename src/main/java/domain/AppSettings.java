package domain;

/**
 * Application settings stored locally by AETHER.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class AppSettings {

    /** Identifier of the currently selected Ollama model. */
    private String activeModelId;

    /** Indicates whether the onboarding process has been completed. */
    private boolean onboardingCompleted;

    /**
     * Creates empty application settings.
     */
    public AppSettings() {
        this.activeModelId = "";
        this.onboardingCompleted = false;
    }

    /**
     * Returns the currently selected Ollama model.
     *
     * @return the active model identifier
     */
    public String getActiveModelId() {
        return activeModelId;
    }

    /**
     * Changes the currently selected Ollama model.
     *
     * @param activeModelId the model identifier
     */
    public void setActiveModelId(String activeModelId) {
        this.activeModelId = activeModelId == null ? "" : activeModelId;
    }

    /**
     * Indicates whether onboarding has been completed.
     *
     * @return true if onboarding is completed
     */
    public boolean isOnboardingCompleted() {
        return onboardingCompleted;
    }

    /**
     * Changes the onboarding completion state.
     *
     * @param onboardingCompleted true when onboarding is completed
     */
    public void setOnboardingCompleted(boolean onboardingCompleted) {
        this.onboardingCompleted = onboardingCompleted;
    }
}