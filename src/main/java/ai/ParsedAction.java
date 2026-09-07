package ai;

import domain.entities.ContextEntityType;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ParsedAction — uma ação extraída da resposta do modelo, ainda por validar.
 * <p>
 * Representa o dados crus vindos do extrator (parser de JSON) antes de serem
 * convertidos num {@link AiActionProposal} validado. É um tipo intermediário
 * deliberadamente simples: o {@link JsonActionParser} só preenche o que consegue
 * ler com segurança, e o {@link AiActionOrchestrator} converte cada
 * {@code ParsedAction} válido num {@code AiActionProposal}.
 * </p>
 *
 * @author AETHER
 */
public final class ParsedAction {

    /** Tipo de ação em texto (mapeado depois para {@link AiActionType}). */
    public final String action;
    /** Tipo de entidade em texto (mapeado para {@link ContextEntityType}). */
    public final String entity;
    /** Campos a criar/atualizar (nome → valor). */
    public final Map<String, String> fields;
    /** Nomes das entidades a relacionar (para LINK/UNLINK). */
    public final List<String> relationships;
    /** Nome/título da entidade existente a atualizar/eliminar/ligar. */
    public final String target;
    /** Razão da ação. */
    public final String reason;
    /**
     * Classificação epistémica atribuída pelo modelo (FACT / INFERENCE /
     * SUGGESTION). Mapeada pelo {@link AiActionOrchestrator} para
     * {@link TrustLevel}. Nunca é usada para saltar a aprovação — só para
     * comunicar ao utilizador o grau de certeza da proposta.
     */
    public final String classification;
    /** Confiança atribuída pelo modelo, em [0,1]. 0 se não fornecida. */
    public final double confidence;
    /** Excerto da nota que justifica a proposta ("evidence"). */
    public final String evidence;

    ParsedAction(String action, String entity, Map<String, String> fields,
                 List<String> relationships, String target, String reason) {
        this(action, entity, fields, relationships, target, reason, null, 0.0, null);
    }

    ParsedAction(String action, String entity, Map<String, String> fields,
                 List<String> relationships, String target, String reason,
                 String classification, double confidence, String evidence) {
        this.action = action;
        this.entity = entity;
        this.fields = fields == null ? Collections.emptyMap() : Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.relationships = relationships == null ? Collections.emptyList() : List.copyOf(relationships);
        this.target = target == null ? "" : target;
        this.reason = reason == null ? "" : reason;
        this.classification = classification == null ? "" : classification;
        this.confidence = clamp01(confidence);
        this.evidence = evidence == null ? "" : evidence;
    }

    private static double clamp01(double c) {
        if (c < 0) return 0;
        if (c > 1) return 1;
        return c;
    }

    /**
     * Fábrica pública para criar uma ação extraída (usada em testes com
     * extratores falsos e pelo parser).
     *
     * @param action       tipo de ação em texto
     * @param entity       tipo de entidade em texto
     * @param fields       campos a criar/atualizar
     * @param relationships nomes das entidades a relacionar
     * @param target       nome/título da entidade existente (update/delete/link)
     * @param reason       razão da ação
     * @return uma nova ação extraída
     */
    public static ParsedAction of(String action, String entity, Map<String, String> fields,
                                  List<String> relationships, String target, String reason) {
        return new ParsedAction(action, entity, fields, relationships, target, reason);
    }

    /**
     * Fábrica pública para uma ação extraída com classificação epistémica,
     * confiança e evidência — usada pelo analyzer de notas. Mantém a
     * retrocompatibilidade: campos desconhecidos ficam vazios/zero.
     *
     * @param action        tipo de ação em texto
     * @param entity        tipo de entidade em texto
     * @param fields        campos a criar/atualizar
     * @param relationships nomes das entidades a relacionar
     * @param target        nome/título da entidade existente (update/delete/link)
     * @param reason        razão da ação
     * @param classification FACT / INFERENCE / SUGGESTION
     * @param confidence    confiança em [0,1]
     * @param evidence      excerto que justifica a proposta
     * @return uma nova ação extraída enriquecida
     */
    public static ParsedAction of(String action, String entity, Map<String, String> fields,
                                  List<String> relationships, String target, String reason,
                                  String classification, double confidence, String evidence) {
        return new ParsedAction(action, entity, fields, relationships, target, reason,
                classification, confidence, evidence);
    }
}
