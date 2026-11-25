package com.stario.launcher.sheet.drawer.search;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class JaroWinklerDistanceTest {

    @Test
    public void getScore_nullInputs_throwIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> JaroWinklerDistance.getScore(null, null));
        assertThrows(IllegalArgumentException.class,
                () -> JaroWinklerDistance.getScore(null, "stario"));
        assertThrows(IllegalArgumentException.class,
                () -> JaroWinklerDistance.getScore("stario", null));
    }

    @Test
    public void getScore_emptyStrings_returnZero() {
        assertEquals(0.0d, JaroWinklerDistance.getScore("", ""), 0.0d);
        assertEquals(0.0d, JaroWinklerDistance.getScore("", "a"), 0.0d);
        assertEquals(0.0d, JaroWinklerDistance.getScore("aaapppp", ""), 0.0d);
    }

    @Test
    public void getScore_exactMatches_returnOne() {
        assertEquals(1.0d, JaroWinklerDistance.getScore("a", "a"), 0.0d);
        assertEquals(1.0d, JaroWinklerDistance.getScore("Settings", "Settings"), 0.0d);
        assertEquals(1.0d, JaroWinklerDistance.getScore("Stario Launcher", "Stario Launcher"), 0.0d);
    }

    @Test
    public void getScore_disjointStrings_returnZero() {
        assertEquals(0.0d, JaroWinklerDistance.getScore("fly", "ant"), 0.0d);
        assertEquals(0.0d, JaroWinklerDistance.getScore("hippo", "zzzzzzzz"), 0.0d);
        assertEquals(0.0d, JaroWinklerDistance.getScore("a", "b"), 0.0d);
    }

    @Test
    public void getScore_matchesReferenceExamples() {
        assertEquals(0.93d, JaroWinklerDistance.getScore("frog", "fog"), 0.001d);
        assertEquals(0.44d, JaroWinklerDistance.getScore("elephant", "hippo"), 0.001d);
        assertEquals(0.44d, JaroWinklerDistance.getScore("hippo", "elephant"), 0.001d);
        assertEquals(0.88d, JaroWinklerDistance.getScore("hello", "hallo"), 0.001d);
        assertEquals(0.93d, JaroWinklerDistance.getScore("ABC Corporation", "ABC Corp"), 0.001d);
        assertEquals(0.95d,
                JaroWinklerDistance.getScore("D N H Enterprises Inc", "D & H Enterprises, Inc."),
                0.001d);
        assertEquals(0.92d,
                JaroWinklerDistance.getScore("My Gym Children's Fitness Center", "My Gym. Childrens Fitness"),
                0.001d);
        assertEquals(0.88d, JaroWinklerDistance.getScore("PENNSYLVANIA", "PENNCISYLVNIA"), 0.001d);
    }

    @Test
    public void getScore_isSymmetric() {
        String[][] pairs = {
                {"calculator", "calc"},
                {"whatsapp", "whats"},
                {"firefox", "foxfire"},
                {"caméra", "camera"},
                {"spotify", "spotfy"}
        };

        for (String[] pair : pairs) {
            assertEquals("Expected symmetry for " + pair[0] + " / " + pair[1],
                    JaroWinklerDistance.getScore(pair[0], pair[1]),
                    JaroWinklerDistance.getScore(pair[1], pair[0]));
        }
    }

    @Test
    public void getScore_appliesWinklerPrefixBoostAboveThreshold() {
        // Common prefix ("chrome" vs "chrom") should score higher than suffix-only overlap ("chrome" vs "hrome")
        double prefixScore = JaroWinklerDistance.getScore("chrome", "chrom");
        double nonPrefixScore = JaroWinklerDistance.getScore("chrome", "hrome");
        assertTrue(prefixScore > nonPrefixScore);
        assertTrue(prefixScore > 0.87d);

        // Below 0.7 Jaro similarity, Winkler prefix boost does not apply
        int[] lowJaroMtp = JaroWinklerDistance.matches("abxxxxxx", "abyyyyyy");
        assertEquals(2, lowJaroMtp[0]); // matches = 2 ('a', 'b')
        assertEquals(2, lowJaroMtp[2]); // prefix = 2
        // Jaro = (2/8 + 2/8 + 2/2) / 3 = 1.5 / 3 = 0.50 (< 0.70, so no prefix boost)
        assertEquals(0.50d, JaroWinklerDistance.getScore("abxxxxxx", "abyyyyyy"), 0.001d);
    }

    @Test
    public void getScore_handlesUnicodeAndEmojis() {
        assertEquals(1.0d, JaroWinklerDistance.getScore("Café", "Café"), 0.0d);
        assertEquals(0.88d, JaroWinklerDistance.getScore("Café", "Cafe"), 0.001d);

        assertEquals(1.0d, JaroWinklerDistance.getScore("東京", "東京"), 0.0d);
        assertTrue(JaroWinklerDistance.getScore("東京都", "東京") > 0.85d);

        assertEquals(1.0d, JaroWinklerDistance.getScore("Привет", "Привет"), 0.0d);
        assertEquals(1.0d, JaroWinklerDistance.getScore("🚀App", "🚀App"), 0.0d);
        assertTrue(JaroWinklerDistance.getScore("🚀App", "🚀Ap") > 0.90d);
    }

    @Test
    public void matches_reportsMatchesTranspositionsPrefixAndMaxLength() {
        // "martha" vs "marhta": 6 matches, 1 transposition pair (t/h), 3 char prefix ("mar"), maxLen 6
        assertArrayEquals(new int[]{6, 1, 3, 6},
                JaroWinklerDistance.matches("martha", "marhta"));
        assertEquals(0.96d, JaroWinklerDistance.getScore("martha", "marhta"), 0.001d);
    }
}
