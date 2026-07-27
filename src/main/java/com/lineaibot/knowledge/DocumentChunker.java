package com.lineaibot.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DocumentChunker {

    private static final Pattern INLINE_SPACE = Pattern.compile("[ \\t]+");
    private static final List<String> BOUNDARIES =
            List.of("\n\n", "\n", "。", "！", "？", ". ", "! ", "? ", " ");

    public List<String> split(String value, int maxChars, int overlapChars) {
        String content = normalize(value);
        if (content.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + maxChars, content.length());
            if (end < content.length()) {
                int minimumBoundary = start + maxChars / 2;
                int selected = -1;
                for (String boundary : BOUNDARIES) {
                    int location = content.lastIndexOf(boundary, end - 1);
                    if (location >= minimumBoundary) {
                        selected = Math.max(selected, location + boundary.length());
                    }
                }
                if (selected >= minimumBoundary) {
                    end = selected;
                }
            }
            String chunk = content.substring(start, end).strip();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end >= content.length()) {
                break;
            }
            start = Math.max(start + 1, end - overlapChars);
        }
        return chunks;
    }

    private String normalize(String value) {
        String[] lines = value.replace("\r\n", "\n").split("\n", -1);
        List<String> normalized = new ArrayList<>();
        for (String line : lines) {
            String current = INLINE_SPACE.matcher(line).replaceAll(" ").strip();
            if (!current.isBlank() || (!normalized.isEmpty()
                    && !normalized.getLast().isBlank())) {
                normalized.add(current);
            }
        }
        return String.join("\n", normalized).strip();
    }
}
