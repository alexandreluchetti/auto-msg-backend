# ADR-007 — Monólito modular em Maven multi-módulo, Java 21 e Spring Boot 4.1

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

Um desenvolvedor, um cliente inicial, deploy numa VPS — mas com o objetivo de virar produto
multi-segmento. Precisamos de fronteiras claras sem o custo operacional de microsserviços.

## Decisão

- **Um deployable** (`platform-app`), dividido em módulos Maven:
  `platform-core`, `channel-whatsapp`, `llm-anthropic`, `segment-restaurant`, `platform-app`.
- `platform-core` em Java puro (sem Spring), contém só tipos e SPIs.
- Adaptadores e segmentos dependem só do `platform-core`; quem os junta é o `platform-app`.
- **ArchUnit** (`ArchitectureTest`) verifica as dependências no build.
- Java 21 (LTS, virtual threads), Spring Boot 4.1.x, PostgreSQL 17 + pgvector, Flyway, JPA
  (Hibernate) para agregados e `JdbcClient` para filas/consultas específicas.
- Testes de integração com **Testcontainers** (`*IT`, rodam no `mvn verify`).

## Alternativas consideradas

- **Microsserviços (webhook, motor, sender separados):** escalabilidade independente que não
  precisamos; mais deploy, rede e consistência distribuída.
- **Módulo único com pacotes:** mais simples, mas fronteiras só por convenção — o Maven
  impede dependências erradas em tempo de compilação.
- **Spring Modulith:** boa opção para fronteiras *dentro* do `platform-app`; pode ser adotado
  depois sem conflito com esta decisão.
- **Gradle:** preferência do time é Maven.

## Consequências

- (+) Um processo para operar; refatoração barata; fronteiras explícitas.
- (+) Extrair um módulo para serviço próprio no futuro é viável (ex.: `channel-whatsapp`).
- (−) Um deploy afeta tudo; aceitável no estágio atual.
