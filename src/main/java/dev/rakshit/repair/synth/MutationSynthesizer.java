package dev.rakshit.repair.synth;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ParenthesizedTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.localize.Suspect;
import dev.rakshit.repair.patch.UnifiedDiff;
import dev.rakshit.repair.trace.FailureReport;
import dev.rakshit.repair.trace.Frame;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/**
 * Offline patch synthesizer: it needs no model and no network. Given the ranked suspects it enumerates small, local
 * source edits inside those methods (the mutation operators of program-repair research: flip a comparison, nudge a
 * constant, swap an arithmetic or logical operator, negate a condition, change an array index, swap two same-typed
 * variables or arguments, flip an increment, delete a statement), orders them by how often each kind of edit is the
 * real fix and how suspicious the method is, and returns the best few as unified diffs.
 *
 * <p>It is deliberately limited: it can only repair bugs that are one such edit away from correct (and, over several
 * rounds with partial fixes accepted, a few such edits). Bugs that need new code - a missing null check, a missing
 * branch - are out of its reach; that is what {@link LlmSynthesizer} is for. The benchmark reports exactly which is which.
 */
public final class MutationSynthesizer implements PatchSynthesizer {
    public static final int MAX_SUSPECTS = 3;

    private static final Map<String, Double> PRIOR = Map.ofEntries(
            Map.entry("RELATIONAL", 1.0), Map.entry("CONSTANT", 0.9), Map.entry("ARITHMETIC", 0.85),
            Map.entry("LOGICAL", 0.8), Map.entry("INDEX", 0.75), Map.entry("NEGATE_CONDITION", 0.7),
            Map.entry("REMOVE_NOT", 0.7), Map.entry("INCREMENT", 0.7), Map.entry("COMPOUND_ASSIGN", 0.7),
            Map.entry("BOOLEAN", 0.5), Map.entry("ARG_SWAP", 0.5), Map.entry("VARIABLE_SWAP", 0.45),
            Map.entry("DELETE_STATEMENT", 0.4));

    private static final Pattern EXPECTED_WAS = Pattern.compile("expected <?(-?\\d+)>? but was <?(-?\\d+)>?");

    @Override
    public String name() {
        return "mutation (offline, deterministic)";
    }

    /** One textual edit [start, end) to replace with {@code replacement}. */
    record Mutation(String operator, int start, int end, String replacement, int line, String description) {}

    @Override
    public Proposal propose(SynthRequest req) throws IOException {
        Map<String, Candidate> best = new LinkedHashMap<>();
        Map<String, Double> priority = new LinkedHashMap<>();
        boolean offByOne = offByOneHint(req.failures());
        int used = 0;
        for (Suspect s : req.suspects()) {
            if (used >= MAX_SUSPECTS) break;
            CodeNode node = req.graph().node(s.id());
            if (node == null) continue;
            Path file = req.workspace().resolve(node.file());
            if (!Files.isRegularFile(file)) continue;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            List<Mutation> mutations = mutationsFor(text, s.id());
            if (mutations.isEmpty()) continue;
            used++;
            Set<Integer> focus = focusLines(req.failures(), node);
            for (Mutation m : mutations) {
                String key = node.file() + "@" + m.start() + ":" + m.end() + "->" + m.replacement();
                if (req.tried().contains(key) || best.containsKey(key)) continue;
                String patched = splice(text, m);
                String diff = UnifiedDiff.create(node.file(), text, patched);
                if (diff.isEmpty()) continue;
                double p = PRIOR.getOrDefault(m.operator(), 0.3) * (0.5 + s.score());
                if (focus.contains(m.line())) p += 0.5;
                if (offByOne && (m.operator().equals("CONSTANT") || m.operator().equals("RELATIONAL"))) p *= 1.2;
                best.put(key, new Candidate(key, m.operator(), m.description(), s.id(), node.file(), m.line(), diff));
                priority.put(key, p);
            }
        }
        List<Candidate> ranked = new ArrayList<>(best.values());
        ranked.sort(Comparator.comparingDouble((Candidate c) -> priority.get(c.key())).reversed().thenComparing(Candidate::key));
        if (ranked.size() > req.beam()) ranked = new ArrayList<>(ranked.subList(0, req.beam()));
        return new Proposal(ranked, Usage.NONE);
    }

