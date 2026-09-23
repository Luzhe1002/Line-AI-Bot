package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.AiProviderRegistry;
import com.lineaibot.knowledge.AiUsageService;
import com.lineaibot.shared.CryptoService;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ConversationUnderstandingService {
    private static final Logger log = LoggerFactory.getLogger(ConversationUnderstandingService.class);
    private static final Set<String> INTENTS = Set.of("KNOWLEDGE", "BOOKING", "CANCEL_BOOKING", "HUMAN_HANDOFF", "ACKNOWLEDGEMENT");
    private final AiProviderRegistry providers;
    private final IntentClassifier classifier;
    private final CryptoService crypto;
    private final AppProperties properties;
    private final AiUsageService aiUsage;

    public ConversationUnderstandingService(AiProviderRegistry providers, IntentClassifier classifier,
                                            CryptoService crypto, AppProperties properties, AiUsageService aiUsage) {
        this.providers = providers;
        this.classifier = classifier;
        this.crypto = crypto;
        this.properties = properties;
        this.aiUsage = aiUsage;
    }

    public ConversationContext.Understanding understand(String tenant, String user, String text,
                                                       ConversationContext.History history) {
        var intent = classifier.classify(text);
        if (intent != IntentClassifier.Intent.KNOWLEDGE) {
            return new ConversationContext.Understanding(intent.name(), text, "", false, "");
        }
        var provider = providers.current();
        // Local resolution makes no external request. Hosted understanding must use the same
        // global switch, actor limits and token budget as grounded answer generation.
        if ("local".equals(provider.name())) return LocalConversationUnderstanding.resolve(text, history);
        long historyChars = history.turns().stream().mapToLong(turn -> (long) turn.customer().length()
                + turn.assistant().length() + turn.state().topic().length()
                + turn.state().question().length() + turn.state().pendingQuestion().length()).sum();
        var lease = aiUsage.acquire(tenant, user, "LINE_UNDERSTANDING",
                4096L + 6L * (text.length() + historyChars) + 1000L, true);
        if (!lease.allowed()) return LocalConversationUnderstanding.resolve(text, history);
        try {
            String identifier = "line_user_" + crypto.stableHmac(properties.getEncryptionKey(), tenant + ":" + user).substring(0, 32);
            var response = provider.understandConversationWithUsage(text, history, identifier);
            var result = response == null ? null : response.understanding();
            if (result == null || !INTENTS.contains(result.intent()) || result.standaloneQuestion() == null
                    || result.standaloneQuestion().isBlank() || result.standaloneQuestion().length() > 1500
                    || result.topic() == null || result.topic().length() > 200
                    || result.clarificationQuestion() == null || result.clarificationQuestion().length() > 500
                    || (result.needsClarification() && result.clarificationQuestion().isBlank())) {
                throw new IllegalStateException("Invalid conversation understanding");
            }
            aiUsage.succeed(lease, response.usage(), response.provider(), response.model(), response.requestId());
            // Models may suggest a flow, but never execute an operation. Cancellation still requires postback confirmation.
            return result;
        } catch (RuntimeException exception) {
            aiUsage.fail(lease, exception);
            log.warn("Conversation understanding unavailable tenantId={} errorType={}", tenant, exception.getClass().getSimpleName());
            return LocalConversationUnderstanding.resolve(text, history);
        }
    }
}
