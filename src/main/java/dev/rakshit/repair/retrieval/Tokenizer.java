package dev.rakshit.repair.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns source code or an error message into search tokens: identifiers are split on camelCase and underscores
 * ({@code getUserName} gives get, user, name), lower-cased, and Java keywords and filler words are dropped. A split
 * identifier is also kept whole, so an exact name still outranks its pieces.
 */
public final class Tokenizer {
    private static final Pattern WORD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|\\d+");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|_+");
    private static final Set<String> STOP = Set.of(
            "public", "private", "protected", "static", "final", "return", "void", "new", "this", "if", "else", "for",
            "while", "do", "class", "interface", "extends", "implements", "import", "package", "throws", "throw", "try",
            "catch", "finally", "null", "true", "false", "int", "long", "double", "boolean", "string", "char", "byte",
            "the", "at", "of", "to", "in", "is", "and", "or", "an", "be", "by", "on", "with", "java", "lang", "util",
            "break", "continue", "switch", "case", "default", "instanceof", "abstract", "override", "var");

    private Tokenizer() {}

    public static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String word = m.group();
            String[] parts = CAMEL.split(word);
            int kept = 0;
            for (String p : parts) {
                String t = p.toLowerCase(Locale.ROOT);
                if (t.isEmpty() || STOP.contains(t) || (t.length() < 2 && !Character.isDigit(t.charAt(0)))) continue;
                out.add(t);
                kept++;
            }
            String whole = word.toLowerCase(Locale.ROOT).replace("_", "");
            if (parts.length > 1 && kept > 0 && !STOP.contains(whole)) out.add(whole);
        }
        return out;
    }
}
