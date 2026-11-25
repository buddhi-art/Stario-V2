package com.stario.launcher.testutil;

import android.os.Handler;
import android.os.Looper;
import android.widget.EdgeEffect;

import com.stario.launcher.ui.utils.UiUtils;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.implementation.StubMethod;
import net.bytebuddy.matcher.ElementMatchers;

import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.concurrent.ConcurrentLinkedQueue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Helper to stub Android static handlers, loopers, Process, and EdgeEffect cleanly for pure-JVM tests.
 */
public final class AndroidStubsHelper {
    private static final ConcurrentLinkedQueue<Runnable> MAIN_CALLBACKS = new ConcurrentLinkedQueue<>();
    private static volatile boolean stubsInstalled = false;

    private AndroidStubsHelper() {
    }

    public static void ensureInitialized() {
        if (stubsInstalled) {
            return;
        }
        synchronized (AndroidStubsHelper.class) {
            if (stubsInstalled) {
                return;
            }

            // Ensure Mockito has attached ByteBuddyAgent before redefining Android stub classes
            Looper mockLooper = mock(Looper.class);
            when(mockLooper.getThread()).thenReturn(Thread.currentThread());

            redefineAsNoOpStubs(EdgeEffect.class);
            redefineAsNoOpStubs(android.os.Process.class);

            try (MockedStatic<Looper> looperStatic = Mockito.mockStatic(Looper.class);
                 MockedConstruction<Handler> ignored = Mockito.mockConstruction(
                         Handler.class,
                         (handler, context) -> {
                             when(handler.post(any(Runnable.class))).thenAnswer(inv -> {
                                 MAIN_CALLBACKS.add(inv.getArgument(0));
                                 return true;
                             });
                             when(handler.postDelayed(any(Runnable.class), anyLong())).thenAnswer(inv -> {
                                 MAIN_CALLBACKS.add(inv.getArgument(0));
                                 return true;
                             });
                         })) {
                looperStatic.when(Looper::getMainLooper).thenReturn(mockLooper);
                Class.forName(UiUtils.class.getName());
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }

            stubsInstalled = true;
        }
    }

    private static void redefineAsNoOpStubs(Class<?> target) {
        try {
            new ByteBuddy()
                    .redefine(target)
                    .constructor(ElementMatchers.any())
                    .intercept(MethodCall.invoke(Object.class.getDeclaredConstructor()))
                    .method(ElementMatchers.isDeclaredBy(target))
                    .intercept(StubMethod.INSTANCE)
                    .make()
                    .load(target.getClassLoader(), ClassReloadingStrategy.fromInstalledAgent());
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static void drainUiCallbacks() {
        Runnable runnable;
        while ((runnable = MAIN_CALLBACKS.poll()) != null) {
            runnable.run();
        }
    }

    public static void clearUiCallbacks() {
        MAIN_CALLBACKS.clear();
    }
}
