package com.lineaibot.knowledge;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class KnowledgeDtos {

    private KnowledgeDtos() {}

    public record DatasetCreate(
            @NotBlank @Size(max = 160) String name,
            @Min(1) Integer version) {}

    public record DatasetRead(
            String id,
            String tenantId,
            String name,
            int version,
            String status,
            Instant createdAt,
            Instant publishedAt) {}

    public record KnowledgeDocumentCreate(
            @NotBlank @Size(max = 240) String title,
            @NotBlank @Size(max = 100_000) String content,
            @Size(max = 1024) String sourceUrl) {}

    public record KnowledgeDocumentUpdate(
            @NotBlank @Size(max = 240) String title,
            @NotBlank @Size(max = 100_000) String content,
            @Size(max = 1024) String sourceUrl) {}

    public record KnowledgeDocumentRead(
            String id,
            String tenantId,
            String datasetId,
            String title,
            String content,
            String sourceUrl,
            boolean active,
            String indexStatus,
            String indexError,
            Instant indexedAt) {}

    public record Citation(
            String documentId,
            String chunkId,
            String title,
            String sourceUrl,
            double score,
            String snippet) {}

    public record AnswerRequest(
            @NotBlank @Size(max = 4000) String question,
            @Size(max = 64) String lineUserId) {}

    public record AnswerResponse(
            String answer,
            double confidence,
            boolean grounded,
            List<Citation> citations,
            String datasetId,
            String provider,
            String model,
            String retrievalMethod) {}

    public record ReindexResponse(int indexed, int failed, List<String> errors) {}
}
