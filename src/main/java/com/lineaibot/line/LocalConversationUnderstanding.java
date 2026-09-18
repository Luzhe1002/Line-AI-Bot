package com.lineaibot.line;

import java.util.regex.Pattern;
import com.lineaibot.line.ConversationContext.History;
import com.lineaibot.line.ConversationContext.Understanding;

/** Conservative, offline resolver. OpenAI handles unrestricted language in production. */
public final class LocalConversationUnderstanding {
    private static final Pattern ASPECT = Pattern.compile(
            "(?:多少錢|多少費用|價格|價錢|費用|要多久|需要多久|多久|時間|時長|可以刷卡嗎|能刷卡嗎|怎麼付款|注意事項|多少時間)");
    private LocalConversationUnderstanding() {}

    public static Understanding resolve(String text, History history) {
        String value = text.strip();
        String intent = new IntentClassifier().classify(value).name();
        if (!intent.equals("KNOWLEDGE")) return new Understanding(intent, value, "", false, "");
        if (value.matches("(?:謝謝|謝啦|好的|好|了解|收到|不用了|沒事)[！!。]*")) {
            return new Understanding("ACKNOWLEDGEMENT", value, "", false, "");
        }
        var previous = history.latestState();
        var aspectMatcher = ASPECT.matcher(value);
        String aspect = aspectMatcher.find() ? aspectMatcher.group() : "";
        String topic = topicOf(value);
        if (!topic.isBlank() && topic.matches(".*(?:和|與|、|以及|或).*")) {
            return clarify(value, "", "您想先詢問哪一項服務？請告訴我服務名稱與想了解的內容。");
        }
        if (!topic.isBlank() && !previous.pendingQuestion().isBlank() && aspect.isBlank()) {
            var pending = ASPECT.matcher(previous.question());
            if (pending.find()) aspect = pending.group();
        }
        if (!topic.isBlank() && value.matches("(?:那|換成|改成).*呢[？?]?")) {
            var oldAspect = ASPECT.matcher(previous.question());
            if (oldAspect.find()) aspect = oldAspect.group();
            else return clarify(value, topic, "您想了解" + topic + "的價格、時間，還是其他資訊？");
        }
        if (isFollowUp(value) && topic.isBlank()) {
            topic = previous.topic();
            if (topic.isBlank()) return clarify(value, "", "您是指哪一項服務或哪件事情呢？");
            // Only carry a resolved topic; never concatenate old answers as knowledge.
            return new Understanding("KNOWLEDGE", topic + "：" + value, topic, false, "");
        }
        String question = !topic.isBlank() && !aspect.isBlank() ? topic + "：" + aspect + "？" : value;
        return new Understanding("KNOWLEDGE", question, topic, false, "");
    }

    public static boolean isFollowUp(String value) {
        return value.matches("^(?:那|這個|那個|它|這項|還有|然後|改成|換成).*" )
                || value.matches("^(?:要|需要)?(?:多久|多少錢|多少費用|怎麼付款|可以刷卡).*" )
                || value.matches("^(?:好|好的|對|是|不是|兩位|三位|明天|後天|下午|上午)[？?。！!]*$");
    }

    private static String topicOf(String text) {
        String value = text.replaceFirst("^(?:請問|想問|我想問|那|換成|改成)", "");
        var matcher = ASPECT.matcher(value);
        if (matcher.find()) value = value.substring(0, matcher.start());
        value = value.replaceAll("(?:需要|要|的|呢|可以|能|？|\\?|：|:|。|！|!)+$", "").strip();
        if (value.matches("(?:這個|那個|它|這項|服務|那|這|好|好的|對|是|不是|兩位|三位|明天|後天|下午|上午)?")) return "";
        // Longer free-form questions are not a safely resolved subject offline.
        return value.length() <= 40 ? value : "";
    }

    private static Understanding clarify(String question, String topic, String prompt) {
        return new Understanding("KNOWLEDGE", question, topic, true, prompt);
    }
}
