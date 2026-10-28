# ADR-006 — Knowledge base: structured data through tools, FAQ in the prompt, RAG (pgvector) only when needed

- **Status:** Accepted
- **Date:** 2026-10-28

## Context

Each business has a menu/catalog, prices, opening hours, delivery area, FAQ and policies. For a
restaurant, almost everything is structured and small; free text (FAQ, policies) fits in a few
thousand tokens.

## Decision

1. **Structured data** (catalog, prices, options, opening hours, delivery zones) lives in
   tables and is exposed **only through tools** (`search_menu`, `get_business_hours`,
   `get_delivery_fee`...). Prices never come from prompt text.
2. **Small textual knowledge** (FAQ, policies) goes into the system prompt, inside the cached
   prefix. No embeddings pipeline in the MVP.
3. **RAG with pgvector** when a tenant's textual knowledge exceeds ~20–30 thousand tokens, or
   for segments with long documents (clinics, shops with large catalogs): a `kb_chunk` table
   with `vector`, hybrid search (vector + Portuguese `tsvector`), exposed as the
   `search_knowledge` tool. The `vector` extension already comes in the initial migration and
   the local image is `pgvector/pgvector`.
4. Versioned content (`kb_document.version`) to know which version the bot answered from.

## Alternatives considered

- **RAG from the start:** embedding cost and complexity with no gain for a menu.
- **Dedicated vector database (Qdrant, Pinecone, Weaviate):** one more piece; pgvector easily
  handles the volume and keeps RLS/backup in the same database.
- **Whole menu in the prompt:** works for small menus, but prices would come from the prompt
  (hard to audit) and the cache would be invalidated on every price change.

## Consequences

- (+) Price/availability answers always consistent with the database.
- (+) A clear path to RAG without changing databases.
- (−) Importing the menu (spreadsheet → tables) is required work in the MVP.
