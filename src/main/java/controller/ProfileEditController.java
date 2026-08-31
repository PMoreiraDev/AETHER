package controller;

import domain.UserProfile;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import session.UserSession;
import persistence.AetherPaths;
import util.AvatarImages;

/**
 * Controlador do modal de edição de perfil ({@code profile_edit.fxml}).
 * <p>
 * Carregado por {@link ProfileViewController#handleEditProfile()} como um
 * Stage modal transparent. O controlador recebe o {@link UserProfile} atual,
 * permite editá-lo visualmente, e ao gravar atualiza o perfil na sessão,
 * persiste na base de dados e notifica a vista de perfil e o dashboard.
 * </p>
 *
 * @author Paulo Moreira
 * @version 1.2
 */
public class ProfileEditController {

    /** Registo de eventos desta classe. */
    private static final Logger LOGGER = Logger.getLogger(ProfileEditController.class.getName());

    // FXML — Header
    @FXML private Button closeButton;

    // FXML — Photo column
    @FXML private Circle avatarGlow;
    @FXML private Circle avatarCircle;
    @FXML private ImageView avatarImageView;
    @FXML private Label avatarInitials;
    @FXML private Button cameraButton;

    // FXML — Info fields
    @FXML private TextField fullNameField;
    @FXML private TextField preferredNameField;
    @FXML private TextField birthdayField;
    @FXML private TextField occupationsField;
    @FXML private TextArea aboutField;

    /** Perfil que está a ser editado (mesma instância da sessão). */
    private UserProfile profile;

    /** Caminho da foto selecionada (ou "" para remover). */
    private String selectedPhotoPath;

    /** Callback chamado quando o perfil é gravado com sucesso. */
    private Consumer<Void> onSaveCallback;

    /** Stage do modal (para fechar). */
    private Stage stage;

    /**
     * Inicializa o controlador. Chamado automaticamente pelo FXMLLoader.
     */
    @FXML
    private void initialize() {
        // Clip circular no ImageView para que a foto apareça redonda
        avatarImageView.setClip(new Circle(0, 0, 58));
    }

    /**
     * Define os dados iniciais do modal.
     *
     * @param profile o perfil do utilizador a editar
     * @param stage o stage do modal
     * @param onSaveCallback callback a chamar após gravação bem-sucedida
     */
    public void setData(UserProfile profile, Stage stage, Consumer<Void> onSaveCallback) {
        this.profile = profile;
        this.stage = stage;
        this.onSaveCallback = onSaveCallback;
        this.selectedPhotoPath = profile.getProfilePhotoPath() == null ? "" : profile.getProfilePhotoPath();

        // Preencher campos
        fullNameField.setText(profile.getFullName());
        preferredNameField.setText(profile.getPreferredName());
        birthdayField.setText(profile.getBirthDate() == null
                ? "" : profile.getBirthDate().format(DateTimeFormatter.ofPattern("d/M/yyyy")));
        occupationsField.setText(String.join(", ", profile.getOccupations()));
        aboutField.setText(profile.getAbout());

        // Avatar inicial
        updateAvatarPreview();
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    /**
     * Fecha o modal sem gravar.
     */
    @FXML
    private void handleClose() {
        if (stage != null) {
            stage.close();
        }
    }

    /**
     * Fecha o modal sem gravar (alias de handleClose).
     */
    @FXML
    private void handleCancel() {
        handleClose();
    }

    /**
     * Abre o seletor de ficheiros para escolher uma foto de perfil.
     */
    @FXML
    private void handleChoosePhoto() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Profile Photo");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                "Image files", "*.png", "*.jpg", "*.jpeg", "*.gif", "*.webp"));
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) {
            selectedPhotoPath = copyProfilePhoto(selected);
            updateAvatarPreview();
        }
    }

    /**
     * Remove a foto de perfil.
     */
    @FXML
    private void handleRemovePhoto() {
        selectedPhotoPath = "";
        updateAvatarPreview();
    }

    /**
     * Grava as alterações no perfil do utilizador.
     */
    @FXML
    private void handleSave() {
        // Validação
        if (fullNameField.getText().isBlank() || preferredNameField.getText().isBlank()) {
            LOGGER.warning("Save blocked: name and preferred name are required");
            return;
        }

        LocalDate parsedBirthday = parseBirthday(birthdayField.getText());
        if (!birthdayField.getText().isBlank() && parsedBirthday == null) {
            LOGGER.warning("Save blocked: invalid birthday format");
            return;
        }

        // Atualizar o perfil (mesma instância da sessão)
        List<String> roles = new ArrayList<>();
        for (String role : occupationsField.getText().split(",")) {
            if (!role.isBlank()) {
                roles.add(role.trim());
            }
        }

        profile.setFullName(fullNameField.getText().trim());
        profile.setPreferredName(preferredNameField.getText().trim());
        profile.setBirthDate(parsedBirthday);
        profile.setOccupations(roles);
        profile.setAboutYou(aboutField.getText().trim());
        profile.setProfilePhotoPath(selectedPhotoPath);

        // Gravar
        boolean saved = UserSession.getInstance().saveUserProfile();
        if (saved) {
            LOGGER.info("Profile updated from edit modal for: " + profile.getFullName());
            if (onSaveCallback != null) {
                onSaveCallback.accept(null);
            }
            if (stage != null) {
                stage.close();
            }
        } else {
            LOGGER.warning("Failed to save profile from edit modal");
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Atualiza a pré-visualização do avatar no modal.
     */
    private void updateAvatarPreview() {
        String displayName = profile.getFullName();
        if (displayName == null || displayName.isBlank()) {
            displayName = "AETHER User";
        }
        avatarInitials.setText(extractInitials(displayName));

        boolean loaded = AvatarImages.applyCenteredCircularImage(avatarImageView, selectedPhotoPath, 116);
        avatarImageView.setVisible(loaded);
        avatarInitials.setVisible(!loaded);
    }

    /**
     * Extrai as iniciais de um nome completo.
     */
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

    /**
     * Copia a foto selecionada para o diretório de dados do AETHER.
     */
    private String copyProfilePhoto(File source) {
        try {
            Path directory = AetherPaths.dataDirectory().resolve("profile");
            Files.createDirectories(directory);
            String extension = "png";
            String name = source.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                extension = name.substring(dot + 1).toLowerCase();
            }
            Path target = directory.resolve("avatar." + extension);
            Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toAbsolutePath().normalize().toString();
        } catch (IOException | RuntimeException e) {
            LOGGER.warning("Could not copy profile photo: " + e.getMessage());
            return "";
        }
    }

    /**
     * Faz o parse da data de aniversário no formato d/M/yyyy.
     */
    private LocalDate parseBirthday(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d/M/yyyy");
            LocalDate date = LocalDate.parse(raw.trim().replaceAll("\\s*/\\s*", "/"), formatter);
            return date.isBefore(LocalDate.now()) || date.equals(LocalDate.now()) ? date : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
