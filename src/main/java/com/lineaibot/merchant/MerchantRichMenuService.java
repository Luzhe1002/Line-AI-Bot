package com.lineaibot.merchant;

import com.lineaibot.config.AppProperties;
import com.lineaibot.line.LineMessagingClient;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

@Service
public class MerchantRichMenuService {

    private static final Logger log = LoggerFactory.getLogger(MerchantRichMenuService.class);
    private static final int HALF_WIDTH = MerchantRichMenuImageFactory.WIDTH / 2;
    private static final int HALF_HEIGHT = MerchantRichMenuImageFactory.HEIGHT / 2;

    private final MerchantRichMenuRepository repository;
    private final TenantRepository tenants;
    private final LineMessagingClient lineClient;
    private final MerchantRichMenuImageFactory images;
    private final CryptoService crypto;
    private final AppProperties properties;

    public MerchantRichMenuService(
            MerchantRichMenuRepository repository,
            TenantRepository tenants,
            LineMessagingClient lineClient,
            MerchantRichMenuImageFactory images,
            CryptoService crypto,
            AppProperties properties) {
        this.repository = repository;
        this.tenants = tenants;
        this.lineClient = lineClient;
        this.images = images;
        this.crypto = crypto;
        this.properties = properties;
    }

    public void scheduleStaff(MerchantDtos.StaffView staff) {
        repository.requestSync(
                staff.tenantId(),
                staff.id(),
                staff.role(),
                "ACTIVE".equals(staff.status()),
                Instant.now());
    }

    public void scheduleTenant(String tenantId) {
        repository.requestTenantSync(tenantId, Instant.now());
    }

    public void recoverStaleJobs(Instant staleBefore, Instant now) {
        repository.recoverStaleJobs(staleBefore, now);
    }

    public List<String> findReadyStaffIds(Instant now, int limit) {
        return repository.findReadyStaffIds(now, limit);
    }

    public boolean claim(String staffId, Instant now) {
        return repository.claim(staffId, now);
    }

    public void processClaimed(String staffId) {
        var job = repository.findClaimedJob(staffId).orElse(null);
        if (job == null) {
            return;
        }
        Instant now = Instant.now();
        String role = job.desiredRole();
        try {
            var tenant = tenants.findById(job.tenantId())
                    .filter(TenantRepository.TenantRow::active)
                    .orElseThrow(() -> new IllegalStateException("Tenant is unavailable"));
            var channel = tenants.findLineChannel(job.tenantId())
                    .filter(TenantRepository.LineChannelRow::enabled)
                    .orElseThrow(() -> new IllegalStateException("LINE channel is unavailable"));
            String accessToken = crypto.decryptSecret(
                    properties.getEncryptionKey(), channel.channelAccessTokenEncrypted());
            String lineUserId = crypto.decryptSecret(
                    properties.getEncryptionKey() + ":merchant-staff-line-encryption",
                    job.lineUserIdEncrypted());

            if (job.desiredLinked()) {
                String richMenuId = ensureRichMenu(
                        job.tenantId(), role, accessToken);
                lineClient.linkRichMenu(accessToken, lineUserId, richMenuId);
            } else {
                try {
                    lineClient.unlinkRichMenu(accessToken, lineUserId);
                } catch (RestClientResponseException exception) {
                    if (exception.getStatusCode().value() != 404) {
                        throw exception;
                    }
                }
            }
            repository.markSynced(job.staffId(), job.revision(), Instant.now());
            log.info(
                    "Merchant rich menu synchronized tenantId={} staffId={} linked={} role={}",
                    job.tenantId(),
                    job.staffId(),
                    job.desiredLinked(),
                    role);
        } catch (Exception exception) {
            if (job.desiredLinked()
                    && exception instanceof RestClientResponseException response
                    && response.getStatusCode().value() == 404) {
                repository.resetRichMenu(
                        job.tenantId(), role, "LINE rich menu was not found", Instant.now());
            }
            int attempts = job.attempts() + 1;
            long delaySeconds = Math.min(300, 5L << Math.min(attempts - 1, 6));
            repository.markRetry(
                    job.staffId(),
                    job.revision(),
                    attempts,
                    now.plusSeconds(delaySeconds),
                    exception.getMessage(),
                    Instant.now());
            log.warn(
                    "Merchant rich menu synchronization deferred tenantId={} staffId={} attempt={} errorType={} message={}",
                    job.tenantId(),
                    job.staffId(),
                    attempts,
                    exception.getClass().getSimpleName(),
                    exception.getMessage());
        }
    }

    private String ensureRichMenu(
            String tenantId, String role, String accessToken) {
        var existing = repository.findRichMenu(tenantId, role).orElse(null);
        if (existing != null
                && "READY".equals(existing.status())
                && existing.lineRichMenuId() != null
                && !existing.lineRichMenuId().isBlank()) {
            return existing.lineRichMenuId();
        }

        String name = menuName(tenantId, role);
        String richMenuId = existing == null ? null : existing.lineRichMenuId();
        if (richMenuId == null || richMenuId.isBlank()) {
            richMenuId = lineClient.findRichMenuIdByName(accessToken, name)
                    .orElseGet(() -> lineClient.createRichMenu(
                            accessToken, definition(name, role)));
            repository.saveRichMenuReference(
                    tenantId, role, richMenuId, Instant.now());
        }
        lineClient.uploadRichMenuImage(accessToken, richMenuId, images.create(role));
        repository.markRichMenuReady(tenantId, role, Instant.now());
        return richMenuId;
    }

    private Map<String, Object> definition(String name, String role) {
        String primaryAction = "OWNER".equals(role) ? "merchant_portal" : "merchant_menu";
        String primaryLabel = "OWNER".equals(role) ? "開啟管理後台" : "開啟預約月曆";
        return Map.of(
                "size",
                Map.of(
                        "width", MerchantRichMenuImageFactory.WIDTH,
                        "height", MerchantRichMenuImageFactory.HEIGHT),
                "selected",
                true,
                "name",
                name,
                "chatBarText",
                "店家管理",
                "areas",
                List.of(
                        postbackArea(
                                0,
                                0,
                                primaryLabel,
                                "action=" + primaryAction),
                        postbackArea(
                                HALF_WIDTH,
                                0,
                                "今日預約",
                                "action=merchant_agenda&range=today"),
                        postbackArea(
                                0,
                                HALF_HEIGHT,
                                "本週預約",
                                "action=merchant_agenda&range=week"),
                        messageArea(
                                HALF_WIDTH,
                                HALF_HEIGHT,
                                "顧客預約",
                                "預約")));
    }

    private Map<String, Object> postbackArea(
            int x, int y, String label, String data) {
        return Map.of(
                "bounds", bounds(x, y),
                "action", Map.of(
                        "type", "postback",
                        "label", label,
                        "data", data,
                        "displayText", label));
    }

    private Map<String, Object> messageArea(
            int x, int y, String label, String text) {
        return Map.of(
                "bounds", bounds(x, y),
                "action", Map.of(
                        "type", "message",
                        "label", label,
                        "text", text));
    }

    private Map<String, Integer> bounds(int x, int y) {
        return Map.of(
                "x", x,
                "y", y,
                "width", HALF_WIDTH,
                "height", HALF_HEIGHT);
    }

    private String menuName(String tenantId, String role) {
        return "line-ai-bot-staff-" + tenantId + "-" + role.toLowerCase();
    }
}
