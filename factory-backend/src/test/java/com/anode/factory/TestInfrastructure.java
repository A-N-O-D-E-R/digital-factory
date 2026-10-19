package com.anode.factory;

import com.anode.factory.forge.FakeForge;
import com.anode.factory.ticket.FakeTicketSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One PostgreSQL container for every integration test of the run, a fake GitHub and its issues. */
@TestConfiguration(proxyBeanMethods = false)
public class TestInfrastructure {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine");
    }

    @Bean
    FakeForge fakeForge() {
        return new FakeForge();
    }

    @Bean
    FakeTicketSource fakeTicketSource() {
        return new FakeTicketSource();
    }
}
