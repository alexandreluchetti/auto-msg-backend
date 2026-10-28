package br.com.conversa.app;

import org.springframework.boot.SpringApplication;

/**
 * Runs the application locally with PostgreSQL in Testcontainers, without docker-compose.
 */
public class TestConversaApplication {

    public static void main(String[] args) {
        SpringApplication.from(ConversaApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
