package com.lineaibot.line;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.lineaibot.config.AppProperties;
import com.lineaibot.knowledge.AiProvider;
import com.lineaibot.knowledge.AiProviderRegistry;
import com.lineaibot.shared.CryptoService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ConversationUnderstandingTest {
    private ConversationContext.History history(String topic, String question, String pending) {
        return new ConversationContext.History(List.of(new ConversationContext.Turn(question, "回覆",
                new ConversationContext.State(topic, question, pending, "", ""))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"取消要收費嗎？", "預約前需要注意什麼？", "可以取消嗎？", "不要取消預約", "人工客服幾點上班？", "Facebook 有優惠嗎？", "What is the cancellation policy?", "我不想預約", "取消會怎樣", "I read a book", "Can I cancel?"})
    void questionsAndNegationsNeverStartOperations(String text) {
        assertThat(new IntentClassifier().classify(text)).isEqualTo(IntentClassifier.Intent.KNOWLEDGE);
    }

    @Test
    void explicitRequestsStillRouteWithoutAnAiCall() {
        var classifier = new IntentClassifier();
        assertThat(classifier.classify("我要取消預約")).isEqualTo(IntentClassifier.Intent.CANCEL_BOOKING);
        assertThat(classifier.classify("我要人工客服")).isEqualTo(IntentClassifier.Intent.HUMAN_HANDOFF);
        assertThat(classifier.classify("我要預約")).isEqualTo(IntentClassifier.Intent.BOOKING);
    }

    @Test
    void followsTopicChangesTopicAndResolvesClarification() {
        var first = LocalConversationUnderstanding.resolve("深層清潔多少錢？", ConversationContext.History.empty());
        assertThat(first.topic()).isEqualTo("深層清潔");
        var next = LocalConversationUnderstanding.resolve("那要多久？", history(first.topic(), first.standaloneQuestion(), ""));
        assertThat(next.standaloneQuestion()).contains("深層清潔", "多久");
        var changed = LocalConversationUnderstanding.resolve("按摩多少錢？", history(first.topic(), next.standaloneQuestion(), ""));
        assertThat(changed.standaloneQuestion()).contains("按摩").doesNotContain("深層清潔");
        var corrected = LocalConversationUnderstanding.resolve("換成按摩呢？", history(first.topic(), next.standaloneQuestion(), ""));
        assertThat(corrected.standaloneQuestion()).contains("按摩", "多久").doesNotContain("深層清潔");
        var clarified = LocalConversationUnderstanding.resolve("深層清潔", history("", "那要多久？", "您是問哪項服務？"));
        assertThat(clarified.standaloneQuestion()).contains("深層清潔", "多久");
    }

    @Test
    void missingOrAmbiguousSubjectAsksInsteadOfGuessing() {
        assertThat(LocalConversationUnderstanding.resolve("那要多久？", ConversationContext.History.empty()).needsClarification()).isTrue();
        var ambiguous = LocalConversationUnderstanding.resolve("清潔和按摩要多久？", ConversationContext.History.empty());
        assertThat(ambiguous.needsClarification()).isTrue();
        var yes = LocalConversationUnderstanding.resolve("對", ConversationContext.History.empty());
        assertThat(yes.needsClarification()).isTrue();
        assertThat(yes.intent()).isEqualTo("KNOWLEDGE");
    }

    @Test
    void providerFailureFallsBackWithoutExecutingAnything() {
        var providers = mock(AiProviderRegistry.class);
        var provider = mock(AiProvider.class);
        when(providers.current()).thenReturn(provider);
        when(provider.understandConversation(anyString(), any(), anyString())).thenThrow(new IllegalStateException("timeout"));
        var service = new ConversationUnderstandingService(providers, new IntentClassifier(), new CryptoService(), new AppProperties());
        assertThat(service.understand("tenant", "user", "那要多久？", ConversationContext.History.empty()).needsClarification()).isTrue();
    }
}
