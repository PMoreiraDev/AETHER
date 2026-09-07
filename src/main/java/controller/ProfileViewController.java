package controller;

import java.net.URL;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import session.UserSession;
import util.AvatarImages;
import util.I18n;
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

    @FXML private Label suggestedUpdatesLabel;

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
    @FXML private Label infoLocationLabel;
    @FXML private Label infoStudiesLabel;
    @FXML private Label infoExperienceLabel;
    @FXML private Label infoSkillsLabel;
    @FXML private Label infoInterestsLabel;
    @FXML private Label infoObjectivesLabel;
    @FXML private Label infoPreferencesLabel;
    @FXML private Label infoProjectsLabel;
    @FXML private Label infoWorkStyleLabel;
    @FXML private Label infoSummaryLabel;
    @FXML private VBox suggestedUpdatesContainer;

    // ------------------------------------------------------------------
    // FXML — Achievements
    // ------------------------------------------------------------------

    @FXML private HBox achievementsBox;

    // ------------------------------------------------------------------
    // FXML — AI Context
    // ------------------------------------------------------------------

    @FXML private TextArea aiContextInput;
    @FXML private Label contextStatusLabel;
    @FXML private HBox aiContextToolbar;
    @FXML private ToggleButton aiContextPreviewBtn;
    @FXML private VBox aiContextPreview;
    private boolean aiContextPreviewMode = false;

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
        loadSuggestedUpdates();
        // Refresca as sugestões pendentes quando o vault/propostas mudam
        // (ex.: nova proposta criada no chat, ou aceitação noutro lado),
        // para o perfil ficar sempre coerente com o badge de notificações.
        util.VaultRefreshBus.subscribe(event -> javafx.application.Platform.runLater(this::loadSuggestedUpdates));
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
        // FONTE ÚNICA (spec #11): as sugestões pendentes vêm do ProposalStore —
        // o mesmo estado que alimenta o sino e a aprovação — e não do campo
        // legado suggestedUpdates do UserProfile (que podia divergir).
        List<ai.ProposalStore.Snapshot> pending = ai.ProposalStore.getInstance().pending();
        if (!pending.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (ai.ProposalStore.Snapshot s : pending) {
                sb.append(s.entityType).append(':')
                        .append(s.identifier == null || s.identifier.isBlank() ? "profile" : s.identifier)
                        .append(" — ")
                        .append(s.reason == null || s.reason.isBlank() ? "(no reason given)" : s.reason)
                        .append('\n');
            }
            suggestedUpdatesLabel.setText(sb.toString().trim());
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
        infoLocationLabel.setText(displayOrDash(profile.getLocation()));

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
        statProjectsLabel.setText(String.valueOf(persistence.VaultManager.listProjects().size()));
        statTasksLabel.setText(String.valueOf(persistence.VaultManager.listTasks().size()));
        statEventsLabel.setText(String.valueOf(persistence.VaultManager.listEvents().size()));

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
    // Suggested profile updates
    // ------------------------------------------------------------------

    /**
     * Carrega TODAS as propostas pendentes persistidas (perfil, pessoas,
     * projetos, tarefas, eventos, relações) — não só as de perfil. Assim, o
     * badge de notificações (que conta todas) corresponde ao que aparece aqui,
     * e cada proposta é apresentada com um título que diz o que é.
     * Sem contadores falsos: se não há propostas, mostra o estado vazio.
     */
    private void loadSuggestedUpdates() {
        suggestedUpdatesContainer.getChildren().clear();
        List<ai.ProposalStore.Snapshot> pending = ai.ProposalStore.getInstance().pending();
        if (pending.isEmpty()) {
            Label empty = new Label(I18n.tr("ai.proposal.suggested.none"));
            empty.getStyleClass().add("profile-suggested-hint");
            suggestedUpdatesContainer.getChildren().add(empty);
            return;
        }
        for (ai.ProposalStore.Snapshot snapshot : pending) {
            suggestedUpdatesContainer.getChildren().add(buildSuggestedUpdate(snapshot));
        }
    }

    private VBox buildSuggestedUpdate(ai.ProposalStore.Snapshot snapshot) {
        VBox card = new VBox(8);
        card.getStyleClass().add("ai-proposal-card");
        // Título descritivo: diz de que é a proposta (pessoa/projeto/tarefa/...).
        Label title = new Label(I18n.tr(proposalTitleKey(snapshot)));
        title.getStyleClass().add("ai-proposal-title");
        card.getChildren().add(title);
        // Eyebrow com o tipo de ação + identificador alvo, para contexto rápido.
        String eyebrowText = snapshot.actionType;
        if (snapshot.identifier != null && !snapshot.identifier.isBlank()) {
            eyebrowText += " · " + snapshot.identifier;
        }
        Label eyebrow = new Label(eyebrowText);
        eyebrow.getStyleClass().add("ai-proposal-eyebrow");
        card.getChildren().add(eyebrow);
        snapshot.fields.forEach((field, value) -> {
            // Para atualizações de perfil, se o campo já tiver um valor
            // confirmado diferente, mostra Current vs Suggested (spec #11 —
            // alterações contraditórias não sobrescrevem silenciosamente).
            String current = "PROFILE".equalsIgnoreCase(snapshot.entityType)
                    ? currentProfileValue(field) : null;
            if (current != null && !current.isBlank()
                    && !current.trim().equalsIgnoreCase(value == null ? "" : value.trim())) {
                VBox change = new VBox(2);
                Label cur = new Label(I18n.tr("ai.proposal.current") + ": " + current);
                cur.getStyleClass().add("ai-proposal-meta-label");
                cur.setWrapText(true);
                Label sug = new Label(I18n.tr("ai.proposal.suggested") + ": " + (value == null ? "" : value));
                sug.getStyleClass().add("ai-proposal-meta-value");
                sug.setWrapText(true);
                change.getChildren().addAll(cur, sug);
                card.getChildren().add(change);
            } else {
                HBox row = new HBox(8);
                Label key = new Label(fieldLabel(field) + ":");
                key.getStyleClass().add("ai-proposal-meta-label");
                Label val = new Label(value == null ? "" : value);
                val.getStyleClass().add("ai-proposal-meta-value");
                val.setWrapText(true);
                row.getChildren().addAll(key, val);
                card.getChildren().add(row);
            }
        });
        if (snapshot.sourceContext != null && !snapshot.sourceContext.isBlank()) {
            Label source = new Label(I18n.tr("ai.proposal.source") + ": " + snapshot.sourceContext);
            source.getStyleClass().add("ai-proposal-subtitle");
            source.setWrapText(true);
            card.getChildren().add(source);
        }
        HBox actions = new HBox(8);
        Button accept = new Button(I18n.tr("ai.proposal.accept"));
        Button reject = new Button(I18n.tr("ai.proposal.reject"));
        accept.getStyleClass().addAll("proposal-btn", "proposal-accept-btn");
        reject.getStyleClass().addAll("proposal-btn", "proposal-reject-btn");
        actions.getChildren().addAll(accept, reject);
        card.getChildren().add(actions);

        accept.setOnAction(e -> {
            accept.setDisable(true); reject.setDisable(true);
            ai.AiActionProposal proposal = proposalFromSnapshot(snapshot);
            boolean ok = ai.ApprovalFlow.executeApproved(proposal).success();
            if (ok) {
                ai.ProposalStore.getInstance().markAccepted(snapshot.id);
                loadPersonalInfo();
                loadProfileSummary();
                card.getChildren().add(new Label("✓ " + I18n.tr("ai.profile.suggested.saved")));
            } else {
                accept.setDisable(false); reject.setDisable(false);
                card.getChildren().add(new Label(I18n.tr("ai.profile.suggested.failed")));
                ai.ProposalStore.getInstance().markFailed(snapshot.id);
                util.VaultRefreshBus.publish(util.VaultRefreshBus.ChangeType.UPDATED, "PROFILE");
            }
            loadSuggestedUpdates();
            if (DashboardController.getActive() != null) DashboardController.getActive().refreshUserProfileCard();
        });
        reject.setOnAction(e -> {
            ai.ProposalStore.getInstance().markRejected(snapshot.id);
            util.VaultRefreshBus.publish(util.VaultRefreshBus.ChangeType.UPDATED, "PROFILE");
            loadSuggestedUpdates();
        });
        return card;
    }

    /**
     * Devolve a chave I18n do título adequado ao tipo de proposta, para cada
     * proposta "dizer de que é".
     */
    private static String proposalTitleKey(ai.ProposalStore.Snapshot s) {
        String e = s.entityType == null ? "" : s.entityType.toUpperCase(Locale.ROOT);
        String a = s.actionType == null ? "" : s.actionType.toUpperCase(Locale.ROOT);
        String ent = switch (e) {
            case "PERSON" -> "person";
            case "PROJECT" -> "project";
            case "TASK" -> "task";
            case "EVENT" -> "event";
            case "NOTE" -> "note";
            default -> "profile";
        };
        switch (a) {
            case "LINK_ENTITIES": return "ai.proposal.title.link";
            case "UNLINK_ENTITIES": return "ai.proposal.title.unlink";
            case "DELETE_ENTITY": return "ai.proposal.title.delete";
            case "CREATE_ENTITY": return "ai.proposal.title." + ent + ".create";
            case "UPDATE_ENTITY":
                return "PROFILE".equals(e)
                        ? "ai.proposal.title.profile"
                        : "ai.proposal.title." + ent + ".update";
            default: return "ai.proposal.title.profile";
        }
    }

    /** Traduz chaves técnicas de campos do perfil em etiquetas legíveis. */
    private static String fieldLabel(String field) {
        return util.ProfileFieldLabels.label(field);
    }

    /**
     * Devolve o valor atual confirmado de um campo do perfil, para mostrar
     * "Current vs Suggested" nas Suggested Updates de perfil (spec #11).
     */
    private static String currentProfileValue(String field) {
        UserProfile p = UserSession.getInstance().getUserProfile();
        if (field == null) return "";
        return switch (field) {
            case "fullName" -> p.getFullName(); case "preferredName" -> p.getPreferredName();
            case "about" -> p.getAbout(); case "occupation" -> p.getOccupation();
            case "studies" -> p.getStudies(); case "experience" -> p.getExperience();
            case "skills" -> p.getSkills(); case "interests" -> p.getInterests();
            case "objectives" -> p.getObjectives(); case "preferences" -> p.getPreferences();
            case "projects" -> p.getProjects(); case "workStyle" -> p.getWorkStyle();
            case "location" -> p.getLocation(); case "aiContext" -> p.getAiContext();
            case "inferredContext" -> p.getInferredContext();
            case "profileSummary" -> p.getProfileSummary();
            default -> "";
        };
    }

    private ai.AiActionProposal proposalFromSnapshot(ai.ProposalStore.Snapshot s) {
        // entityId: "profile" para atualizações de perfil; para entidades usa-se
        // o identificador alvo quando faz sentido (updates/links), ou vazio
        // para criações (o ActionExecutor lê o nome/título dos fields).
        String entityId = "PROFILE".equalsIgnoreCase(s.entityType) ? "profile"
                : (s.identifier == null ? "" : s.identifier);
        ai.AiActionProposal.Builder b = new ai.AiActionProposal.Builder()
                .actionType(ai.AiActionType.valueOf(s.actionType))
                .entityType(domain.entities.ContextEntityType.valueOf(s.entityType))
                .entityId(entityId)
                .reason(s.reason)
                .confidence(s.confidence)
                .sourceContext(s.sourceContext)
                .semanticClassification(s.semanticClassification);
        try { b.trustLevel(ai.TrustLevel.valueOf(s.trustLevel)); } catch (Exception ignored) { }
        if (s.fields != null) s.fields.forEach(b::field);
        if (s.relationships != null) b.relationships(s.relationships);
        return b.build();
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
        // Garante que o conteúdo editado está visível no TextArea antes de guardar.
        setAiContextPreviewMode(false);
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
            // Sincroniza a pasta User/ do vault (spec #12) — merge-read-write.
            try {
                persistence.UserVaultSync.syncProfile(profile, "MANUAL_EDIT");
            } catch (RuntimeException rex) {
                LOGGER.warning("Falha ao sincronizar vault após edição manual: " + rex.getMessage());
            }
            util.VaultRefreshBus.publish(util.VaultRefreshBus.ChangeType.UPDATED, "PROFILE");
        } else {
            contextStatusLabel.setText("Failed to save");
            contextStatusLabel.getStyleClass().setAll("profile-context-status", "profile-context-error");
            LOGGER.warning("Failed to save AI Context.");
        }
    }

    // ------------------------------------------------------------------
    // Markdown editor — mesma experiência das Notes (spec #12, #13)
    // ------------------------------------------------------------------

    /** Alterna entre modo edição e preview, reutilizando NoteMarkdownRenderer. */
    @FXML
    private void ctxTogglePreview() {
        setAiContextPreviewMode(!aiContextPreviewMode);
    }

    private void setAiContextPreviewMode(boolean preview) {
        this.aiContextPreviewMode = preview;
        if (aiContextInput != null) {
            aiContextInput.setManaged(!preview);
            aiContextInput.setVisible(!preview);
        }
        if (aiContextPreview != null) {
            aiContextPreview.setManaged(preview);
            aiContextPreview.setVisible(preview);
            if (preview) {
                aiContextPreview.getChildren().clear();
                String text = aiContextInput == null ? "" : aiContextInput.getText();
                util.NoteMarkdownRenderer.renderInto(aiContextPreview, text == null ? "" : text);
            }
        }
        if (aiContextPreviewBtn != null) aiContextPreviewBtn.setSelected(preview);
        // A toolbar só faz sentido em modo edição (igual ao NoteEditorController).
        if (aiContextToolbar != null) {
            aiContextToolbar.setManaged(!preview);
            aiContextToolbar.setVisible(!preview);
        }
    }

    @FXML private void ctxInsertBold() { ctxWrap("**", "**", "bold text"); }
    @FXML private void ctxInsertItalic() { ctxWrap("*", "*", "italic text"); }
    @FXML private void ctxInsertCode() { ctxWrap("`", "`", "code"); }
    @FXML private void ctxInsertH1() { ctxPrefix("# ", "Heading 1"); }
    @FXML private void ctxInsertH2() { ctxPrefix("## ", "Heading 2"); }
    @FXML private void ctxInsertBullet() { ctxPrefix("- ", "List item"); }
    @FXML private void ctxInsertNumbered() { ctxPrefix("1. ", "List item"); }
    @FXML private void ctxInsertQuote() { ctxPrefix("> ", "Quote"); }
    @FXML private void ctxInsertLink() { ctxWrap("[", "](https://)", "link text"); }

    private void ctxWrap(String before, String after, String placeholder) {
        if (aiContextInput == null) return;
        String sel = aiContextInput.getSelectedText();
        if (sel != null && !sel.isEmpty()) {
            int start = aiContextInput.getSelection().getStart();
            int end = aiContextInput.getSelection().getEnd();
            aiContextInput.replaceText(start, end, before + sel + after);
            aiContextInput.selectRange(start + before.length(), start + before.length() + sel.length());
        } else {
            int caret = aiContextInput.getCaretPosition();
            aiContextInput.insertText(caret, before + placeholder + after);
            aiContextInput.selectRange(caret + before.length(), caret + before.length() + placeholder.length());
        }
        aiContextInput.requestFocus();
    }

    private void ctxPrefix(String prefix, String placeholder) {
        if (aiContextInput == null) return;
        String text = aiContextInput.getText();
        int caret = aiContextInput.getCaretPosition();
        int lineStart = 0;
        for (int i = caret - 1; i >= 0; i--) {
            if (text.charAt(i) == '\n') { lineStart = i + 1; break; }
        }
        aiContextInput.insertText(lineStart, prefix);
        aiContextInput.requestFocus();
    }
}
