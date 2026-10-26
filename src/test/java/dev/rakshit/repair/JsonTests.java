package dev.rakshit.repair;

import static dev.rakshit.repair.testkit.Assert.assertEquals;
import static dev.rakshit.repair.testkit.Assert.assertNull;
import static dev.rakshit.repair.testkit.Assert.assertThrows;
import static dev.rakshit.repair.testkit.Assert.assertTrue;

import dev.rakshit.repair.testkit.Test;
import dev.rakshit.repair.util.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class JsonTests {
    @Test
    public void roundTripsNestedValues() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "a \"quoted\" \\ name\nwith\ttabs");
        m.put("n", 42L);
        m.put("pi", 3.5);
        m.put("ok", true);
        m.put("none", null);
        m.put("list", List.of(1L, "two", List.of(), Map.of("k", "v")));
        assertEquals(m.toString(), Json.parseObject(Json.write(m)).toString());
    }

    @Test
    public void escapesControlCharactersAndUnicode() {
        String s = "bell\u0007 snow\u2603 emoji\uD83D\uDE00";
        String json = Json.write(s);
        assertTrue(json.contains("\\u0007"), "control char escaped: " + json);
        assertEquals(s, Json.parse(json));
        assertEquals("A", Json.parse("\"\\u0041\""));
    }

    @Test
    public void numbersKeepTheirKind() {
        assertEquals(12L, Json.parse("12"));
        assertEquals(-7L, Json.parse("-7"));
        assertEquals(1.5e3, Json.parse("1.5e3"));
        assertEquals("1.0", String.valueOf(Json.parse("1.0")));
    }

    @Test
    public void nonFiniteNumbersBecomeNullNotInvalidJson() {
        assertEquals("null", Json.write(Double.NaN));
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY));
    }

    @Test
    public void rejectsMalformedInput() {
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "tru", "\"unterminated", "1 2", "{'a':1}", "[1 2]", "\"\\x\""}) {
            assertThrows(Json.JsonException.class, () -> Json.parse(bad));
        }
    }

    @Test
    public void refusesAbsurdNesting() {
        assertThrows(Json.JsonException.class, () -> Json.parse("[".repeat(5000) + "]".repeat(5000)));
    }

    @Test
    public void pathNavigatesAndReturnsNullWhenMissing() {
        Object doc = Json.parse("{\"a\":{\"b\":[10,{\"c\":\"deep\"}]}}");
        assertEquals("deep", Json.str(doc, "a", "b", 1, "c"));
        assertNull(Json.path(doc, "a", "x"));
        assertNull(Json.path(doc, "a", "b", 9));
        assertNull(Json.str(doc, "a", "b", 0));
    }

    @Test
    public void randomDocumentsSurviveARoundTrip() {
        Random r = new Random(11);
        for (int i = 0; i < 200; i++) {
            Object v = random(r, 0);
            assertEquals(Json.write(v), Json.write(Json.parse(Json.write(v))));
        }
    }

    private static Object random(Random r, int depth) {
        int k = r.nextInt(depth > 3 ? 4 : 6);
        switch (k) {
            case 0:
                return (long) r.nextInt(1000) - 500;
            case 1: {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < r.nextInt(8); i++) sb.append((char) (r.nextInt(120) + 1));
                return sb.toString();
            }
            case 2:
                return r.nextBoolean();
            case 3:
                return null;
            case 4: {
                List<Object> l = new ArrayList<>();
                for (int i = 0; i < r.nextInt(4); i++) l.add(random(r, depth + 1));
                return l;
            }
            default: {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = 0; i < r.nextInt(4); i++) m.put("k" + i, random(r, depth + 1));
                return m;
            }
        }
    }
}
