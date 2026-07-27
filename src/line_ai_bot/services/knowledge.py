from sqlalchemy import select
from sqlalchemy.orm import Session

from line_ai_bot.config import Settings
from line_ai_bot.models import Dataset
from line_ai_bot.schemas import AnswerResponse, Citation
from line_ai_bot.services.ai_provider import (
    AiProvider,
    AiProviderError,
    LocalAiProvider,
    build_ai_provider,
    build_safety_identifier,
)
from line_ai_bot.services.rag import RagRetriever


class KnowledgeAnswerService:
    def __init__(
        self,
        db: Session,
        settings: Settings,
        provider: AiProvider | None = None,
    ) -> None:
        self.db = db
        self.settings = settings
        self.provider = provider or build_ai_provider(settings)
        self.retriever = RagRetriever(db, settings, self.provider)

    def answer(
        self,
        tenant_id: str,
        question: str,
        *,
        tenant_name: str | None = None,
        line_user_id: str | None = None,
    ) -> AnswerResponse:
        dataset = self.db.scalar(
            select(Dataset).where(
                Dataset.tenant_id == tenant_id,
                Dataset.status == "ACTIVE",
            )
        )
        if dataset is None:
            return self._fallback(
                "目前尚未發布可用的客服資料，請轉由人工客服協助。",
                retrieval_method="none",
            )

        retrieval = self.retriever.retrieve(tenant_id, dataset.id, question)
        if not retrieval.contexts:
            return self._fallback(
                "目前的資料無法確認這個問題，我可以替您轉接人工客服。",
                dataset_id=dataset.id,
                retrieval_method=retrieval.method,
            )

        safety_identifier = build_safety_identifier(
            self.settings,
            tenant_id=tenant_id,
            line_user_id=line_user_id,
        )
        try:
            generated = self.provider.generate_answer(
                question=question,
                contexts=retrieval.contexts,
                tenant_name=tenant_name,
                safety_identifier=safety_identifier,
            )
        except AiProviderError:
            fallback_provider = LocalAiProvider(self.settings)
            generated = fallback_provider.generate_answer(
                question=question,
                contexts=retrieval.contexts,
                tenant_name=tenant_name,
                safety_identifier=safety_identifier,
            )

        citations = [
            Citation(
                document_id=context.document_id,
                chunk_id=context.chunk_id,
                title=context.title,
                source_url=context.source_url,
                score=round(context.score, 4),
                snippet=(
                    context.content[:240] + "…" if len(context.content) > 240 else context.content
                ),
            )
            for context in retrieval.contexts
        ]
        return AnswerResponse(
            answer=generated.text,
            confidence=round(max(0.0, min(1.0, retrieval.contexts[0].score)), 4),
            grounded=True,
            citations=citations,
            dataset_id=dataset.id,
            provider=generated.provider,
            model=generated.model,
            retrieval_method=retrieval.method,
        )

    def _fallback(
        self,
        message: str,
        *,
        dataset_id: str | None = None,
        retrieval_method: str,
    ) -> AnswerResponse:
        return AnswerResponse(
            answer=message,
            confidence=0.0,
            grounded=False,
            citations=[],
            dataset_id=dataset_id,
            provider=self.provider.name,
            model=self.provider.generation_model,
            retrieval_method=retrieval_method,
        )
