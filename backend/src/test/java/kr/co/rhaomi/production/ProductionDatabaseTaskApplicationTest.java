package kr.co.rhaomi.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import kr.co.rhaomi.publisher.PublisherApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;

class ProductionDatabaseTaskApplicationTest {

    @Test
    void should_requireExactSingleTaskArgument_when_productionDatabaseTaskIsSelected() {
        assertTrue(ProductionDatabaseTaskApplication.hasModeArgument(
                new String[] {ProductionDatabaseTaskApplication.MIGRATE_ARGUMENT}));
        assertEquals(
                ProductionDatabaseTaskApplication.Task.MIGRATE,
                ProductionDatabaseTaskApplication.parseTask(
                        new String[] {ProductionDatabaseTaskApplication.MIGRATE_ARGUMENT}));
        assertEquals(
                ProductionDatabaseTaskApplication.Task.SCHEMA_VALIDATE,
                ProductionDatabaseTaskApplication.parseTask(
                        new String[] {ProductionDatabaseTaskApplication.SCHEMA_VALIDATE_ARGUMENT}));

        assertFalse(ProductionDatabaseTaskApplication.hasModeArgument(
                new String[] {"--spring.flyway.enabled=true"}));
        assertTrue(ProductionDatabaseTaskApplication.hasModeArgument(
                new String[] {"--rhaomi.production-task"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProductionDatabaseTaskApplication.parseTask(
                        new String[] {"--rhaomi.production-task"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProductionDatabaseTaskApplication.parseTask(
                        new String[] {"--rhaomi.production-task=other"}));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProductionDatabaseTaskApplication.parseTask(new String[] {
                    ProductionDatabaseTaskApplication.MIGRATE_ARGUMENT,
                    ProductionDatabaseTaskApplication.SCHEMA_VALIDATE_ARGUMENT
                }));
        assertThrows(
                IllegalArgumentException.class,
                () -> ProductionDatabaseTaskApplication.parseTask(new String[] {
                    ProductionDatabaseTaskApplication.MIGRATE_ARGUMENT,
                    PublisherApplication.MODE_ARGUMENT
                }));
    }

    @Test
    void should_forceNonWebApplication_when_productionDatabaseTaskIsCreated() {
        assertEquals(
                WebApplicationType.NONE,
                ProductionDatabaseTaskApplication
                        .createApplication(ProductionDatabaseTaskApplication.Task.MIGRATE)
                        .getWebApplicationType());
        assertEquals(
                WebApplicationType.NONE,
                ProductionDatabaseTaskApplication
                        .createApplication(ProductionDatabaseTaskApplication.Task.SCHEMA_VALIDATE)
                        .getWebApplicationType());
    }
}