    /** When failures say "expected 5 but was 4", an off-by-one edit is the likelier fix. */
    private static boolean offByOneHint(List<FailureReport> failures) {
        for (FailureReport f : failures) {
            Matcher m = EXPECTED_WAS.matcher(f.message());
            if (m.find() && Math.abs(Long.parseLong(m.group(1)) - Long.parseLong(m.group(2))) == 1) return true;
        }
        return false;
    }

    private static Set<Integer> focusLines(List<FailureReport> failures, CodeNode node) {
        Set<Integer> lines = new HashSet<>();
        String simple = node.className().substring(node.className().lastIndexOf('.') + 1);
        for (FailureReport f : failures) {
            for (Frame fr : f.frames()) {
                if (fr.fileName().equals(simple + ".java") && fr.line() >= node.startLine() && fr.line() <= node.endLine()) lines.add(fr.line());
            }
        }
        return lines;
    }

    static String splice(String text, Mutation m) {
        String patched = text.substring(0, m.start()) + m.replacement() + text.substring(m.end());
        if (m.replacement().isEmpty()) {
            // deleting a statement that sat alone on its line: remove the now-blank line too
            int ls = patched.lastIndexOf('\n', m.start() - 1) + 1;
            int le = patched.indexOf('\n', m.start());
            if (le >= 0 && patched.substring(ls, le).isBlank()) patched = patched.substring(0, ls) + patched.substring(le + 1);
        }
        return patched;
    }

    // ------------------------------------------------------------------------------------------- enumeration

