package com.lineaibot.shared;

import com.lineaibot.config.AppProperties;
import com.lineaibot.tenant.TenantRepository;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ApiAuthService {

    private final AppProperties properties;
    private final CryptoService crypto;
    private final TenantRepository tenants;

    public ApiAuthService(
            AppProperties properties, CryptoService crypto, TenantRepository tenants) {
        this.properties = properties;
        this.crypto = crypto;
        this.tenants = tenants;
    }

    public void requirePlatformAdmin(String suppliedKey) {
        if (!crypto.constantTimeEquals(suppliedKey, properties.getPlatformAdminApiKey())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid platform admin API key");
        }
    }

    public TenantRow requireTenantAdmin(String tenantId, String suppliedKey) {
        TenantRow tenant = tenants.findById(tenantId)
                .filter(TenantRow::active)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Tenant not found"));
        if (!crypto.verifyApiKey(suppliedKey, tenant.adminApiKeyHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid tenant API key");
        }
        return tenant;
    }
}
