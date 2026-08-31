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
        migrateAddAiContext();
        migrateAddProfilePhotoPath();
        migrateAddStructuredFields();
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

    /**
     * Adiciona a coluna {@code ai_context} à tabela existente se ainda não
     * existir. Esta migração é idempotente: pode correr quantas vezes for
     * necessária sem erro.
     */
    private void migrateAddAiContext() {
        String checkColumn = """
                PRAGMA table_info(user_profile)
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(checkColumn);
             ResultSet resultSet = statement.executeQuery()) {

            boolean columnExists = false;
            while (resultSet.next()) {
                if ("ai_context".equals(resultSet.getString("name"))) {
                    columnExists = true;
                    break;
                }
            }

            if (!columnExists) {
                String alterSql = "ALTER TABLE user_profile ADD COLUMN ai_context TEXT NOT NULL DEFAULT ''";
                try (PreparedStatement alter = connection.prepareStatement(alterSql)) {
                    alter.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException("Could not migrate user_profile table to add ai_context.", e);
        }
    }

    /** Adds the local profile-photo path column to existing databases. */
    private void migrateAddProfilePhotoPath() {
        String checkColumn = "PRAGMA table_info(user_profile)";
        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(checkColumn);
             ResultSet resultSet = statement.executeQuery()) {

            boolean exists = false;
            while (resultSet.next()) {
                if ("profile_photo_path".equals(resultSet.getString("name"))) {
                    exists = true;
                    break;
                }
            }

            if (!exists) {
                try (PreparedStatement alter = connection.prepareStatement(
                        "ALTER TABLE user_profile ADD COLUMN profile_photo_path TEXT NOT NULL DEFAULT ''")) {
                    alter.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException(
                    "Could not migrate user_profile table to add profile_photo_path.", e);
        }
    }

    /**
     * Adds the structured profile fields as new columns to existing databases.
     * This migration is idempotent: it can run any number of times without error.
     */
    private void migrateAddStructuredFields() {
        String[] newColumns = {
            "studies", "experience", "skills", "interests", "objectives",
            "preferences", "projects", "work_style", "profile_summary",
            "inferred_context", "suggested_updates"
        };

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(user_profile)");
             ResultSet resultSet = statement.executeQuery()) {

            // Collect existing column names
            java.util.Set<String> existingColumns = new java.util.HashSet<>();
            while (resultSet.next()) {
                existingColumns.add(resultSet.getString("name"));
            }

            // Add each missing column
            for (String column : newColumns) {
                if (!existingColumns.contains(column)) {
                    String alterSql = "ALTER TABLE user_profile ADD COLUMN "
                            + column + " TEXT NOT NULL DEFAULT ''";
                    try (PreparedStatement alter = connection.prepareStatement(alterSql)) {
                        alter.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            throw new PersistenceException(
                    "Could not migrate user_profile table to add structured fields.", e);
        }
    }

    @Override
    public Optional<UserProfile> find() {
        String sql = """
                SELECT full_name, preferred_name, birth_date, occupations, about_you, ai_context, profile_photo_path,
                       studies, experience, skills, interests, objectives, preferences, projects, work_style,
                       profile_summary, inferred_context, suggested_updates
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
            profile.setAiContext(resultSet.getString("ai_context"));
            profile.setProfilePhotoPath(resultSet.getString("profile_photo_path"));
            profile.setStudies(resultSet.getString("studies"));
            profile.setExperience(resultSet.getString("experience"));
            profile.setSkills(resultSet.getString("skills"));
            profile.setInterests(resultSet.getString("interests"));
            profile.setObjectives(resultSet.getString("objectives"));
            profile.setPreferences(resultSet.getString("preferences"));
            profile.setProjects(resultSet.getString("projects"));
            profile.setWorkStyle(resultSet.getString("work_style"));
            profile.setProfileSummary(resultSet.getString("profile_summary"));
            profile.setInferredContext(resultSet.getString("inferred_context"));
            profile.setSuggestedUpdates(resultSet.getString("suggested_updates"));

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
                    (id, schema_version, full_name, preferred_name, birth_date, occupations, about_you, ai_context, profile_photo_path,
                     studies, experience, skills, interests, objectives, preferences, projects, work_style,
                     profile_summary, inferred_context, suggested_updates)
                VALUES (1, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET
                    schema_version = excluded.schema_version,
                    full_name = excluded.full_name,
                    preferred_name = excluded.preferred_name,
                    birth_date = excluded.birth_date,
                    occupations = excluded.occupations,
                    about_you = excluded.about_you,
                    ai_context = excluded.ai_context,
                    profile_photo_path = excluded.profile_photo_path,
                    studies = excluded.studies,
                    experience = excluded.experience,
                    skills = excluded.skills,
                    interests = excluded.interests,
                    objectives = excluded.objectives,
                    preferences = excluded.preferences,
                    projects = excluded.projects,
                    work_style = excluded.work_style,
                    profile_summary = excluded.profile_summary,
                    inferred_context = excluded.inferred_context,
                    suggested_updates = excluded.suggested_updates
                """;

        try (Connection connection = Database.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nullToEmpty(profile.getFullName()));
            statement.setString(2, nullToEmpty(profile.getPreferredName()));
            statement.setString(3, profile.getBirthDate() == null ? null : profile.getBirthDate().toString());
            statement.setString(4, String.join("\u001F", profile.getOccupations()));
            statement.setString(5, nullToEmpty(profile.getAboutYou()));
            statement.setString(6, nullToEmpty(profile.getAiContext()));
            statement.setString(7, nullToEmpty(profile.getProfilePhotoPath()));
            statement.setString(8, nullToEmpty(profile.getStudies()));
            statement.setString(9, nullToEmpty(profile.getExperience()));
            statement.setString(10, nullToEmpty(profile.getSkills()));
            statement.setString(11, nullToEmpty(profile.getInterests()));
            statement.setString(12, nullToEmpty(profile.getObjectives()));
            statement.setString(13, nullToEmpty(profile.getPreferences()));
            statement.setString(14, nullToEmpty(profile.getProjects()));
            statement.setString(15, nullToEmpty(profile.getWorkStyle()));
            statement.setString(16, nullToEmpty(profile.getProfileSummary()));
            statement.setString(17, nullToEmpty(profile.getInferredContext()));
            statement.setString(18, nullToEmpty(profile.getSuggestedUpdates()));
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