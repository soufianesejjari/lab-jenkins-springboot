package fr.formation.tasks.api;

import fr.formation.tasks.domain.Task;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps des requêtes POST / PUT. Le champ "done" est facultatif (false par défaut).
 */
public record TaskRequest(
        @NotBlank(message = "le titre est obligatoire")
        @Size(max = Task.TITLE_MAX_LENGTH, message = "le titre ne doit pas dépasser {max} caractères")
        String title,
        Boolean done) {

    public boolean doneOrFalse() {
        return Boolean.TRUE.equals(done);
    }
}
