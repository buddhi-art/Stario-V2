package com.stario.launcher.ui.recyclers.overscroll;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;

import com.stario.launcher.testutil.AndroidStubsHelper;
import com.stario.launcher.ui.Measurements;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class OverScrollEffectTest {
    private MockedStatic<ViewConfiguration> viewConfigStatic;
    private MockedStatic<VelocityTracker> velocityTrackerStatic;
    private MockedStatic<Looper> looperStatic;
    private MockedStatic<Choreographer> choreographerStatic;
    private Harness harness;

    @BeforeClass
    public static void installAndroidStubs() {
        AndroidStubsHelper.ensureInitialized();
    }

    @Before
    public void setUp() throws Exception {
        setMeasurementsDp(2.0f);

        ViewConfiguration mockViewConfig = mock(ViewConfiguration.class);
        when(mockViewConfig.getScaledTouchSlop()).thenReturn(8);
        when(mockViewConfig.getScaledMaximumFlingVelocity()).thenReturn(8000);
        viewConfigStatic = Mockito.mockStatic(ViewConfiguration.class);
        viewConfigStatic.when(() -> ViewConfiguration.get(any())).thenReturn(mockViewConfig);

        VelocityTracker mockTracker = mock(VelocityTracker.class);
        velocityTrackerStatic = Mockito.mockStatic(VelocityTracker.class);
        velocityTrackerStatic.when(VelocityTracker::obtain).thenReturn(mockTracker);

        Looper mockLooper = mock(Looper.class);
        when(mockLooper.getThread()).thenReturn(Thread.currentThread());
        looperStatic = Mockito.mockStatic(Looper.class);
        looperStatic.when(Looper::myLooper).thenReturn(mockLooper);
        looperStatic.when(Looper::getMainLooper).thenReturn(mockLooper);

        Choreographer mockChoreographer = mock(Choreographer.class);
        choreographerStatic = Mockito.mockStatic(Choreographer.class);
        choreographerStatic.when(Choreographer::getInstance).thenReturn(mockChoreographer);

        harness = new Harness();
    }

    @After
    public void tearDown() throws Exception {
        if (choreographerStatic != null) choreographerStatic.close();
        if (looperStatic != null) looperStatic.close();
        if (velocityTrackerStatic != null) velocityTrackerStatic.close();
        if (viewConfigStatic != null) viewConfigStatic.close();
        setMeasurementsDp(0.0f);
    }

    private static void setMeasurementsDp(float dp) throws Exception {
        Field dpField = Measurements.class.getDeclaredField("dp");
        dpField.setAccessible(true);
        dpField.setFloat(null, dp);
    }

    @Test
    public void prepare_whenIdle_returnsFalseWithoutTranslatingCanvas() {
        harness.testIdleState();
    }

    @Test
    public void rubberBandMath_topEdge_followsHyperbolicCurveAndClampsAtMaxFactor() {
        harness.testTopEdgeRubberBandMath();
    }

    @Test
    public void rubberBandMath_bottomEdge_negatesTranslationAndExhibitsDiminishingReturns() {
        harness.testBottomEdgeRubberBandMath();
    }

    @Test
    public void pullDistanceReleaseAndPositiveAbsorb_manageStateCleanly() {
        harness.testPullDistanceReleaseAndAbsorb();
    }

    /**
     * Separate class so OverScrollEffect/EdgeEffect is only resolved after @BeforeClass installs
     * the EdgeEffect ByteBuddy transformer.
     */
    private static class Harness {
        private static class TestOverScrollView extends View implements OverScroll {
            List<OverScrollContract> contracts;
            OverScrollEffect<?> owner;

            TestOverScrollView() {
                super(null);
            }

            @Override
            public boolean tryCaptureOverScroll(@NonNull OverScrollEffect<?> effect) {
                if (owner == null || owner.equals(effect)) {
                    owner = effect;
                    return true;
                }
                return false;
            }

            @Override
            public void releaseOverScroll(@NonNull OverScrollEffect<?> effect) {
                if (owner != null && owner.equals(effect)) {
                    owner = null;
                }
            }

            @Override
            public void addOverScrollContract(@NonNull OverScrollContract contract) {
                contracts.add(contract);
            }
        }

        private TestOverScrollView createView() {
            TestOverScrollView view = mock(TestOverScrollView.class, Mockito.CALLS_REAL_METHODS);
            view.contracts = new ArrayList<>();
            view.owner = null;
            Context mockContext = mock(Context.class);
            doReturn(mockContext).when(view).getContext();
            doReturn(false).when(view).canScrollVertically(anyInt());
            doNothing().when(view).invalidate();
            doAnswer(inv -> {
                Runnable action = inv.getArgument(0);
                if (action != null) {
                    action.run();
                }
                return null;
            }).when(view).postOnAnimation(any(Runnable.class));
            return view;
        }

        private static float expectedRubberBand(float factor, int canvasHeight) {
            int maxTranslation = Math.min((int) (canvasHeight * 0.12f), Measurements.dpToPx(100f));
            float clampedFactor = Math.min(1.5f, factor);
            float rawOvershoot = clampedFactor * maxTranslation * 1.5f;
            float c = 0.55f;
            return (rawOvershoot * maxTranslation * c) /
                    (maxTranslation + c * Math.abs(rawOvershoot));
        }

        private static void simulateMoveEvent(OverScrollEffect<?> effect) {
            MotionEvent move = mock(MotionEvent.class);
            when(move.getAction()).thenReturn(MotionEvent.ACTION_MOVE);
            when(move.getActionMasked()).thenReturn(MotionEvent.ACTION_MOVE);
            when(move.getRawY()).thenReturn(120f);
            effect.onTouchEvent(move);
        }

        void testIdleState() {
            TestOverScrollView view = createView();
            OverScrollEffect<TestOverScrollView> effect = new OverScrollEffect<>(
                    view, OverScrollEffect.PULL_EDGE_TOP, OverScrollEffect.PIVOT_TOP);
            Canvas canvas = mock(Canvas.class);
            when(canvas.getHeight()).thenReturn(2000);

            assertTrue(effect.isFinished());
            assertFalse(view.contracts.get(0).prepare(canvas));
            verify(canvas, never()).translate(eq(0f), anyFloat());
            effect.finish();
        }

        void testTopEdgeRubberBandMath() {
            TestOverScrollView view = createView();
            OverScrollEffect<TestOverScrollView> effect = new OverScrollEffect<>(
                    view, OverScrollEffect.PULL_EDGE_TOP, OverScrollEffect.PIVOT_TOP);
            simulateMoveEvent(effect);

            int canvasHeight = 2000;
            int maxTranslation = Math.min((int) (canvasHeight * 0.12f), Measurements.dpToPx(100f));
            assertEquals(200, maxTranslation);

            float[] factors = {0.25f, 0.5f, 1.0f, 1.5f, 2.5f};
            float prevTranslation = -1f;
            float translationAt15 = 0f;

            float current = 0f;
            for (float targetFactor : factors) {
                effect.onPull(targetFactor - current);
                current = targetFactor;

                Canvas canvas = mock(Canvas.class);
                when(canvas.getHeight()).thenReturn(canvasHeight);
                assertTrue(view.contracts.get(0).prepare(canvas));

                ArgumentCaptor<Float> dyCaptor = ArgumentCaptor.forClass(Float.class);
                verify(canvas).translate(eq(0f), dyCaptor.capture());
                float actual = dyCaptor.getValue();
                float expected = expectedRubberBand(targetFactor, canvasHeight);

                assertEquals("Mismatch at factor=" + targetFactor, expected, actual, 1e-3f);
                assertTrue("Translation must stay strictly below maxTranslation", actual < maxTranslation);
                assertTrue("Translation must be non-decreasing", actual >= prevTranslation);

                if (targetFactor == 1.5f) {
                    translationAt15 = actual;
                } else if (targetFactor > 1.5f) {
                    assertEquals("Factor > 1.5f should clamp to 1.5f", translationAt15, actual, 1e-3f);
                }
                prevTranslation = actual;
            }

            effect.finish();
        }

        void testBottomEdgeRubberBandMath() {
            TestOverScrollView view = createView();
            OverScrollEffect<TestOverScrollView> effect = new OverScrollEffect<>(
                    view, OverScrollEffect.PULL_EDGE_BOTTOM, OverScrollEffect.PIVOT_BOTTOM);
            simulateMoveEvent(effect);

            int canvasHeight = 600;
            effect.onPull(0.5f);
            Canvas firstCanvas = mock(Canvas.class);
            when(firstCanvas.getHeight()).thenReturn(canvasHeight);
            assertTrue(view.contracts.get(0).prepare(firstCanvas));
            ArgumentCaptor<Float> firstDy = ArgumentCaptor.forClass(Float.class);
            verify(firstCanvas).translate(eq(0f), firstDy.capture());

            effect.onPull(0.5f); // total factor = 1.0f
            Canvas secondCanvas = mock(Canvas.class);
            when(secondCanvas.getHeight()).thenReturn(canvasHeight);
            assertTrue(view.contracts.get(0).prepare(secondCanvas));
            ArgumentCaptor<Float> secondDy = ArgumentCaptor.forClass(Float.class);
            verify(secondCanvas).translate(eq(0f), secondDy.capture());

            assertEquals(-expectedRubberBand(0.5f, canvasHeight), firstDy.getValue(), 1e-3f);
            assertEquals(-expectedRubberBand(1.0f, canvasHeight), secondDy.getValue(), 1e-3f);

            // Progressive resistance: first 0.5 pull translates more than second 0.5 pull
            float firstDelta = Math.abs(firstDy.getValue());
            float secondDelta = Math.abs(secondDy.getValue()) - firstDelta;
            assertTrue("Expected diminishing returns for larger overshoot", firstDelta > secondDelta);

            effect.finish();
        }

        void testPullDistanceReleaseAndAbsorb() {
            TestOverScrollView view = createView();
            OverScrollEffect<TestOverScrollView> effect = new OverScrollEffect<>(
                    view, OverScrollEffect.PULL_EDGE_TOP, OverScrollEffect.PIVOT_TOP);

            // Before move event, pull distance is ignored
            assertEquals(0f, effect.onPullDistance(0.4f, 0.5f), 1e-6f);
            assertEquals(0f, effect.getDistance(), 1e-6f);

            simulateMoveEvent(effect);
            assertEquals(0.4f, effect.onPullDistance(0.4f, 0.5f), 1e-6f);
            assertEquals(0.4f, effect.getDistance(), 1e-6f);
            assertFalse(effect.isFinished());

            // Pulling back past zero returns clamped delta and finishes overscroll
            float consumed = effect.onPullDistance(-0.8f, 0.5f);
            assertEquals(-0.4f, consumed, 1e-6f);
            assertTrue(effect.isFinished());

            // Pull again and trigger onRelease + positive onAbsorb
            simulateMoveEvent(effect);
            effect.onPull(0.5f);
            effect.onRelease();
            assertFalse(effect.isFinished());

            effect.onAbsorb(5000);
            assertFalse(effect.isFinished());
            assertFalse(Float.isNaN(effect.getDistance()));

            effect.finish();
            assertTrue(effect.isFinished());
        }
    }
}
