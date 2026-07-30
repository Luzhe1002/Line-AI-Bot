package com.lineaibot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenAiProviderTest {

    @Test
    void answerInstructionsRequestNaturalGroundedCustomerServiceTone() {
        String instructions = OpenAiProvider.answerInstructions();

        assertThat(instructions)
                .contains("自然、圓潤、有服務感")
                .contains("二至三句")
                .contains("不要照貼來源句子")
                .contains("不得使用未出現在資料中的事實")
                .contains("不得宣稱已完成預約、取消、退款或其他交易");
    }
}
