package ai;

import domain.entities.ContextEntityType;

import java.util.ArrayList;
import java.util.List;

/**
 * ActionValidator — valida propostas de ação da IA antes de serem apresentadas
 * ou executadas.
 * <p>
 * Garante invariáveis de segurança fundamentais do AETHER:
 * <ul>
 *   <li>Ações destrutivas ({@link AiActionType#DELETE_ENTITY}) exigem razão e
 *       confiança e nunca podem ser marcadas como {@link TrustLevel#CONFIRMED}
 *       automaticamente.</li>
 *   <li>CREATE_ENTITY tem de ter pelo menos um campo identificador
 *       (nome/título/conteúdo).</li>
 *   <li>UPDATE_ENTITY / LINK / UNLINK exigem entityId preenchido.</li>
 * </ul>
 *
 * @author AETHER
 */
public final class ActionValidator {

    private ActionValidator() {
        // Classe de utilitário estático.
    }

    /**
     * Valida uma proposta.
     *
     * @param proposal a proposta
     * @return lista de erros (vazia = válida)
     */
    public static List<String> validate(AiActionProposal proposal) {
        List<String> errors = new ArrayList<>();
        if (proposal == null) {
            errors.add("Proposal is null.");
            return errors;
        }
        if (proposal.getActionType() == null) {
            errors.add("Action type is required.");
        }
        if (proposal.getEntityType() == null) {
            errors.add("Entity type is required.");
        }

        switch (proposal.getActionType()) {
            case CREATE_ENTITY -> {
                if (!hasIdentifier(proposal)) {
                    errors.add("CREATE_ENTITY requires an identifying field (name/title/content).");
                }
                if (proposal.isDestructive()) {
                    errors.add("CREATE_ENTITY cannot be destructive.");
                }
            }
            case UPDATE_ENTITY -> {
                if (proposal.getEntityId().isBlank()) {
                    errors.add("UPDATE_ENTITY requires an existing entityId.");
                }
                if (proposal.getFields().isEmpty()) {
                    errors.add("UPDATE_ENTITY requires at least one field.");
                }
                if (proposal.getEntityType() == ContextEntityType.PROFILE && !hasProfileField(proposal)) {
                    errors.add("PROFILE update contains no supported profile field.");
                }
            }
            case LINK_ENTITIES, UNLINK_ENTITIES -> {
                if (proposal.getEntityId().isBlank()) {
                    errors.add(proposal.getActionType() + " requires a source entityId.");
                }
                if (proposal.getRelationships().isEmpty()) {
                    errors.add(proposal.getActionType() + " requires at least one relationship target.");
                }
            }
            case DELETE_ENTITY -> {
                if (proposal.getEntityId().isBlank()) {
                    errors.add("DELETE_ENTITY requires an existing entityId.");
                }
                if (proposal.getReason().isBlank()) {
                    errors.add("DELETE_ENTITY requires an explicit reason.");
                }
                if (proposal.getTrustLevel() == TrustLevel.CONFIRMED) {
                    errors.add("DELETE_ENTITY may never be auto-confirmed.");
                }
            }
            default -> errors.add("Unknown action type.");
        }
        return errors;
    }

    /**
     * Indica se uma proposta é válida.
     */
    public static boolean isValid(AiActionProposal proposal) {
        return validate(proposal).isEmpty();
    }

    private static boolean hasProfileField(AiActionProposal p) {
        for (String key : p.getFields().keySet()) {
            if (switch (key) {
                case "fullName", "preferredName", "birthDate", "about", "occupation", "studies", "experience",
                     "skills", "interests", "objectives", "preferences", "projects", "workStyle",
                     "location", "aiContext", "inferredContext", "profileSummary" -> true;
                default -> false;
            }) return true;
        }
        return false;
    }

    private static boolean hasIdentifier(AiActionProposal p) {
        ContextEntityType t = p.getEntityType();
        if (t == null) {
            return false;
        }
        return switch (t) {
            case PERSON, PROJECT -> p.getFields().containsKey("name")
                    && !p.getFields().get("name").isBlank();
            case EVENT, TASK -> p.getFields().containsKey("title")
                    && !p.getFields().get("title").isBlank();
            case NOTE -> p.getFields().containsKey("content")
                    && !p.getFields().get("content").isBlank();
            case PROFILE -> p.getFields().containsKey("preferredName")
                    || p.getFields().containsKey("fullName");
        };
    }
}
