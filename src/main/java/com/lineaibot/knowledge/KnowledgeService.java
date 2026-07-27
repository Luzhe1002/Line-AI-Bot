package com.lineaibot.knowledge;

import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.AiProvider.GroundingContext;
import com.lineaibot.knowledge.KnowledgeDtos.AnswerResponse;
import com.lineaibot.knowledge.KnowledgeDtos.Citation;
import com.lineaibot.knowledge.KnowledgeDtos.DatasetCreate;
import com.lineaibot.knowledge.KnowledgeDtos.DatasetRead;
import com.lineaibot.knowledge.KnowledgeDtos.KnowledgeDocumentCreate;
import com.lineaibot.knowledge.KnowledgeDtos.KnowledgeDocumentRead;
import com.lineaibot.knowledge.KnowledgeRepository.ChunkRow;
import com.lineaibot.knowledge.KnowledgeRepository.DocumentRow;
import com.lineaibot.shared.ApiException;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class KnowledgeService {

    private static final Pattern LATIN_WORD = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u3400-\\u9fff]");

    private final KnowledgeRepository repository;
    private final KnowledgeIndexer indexer;
    private final AiProviderRegistry providers;
    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final CryptoService crypto;

    public KnowledgeService(
            KnowledgeRepository repository,
            KnowledgeIndexer indexer,
            AiProviderRegistry providers,
            AppProperties properties,
            ObjectMapper objectMapper,
            CryptoService crypto) {
        this.repository = repository;
        this.indexer = indexer;
        this.providers = providers;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
    }

    @Transactional
    public DatasetRead createDataset(TenantRow tenant, DatasetCreate request) {
        DatasetRead dataset = new DatasetRead(
                UUID.randomUUID().toString(),
                tenant.id(),
                request.name(),
                request.version() == null ? 1 : request.version(),
                "DRAFT",
                Instant.now(),
                null);
        try {
            repository.insertDataset(dataset);
            return dataset;
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "A dataset with this name and version already exists");
        }
    }

    public List<DatasetRead> listDatasets(TenantRow tenant) {
        return repository.findDatasets(tenant.id());
    }

    public KnowledgeDocumentRead addDocument(
            TenantRow tenant, String datasetId, KnowledgeDocumentCreate request) {
        DatasetRead dataset = repository.findDataset(tenant.id(), datasetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dataset not found"));
        if (!"DRAFT".equals(dataset.status())) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "Only draft datasets can be changed");
        }
        DocumentRow document = new DocumentRow(
                UUID.randomUUID().toString(),
                tenant.id(),
                dataset.id(),
                request.title(),
                request.content(),
                request.sourceUrl(),
                true,
                "PENDING",
                null,
                null);
        repository.insertDocument(document, Instant.now());
        return indexer.indexDocument(document.id()).toRead();
    }

    public List<KnowledgeDocumentRead> listDocuments(TenantRow tenant, String datasetId) {
        repository.findDataset(tenant.id(), datasetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dataset not found"));
        return repository.findDocuments(tenant.id(), datasetId).stream()
                .map(DocumentRow::toRead)
                .toList();
    }

    @Transactional
    public DatasetRead publishDataset(TenantRow tenant, String datasetId) {
        DatasetRead dataset = repository.findDataset(tenant.id(), datasetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Dataset not found"));
        if (!repository.hasActiveDocument(tenant.id(), datasetId)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Dataset must contain at least one active document");
        }
        if (repository.hasUnreadyDocument(tenant.id(), datasetId)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "All active documents must be indexed before publishing");
        }
        AiProvider provider = providers.current();
        if (!repository.allDocumentsUseEmbedding(
                tenant.id(),
                datasetId,
                provider.embeddingModel(),
                provider.embeddingDimensions())) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Documents must be reindexed for the current AI provider before publishing");
        }
        Instant publishedAt = Instant.now();
        repository.archiveCurrentDataset(tenant.id(), datasetId);
        repository.activateDataset(tenant.id(), datasetId, publishedAt);
        return new DatasetRead(
                dataset.id(),
                dataset.tenantId(),
                dataset.name(),
                dataset.version(),
                "ACTIVE",
                dataset.createdAt(),
                publishedAt);
    }

    public AnswerResponse answer(
            TenantRow tenant, String question, String lineUserId) {
        AiProvider provider = providers.current();
        var activeDataset = repository.findActiveDataset(tenant.id());
        if (activeDataset.isEmpty()) {
            return fallback(
                    provider,
                    "目前尚未發布可用的客服資料，請轉由人工客服協助。",
                    null,
                    "none");
        }
        DatasetRead dataset = activeDataset.get();
        List<GroundingContext> contexts = retrieve(
                tenant.id(), dataset.id(), question, provider);
        if (contexts.isEmpty()) {
            return fallback(
                    provider,
                    "目前的資料無法確認這個問題，我可以替您轉接人工客服。",
                    dataset.id(),
                    "hybrid-vector");
        }
        String subject = tenant.id() + ":" + (lineUserId == null ? "anonymous" : lineUserId);
        String safetyIdentifier = "line_user_"
                + crypto.stableHmac(properties.getEncryptionKey(), subject).substring(0, 32);
        var generated = provider.generateAnswer(
                question, contexts, tenant.name(), safetyIdentifier);
        List<Citation> citations = contexts.stream()
                .map(context -> new Citation(
                        context.documentId(),
                        context.chunkId(),
                        context.title(),
                        context.sourceUrl(),
                        round(context.score()),
                        context.content().length() > 240
                                ? context.content().substring(0, 240) + "…"
                                : context.content()))
                .toList();
        return new AnswerResponse(
                generated.text(),
                round(Math.max(0, Math.min(1, contexts.getFirst().score()))),
                true,
                citations,
                dataset.id(),
                generated.provider(),
                generated.model(),
                "hybrid-vector");
    }

    public KnowledgeDtos.ReindexResponse reindex(TenantRow tenant, String datasetId) {
        return indexer.reindexDataset(tenant.id(), datasetId);
    }

    private List<GroundingContext> retrieve(
            String tenantId, String datasetId, String question, AiProvider provider) {
        List<ChunkRow> rows = repository.findSearchableChunks(
                tenantId,
                datasetId,
                provider.embeddingModel(),
                provider.embeddingDimensions());
        if (rows.isEmpty()) {
            return List.of();
        }
        double[] queryEmbedding = provider.embedTexts(List.of(question)).getFirst();
        List<GroundingContext> ranked = new ArrayList<>();
        for (ChunkRow row : rows) {
            double lexical = lexicalSimilarity(question, row.title(), row.content());
            double vector = cosine(queryEmbedding, readEmbedding(row.embeddingJson()));
            double score = Math.max(Math.max(0, vector), lexical);
            ranked.add(new GroundingContext(
                    row.id(),
                    row.documentId(),
                    row.title(),
                    row.content(),
                    row.sourceUrl(),
                    score));
        }
        ranked.sort(Comparator.comparingDouble(GroundingContext::score).reversed());
        List<GroundingContext> selected = new ArrayList<>();
        int remainingChars = properties.getAi().getMaxContextChars();
        for (GroundingContext context : ranked) {
            if (context.score() < properties.getAi().getMinRetrievalScore()
                    || remainingChars <= 0
                    || selected.size() >= properties.getAi().getMaxContextDocuments()) {
                break;
            }
            String content = context.content().substring(
                    0, Math.min(remainingChars, context.content().length()));
            selected.add(new GroundingContext(
                    context.chunkId(),
                    context.documentId(),
                    context.title(),
                    content,
                    context.sourceUrl(),
                    context.score()));
            remainingChars -= content.length();
        }
        return selected;
    }

    private AnswerResponse fallback(
            AiProvider provider, String message, String datasetId, String method) {
        return new AnswerResponse(
                message,
                0,
                false,
                List.of(),
                datasetId,
                provider.name(),
                provider.generationModel(),
                method);
    }

    private double[] readEmbedding(String value) {
        try {
            return objectMapper.readValue(value, double[].class);
        } catch (JacksonException exception) {
            return new double[0];
        }
    }

    private double cosine(double[] first, double[] second) {
        if (first.length == 0 || first.length != second.length) {
            return 0;
        }
        double dot = 0;
        double firstMagnitude = 0;
        double secondMagnitude = 0;
        for (int index = 0; index < first.length; index++) {
            dot += first[index] * second[index];
            firstMagnitude += first[index] * first[index];
            secondMagnitude += second[index] * second[index];
        }
        if (firstMagnitude == 0 || secondMagnitude == 0) {
            return 0;
        }
        return dot / (Math.sqrt(firstMagnitude) * Math.sqrt(secondMagnitude));
    }

    private double lexicalSimilarity(String question, String title, String content) {
        Set<String> query = features(question);
        if (query.isEmpty()) {
            return 0;
        }
        Set<String> document = features(title + " " + title + " " + content);
        Set<String> overlap = new HashSet<>(query);
        overlap.retainAll(document);
        if (overlap.isEmpty()) {
            return 0;
        }
        return Math.min(
                1,
                overlap.size()
                        / Math.sqrt(query.size() * Math.max(1.0, document.size())));
    }

    private Set<String> features(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        Set<String> result = new HashSet<>();
        var words = LATIN_WORD.matcher(normalized);
        while (words.find()) {
            result.add(words.group());
        }
        List<String> chars = new ArrayList<>();
        var matcher = CJK_CHAR.matcher(normalized);
        while (matcher.find()) {
            chars.add(matcher.group());
            result.add(matcher.group());
        }
        for (int index = 0; index + 1 < chars.size(); index++) {
            result.add(chars.get(index) + chars.get(index + 1));
        }
        return result;
    }

    private double round(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }
}
