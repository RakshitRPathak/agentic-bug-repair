package dev.rakshit.repair;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertFalse;
import static dev.rakshit.repair.testkit.Assert.assertNull;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.graph.CodeGraph;
import dev.rakshit.repair.graph.CodeNode;
import dev.rakshit.repair.graph.EdgeKind;
import dev.rakshit.repair.graph.JavaIndexer;
import dev.rakshit.repair.testkit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

public class JavaIndexerTests {
    private static Path repo(Map<String, String> files) throws Exception {
        Path dir = Fixtures.tempDir("idx-");
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
        return dir;
    }

    private static boolean calls(CodeGraph g, String from, String to) {
        return g.callees(from).contains(to);
    }

    @Test
    public void resolvesOverloadsByTheirRealSignature() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of("src/main/java/p/A.java", """
                package p;
                public class A {
                    void go() { f(1); f("x"); }
                    void f(int i) { }
                    void f(String s) { }
                }
                """)));
        assertTrue(calls(g, "p.A#go()", "p.A#f(int)"));
        assertTrue(calls(g, "p.A#go()", "p.A#f(String)"));
        assertEquals(2, g.callees("p.A#go()").size());
    }

    @Test
    public void callsThroughAnInterfaceReachEveryImplementation() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of(
                "src/main/java/p/Shape.java", "package p;\npublic interface Shape { double area(); }\n",
                "src/main/java/p/Sq.java", "package p;\npublic class Sq implements Shape { public double area() { return 4; } }\n",
                "src/main/java/p/Circ.java", "package p;\npublic class Circ implements Shape { public double area() { return 3; } }\n",
                "src/main/java/p/Use.java", "package p;\npublic class Use { double total(Shape s) { return s.area(); } }\n")));
        assertTrue(calls(g, "p.Use#total(Shape)", "p.Shape#area()"));
        assertTrue(calls(g, "p.Use#total(Shape)", "p.Sq#area()"), "class hierarchy analysis adds the implementation");
        assertTrue(calls(g, "p.Use#total(Shape)", "p.Circ#area()"));
    }

    @Test
    public void recordsInheritanceAndImports() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of(
                "src/main/java/p/Base.java", "package p;\npublic class Base { }\n",
                "src/main/java/p/Util.java", "package p;\npublic class Util { public static int one() { return 1; } }\n",
                "src/main/java/q/Kid.java", "package q;\nimport p.Base;\nimport static p.Util.one;\npublic class Kid extends Base { int x() { return one(); } }\n")));
        assertTrue(g.neighbours("q.Kid", EdgeKind.INHERITS, CodeGraph.Direction.OUT).contains("p.Base"));
        Set<String> imports = Set.copyOf(g.neighbours("q.Kid", EdgeKind.IMPORTS, CodeGraph.Direction.OUT));
        assertTrue(imports.contains("p.Base"), imports.toString());
        assertTrue(imports.contains("p.Util"), "a static import points at the class: " + imports);
        assertTrue(calls(g, "q.Kid#x()", "p.Util#one()"));
    }

    @Test
    public void recordsFieldWritesButNotReads() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of("src/main/java/p/C.java", """
                package p;
                public class C {
                    int count; int[] cells = new int[3]; int other;
                    void bump() { count++; }
                    void set(int v) { this.other = v; }
                    void cell() { cells[1] = 5; }
                    void compound() { count += 2; }
                    int read() { return count + other; }
                }
                """)));
        Set<String> bump = Set.copyOf(g.neighbours("p.C#bump()", EdgeKind.MODIFIES, CodeGraph.Direction.OUT));
        assertEquals(Set.of("p.C#count"), bump);
        assertEquals(Set.of("p.C#other"), Set.copyOf(g.neighbours("p.C#set(int)", EdgeKind.MODIFIES, CodeGraph.Direction.OUT)));
        assertEquals(Set.of("p.C#cells"), Set.copyOf(g.neighbours("p.C#cell()", EdgeKind.MODIFIES, CodeGraph.Direction.OUT)));
        assertEquals(Set.of("p.C#count"), Set.copyOf(g.neighbours("p.C#compound()", EdgeKind.MODIFIES, CodeGraph.Direction.OUT)));
        assertTrue(g.neighbours("p.C#read()", EdgeKind.MODIFIES, CodeGraph.Direction.OUT).isEmpty());
        assertEquals(2, g.neighbours("p.C#count", EdgeKind.MODIFIES, CodeGraph.Direction.IN).size());   // bump and compound
    }

    @Test
    public void constructorsLambdasAndAnonymousClassesAreAttributedToTheEnclosingMethod() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of("src/main/java/p/D.java", """
                package p;
                import java.util.List;
                public class D {
                    D(int n) { }
                    static void helper() { }
                    static void other() { }
                    void run(List<Integer> xs) {
                        xs.forEach(x -> helper());
                        Runnable r = new Runnable() { public void run() { other(); } };
                        new D(3);
                    }
                }
                """)));
        assertTrue(calls(g, "p.D#run(List)", "p.D#helper()"), "call inside a lambda");
        assertTrue(calls(g, "p.D#run(List)", "p.D#other()"), "call inside an anonymous class");
        assertTrue(calls(g, "p.D#run(List)", "p.D#<init>(int)"), "constructor call");
    }

    @Test
    public void nestedClassesGetDottedIdsAndOwnMembers() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of("src/main/java/p/Outer.java", """
                package p;
                public class Outer {
                    static class Inner { int v() { return 1; } }
                    int use() { return new Inner().v(); }
                }
                """)));
        assertTrue(g.contains("p.Outer.Inner"));
        assertTrue(g.contains("p.Outer.Inner#v()"));
        assertTrue(calls(g, "p.Outer#use()", "p.Outer.Inner#v()"));
        assertEquals("p.Outer.Inner", g.node("p.Outer.Inner#v()").className());
    }

    @Test
    public void methodAtFindsTheInnermostMethodByLine() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of("src/main/java/p/E.java",
                "package p;\npublic class E {\n  int a() {\n    return 1;\n  }\n\n  int b() {\n    return 2;\n  }\n}\n")));
        assertEquals("p.E#a()", g.methodAt("src/main/java/p/E.java", 4).id());
        assertEquals("p.E#b()", g.methodAt("src/main/java/p/E.java", 8).id());
        assertNull(g.methodAt("src/main/java/p/E.java", 6));
        CodeNode b = g.node("p.E#b()");
        assertEquals(7, b.startLine());
        assertEquals(9, b.endLine());
        assertTrue(b.source().contains("return 2"));
    }

    @Test
    public void marksTestsByLocationAndAnnotation() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of(
                "src/main/java/p/Main.java", "package p;\npublic class Main { public int f() { return 1; } }\n",
                "src/test/java/p/MainTests.java", "package p;\npublic class MainTests { @interface Test {} @Test public void ok() { new Main().f(); } void helper() { } }\n")));
        assertFalse(g.node("p.Main#f()").isTest());
        assertTrue(g.node("p.MainTests#ok()").isTest());
        assertTrue(g.node("p.MainTests#helper()").isTest(), "everything under src/test counts as test code");
    }

    @Test
    public void blastRadiusFollowsCallersAndNamesTheTestsThatExerciseIt() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of(
                "src/main/java/p/L.java", "package p;\npublic class L { static int low() { return 1; } static int mid() { return low(); } static int top() { return mid(); } static int unrelated() { return 0; } }\n",
                "src/test/java/p/LTests.java", "package p;\npublic class LTests { void topWorks() { L.top(); } void unrelatedWorks() { L.unrelated(); } }\n")));
        CodeGraph.BlastRadius b = g.blastRadius("p.L#low()", 4);
        assertEquals(Set.of("p.L#mid()", "p.L#top()", "p.LTests#topWorks()"), b.upstream().keySet());
        assertEquals(java.util.List.of("p.LTests#topWorks()"), b.tests());
        assertEquals(1, b.upstream().get("p.L#mid()"));
        assertEquals(3, b.upstream().get("p.LTests#topWorks()"));
        assertTrue(g.blastRadius("p.L#top()", 4).downstream().containsKey("p.L#low()"));
    }

    @Test
    public void traversalRespectsTheHopLimitAndEdgeKinds() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of("src/main/java/p/F.java",
                "package p;\npublic class F { static void a() { b(); } static void b() { c(); } static void c() { d(); } static void d() { } }\n")));
        Map<String, Integer> two = g.traverse("p.F#a()", 2, Set.of(EdgeKind.CALLS), CodeGraph.Direction.OUT);
        assertEquals(Set.of("p.F#a()", "p.F#b()", "p.F#c()"), two.keySet());
        assertEquals(2, two.get("p.F#c()"));
        assertTrue(g.traverse("p.F#a()", 5, Set.of(EdgeKind.IMPORTS), CodeGraph.Direction.OUT).size() == 1);
    }

    @Test
    public void aRepositoryThatDoesNotCompileStillYieldsAGraphAndReportsTheErrors() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of("src/main/java/p/Broken.java",
                "package p;\npublic class Broken { int ok() { return 1; } int bad() { return missing(); } }\n")));
        assertTrue(g.contains("p.Broken#ok()"));
        assertFalse(g.compileErrors().isEmpty());
        assertTrue(g.compileErrors().get(0).contains("Broken.java"), g.compileErrors().toString());
    }

    @Test
    public void skipsBuildOutputDirectories() throws Exception {
        CodeGraph g = JavaIndexer.index(repo(Map.of(
                "src/main/java/p/Real.java", "package p;\npublic class Real { }\n",
                "out/main/p/Stale.java", "package p;\npublic class Stale { }\n")));
        assertTrue(g.contains("p.Real"));
        assertFalse(g.contains("p.Stale"));
    }
}
