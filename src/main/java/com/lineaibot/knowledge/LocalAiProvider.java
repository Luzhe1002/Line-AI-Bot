package com.lineaibot.knowledge;

import com.lineaibot.config.AppProperties;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LocalAiProvider implements AiProvider {

    private static final Pattern LATIN_WORD = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u3400-\\u9fff]");

    private final AppProperties properties;

    public LocalAiProvider(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public String name() {
        return "local";
    }

    @Override
    public String embeddingModel() {
        return "local-hash-embedding-java-v1";
    }

    @Override
    public int embeddingDimensions() {
        return properties.getAi().getEmbeddingDimensions();
    }

    @Override
    public String generationModel() {
        return "local-extractive-v1";
    }

    @Override
    public List<double[]> embedTexts(List<String> texts) {
        return texts.stream().map(this::embedding).toList();
    }

    @Override
    public GeneratedText generateAnswer(
            String question,
            List<GroundingContext> contexts,
            String tenantName,
            String safetyIdentifier) {
        if (contexts.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot generate a grounded answer without context");
        }
        return new GeneratedText(
                contexts.getFirst().content().strip(), name(), generationModel(), null);
    }

    private double[] embedding(String value) {
        double[] vector = new double[embeddingDimensions()];
        for (WeightedToken token : tokens(value)) {
            long hash = tokenHash(token.value());
            int index = Math.floorMod(hash, embeddingDimensions());
            vector[index] += token.weight();
        }
        double magnitude = 0;
        for (double component : vector) {
            magnitude += component * component;
        }
        magnitude = Math.sqrt(magnitude);
        if (magnitude > 0) {
            for (int index = 0; index < vector.length; index++) {
                vector[index] /= magnitude;
            }
        }
        return vector;
    }

    private List<WeightedToken> tokens(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        List<WeightedToken> tokens = new ArrayList<>();
        var wordMatcher = LATIN_WORD.matcher(normalized);
        while (wordMatcher.find()) {
            String word = wordMatcher.group();
            tokens.add(new WeightedToken("word:" + word, 2.0));
            for (int index = 0; index + 3 <= word.length(); index++) {
                tokens.add(new WeightedToken(
                        "latin3:" + word.substring(index, index + 3), 0.5));
            }
        }
        List<String> chars = new ArrayList<>();
        var cjkMatcher = CJK_CHAR.matcher(normalized);
        while (cjkMatcher.find()) {
            chars.add(cjkMatcher.group());
            tokens.add(new WeightedToken("cjk1:" + cjkMatcher.group(), 0.5));
        }
        for (int index = 0; index + 1 < chars.size(); index++) {
            tokens.add(new WeightedToken(
                    "cjk2:" + chars.get(index) + chars.get(index + 1), 2.0));
        }
        return tokens;
    }

    private long tokenHash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, 8).getLong();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record WeightedToken(String value, double weight) {}
}
