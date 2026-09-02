package kr.co.rhaomi.publisher;

import java.util.Arrays;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;

public final class PublisherApplication {

    public static final String MODE_ARGUMENT = "--rhaomi.publisher.mode=control-loop";
    private static final String MODE_PREFIX = "--rhaomi.publisher.mode=";

    private PublisherApplication() {}

    public static boolean hasModeArgument(String[] arguments) {
        return Arrays.stream(arguments).anyMatch(argument -> argument.startsWith(MODE_PREFIX));
    }

    public static boolean isRequested(String[] arguments) {
        var modeArguments = Arrays.stream(arguments)
                .filter(argument -> argument.startsWith(MODE_PREFIX))
                .toList();
        return modeArguments.size() == 1 && MODE_ARGUMENT.equals(modeArguments.getFirst());
    }

    public static ConfigurableApplicationContext run(String[] arguments) {
        return createApplication().run(arguments);
    }

    static SpringApplication createApplication() {
        var application = new SpringApplication(PublisherConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setAdditionalProfiles("publisher");
        application.setDefaultProperties(Map.of(
                "spring.main.web-application-type", "none",
                "spring.flyway.enabled", "false"));
        application.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event ->
                event.getEnvironment()
                        .getPropertySources()
                        .addFirst(new MapPropertySource(
                                "publisherNonWebBoundary",
                                Map.of(
                                        "spring.main.web-application-type", "none",
                                        "spring.flyway.enabled", "false"))));
        return application;
    }
}
