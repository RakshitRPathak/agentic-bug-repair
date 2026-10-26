package dev.rakshit.repair.graph;

/**
 * One vertex of the code property graph. {@code id} is stable and readable: {@code pkg.Class},
 * {@code pkg.Class#method(int,String)} or {@code pkg.Class#field}. Lines are 1-based and inclusive.
 */
public record CodeNode(
        String id,
        NodeKind kind,
        String name,
        String className,
        String file,
        int startLine,
        int endLine,
        boolean isTest,
        String source) {

    /** Short label for UIs: {@code Class#method}. */
    public String label() {
        String simple = className.substring(className.lastIndexOf('.') + 1);
        return kind == NodeKind.CLASS ? simple : simple + "#" + name;
    }
}
