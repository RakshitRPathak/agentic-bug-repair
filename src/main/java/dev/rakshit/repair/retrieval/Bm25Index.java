package dev.rakshit.repair.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Okapi BM25 over an in-memory inverted index (k1 = 1.2, b = 0.75). */
public final class Bm25Index {
    public record Hit(String id, double score) {}

    private static final double K1 = 1.2, B = 0.75;
    private final Map<String, Map<String, Integer>> postings = new HashMap<>();   // term -> doc -> term frequency
    private final Map<String, Integer> docLength = new HashMap<>();
    private long totalLength;

    public void add(String id, List<String> tokens) {
        if (docLength.containsKey(id)) throw new IllegalArgumentException("duplicate document " + id);
        docLength.put(id, tokens.size());
        totalLength += tokens.size();
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            postings.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(id, e.getValue());
        }
    }

    public int size() {
        return docLength.size();
    }

    public List<Hit> search(List<String> query, int k) {
        int n = docLength.size();
        if (n == 0) return List.of();
        double avgLen = Math.max(1.0, (double) totalLength / n);
        Map<String, Double> scores = new HashMap<>();
        for (String term : new java.util.LinkedHashSet<>(query)) {
            Map<String, Integer> docs = postings.get(term);
            if (docs == null) continue;
            double idf = Math.log(1.0 + (n - docs.size() + 0.5) / (docs.size() + 0.5));
            for (Map.Entry<String, Integer> e : docs.entrySet()) {
                double tf = e.getValue();
                double len = docLength.get(e.getKey());
                double s = idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * len / avgLen));
                scores.merge(e.getKey(), s, Double::sum);
            }
        }
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> e : scores.entrySet()) hits.add(new Hit(e.getKey(), e.getValue()));
        hits.sort(Comparator.comparingDouble(Hit::score).reversed().thenComparing(Hit::id));
        return hits.size() > k ? hits.subList(0, k) : hits;
    }
}
