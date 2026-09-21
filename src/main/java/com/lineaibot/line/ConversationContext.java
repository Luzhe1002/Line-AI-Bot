package com.lineaibot.line;

import java.util.List;
import java.util.Map;

public final class ConversationContext {
    private ConversationContext() {}

    public record State(String topic, String question, String pendingQuestion,
                        String cancellationId, String confirmationToken) {
        public static State empty() { return new State("", "", "", "", ""); }
    }

    public record Turn(String customer, String assistant, State state) {}

    public record History(List<Turn> turns) {
        public static History empty() { return new History(List.of()); }
        public State latestState() {
            return turns.isEmpty() ? State.empty() : turns.getLast().state();
        }
    }

    public record Reply(List<Map<String, Object>> messages, State state) {
        public static Reply plain(List<Map<String, Object>> messages) {
            return new Reply(messages, State.empty());
        }
    }

    public record Understanding(String intent, String standaloneQuestion, String topic,
                                boolean needsClarification, String clarificationQuestion) {}
}
