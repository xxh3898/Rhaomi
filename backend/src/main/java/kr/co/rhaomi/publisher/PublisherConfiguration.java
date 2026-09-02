package kr.co.rhaomi.publisher;

import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kr.co.rhaomi.backend.publication.PublicationStateService;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
    HibernateJpaAutoConfiguration.class,
    DataJpaRepositoriesAutoConfiguration.class,
    SecurityAutoConfiguration.class
})
@EnableConfigurationProperties({PublisherProperties.class, PublicationExecutorProperties.class})
@Import(PublicationStateService.class)
public class PublisherConfiguration {

    @Bean
    PublisherSettings publisherSettings(PublisherProperties properties) {
        return properties.toSettings();
    }

    @Bean
    PublicationStateOperations publicationStateOperations(
            PublicationStateService publicationStateService) {
        return new PublicationStateServiceAdapter(publicationStateService);
    }

    @Bean
    Clock publisherClock() {
        return Clock.systemUTC();
    }

    @Bean
    PublisherStopSignal publisherStopSignal() {
        return new PublisherStopSignal();
    }

    @Bean
    PublisherControlLoop.PublisherSleeper publisherSleeper() {
        return (duration, stopSignal) -> stopSignal.await(duration);
    }

    @Bean
    PublicationBuildExecutor publicationBuildExecutor(
            PublicationExecutorProperties properties) {
        return new NodePublicationBuildExecutor(properties.toSettings());
    }

    @Bean(destroyMethod = "shutdownNow")
    ExecutorService publicationBuildTaskExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("rhaomi-publisher-build-", 0).factory());
    }

    @Bean
    PublisherControlLoop.PublicationBuildTaskFactory publicationBuildTaskFactory(
            PublicationBuildExecutor buildExecutor,
            ExecutorService publicationBuildTaskExecutor) {
        return new AsyncPublicationBuildTaskFactory(
                buildExecutor, publicationBuildTaskExecutor);
    }

    @Bean
    PublicationExecutionLock publicationExecutionLock(PublisherSettings settings) {
        return new FileSystemPublicationExecutionLock(settings.lockFile());
    }

    @Bean
    PublisherControlLoop publisherControlLoop(
            PublicationStateOperations stateOperations,
            PublisherControlLoop.PublicationBuildTaskFactory taskFactory,
            PublicationExecutionLock executionLock,
            Clock publisherClock,
            PublisherControlLoop.PublisherSleeper sleeper,
            PublisherSettings settings,
            PublisherStopSignal stopSignal) {
        return new PublisherControlLoop(
                stateOperations,
                taskFactory,
                executionLock,
                publisherClock,
                sleeper,
                settings,
                stopSignal);
    }

    @Bean
    PublisherLifecycle publisherLifecycle(
            PublisherControlLoop controlLoop,
            PublisherStopSignal stopSignal,
            PublisherSettings settings,
            PublisherProperties properties) {
        return new PublisherLifecycle(
                controlLoop, stopSignal, settings, properties.isAutoStart());
    }
}
