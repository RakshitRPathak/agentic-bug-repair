package dev.rakshit.repair.retrieval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reciprocal rank fusion: a document's fused score is the sum over rankings of 1 / (60 + rank). It needs no score
 * calibration between BM25 (unbounded) and cosine similarity (0..1), which is why it is the usual way to merge them.
 */
public final class ReciprocalRankFusion {
    public static final int K = 60;

    private ReciprocalRankFusion() {}

    public static Map<String, Double> fuse(List<List<String>> rankings) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (List<String> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) scores.merge(ranking.get(i), 1.0 / (K + i + 1), Double::sum);
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> a, LinkedHashMap::new));
    }
}
