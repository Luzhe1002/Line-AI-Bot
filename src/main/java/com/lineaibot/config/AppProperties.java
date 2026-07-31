package com.lineaibot.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    @NotBlank
    private String environment = "development";

    @NotBlank
    private String encryptionKey = "change-this-development-encryption-key";

    @NotBlank
    private String platformAdminApiKey = "change-this-platform-admin-key";

    private boolean lineApiEnabled;
    private boolean lineWorkerEnabled = true;
    private long lineWorkerDelayMs = 250;

    @Min(1)
    @Max(64)
    private int lineWorkerConcurrency = 8;

    private String lineApiBaseUrl = "https://api.line.me";
    private String lineApiDataBaseUrl = "https://api-data.line.me";
    private String publicBaseUrl = "http://localhost:8000";
    private final Ai ai = new Ai();

    @PostConstruct
    void validateProductionSecrets() {
        if ("production".equalsIgnoreCase(environment)) {
            if (encryptionKey.startsWith("change-this")) {
                throw new IllegalStateException("APP_ENCRYPTION_KEY must be changed in production");
            }
            if (platformAdminApiKey.startsWith("change-this")) {
                throw new IllegalStateException(
                        "APP_PLATFORM_ADMIN_API_KEY must be changed in production");
            }
        }
        if (!"local".equals(ai.provider) && !"openai".equals(ai.provider)) {
            throw new IllegalStateException("APP_AI_PROVIDER must be local or openai");
        }
        if ("openai".equals(ai.provider) && (ai.openaiApiKey == null
                || ai.openaiApiKey.isBlank())) {
            throw new IllegalStateException(
                    "OPENAI_API_KEY is required when APP_AI_PROVIDER=openai");
        }
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    public String getPlatformAdminApiKey() {
        return platformAdminApiKey;
    }

    public void setPlatformAdminApiKey(String platformAdminApiKey) {
        this.platformAdminApiKey = platformAdminApiKey;
    }

    public boolean isLineApiEnabled() {
        return lineApiEnabled;
    }

    public void setLineApiEnabled(boolean lineApiEnabled) {
        this.lineApiEnabled = lineApiEnabled;
    }

    public boolean isLineWorkerEnabled() {
        return lineWorkerEnabled;
    }

    public void setLineWorkerEnabled(boolean lineWorkerEnabled) {
        this.lineWorkerEnabled = lineWorkerEnabled;
    }

    public long getLineWorkerDelayMs() {
        return lineWorkerDelayMs;
    }

    public void setLineWorkerDelayMs(long lineWorkerDelayMs) {
        this.lineWorkerDelayMs = lineWorkerDelayMs;
    }

    public int getLineWorkerConcurrency() {
        return lineWorkerConcurrency;
    }

    public void setLineWorkerConcurrency(int lineWorkerConcurrency) {
        this.lineWorkerConcurrency = lineWorkerConcurrency;
    }

    public String getLineApiBaseUrl() {
        return lineApiBaseUrl;
    }

    public void setLineApiBaseUrl(String lineApiBaseUrl) {
        this.lineApiBaseUrl = lineApiBaseUrl;
    }

    public String getLineApiDataBaseUrl() {
        return lineApiDataBaseUrl;
    }

    public void setLineApiDataBaseUrl(String lineApiDataBaseUrl) {
        this.lineApiDataBaseUrl = lineApiDataBaseUrl;
    }

    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    public void setPublicBaseUrl(String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public Ai getAi() {
        return ai;
    }

    public static class Ai {
        private String provider = "local";
        private String generationModel = "gpt-5.6-luna";
        private String embeddingModel = "text-embedding-3-small";

        @Min(64)
        @Max(3072)
        private int embeddingDimensions = 512;

        private String reasoningEffort = "none";
        private double minRetrievalScore = 0.20;
        private int maxContextDocuments = 4;
        private int maxContextChars = 6000;
        private int chunkSizeChars = 1200;
        private int chunkOverlapChars = 150;
        private int maxOutputTokens = 500;
        private int timeoutSeconds = 20;
        private String openaiApiKey;
        private String openaiBaseUrl = "https://api.openai.com/v1";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getGenerationModel() {
            return generationModel;
        }

        public void setGenerationModel(String generationModel) {
            this.generationModel = generationModel;
        }

        public String getEmbeddingModel() {
            return embeddingModel;
        }

        public void setEmbeddingModel(String embeddingModel) {
            this.embeddingModel = embeddingModel;
        }

        public int getEmbeddingDimensions() {
            return embeddingDimensions;
        }

        public void setEmbeddingDimensions(int embeddingDimensions) {
            this.embeddingDimensions = embeddingDimensions;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }

        public double getMinRetrievalScore() {
            return minRetrievalScore;
        }

        public void setMinRetrievalScore(double minRetrievalScore) {
            this.minRetrievalScore = minRetrievalScore;
        }

        public int getMaxContextDocuments() {
            return maxContextDocuments;
        }

        public void setMaxContextDocuments(int maxContextDocuments) {
            this.maxContextDocuments = maxContextDocuments;
        }

        public int getMaxContextChars() {
            return maxContextChars;
        }

        public void setMaxContextChars(int maxContextChars) {
            this.maxContextChars = maxContextChars;
        }

        public int getChunkSizeChars() {
            return chunkSizeChars;
        }

        public void setChunkSizeChars(int chunkSizeChars) {
            this.chunkSizeChars = chunkSizeChars;
        }

        public int getChunkOverlapChars() {
            return chunkOverlapChars;
        }

        public void setChunkOverlapChars(int chunkOverlapChars) {
            this.chunkOverlapChars = chunkOverlapChars;
        }

        public int getMaxOutputTokens() {
            return maxOutputTokens;
        }

        public void setMaxOutputTokens(int maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String getOpenaiApiKey() {
            return openaiApiKey;
        }

        public void setOpenaiApiKey(String openaiApiKey) {
            this.openaiApiKey = openaiApiKey;
        }

        public String getOpenaiBaseUrl() {
            return openaiBaseUrl;
        }

        public void setOpenaiBaseUrl(String openaiBaseUrl) {
            this.openaiBaseUrl = openaiBaseUrl;
        }
    }
}
