package com.lineaibot.knowledge;

import com.lineaibot.config.AppProperties;
import org.springframework.stereotype.Component;

@Component
public class AiProviderRegistry {

    private final AppProperties properties;
    private final LocalAiProvider local;
    private final OpenAiProvider openAi;

    public AiProviderRegistry(
            AppProperties properties, LocalAiProvider local, OpenAiProvider openAi) {
        this.properties = properties;
        this.local = local;
        this.openAi = openAi;
    }

    public AiProvider current() {
        return "openai".equals(properties.getAi().getProvider()) ? openAi : local;
    }
}
