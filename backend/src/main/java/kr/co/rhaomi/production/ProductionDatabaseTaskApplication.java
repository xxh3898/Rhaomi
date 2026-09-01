package kr.co.rhaomi.production;

import java.util.Arrays;
import java.util.Map;
import kr.co.rhaomi.publisher.PublisherApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

public final class ProductionDatabaseTaskApplication {

    public static final String MIGRATE_ARGUMENT = "--rhaomi.production-task=migrate";
    public static final String SCHEMA_VALIDATE_ARGUMENT =
            "--rhaomi.production-task=schema-validate";
    private static final String TASK_OPTION = "--rhaomi.production-task";
    private static final String TASK_PREFIX = TASK_OPTION + "=";

    private ProductionDatabaseTaskApplication() {}

    public enum Task {
        MIGRATE(true),
        SCHEMA_VALIDATE(false);

        private final boolean flywayEnabled;

        Task(boolean flywayEnabled) {
            this.flywayEnabled = flywayEnabled;
        }

        boolean flywayEnabled() {
            return flywayEnabled;
        }
    }

    public static boolean hasModeArgument(String[] arguments) {
        return Arrays.stream(arguments)
                .anyMatch(argument ->
                        argument.equals(TASK_OPTION) || argument.startsWith(TASK_PREFIX));
    }

    public static Task parseTask(String[] arguments) {
        var taskArguments = Arrays.stream(arguments)
                .filter(argument ->
                        argument.equals(TASK_OPTION) || argument.startsWith(TASK_PREFIX))
                .toList();
        if (taskArguments.size() != 1 || PublisherApplication.hasModeArgument(arguments)) {
            throw new IllegalArgumentException("Invalid production database task mode");
        }

        return switch (taskArguments.getFirst()) {
            case MIGRATE_ARGUMENT -> Task.MIGRATE;
            case SCHEMA_VALIDATE_ARGUMENT -> Task.SCHEMA_VALIDATE;
            default -> throw new IllegalArgumentException("Invalid production database task mode");
        };
    }

    public static ConfigurableApplicationContext run(String[] arguments) {
        return createApplication(parseTask(arguments)).run(arguments);
    }

    static SpringApplication createApplication(Task task) {
        var application = new SpringApplication(ProductionDatabaseTaskConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("production-database-task");

        var boundaryProperties = Map.<String, Object>of(
                "spring.main.web-application-type", "none",
                "spring.flyway.enabled", task.flywayEnabled(),
                "spring.jpa.hibernate.ddl-auto", "validate");
        application.setDefaultProperties(boundaryProperties);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                event.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource(
                                "productionDatabaseTaskBoundary", boundaryProperties)));
        return application;
    }
}
