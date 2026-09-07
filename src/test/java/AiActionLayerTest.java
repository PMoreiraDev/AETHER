import ai.ActionExecutor;
import ai.ActionValidator;
import ai.AiActionProposal;
import ai.AiActionType;
import ai.ApprovalFlow;
import ai.TrustLevel;
import domain.entities.ContextEntityType;
import domain.entities.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.VaultManager;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the AI action layer: proposal validation, the approval flow
 * (Suggest → Explain → Ask → Execute), trust levels, and that the IA never
 * mutates data without explicit approval.
 */
class AiActionLayerTest {

    private Path tmpVault;

    @BeforeEach
    void setup() throws Exception {
        tmpVault = Files.createTempDirectory("aether-actions-test");
        System.setProperty("aether.data.dir", tmpVault.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
    }

    @Test
    void createEntity_validProposalAcceptedAndExecuted() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .field("title", "Implement global search")
                .field("priority", "HIGH")
                .reason("Inferred from user message about finishing search this week.")
                .confidence(0.85)
                .trustLevel(TrustLevel.SUGGESTED)
                .build();

        assertTrue(ActionValidator.isValid(proposal), "valid proposal should pass validation");

        ApprovalFlow flow = new ApprovalFlow();
        assertTrue(flow.propose(proposal), "proposal should be pending");
        ApprovalFlow.ExecutionResult result = flow.accept(proposal);
        assertTrue(result.success(), "accepted proposal should execute");
        assertEquals(0, flow.pendingCount(), "no proposals should remain pending");

        // Verify the task was actually persisted in the vault.
        assertEquals(1, VaultManager.listTasks().size());
        Task created = VaultManager.listTasks().get(0);
        assertEquals("Implement global search", created.getTitle());
    }

    @Test
    void rejectedProposalDoesNotMutateData() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .field("title", "Should not be created")
                .trustLevel(TrustLevel.SUGGESTED)
                .build();

        ApprovalFlow flow = new ApprovalFlow();
        flow.propose(proposal);
        flow.reject(proposal);

        assertEquals(0, VaultManager.listTasks().size(), "rejected proposal must not persist anything");
        assertEquals(1, flow.rejected().size());
    }

    @Test
    void createWithoutIdentifierIsInvalid() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.PERSON)
                .build(); // no name
        List<String> errors = ActionValidator.validate(proposal);
        assertFalse(errors.isEmpty(), "person without name should be invalid");
    }

    @Test
    void deleteEntityRequiresExplicitReason() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.DELETE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .entityId("some-id")
                .trustLevel(TrustLevel.INFERRED)
                .build(); // no reason
        assertFalse(ActionValidator.isValid(proposal), "delete without reason should be invalid");
    }

    @Test
    void deleteEntityCannotBeAutoConfirmed() {
        AiActionProposal proposal = new AiActionProposal.Builder()
                .actionType(AiActionType.DELETE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .entityId("some-id")
                .reason("user asked to remove")
                .trustLevel(TrustLevel.CONFIRMED) // forbidden for destructive
                .build();
        assertFalse(ActionValidator.isValid(proposal), "destructive action must never be auto-confirmed");
    }

    @Test
    void destructiveActionRequiresExplicitConfirmation() {
        // First create a task to target.
        AiActionProposal create = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .field("title", "To be deleted")
                .build();
        ApprovalFlow createFlow = new ApprovalFlow();
        createFlow.propose(create);
        createFlow.accept(create);
        Task target = VaultManager.listTasks().get(0);

        AiActionProposal delete = new AiActionProposal.Builder()
                .actionType(AiActionType.DELETE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .entityId(target.getId())
                .reason("User explicitly asked to remove this task.")
                .trustLevel(TrustLevel.SUGGESTED)
                .build();

        ApprovalFlow flow = new ApprovalFlow();
        flow.propose(delete);
        // Without explicit confirmation, the destructive action is refused by
        // the ApprovalFlow (the confirmation gate).
        ApprovalFlow.ExecutionResult r1 = flow.accept(delete);
        assertFalse(r1.success(), "delete must require explicit confirmation");

        // When explicitly confirmed, the ApprovalFlow delegates to ActionExecutor,
        // which now implements DELETE (with orphaned-wikilink cleanup). The
        // double-confirmation gate lives in ApprovalFlow + the UI (second click),
        // not in the executor itself — same as CREATE/UPDATE.
        ApprovalFlow.ExecutionResult r2 = flow.accept(delete, true);
        assertTrue(r2.success(), "delete executes when explicitly confirmed and target exists");
        assertFalse(VaultManager.listTasks().stream().anyMatch(t -> t.getId().equals(target.getId())),
                "deleted task must no longer be present");
    }

    @Test
    void acceptAllSkipsDestructiveActions() {
        AiActionProposal safe = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.NOTE)
                .field("content", "Quick note from batch")
                .build();
        AiActionProposal destructive = new AiActionProposal.Builder()
                .actionType(AiActionType.DELETE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .entityId("nonexistent")
                .reason("batch test")
                .trustLevel(TrustLevel.SUGGESTED)
                .build();

        ApprovalFlow flow = new ApprovalFlow();
        flow.propose(safe);
        flow.propose(destructive);
        List<ApprovalFlow.ExecutionResult> results = flow.acceptAll();

        // Only the safe action executed; destructive remains pending.
        assertEquals(1, results.size(), "only non-destructive actions execute in batch");
        assertEquals(1, flow.pendingCount(), "destructive action remains pending for confirmation");
        assertEquals(1, VaultManager.listNotes().size());
    }

    @Test
    void editProposalBeforeAccept() {
        AiActionProposal original = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .field("title", "Draft title")
                .build();
        AiActionProposal edited = new AiActionProposal.Builder()
                .actionType(AiActionType.CREATE_ENTITY)
                .entityType(ContextEntityType.TASK)
                .field("title", "Final title")
                .build();

        ApprovalFlow flow = new ApprovalFlow();
        flow.propose(original);
        assertTrue(flow.edit(original, edited), "edit should replace pending proposal");
        ApprovalFlow.ExecutionResult r = flow.accept(edited);
        assertTrue(r.success());
        assertEquals("Final title", VaultManager.listTasks().get(0).getTitle());
    }
}
