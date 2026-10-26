package dev.rakshit.repair.retrieval;

import java.util.List;

/** Maps tokens to a unit-length vector. Implement this to plug in a neural embedding model. */
public interface Embedder {
    double[] embed(List<String> tokens);
}
