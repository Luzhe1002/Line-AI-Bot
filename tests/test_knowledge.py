from types import SimpleNamespace

from sqlalchemy import func, select

from line_ai_bot.config import Settings
from line_ai_bot.database import SessionLocal
from line_ai_bot.models import KnowledgeChunk
from line_ai_bot.services.ai_provider import (
    GroundingContext,
    OpenAiProvider,
    build_safety_identifier,
)
from line_ai_bot.services.rag import split_into_chunks
from tests.conftest import provision_tenant


def publish_document(
    client,
    tenant: dict,
    headers: dict[str, str],
    *,
    title: str,
    content: str,
) -> str:
    datasets = client.get(
        f"/api/v1/tenants/{tenant['id']}/datasets",
        headers=headers,
    ).json()
    dataset_id = datasets[0]["id"]
    added = client.post(
        f"/api/v1/tenants/{tenant['id']}/datasets/{dataset_id}/documents",
        headers=headers,
        json={"title": title, "content": content},
    )
    assert added.status_code == 201, added.text
    published = client.post(
        f"/api/v1/tenants/{tenant['id']}/datasets/{dataset_id}/publish",
        headers=headers,
    )
    assert published.status_code == 200, published.text
    return dataset_id


def test_grounded_answer_and_tenant_isolation(client, platform_headers):
    first, first_headers = provision_tenant(client, platform_headers, slug="knowledge-a")
    second, second_headers = provision_tenant(client, platform_headers, slug="knowledge-b")
    publish_document(
        client,
        first,
        first_headers,
        title="營業時間",
        content="本店週一至週五上午九點到下午六點營業。",
    )

    answer = client.post(
        f"/api/v1/tenants/{first['id']}/ai/answer",
        headers=first_headers,
        json={"question": "請問營業時間？"},
    )
    assert answer.status_code == 200
    assert answer.json()["grounded"] is True
    assert "上午九點" in answer.json()["answer"]
    assert answer.json()["citations"]
    assert answer.json()["citations"][0]["chunk_id"]
    assert answer.json()["provider"] == "local"
    assert answer.json()["model"] == "local-extractive-v1"
    assert answer.json()["retrieval_method"] == "hybrid-vector"

    other_tenant_answer = client.post(
        f"/api/v1/tenants/{second['id']}/ai/answer",
        headers=second_headers,
        json={"question": "請問營業時間？"},
    )
    assert other_tenant_answer.status_code == 200
    assert other_tenant_answer.json()["grounded"] is False
    assert "上午九點" not in other_tenant_answer.json()["answer"]


def test_document_is_chunked_and_can_be_reindexed(client, platform_headers):
    tenant, headers = provision_tenant(client, platform_headers, slug="chunk-shop")
    dataset_id = client.get(
        f"/api/v1/tenants/{tenant['id']}/datasets",
        headers=headers,
    ).json()[0]["id"]
    content = "\n\n".join(f"第 {index} 段：本店預約前一天可以免費改期。" for index in range(160))

    added = client.post(
        f"/api/v1/tenants/{tenant['id']}/datasets/{dataset_id}/documents",
        headers=headers,
        json={"title": "改期規則", "content": content},
    )

    assert added.status_code == 201, added.text
    assert added.json()["index_status"] == "READY"
    assert added.json()["indexed_at"] is not None
    with SessionLocal() as db:
        chunk_count = db.scalar(
            select(func.count(KnowledgeChunk.id)).where(
                KnowledgeChunk.document_id == added.json()["id"]
            )
        )
        chunks = db.scalars(
            select(KnowledgeChunk).where(KnowledgeChunk.document_id == added.json()["id"])
        ).all()
        for chunk in chunks:
            chunk.embedding_model = "stale-embedding-model"
        db.commit()
    assert chunk_count is not None and chunk_count >= 2

    rejected_publish = client.post(
        f"/api/v1/tenants/{tenant['id']}/datasets/{dataset_id}/publish",
        headers=headers,
    )
    assert rejected_publish.status_code == 422

    reindexed = client.post(
        f"/api/v1/tenants/{tenant['id']}/datasets/{dataset_id}/reindex",
        headers=headers,
    )
    assert reindexed.status_code == 200, reindexed.text
    assert reindexed.json() == {"indexed": 1, "failed": 0, "errors": []}
    published = client.post(
        f"/api/v1/tenants/{tenant['id']}/datasets/{dataset_id}/publish",
        headers=headers,
    )
    assert published.status_code == 200, published.text


def test_long_text_without_natural_boundaries_uses_hard_chunk_limit():
    chunks = split_into_chunks("甲" * 2500, max_chars=1000, overlap_chars=100)

    assert len(chunks) == 3
    assert len(chunks[0]) == 1000
    assert all(1 < len(chunk) <= 1000 for chunk in chunks)


class FakeEmbeddingsApi:
    def __init__(self) -> None:
        self.last_request: dict | None = None

    def create(self, **kwargs):
        self.last_request = kwargs
        dimensions = kwargs["dimensions"]
        return SimpleNamespace(
            data=[
                SimpleNamespace(index=index, embedding=[float(index + 1)] * dimensions)
                for index, _text in enumerate(kwargs["input"])
            ]
        )


class FakeResponsesApi:
    def __init__(self) -> None:
        self.last_request: dict | None = None

    def create(self, **kwargs):
        self.last_request = kwargs
        return SimpleNamespace(
            output_text="依照商家資料，預約前一天可免費改期。",
            _request_id="req_test",
        )


def test_openai_provider_contract_without_live_api_call():
    fake_client = SimpleNamespace(
        embeddings=FakeEmbeddingsApi(),
        responses=FakeResponsesApi(),
    )
    settings = Settings(
        _env_file=None,
        ai_provider="openai",
        ai_embedding_dimensions=64,
        OPENAI_API_KEY="sk-test",
    )
    provider = OpenAiProvider(settings, client=fake_client)

    embeddings = provider.embed_texts(["第一段", "第二段"])
    generated = provider.generate_answer(
        question="如何改期？",
        contexts=[
            GroundingContext(
                chunk_id="chunk-1",
                document_id="document-1",
                title="改期規則",
                content="預約前一天可以免費改期。",
                source_url=None,
                score=0.9,
            )
        ],
        tenant_name="測試商家",
        safety_identifier="line_user_hash",
    )

    assert len(embeddings) == 2
    assert len(embeddings[0]) == 64
    assert fake_client.embeddings.last_request["model"] == "text-embedding-3-small"
    assert fake_client.responses.last_request["model"] == "gpt-5.6-luna"
    assert fake_client.responses.last_request["store"] is False
    assert fake_client.responses.last_request["safety_identifier"] == "line_user_hash"
    assert generated.text == "依照商家資料，預約前一天可免費改期。"
    assert generated.request_id == "req_test"

    first_identifier = build_safety_identifier(
        settings,
        tenant_id="tenant-1",
        line_user_id="U-sensitive-line-id",
    )
    second_identifier = build_safety_identifier(
        settings,
        tenant_id="tenant-1",
        line_user_id="U-sensitive-line-id",
    )
    assert first_identifier == second_identifier
    assert "U-sensitive-line-id" not in first_identifier
