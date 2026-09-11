package kr.co.rhaomi.production;

import java.util.Arrays;
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
    public static final String INITIAL_ADMIN_ARGUMENT =
            "--rhaomi.production-task=initial-admin";
    private static final String TASK_OPTION = "--rhaomi.production-task";
    private static final String TASK_PREFIX = TASK_OPTION + "=";

    private ProductionDatabaseTaskApplication() {}

    public enum Task {
        MIGRATE(true, "production-database-task", ProductionDatabaseTaskConfiguration.class),
        SCHEMA_VALIDATE(
                false, "production-database-task", ProductionDatabaseTaskConfiguration.class),
        INITIAL_ADMIN(
                false,
                "production-initial-admin-task",
                ProductionInitialAdminTaskConfiguration.class);

        private final boolean flywayEnabled;
        private final String profile;
        private final Class<?> configurationClass;

        Task(boolean flywayEnabled, String profile, Class<?> configurationClass) {
            this.flywayEnabled = flywayEnabled;
            this.profile = profile;
            this.configurationClass = configurationClass;
        }

        boolean flywayEnabled() {
            return flywayEnabled;
        }

        String profile() {
            return profile;
        }

        Class<?> configurationClass() {
            return configurationClass;
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
            case INITIAL_ADMIN_ARGUMENT -> Task.INITIAL_ADMIN;
            default -> throw new IllegalArgumentException("Invalid production database task mode");
        };
    }

    public static ConfigurableApplicationContext run(String[] arguments) {
        return createApplication(parseTask(arguments)).run(arguments);
    }

    static SpringApplication createApplication(Task task) {
        var application = new SpringApplication(task.configurationClass());
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles(task.profile());

        var boundaryProperties = new java.util.HashMap<String, Object>();
        boundaryProperties.put("spring.main.web-application-type", "none");
        boundaryProperties.put("spring.flyway.enabled", task.flywayEnabled());
        boundaryProperties.put("spring.jpa.hibernate.ddl-auto", "validate");
        if (task == Task.INITIAL_ADMIN) {
            boundaryProperties.put(
                    "logging.level.org.hibernate.engine.jdbc.spi.SqlExceptionHelper", "OFF");
        }
        application.setDefaultProperties(boundaryProperties);
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                event.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource(
                                "productionDatabaseTaskBoundary", boundaryProperties)));
        return application;
    }

    static ConfigurableApplicationContext runInitialAdmin(
            String[] arguments,
            InitialAdminCredentialSource credentialSource,
            java.io.PrintStream output) {
        var task = parseTask(arguments);
        if (task != Task.INITIAL_ADMIN) {
            throw new IllegalArgumentException("Invalid production initial admin task mode");
        }

        var application = createApplication(task);
        application.addInitializers(context -> {
            context.getBeanFactory()
                    .registerSingleton("initialAdminCredentialSource", credentialSource);
            context.getBeanFactory().registerSingleton("initialAdminOutput", output);
        });
        return application.run(arguments);
    }
}
