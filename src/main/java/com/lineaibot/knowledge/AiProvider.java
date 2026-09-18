package com.lineaibot.knowledge;

import java.util.List;

public interface AiProvider {

    record GroundingContext(
            String chunkId,
            String documentId,
            String title,
            String content,
            String sourceUrl,
            double score) {}

    record GeneratedText(String text, String provider, String model, String requestId) {}

    String name();

    String embeddingModel();

    int embeddingDimensions();

    String generationModel();

    List<double[]> embedTexts(List<String> texts);

    default com.lineaibot.line.ConversationContext.Understanding understandConversation(
            String text, com.lineaibot.line.ConversationContext.History history, String safetyIdentifier) {
        return com.lineaibot.line.LocalConversationUnderstanding.resolve(text, history);
    }

    GeneratedText generateAnswer(
            String question,
            List<GroundingContext> contexts,
            String tenantName,
            String safetyIdentifier);
}
