package repository;

import domain.UserProfile;
import java.util.Optional;

/**
 * Defines persistence operations for the user's profile.
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public interface UserProfileRepository {

    /**
     * Loads the saved profile.
     *
     * @return the saved profile, or empty when no profile exists
     * @throws PersistenceException if the profile cannot be read
     */
    Optional<UserProfile> find();

    /**
     * Saves the supplied profile.
     *
     * @param profile the profile to save
     * @throws PersistenceException if the profile cannot be saved
     */
    void save(UserProfile profile);

    /**
     * Deletes the saved profile.
     *
     * @throws PersistenceException if the profile cannot be deleted
     */
    void delete();
}
