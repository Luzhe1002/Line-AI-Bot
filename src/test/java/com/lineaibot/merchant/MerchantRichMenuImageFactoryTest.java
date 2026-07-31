package com.lineaibot.merchant;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MerchantRichMenuImageFactoryTest {

    private final MerchantRichMenuImageFactory factory =
            new MerchantRichMenuImageFactory();

    @Test
    void rendersLineCompatibleRoleMenus() throws Exception {
        for (String role : new String[] {"OWNER", "MANAGER", "VIEWER"}) {
            byte[] png = factory.create(role);
            var image = ImageIO.read(new ByteArrayInputStream(png));

            assertThat(image.getWidth()).isEqualTo(2500);
            assertThat(image.getHeight()).isEqualTo(1686);
            assertThat(png.length).isLessThan(1_000_000);
        }
    }

    @Test
    void usesChineseLabelsForOwnerAndBookingManagementMenus() {
        assertThat(MerchantRichMenuImageFactory.labelsForRole("OWNER"))
                .containsExactly(
                        "管理後台", "完整設定",
                        "今日預約", "查看行程",
                        "未來七天", "預約行程",
                        "顧客預約", "建立預約");
        assertThat(MerchantRichMenuImageFactory.labelsForRole("MANAGER"))
                .startsWith("預約管理", "查看月曆");
        assertThat(MerchantRichMenuImageFactory.labelsForRole("VIEWER"))
                .startsWith("預約管理", "查看月曆");
    }

    @Test
    void usesAVersionedChineseMenuNameInsteadOfReusingTheEnglishMenu() {
        assertThat(MerchantRichMenuService.menuName("tenant-1", "OWNER"))
                .isEqualTo("line-ai-bot-staff-tenant-1-owner-zh-tw-v1");
    }
}
