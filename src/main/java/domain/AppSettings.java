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

    /** Override path for the AETHER vault folder, or empty if using the default. */
    private String vaultPathOverride = "";

    /** Override path for the Ollama executable or folder, or empty if auto-detected. */
    private String ollamaPathOverride = "";

    /**
     * Creates empty application settings.
     */
    public AppSettings() {
        this.activeModelId = "";
        this.onboardingCompleted = false;
        this.vaultPathOverride = "";
        this.ollamaPathOverride = "";
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

    /**
     * Returns the override path for the AETHER vault folder.
     *
     * @return the vault override path, or empty string if not set
     */
    public String getVaultPathOverride() {
        return vaultPathOverride == null ? "" : vaultPathOverride;
    }

    /**
     * Changes the override path for the AETHER vault folder.
     * An empty or blank value resets to the default location.
     *
     * @param vaultPathOverride the vault folder path
     */
    public void setVaultPathOverride(String vaultPathOverride) {
        this.vaultPathOverride = vaultPathOverride == null ? "" : vaultPathOverride;
    }

    /**
     * Returns the override path for the Ollama executable or folder.
     *
     * @return the Ollama override path, or empty string if not set
     */
    public String getOllamaPathOverride() {
        return ollamaPathOverride == null ? "" : ollamaPathOverride;
    }

    /**
     * Changes the override path for the Ollama executable or folder.
     * An empty or blank value resets to auto-detection.
     *
     * @param ollamaPathOverride the Ollama path
     */
    public void setOllamaPathOverride(String ollamaPathOverride) {
        this.ollamaPathOverride = ollamaPathOverride == null ? "" : ollamaPathOverride;
    }
}