package fr.formation.tasks.service;

public class TaskNotFoundException extends RuntimeException {

    public TaskNotFoundException(Long id) {
        super("Tâche introuvable : " + id);
    }
}
