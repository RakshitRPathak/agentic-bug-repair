package dev.rakshit.repair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Builds small throwaway Java projects (sources + build.sh) for tests. */
public final class Fixtures {
    private Fixtures() {}

    public static final String STATS = """
            package demo;

            public final class Stats {
                private Stats() {}

                public static int max(int[] a) {
                    int m = a[0];
                    for (int i = 1; i < a.length; i++) {
                        if (a[i] > m) m = a[i];
                    }
                    return m;
                }

                public static double mean(int[] a) {
                    long s = 0;
                    for (int x : a) s += x;
                    return (double) s / a.length;
                }

                public static int countAbove(int[] a, int t) {
                    int c = 0;
                    for (int x : a) {
                        if (x > t) c++;
                    }
                    return c;
                }
            }
            """;

    public static final String REPORT = """
            package demo;

            public final class Report {
                private Report() {}

                public static String summary(int[] a) {
                    return "max=" + Stats.max(a) + " mean=" + Stats.mean(a);
                }

                public static int outliers(int[] a) {
                    return Stats.countAbove(a, (int) Stats.mean(a));
                }
            }
            """;

    public static final String STATS_TESTS = """
            package demo;

            import static dev.rakshit.repair.testkit.Assert.assertEquals;

            import dev.rakshit.repair.testkit.Test;

            public class StatsTests {
                @Test
                public void maxOfSeveral() {
                    assertEquals(9, Stats.max(new int[] {3, 9, 4}));
                }

                @Test
                public void meanOfThree() {
                    assertEquals(4.0, Stats.mean(new int[] {3, 4, 5}));
                }

                @Test
                public void countAboveIsStrict() {
                    assertEquals(1, Stats.countAbove(new int[] {1, 5, 9}, 5));
                }

                @Test
                public void countAboveNone() {
                    assertEquals(0, Stats.countAbove(new int[] {1, 2}, 5));
                }
            }
            """;

    public static final String REPORT_TESTS = """
            package demo;

            import static dev.rakshit.repair.testkit.Assert.assertEquals;

            import dev.rakshit.repair.testkit.Test;

            public class ReportTests {
                @Test
                public void summaryText() {
                    assertEquals("max=9 mean=4.666666666666667", Report.summary(new int[] {4, 9, 1}));
                }

                @Test
                public void outliersCountsValuesAboveTheMean() {
                    assertEquals(2, Report.outliers(new int[] {1, 2, 9, 10}));
                }
            }
            """;

    /** The demo project: Stats + Report with their tests. Pass replacements to plant a bug. */
    public static Path demo(Path dir, Map<String, String> sourceOverrides) throws IOException {
        Map<String, String> files = new java.util.LinkedHashMap<>();
        files.put("src/main/java/demo/Stats.java", STATS);
        files.put("src/main/java/demo/Report.java", REPORT);
        files.put("src/test/java/demo/StatsTests.java", STATS_TESTS);
        files.put("src/test/java/demo/ReportTests.java", REPORT_TESTS);
        files.putAll(sourceOverrides);
        return project(dir, files, "demo.StatsTests demo.ReportTests");
    }

    public static Path project(Path dir, Map<String, String> files, String testClasses) throws IOException {
        Files.createDirectories(dir);
        Path kit = Path.of("src/test/java/dev/rakshit/repair/testkit");
        for (String f : new String[] {"Assert.java", "Runner.java", "Test.java"}) {
            Path target = dir.resolve("src/test/java/dev/rakshit/repair/testkit/" + f);
            Files.createDirectories(target.getParent());
            Files.copy(kit.resolve(f), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        for (Map.Entry<String, String> e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
        }
        Files.writeString(dir.resolve("build.sh"), """
                #!/usr/bin/env bash
                set -euo pipefail
                cd "$(dirname "$0")"
                rm -rf out && mkdir -p out/main out/test
                javac -Xlint:all -d out/main $(find src/main -name '*.java')
                javac -Xlint:all -cp out/main -d out/test $(find src/test -name '*.java')
                java -cp out/main:out/test dev.rakshit.repair.testkit.Runner %s
                """.formatted(testClasses), StandardCharsets.UTF_8);
        return dir;
    }

    public static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String replace(String text, String from, String to) {
        if (!text.contains(from)) throw new IllegalArgumentException("fixture text does not contain: " + from);
        return text.replace(from, to);
    }
}
