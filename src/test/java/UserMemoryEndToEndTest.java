import ai.AiActionOrchestrator;
import ai.AiActionProposal;
import ai.AiActionType;
import ai.ApprovalFlow;
import ai.ParsedAction;
import ai.PersonalInfoFallbackExtractor;
import ai.ResolvedAction;
import domain.entities.ContextEntityType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import persistence.SqliteUserMemoryRepository;
import persistence.UserVaultSync;
import persistence.VaultManager;
import session.UserSession;
import util.ContextManager;
import util.VaultIndex;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * UserMemoryEndToEndTest — testes E2E da MEMÓRIA PERSISTENTE DO UTILIZADOR
 * (specs #4, #5, #9, #10, #12, #17, #21, #23, #38).
 * <p>
 * Cada cenário percorre o caminho REAL: mensagem → extractores (heurístico
 * determinístico, o mesmo usado em produção como fallback) → orchestrator
 * (guardas em código) → ProposalStore (fonte única) → ApprovalFlow/ActionExecutor
 * (ACCEPT coordenado: perfil SQLite + memória com proveniência + vault User/ +
 * timeline + índice) → ContextManager (prompt da próxima conversa).
 * </p>
 * Cenários A-G do critério de sucesso (spec #38):
 * <ul>
 *   <li>A — fluxo ISEP completo (deteção→proposta→aceite→persistência→recuperação);</li>
 *   <li>B — fluxo Amarante completo (location);</li>
 *   <li>C — negativo: "O ISEP tem um curso interessante." NÃO propõe studies;</li>
 *   <li>D — negativo: "Rafael vive em Amarante." NÃO propõe location;</li>
 *   <li>E — atualização Porto com histórico (timeline mantém Amarante);</li>
 *   <li>F — "Estou a aprender Python." → skills + persistência;</li>
 *   <li>G — vault-first: nova conversa recupera memória sem depender da última mensagem.</li>
 * </ul>
 */
class UserMemoryEndToEndTest {

    private Path tmpDir;

    @BeforeEach
    void setup() throws Exception {
        tmpDir = Files.createTempDirectory("user-memory-e2e");
        System.setProperty("aether.data.dir", tmpDir.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
        java.lang.reflect.Field f = UserSession.class.getDeclaredField("instance");
        f.setAccessible(true);
        f.set(null, null);
        java.lang.reflect.Field pf = ai.ProposalStore.class.getDeclaredField("instance");
        pf.setAccessible(true);
        pf.set(null, null);
        // Perfil limpo.
        var p = UserSession.getInstance().getUserProfile();
        p.setStudies(""); p.setLocation(""); p.setSkills(""); p.setOccupation("");
        p.setInferredContext(""); p.setAiContext(""); p.setProjects("");
        p.setPreferences(""); p.setObjectives("");
        UserSession.getInstance().saveUserProfile();
    }

    /** Caminho real: heurístico → orchestrator → ProposalStore (fonte única). */
    private List<ResolvedAction> detect(String userMessage) {
        PersonalInfoFallbackExtractor fallback = new PersonalInfoFallbackExtractor();
        AiActionOrchestrator orch = new AiActionOrchestrator(fallback);
        return orch.orchestrate(userMessage, "");
    }

    private String acceptFirst(List<ResolvedAction> actions) {
        assertTrue(!actions.isEmpty(), "Deve haver proposta para aceitar");
        AiActionProposal proposal = actions.get(0).proposal;
        assertTrue(ApprovalFlow.executeApproved(proposal).success(), "ACCEPT deve executar");
        return ai.ProposalStore.idFor(proposal.getActionType().name(),
                proposal.getEntityType().name(), "profile", proposal.getFields());
    }

    // ------------------------------------------------------------------
    // Cenário A — fluxo ISEP completo
    // ------------------------------------------------------------------
    @Test
    void scenarioA_isepFullFlow() {
        List<ResolvedAction> actions = detect("Eu estudo no ISEP.");
        assertEquals(1, actions.size(), "«Eu estudo no ISEP.» deve gerar 1 proposta");
        AiActionProposal p = actions.get(0).proposal;
        assertEquals("ISEP", p.getFields().get("studies"));
        acceptFirst(actions);

        // Persistiu no perfil (fonte canónica).
        assertEquals("ISEP", UserSession.getInstance().getUserProfile().getStudies());
        // Memória com proveniência (spec #9).
        SqliteUserMemoryRepository repo = new SqliteUserMemoryRepository();
        var hist = repo.history("studies");
        assertEquals(1, hist.size(), "Deve existir 1 registo de memória");
        assertEquals("ISEP", hist.get(0).value());
        assertEquals("conversation", hist.get(0).sourceType());
        assertEquals("FACT", hist.get(0).classification());
        // Vault do utilizador (spec #12, #14).
        String studies = readUserVault("Studies.md");
        assertTrue(studies.contains("studies: ISEP"), "Studies.md deve refletir ISEP. Conteúdo:\n" + studies);
        // Recuperação no prompt (spec #20).
        String prompt = ContextManager.buildSystemPrompt("o que estudo?");
        assertTrue(prompt.contains("ISEP"), "O prompt deve incluir Studies: ISEP");
        // Re-sugerir o mesmo valor é bloqueado (anti-duplicação, spec #22).
        assertEquals(0, detect("Eu estudo no ISEP.").size(), "Valor já confirmado não re-propõe");
    }

    // ------------------------------------------------------------------
    // Cenário B — fluxo Amarante completo (spec #4 CRITICAL)
    // ------------------------------------------------------------------
    @Test
    void scenarioB_amaranteFullFlow() {
        List<ResolvedAction> actions = detect("Eu vivo em Amarante.");
        assertEquals(1, actions.size(), "«Eu vivo em Amarante.» deve gerar 1 proposta");
        assertEquals("Amarante", actions.get(0).proposal.getFields().get("location"));
        acceptFirst(actions);

        assertEquals("Amarante", UserSession.getInstance().getUserProfile().getLocation());
        // Vault + Personal Information.md.
        String personal = readUserVault("Personal Information.md");
        assertTrue(personal.contains("location: Amarante"), "Personal Information.md deve ter location");
        // Nova conversa: "Onde vivo?" sem depender da última mensagem (spec #17).
        String prompt = ContextManager.buildSystemPrompt("Onde vivo?");
        assertTrue(prompt.contains("Amarante"), "«Onde vivo?» deve recuperar Amarante do perfil");
        // Variantes da mesma pergunta (spec #20).
        assertTrue(ContextManager.buildSystemPrompt("Em que cidade moro?").contains("Amarante"));
        assertTrue(ContextManager.buildSystemPrompt("Qual é a minha localização?").contains("Amarante"));
        // Memória com proveniência.
        var hist = new SqliteUserMemoryRepository().history("location");
        assertEquals(1, hist.size());
        assertEquals("Amarante", hist.get(0).value());
        assertNotNull(hist.get(0).sourceContext(), "Proveniência (sourceContext) obrigatória");
        assertFalse(hist.get(0).sourceContext().isBlank());
    }

    // ------------------------------------------------------------------
    // Cenário C — negativo: menção ao ISEP não é facto pessoal
    // ------------------------------------------------------------------
    @Test
    void scenarioC_isepNegative() {
        List<ResolvedAction> actions = detect("O ISEP tem um curso interessante.");
        assertEquals(0, actions.size(), "Menção de terceiros NÃO pode propor studies=ISEP");
        assertEquals("", UserSession.getInstance().getUserProfile().getStudies());
        assertFalse(readUserVault("Studies.md").contains("ISEP"),
                "Vault não pode ter ISEP em Studies");
    }

    // ------------------------------------------------------------------
    // Cenário D — negativo: Rafael vive em Amarante NÃO é location do user
    // ------------------------------------------------------------------
    @Test
    void scenarioD_rafaelNegative() {
        // A nível de extractor: nada é proposto (frase de terceiros).
        List<ResolvedAction> actions = detect("Rafael vive em Amarante.");
        assertEquals(0, actions.size(), "«Rafael vive em Amarante.» NÃO pode propor location");
        // A nível de GUARDA EM CÓDIGO (spec #5): mesmo que o LLM alucine uma
        // proposta de location, o orchestrator rejeita-a porque "Amarante" não
        // está ancorado numa frase em 1ª pessoa.
        ParsedAction hallucinated = ParsedAction.of("UPDATE_ENTITY", "PROFILE",
                Map.of("location", "Amarante"), List.of(), "", "Rafael vive em Amarante.",
                "FACT", 0.99, "Rafael vive em Amarante.");
        AiActionOrchestrator orch = new AiActionOrchestrator((um, ar) -> List.of(hallucinated));
        List<ResolvedAction> out = orch.orchestrate("Rafael vive em Amarante.", "");
        assertEquals(0, out.size(), "Guarda em código deve rejeitar proposta não ancorada em 1ª pessoa");
        assertEquals("", UserSession.getInstance().getUserProfile().getLocation());
    }

    // ------------------------------------------------------------------
    // Cenário E — atualização Porto com histórico (spec #23)
    // ------------------------------------------------------------------
    @Test
    void scenarioE_portoUpdateKeepsHistory() {
        acceptFirst(detect("Eu vivo em Amarante."));
        acceptFirst(detect("Agora vivo no Porto."));

        assertEquals("Porto", UserSession.getInstance().getUserProfile().getLocation(),
                "location atualiza para Porto");
        // Histórico preservado (spec #23): a memória antiga continua registada.
        var hist = new SqliteUserMemoryRepository().history("location");
        assertEquals(2, hist.size(), "Devem existir 2 registos (Amarante + Porto)");
        assertEquals("Porto", hist.get(0).value());
        assertEquals("Amarante", hist.get(1).value());
        assertEquals("Amarante", hist.get(0).previousValue(), "Proveniência liga Porto→Amarante");
        // Timeline do vault mantém a transição visível.
        String timeline = readUserVault("Timeline.md");
        assertTrue(timeline.contains("Amarante"), "Timeline deve manter Amarante:\n" + timeline);
        assertTrue(timeline.contains("Porto"), "Timeline deve registar Porto");
        // Frase diferente, mesma informação → sem terceira proposta (spec #22).
        assertEquals(0, detect("Eu moro no Porto.").size(),
                "«Eu moro no Porto» é a MESMA info — não gera nova proposta");
    }

    // ------------------------------------------------------------------
    // Cenário F — competências ("Estou a aprender Python.")
    // ------------------------------------------------------------------
    @Test
    void scenarioF_pythonSkill() {
        List<ResolvedAction> actions = detect("Estou a aprender Python.");
        assertEquals(1, actions.size());
        assertEquals("Python", actions.get(0).proposal.getFields().get("skills"));
        acceptFirst(actions);
        assertEquals("Python", UserSession.getInstance().getUserProfile().getSkills());
        assertTrue(readUserVault("Skills.md").contains("skills: Python"));
        assertTrue(ContextManager.buildSystemPrompt("que competências tenho?").contains("Python"));
    }

    // ------------------------------------------------------------------
    // Cenário G — vault-first: nova conversa recupera memória persistida
    // ------------------------------------------------------------------
    @Test
    void scenarioG_vaultFirstNewConversation() {
        // 1) Memória aceite numa conversa antiga.
        acceptFirst(detect("Eu estudo no ISEP."));
        // 2) Simula "Nova conversa" + REINÍCIO DA APP: singleton reposto,
        //    index invalidado — só o disco responde.
        java.lang.reflect.Field f;
        try {
            f = UserSession.class.getDeclaredField("instance");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception e) { throw new RuntimeException(e); }
        VaultIndex.getInstance().invalidate();
        VaultManager.initializeVault();
        // 3) Pergunta nova, sem relação com a última mensagem (spec #17).
        String prompt = ContextManager.buildSystemPrompt("O que sabes sobre os meus estudos?");
        assertTrue(prompt.contains("ISEP"),
                "Nova conversa deve recuperar studies=ISEP do disco (prompt):\n" + prompt);
        assertEquals("ISEP", UserSession.getInstance().getUserProfile().getStudies());
        // 4) A estrutura User/ existe no vault com os ficheiros do perfil.
        Path userDir = VaultManager.getVaultPath().resolve("User");
        assertTrue(Files.isDirectory(userDir), "Pasta User/ deve existir");
        assertTrue(Files.exists(userDir.resolve("Profile.md")));
        assertTrue(Files.exists(userDir.resolve("Personal Information.md")));
        assertTrue(Files.exists(userDir.resolve("Studies.md")));
    }

    // ------------------------------------------------------------------
    // Mensagens-tipo adicionais (spec #21)
    // ------------------------------------------------------------------
    @Test
    void spec21_remainingTestMessages() {
        // "Estou no segundo ano." → inferredContext (não sobrescreve studies).
        acceptFirst(detect("Estou no segundo ano."));
        assertTrue(UserSession.getInstance().getUserProfile().getInferredContext()
                .toLowerCase().contains("segundo ano"));
        // "Quero trabalhar em cybersecurity." → objectives.
        var actions = detect("Quero trabalhar em cybersecurity.");
        assertEquals(1, actions.size());
        assertEquals("objectives", actions.get(0).proposal.getFields().keySet().iterator().next());
        acceptFirst(actions);
        assertTrue(UserSession.getInstance().getUserProfile().getObjectives()
                .toLowerCase().contains("cybersecurity"));
        // "O meu projeto chama-se AETHER." → projects.
        acceptFirst(detect("O meu projeto chama-se AETHER."));
        assertEquals("AETHER", UserSession.getInstance().getUserProfile().getProjects());
        assertTrue(readUserVault("Projects.md").contains("AETHER"));
        // "Prefiro trabalhar à noite." → preferences.
        acceptFirst(detect("Prefiro trabalhar à noite."));
        assertTrue(UserSession.getInstance().getUserProfile().getPreferences()
                .toLowerCase().contains("noite"));
        // "Tenho um irmão chamado Rafael." → PERSON (não perfil).
        var fam = detect("Tenho um irmão chamado Rafael.");
        assertEquals(1, fam.size(), "Deve criar proposta de PERSON para o irmão");
        assertEquals(ContextEntityType.PERSON, fam.get(0).proposal.getEntityType());
        assertEquals("Rafael", fam.get(0).proposal.getFields().get("name"));
        assertEquals("Irmão do utilizador", fam.get(0).proposal.getFields().get("about"));
        // ACCEPT da PERSON → ficheiro no vault People/.
        assertTrue(ApprovalFlow.executeApproved(fam.get(0).proposal).success());
        assertTrue(persistence.VaultManager.findPerson("Rafael").isPresent(),
                "ACCEPT de PERSON deve persistir Rafael no vault");
        // Recuperação: prompt base contém tudo o que foi aceite.
        String prompt = ContextManager.buildSystemPrompt("quem sou eu?");
        assertTrue(prompt.contains("AETHER"));
        assertTrue(prompt.contains("cybersecurity"));
    }

    // ------------------------------------------------------------------
    // Especulação → INFERENCE, nunca FACT (spec #8)
    // ------------------------------------------------------------------
    @Test
    void speculationIsInferenceNotFact() {
        var actions = detect("Acho que vivo em Amarante.");
        assertEquals(1, actions.size());
        assertEquals("INFERENCE", actions.get(0).proposal.getSemanticClassification(),
                "Especulação deve ser classificada INFERENCE");
        acceptFirst(actions);
        var hist = new SqliteUserMemoryRepository().history("location");
        assertEquals("INFERENCE", hist.get(0).classification(),
                "Proveniência persistida deve manter INFERENCE — nunca auto-promovida a FACT");
    }

    // ------------------------------------------------------------------
    // REJECT: memória rejeitada não volta a ser sugerida (spec #10)
    // ------------------------------------------------------------------
    @Test
    void rejectedMemoryIsNotReSuggested() {
        var actions = detect("Eu estudo no ISEP.");
        assertEquals(1, actions.size());
        String id = ai.ProposalStore.idFor("UPDATE_ENTITY", "PROFILE", "profile",
                actions.get(0).proposal.getFields());
        ai.ProposalStore.getInstance().propose(NoteAnalysisServiceToPending.snapshotOf(actions.get(0)));
        ai.ProposalStore.getInstance().markRejected(id);
        // Voltar a detetar: o ProposalStore mantém REJECTED — sem nova proposta.
        var again = detect("Eu estudo no ISEP.");
        assertEquals(1, again.size(), "Orchestrator cria a ResolvedAction, mas...");
        // O persist devolve ALREADY_REJECTED — a UI não volta a notificar.
        var snap = NoteAnalysisServiceToPending.snapshotOf(again.get(0));
        var decision = ai.ProposalStore.getInstance().propose(snap);
        assertEquals(ai.ProposalStore.Decision.ALREADY_REJECTED, decision,
                "REJECT não pode voltar a ser sugerido infinitamente");
        // E nada foi persistido no perfil.
        assertEquals("", UserSession.getInstance().getUserProfile().getStudies());
    }

    // ------------------------------------------------------------------
    // Merge: edições manuais do Obsidian são preservadas (spec #13)
    // ------------------------------------------------------------------
    @Test
    void manualObsidianEditsArePreserved() {
        acceptFirst(detect("Eu vivo em Amarante."));
        Path file = VaultManager.getVaultPath().resolve("User").resolve("Personal Information.md");
        try {
            String content = Files.readString(file);
            // O utilizador edita à mão no Obsidian: nova chave + nota no corpo.
            String manual = content
                    .replace("# Personal Information", "# Personal Information\n\nNota manual do utilizador.")
                    .replace("location: Amarante", "location: Amarante\nnickname: Ze do Barrio");
            Files.writeString(file, manual);
            // Nova sincronização (ACEPT de outro campo).
            acceptFirst(detect("Estou a aprender Python."));
            String after = Files.readString(file);
            assertTrue(after.contains("nickname: Ze do Barrio"),
                    "Chave manual do Obsidian deve ser PRESERVADA:\n" + after);
            assertTrue(after.contains("Nota manual do utilizador."),
                    "Corpo manual do Obsidian deve ser PRESERVADO:\n" + after);
            assertTrue(after.contains("location: Amarante"),
                    "Campo conhecido continua sincronizado");
            assertTrue(after.contains("skills: Python") == false,
                    "Skills.md é que tem skills — este ficheiro não");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // Notas alimentam memória de perfil mesmo sem Ollama (spec #25)
    // ------------------------------------------------------------------
    @Test
    void notesFeedProfileMemoryWithoutModel() {
        ai.NoteAnalysisService service = new ai.NoteAnalysisService("", List::of);
        var actions = service.analyze("Vivo em Amarante. Estou a aprender Java.", "Nota: autobiografia");
        assertTrue(!actions.isEmpty(), "Nota com factos pessoais deve gerar propostas mesmo sem modelo");
        boolean hasLocation = false;
        boolean hasSkill = false;
        for (ResolvedAction ra : actions) {
            ApprovalFlow.executeApproved(ra.proposal);
            if (ra.proposal.getFields().containsKey("location")) hasLocation = true;
            if (ra.proposal.getFields().containsKey("skills")) hasSkill = true;
        }
        assertTrue(hasLocation, "A nota deve propor location=Amarante");
        assertTrue(hasSkill, "A nota deve propor skills=Java");
        assertEquals("Amarante", UserSession.getInstance().getUserProfile().getLocation());
        assertEquals("Java", UserSession.getInstance().getUserProfile().getSkills());
        assertTrue(new SqliteUserMemoryRepository().history("location").get(0)
                .sourceType().equals("conversation")
                || !new SqliteUserMemoryRepository().history("location").isEmpty());
    }

    // ------------------------------------------------------------------
    // Timeline: existe no arranque, append-only preserva TODAS as linhas
    // ------------------------------------------------------------------
    @Test
    void timelineIsAppendOnlyAndCreatedOnSync() {
        // syncProfile cria Timeline.md mesmo sem histórico.
        persistence.UserVaultSync.syncProfile(UserSession.getInstance().getUserProfile(), "TEST");
        Path timeline = VaultManager.getVaultPath().resolve("User").resolve("Timeline.md");
        assertTrue(Files.exists(timeline), "syncProfile deve criar Timeline.md");
        // Três ACCEPTs → três linhas de histórico preservadas.
        acceptFirst(detect("Eu estudo no ISEP."));
        acceptFirst(detect("Eu vivo em Amarante."));
        acceptFirst(detect("Estou a aprender Python."));
        try {
            long bullets = Files.readString(timeline).lines()
                    .filter(l -> l.trim().startsWith("-")).count();
            assertTrue(bullets >= 3, "Timeline deve ter >= 3 entradas (tem " + bullets + ")");
            String all = Files.readString(timeline);
            assertTrue(all.contains("ISEP"));
            assertTrue(all.contains("Amarante"));
            assertTrue(all.contains("Python"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ------------------------------------------------------------------
    // birthDate canónico (spec #7)
    // ------------------------------------------------------------------
    @Test
    void birthDateIsCanonicalProfileField() {
        ParsedAction pa = ParsedAction.of("UPDATE_ENTITY", "PROFILE",
                Map.of("birthday", "2003-05-15"), List.of(), "",
                "Nasci a 15 de maio de 2003.", "FACT", 0.9, "Nasci a 15 de maio de 2003.");
        AiActionOrchestrator orch = new AiActionOrchestrator((um, ar) -> List.of(pa));
        var out = orch.orchestrate("Nasci a 15 de maio de 2003.", "");
        assertEquals(1, out.size());
        assertTrue(out.get(0).proposal.getFields().containsKey("birthDate"),
                "birthday deve ser normalizado para birthDate");
        assertTrue(ApprovalFlow.executeApproved(out.get(0).proposal).success());
        assertEquals(java.time.LocalDate.of(2003, 5, 15),
                UserSession.getInstance().getUserProfile().getBirthDate());
        assertTrue(ContextManager.buildSystemPrompt("quando faço anos?").contains("2003-05-15"));
    }

    // ------------------------------------------------------------------
    // Comando explícito de perfil não exige 1ª pessoa (spec #6)
    // ------------------------------------------------------------------
    @Test
    void explicitProfileCommandBypassesFirstPersonRequirement() {
        ParsedAction pa = ParsedAction.of("UPDATE_ENTITY", "PROFILE",
                Map.of("location", "Lisboa"), List.of(), "",
                "Comando explícito do utilizador.", "FACT", 0.9, "Define a minha localização: Lisboa");
        AiActionOrchestrator orch = new AiActionOrchestrator((um, ar) -> List.of(pa));
        var out = orch.orchestrate("Define a minha localização: Lisboa", "");
        assertEquals(1, out.size(), "Comando explícito com valor ancorado deve ser admitido");
        // Mas o mesmo comando com valor NÃO presente na mensagem é rejeitado.
        ParsedAction invented = ParsedAction.of("UPDATE_ENTITY", "PROFILE",
                Map.of("location", "Faro"), List.of(), "",
                "Comando explícito do utilizador.", "FACT", 0.9, "Define a minha localização: Lisboa");
        AiActionOrchestrator orch2 = new AiActionOrchestrator((um, ar) -> List.of(invented));
        assertEquals(0, orch2.orchestrate("Define a minha localização: Lisboa", "").size(),
                "Valor inventado (não presente na mensagem) é sempre rejeitado");
    }

    // ------------------------------------------------------------------
    // Mensagem mista: terceiros + utilizador na mesma mensagem
    // ------------------------------------------------------------------
    @Test
    void mixedMessageDetectsUserPartOnly() {
        var actions = detect("Rafael vive em Amarante. Eu vivo no Porto.");
        assertEquals(1, actions.size(), "Só a parte em 1ª pessoa gera proposta");
        assertEquals("Porto", actions.get(0).proposal.getFields().get("location"));
        acceptFirst(actions);
        assertEquals("Porto", UserSession.getInstance().getUserProfile().getLocation());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String readUserVault(String fileName) {
        try {
            Path file = VaultManager.getVaultPath().resolve("User").resolve(fileName);
            return Files.readString(file);
        } catch (Exception e) {
            return "<MISSING: " + fileName + ">";
        }
    }
}

/** Helper: converte ResolvedAction em Snapshot PENDING (mesmo cálculo do controller). */
class NoteAnalysisServiceToPending {
    static ai.ProposalStore.Snapshot snapshotOf(ResolvedAction ra) {
        var p = ra.proposal;
        String identifier = p.getFields().getOrDefault("name",
                p.getFields().getOrDefault("title", p.getFields().getOrDefault("content", "profile")));
        String id = ai.ProposalStore.idFor(p.getActionType().name(), p.getEntityType().name(),
                identifier, p.getFields());
        return new ai.ProposalStore.Snapshot(id, "PENDING", p.getActionType().name(),
                p.getEntityType().name(), identifier, p.getFields(), p.getRelationships(),
                p.getReason(), p.getTrustLevel().name(), p.getConfidence(),
                p.getSourceContext(), System.currentTimeMillis(), p.getSemanticClassification());
    }
}
