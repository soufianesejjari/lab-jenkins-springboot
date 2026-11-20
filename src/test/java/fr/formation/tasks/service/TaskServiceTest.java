package fr.formation.tasks.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import fr.formation.tasks.domain.Task;
import fr.formation.tasks.repository.TaskRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests unitaires : le dépôt est simulé avec Mockito, aucune base de données.
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Mock
    private TaskRepository repository;

    private TaskService service;

    @BeforeEach
    void setUp() {
        service = new TaskService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("create() supprime les espaces autour du titre")
    void createTrimsTitle() {
        when(repository.save(any(Task.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Task created = service.create("  Préparer le TP Jenkins  ", false);

        assertThat(created.getTitle()).isEqualTo("Préparer le TP Jenkins");
        assertThat(created.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("create() refuse un titre composé uniquement d'espaces")
    void createRejectsBlankTitle() {
        assertThatThrownBy(() -> service.create("   ", false))
                .isInstanceOf(InvalidTaskException.class)
                .hasMessageContaining("vide");
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("create() refuse un titre trop long")
    void createRejectsTooLongTitle() {
        assertThatThrownBy(() -> service.create("x".repeat(Task.TITLE_MAX_LENGTH + 1), false))
                .isInstanceOf(InvalidTaskException.class);
    }

    @Test
    void findByIdThrowsWhenMissing() {
        when(repository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(42L))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessageContaining("42");
    }

    @Test
    void findAllDelegatesToRepository() {
        when(repository.findAllByOrderByIdAsc()).thenReturn(List.of(new Task("a", false)));

        assertThat(service.findAll()).hasSize(1);
    }

    @Test
    void updateChangesTitleAndStatus() {
        Task existing = new Task("ancien", false);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));
        when(repository.save(existing)).thenReturn(existing);

        Task updated = service.update(1L, " nouveau ", true);

        assertThat(updated.getTitle()).isEqualTo("nouveau");
        assertThat(updated.isDone()).isTrue();
    }

    @Test
    void deleteRemovesExistingTask() {
        Task existing = new Task("à supprimer", false);
        when(repository.findById(1L)).thenReturn(Optional.of(existing));

        service.delete(1L);

        verify(repository).delete(existing);
    }
}
