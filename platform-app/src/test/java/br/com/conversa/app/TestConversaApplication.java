package br.com.conversa.app;

import org.springframework.boot.SpringApplication;

/**
 * Sobe a aplicação localmente com Postgres em Testcontainers, sem docker-compose.
 */
public class TestConversaApplication {

    public static void main(String[] args) {
        SpringApplication.from(ConversaApplication::main)
                .with(TestcontainersConfiguration.class)
                .run(args);
    }
}
