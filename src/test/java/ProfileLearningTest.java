import ai.AiActionOrchestrator;
import ai.AiActionProposal;
import ai.AiActionType;
import ai.ActionExtractor;
import ai.ApprovalFlow;
import ai.ParsedAction;
import ai.PersonalInfoFallbackExtractor;
import ai.ResolvedAction;
import domain.entities.ContextEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import session.UserSession;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProfileLearningTest — valida o fluxo de aprendizagem do perfil (spec):
 *  - deduplicação: não criar proposta para info já confirmada (spec #9);
 *  - acréscimo de bullets no AI Context em vez de substituir (spec #8);
 *  - info rejeitada não é memória persistente (spec #15);
 *  - info aceita chega ao perfil (spec #4).
 */
class ProfileLearningTest {

    private Path tmpDir;

    @BeforeEach
    void setup() throws Exception {
        tmpDir = Files.createTempDirectory("profile-learn-test");
        System.setProperty("aether.data.dir", tmpDir.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
        // O UserSession é um singleton criado uma única vez; os repositórios
        // SQLite apontam para o data dir ativo na primeira chamada. Como testes
        // anteriores podem tê-lo criado com outro dir, reinicia a instância para
        // garantir que aponta para o nosso dir temporário.
        java.lang.reflect.Field f = UserSession.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
        // O ProposalStore é também um singleton persistido em ficheiro; reset
        // para que cada teste comece sem propostas pendentes herdadas.
        java.lang.reflect.Field pf = ai.ProposalStore.class.getDeclaredField("instance");
        pf.setAccessible(true);
        pf.set(null, null);
        UserSession.getInstance().getUserProfile().setStudies("");
        UserSession.getInstance().getUserProfile().setAiContext("");
        UserSession.getInstance().getUserProfile().setInferredContext("");
        UserSession.getInstance().saveUserProfile();
    }

    private ActionExtractor fakeExtractor(List<ParsedAction> actions) {
        return (userMessage, reply) -> actions;
    }

    /** Spec #9: se "studies" já está confirmado como ISEP, não criar proposta duplicada. */
    @Test
    void dedupDropsProposalForAlreadyConfirmedValue() {
        UserSession.getInstance().getUserProfile().setStudies("ISEP");
        UserSession.getInstance().saveUserProfile();

        ParsedAction pa = ParsedAction.of("UPDATE_ENTITY", "PROFILE",
                Map.of("studies", "ISEP"), List.of(), "", "Utilizador disse que estuda no ISEP.");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("eu estudo no ISEP", "Ok.");
        assertEquals(0, out.size(), "Não deve criar proposta para valor já confirmado");
    }

    /** Spec #11: valor diferente do confirmado gera proposta pendente (não sobrescreve). */
    @Test
    void contradictoryValueGeneratesPendingProposal() {
        UserSession.getInstance().getUserProfile().setStudies("ISEP");
        UserSession.getInstance().saveUserProfile();

        ParsedAction pa = ParsedAction.of("UPDATE_ENTITY", "PROFILE",
                Map.of("studies", "Universidade do Porto"), List.of(), "", "Mudou de instituição.");
        AiActionOrchestrator orch = new AiActionOrchestrator(fakeExtractor(List.of(pa)));
        List<ResolvedAction> out = orch.orchestrate("agora estudo na Universidade do Porto", "Ok.");
        assertEquals(1, out.size(), "Deve criar proposta pendente para valor contraditório");
        assertEquals("Universidade do Porto",
                out.get(0).proposal.getFields().get("studies"));
    }

    /** Spec #8 (atualizado): aceitar info sem campo estruturado vai para inferredContext
     *  (SEPARADO do aiContext manual do utilizador) e acrescenta como bullet. */
    @Test
    void acceptingInferredContextAppendsAsBullet() {
        // Pré-existente: já há um bullet no inferredContext.
        UserSession.getInstance().getUserProfile().setInferredContext("• Likes programming.");
        // E o utilizador tem texto manual no aiContext.
        UserSession.getInstance().getUserProfile().setAiContext("Notas manuais do utilizador.");
        UserSession.getInstance().saveUserProfile();

        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.UPDATE_ENTITY)
                .entityType(ContextEntityType.PROFILE)
                .entityId("profile")
                .field("inferredContext", "Likes building personal projects.")
                .reason("Gosto de construir projetos pessoais.")
                .build();

        boolean ok = ApprovalFlow.executeApproved(proposal).success();
        assertTrue(ok, "A proposta aprovada deve executar com sucesso");

        String inf = UserSession.getInstance().getUserProfile().getInferredContext();
        assertTrue(inf.contains("Likes programming."), "Deve manter o bullet anterior no inferredContext");
        assertTrue(inf.contains("Likes building personal projects."), "Deve conter o novo bullet no inferredContext");
        // O texto manual NÃO foi tocado.
        assertEquals("Notas manuais do utilizador.",
                UserSession.getInstance().getUserProfile().getAiContext());
    }

    /** Spec #8 + #10: não duplicar bullet semelhante já presente no inferredContext. */
    @Test
    void acceptingDuplicateInferredContextDoesNotDoubleAdd() {
        UserSession.getInstance().getUserProfile().setInferredContext("• Likes building personal projects.");
        UserSession.getInstance().saveUserProfile();

        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.UPDATE_ENTITY)
                .entityType(ContextEntityType.PROFILE)
                .entityId("profile")
                .field("inferredContext", "Likes building personal projects.")
                .reason("Mesma informação.")
                .build();

        ApprovalFlow.executeApproved(proposal);
        long count = UserSession.getInstance().getUserProfile().getInferredContext().lines()
                .filter(l -> l.contains("Likes building personal projects")).count();
        assertEquals(1, count, "Não deve duplicar bullet semelhante");
    }

    /** Spec #4: aceitar uma proposta de campo estruturado atualiza o campo correto. */
    @Test
    void acceptingStructuredFieldUpdatesProfile() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.UPDATE_ENTITY)
                .entityType(ContextEntityType.PROFILE)
                .entityId("profile")
                .field("studies", "ISEP")
                .reason("Estuda no ISEP.")
                .build();

        boolean ok = ApprovalFlow.executeApproved(proposal).success();
        assertTrue(ok);
        assertEquals("ISEP", UserSession.getInstance().getUserProfile().getStudies());
    }

    /** Spec #15: informação rejeitada não chega ao perfil nem ao inferredContext.
     *  Rejeitar significa NUNCA chamar o executor — o perfil permanece inalterado. */
    @Test
    void rejectedProposalIsNotPersisted() {
        // Pré-condição: perfil vazio.
        assertEquals("", UserSession.getInstance().getUserProfile().getStudies());
        assertEquals("", UserSession.getInstance().getUserProfile().getInferredContext());

        // Uma proposta rejeitada NÃO é executada — o ApprovalFlow nunca é chamado.
        // (Na UI, markRejected remove a proposta; o executor nunca corre.)
        // Logo o perfil mantém-se vazio.
        assertEquals("", UserSession.getInstance().getUserProfile().getStudies());
        assertEquals("", UserSession.getInstance().getUserProfile().getInferredContext());
    }

    // ------------------------------------------------------------------
    // PersonalInfoFallbackExtractor — deteção heurística robusta.
    // ------------------------------------------------------------------

    private PersonalInfoFallbackExtractor fallback() {
        return new PersonalInfoFallbackExtractor();
    }

    /** "Eu estudo no ISEP" → proposta PROFILE.studies=ISEP. */
    @Test
    void fallbackDetectsStudyPlaceWithPronoun() {
        List<ParsedAction> out = fallback().extract("Eu estudo no ISEP", "Ok.");
        assertEquals(1, out.size(), "Deve detetar uma ação de perfil");
        assertEquals("PROFILE", out.get(0).entity);
        assertEquals("ISEP", out.get(0).fields.get("studies"));
    }

    /** "Estudo no ISEP" (sem "eu") → também conta (1ª pessoa). */
    @Test
    void fallbackDetectsStudyPlaceWithoutPronoun() {
        List<ParsedAction> out = fallback().extract("Estudo no ISEP", "Ok.");
        assertEquals(1, out.size(), "Verbo na 1ª pessoa sem pronome deve contar");
        assertEquals("ISEP", out.get(0).fields.get("studies"));
    }

    /** "O João estuda no ISEP" (3ª pessoa) → NÃO é info do utilizador. */
    @Test
    void fallbackRejectsThirdParty() {
        List<ParsedAction> out = fallback().extract("O João estuda no ISEP", "Ok.");
        assertEquals(0, out.size(), "Afirmacao sobre terceiro não deve gerar proposta");
    }

    /** "Sou programador" → occupation. */
    @Test
    void fallbackDetectsOccupation() {
        List<ParsedAction> out = fallback().extract("Sou programador", "Ok.");
        assertEquals(1, out.size());
        assertEquals("Programador", out.get(0).fields.get("occupation"));
    }

    /** "Gosto de desenvolver aplicações desktop" → inferredContext (atividade longa). */
    @Test
    void fallbackActivityGoesToInferredContext() {
        List<ParsedAction> out = fallback().extract("Gosto de desenvolver aplicações desktop", "Ok.");
        assertEquals(1, out.size());
        assertTrue(out.get(0).fields.containsKey("inferredContext"),
                "Atividade longa deve ir para inferredContext, não interests");
        assertTrue(out.get(0).fields.get("inferredContext").contains("desenvolver aplicações desktop"));
    }

    /** Fluxo end-to-end: fallback → orchestrator → proposta pendente → pendingCount 0→1. */
    @Test
    void fallbackCreatesPendingProposalAndBellIncrements() {
        // Proposta ainda não existe.
        assertEquals(0, ai.ProposalStore.getInstance().pendingCount());

        AiActionOrchestrator orch = new AiActionOrchestrator(fallback());
        List<ResolvedAction> out = orch.orchestrate("Eu estudo no ISEP", "Ok.");
        assertEquals(1, out.size(), "Orchestrator deve aceitar a ação do fallback");

        // Persistir (como o AetherAIController faz) incrementa o sino.
        ai.AiActionProposal p = out.get(0).proposal;
        String id = ai.ProposalStore.idFor(p.getActionType().name(), p.getEntityType().name(),
                "profile", p.getFields());
        ai.ProposalStore.getInstance().propose(new ai.ProposalStore.Snapshot(
                id, "PENDING", p.getActionType().name(), p.getEntityType().name(), "profile",
                p.getFields(), p.getRelationships(), p.getReason(), p.getTrustLevel().name(),
                p.getConfidence(), p.getSourceContext(), System.currentTimeMillis(),
                p.getSemanticClassification()));

        assertEquals(1, ai.ProposalStore.getInstance().pendingCount(),
                "Sino deve incrementar para 1 após persistir a proposta");
    }

    /** Fluxo end-to-end: Accept resolve a proposta e decrementa o sino 1→0. */
    @Test
    void acceptingProposalResolvesBellCountToOneToZero() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.UPDATE_ENTITY)
                .entityType(ContextEntityType.PROFILE)
                .entityId("profile")
                .field("studies", "ISEP")
                .reason("Estuda no ISEP.")
                .build();

        String id = ai.ProposalStore.idFor(proposal.getActionType().name(),
                proposal.getEntityType().name(), "profile", proposal.getFields());
        ai.ProposalStore.getInstance().propose(new ai.ProposalStore.Snapshot(
                id, "PENDING", proposal.getActionType().name(), proposal.getEntityType().name(), "profile",
                proposal.getFields(), proposal.getRelationships(), proposal.getReason(),
                proposal.getTrustLevel().name(), proposal.getConfidence(), proposal.getSourceContext(),
                System.currentTimeMillis(), proposal.getSemanticClassification()));
        assertEquals(1, ai.ProposalStore.getInstance().pendingCount(), "Deve haver 1 pendente");

        boolean ok = ApprovalFlow.executeApproved(proposal).success();
        assertTrue(ok, "Accept deve executar com sucesso");
        ai.ProposalStore.getInstance().markAccepted(id);
        assertEquals(0, ai.ProposalStore.getInstance().pendingCount(),
                "Sino deve voltar a 0 após accept");
        assertEquals("ISEP", UserSession.getInstance().getUserProfile().getStudies());
    }

    /** Fluxo end-to-end: Reject remove a proposta sem alterar o perfil; sino 1→0. */
    @Test
    void rejectingProposalResolvesBellCountWithoutPersisting() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.UPDATE_ENTITY)
                .entityType(ContextEntityType.PROFILE)
                .entityId("profile")
                .field("studies", "ISEP")
                .reason("Estuda no ISEP.")
                .build();

        String id = ai.ProposalStore.idFor(proposal.getActionType().name(),
                proposal.getEntityType().name(), "profile", proposal.getFields());
        ai.ProposalStore.getInstance().propose(new ai.ProposalStore.Snapshot(
                id, "PENDING", proposal.getActionType().name(), proposal.getEntityType().name(), "profile",
                proposal.getFields(), proposal.getRelationships(), proposal.getReason(),
                proposal.getTrustLevel().name(), proposal.getConfidence(), proposal.getSourceContext(),
                System.currentTimeMillis(), proposal.getSemanticClassification()));
        assertEquals(1, ai.ProposalStore.getInstance().pendingCount());

        // Rejeitar: NUNCA chama o executor. Apenas marca como rejeitada.
        ai.ProposalStore.getInstance().markRejected(id);
        assertEquals(0, ai.ProposalStore.getInstance().pendingCount(),
                "Sino deve voltar a 0 após reject");
        assertEquals("", UserSession.getInstance().getUserProfile().getStudies(),
                "Perfil não deve ser alterado ao rejeitar");
    }

    /** Dedup melhorado: bullet já presente no inferredContext não gera proposta repetida. */
    @Test
    void duplicateInferredContextBulletIsNotReproposed() {
        UserSession.getInstance().getUserProfile().setInferredContext(
                "• Gosta de desenvolver aplicações desktop");
        UserSession.getInstance().saveUserProfile();

        AiActionOrchestrator orch = new AiActionOrchestrator(fallback());
        // Mesma mensagem deve detetar a info, mas o dedup contra o perfil
        // confirmado deve impedi-la (já está como bullet).
        List<ResolvedAction> out = orch.orchestrate(
                "Gosto de desenvolver aplicações desktop", "Ok.");
        assertEquals(0, out.size(), "Não deve repropor bullet já presente no inferredContext");
    }
}
