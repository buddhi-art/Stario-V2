package com.stario.launcher.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UtilsTest {

    @Test
    public void toFahrenheit_convertsCelsiusAccurately() {
        assertEquals(32.0d, Utils.toFahrenheit(0.0d), 1e-9);
        assertEquals(212.0d, Utils.toFahrenheit(100.0d), 1e-9);
        assertEquals(-40.0d, Utils.toFahrenheit(-40.0d), 1e-9);
        assertEquals(98.6d, Utils.toFahrenheit(37.0d), 1e-9);
        assertEquals(77.9d, Utils.toFahrenheit(25.5d), 1e-9);
    }

    @Test
    public void msToMph_convertsSpeedAccurately() {
        assertEquals(0.0d, Utils.msToMph(0.0d), 1e-9);
        assertEquals(2.237d, Utils.msToMph(1.0d), 1e-9);
        assertEquals(22.37d, Utils.msToMph(10.0d), 1e-9);
        assertEquals(-11.185d, Utils.msToMph(-5.0d), 1e-9);
    }

    @Test
    public void intToUUID_isDeterministicUniqueAndMatchesMd5NameUuid() {
        int[] samples = {Integer.MIN_VALUE, -2, -1, 0, 1, 2, 12, Integer.MAX_VALUE};
        Set<UUID> seen = new HashSet<>();

        for (int sample : samples) {
            UUID first = Utils.intToUUID(sample);
            UUID second = Utils.intToUUID(sample);
            UUID expected = UUID.nameUUIDFromBytes(
                    ByteBuffer.allocate(Integer.SIZE / Byte.SIZE).putInt(sample).array());

            assertEquals(expected, first);
            assertEquals(first, second);
            assertEquals(3, first.version());
            assertTrue("Collision detected for " + sample, seen.add(first));
        }

        assertNotEquals(Utils.intToUUID(-1), Utils.intToUUID(1));
    }

    @Test
    public void getGenericInterpolatedValue_isCubicEaseInOut() {
        assertEquals(0.0d, Utils.getGenericInterpolatedValue(0.0d), 1e-9);
        assertEquals(0.0625d, Utils.getGenericInterpolatedValue(0.25d), 1e-9);
        assertEquals(0.5d, Utils.getGenericInterpolatedValue(0.5d), 1e-9);
        assertEquals(0.9375d, Utils.getGenericInterpolatedValue(0.75d), 1e-9);
        assertEquals(1.0d, Utils.getGenericInterpolatedValue(1.0d), 1e-9);

        double prev = -1.0d;
        for (int i = 0; i <= 20; i++) {
            double t = i / 20.0d;
            double value = Utils.getGenericInterpolatedValue(t);
            double mirrored = Utils.getGenericInterpolatedValue(1.0d - t);

            assertTrue("Expected monotonic increase at t=" + t, value >= prev);
            assertEquals("Expected odd symmetry around 0.5 at t=" + t, 1.0d, value + mirrored, 1e-9);
            prev = value;
        }
    }

    @Test
    public void isSystemUsingImperial_handlesNullContext() {
        assertFalse(Utils.isSystemUsingImperial(null));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void imperialCountries_containsExpectedCountriesAndMatchesContextCheck() throws Exception {
        Field field = Utils.class.getDeclaredField("IMPERIAL_COUNTRIES");
        field.setAccessible(true);
        Set<String> imperialCountries = (Set<String>) field.get(null);

        Set<String> expectedImperial = new HashSet<>(Arrays.asList(
                "US", "PW", "MH", "MP", "AS", "KY", "VI", "FM", "GU", "LR", "PR"
        ));
        assertEquals(expectedImperial, imperialCountries);

        for (String country : expectedImperial) {
            Context context = mockContextWithCountry(country);
            assertTrue("Expected imperial for country " + country,
                    Utils.isSystemUsingImperial(context));
        }

        for (String country : new String[]{"GB", "DE", "FR", "JP", "CA", "NP", "IN", "AU"}) {
            Context context = mockContextWithCountry(country);
            assertFalse("Expected metric for country " + country,
                    Utils.isSystemUsingImperial(context));
        }
    }

    @Test
    public void readStream_readsUtf8AndClosesStream() throws IOException {
        AtomicBoolean closed = new AtomicBoolean(false);
        ByteArrayInputStream stream = new ByteArrayInputStream(
                "Line 1\nLine 2 café 🚀".getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        assertEquals("Line 1Line 2 café 🚀", Utils.readStream(stream));
        assertTrue(closed.get());
    }

    private static Context mockContextWithCountry(String countryCode) {
        Context context = mock(Context.class);
        Resources resources = mock(Resources.class);
        Configuration configuration = mock(Configuration.class);
        LocaleList localeList = mock(LocaleList.class);

        when(context.getResources()).thenReturn(resources);
        when(resources.getConfiguration()).thenReturn(configuration);
        when(configuration.getLocales()).thenReturn(localeList);
        when(localeList.get(0)).thenReturn(new Locale("en", countryCode));

        return context;
    }
}
