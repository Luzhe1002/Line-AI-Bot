package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import com.lineaibot.merchant.MerchantRepository;
import com.lineaibot.merchant.MerchantStaffService;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository;
import com.lineaibot.tenant.TenantRepository.TenantRow;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class HandoffNotificationService {

    private final MerchantRepository merchants;
    private final MerchantStaffService staffService;
    private final TenantRepository tenants;
    private final LineMessagingClient lineClient;
    private final CryptoService crypto;
    private final AppProperties properties;

    public HandoffNotificationService(
            MerchantRepository merchants,
            MerchantStaffService staffService,
            TenantRepository tenants,
            LineMessagingClient lineClient,
            CryptoService crypto,
            AppProperties properties) {
        this.merchants = merchants;
        this.staffService = staffService;
        this.tenants = tenants;
        this.lineClient = lineClient;
        this.crypto = crypto;
        this.properties = properties;
    }

    public void notifyMerchant(TenantRow tenant, String ticketId) {
        var recipients = merchants.listActiveStaffSecrets(tenant.id());
        if (recipients.isEmpty()) {
            return;
        }
        var channel = tenants.findLineChannel(tenant.id())
                .filter(TenantRepository.LineChannelRow::enabled)
                .orElseThrow(() -> new IllegalStateException("LINE channel is unavailable"));
        String channelToken = crypto.decryptSecret(
                properties.getEncryptionKey(), channel.channelAccessTokenEncrypted());
        List<Map<String, Object>> messages = List.of(Map.of(
                "type", "text",
                "text", "顧客要求轉接人工客服\n請儘快查看並回覆顧客。\n案件編號："
                        + shortId(ticketId)));
        for (MerchantRepository.StaffSecretRow recipient : recipients) {
            lineClient.push(
                    tenant.id(),
                    channelToken,
                    staffService.decryptLineUserId(recipient.lineUserIdEncrypted()),
                    messages,
                    "handoff:" + ticketId + ":staff:" + recipient.staff().id());
        }
    }

    private String shortId(String id) {
        return id.substring(0, Math.min(id.length(), 8)).toUpperCase();
    }
}
