from fastapi import APIRouter, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from line_ai_bot.api.deps import AppSettings, DbSession, TenantAdmin
from line_ai_bot.models import Dataset, KnowledgeChunk, KnowledgeDocument, utc_now
from line_ai_bot.schemas import (
    AnswerRequest,
    AnswerResponse,
    DatasetCreate,
    DatasetRead,
    KnowledgeDocumentCreate,
    KnowledgeDocumentRead,
    ReindexResponse,
)
from line_ai_bot.services.ai_provider import build_ai_provider
from line_ai_bot.services.knowledge import KnowledgeAnswerService
from line_ai_bot.services.rag import KnowledgeIndexError, RagIngestionService

router = APIRouter(prefix="/api/v1/tenants/{tenant_id}", tags=["knowledge"])


@router.post("/datasets", response_model=DatasetRead, status_code=status.HTTP_201_CREATED)
def create_dataset(
    tenant: TenantAdmin,
    payload: DatasetCreate,
    db: DbSession,
) -> Dataset:
    dataset = Dataset(
        tenant_id=tenant.id,
        name=payload.name,
        version=payload.version,
        status="DRAFT",
    )
    db.add(dataset)
    try:
        db.commit()
    except IntegrityError as exc:
        db.rollback()
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Dataset name and version already exist",
        ) from exc
    db.refresh(dataset)
    return dataset


@router.get("/datasets", response_model=list[DatasetRead])
def list_datasets(tenant: TenantAdmin, db: DbSession) -> list[Dataset]:
    return list(
        db.scalars(
            select(Dataset)
            .where(Dataset.tenant_id == tenant.id)
            .order_by(Dataset.name, Dataset.version)
        ).all()
    )


@router.post(
    "/datasets/{dataset_id}/documents",
    response_model=KnowledgeDocumentRead,
    status_code=status.HTTP_201_CREATED,
)
def add_document(
    tenant: TenantAdmin,
    dataset_id: str,
    payload: KnowledgeDocumentCreate,
    db: DbSession,
    settings: AppSettings,
) -> KnowledgeDocument:
    dataset = db.scalar(
        select(Dataset).where(Dataset.id == dataset_id, Dataset.tenant_id == tenant.id)
    )
    if dataset is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Dataset not found")
    if dataset.status != "DRAFT":
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Only draft datasets can be changed",
        )
    document = KnowledgeDocument(
        tenant_id=tenant.id,
        dataset_id=dataset.id,
        title=payload.title,
        content=payload.content,
        source_url=payload.source_url,
    )
    db.add(document)
    db.commit()
    db.refresh(document)
    try:
        return RagIngestionService(db, settings, build_ai_provider(settings)).index_document(
            document.id
        )
    except KnowledgeIndexError as exc:
        raise HTTPException(
            status_code=status.HTTP_502_BAD_GATEWAY,
            detail=(
                f"Document {document.id} was saved, but indexing failed. "
                "Retry with the reindex endpoint."
            ),
        ) from exc


@router.get(
    "/datasets/{dataset_id}/documents",
    response_model=list[KnowledgeDocumentRead],
)
def list_documents(
    tenant: TenantAdmin,
    dataset_id: str,
    db: DbSession,
) -> list[KnowledgeDocument]:
    return list(
        db.scalars(
            select(KnowledgeDocument)
            .where(
                KnowledgeDocument.tenant_id == tenant.id,
                KnowledgeDocument.dataset_id == dataset_id,
            )
            .order_by(KnowledgeDocument.created_at)
        ).all()
    )


@router.post("/datasets/{dataset_id}/publish", response_model=DatasetRead)
def publish_dataset(
    tenant: TenantAdmin,
    dataset_id: str,
    db: DbSession,
    settings: AppSettings,
) -> Dataset:
    dataset = db.scalar(
        select(Dataset).where(Dataset.id == dataset_id, Dataset.tenant_id == tenant.id)
    )
    if dataset is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Dataset not found")
    has_document = db.scalar(
        select(KnowledgeDocument.id)
        .where(
            KnowledgeDocument.tenant_id == tenant.id,
            KnowledgeDocument.dataset_id == dataset.id,
            KnowledgeDocument.active.is_(True),
        )
        .limit(1)
    )
    if has_document is None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Dataset must contain at least one active document",
        )
    not_ready = db.scalar(
        select(KnowledgeDocument.id)
        .where(
            KnowledgeDocument.tenant_id == tenant.id,
            KnowledgeDocument.dataset_id == dataset.id,
            KnowledgeDocument.active.is_(True),
            KnowledgeDocument.index_status != "READY",
        )
        .limit(1)
    )
    if not_ready is not None:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="All active documents must be indexed before publishing",
        )
    provider = build_ai_provider(settings)
    active_document_ids = set(
        db.scalars(
            select(KnowledgeDocument.id).where(
                KnowledgeDocument.tenant_id == tenant.id,
                KnowledgeDocument.dataset_id == dataset.id,
                KnowledgeDocument.active.is_(True),
            )
        ).all()
    )
    current_embedding_document_ids = set(
        db.scalars(
            select(KnowledgeChunk.document_id).where(
                KnowledgeChunk.tenant_id == tenant.id,
                KnowledgeChunk.dataset_id == dataset.id,
                KnowledgeChunk.embedding_model == provider.embedding_model,
                KnowledgeChunk.embedding_dimensions == provider.embedding_dimensions,
            )
        ).all()
    )
    if not active_document_ids.issubset(current_embedding_document_ids):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_CONTENT,
            detail="Documents must be reindexed for the current AI provider before publishing",
        )

    current = db.scalar(
        select(Dataset).where(
            Dataset.tenant_id == tenant.id,
            Dataset.status == "ACTIVE",
            Dataset.id != dataset.id,
        )
    )
    if current is not None:
        current.status = "ARCHIVED"
        db.flush()
    dataset.status = "ACTIVE"
    dataset.published_at = utc_now()
    db.commit()
    db.refresh(dataset)
    return dataset


@router.post("/datasets/{dataset_id}/reindex", response_model=ReindexResponse)
def reindex_dataset(
    tenant: TenantAdmin,
    dataset_id: str,
    db: DbSession,
    settings: AppSettings,
) -> ReindexResponse:
    service = RagIngestionService(db, settings, build_ai_provider(settings))
    try:
        result = service.reindex_dataset(tenant.id, dataset_id)
    except KnowledgeIndexError as exc:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=str(exc)) from exc
    return ReindexResponse(
        indexed=result.indexed,
        failed=result.failed,
        errors=result.errors,
    )


@router.post("/ai/answer", response_model=AnswerResponse)
def answer_question(
    tenant: TenantAdmin,
    payload: AnswerRequest,
    db: DbSession,
    settings: AppSettings,
) -> AnswerResponse:
    return KnowledgeAnswerService(db, settings).answer(
        tenant.id,
        payload.question,
        tenant_name=tenant.name,
        line_user_id=payload.line_user_id,
    )
