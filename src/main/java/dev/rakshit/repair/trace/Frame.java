package dev.rakshit.repair.trace;

/** One line of a Java stack trace: {@code at pkg.Class.method(File.java:12)}. */
public record Frame(String className, String methodName, String fileName, int line) {
    @Override
    public String toString() {
        return className + "." + methodName + "(" + fileName + ":" + line + ")";
    }
}
