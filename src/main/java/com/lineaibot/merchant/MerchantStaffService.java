package com.lineaibot.merchant;

import static com.lineaibot.merchant.MerchantDtos.StaffLinkCreate;
import static com.lineaibot.merchant.MerchantDtos.StaffLinkView;
import static com.lineaibot.merchant.MerchantDtos.StaffUpdate;
import static com.lineaibot.merchant.MerchantDtos.StaffView;

import com.lineaibot.config.AppProperties;
import com.lineaibot.shared.ApiException;
import com.lineaibot.shared.CryptoService;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantStaffService {

    private static final String LINK_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LINK_TTL_MINUTES = 10;

    private final MerchantRepository repository;
    private final MerchantRichMenuService richMenus;
    private final CryptoService crypto;
    private final AppProperties properties;

    public MerchantStaffService(
            MerchantRepository repository,
            MerchantRichMenuService richMenus,
            CryptoService crypto,
            AppProperties properties) {
        this.repository = repository;
        this.richMenus = richMenus;
        this.crypto = crypto;
        this.properties = properties;
    }

    public StaffLinkView createLink(String tenantId, StaffLinkCreate request) {
        String role = requireRole(request.role());
        String displayName = request.displayName().strip();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(LINK_TTL_MINUTES, ChronoUnit.MINUTES);
        for (int attempt = 0; attempt < 5; attempt++) {
            String code = randomCode();
            try {
                repository.insertStaffLink(
                        UUID.randomUUID().toString(),
                        tenantId,
                        linkHash(code),
                        displayName,
                        role,
                        expiresAt,
                        now);
                return new StaffLinkView(code, displayName, role, expiresAt);
            } catch (DataIntegrityViolationException exception) {
                if (attempt == 4) {
                    throw new IllegalStateException("Unable to create a unique staff link");
                }
            }
        }
        throw new IllegalStateException("Unable to create a staff link");
    }

    @Transactional
    public StaffView bind(String tenantId, String lineUserId, String code) {
        if (lineUserId == null || lineUserId.isBlank()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LINE user is unavailable");
        }
        String normalizedCode = normalizeCode(code);
        Instant now = Instant.now();
        var link = repository.findUsableStaffLink(tenantId, linkHash(normalizedCode), now)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "綁定碼無效、已使用或已過期"));
        String lookupKey = lineUserKey(lineUserId);
        var existing = repository.findStaffByLineKey(tenantId, lookupKey);
        if (existing.filter(staff -> "ACTIVE".equals(staff.status())).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "這個 LINE 已經綁定店家管理權限");
        }
        StaffView staff;
        if (existing.isPresent()) {
            staff = repository.reactivateStaff(
                    tenantId,
                    existing.get().id(),
                    encryptLineUserId(lineUserId),
                    link.displayName(),
                    link.role(),
                    now);
        } else {
            try {
                staff = repository.insertStaff(
                        UUID.randomUUID().toString(),
                        tenantId,
                        lookupKey,
                        encryptLineUserId(lineUserId),
                        link.displayName(),
                        link.role(),
                        now);
            } catch (DataIntegrityViolationException exception) {
                throw new ApiException(
                        HttpStatus.CONFLICT, "這個 LINE 已經綁定店家管理權限");
            }
        }
        if (!repository.consumeStaffLink(link.id(), now)) {
            throw new ApiException(HttpStatus.CONFLICT, "綁定碼已被使用");
        }
        richMenus.scheduleStaff(staff);
        return staff;
    }

    public Optional<StaffView> findActive(String tenantId, String lineUserId) {
        if (lineUserId == null || lineUserId.isBlank()) {
            return Optional.empty();
        }
        return repository.findActiveStaffByLineKey(tenantId, lineUserKey(lineUserId));
    }

    public StaffView requireActive(String tenantId, String staffId) {
        return repository.findStaff(tenantId, staffId)
                .filter(staff -> "ACTIVE".equals(staff.status()))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNAUTHORIZED, "Merchant staff session is invalid"));
    }

    public List<StaffView> list(String tenantId) {
        return repository.listStaff(tenantId);
    }

    public StaffView update(String tenantId, String staffId, StaffUpdate request) {
        StaffView current = repository.findStaff(tenantId, staffId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Staff not found"));
        String displayName = request.displayName() == null
                ? current.displayName()
                : request.displayName().strip();
        if (displayName.isBlank()) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "Staff display name is required");
        }
        String role = request.role() == null ? current.role() : requireRole(request.role());
        String status = request.status() == null
                ? current.status()
                : requireStatus(request.status());
        boolean removesLastOwner = "OWNER".equals(current.role())
                && "ACTIVE".equals(current.status())
                && (!"OWNER".equals(role) || !"ACTIVE".equals(status))
                && repository.countActiveOwners(tenantId) <= 1;
        if (removesLastOwner) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "At least one active owner is required");
        }
        StaffView updated = repository.updateStaff(
                tenantId,
                staffId,
                displayName,
                role,
                status,
                request.notifyNewBooking() == null
                        ? current.notifyNewBooking()
                        : request.notifyNewBooking(),
                request.notifyCancellation() == null
                        ? current.notifyCancellation()
                        : request.notifyCancellation(),
                request.dailySummaryEnabled() == null
                        ? current.dailySummaryEnabled()
                        : request.dailySummaryEnabled(),
                request.dailySummaryTime() == null
                        ? current.dailySummaryTime()
                        : request.dailySummaryTime(),
                Instant.now());
        richMenus.scheduleStaff(updated);
        return updated;
    }

    @Transactional
    public void remove(String tenantId, String staffId) {
        StaffView current = repository.findStaff(tenantId, staffId)
                .filter(staff -> "ACTIVE".equals(staff.status()))
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "找不到這位已綁定人員"));
        if ("OWNER".equals(current.role())
                && repository.countActiveOwners(tenantId) <= 1) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "至少需要保留一位擁有者");
        }
        StaffView removed = repository.disableStaff(tenantId, staffId, Instant.now());
        richMenus.scheduleStaff(removed);
    }

    public boolean canMutateBookings(StaffView staff) {
        return "OWNER".equals(staff.role()) || "MANAGER".equals(staff.role());
    }

    public String decryptLineUserId(String encrypted) {
        return crypto.decryptSecret(lineEncryptionKey(), encrypted);
    }

    private String encryptLineUserId(String lineUserId) {
        return crypto.encryptSecret(lineEncryptionKey(), lineUserId);
    }

    private String lineUserKey(String lineUserId) {
        return crypto.stableHmac(lineLookupKey(), lineUserId);
    }

    private String linkHash(String code) {
        return crypto.stableHmac(linkKey(), normalizeCode(code));
    }

    private String normalizeCode(String code) {
        return code == null ? "" : code.replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
    }

    private String requireRole(String role) {
        String normalized = role == null ? "" : role.strip().toUpperCase(Locale.ROOT);
        if (!List.of("OWNER", "MANAGER", "VIEWER").contains(normalized)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Staff role must be OWNER, MANAGER, or VIEWER");
        }
        return normalized;
    }

    private String requireStatus(String status) {
        String normalized = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
        if (!List.of("ACTIVE", "DISABLED").contains(normalized)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "Staff status must be ACTIVE or DISABLED");
        }
        return normalized;
    }

    private String randomCode() {
        StringBuilder value = new StringBuilder(8);
        for (int index = 0; index < 8; index++) {
            value.append(LINK_ALPHABET.charAt(RANDOM.nextInt(LINK_ALPHABET.length())));
        }
        return value.toString();
    }

    private String lineLookupKey() {
        return properties.getEncryptionKey() + ":merchant-staff-line-key";
    }

    private String lineEncryptionKey() {
        return properties.getEncryptionKey() + ":merchant-staff-line-encryption";
    }

    private String linkKey() {
        return properties.getEncryptionKey() + ":merchant-staff-link";
    }
}
