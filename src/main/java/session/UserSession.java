package session;

import domain.AppSettings;
import domain.UserProfile;
import persistence.SqliteAppSettingsRepository;
import persistence.SqliteUserProfileRepository;
import repository.AppSettingsRepository;
import repository.UserProfileRepository;

/**
 * Manages the current AETHER user session.
 *
 * @author Paulo Moreira
 * @version 2.1
 */
public final class UserSession {

    /** Singleton instance. */
    private static volatile UserSession instance;

    /** Current user profile. */
    private UserProfile userProfile;

    /** Application settings. */
    private AppSettings appSettings;

    /** Repository responsible for application settings. */
    private final AppSettingsRepository appSettingsRepository;

    /** Repository responsible for the user profile. */
    private final UserProfileRepository userProfileRepository;

    /**
     * Creates a new user session, loading any stored profile and settings.
     */
    private UserSession() {
        this.appSettingsRepository = new SqliteAppSettingsRepository();
        this.userProfileRepository = new SqliteUserProfileRepository();

        this.userProfile = userProfileRepository.find().orElseGet(UserProfile::new);
        this.appSettings = appSettingsRepository.load().orElseGet(AppSettings::new);
    }

    /**
     * Returns the singleton session.
     *
     * @return current user session
     */
    public static synchronized UserSession getInstance() {
        if (instance == null) {
            instance = new UserSession();
        }
        return instance;
    }

    /**
     * Returns the current user profile.
     *
     * @return current user profile
     */
    public UserProfile getUserProfile() {
        return userProfile;
    }

    /**
     * Replaces the current user profile.
     *
     * @param userProfile new user profile
     */
    public void setUserProfile(UserProfile userProfile) {
        this.userProfile = userProfile;
    }

    /**
     * Returns the application settings.
     *
     * @return application settings
     */
    public AppSettings getAppSettings() {
        return appSettings;
    }

    /**
     * Saves the current application settings.
     *
     * @return true if saving succeeded
     */
    public boolean saveAppSettings() {
        try {
            appSettingsRepository.save(appSettings);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Saves the current user profile.
     *
     * @return true if saving succeeded
     */
    public boolean saveUserProfile() {
        try {
            userProfileRepository.save(userProfile);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Resets the application settings in memory.
     */
    public void resetAppSettings() {
        this.appSettings = new AppSettings();
    }

    /**
     * Resets the user profile in memory.
     */
    public void resetUserProfile() {
        this.userProfile = new UserProfile();
    }
}