package com.lineaibot.knowledge;

import com.lineaibot.knowledge.KnowledgeDtos.AnswerRequest;
import com.lineaibot.knowledge.KnowledgeDtos.AnswerResponse;
import com.lineaibot.knowledge.KnowledgeDtos.DatasetCreate;
import com.lineaibot.knowledge.KnowledgeDtos.DatasetRead;
import com.lineaibot.knowledge.KnowledgeDtos.KnowledgeDocumentCreate;
import com.lineaibot.knowledge.KnowledgeDtos.KnowledgeDocumentRead;
import com.lineaibot.knowledge.KnowledgeDtos.ReindexResponse;
import com.lineaibot.shared.ApiAuthService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}")
public class KnowledgeController {

    private final KnowledgeService service;
    private final ApiAuthService auth;

    public KnowledgeController(KnowledgeService service, ApiAuthService auth) {
        this.service = service;
        this.auth = auth;
    }

    @PostMapping("/datasets")
    @ResponseStatus(HttpStatus.CREATED)
    DatasetRead createDataset(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @Valid @RequestBody DatasetCreate request) {
        return service.createDataset(auth.requireTenantAdmin(tenantId, apiKey), request);
    }

    @GetMapping("/datasets")
    List<DatasetRead> listDatasets(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return service.listDatasets(auth.requireTenantAdmin(tenantId, apiKey));
    }

    @PostMapping("/datasets/{datasetId}/documents")
    @ResponseStatus(HttpStatus.CREATED)
    KnowledgeDocumentRead addDocument(
            @PathVariable String tenantId,
            @PathVariable String datasetId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @Valid @RequestBody KnowledgeDocumentCreate request) {
        return service.addDocument(
                auth.requireTenantAdmin(tenantId, apiKey), datasetId, request);
    }

    @GetMapping("/datasets/{datasetId}/documents")
    List<KnowledgeDocumentRead> listDocuments(
            @PathVariable String tenantId,
            @PathVariable String datasetId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return service.listDocuments(
                auth.requireTenantAdmin(tenantId, apiKey), datasetId);
    }

    @PostMapping("/datasets/{datasetId}/publish")
    DatasetRead publishDataset(
            @PathVariable String tenantId,
            @PathVariable String datasetId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return service.publishDataset(
                auth.requireTenantAdmin(tenantId, apiKey), datasetId);
    }

    @PostMapping("/datasets/{datasetId}/reindex")
    ReindexResponse reindex(
            @PathVariable String tenantId,
            @PathVariable String datasetId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey) {
        return service.reindex(auth.requireTenantAdmin(tenantId, apiKey), datasetId);
    }

    @PostMapping("/ai/answer")
    AnswerResponse answer(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Tenant-Api-Key", required = false) String apiKey,
            @Valid @RequestBody AnswerRequest request) {
        return service.answer(
                auth.requireTenantAdmin(tenantId, apiKey),
                request.question(),
                request.lineUserId());
    }
}
