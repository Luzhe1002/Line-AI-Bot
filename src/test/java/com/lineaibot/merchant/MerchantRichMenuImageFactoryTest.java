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
}