    /** All mutations inside the method with the given id ({@code pkg.Class#name(Type,Type)}), as edits of {@code source}. */
    static List<Mutation> mutationsFor(String source, String methodId) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        JavaFileObjectOf file = new JavaFileObjectOf(source);
        JavacTask task = (JavacTask) compiler.getTask(null, null, d -> { }, List.of("-proc:none"), null, List.of(file));
        CompilationUnitTree cu = task.parse().iterator().next();
        SourcePositions sp = Trees.instance(task).getSourcePositions();
        MethodTree[] found = new MethodTree[1];
        String pkg = cu.getPackageName() == null ? "" : cu.getPackageName() + ".";
        new TreeScanner<Void, java.util.ArrayDeque<String>>() {
            @Override
            public Void visitClass(ClassTree node, java.util.ArrayDeque<String> stack) {
                if (node.getSimpleName().isEmpty()) return super.visitClass(node, stack);
                stack.addLast(node.getSimpleName().toString());
                super.visitClass(node, stack);
                stack.removeLast();
                return null;
            }

            @Override
            public Void visitMethod(MethodTree node, java.util.ArrayDeque<String> stack) {
                if (stack.isEmpty()) return null;
                StringBuilder id = new StringBuilder(pkg).append(String.join(".", stack)).append('#')
                        .append(node.getName().contentEquals("<init>") ? "<init>" : node.getName()).append('(');
                boolean first = true;
                for (VariableTree p : node.getParameters()) {
                    if (!first) id.append(',');
                    first = false;
                    id.append(simple(p.getType().toString()));
                }
                if (id.append(')').toString().equals(methodId) && found[0] == null) found[0] = node;
                return null;                                         // do not descend into nested methods of anonymous classes
            }
        }.scan(cu, new java.util.ArrayDeque<>());
        if (found[0] == null || found[0].getBody() == null) return List.of();
        return new Enumerator(source, cu, sp, found[0]).run();
    }

    static String simple(String type) {
        String t = type.replaceAll("<.*>", "").replace("...", "[]");
        int dot = t.lastIndexOf('.');
        return dot >= 0 ? t.substring(dot + 1) : t;
    }

    private static final class JavaFileObjectOf extends SimpleJavaFileObject {
        private final String code;

        JavaFileObjectOf(String code) {
            super(URI.create("string:///Mutated.java"), Kind.SOURCE);
            this.code = code;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return code;
        }
    }

    private static final class Enumerator extends TreeScanner<Void, Void> {
        final String src;
        final CompilationUnitTree cu;
        final SourcePositions sp;
        final MethodTree method;
        final List<Mutation> out = new ArrayList<>();
        final List<Declared> declared = new ArrayList<>();
        final Set<Tree> methodSelects = new HashSet<>();

        record Declared(String name, String type, long pos) {}

        Enumerator(String src, CompilationUnitTree cu, SourcePositions sp, MethodTree method) {
            this.src = src;
            this.cu = cu;
            this.sp = sp;
            this.method = method;
        }

        List<Mutation> run() {
            for (VariableTree p : method.getParameters()) declared.add(new Declared(p.getName().toString(), p.getType().toString(), pos(p)));
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitVariable(VariableTree node, Void v) {
                    declared.add(new Declared(node.getName().toString(), node.getType() == null ? "?" : node.getType().toString(), pos(node)));
                    return super.visitVariable(node, v);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void v) {
                    methodSelects.add(node.getMethodSelect());
                    return super.visitMethodInvocation(node, v);
                }
            }.scan(method.getBody(), null);
            scan(method.getBody(), null);
            return out;
        }

        int pos(Tree t) {
            return (int) sp.getStartPosition(cu, t);
        }

        int end(Tree t) {
            return (int) sp.getEndPosition(cu, t);
        }

        int lineOf(int offset) {
            return (int) cu.getLineMap().getLineNumber(offset);
        }

        void add(String op, int s, int e, String repl, String what) {
            if (s < 0 || e < s) return;
            out.add(new Mutation(op, s, e, repl, lineOf(s), what + " (line " + lineOf(s) + ")"));
        }

        @Override
        public Void visitBinary(BinaryTree node, Void v) {
            String op;
            String[] alternatives;
            String kindOp;
            switch (node.getKind()) {
                case LESS_THAN -> { op = "<"; alternatives = new String[] {"<=", ">"}; kindOp = "RELATIONAL"; }
                case LESS_THAN_EQUAL -> { op = "<="; alternatives = new String[] {"<", ">="}; kindOp = "RELATIONAL"; }
                case GREATER_THAN -> { op = ">"; alternatives = new String[] {">=", "<"}; kindOp = "RELATIONAL"; }
                case GREATER_THAN_EQUAL -> { op = ">="; alternatives = new String[] {">", "<="}; kindOp = "RELATIONAL"; }
                case EQUAL_TO -> { op = "=="; alternatives = new String[] {"!="}; kindOp = "RELATIONAL"; }
                case NOT_EQUAL_TO -> { op = "!="; alternatives = new String[] {"=="}; kindOp = "RELATIONAL"; }
                case PLUS -> { op = "+"; alternatives = isStringy(node) ? new String[0] : new String[] {"-"}; kindOp = "ARITHMETIC"; }
                case MINUS -> { op = "-"; alternatives = new String[] {"+"}; kindOp = "ARITHMETIC"; }
                case MULTIPLY -> { op = "*"; alternatives = new String[] {"/"}; kindOp = "ARITHMETIC"; }
                case DIVIDE -> { op = "/"; alternatives = new String[] {"*"}; kindOp = "ARITHMETIC"; }
                case CONDITIONAL_AND -> { op = "&&"; alternatives = new String[] {"||"}; kindOp = "LOGICAL"; }
                case CONDITIONAL_OR -> { op = "||"; alternatives = new String[] {"&&"}; kindOp = "LOGICAL"; }
                default -> { op = null; alternatives = new String[0]; kindOp = null; }
            }
            if (op != null) {
                int from = end(node.getLeftOperand()), to = pos(node.getRightOperand());
                int at = from >= 0 && to >= from ? src.indexOf(op, from) : -1;
                if (at >= from && at + op.length() <= to) {
                    for (String alt : alternatives) add(kindOp, at, at + op.length(), alt, "replace `" + op + "` with `" + alt + "`");
                }
            }
            return super.visitBinary(node, v);
        }

        private boolean isStringy(BinaryTree node) {
            return containsStringLiteral(node.getLeftOperand()) || containsStringLiteral(node.getRightOperand());
        }

        private boolean containsStringLiteral(Tree t) {
            boolean[] found = {false};
            new TreeScanner<Void, Void>() {
                @Override
                public Void visitLiteral(LiteralTree n, Void v) {
                    if (n.getKind() == Tree.Kind.STRING_LITERAL) found[0] = true;
                    return null;
                }
            }.scan(t, null);
            return found[0];
        }

        @Override
        public Void visitLiteral(LiteralTree node, Void v) {
            Object val = node.getValue();
            int s = pos(node), e = end(node);
            if (node.getKind() == Tree.Kind.INT_LITERAL && val instanceof Integer n && s >= 0) {
                if (n < Integer.MAX_VALUE) add("CONSTANT", s, e, String.valueOf(n + 1), "change constant " + n + " to " + (n + 1));
                if (n > 0) add("CONSTANT", s, e, String.valueOf(n - 1), "change constant " + n + " to " + (n - 1));
            } else if (node.getKind() == Tree.Kind.LONG_LITERAL && val instanceof Long n && s >= 0) {
                add("CONSTANT", s, e, (n + 1) + "L", "change constant " + n + "L to " + (n + 1) + "L");
                if (n > 0) add("CONSTANT", s, e, (n - 1) + "L", "change constant " + n + "L to " + (n - 1) + "L");
            } else if (node.getKind() == Tree.Kind.BOOLEAN_LITERAL && val instanceof Boolean b && s >= 0) {
                add("BOOLEAN", s, e, String.valueOf(!b), "change `" + b + "` to `" + !b + "`");
            }
            return super.visitLiteral(node, v);
        }

        @Override
        public Void visitUnary(UnaryTree node, Void v) {
            int s = pos(node), e = end(node);
            switch (node.getKind()) {
                case LOGICAL_COMPLEMENT -> {
                    if (src.charAt(s) == '!') add("REMOVE_NOT", s, s + 1, "", "remove the negation `!`");
                }
                case POSTFIX_INCREMENT, POSTFIX_DECREMENT -> {
                    String op = node.getKind() == Tree.Kind.POSTFIX_INCREMENT ? "++" : "--";
                    String alt = op.equals("++") ? "--" : "++";
                    if (e - 2 >= 0 && src.startsWith(op, e - 2)) add("INCREMENT", e - 2, e, alt, "replace `" + op + "` with `" + alt + "`");
                }
                case PREFIX_INCREMENT, PREFIX_DECREMENT -> {
                    String op = node.getKind() == Tree.Kind.PREFIX_INCREMENT ? "++" : "--";
                    String alt = op.equals("++") ? "--" : "++";
                    if (src.startsWith(op, s)) add("INCREMENT", s, s + 2, alt, "replace `" + op + "` with `" + alt + "`");
                }
                default -> { }
            }
            return super.visitUnary(node, v);
        }

        @Override
        public Void visitCompoundAssignment(CompoundAssignmentTree node, Void v) {
            String op = switch (node.getKind()) {
                case PLUS_ASSIGNMENT -> "+=";
                case MINUS_ASSIGNMENT -> "-=";
                case MULTIPLY_ASSIGNMENT -> "*=";
                case DIVIDE_ASSIGNMENT -> "/=";
                default -> null;
            };
            if (op != null) {
                String alt = switch (op) { case "+=" -> "-="; case "-=" -> "+="; case "*=" -> "/="; default -> "*="; };
                int from = end(node.getVariable()), to = pos(node.getExpression());
                int at = from >= 0 ? src.indexOf(op, from) : -1;
                if (at >= from && at + 2 <= to) add("COMPOUND_ASSIGN", at, at + 2, alt, "replace `" + op + "` with `" + alt + "`");
            }
            return super.visitCompoundAssignment(node, v);
        }

        @Override
        public Void visitArrayAccess(ArrayAccessTree node, Void v) {
            ExpressionTree idx = node.getIndex();
            int s = pos(idx), e = end(idx);
            if (s >= 0) {
                String text = src.substring(s, e);
                add("INDEX", s, e, "(" + text + ") - 1", "use index `(" + text + ") - 1`");
                add("INDEX", s, e, "(" + text + ") + 1", "use index `(" + text + ") + 1`");
            }
            return super.visitArrayAccess(node, v);
        }

        private void negate(ExpressionTree cond) {
            if (cond == null) return;
            ExpressionTree inner = cond instanceof ParenthesizedTree p ? p.getExpression() : cond;
            int s = pos(inner), e = end(inner);
            if (s < 0) return;
            if (inner instanceof UnaryTree u && u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT) return;   // REMOVE_NOT covers it
            add("NEGATE_CONDITION", s, e, "!(" + src.substring(s, e) + ")", "negate the condition");
        }

        @Override
        public Void visitIf(IfTree node, Void v) {
            negate(node.getCondition());
            return super.visitIf(node, v);
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree node, Void v) {
            negate(node.getCondition());
            return super.visitWhileLoop(node, v);
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree node, Void v) {
            negate(node.getCondition());
            return super.visitDoWhileLoop(node, v);
        }

        @Override
        public Void visitForLoop(ForLoopTree node, Void v) {
            negate(node.getCondition());
            return super.visitForLoop(node, v);
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree node, Void v) {
            negate(node.getCondition());
            return super.visitConditionalExpression(node, v);
        }

        @Override
        public Void visitMethodInvocation(MethodInvocationTree node, Void v) {
            List<? extends ExpressionTree> args = node.getArguments();
            for (int i = 0; i + 1 < args.size(); i++) {
                ExpressionTree a = args.get(i), b = args.get(i + 1);
                int as = pos(a), ae = end(a), bs = pos(b), be = end(b);
                if (as < 0 || be < 0) continue;
                String ta = src.substring(as, ae), tb = src.substring(bs, be);
                if (ta.equals(tb)) continue;
                add("ARG_SWAP", as, be, tb + src.substring(ae, bs) + ta, "swap arguments `" + ta + "` and `" + tb + "`");
            }
            return super.visitMethodInvocation(node, v);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, Void v) {
            if (methodSelects.contains(node)) return null;
            String name = node.getName().toString();
            long at = pos(node);
            Declared self = null;
            for (Declared d : declared) if (d.name().equals(name) && d.pos() < at) self = d;
            if (self == null) return null;
            int swaps = 0;
            for (Declared other : declared) {
                if (other.name().equals(name) || !other.type().equals(self.type()) || other.pos() >= at) continue;
                if (swaps++ >= 3) break;
                add("VARIABLE_SWAP", (int) at, end(node), other.name(), "use `" + other.name() + "` instead of `" + name + "`");
            }
            return super.visitIdentifier(node, v);
        }

        @Override
        public Void visitExpressionStatement(ExpressionStatementTree node, Void v) {
            Tree.Kind k = node.getExpression().getKind();
            boolean sideEffect = k == Tree.Kind.ASSIGNMENT || k == Tree.Kind.POSTFIX_INCREMENT || k == Tree.Kind.POSTFIX_DECREMENT
                    || k == Tree.Kind.PREFIX_INCREMENT || k == Tree.Kind.PREFIX_DECREMENT
                    || k.name().endsWith("_ASSIGNMENT");
            if (sideEffect) {
                int s = pos(node), e = end(node);
                add("DELETE_STATEMENT", s, e, "", "delete the statement `" + src.substring(s, e).trim() + "`");
            }
            return super.visitExpressionStatement(node, v);
        }
    }
}
