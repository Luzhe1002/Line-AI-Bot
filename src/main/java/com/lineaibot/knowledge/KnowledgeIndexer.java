package com.lineaibot.knowledge;

import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.KnowledgeRepository.DocumentRow;
import com.lineaibot.shared.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeIndexer {

    private final KnowledgeRepository repository;
    private final AiProviderRegistry providers;
    private final DocumentChunker chunker;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public KnowledgeIndexer(
            KnowledgeRepository repository,
            AiProviderRegistry providers,
            DocumentChunker chunker,
            AppProperties properties,
            ObjectMapper objectMapper,
            TransactionTemplate transactions) {
        this.repository = repository;
        this.providers = providers;
        this.chunker = chunker;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
    }

    public DocumentRow indexDocument(String documentId) {
        DocumentRow document = repository.findDocument(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Document not found"));
        repository.markDocumentIndexing(documentId);
        try {
            var provider = providers.current();
            List<String> chunks = chunker.split(
                    document.content(),
                    properties.getAi().getChunkSizeChars(),
                    properties.getAi().getChunkOverlapChars());
            if (chunks.isEmpty()) {
                throw new IllegalArgumentException("Document has no indexable content");
            }
            List<String> embeddingInputs = chunks.stream()
                    .map(chunk -> "標題：" + document.title() + "\n內容：" + chunk)
                    .toList();
            List<double[]> embeddings = provider.embedTexts(embeddingInputs);
            if (embeddings.size() != chunks.size()) {
                throw new IllegalStateException("Embedding count does not match chunk count");
            }
            Instant now = Instant.now();
            transactions.executeWithoutResult(status -> {
                repository.deleteChunks(document.id());
                for (int position = 0; position < chunks.size(); position++) {
                    double[] embedding = embeddings.get(position);
                    if (embedding.length != provider.embeddingDimensions()) {
                        throw new IllegalStateException(
                                "Embedding dimension does not match configuration");
                    }
                    String content = chunks.get(position);
                    repository.insertChunk(
                            UUID.randomUUID().toString(),
                            document,
                            position,
                            content,
                            hash(content),
                            toJson(embedding),
                            provider.embeddingModel(),
                            provider.embeddingDimensions(),
                            Math.max(1, (int) Math.ceil(content.length() / 4.0)),
                            now);
                }
                repository.markDocumentReady(
                        document.id(), hash(document.title() + "\0" + document.content()), now);
            });
            return repository.findDocument(documentId).orElseThrow();
        } catch (RuntimeException exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            repository.markDocumentFailed(
                    documentId, message.substring(0, Math.min(2000, message.length())));
            throw new ApiException(
                    HttpStatus.BAD_GATEWAY,
                    "Document "
                            + documentId
                            + " was saved, but indexing failed. Retry with the reindex endpoint.");
        }
    }

    public KnowledgeDtos.ReindexResponse reindexDataset(String tenantId, String datasetId) {
        repository.findDataset(tenantId, datasetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dataset not found"));
        int indexed = 0;
        List<String> errors = new ArrayList<>();
        for (DocumentRow document : repository.findDocuments(tenantId, datasetId)) {
            if (!document.active()) {
                continue;
            }
            try {
                indexDocument(document.id());
                indexed++;
            } catch (ApiException exception) {
                errors.add(document.id() + ": " + exception.getMessage());
            }
        }
        return new KnowledgeDtos.ReindexResponse(indexed, errors.size(), errors);
    }

    private String toJson(double[] values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Unable to serialize embedding", exception);
        }
    }

    private String hash(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
