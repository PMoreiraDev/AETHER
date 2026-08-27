package persistence;

import domain.UserProfile;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import repository.PersistenceException;
import repository.UserProfileRepository;

/**
 * SQLite implementation of {@link UserProfileRepository}.
 *
 * @author Paulo Moreira
 * @version 1.1
 */
public class SqliteUserProfileRepository implements UserProfileRepository {

    /**
     * Creates the repository and ensures its table exists.
     */
    public SqliteUserProfileRepository() {
        createTable();
    }

    /**
     * Creates the user profile table if necessary.
     */
    private void createTable() {
        String sql = """
                CREATE TABLE IF NOT EXISTS user_profile (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    schema_version INTEGER NOT NULL DEFAULT 1,
                    full_name TEXT NOT NULL DEFAULT '',
                    preferred_name TEXT NOT NULL DEFAULT '',
                    birth_date TEXT,
                    occupations TEXT NOT NULL DEFAULT '',
                    about_you TEXT NOT NULL DEFAULT ''
                )
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not create user_profile table.", e);
        }
    }

    @Override
    public Optional<UserProfile> find() {
        String sql = """
                SELECT full_name, preferred_name, birth_date, occupations, about_you
                FROM user_profile
                WHERE id = 1
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {

            if (!resultSet.next()) {
                return Optional.empty();
            }

            UserProfile profile = new UserProfile();
            profile.setFullName(resultSet.getString("full_name"));
            profile.setPreferredName(resultSet.getString("preferred_name"));

            String birthDate = resultSet.getString("birth_date");
            profile.setBirthDate(birthDate == null || birthDate.isBlank() ? null : LocalDate.parse(birthDate));

            String occupations = resultSet.getString("occupations");
            profile.setOccupations(parseOccupations(occupations));
            profile.setAboutYou(resultSet.getString("about_you"));

            return Optional.of(profile);

        } catch (SQLException e) {
            throw new PersistenceException("Could not load the user profile.", e);
        }
    }

    @Override
    public void save(UserProfile profile) {
        if (profile == null) {
            throw new PersistenceException("Cannot save a null user profile.", new IllegalArgumentException("profile"));
        }

        String sql = """
                INSERT INTO user_profile
                    (id, schema_version, full_name, preferred_name, birth_date, occupations, about_you)
                VALUES (1, 1, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    schema_version = excluded.schema_version,
                    full_name = excluded.full_name,
                    preferred_name = excluded.preferred_name,
                    birth_date = excluded.birth_date,
                    occupations = excluded.occupations,
                    about_you = excluded.about_you
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nullToEmpty(profile.getFullName()));
            statement.setString(2, nullToEmpty(profile.getPreferredName()));
            statement.setString(3, profile.getBirthDate() == null ? null : profile.getBirthDate().toString());
            statement.setString(4, String.join("\u001F", profile.getOccupations()));
            statement.setString(5, nullToEmpty(profile.getAboutYou()));
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not save the user profile.", e);
        }
    }

    @Override
    public void delete() {
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM user_profile WHERE id = 1")) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new PersistenceException("Could not delete the user profile.", e);
        }
    }

    private List<String> parseOccupations(String value) {
        if (value == null || value.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(value.split("\u001F", -1)));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}