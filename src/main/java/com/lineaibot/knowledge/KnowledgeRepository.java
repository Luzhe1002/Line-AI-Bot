package com.lineaibot.knowledge;

import com.lineaibot.knowledge.KnowledgeDtos.DatasetRead;
import com.lineaibot.knowledge.KnowledgeDtos.KnowledgeDocumentRead;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeRepository {

    public record DocumentRow(
            String id,
            String tenantId,
            String datasetId,
            String title,
            String content,
            String sourceUrl,
            boolean active,
            String indexStatus,
            String indexError,
            Instant indexedAt) {

        KnowledgeDocumentRead toRead() {
            return new KnowledgeDocumentRead(
                    id,
                    tenantId,
                    datasetId,
                    title,
                    content,
                    sourceUrl,
                    active,
                    indexStatus,
                    indexError,
                    indexedAt);
        }
    }

    public record ChunkRow(
            String id,
            String documentId,
            String title,
            String sourceUrl,
            String content,
            String embeddingJson) {}

    private final JdbcClient jdbc;

    public KnowledgeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertDataset(DatasetRead dataset) {
        jdbc.sql("""
                        insert into datasets (
                            id, tenant_id, name, version, status, active_marker,
                            created_at, published_at
                        ) values (
                            :id, :tenantId, :name, :version, :status, null,
                            :createdAt, null
                        )
                        """)
                .param("id", dataset.id())
                .param("tenantId", dataset.tenantId())
                .param("name", dataset.name())
                .param("version", dataset.version())
                .param("status", dataset.status())
                .param("createdAt", utc(dataset.createdAt()))
                .update();
    }

    public Optional<DatasetRead> findDataset(String tenantId, String datasetId) {
        return jdbc.sql("""
                        select * from datasets
                        where tenant_id = :tenantId and id = :datasetId
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .query(this::mapDataset)
                .optional();
    }

    public Optional<DatasetRead> findActiveDataset(String tenantId) {
        return jdbc.sql("""
                        select * from datasets
                        where tenant_id = :tenantId and status = 'ACTIVE'
                        """)
                .param("tenantId", tenantId)
                .query(this::mapDataset)
                .optional();
    }

    public List<DatasetRead> findDatasets(String tenantId) {
        return jdbc.sql("""
                        select * from datasets
                        where tenant_id = :tenantId order by name, version
                        """)
                .param("tenantId", tenantId)
                .query(this::mapDataset)
                .list();
    }

    public Optional<DatasetRead> findDraftDataset(String tenantId, String name) {
        return jdbc.sql("""
                        select * from datasets
                        where tenant_id = :tenantId and name = :name and status = 'DRAFT'
                        order by version desc
                        limit 1
                        """)
                .param("tenantId", tenantId)
                .param("name", name)
                .query(this::mapDataset)
                .optional();
    }

    public int nextDatasetVersion(String tenantId, String name) {
        return jdbc.sql("""
                        select coalesce(max(version), 0) + 1
                        from datasets
                        where tenant_id = :tenantId and name = :name
                        """)
                .param("tenantId", tenantId)
                .param("name", name)
                .query(Integer.class)
                .single();
    }

    public void insertDocument(DocumentRow document, Instant createdAt) {
        jdbc.sql("""
                        insert into knowledge_documents (
                            id, tenant_id, dataset_id, title, content, source_url,
                            active, content_hash, index_status, index_error,
                            indexed_at, created_at
                        ) values (
                            :id, :tenantId, :datasetId, :title, :content, :sourceUrl,
                            true, null, 'PENDING', null, null, :createdAt
                        )
                        """)
                .param("id", document.id())
                .param("tenantId", document.tenantId())
                .param("datasetId", document.datasetId())
                .param("title", document.title())
                .param("content", document.content())
                .param("sourceUrl", document.sourceUrl())
                .param("createdAt", utc(createdAt))
                .update();
    }

    public Optional<DocumentRow> findDocument(String documentId) {
        return jdbc.sql("select * from knowledge_documents where id = :id")
                .param("id", documentId)
                .query(this::mapDocument)
                .optional();
    }

    public Optional<DocumentRow> findDocument(
            String tenantId, String datasetId, String documentId) {
        return jdbc.sql("""
                        select * from knowledge_documents
                        where tenant_id = :tenantId
                          and dataset_id = :datasetId
                          and id = :documentId
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .param("documentId", documentId)
                .query(this::mapDocument)
                .optional();
    }

    public List<DocumentRow> findDocuments(String tenantId, String datasetId) {
        return jdbc.sql("""
                        select * from knowledge_documents
                        where tenant_id = :tenantId and dataset_id = :datasetId
                        order by created_at
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .query(this::mapDocument)
                .list();
    }

    public void updateDocument(
            String tenantId,
            String datasetId,
            String documentId,
            String title,
            String content,
            String sourceUrl) {
        jdbc.sql("""
                        update knowledge_documents
                        set title = :title,
                            content = :content,
                            source_url = :sourceUrl,
                            content_hash = null,
                            index_status = 'PENDING',
                            index_error = null,
                            indexed_at = null
                        where tenant_id = :tenantId
                          and dataset_id = :datasetId
                          and id = :documentId
                        """)
                .param("title", title)
                .param("content", content)
                .param("sourceUrl", sourceUrl)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .param("documentId", documentId)
                .update();
    }

    public void deleteDocument(String tenantId, String datasetId, String documentId) {
        jdbc.sql("""
                        delete from knowledge_documents
                        where tenant_id = :tenantId
                          and dataset_id = :datasetId
                          and id = :documentId
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .param("documentId", documentId)
                .update();
    }

    public void markDocumentIndexing(String documentId) {
        jdbc.sql("""
                        update knowledge_documents
                        set index_status = 'INDEXING', index_error = null
                        where id = :id
                        """)
                .param("id", documentId)
                .update();
    }

    public void markDocumentReady(
            String documentId, String contentHash, Instant indexedAt) {
        jdbc.sql("""
                        update knowledge_documents
                        set content_hash = :contentHash, index_status = 'READY',
                            index_error = null, indexed_at = :indexedAt
                        where id = :id
                        """)
                .param("contentHash", contentHash)
                .param("indexedAt", utc(indexedAt))
                .param("id", documentId)
                .update();
    }

    public void markDocumentFailed(String documentId, String error) {
        jdbc.sql("""
                        update knowledge_documents
                        set index_status = 'FAILED', index_error = :error, indexed_at = null
                        where id = :id
                        """)
                .param("error", error)
                .param("id", documentId)
                .update();
    }

    public void deleteChunks(String documentId) {
        jdbc.sql("delete from knowledge_chunks where document_id = :documentId")
                .param("documentId", documentId)
                .update();
    }

    public void insertChunk(
            String id,
            DocumentRow document,
            int position,
            String content,
            String contentHash,
            String embeddingJson,
            String embeddingModel,
            int embeddingDimensions,
            int tokenCount,
            Instant createdAt) {
        jdbc.sql("""
                        insert into knowledge_chunks (
                            id, tenant_id, dataset_id, document_id, position,
                            content, content_hash, embedding_json, embedding_model,
                            embedding_dimensions, token_count_estimate, created_at
                        ) values (
                            :id, :tenantId, :datasetId, :documentId, :position,
                            :content, :contentHash, :embeddingJson, :embeddingModel,
                            :embeddingDimensions, :tokenCount, :createdAt
                        )
                        """)
                .param("id", id)
                .param("tenantId", document.tenantId())
                .param("datasetId", document.datasetId())
                .param("documentId", document.id())
                .param("position", position)
                .param("content", content)
                .param("contentHash", contentHash)
                .param("embeddingJson", embeddingJson)
                .param("embeddingModel", embeddingModel)
                .param("embeddingDimensions", embeddingDimensions)
                .param("tokenCount", tokenCount)
                .param("createdAt", utc(createdAt))
                .update();
    }

    public boolean hasActiveDocument(String tenantId, String datasetId) {
        return jdbc.sql("""
                        select count(*) from knowledge_documents
                        where tenant_id = :tenantId and dataset_id = :datasetId
                          and active = true
                        """)
                        .param("tenantId", tenantId)
                        .param("datasetId", datasetId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public boolean hasUnreadyDocument(String tenantId, String datasetId) {
        return jdbc.sql("""
                        select count(*) from knowledge_documents
                        where tenant_id = :tenantId and dataset_id = :datasetId
                          and active = true and index_status <> 'READY'
                        """)
                        .param("tenantId", tenantId)
                        .param("datasetId", datasetId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    public boolean allDocumentsUseEmbedding(
            String tenantId, String datasetId, String model, int dimensions) {
        int activeDocuments = jdbc.sql("""
                        select count(*) from knowledge_documents
                        where tenant_id = :tenantId and dataset_id = :datasetId
                          and active = true
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .query(Integer.class)
                .single();
        int indexedDocuments = jdbc.sql("""
                        select count(distinct document_id) from knowledge_chunks
                        where tenant_id = :tenantId and dataset_id = :datasetId
                          and embedding_model = :model
                          and embedding_dimensions = :dimensions
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .param("model", model)
                .param("dimensions", dimensions)
                .query(Integer.class)
                .single();
        return activeDocuments > 0 && activeDocuments == indexedDocuments;
    }

    public void archiveCurrentDataset(String tenantId, String exceptDatasetId) {
        jdbc.sql("""
                        update datasets
                        set status = 'ARCHIVED', active_marker = null
                        where tenant_id = :tenantId and status = 'ACTIVE' and id <> :datasetId
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", exceptDatasetId)
                .update();
    }

    public void activateDataset(String tenantId, String datasetId, Instant publishedAt) {
        jdbc.sql("""
                        update datasets
                        set status = 'ACTIVE', active_marker = 'ACTIVE',
                            published_at = :publishedAt
                        where tenant_id = :tenantId and id = :datasetId
                        """)
                .param("publishedAt", utc(publishedAt))
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .update();
    }

    public List<ChunkRow> findSearchableChunks(
            String tenantId,
            String datasetId,
            String embeddingModel,
            int embeddingDimensions) {
        return jdbc.sql("""
                        select c.id, c.document_id, d.title, d.source_url,
                               c.content, c.embedding_json
                        from knowledge_chunks c
                        join knowledge_documents d on d.id = c.document_id
                        where c.tenant_id = :tenantId
                          and c.dataset_id = :datasetId
                          and c.embedding_model = :embeddingModel
                          and c.embedding_dimensions = :embeddingDimensions
                          and d.active = true
                          and d.index_status = 'READY'
                        """)
                .param("tenantId", tenantId)
                .param("datasetId", datasetId)
                .param("embeddingModel", embeddingModel)
                .param("embeddingDimensions", embeddingDimensions)
                .query((rs, rowNum) -> new ChunkRow(
                        rs.getString("id"),
                        rs.getString("document_id"),
                        rs.getString("title"),
                        rs.getString("source_url"),
                        rs.getString("content"),
                        rs.getString("embedding_json")))
                .list();
    }

    private DatasetRead mapDataset(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime publishedAt = rs.getObject("published_at", OffsetDateTime.class);
        return new DatasetRead(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("name"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                publishedAt == null ? null : publishedAt.toInstant());
    }

    private DocumentRow mapDocument(ResultSet rs, int rowNum) throws SQLException {
        OffsetDateTime indexedAt = rs.getObject("indexed_at", OffsetDateTime.class);
        return new DocumentRow(
                rs.getString("id"),
                rs.getString("tenant_id"),
                rs.getString("dataset_id"),
                rs.getString("title"),
                rs.getString("content"),
                rs.getString("source_url"),
                rs.getBoolean("active"),
                rs.getString("index_status"),
                rs.getString("index_error"),
                indexedAt == null ? null : indexedAt.toInstant());
    }

    private OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
