package controller;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.logging.Logger;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import session.UserSession;
import util.AvatarImages;
import domain.UserProfile;

/**
 * Controlador da vista de perfil ({@code profile_view.fxml}), carregada
 * dentro do {@code contentArea} do shell principal.
 * <p>
 * Apresenta o resumo do utilizador (avatar, nome, ocupações, bio), estatísticas,
 * atividade recente, informações pessoais detalhadas, conquistas e ações rápidas.
 * Todos os dados vêm de {@link UserSession#getUserProfile()} — não há valores
 * hardcoded.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.0
 */
public class ProfileViewController implements Initializable {

    /**
     * Cria o controlador. Instanciado pelo {@link javafx.fxml.FXMLLoader}.
     */
    public ProfileViewController() {
        // Construtor por omissão explícito.
    }

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(ProfileViewController.class.getName());

    /** Formato para apresentação da data de nascimento (ex.: 12 Apr 2006). */
    private static final DateTimeFormatter BIRTHDAY_DISPLAY =
            DateTimeFormatter.ofPattern("d MMM yyyy");

    // ------------------------------------------------------------------
    // FXML — Profile Summary
    // ------------------------------------------------------------------

    @FXML private Label profileInitialsLabel;
    @FXML private ImageView profileAvatarImageView;
    @FXML private Label profileNameLabel;
    @FXML private Label profileRolesLabel;
    @FXML private Label profileBioLabel;
    @FXML private HBox profileSkillsBox;
    @FXML private Label statProjectsLabel;
    @FXML private Label statTasksLabel;
    @FXML private Label statEventsLabel;
    @FXML private Label statYearsLabel;

    // ------------------------------------------------------------------
    // FXML — Recent Activity
    // ------------------------------------------------------------------

    @FXML private VBox activityContainer;

    // ------------------------------------------------------------------
    // FXML — Personal Information
    // ------------------------------------------------------------------

    @FXML private Label infoFullNameLabel;
    @FXML private Label infoPreferredNameLabel;
    @FXML private Label infoBirthdayLabel;
    @FXML private Label infoOccupationsLabel;
    @FXML private Label infoAboutLabel;
    @FXML private Label infoStudiesLabel;
    @FXML private Label infoExperienceLabel;
    @FXML private Label infoSkillsLabel;
    @FXML private Label infoInterestsLabel;
    @FXML private Label infoObjectivesLabel;
    @FXML private Label infoPreferencesLabel;
    @FXML private Label infoProjectsLabel;
    @FXML private Label infoWorkStyleLabel;
    @FXML private Label infoSummaryLabel;
    @FXML private Label suggestedUpdatesLabel;

    // ------------------------------------------------------------------
    // FXML — Achievements
    // ------------------------------------------------------------------

    @FXML private HBox achievementsBox;

    // ------------------------------------------------------------------
    // FXML — AI Context
    // ------------------------------------------------------------------

    @FXML private TextArea aiContextInput;
    @FXML private Label contextStatusLabel;

    // ------------------------------------------------------------------
    // Inicialização
    // ------------------------------------------------------------------

