package com.lineaibot.knowledge;

import java.util.List;

public interface AiProvider {

    record TokenUsage(
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            long reasoningTokens,
            long totalTokens) {

        public static TokenUsage none() {
            return new TokenUsage(0, 0, 0, 0, 0);
        }

        public TokenUsage plus(TokenUsage other) {
            if (other == null) {
                return this;
            }
            return new TokenUsage(
                    inputTokens + other.inputTokens,
                    cachedInputTokens + other.cachedInputTokens,
                    outputTokens + other.outputTokens,
                    reasoningTokens + other.reasoningTokens,
                    totalTokens + other.totalTokens);
        }
    }

    record GroundingContext(
            String chunkId,
            String documentId,
            String title,
            String content,
            String sourceUrl,
            double score) {}

    record EmbeddingResult(
            List<double[]> embeddings,
            String provider,
            String model,
            String requestId,
            TokenUsage usage) {}

    record GeneratedText(
            String text,
            String provider,
            String model,
            String requestId,
            TokenUsage usage) {}

    record ConversationUnderstandingResult(
            com.lineaibot.line.ConversationContext.Understanding understanding,
            TokenUsage usage, String provider, String model, String requestId) {}

    default ConversationUnderstandingResult understandConversationWithUsage(
            String text, com.lineaibot.line.ConversationContext.History history, String safetyIdentifier) {
        return new ConversationUnderstandingResult(understandConversation(text, history, safetyIdentifier),
                TokenUsage.none(), name(), generationModel(), null);
    }

    String name();

    String embeddingModel();

    int embeddingDimensions();

    String generationModel();

    EmbeddingResult embedTexts(List<String> texts);

    default com.lineaibot.line.ConversationContext.Understanding understandConversation(
            String text, com.lineaibot.line.ConversationContext.History history, String safetyIdentifier) {
        return com.lineaibot.line.LocalConversationUnderstanding.resolve(text, history);
    }

    default GeneratedText generateAnswer(String question, List<GroundingContext> contexts,
            String tenantName, String safetyIdentifier, boolean bookingEnabled) {
        return generateAnswer(question, contexts, tenantName, safetyIdentifier);
    }

    GeneratedText generateAnswer(
            String question,
            List<GroundingContext> contexts,
            String tenantName,
            String safetyIdentifier);
}
