package fr.formation.tasks.api;

import java.time.Instant;
import java.util.Map;

/**
 * Corps JSON renvoyé en cas d'erreur (400, 404).
 */
public record ApiError(Instant timestamp, int status, String error, String message, String path,
                       Map<String, String> fieldErrors) {
}