    /**
     * Inicializa a vista de perfil, carregando todos os dados reais do
     * utilizador a partir da sessão.
     *
     * @param location o URL do FXML carregado
     * @param resources o pacote de recursos de localização
     */
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        loadProfileSummary();
        loadPersonalInfo();
        loadStats();
        loadActivity();
        loadAchievements();
        loadAiContext();
    }

    // ------------------------------------------------------------------
    // Profile Summary
    // ------------------------------------------------------------------

    /**
     * Carrega o resumo do perfil: avatar (iniciais), nome, papéis e bio.
     */
    private void loadProfileSummary() {
        UserProfile profile = UserSession.getInstance().getUserProfile();

        String fullName = profile.getFullName();
        if (fullName == null || fullName.isBlank()) {
            fullName = "AETHER User";
        }

        String displayName = profile.getPreferredName();
        if (displayName == null || displayName.isBlank()) {
            displayName = fullName;
        }

        profileNameLabel.setText(displayName);
        profileInitialsLabel.setText(extractInitials(fullName));
        loadProfilePhoto(profile);

        // Roles: occupations joined with bullet separator
        List<String> occupations = profile.getOccupations();
        if (occupations != null && !occupations.isEmpty()) {
            StringBuilder roles = new StringBuilder();
            for (int i = 0; i < occupations.size(); i++) {
                if (i > 0) {
                    roles.append(" • ");
                }
                roles.append(occupations.get(i));
            }
            profileRolesLabel.setText(roles.toString());
        } else {
            profileRolesLabel.setText("No roles set");
        }

        // Bio
        String about = profile.getAbout();
        if (about != null && !about.isBlank()) {
            profileBioLabel.setText(about);
            profileBioLabel.setVisible(true);
            profileBioLabel.setManaged(true);
        } else {
            profileBioLabel.setText("");
            profileBioLabel.setVisible(false);
            profileBioLabel.setManaged(false);
        }

        // Skills: display from structured profile data
        profileSkillsBox.getChildren().clear();
        String skillsText = profile.getSkills();
        if (skillsText != null && !skillsText.isBlank()) {
            String[] skills = skillsText.split(",");
            for (String skill : skills) {
                String trimmed = skill.trim();
                if (!trimmed.isEmpty()) {
                    Label badge = new Label(trimmed);
                    badge.getStyleClass().add("dash-badge-pill");
                    profileSkillsBox.getChildren().add(badge);
                }
            }
            if (profileSkillsBox.getChildren().isEmpty()) {
                Label noSkills = new Label("No skills added yet.");
                noSkills.getStyleClass().add("profile-empty-hint");
                profileSkillsBox.getChildren().add(noSkills);
            }
        } else {
            Label noSkills = new Label("No skills added yet.");
            noSkills.getStyleClass().add("profile-empty-hint");
            profileSkillsBox.getChildren().add(noSkills);
        }

        LOGGER.info("Profile view loaded for: " + displayName);

        // Display AI-suggested profile updates (if any)
        String suggestedUpdates = profile.getSuggestedUpdates();
        if (suggestedUpdates != null && !suggestedUpdates.isBlank()) {
            suggestedUpdatesLabel.setText(suggestedUpdates);
            suggestedUpdatesLabel.getStyleClass().removeAll("profile-suggested-hint");
            suggestedUpdatesLabel.getStyleClass().add("profile-suggested-text");
        } else {
            suggestedUpdatesLabel.setText("No pending suggestions. AETHER will suggest updates here when it learns new things about you.");
        }
    }

    /**
     * Extrai as iniciais de um nome completo.
     *
     * @param fullName o nome completo do utilizador
     * @return as iniciais (1 a 2 caracteres), maiúsculas
     */
    /** Loads the persisted profile photo, falling back to initials when absent or invalid. */
    private void loadProfilePhoto(UserProfile profile) {
        boolean loaded = AvatarImages.applyCenteredCircularImage(
                profileAvatarImageView, profile.getProfilePhotoPath(), 76);
        if (loaded) {
            profileAvatarImageView.setClip(new Circle(38, 38, 38));
            profileInitialsLabel.setVisible(false);
        } else {
            showInitialsAvatar();
        }
    }

    /** Shows the generated initials avatar. */
    private void showInitialsAvatar() {
        profileAvatarImageView.setImage(null);
        profileAvatarImageView.setClip(null);
        profileAvatarImageView.setVisible(false);
        profileInitialsLabel.setVisible(true);
    }

    private String extractInitials(String fullName) {
        String[] parts = fullName.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return "AU";
        }
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].charAt(0) + "" + parts[parts.length - 1].charAt(0)).toUpperCase();
    }

    // ------------------------------------------------------------------
    // Personal Information
    // ------------------------------------------------------------------

    /**
     * Preenche o cartão de informações pessoais com os dados do perfil.
     */
    private void loadPersonalInfo() {
        UserProfile profile = UserSession.getInstance().getUserProfile();

        // Full Name
        String fullName = profile.getFullName();
        infoFullNameLabel.setText(fullName != null && !fullName.isBlank() ? fullName : "—");

        // Preferred Name
        String preferredName = profile.getPreferredName();
        infoPreferredNameLabel.setText(preferredName != null && !preferredName.isBlank() ? preferredName : "—");

        // Birthday
        LocalDate birthDate = profile.getBirthDate();
        if (birthDate != null) {
            infoBirthdayLabel.setText(birthDate.format(BIRTHDAY_DISPLAY));
        } else {
            infoBirthdayLabel.setText("—");
        }

        // Occupations
        List<String> occupations = profile.getOccupations();
        if (occupations != null && !occupations.isEmpty()) {
            infoOccupationsLabel.setText(String.join(", ", occupations));
        } else {
            infoOccupationsLabel.setText("—");
        }

        // About
        String about = profile.getAbout();
        infoAboutLabel.setText(about != null && !about.isBlank() ? about : "—");

        // Structured fields
        infoStudiesLabel.setText(displayOrDash(profile.getStudies()));
        infoExperienceLabel.setText(displayOrDash(profile.getExperience()));
        infoSkillsLabel.setText(displayOrDash(profile.getSkills()));
        infoInterestsLabel.setText(displayOrDash(profile.getInterests()));
        infoObjectivesLabel.setText(displayOrDash(profile.getObjectives()));
        infoPreferencesLabel.setText(displayOrDash(profile.getPreferences()));
        infoProjectsLabel.setText(displayOrDash(profile.getProjects()));
        infoWorkStyleLabel.setText(displayOrDash(profile.getWorkStyle()));
        infoSummaryLabel.setText(displayOrDash(profile.getProfileSummary()));
    }

    /**
     * Devolve o valor ou um travessão se vazio.
     */
    private String displayOrDash(String value) {
        return (value != null && !value.isBlank()) ? value : "—";
    }

    // ------------------------------------------------------------------
    // Stats
    // ------------------------------------------------------------------

    /**
     * Carrega as estatísticas do utilizador.
     * <p>
     * Atualmente não existem repositórios de projetos, tarefas ou eventos.
     * Mostra zeros até que essas entidades estejam implementadas.
     * </p>
     */
    private void loadStats() {
        statProjectsLabel.setText("0");
        statTasksLabel.setText("0");
        statEventsLabel.setText("0");

        // Years: approximate from birthday if available
        UserProfile profile = UserSession.getInstance().getUserProfile();
        LocalDate birthDate = profile.getBirthDate();
        if (birthDate != null) {
            int years = LocalDate.now().getYear() - birthDate.getYear();
            statYearsLabel.setText(String.valueOf(Math.max(0, years)));
        } else {
            statYearsLabel.setText("0");
        }
    }

    // ------------------------------------------------------------------
    // Recent Activity
    // ------------------------------------------------------------------

    /**
     * Carrega a atividade recente do utilizador.
     * <p>
     * Sem repositório de atividade ainda — mostra estado vazio.
     * </p>
     */
    private void loadActivity() {
        activityContainer.getChildren().clear();
        Label empty = new Label("No activity yet.");
        empty.getStyleClass().add("profile-empty-hint");
        activityContainer.getChildren().add(empty);
    }

    // ------------------------------------------------------------------
    // Achievements
    // ------------------------------------------------------------------

    /**
     * Carrega as conquistas do utilizador.
     * <p>
     * Sem sistema de conquistas ainda — mostra estado vazio.
     * </p>
     */
    private void loadAchievements() {
        achievementsBox.getChildren().clear();
        Label empty = new Label("No achievements yet.");
        empty.getStyleClass().add("profile-empty-hint");
        achievementsBox.getChildren().add(empty);
    }

    // ------------------------------------------------------------------
    // Handlers — Quick Actions
    // ------------------------------------------------------------------

    /**
     * Handler do botão "Edit Profile".
     * <p>
     * Abre um modal FXML dedicado ({@code profile_edit.fxml}) com design
     * premium AETHER: header, duas colunas (foto + informações), card de
     * informação IA e footer com botões Cancel/Save.
     * </p>
     */
    @FXML
    private void handleEditProfile() {
        UserSession session = UserSession.getInstance();
        UserProfile profile = session.getUserProfile();

        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(
                    getClass().getResource("/FXML/profile_edit.fxml"));
            loader.load();
            ProfileEditController editController = loader.getController();

            Stage editStage = new Stage();
            editStage.initStyle(StageStyle.TRANSPARENT);
            editStage.initModality(Modality.WINDOW_MODAL);
            if (profileNameLabel.getScene() != null && profileNameLabel.getScene().getWindow() != null) {
                editStage.initOwner(profileNameLabel.getScene().getWindow());
            }
            editStage.setTitle("Edit Profile");

            javafx.scene.Parent root = loader.getRoot();
            root.getStylesheets().add(getClass().getResource("/Style.css").toExternalForm());
            javafx.scene.Scene scene = new javafx.scene.Scene(root);
            scene.setFill(Color.TRANSPARENT);
            editStage.setScene(scene);

            editController.setData(profile, editStage, v -> {
                // Após gravar com sucesso: atualizar todas as vistas
                loadProfileSummary();
                loadPersonalInfo();
                loadStats();
                loadAiContext();
                if (DashboardController.getActive() != null) {
                    DashboardController.getActive().refreshUserProfileCard();
                }
                contextStatusLabel.setText("Profile updated");
                contextStatusLabel.getStyleClass().setAll("profile-context-status", "profile-context-saved");
            });

            editStage.showAndWait();

        } catch (java.io.IOException e) {
            LOGGER.severe("Could not load profile_edit.fxml: " + e.getMessage());
            contextStatusLabel.setText("Could not open edit dialog");
            contextStatusLabel.getStyleClass().setAll("profile-context-status", "profile-context-error");
        }
    }

    /**
     * Handler do botão "Change Password".
     */
    @FXML
    private void handleChangePassword() {
        LOGGER.info("Action: Change Password");
    }

    /**
     * Handler do botão "Manage Notifications".
     */
    @FXML
    private void handleManageNotifications() {
        LOGGER.info("Action: Manage Notifications");
    }

    /**
     * Handler do botão "Privacy Settings".
     */
    @FXML
    private void handlePrivacySettings() {
        LOGGER.info("Action: Privacy Settings");
    }

    // ------------------------------------------------------------------
    // AI Context
    // ------------------------------------------------------------------

    /**
     * Carrega o contexto central de IA do utilizador a partir da sessão.
     * <p>
     * Se o contexto estiver vazio, mostra um prompt a convidar o utilizador
     * a preenchê-lo. Caso contrário, carrega o conteúdo guardado.
     * </p>
     */
    private void loadAiContext() {
        UserProfile profile = UserSession.getInstance().getUserProfile();
        String context = profile.getAiContext();
        aiContextInput.setText(context == null ? "" : context);
        contextStatusLabel.setText("Profile details are included automatically in AETHER's context");
        contextStatusLabel.getStyleClass().setAll("profile-context-status");
    }

    /**
     * Handler do botão "Save Context".
     * <p>
     * Guarda o contexto central de IA no perfil do utilizador e persiste na
     * base de dados. Este conteúdo torna-se a fonte única de verdade para o
     * sistema de contexto do AETHER: o Context Graph do Dashboard e o agente
     * de IA leem ambos a partir deste mesmo dado.
     * </p>
     */
    @FXML
    private void handleSaveContext() {
        String context = aiContextInput.getText();
        if (context == null) {
            context = "";
        }

        UserProfile profile = UserSession.getInstance().getUserProfile();
        profile.setAiContext(context.trim());

        boolean saved = UserSession.getInstance().saveUserProfile();
        if (saved) {
            contextStatusLabel.setText("Context saved");
            contextStatusLabel.getStyleClass().setAll("profile-context-status", "profile-context-saved");
            LOGGER.info("AI Context saved for user: " + profile.getFullName());
        } else {
            contextStatusLabel.setText("Failed to save");
            contextStatusLabel.getStyleClass().setAll("profile-context-status", "profile-context-error");
            LOGGER.warning("Failed to save AI Context.");
        }
    }
}
