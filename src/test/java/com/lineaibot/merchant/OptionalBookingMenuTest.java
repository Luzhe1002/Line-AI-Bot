package com.lineaibot.merchant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lineaibot.config.AppProperties;
import com.lineaibot.line.LineMessagingClient;
import com.lineaibot.shared.CryptoService;
import com.lineaibot.tenant.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OptionalBookingMenuTest {
    @Test
    void definitionsSeparateCustomerActionsFromStaffPrivilegesInBothModes() {
        var service = new MerchantRichMenuService(null, null, null, null, null, null);
        String customer = service.definition("retail", "CUSTOMER_SUPPORT").toString();
        assertThat(customer).contains("商品與服務", "營業資訊", "常見問題", "人工客服")
                .doesNotContain("預約", "merchant_", "管理後台");
        String owner = service.definition("owner", "OWNER_SUPPORT").toString();
        assertThat(owner).contains("merchant_portal", "merchant_support").doesNotContain("預約");
        String viewer = service.definition("viewer", "VIEWER_SUPPORT").toString();
        assertThat(viewer).contains("merchant_info", "merchant_support").doesNotContain("merchant_portal", "預約");
        assertThat(service.definition("booking", "CUSTOMER_BOOKING").toString())
                .contains("立即預約", "查詢預約").doesNotContain("merchant_");
    }

    @Test
    void customerMenuSyncRetriesFailuresAndPublishesCurrentMode() {
        var repository = mock(MerchantRichMenuRepository.class);
        var tenants = mock(TenantRepository.class);
        var client = mock(LineMessagingClient.class);
        var images = mock(MerchantRichMenuImageFactory.class);
        var crypto = mock(CryptoService.class);
        var properties = new AppProperties();
        var service = new MerchantRichMenuService(repository, tenants, client, images, crypto, properties);
        var job = new MerchantRichMenuRepository.CustomerMenuJob("tenant", 3);
        when(repository.readyCustomerMenus(any())).thenReturn(List.of(job));
        when(repository.claimCustomerMenu(eq(job), any())).thenReturn(true);
        when(tenants.findById("tenant")).thenReturn(Optional.of(new TenantRepository.TenantRow(
                "tenant", "shop", "Shop", "Asia/Taipei", 60, "hash", true, Instant.now(), false)));
        when(tenants.findLineChannel("tenant")).thenReturn(Optional.of(new TenantRepository.LineChannelRow(
                "channel", "tenant", "secret", "encrypted", true)));
        when(crypto.decryptSecret(any(), eq("encrypted"))).thenReturn("token");
        when(repository.findRichMenu("tenant", "CUSTOMER_SUPPORT")).thenReturn(Optional.of(
                new MerchantRichMenuRepository.RichMenuRow("menu", "tenant", "CUSTOMER_SUPPORT", "menu-id", "READY")));
        doThrow(new IllegalStateException("temporarily unavailable")).doNothing().when(client).setDefaultRichMenu("token", "menu-id");
        service.syncCustomerMenus();
        verify(repository).finishCustomerMenu(eq(job), eq(false), any());
        service.syncCustomerMenus();
        verify(repository).finishCustomerMenu(eq(job), eq(true), any());
        verify(client, times(2)).setDefaultRichMenu("token", "menu-id");
        verify(client, never()).linkRichMenu(any(), any(), any());
    }
}
