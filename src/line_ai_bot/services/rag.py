import hashlib
import json
import math
import re
import unicodedata
from dataclasses import dataclass

from sqlalchemy import delete, select
from sqlalchemy.orm import Session

from line_ai_bot.config import Settings
from line_ai_bot.models import Dataset, KnowledgeChunk, KnowledgeDocument, utc_now
from line_ai_bot.services.ai_provider import AiProvider, AiProviderError, GroundingContext

LATIN_WORD = re.compile(r"[a-z0-9]+")
CJK_CHAR = re.compile(r"[\u3400-\u9fff]")


class KnowledgeIndexError(RuntimeError):
    """Raised when a document cannot be converted into searchable chunks."""


@dataclass(frozen=True, slots=True)
class RetrievalResult:
    contexts: list[GroundingContext]
    method: str


@dataclass(frozen=True, slots=True)
class ReindexResult:
    indexed: int
    failed: int
    errors: list[str]


def _normalize_content(value: str) -> str:
    lines = [
        re.sub(r"[ \t]+", " ", line).strip() for line in value.replace("\r\n", "\n").split("\n")
    ]
    normalized_lines: list[str] = []
    for line in lines:
        if line or (normalized_lines and normalized_lines[-1]):
            normalized_lines.append(line)
    return "\n".join(normalized_lines).strip()


def split_into_chunks(value: str, *, max_chars: int, overlap_chars: int) -> list[str]:
    content = _normalize_content(value)
    if not content:
        return []

    chunks: list[str] = []
    start = 0
    content_length = len(content)
    while start < content_length:
        end = min(start + max_chars, content_length)
        if end < content_length:
            minimum_boundary = start + max_chars // 2
            candidates: list[int] = []
            for boundary in ("\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", " "):
                location = content.rfind(boundary, minimum_boundary, end)
                if location >= minimum_boundary:
                    candidates.append(location + len(boundary))
            if candidates:
                end = max(candidates)

        chunk = content[start:end].strip()
        if chunk:
            chunks.append(chunk)
        if end >= content_length:
            break
        start = max(start + 1, end - overlap_chars)
    return chunks


def _content_hash(*values: str) -> str:
    digest = hashlib.sha256()
    for value in values:
        digest.update(value.encode("utf-8"))
        digest.update(b"\0")
    return digest.hexdigest()


def _lexical_features(value: str) -> set[str]:
    normalized = unicodedata.normalize("NFKC", value).lower()
    features = set(LATIN_WORD.findall(normalized))
    chars = CJK_CHAR.findall(normalized)
    features.update(chars)
    features.update(first + second for first, second in zip(chars, chars[1:], strict=False))
    return features


def _lexical_similarity(question: str, title: str, content: str) -> float:
    query_features = _lexical_features(question)
    if not query_features:
        return 0.0
    document_features = _lexical_features(f"{title} {title} {content}")
    overlap = len(query_features & document_features)
    if overlap == 0:
        return 0.0
    return min(1.0, overlap / math.sqrt(len(query_features) * max(1, len(document_features))))


def _cosine_similarity(first: list[float], second: list[float]) -> float:
    if len(first) != len(second) or not first:
        return 0.0
    first_magnitude = math.sqrt(sum(value * value for value in first))
    second_magnitude = math.sqrt(sum(value * value for value in second))
    if first_magnitude == 0 or second_magnitude == 0:
        return 0.0
    dot_product = sum(left * right for left, right in zip(first, second, strict=True))
    return dot_product / (first_magnitude * second_magnitude)


