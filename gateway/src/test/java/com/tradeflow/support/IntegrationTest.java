package com.tradeflow.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base for full-context integration tests (NFR-TEST-02).
 *
 * <p><b>Singleton container pattern:</b> the Postgres container is started ONCE in a static
 * initializer and never explicitly stopped (Ryuk removes it at JVM exit). We deliberately do NOT
 * use {@code @Testcontainers}/{@code @Container}, whose per-class start/stop would tear the
 * container down after one test class while a *cached* Spring context (shared by another test
 * class) still points at the now-dead port. {@code @ServiceConnection} wires the datasource to it,
 * so Flyway builds the schema and Hibernate validates against a real database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// The 'test' profile (application-test.yml) turns every background feature OFF by default, even for
// subclasses that declare their own @SpringBootTest — @ActiveProfiles is inherited, so their
// property overrides only need to ENABLE the one feature they exercise.
@ActiveProfiles("test")
public abstract class IntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    static {
        POSTGRES.start();
    }
}
