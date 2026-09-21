package com.lineaibot.line;

import com.lineaibot.config.AppProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConversationContextService {
    private final ConversationContextRepository repository;
    private final AppProperties properties;
    private final ObjectMapper mapper;

    public ConversationContextService(ConversationContextRepository repository, AppProperties properties, ObjectMapper mapper) {
        this.repository = repository;
        this.properties = properties;
        this.mapper = mapper;
    }

    public static String sourceKey(JsonNode source, String eventId) {
        String type = source.path("type").asText("user");
        String id = switch (type) {
            case "user" -> source.path("userId").asText("");
            case "group" -> source.path("groupId").asText("");
            case "room" -> source.path("roomId").asText("");
            default -> "";
        };
        // An incomplete/unknown source must never share history with another event.
        return id.isBlank() || id.length() > 128 ? "event:" + eventId : type + ":" + id;
    }

    public ConversationContext.History load(String tenant, String user, String source, Instant now) {
        var settings = properties.getConversation();
        var rows = repository.recent(tenant, user, source,
                now.minusSeconds(settings.getIdleMinutes() * 60L), now, settings.getMaxTurns());
        var turns = new ArrayList<ConversationContext.Turn>();
        int remaining = settings.getMaxChars();
        for (var row : rows) {
            // An unanswered topic must not silently fall back to a different older topic.
            if (!row.delivered()) break;
            var state = mapper.readValue(row.stateJson(), ConversationContext.State.class);
            // Management replies contain private login URLs. Never send them to the language model.
            // Empty states also form a boundary after handoff, an operation, or an explicit reset.
            if (state.question().isBlank()) {
                if (turns.isEmpty() && !state.cancellationId().isBlank()) {
                    turns.add(new ConversationContext.Turn("", "", state));
                }
                break;
            }
            if (!settings.isEnabled()) break;
            StringBuilder answer = new StringBuilder();
            for (JsonNode message : mapper.readTree(row.replyJson())) {
                if (!answer.isEmpty()) answer.append('\n');
                answer.append(message.path("text").asText(""));
            }
            // Count state too; do not allow summaries to bypass the context budget.
            int size = row.customer().length() + answer.length() + row.stateJson().length();
            if (size > remaining) break;
            turns.add(new ConversationContext.Turn(row.customer(), answer.toString(),
                    state));
            remaining -= size;
        }
        Collections.reverse(turns);
        return new ConversationContext.History(java.util.List.copyOf(turns));
    }
}
