package com.lineaibot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.AiProvider.GroundingContext;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalAiProviderTest {

    @Test
    void answersWithOnlyTheSentenceRelevantToTheQuestion() {
        var provider = new LocalAiProvider(new AppProperties());
        var context = new GroundingContext(
                "chunk-1",
                "document-1",
                "店家規則",
                "本店營業時間為週一至週五上午九點到下午六點。"
                        + "本店接受現金及信用卡付款。"
                        + "預約如需取消，請提前一天通知。",
                null,
                0.9);

        var answer = provider.generateAnswer(
                "可以刷卡嗎？", List.of(context), "測試商家", "safe-user");

        assertThat(answer.text()).isEqualTo("本店接受現金及信用卡付款。");
        assertThat(answer.text()).doesNotContain("營業時間", "取消");
        assertThat(answer.model()).isEqualTo("local-extractive-v2");
    }
}
