package dev.rakshit.repair;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertInRange;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.retrieval.Bm25Index;
import dev.rakshit.repair.retrieval.HashedVectorizer;
import dev.rakshit.repair.retrieval.HybridRetriever;
import dev.rakshit.repair.retrieval.ReciprocalRankFusion;
import dev.rakshit.repair.retrieval.Tokenizer;
import dev.rakshit.repair.testkit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public class RetrievalTests {
    @Test
    public void tokenizerSplitsCamelCaseAndKeepsTheWholeIdentifier() {
        List<String> t = Tokenizer.tokens("getUserName HTTPServer parse_json_value");
        assertTrue(t.containsAll(List.of("get", "user", "name", "getusername", "http", "server", "parse", "json", "value")), t.toString());
    }

    @Test
    public void tokenizerDropsKeywordsAndSingleLetters() {
        List<String> t = Tokenizer.tokens("public static int x = new Foo();");
        assertEquals(List.of("foo"), t);
    }

    @Test
    public void bm25RanksTheDocumentWithTheRareMatchingTermFirst() {
        Bm25Index idx = new Bm25Index();
        idx.add("a", List.of("cache", "evict", "lru", "node"));
        idx.add("b", List.of("cache", "put", "get", "node"));
        idx.add("c", List.of("rate", "limit", "token", "node"));
        List<Bm25Index.Hit> hits = idx.search(List.of("evict", "node"), 3);
        assertEquals("a", hits.get(0).id());
        assertTrue(hits.get(0).score() > hits.get(1).score());
        assertEquals(0, idx.search(List.of("unseen"), 3).size());
    }

    @Test
    public void bm25PenalisesVeryLongDocuments() {
        Bm25Index idx = new Bm25Index();
        List<String> longDoc = new java.util.ArrayList<>(List.of("target"));
        for (int i = 0; i < 200; i++) longDoc.add("filler" + i);
        idx.add("long", longDoc);
        idx.add("short", List.of("target", "word"));
        assertEquals("short", idx.search(List.of("target"), 2).get(0).id());
    }

    @Test
    public void reciprocalRankFusionMatchesTheFormula() {
        Map<String, Double> f = ReciprocalRankFusion.fuse(List.of(List.of("a", "b", "c"), List.of("b", "a", "d")));
        assertInRange(f.get("a"), 1.0 / 61 + 1.0 / 62 - 1e-12, 1.0 / 61 + 1.0 / 62 + 1e-12, "a");
        assertInRange(f.get("b"), 1.0 / 62 + 1.0 / 61 - 1e-12, 1.0 / 62 + 1.0 / 61 + 1e-12, "b");
        assertInRange(f.get("c"), 1.0 / 63 - 1e-12, 1.0 / 63 + 1e-12, "c");
        assertEquals("a", f.keySet().iterator().next());          // tie between a and b broken by id
        assertEquals(4, f.size());
    }

    @Test
    public void hashedVectorsAreUnitLengthAndSimilarTextIsCloser() {
        HashedVectorizer v = new HashedVectorizer(512);
        double[] a = v.embed(List.of("binary", "search", "lower", "bound")), b = v.embed(List.of("binary", "search", "upper", "bound")),
                c = v.embed(List.of("rate", "limiter", "token", "bucket"));
        assertInRange(dot(a, a), 0.999, 1.001, "unit length");
        assertTrue(dot(a, b) > dot(a, c) + 0.2, dot(a, b) + " vs " + dot(a, c));
        assertEquals(0.0, dot(v.embed(List.of()), a));
    }

    @Test
    public void hybridSearchFindsTheMethodNamedInAnErrorMessage() throws Exception {
        Path dir = Fixtures.tempDir("hyb-");
        Files.createDirectories(dir.resolve("src/main/java/p"));
        Files.writeString(dir.resolve("src/main/java/p/Q.java"), """
                package p;
                public class Q {
                    int lowerBound(int[] a, int x) { int lo = 0, hi = a.length; while (lo < hi) { int mid = (lo + hi) / 2; if (a[mid] >= x) hi = mid; else lo = mid + 1; } return lo; }
                    int checksum(byte[] data) { int s = 0; for (byte b : data) s = s * 31 + b; return s; }
                    String greet(String name) { return "hello " + name; }
                }
                """);
        CodeGraph g = JavaIndexer.index(dir);
        HybridRetriever r = new HybridRetriever(g);
        assertEquals("p.Q#lowerBound(int[],int)", r.search("expected <2> but was <3> in lowerBound with a sorted array", 3).get(0).id());
        assertEquals("p.Q#greet(String)", r.search("greeting text hello name", 3).get(0).id());
        Map<String, Double> scores = r.normalizedScores("checksum of data bytes");
        assertEquals(1.0, scores.get("p.Q#checksum(byte[])"));
    }

    private static double dot(double[] x, double[] y) {
        double s = 0;
        for (int i = 0; i < x.length; i++) s += x[i] * y[i];
        return s;
    }
}
