import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import util.GlobalSearchService;
import util.VaultRefreshBus;
import persistence.VaultManager;
import util.VaultIndex;
import domain.entities.ContextEntityType;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Note;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes do serviço de pesquisa global e do bus de refresh reativo.
 * Cobre P0.9 (Global Search) e P0.7 (reactive refresh).
 */
class SearchAndRefreshBusTest {

    private Path tmp;

    @BeforeEach
    void setup() throws Exception {
        tmp = Files.createTempDirectory("aether-search-test");
        System.setProperty("aether.data.dir", tmp.toString());
        VaultManager.initializeVault();
        VaultIndex.getInstance().invalidate();
    }

    @AfterEach
    void teardown() throws Exception {
        VaultIndex.getInstance().invalidate();
    }

    @Test
    void searchFindsPeopleByDiacriticInsensitiveName() {
        Person p = new Person();
        p.setName("João Pedro");
        p.setOccupation("Engineer");
        VaultManager.savePerson(p);
        VaultIndex.getInstance().invalidate();

        var result = GlobalSearchService.search("joao");
        assertFalse(result.isEmpty(), "deve encontrar João sem diacríticos");
        assertTrue(result.people().stream().anyMatch(x -> x.getName().equals("João Pedro")));
    }

    @Test
    void searchFindsAcrossEntityTypes() {
        Person maria = new Person();
        maria.setName("Maria Silva");
        VaultManager.savePerson(maria);

        Project proj = new Project();
        proj.setName("Maria Project");
        VaultManager.saveProject(proj);

        Note note = new Note();
        VaultManager.saveNote(note, "Reunião com Maria sobre o projeto");
        VaultIndex.getInstance().invalidate();

        var result = GlobalSearchService.search("Maria");
        assertFalse(result.isEmpty());
        assertFalse(result.people().isEmpty(), "deve encontrar pessoa Maria");
        assertFalse(result.projects().isEmpty(), "deve encontrar projeto Maria");
    }

    @Test
    void searchReturnsEmptyForUnknownQuery() {
        Person a = new Person();
        a.setName("Alberto");
        VaultManager.savePerson(a);
        VaultIndex.getInstance().invalidate();

        var result = GlobalSearchService.search("zzzznonexistent");
        assertTrue(result.isEmpty());
        assertEquals(0, result.total());
    }

    @Test
    void refreshBusDeliversEventsToSubscribers() {
        AtomicInteger delivered = new AtomicInteger(0);
        var listener = new java.util.function.Consumer<VaultRefreshBus.VaultChangedEvent>() {
            @Override
            public void accept(VaultRefreshBus.VaultChangedEvent e) {
                delivered.incrementAndGet();
            }
        };
        VaultRefreshBus.subscribe(listener);

        VaultRefreshBus.publish(VaultRefreshBus.ChangeType.CREATED, ContextEntityType.TASK.name());
        VaultRefreshBus.publish(VaultRefreshBus.ChangeType.UPDATED, ContextEntityType.PERSON.name());
        VaultRefreshBus.publish(VaultRefreshBus.ChangeType.EXTERNAL);

        assertEquals(3, delivered.get(), "todos os 3 eventos devem ser entregues ao subscritor");

        VaultRefreshBus.unsubscribe(listener);
        VaultRefreshBus.publish(VaultRefreshBus.ChangeType.DELETED);
        assertEquals(3, delivered.get(), "depois de cancelar a subscrição, não recebe mais eventos");
    }
}