class RagIngestionService:
    def __init__(self, db: Session, settings: Settings, provider: AiProvider) -> None:
        self.db = db
        self.settings = settings
        self.provider = provider

    def index_document(self, document_id: str) -> KnowledgeDocument:
        document = self.db.get(KnowledgeDocument, document_id)
        if document is None:
            raise KnowledgeIndexError("Document not found")

        document.index_status = "INDEXING"
        document.index_error = None
        self.db.commit()

        try:
            chunks = split_into_chunks(
                document.content,
                max_chars=self.settings.ai_chunk_size_chars,
                overlap_chars=self.settings.ai_chunk_overlap_chars,
            )
            if not chunks:
                raise KnowledgeIndexError("Document has no indexable content")

            embedding_inputs = [f"標題：{document.title}\n內容：{chunk}" for chunk in chunks]
            embeddings = self.provider.embed_texts(embedding_inputs)
            if len(embeddings) != len(chunks):
                raise KnowledgeIndexError("Embedding count does not match chunk count")

            self.db.execute(delete(KnowledgeChunk).where(KnowledgeChunk.document_id == document.id))
            for position, (content, embedding) in enumerate(zip(chunks, embeddings, strict=True)):
                if len(embedding) != self.provider.embedding_dimensions:
                    raise KnowledgeIndexError("Embedding dimension does not match configuration")
                self.db.add(
                    KnowledgeChunk(
                        tenant_id=document.tenant_id,
                        dataset_id=document.dataset_id,
                        document_id=document.id,
                        position=position,
                        content=content,
                        content_hash=_content_hash(content),
                        embedding_json=json.dumps(embedding, separators=(",", ":")),
                        embedding_model=self.provider.embedding_model,
                        embedding_dimensions=self.provider.embedding_dimensions,
                        token_count_estimate=max(1, math.ceil(len(content) / 4)),
                    )
                )

            document.content_hash = _content_hash(document.title, document.content)
            document.index_status = "READY"
            document.index_error = None
            document.indexed_at = utc_now()
            self.db.commit()
            self.db.refresh(document)
            return document
        except Exception as exc:
            self.db.rollback()
            failed_document = self.db.get(KnowledgeDocument, document_id)
            if failed_document is not None:
                failed_document.index_status = "FAILED"
                failed_document.index_error = str(exc)[:2000] or type(exc).__name__
                failed_document.indexed_at = None
                self.db.commit()
            if isinstance(exc, KnowledgeIndexError):
                raise
            raise KnowledgeIndexError("Unable to index document") from exc

    def reindex_dataset(self, tenant_id: str, dataset_id: str) -> ReindexResult:
        dataset = self.db.scalar(
            select(Dataset).where(Dataset.id == dataset_id, Dataset.tenant_id == tenant_id)
        )
        if dataset is None:
            raise KnowledgeIndexError("Dataset not found")

        documents = self.db.scalars(
            select(KnowledgeDocument)
            .where(
                KnowledgeDocument.tenant_id == tenant_id,
                KnowledgeDocument.dataset_id == dataset_id,
                KnowledgeDocument.active.is_(True),
            )
            .order_by(KnowledgeDocument.created_at)
        ).all()
        indexed = 0
        errors: list[str] = []
        for document in documents:
            try:
                self.index_document(document.id)
                indexed += 1
            except KnowledgeIndexError as exc:
                errors.append(f"{document.id}: {exc}")
        return ReindexResult(indexed=indexed, failed=len(errors), errors=errors)


class RagRetriever:
    def __init__(self, db: Session, settings: Settings, provider: AiProvider) -> None:
        self.db = db
        self.settings = settings
        self.provider = provider

    def retrieve(self, tenant_id: str, dataset_id: str, question: str) -> RetrievalResult:
        rows = self.db.execute(
            select(KnowledgeChunk, KnowledgeDocument)
            .join(KnowledgeDocument, KnowledgeDocument.id == KnowledgeChunk.document_id)
            .where(
                KnowledgeChunk.tenant_id == tenant_id,
                KnowledgeChunk.dataset_id == dataset_id,
                KnowledgeChunk.embedding_model == self.provider.embedding_model,
                KnowledgeChunk.embedding_dimensions == self.provider.embedding_dimensions,
                KnowledgeDocument.active.is_(True),
                KnowledgeDocument.index_status == "READY",
            )
        ).all()
        if not rows:
            return RetrievalResult(contexts=[], method="hybrid-vector")

        query_embedding: list[float] | None
        method = "hybrid-vector"
        try:
            query_embedding = self.provider.embed_texts([question])[0]
        except (AiProviderError, IndexError):
            query_embedding = None
            method = "lexical-fallback"

        ranked: list[GroundingContext] = []
        for chunk, document in rows:
            lexical_score = _lexical_similarity(question, document.title, chunk.content)
            vector_score = 0.0
            if query_embedding is not None:
                try:
                    stored_embedding = json.loads(chunk.embedding_json)
                    vector_score = max(
                        0.0,
                        _cosine_similarity(
                            query_embedding, [float(value) for value in stored_embedding]
                        ),
                    )
                except (TypeError, ValueError, json.JSONDecodeError):
                    vector_score = 0.0
            score = lexical_score if query_embedding is None else max(vector_score, lexical_score)
            ranked.append(
                GroundingContext(
                    chunk_id=chunk.id,
                    document_id=document.id,
                    title=document.title,
                    content=chunk.content,
                    source_url=document.source_url,
                    score=score,
                )
            )

        ranked.sort(key=lambda item: item.score, reverse=True)
        contexts: list[GroundingContext] = []
        remaining_chars = self.settings.ai_max_context_chars
        for item in ranked:
            if item.score < self.settings.ai_min_retrieval_score or remaining_chars <= 0:
                break
            content = item.content[:remaining_chars]
            if not content:
                continue
            contexts.append(
                GroundingContext(
                    chunk_id=item.chunk_id,
                    document_id=item.document_id,
                    title=item.title,
                    content=content,
                    source_url=item.source_url,
                    score=item.score,
                )
            )
            remaining_chars -= len(content)
            if len(contexts) >= self.settings.ai_max_context_documents:
                break
        return RetrievalResult(contexts=contexts, method=method)
