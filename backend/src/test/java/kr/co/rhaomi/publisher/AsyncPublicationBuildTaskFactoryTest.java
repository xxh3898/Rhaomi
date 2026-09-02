package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class AsyncPublicationBuildTaskFactoryTest {

    @Test
    void should_returnTypedResult_when_executorCompletes() throws Exception {
        var generation = new long[1];
        var executorService = Executors.newSingleThreadExecutor();
        try {
            var factory = new AsyncPublicationBuildTaskFactory(target -> {
                generation[0] = target;
                return PublicationBuildResult.NO_PUBLIC_CHANGE;
            }, executorService);

            try (var task = factory.start(42)) {
                var result = task.await(Duration.ofSeconds(1));
                assertTrue(result.completed());
                assertEquals(PublicationBuildResult.NO_PUBLIC_CHANGE, result.result());
                assertEquals(42L, generation[0]);
            }
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    void should_hideExecutorExceptionDetail_when_executorThrows() {
        var executorService = Executors.newSingleThreadExecutor();
        try {
            var factory = new AsyncPublicationBuildTaskFactory(target -> {
                throw new IllegalStateException("credential=/private/secret");
            }, executorService);

            try (var task = factory.start(7)) {
                var exception = assertThrows(
                        PublisherControlLoop.PublicationBuildExecutionException.class,
                        () -> task.await(Duration.ofSeconds(1)));
                assertEquals("Publication build execution failed", exception.getMessage());
                assertNull(exception.getCause());
            }
        } finally {
            executorService.shutdownNow();
        }
    }
}
