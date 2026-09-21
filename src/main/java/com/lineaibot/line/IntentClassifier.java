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
        String normalized = text.strip().toLowerCase(Locale.ROOT).replaceAll("[！!。]+$", "");
        if (isInformationQuestion(normalized)) return Intent.KNOWLEDGE;
        if (containsAny(normalized, "不要", "不想", "不用", "不取消", "不預約", "不需要", "don't", "do not")) {
            return Intent.KNOWLEDGE;
        }
        // Only explicit requests take the deterministic shortcut. Mere mentions go to understanding.
        String requestPrefix = "(?:(?:請|麻煩|幫我|請幫我|我要|我想|我想要|想要|想|要|請你)\\s*)*";
        if (normalized.matches("(?:please )?cancel(?: (?:my )?(?:booking|reservation))?")
                || normalized.matches(requestPrefix + "取消(?:預約|訂位)?")) {
            return Intent.CANCEL_BOOKING;
        }
        if (normalized.matches(requestPrefix + "(?:轉接|轉|找)?(?:真人|人工(?:客服)?|客服人員|專人)")
                || normalized.matches("(?:human|(?:talk|speak) to (?:a )?human)")) {
            return Intent.HUMAN_HANDOFF;
        }
        if (normalized.matches(requestPrefix + "(?:預約|訂位|預訂)")
                || normalized.matches("(?:booking|book|(?:i want to |please )book(?: an appointment)?)")) {
            return Intent.BOOKING;
        }
        return Intent.KNOWLEDGE;
    }

    private boolean isInformationQuestion(String text) {
        return containsAny(text, "多少", "多久", "收費", "費用", "手續費", "退款", "規則", "規定",
                "政策", "注意", "提前", "最晚", "怎麼", "如何", "什麼", "是否", "需要", "能不能",
                "可不可以", "可以取消嗎", "可以預約嗎", "為什麼", "幾點", "營業時間", "取消嗎",
                "預約嗎", "how ", "when ", "what ", "fee", "policy", "refund", "cost");
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
