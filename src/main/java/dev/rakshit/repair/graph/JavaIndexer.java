package dev.rakshit.repair.graph;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Builds a {@link CodeGraph} from a Java repository using the compiler that ships with the JDK
 * ({@code com.sun.source}), so no parser library is needed. The sources are parsed <em>and attributed</em>, which means
 * every method call is resolved to the exact method it invokes (overloads, inherited methods, static imports), not
 * guessed from its name. Calls through an interface or an overridden method also get an edge to every project
 * implementation (class hierarchy analysis), because any of them might run.
 */
public final class JavaIndexer {
    private static final Set<String> SKIP_DIRS = Set.of("out", "target", "build", ".git", "node_modules", ".gradle", ".idea");

    private JavaIndexer() {}

    public static CodeGraph index(Path repoRoot) throws IOException {
        Path root = repoRoot.toAbsolutePath().normalize();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(root)) {
            files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        Path rel = root.relativize(p);
                        for (Path part : rel) if (SKIP_DIRS.contains(part.toString())) return false;
                        return true;
                    })
                    .sorted()
                    .collect(Collectors.toList());
        }
        return index(root, files);
    }

    static CodeGraph index(Path root, List<Path> files) throws IOException {
        CodeGraph graph = new CodeGraph();
        if (files.isEmpty()) return graph;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("a JDK is required (no system Java compiler found)");
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, diags, List.of("-proc:none", "-Xlint:none"), null,
                    fm.getJavaFileObjectsFromPaths(files));
            Iterable<? extends CompilationUnitTree> units = task.parse();
            try {
                task.analyze();
            } catch (RuntimeException e) {
                graph.compileErrors().add("attribution failed: " + e);
            }
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR && graph.compileErrors().size() < 30) {
                    graph.compileErrors().add(d.getSource() == null ? d.getMessage(null)
                            : root.relativize(Path.of(d.getSource().toUri())) + ":" + d.getLineNumber() + ": " + d.getMessage(null));
                }
            }
            Collector c = new Collector(root, graph, Trees.instance(task), task.getElements(), task.getTypes());
            for (CompilationUnitTree cu : units) c.collect(cu);
            c.link();
        }
        return graph;
    }

    // ------------------------------------------------------------------------------------------------------------

    private record PendingCall(String caller, ExecutableElement target) {}

    private record PendingEdge(String from, EdgeKind kind, String to) {}

    private static final class Collector {
        final Path root;
        final CodeGraph graph;
        final Trees trees;
        final Elements elements;
        final Types types;
        final SourcePositions positions;
        final List<PendingCall> calls = new ArrayList<>();
        final List<PendingEdge> edges = new ArrayList<>();
        final Map<String, List<ExecutableElement>> methodsByName = new HashMap<>();

        Collector(Path root, CodeGraph graph, Trees trees, Elements elements, Types types) {
            this.root = root;
            this.graph = graph;
            this.trees = trees;
            this.elements = elements;
            this.types = types;
            this.positions = trees.getSourcePositions();
        }

        void collect(CompilationUnitTree cu) throws IOException {
            Path file = Path.of(cu.getSourceFile().toUri());
            String rel = root.relativize(file).toString().replace('\\', '/');
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            boolean testFile = rel.contains("/test/") || rel.startsWith("test/") || rel.matches(".*Tests?\\.java");
            List<String> importTargets = new ArrayList<>();
            for (ImportTree imp : cu.getImports()) {
                String name = imp.getQualifiedIdentifier().toString();
                if (name.endsWith(".*")) name = name.substring(0, name.length() - 2);
                importTargets.add(name);
            }
            new TreePathScanner<Void, Void>() {
                private final ArrayList<String> classStack = new ArrayList<>();
                private String currentMethod;

                @Override
                public Void visitClass(ClassTree node, Void unused) {
                    Element el = trees.getElement(getCurrentPath());
                    boolean named = el instanceof TypeElement && !node.getSimpleName().isEmpty()
                            && el.getEnclosingElement().getKind() != ElementKind.METHOD;
                    if (!named) return super.visitClass(node, unused);     // anonymous / local: its code counts for the enclosing method
                    String id = ((TypeElement) el).getQualifiedName().toString();
                    graph.addNode(new CodeNode(id, NodeKind.CLASS, node.getSimpleName().toString(), id, rel,
                            line(cu, node, true), line(cu, node, false), testFile, slice(lines, cu, node)));
                    for (String imp : importTargets) edges.add(new PendingEdge(id, EdgeKind.IMPORTS, imp));
                    Tree ext = node.getExtendsClause();
                    List<Tree> supers = new ArrayList<>(node.getImplementsClause());
                    if (ext != null) supers.add(ext);
                    for (Tree s : supers) {
                        Element se = trees.getElement(new TreePath(getCurrentPath(), s));
                        if (se instanceof TypeElement te) edges.add(new PendingEdge(id, EdgeKind.INHERITS, te.getQualifiedName().toString()));
                    }
                    classStack.add(id);
                    String savedMethod = currentMethod;
                    currentMethod = null;
                    try {
                        return super.visitClass(node, unused);
                    } finally {
                        currentMethod = savedMethod;
                        classStack.remove(classStack.size() - 1);
                    }
                }

                @Override
                public Void visitMethod(MethodTree node, Void unused) {
                    Element el = trees.getElement(getCurrentPath());
                    if (!(el instanceof ExecutableElement ee) || classStack.isEmpty()
                            || !(ee.getEnclosingElement() instanceof TypeElement owner)
                            || !owner.getQualifiedName().contentEquals(classStack.get(classStack.size() - 1))) {
                        return super.visitMethod(node, unused);            // a method of an anonymous class
                    }
                    String id = methodId(ee);
                    String name = node.getName().contentEquals("<init>") ? "<init>" : node.getName().toString();
                    boolean isTest = false;
                    for (AnnotationTree a : node.getModifiers().getAnnotations()) {
                        String t = a.getAnnotationType().toString();
                        if (t.equals("Test") || t.endsWith(".Test")) isTest = true;
                    }
                    graph.addNode(new CodeNode(id, NodeKind.METHOD, name, classStack.get(classStack.size() - 1), rel,
                            line(cu, node, true), line(cu, node, false), testFile || isTest, slice(lines, cu, node)));
                    edges.add(new PendingEdge(classStack.get(classStack.size() - 1), EdgeKind.CONTAINS, id));
                    methodsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(ee);
                    String saved = currentMethod;
                    currentMethod = id;
                    try {
                        return super.visitMethod(node, unused);
                    } finally {
                        currentMethod = saved;
                    }
                }

                @Override
                public Void visitVariable(VariableTree node, Void unused) {
                    Element el = trees.getElement(getCurrentPath());
                    if (el != null && el.getKind() == ElementKind.FIELD && !classStack.isEmpty()
                            && el.getEnclosingElement() instanceof TypeElement owner
                            && owner.getQualifiedName().contentEquals(classStack.get(classStack.size() - 1))) {
                        String cls = classStack.get(classStack.size() - 1);
                        String id = cls + "#" + node.getName();
                        graph.addNode(new CodeNode(id, NodeKind.FIELD, node.getName().toString(), cls, rel,
                                line(cu, node, true), line(cu, node, false), testFile, slice(lines, cu, node)));
                        edges.add(new PendingEdge(cls, EdgeKind.CONTAINS, id));
                    }
                    return super.visitVariable(node, unused);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                    if (currentMethod != null) {
                        Element target = trees.getElement(new TreePath(getCurrentPath(), node.getMethodSelect()));
                        if (target instanceof ExecutableElement ee) calls.add(new PendingCall(currentMethod, ee));
                    }
                    return super.visitMethodInvocation(node, unused);
                }

                @Override
                public Void visitNewClass(NewClassTree node, Void unused) {
                    if (currentMethod != null) {
                        Element target = trees.getElement(getCurrentPath());
                        if (target instanceof ExecutableElement ee) calls.add(new PendingCall(currentMethod, ee));
                    }
                    return super.visitNewClass(node, unused);
                }

                @Override
                public Void visitAssignment(AssignmentTree node, Void unused) {
                    modified(node.getVariable());
                    return super.visitAssignment(node, unused);
                }

                @Override
                public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                    modified(node.getVariable());
                    return super.visitCompoundAssignment(node, unused);
                }

                @Override
                public Void visitUnary(UnaryTree node, Void unused) {
                    switch (node.getKind()) {
                        case PREFIX_INCREMENT, PREFIX_DECREMENT, POSTFIX_INCREMENT, POSTFIX_DECREMENT -> modified(node.getExpression());
                        default -> { }
                    }
                    return super.visitUnary(node, unused);
                }

                private void modified(ExpressionTree target) {
                    if (currentMethod == null) return;
                    TreePath path = new TreePath(getCurrentPath(), target);
                    while (true) {
                        Tree t = path.getLeaf();
                        if (t instanceof ParenthesizedTree p) path = new TreePath(path, p.getExpression());
                        else if (t instanceof ArrayAccessTree a) path = new TreePath(path, a.getExpression());
                        else break;
                    }
                    Tree leaf = path.getLeaf();
                    if (!(leaf instanceof MemberSelectTree) && !(leaf instanceof com.sun.source.tree.IdentifierTree)) return;
                    Element el = trees.getElement(path);
                    if (el instanceof VariableElement ve && ve.getKind() == ElementKind.FIELD
                            && ve.getEnclosingElement() instanceof TypeElement owner) {
                        edges.add(new PendingEdge(currentMethod, EdgeKind.MODIFIES, owner.getQualifiedName() + "#" + ve.getSimpleName()));
                    }
                }
            }.scan(cu, null);
        }

        int line(CompilationUnitTree cu, Tree t, boolean start) {
            long pos = start ? positions.getStartPosition(cu, t) : positions.getEndPosition(cu, t);
            return pos < 0 ? 1 : (int) cu.getLineMap().getLineNumber(Math.max(0, start ? pos : pos - 1));
        }

        String slice(List<String> lines, CompilationUnitTree cu, Tree t) {
            int s = line(cu, t, true), e = Math.min(line(cu, t, false), lines.size());
            return String.join("\n", lines.subList(Math.max(0, s - 1), Math.max(s, e)));
        }

        static String methodId(ExecutableElement ee) {
            TypeElement owner = (TypeElement) ee.getEnclosingElement();
            StringBuilder sb = new StringBuilder(owner.getQualifiedName()).append('#');
            sb.append(ee.getKind() == ElementKind.CONSTRUCTOR ? "<init>" : ee.getSimpleName()).append('(');
            boolean first = true;
            for (VariableElement p : ee.getParameters()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(simple(p.asType().toString()));
            }
            return sb.append(')').toString();
        }

        /** Strips package qualifiers and type arguments, keeping array brackets: java.util.List&lt;String&gt;[] to List[]. */
        static String simple(String type) {
            String t = type.replaceAll("<.*>", "");
            int dot = t.lastIndexOf('.');
            return dot >= 0 ? t.substring(dot + 1) : t;
        }

        void link() {
            for (PendingEdge e : edges) {
                String to = e.to();
                if (e.kind() == EdgeKind.IMPORTS) {
                    while (!graph.contains(to) && to.contains(".")) to = to.substring(0, to.lastIndexOf('.'));   // static import of a member
                }
                graph.addEdge(e.from(), e.kind(), to);
            }
            for (PendingCall c : calls) {
                graph.addEdge(c.caller(), EdgeKind.CALLS, methodId(c.target()));
                // class hierarchy analysis: any project override of the called method might be what runs
                List<ExecutableElement> sameName = methodsByName.get(c.target().getKind() == ElementKind.CONSTRUCTOR
                        ? "<init>" : c.target().getSimpleName().toString());
                if (sameName == null || c.target().getKind() == ElementKind.CONSTRUCTOR) continue;
                for (ExecutableElement cand : sameName) {
                    if (cand.equals(c.target()) || cand.getParameters().size() != c.target().getParameters().size()) continue;
                    if (!(cand.getEnclosingElement() instanceof TypeElement candOwner)) continue;
                    if (elements.overrides(cand, c.target(), candOwner)) graph.addEdge(c.caller(), EdgeKind.CALLS, methodId(cand));
                }
            }
        }
    }

    /** Parameter-type string used in method ids, exposed for tests and the localizer. */
    public static String simpleTypeName(TypeMirror t) {
        return Collector.simple(t.toString());
    }
}
