package dev.rakshit.repair.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dense-vector search without a model download: tokens and adjacent token pairs are hashed into a fixed number of
 * signed buckets ("the hashing trick"), weighted by 1 + log(tf), and length-normalised, so cosine similarity is a dot
 * product. It captures shared vocabulary and word order that BM25's bag of words ignores. {@link Embedder} is the seam
 * for swapping in a neural embedding model.
 */
public final class HashedVectorizer implements Embedder {
    private final int dim;

    public HashedVectorizer(int dim) {
        if (dim < 16) throw new IllegalArgumentException("dim too small");
        this.dim = dim;
    }

    @Override
    public double[] embed(List<String> tokens) {
        double[] v = new double[dim];
        Map<String, Integer> tf = new LinkedHashMap<>();
        for (int i = 0; i < tokens.size(); i++) {
            tf.merge(tokens.get(i), 1, Integer::sum);
            if (i + 1 < tokens.size()) tf.merge(tokens.get(i) + " " + tokens.get(i + 1), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : tf.entrySet()) {
            long h = fnv1a(e.getKey());
            int bucket = (int) ((h >>> 1) % dim);
            double sign = (h & 1) == 0 ? 1.0 : -1.0;
            v[bucket] += sign * (1.0 + Math.log(e.getValue()));
        }
        double norm = 0;
        for (double x : v) norm += x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) for (int i = 0; i < dim; i++) v[i] /= norm;
        return v;
    }

    static long fnv1a(String s) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /** Brute-force nearest-neighbour index over embeddings. */
    public static final class Index {
        private final Embedder embedder;
        private final List<String> ids = new ArrayList<>();
        private final List<double[]> vectors = new ArrayList<>();

        public Index(Embedder embedder) {
            this.embedder = embedder;
        }

        public void add(String id, List<String> tokens) {
            ids.add(id);
            vectors.add(embedder.embed(tokens));
        }

        public List<Bm25Index.Hit> search(List<String> query, int k) {
            double[] q = embedder.embed(query);
            List<Bm25Index.Hit> hits = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                double dot = 0;
                double[] d = vectors.get(i);
                for (int j = 0; j < q.length; j++) dot += q[j] * d[j];
                if (dot > 0) hits.add(new Bm25Index.Hit(ids.get(i), dot));
            }
            hits.sort(Comparator.comparingDouble(Bm25Index.Hit::score).reversed().thenComparing(Bm25Index.Hit::id));
            return hits.size() > k ? hits.subList(0, k) : hits;
        }
    }
}
