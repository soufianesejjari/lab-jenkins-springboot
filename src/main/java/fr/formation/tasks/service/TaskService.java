package fr.formation.tasks.service;

import fr.formation.tasks.domain.Task;
import fr.formation.tasks.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Règles métier des tâches.
 */
@Service
@Transactional(readOnly = true)
public class TaskService {

    private final TaskRepository repository;
    private final Clock clock;

    public TaskService(TaskRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<Task> findAll() {
        return repository.findAllByOrderByIdAsc();
    }

    public Task findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
    }

    /**
     * Règle métier : le titre est nettoyé (trim) et ne peut pas être vide une fois nettoyé.
     * C'est cette règle que le LAB vous fait casser volontairement (étape 5).
     */
    @Transactional
    public Task create(String title, boolean done) {
        Task task = new Task(normalizeTitle(title), done);
        task.prePersist(Instant.now(clock));
        return repository.save(task);
    }

    @Transactional
    public Task update(Long id, String title, boolean done) {
        Task task = findById(id);
        task.setTitle(normalizeTitle(title));
        task.setDone(done);
        return repository.save(task);
    }

    @Transactional
    public void delete(Long id) {
        Task task = findById(id);
        repository.delete(task);
    }

    static String normalizeTitle(String title) {
        String normalized = title == null ? "" : title.trim();
        if (normalized.isEmpty()) {
            throw new InvalidTaskException("Le titre ne doit pas être vide");
        }
        if (normalized.length() > Task.TITLE_MAX_LENGTH) {
            throw new InvalidTaskException("Le titre ne doit pas dépasser " + Task.TITLE_MAX_LENGTH + " caractères");
        }
        return normalized;
    }
}
