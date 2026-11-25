package com.stario.launcher.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CasingTest {

    @Test
    public void toSentenceCase_handlesStandardSentences() {
        assertEquals("Hello world", Casing.toSentenceCase("hello world"));
        assertEquals("Hello world", Casing.toSentenceCase("HELLO WORLD"));
        assertEquals("Hello world. This is a test.",
                Casing.toSentenceCase("hello world. this IS a TEST."));
        assertEquals("First. Second. Third",
                Casing.toSentenceCase("first. second. third"));
    }

    @Test
    public void toSentenceCase_handlesEdgeCasesAndSeparators() {
        assertEquals("", Casing.toSentenceCase(""));
        assertEquals("A", Casing.toSentenceCase("a"));
        assertEquals("A", Casing.toSentenceCase("A"));
        assertEquals("  .-_/()Hello world",
                Casing.toSentenceCase("  .-_/()hello WORLD"));
        assertEquals("123 abc", Casing.toSentenceCase("123 ABC"));
        assertEquals("École. Árbol", Casing.toSentenceCase("éCOLE. ÁRBOL"));
    }

    @Test
    public void toTitleCase_handlesAllWordSeparators() {
        assertEquals("Hello World", Casing.toTitleCase("hello world"));
        assertEquals("Google Play Store", Casing.toTitleCase("GOOGLE PLAY STORE"));
        assertEquals("A B.C-D_E/F(G)H",
                Casing.toTitleCase("a b.c-d_e/f(g)h"));
        assertEquals("Hello   ---   World",
                Casing.toTitleCase("hello   ---   WORLD"));
    }

    @Test
    public void toTitleCase_handlesEmptyAndCalendarStringsAndUnicode() {
        assertEquals("", Casing.toTitleCase(""));
        assertEquals("Monday, ", Casing.toTitleCase("monday, "));
        assertEquals("Jan\u00A05", Casing.toTitleCase("jan\u00A05"));
        assertEquals("Lundi, Café-Crème", Casing.toTitleCase("lundi, café-crème"));
    }
}
