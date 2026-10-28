# ADR-006 — Base de conhecimento: dados estruturados via ferramentas, FAQ no prompt, RAG (pgvector) só quando precisar

- **Status:** Aceito
- **Data:** 2026-10-28

## Contexto

Cada empresa tem cardápio/catálogo, preços, horários, área de entrega, FAQ e políticas.
Para um restaurante, quase tudo é estruturado e pequeno; texto livre (FAQ, políticas) cabe
em poucos milhares de tokens.

## Decisão

1. **Dados estruturados** (catálogo, preços, opções, horários, zonas de entrega) em tabelas e
   expostos **somente por ferramentas** (`search_menu`, `get_business_hours`,
   `get_delivery_fee`...). Preço nunca vem do texto do prompt.
2. **Conhecimento textual pequeno** (FAQ, políticas) entra no prompt do sistema, dentro do
   prefixo cacheado. Sem pipeline de embeddings no MVP.
3. **RAG com pgvector** quando o conhecimento textual de um tenant passar de ~20–30 mil tokens
   ou para segmentos com documentos longos (clínicas, lojas com catálogos grandes): tabela
   `kb_chunk` com `vector`, busca híbrida (vetor + `tsvector` em português), exposta como
   ferramenta `search_knowledge`. A extensão `vector` já vem na migração inicial e a imagem
   local é `pgvector/pgvector`.
4. Conteúdo versionado (`kb_document.version`) para saber com qual versão o bot respondeu.

## Alternativas consideradas

- **RAG desde o início:** custo de embeddings e complexidade sem ganho para um cardápio.
- **Banco vetorial dedicado (Qdrant, Pinecone, Weaviate):** mais uma peça; pgvector atende
  com folga o volume e mantém RLS/backup no mesmo banco.
- **Cardápio inteiro no prompt:** funciona para cardápios pequenos, mas o preço viria do
  prompt (difícil de auditar) e o cache seria invalidado a cada mudança de preço.

## Consequências

- (+) Respostas de preço/disponibilidade sempre consistentes com o banco.
- (+) Caminho claro para RAG sem trocar de banco.
- (−) Importação do cardápio (planilha → tabelas) é trabalho necessário no MVP.
