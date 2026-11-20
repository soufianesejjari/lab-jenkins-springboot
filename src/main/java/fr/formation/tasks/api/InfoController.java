package fr.formation.tasks.api;

import fr.formation.tasks.repository.TaskRepository;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Preuve de concept « une image, plusieurs environnements ».
 * <p>
 * GET /api/info montre ce que l'application a reçu de son environnement :
 * nom de l'environnement, version de l'image, base de données réellement connectée.
 * Le code est identique en dev et en prod : seules les variables d'environnement changent.
 */
@RestController
public class InfoController {

    private final DataSource dataSource;
    private final TaskRepository repository;
    private final Environment environment;
    private final String appEnv;
    private final String version;
    private final String commit;

    public InfoController(DataSource dataSource,
                          TaskRepository repository,
                          Environment environment,
                          @Value("${info.app.env}") String appEnv,
                          @Value("${info.app.version}") String version,
                          @Value("${info.app.commit}") String commit) {
        this.dataSource = dataSource;
        this.repository = repository;
        this.environment = environment;
        this.appEnv = appEnv;
        this.version = version;
        this.commit = commit;
    }

    @GetMapping("/api/info")
    public Map<String, Object> info() throws SQLException {
        Map<String, Object> database = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            database.put("product", meta.getDatabaseProductName());
            database.put("name", connection.getCatalog());
            database.put("user", meta.getUserName());
        }

        Map<String, Object> info = new LinkedHashMap<>();
        info.put("env", appEnv);
        info.put("profiles", List.of(environment.getActiveProfiles()));
        info.put("version", version);
        info.put("commit", commit);
        info.put("database", database);
        info.put("tasks", repository.count());
        return info;
    }
}
