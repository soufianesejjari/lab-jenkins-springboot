package fr.formation.tasks.api;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fr.formation.tasks.domain.Task;
import fr.formation.tasks.service.InvalidTaskException;
import fr.formation.tasks.service.TaskNotFoundException;
import fr.formation.tasks.service.TaskService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Tests de la couche web seule : le service est simulé (@MockitoBean).
 */
@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TaskService service;

    @Test
    void listReturnsTasks() throws Exception {
        when(service.findAll()).thenReturn(List.of(new Task("Lire le Jenkinsfile", false)));

        mvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Lire le Jenkinsfile"));
    }

    @Test
    void getUnknownTaskReturns404WithJsonBody() throws Exception {
        when(service.findById(99L)).thenThrow(new TaskNotFoundException(99L));

        mvc.perform(get("/api/tasks/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Tâche introuvable : 99"))
                .andExpect(jsonPath("$.path").value("/api/tasks/99"));
    }

    @Test
    void createReturns201WithLocation() throws Exception {
        when(service.create(anyString(), anyBoolean())).thenReturn(new Task("Écrire les tests", false));

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Écrire les tests\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.title").value("Écrire les tests"));
    }

    @Test
    void createWithBlankTitleReturns400WithFieldErrors() throws Exception {
        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors.title").value("le titre est obligatoire"));
    }

    @Test
    void createWithTooLongTitleReturns400() throws Exception {
        String longTitle = "x".repeat(Task.TITLE_MAX_LENGTH + 1);
        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + longTitle + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.title").exists());
    }

    @Test
    void createWithInvalidJsonReturns400() throws Exception {
        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{pas du json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Corps JSON illisible"));
    }

    @Test
    void serviceValidationErrorReturns400() throws Exception {
        when(service.create(anyString(), anyBoolean())).thenThrow(new InvalidTaskException("Le titre ne doit pas être vide"));

        mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\" a \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Le titre ne doit pas être vide"));
    }

    @Test
    void updateReturnsUpdatedTask() throws Exception {
        when(service.update(eq(1L), anyString(), anyBoolean())).thenReturn(new Task("Fait", true));

        mvc.perform(put("/api/tasks/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Fait\",\"done\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.done").value(true));
    }

    @Test
    void deleteReturns204() throws Exception {
        mvc.perform(delete("/api/tasks/1"))
                .andExpect(status().isNoContent());
        verify(service).delete(1L);
    }
}
