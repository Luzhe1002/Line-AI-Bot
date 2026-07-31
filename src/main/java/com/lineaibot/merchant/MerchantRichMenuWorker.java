package com.lineaibot.merchant;

import com.lineaibot.config.AppProperties;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MerchantRichMenuWorker {

    private static final int BATCH_SIZE = 10;

    private final MerchantRichMenuService richMenus;
    private final AppProperties properties;

    public MerchantRichMenuWorker(
            MerchantRichMenuService richMenus, AppProperties properties) {
        this.richMenus = richMenus;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.line-rich-menu-worker-delay-ms:2000}")
    public void runOnce() {
        if (!properties.isLineApiEnabled() || !properties.isLineWorkerEnabled()) {
            return;
        }
        Instant now = Instant.now();
        richMenus.recoverStaleJobs(now.minus(2, ChronoUnit.MINUTES), now);
        for (String staffId : richMenus.findReadyStaffIds(now, BATCH_SIZE)) {
            if (richMenus.claim(staffId, Instant.now())) {
                richMenus.processClaimed(staffId);
            }
        }
    }
}
