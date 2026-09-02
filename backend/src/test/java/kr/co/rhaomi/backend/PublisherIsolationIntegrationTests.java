package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.co.rhaomi.publisher.PublisherControlLoop;
import kr.co.rhaomi.publisher.PublisherLifecycle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PublisherIsolationIntegrationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void should_notCreatePublisherLoopOrLifecycle_when_normalBackendStarts() {
        assertTrue(applicationContext.getBeansOfType(PublisherControlLoop.class).isEmpty());
        assertTrue(applicationContext.getBeansOfType(PublisherLifecycle.class).isEmpty());
    }
}
