package com.lineaibot.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

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

    @Test
    void parsesOfficialResponseAndEmbeddingUsageFields() throws Exception {
        var objectMapper = new ObjectMapper();
        var response = objectMapper.readTree("""
                {
                  "usage": {
                    "input_tokens": 100,
                    "input_tokens_details": {"cached_tokens": 25},
                    "output_tokens": 40,
                    "output_tokens_details": {"reasoning_tokens": 10},
                    "total_tokens": 140
                  }
                }
                """);
        var embedding = objectMapper.readTree("""
                {"usage": {"prompt_tokens": 50, "total_tokens": 50}}
                """);

        var responseUsage = OpenAiProvider.responseUsage(response, 1, 1);
        var embeddingUsage = OpenAiProvider.embeddingUsage(embedding, 1);

        assertThat(responseUsage.inputTokens()).isEqualTo(100);
        assertThat(responseUsage.cachedInputTokens()).isEqualTo(25);
        assertThat(responseUsage.outputTokens()).isEqualTo(40);
        assertThat(responseUsage.reasoningTokens()).isEqualTo(10);
        assertThat(responseUsage.totalTokens()).isEqualTo(140);
        assertThat(embeddingUsage.inputTokens()).isEqualTo(50);
        assertThat(embeddingUsage.totalTokens()).isEqualTo(50);
    }
}
