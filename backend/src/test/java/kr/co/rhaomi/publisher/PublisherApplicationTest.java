package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;

class PublisherApplicationTest {

    @Test
    void should_configureNonWebApplication_when_publisherModeIsCreated() {
        assertEquals(
                WebApplicationType.NONE,
                PublisherApplication.createApplication().getWebApplicationType());
    }

    @Test
    void should_requireExactExplicitModeArgument_when_publisherIsSelected() {
        assertTrue(PublisherApplication.isRequested(
                new String[] {PublisherApplication.MODE_ARGUMENT}));
        assertFalse(PublisherApplication.isRequested(
                new String[] {"--spring.profiles.active=publisher"}));
        assertFalse(PublisherApplication.isRequested(
                new String[] {"--rhaomi.publisher.mode=other"}));
        assertFalse(PublisherApplication.isRequested(new String[] {
            PublisherApplication.MODE_ARGUMENT,
            "--rhaomi.publisher.mode=other"
        }));
        assertTrue(PublisherApplication.hasModeArgument(
                new String[] {"--rhaomi.publisher.mode=other"}));
    }
}
