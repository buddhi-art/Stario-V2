package com.stario.launcher.preferences;

import org.junit.Test;

import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EntryTest {

    @Test
    public void toString_matchesExpectedPrefixForAllEntries() {
        assertTrue(Entry.values().length > 0);
        for (Entry entry : Entry.values()) {
            assertEquals("com.stario." + entry.name(), entry.toString());
        }
    }

    @Test
    public void isValid_acceptsEveryEntryAndItsSubPreferences() {
        String sampleUuid = UUID.randomUUID().toString();
        for (Entry entry : Entry.values()) {
            assertTrue("Expected valid entry: " + entry, Entry.isValid(entry.toString()));
            assertTrue("Expected valid sub-preference for: " + entry,
                    Entry.isValid(entry.toSubPreference(sampleUuid)));
            assertTrue("Expected valid numeric sub-preference for: " + entry,
                    Entry.isValid(entry.toSubPreference("0")));
        }
    }

    @Test
    public void toSubPreference_formatsWithDotSeparator() {
        assertEquals("com.stario.CATEGORY_MAP.CATEGORIES",
                Entry.CATEGORY_MAP.toSubPreference("CATEGORIES"));
        assertEquals("com.stario.HIDDEN_APPS.10",
                Entry.HIDDEN_APPS.toSubPreference("10"));
    }

    @Test
    public void isValid_rejectsNullEmptyAndUnknownPreferences() {
        assertFalse(Entry.isValid(null));
        assertFalse(Entry.isValid(""));
        assertFalse(Entry.isValid("   "));
        assertFalse(Entry.isValid("com.stario."));
        assertFalse(Entry.isValid("com.stario.UNKNOWN_STORE"));
        assertFalse(Entry.isValid("STARIO"));
        assertFalse(Entry.isValid("com.stario.stario"));
        assertFalse(Entry.isValid("prefix.com.stario.STARIO"));
    }
}
