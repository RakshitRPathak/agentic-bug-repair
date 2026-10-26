package dev.rakshit.repair.retrieval;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.graph.NodeKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** BM25 + vector search over the methods of a code graph, merged with reciprocal rank fusion. */
public final class HybridRetriever {
    private static final int POOL = 50;

    public record Hit(String id, double score, int bm25Rank, int vectorRank) {}

    private final Bm25Index bm25 = new Bm25Index();
    private final HashedVectorizer.Index vectors;

    public HybridRetriever(CodeGraph graph, Embedder embedder) {
        this.vectors = new HashedVectorizer.Index(embedder);
        for (CodeNode n : graph.nodes()) {
            if (n.kind() != NodeKind.METHOD) continue;
            List<String> doc = new ArrayList<>();
            List<String> nameTokens = Tokenizer.tokens(n.name());
            doc.addAll(nameTokens);
            doc.addAll(nameTokens);                       // the name says more than any one line of the body
            doc.addAll(Tokenizer.tokens(n.className().substring(n.className().lastIndexOf('.') + 1)));
            doc.addAll(Tokenizer.tokens(n.source()));
            bm25.add(n.id(), doc);
            vectors.add(n.id(), doc);
        }
    }

    public HybridRetriever(CodeGraph graph) {
        this(graph, new HashedVectorizer(1024));
    }

    public List<Hit> search(String query, int k) {
        List<String> q = Tokenizer.tokens(query);
        List<String> byBm25 = bm25.search(q, POOL).stream().map(Bm25Index.Hit::id).collect(Collectors.toList());
        List<String> byVector = vectors.search(q, POOL).stream().map(Bm25Index.Hit::id).collect(Collectors.toList());
        Map<String, Double> fused = ReciprocalRankFusion.fuse(List.of(byBm25, byVector));
        List<Hit> hits = new ArrayList<>();
        for (Map.Entry<String, Double> e : fused.entrySet()) {
            if (hits.size() >= k) break;
            hits.add(new Hit(e.getKey(), e.getValue(), byBm25.indexOf(e.getKey()), byVector.indexOf(e.getKey())));
        }
        return hits;
    }

    /** Every retrieved method with its fused score scaled so the best is 1.0. */
    public Map<String, Double> normalizedScores(String query) {
        List<Hit> hits = search(query, POOL);
        Map<String, Double> r = new LinkedHashMap<>();
        if (hits.isEmpty()) return r;
        double max = hits.get(0).score();
        for (Hit h : hits) r.put(h.id(), h.score() / max);
        return r;
    }
}
