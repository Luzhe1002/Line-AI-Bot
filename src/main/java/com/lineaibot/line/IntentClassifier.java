package com.lineaibot.line;

import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class IntentClassifier {

    public enum Intent {
        BOOKING,
        CANCEL_BOOKING,
        HUMAN_HANDOFF,
        KNOWLEDGE
    }

    public Intent classify(String text) {
        String normalized = text.strip().toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "取消預約", "取消訂位", "cancel booking", "取消")) {
            return Intent.CANCEL_BOOKING;
        }
        if (containsAny(normalized, "真人", "人工", "客服人員", "專人", "human")) {
            return Intent.HUMAN_HANDOFF;
        }
        if (containsAny(normalized, "預約", "訂位", "預訂", "booking", "book")) {
            return Intent.BOOKING;
        }
        return Intent.KNOWLEDGE;
    }

    private boolean containsAny(String value, String... terms) {
        for (String term : terms) {
            if (value.contains(term)) {
                return true;
            }
        }
        return false;
    }
}
