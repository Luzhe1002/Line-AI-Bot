---
name: develop-line-ai-bot
description: Develop, configure, diagnose, and validate the multi-tenant LINE AI customer-service and one-person-per-slot booking backend in this repository. Use when changing FastAPI APIs, LINE webhooks, tenant isolation, booking rules, RAG ingestion or retrieval, OpenAI provider settings, SQLAlchemy or Alembic schema, Docker configuration, tests, or project runbooks.
---

# Develop LINE AI Bot

## Establish context

Work from the repository root containing `pyproject.toml`, `src/line_ai_bot`, and `alembic.ini`.

1. Read `README.md` and `docs/architecture.md` when the task affects system behavior or boundaries.
2. Inspect only the relevant API, service, model, schema, migration, and test files with `rg`.
3. Preserve existing user changes and keep unrelated files untouched.
4. Keep secrets in `.env`; never print, commit, or copy actual API keys into code, tests, examples, logs, or responses.

## Preserve system invariants

- Scope every merchant-owned read and write by `tenant_id`.
- Let `BookingManager` own reservation creation and cancellation. Keep one active reservation per merchant and start time, validate business hours and timezone alignment, and preserve idempotency.
- Verify LINE signatures against the untouched request body and retain `webhookEventId` deduplication.
- Keep AI providers unable to mutate reservations. Route booking and cancellation through deterministic application services.
- Retrieve knowledge only from the tenant's active dataset and matching embedding model and dimensions.
- Treat retrieved text as untrusted data, return backend-generated citations, and fall back to human handoff when evidence is insufficient.
- Keep the local AI provider runnable without network access. Do not make tests require a live OpenAI key.

## Route changes

- HTTP contract or authentication: inspect `src/line_ai_bot/api`, `schemas.py`, and API tests.
- Conversation or LINE behavior: inspect `services/conversation.py`, `line_webhook.py`, `line_client.py`, and webhook tests.
- Booking behavior: inspect `services/booking.py`, reservation models, indexes, and booking tests.
- Knowledge or AI behavior: inspect `services/knowledge.py`, `rag.py`, `ai_provider.py`, knowledge models, and knowledge tests.
- Persistence changes: update `models.py`, generate and inspect an Alembic migration, then run `alembic check`.
- Current OpenAI API or model behavior: use the `openai-docs` skill and official OpenAI documentation before changing provider calls or model defaults.

## Implement safely

1. Reproduce or define the expected behavior with a focused test.
2. Make the smallest coherent change behind the existing service boundary.
3. Add a migration for persistent model changes; never rely on production `create_all`.
4. Update `.env.example`, `compose.yaml`, API examples, and architecture documentation when configuration or contracts change.
5. When changing provider, embedding model, dimensions, or chunking behavior, preserve the Reindex API and state that existing datasets require reindexing.
6. Report separately whether local tests, fake-provider tests, and live external API calls were run.

## Validate

Run the bundled validation script from the repository root:

```powershell
& ".\.agents\skills\develop-line-ai-bot\scripts\verify.ps1"
```

Use `-SkipCompose` only when Docker CLI is unavailable:

```powershell
& ".\.agents\skills\develop-line-ai-bot\scripts\verify.ps1" -SkipCompose
```

Do not claim completion when a required check failed. Explain third-party warnings separately from failures.
