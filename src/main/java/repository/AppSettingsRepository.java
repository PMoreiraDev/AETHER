package repository;

import domain.AppSettings;

import java.util.Optional;

/**
 * Repository responsible for storing application settings.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public interface AppSettingsRepository {

    /**
     * Loads the application settings.
     *
     * @return the stored settings, or empty if none exist
     */
    Optional<AppSettings> load();

    /**
     * Saves the application settings.
     *
     * @param settings settings to save
     * @throws PersistenceException if the settings cannot be saved
     */
    void save(AppSettings settings) throws PersistenceException;
}