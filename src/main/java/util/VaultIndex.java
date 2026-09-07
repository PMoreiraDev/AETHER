package util;

import domain.entities.Event;
import domain.entities.Note;
import domain.entities.Person;
import domain.entities.Project;
import domain.entities.Task;
import persistence.VaultManager;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * VaultIndex — cache/index central e thread-safe das entidades do vault.
 * <p>
 * É a fonte única de verdade em memória para pesquisa global, calendário e
 * context retrieval. Não duplica o parsing: delega ao {@link VaultManager} para
 * ler os ficheiros markdown do vault, e mantém em cache o resultado até que
 * algo o invalide (uma escrita interna ou uma alteração externa detetada pelo
 * {@link VaultFileWatcher}).
 * </p>
 * <p>
 * Princípio: o vault continua a ser a fonte de persistência. Este index é um
 * cache derivado e pode ser reconstruído a qualquer momento sem perda de dados.
 * </p>
 *
 * @author AETHER
 */
public final class VaultIndex {

    private static final VaultIndex INSTANCE = new VaultIndex();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Snapshot snapshot = Snapshot.empty();
    private final AtomicLong version = new AtomicLong(0);

    private VaultIndex() {
        // Singleton.
    }

    /** Devolve a instância única do index. */
    public static VaultIndex getInstance() {
        return INSTANCE;
    }

    /**
     * Marca o index como sujo. A próxima leitura reconstrói o cache a partir do
     * vault. Chamado pelo {@link VaultFileWatcher} e pelas escritas do VaultManager.
     */
    public void invalidate() {
        lock.writeLock().lock();
        try {
            snapshot = Snapshot.empty();
            version.incrementAndGet();
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Marca o index como sujo e, opcionalmente, reconstrói imediatamente.
     *
     * @param rebuildNow se {@code true}, reconstrói de forma síncrona
     */
    public void invalidate(boolean rebuildNow) {
        invalidate();
        if (rebuildNow) {
            current();
        }
    }

    /** Devolve a versão atual do index (incrementa a cada invalidação). */
    public long version() {
        return version.get();
    }

    /**
     * Devolve o snapshot atual, reconstruindo-o a partir do vault se estiver sujo.
     *
     * @return snapshot não nulo (pode ser vazio)
     */
    public Snapshot current() {
        Snapshot s = snapshot;
        if (s != Snapshot.EMPTY) {
            return s;
        }
        lock.writeLock().lock();
        try {
            if (snapshot == Snapshot.EMPTY) {
                snapshot = Snapshot.build();
                version.incrementAndGet();
            }
            return snapshot;
        } finally {
            lock.writeLock().unlock();
        }
    }

    // ------------------------------------------------------------------
    // Calendar queries
    // ------------------------------------------------------------------

    /**
     * Devolve os eventos que começam no dia indicado.
     *
     * @param day o dia
     * @return lista não nula de eventos
     */
    public List<Event> eventsOn(LocalDate day) {
        if (day == null) {
            return List.of();
        }
        List<Event> out = new ArrayList<>();
        for (Event e : current().events) {
            if (e.getStartDateTime() != null && e.getStartDateTime().toLocalDate().equals(day)) {
                out.add(e);
            }
        }
        return out;
    }

    /**
     * Devolve as tarefas cuja deadline cai no dia indicado.
     *
     * @param day o dia
     * @return lista não nula de tarefas
     */
    public List<Task> tasksDueOn(LocalDate day) {
        if (day == null) {
            return List.of();
        }
        List<Task> out = new ArrayList<>();
        for (Task t : current().tasks) {
            if (t.getDeadline() != null && t.getDeadline().toLocalDate().equals(day)) {
                out.add(t);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Convenience accessors (read-through cache)
    // ------------------------------------------------------------------

    public List<Person> people() {
        return current().people;
    }

    public List<Project> projects() {
        return current().projects;
    }

    public List<Event> events() {
        return current().events;
    }

    public List<Task> tasks() {
        return current().tasks;
    }

    public List<Note> notes() {
        return current().notes;
    }

    /**
     * Snapshot imutável de todas as entidades do vault num determinado momento.
     * As listas são não modificáveis.
     */
    public static final class Snapshot {

        private static final Snapshot EMPTY = new Snapshot(
                List.of(), List.of(), List.of(), List.of(), List.of());

        private final List<Person> people;
        private final List<Project> projects;
        private final List<Event> events;
        private final List<Task> tasks;
        private final List<Note> notes;

        private Snapshot(List<Person> people, List<Project> projects,
                          List<Event> events, List<Task> tasks, List<Note> notes) {
            this.people = Collections.unmodifiableList(people);
            this.projects = Collections.unmodifiableList(projects);
            this.events = Collections.unmodifiableList(events);
            this.tasks = Collections.unmodifiableList(tasks);
            this.notes = Collections.unmodifiableList(notes);
        }

        static Snapshot empty() {
            return EMPTY;
        }

        static Snapshot build() {
            List<Person> people = new ArrayList<>();
            List<Project> projects = new ArrayList<>();
            List<Event> events = new ArrayList<>();
            List<Task> tasks = new ArrayList<>();
            List<Note> notes = new ArrayList<>();

            if (VaultManager.isVaultInitialized()) {
                try {
                    people.addAll(VaultManager.listPeople());
                } catch (Exception ignored) {
                    // O index degrada graciosamente: entidades ilegíveis são omitidas.
                }
                try {
                    projects.addAll(VaultManager.listProjects());
                } catch (Exception ignored) {
                    // Ignorar tipos ilegíveis.
                }
                try {
                    events.addAll(VaultManager.listEvents());
                } catch (Exception ignored) {
                    // Ignorar tipos ilegíveis.
                }
                try {
                    tasks.addAll(VaultManager.listTasks());
                } catch (Exception ignored) {
                    // Ignorar tipos ilegíveis.
                }
                try {
                    notes.addAll(VaultManager.listNotes());
                } catch (Exception ignored) {
                    // Ignorar tipos ilegíveis.
                }
            }

            return new Snapshot(people, projects, events, tasks, notes);
        }
    }
}
