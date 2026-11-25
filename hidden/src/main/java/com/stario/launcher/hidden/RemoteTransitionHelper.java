/*
 * Copyright (C) 2026 Răzvan Albu
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>
 */

package com.stario.launcher.hidden;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.view.Choreographer;
import android.view.SurfaceControl;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Safely bridges privileged RemoteAnimationAdapter (API 29+) and RemoteTransition (API 34+)
 * using runtime reflection + dynamic AIDL Binder dispatch so non-privileged builds never crash.
 */
@SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
public final class RemoteTransitionHelper {
    private static final String TAG = "RemoteTransitionHelper";
    public static final String PERMISSION_CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS =
            "android.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS";

    private static final int MODE_OPENING = 0;
    private static final int MODE_CLOSING = 1;
    private static final int TRANSIT_OPEN = 1;
    private static final int TRANSIT_CLOSE = 2;
    private static final int TRANSIT_TO_FRONT = 3;
    private static final int TRANSIT_TO_BACK = 4;
    private static final int ACTIVITY_TYPE_HOME = 2;

    // TransitionInfo.FLAG_IS_WALLPAPER | FLAG_IS_DISPLAY | FLAG_IS_SYSTEM_WINDOW | FLAG_IS_INPUT_METHOD
    private static final int SKIP_CHANGE_FLAGS = (1 << 1) | (1 << 4) | (1 << 5) | (1 << 8);

    private static final long WATCHDOG_TIMEOUT_MS = 1400L;
    private static final float LAUNCH_STIFFNESS = 380f;
    private static final float LAUNCH_DAMPING = 1.0f;
    private static final float RETURN_STIFFNESS = 340f;
    private static final float RETURN_DAMPING = 0.98f;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final int PRIMARY_LAYER = 100;
    private static final int SECONDARY_LAYER = 50;

    private static boolean surfaceHiddenChecked = false;
    private static Method txSetCornerRadiusMethod;
    private static Method txSetWindowCropMethod;
    private static Method txSetMatrixMethod;
    private static Method txSetPositionMethod;

    private static volatile ActiveSurfaceAnimator activeAnimator;

    public interface TransitionProgressListener {
        void onTransitionStart(boolean isOpening);

        void onTransitionProgress(float progress, boolean isOpening);

        void onTransitionEnd(boolean isOpening);
    }

    public interface ReturnTargetResolver {
        Rect resolveReturnBounds(ComponentName closingComponent, Rect screenFallback);

        ComponentName getLastLaunchedComponent();
    }

    private RemoteTransitionHelper() {
    }

    public static boolean hasPrivilegedPermission(Context context) {
        if (context == null) {
            return false;
        }
        try {
            return context.checkSelfPermission(PERMISSION_CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isRemoteReturnAnimating() {
        ActiveSurfaceAnimator current = activeAnimator;
        return current != null && !current.isOpening && !current.finished.get();
    }

    public static ActivityOptions createLaunchOptions(Context context,
                                                      Rect sourceBounds,
                                                      float startCornerRadiusPx,
                                                      TransitionProgressListener progressListener) {
        if (context == null || sourceBounds == null || !hasPrivilegedPermission(context)) {
            return null;
        }

        Rect safeBounds = new Rect(sourceBounds);
        if (safeBounds.width() <= 0 || safeBounds.height() <= 0) {
            return null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ActivityOptions transitionOptions = createRemoteTransitionOptions(
                    context, safeBounds, startCornerRadiusPx, progressListener);
            if (transitionOptions != null) {
                return transitionOptions;
            }
        }

        return createRemoteAnimationOptions(
                context, safeBounds, startCornerRadiusPx, true, null, progressListener);
    }

    public static boolean registerLauncherReturnAnimations(Activity activity,
                                                         ReturnTargetResolver resolver,
                                                         TransitionProgressListener progressListener) {
        if (activity == null || resolver == null || !hasPrivilegedPermission(activity)) {
            return false;
        }

        try {
            Class<?> definitionClass = Class.forName("android.view.RemoteAnimationDefinition");
            Class<?> adapterClass = Class.forName("android.view.RemoteAnimationAdapter");
            Object definition = definitionClass.getDeclaredConstructor().newInstance();

            Object returnAdapter = createRemoteAnimationAdapter(
                    activity, null, 0f, false, resolver, progressListener);
            if (returnAdapter == null) {
                return false;
            }

            Method addMethod = findAddRemoteAnimationMethod(definitionClass, adapterClass);
            if (addMethod == null) {
                return false;
            }

            int wallpaperOpen = getWindowManagerIntConst("TRANSIT_OLD_WALLPAPER_OPEN",
                    getWindowManagerIntConst("TRANSIT_WALLPAPER_OPEN", 13));
            int taskToFront = getWindowManagerIntConst("TRANSIT_OLD_TASK_TO_FRONT",
                    getWindowManagerIntConst("TRANSIT_TASK_TO_FRONT", 10));
            int taskClose = getWindowManagerIntConst("TRANSIT_OLD_TASK_CLOSE",
                    getWindowManagerIntConst("TRANSIT_TASK_CLOSE", 9));

            invokeAddRemoteAnimation(addMethod, definition, wallpaperOpen, returnAdapter);
            invokeAddRemoteAnimation(addMethod, definition, taskToFront, returnAdapter);
            invokeAddRemoteAnimation(addMethod, definition, taskClose, returnAdapter);

            Method registerMethod = Activity.class.getMethod("registerRemoteAnimations", definitionClass);
            registerMethod.invoke(activity, definition);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "registerLauncherReturnAnimations failed safely: " + t.getMessage());
            return false;
        }
    }

    public static void unregisterLauncherReturnAnimations(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Method unregisterMethod = Activity.class.getMethod("unregisterRemoteAnimations");
            unregisterMethod.invoke(activity);
        } catch (Throwable ignored) {
        }
    }

    private static ActivityOptions createRemoteTransitionOptions(Context context,
                                                                 Rect sourceBounds,
                                                                 float startCornerRadiusPx,
                                                                 TransitionProgressListener listener) {
        try {
            Class<?> remoteTransitionClass = Class.forName("android.window.RemoteTransition");
            Class<?> iRemoteTransitionClass = Class.forName("android.window.IRemoteTransition");
            Class<?> iRemoteTransitionStubClass = Class.forName("android.window.IRemoteTransition$Stub");
            Class<?> transitionInfoClass = Class.forName("android.window.TransitionInfo");
            Class<?> finishCbStubClass = Class.forName("android.window.IRemoteTransitionFinishedCallback$Stub");

            String descriptor = getStaticStringField(
                    iRemoteTransitionStubClass, "DESCRIPTOR", "android.window.IRemoteTransition");
            int txStart = getStaticIntField(
                    iRemoteTransitionStubClass, "TRANSACTION_startAnimation", IBinder.FIRST_CALL_TRANSACTION);
            int txMerge = getStaticIntField(
                    iRemoteTransitionStubClass, "TRANSACTION_mergeAnimation", IBinder.FIRST_CALL_TRANSACTION + 1);
            int txTakeOver = getStaticIntField(
                    iRemoteTransitionStubClass, "TRANSACTION_takeOverAnimation", -1);
            int txConsumed = getStaticIntField(
                    iRemoteTransitionStubClass, "TRANSACTION_onTransitionConsumed", -1);

            Method startMethod = findMethodByName(iRemoteTransitionClass, "startAnimation");
            Method mergeMethod = findMethodByName(iRemoteTransitionClass, "mergeAnimation");
            if (startMethod == null) {
                return null;
            }
            Class<?>[] startParams = startMethod.getParameterTypes();
            Class<?>[] mergeParams = mergeMethod != null ? mergeMethod.getParameterTypes() : null;

            final IInterface[] proxyHolder = new IInterface[1];
            final Binder binder = new Binder() {
                @Override
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code == txStart) {
                        data.enforceInterface(descriptor);
                        Object[] args = readAidlParams(data, startParams);
                        handleRemoteTransitionStart(context, args, sourceBounds, startCornerRadiusPx, listener);
                        ackOnewayFallback(flags, reply);
                        return true;
                    } else if (code == txMerge && mergeParams != null) {
                        data.enforceInterface(descriptor);
                        Object[] args = readAidlParams(data, mergeParams);
                        finishRemoteTransitionCallback(findLastInterfaceArg(args));
                        ackOnewayFallback(flags, reply);
                        return true;
                    } else if (txTakeOver != -1 && code == txTakeOver) {
                        data.enforceInterface(descriptor);
                        Method takeOverMethod = findMethodByName(iRemoteTransitionClass, "takeOverAnimation");
                        if (takeOverMethod != null) {
                            Object[] args = readAidlParams(data, takeOverMethod.getParameterTypes());
                            finishRemoteTransitionCallback(findLastInterfaceArg(args));
                        }
                        ackOnewayFallback(flags, reply);
                        return true;
                    } else if (txConsumed != -1 && code == txConsumed) {
                        data.enforceInterface(descriptor);
                        cancelActiveAnimatorOnMain();
                        ackOnewayFallback(flags, reply);
                        return true;
                    }
                    return super.onTransact(code, data, reply, flags);
                }
            };

            InvocationHandler handler = (proxy, method, args) -> {
                String name = method.getName();
                if ("asBinder".equals(name)) {
                    return binder;
                } else if ("startAnimation".equals(name)) {
                    handleRemoteTransitionStart(context, args, sourceBounds, startCornerRadiusPx, listener);
                    return null;
                } else if ("mergeAnimation".equals(name) || "takeOverAnimation".equals(name)) {
                    finishRemoteTransitionCallback(findLastInterfaceArg(args));
                    return null;
                } else if ("onTransitionConsumed".equals(name)) {
                    cancelActiveAnimatorOnMain();
                    return null;
                }
                return defaultValueForReturn(method.getReturnType());
            };

            Object proxy = Proxy.newProxyInstance(
                    iRemoteTransitionClass.getClassLoader(),
                    new Class<?>[]{iRemoteTransitionClass},
                    handler);
            proxyHolder[0] = (IInterface) proxy;
            binder.attachInterface(proxyHolder[0], descriptor);

            Object remoteTransition = instantiateRemoteTransition(remoteTransitionClass, iRemoteTransitionClass, proxy);
            if (remoteTransition == null) {
                return null;
            }

            Method makeRemoteTransition = ActivityOptions.class.getMethod("makeRemoteTransition", remoteTransitionClass);
            ActivityOptions opts = (ActivityOptions) makeRemoteTransition.invoke(null, remoteTransition);
            if (opts != null && transitionInfoClass != null && finishCbStubClass != null) {
                return opts;
            }
        } catch (Throwable t) {
            Log.w(TAG, "createRemoteTransitionOptions unavailable, falling back: " + t.getMessage());
        }
        return null;
    }

    private static ActivityOptions createRemoteAnimationOptions(Context context,
                                                                Rect sourceBounds,
                                                                float startCornerRadiusPx,
                                                                boolean isOpening,
                                                                ReturnTargetResolver resolver,
                                                                TransitionProgressListener listener) {
        try {
            Object adapter = createRemoteAnimationAdapter(
                    context, sourceBounds, startCornerRadiusPx, isOpening, resolver, listener);
            if (adapter == null) {
                return null;
            }
            Class<?> adapterClass = Class.forName("android.view.RemoteAnimationAdapter");
            Method makeRemoteAnimation = ActivityOptions.class.getMethod("makeRemoteAnimation", adapterClass);
            return (ActivityOptions) makeRemoteAnimation.invoke(null, adapter);
        } catch (Throwable t) {
            Log.w(TAG, "createRemoteAnimationOptions failed safely: " + t.getMessage());
            return null;
        }
    }

    private static Object createRemoteAnimationAdapter(Context context,
                                                       Rect sourceBounds,
                                                       float startCornerRadiusPx,
                                                       boolean isOpening,
                                                       ReturnTargetResolver resolver,
                                                       TransitionProgressListener listener) throws Exception {
        Class<?> adapterClass = Class.forName("android.view.RemoteAnimationAdapter");
        Class<?> runnerClass = Class.forName("android.view.IRemoteAnimationRunner");
        Class<?> runnerStubClass = Class.forName("android.view.IRemoteAnimationRunner$Stub");

        String descriptor = getStaticStringField(
                runnerStubClass, "DESCRIPTOR", "android.view.IRemoteAnimationRunner");
        int txStart = getStaticIntField(
                runnerStubClass, "TRANSACTION_onAnimationStart", IBinder.FIRST_CALL_TRANSACTION);
        int txCancel = getStaticIntField(
                runnerStubClass, "TRANSACTION_onAnimationCancelled", IBinder.FIRST_CALL_TRANSACTION + 1);

        Method startMethod = findMethodByName(runnerClass, "onAnimationStart");
        Method cancelMethod = findMethodByName(runnerClass, "onAnimationCancelled");
        if (startMethod == null) {
            return null;
        }
        Class<?>[] startParams = startMethod.getParameterTypes();
        Class<?>[] cancelParams = cancelMethod != null ? cancelMethod.getParameterTypes() : new Class<?>[0];

        final IInterface[] proxyHolder = new IInterface[1];
        final Binder binder = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == txStart) {
                    data.enforceInterface(descriptor);
                    Object[] args = readAidlParams(data, startParams);
                    handleRemoteAnimationStart(
                            context, args, sourceBounds, startCornerRadiusPx, isOpening, resolver, listener);
                    ackOnewayFallback(flags, reply);
                    return true;
                } else if (code == txCancel) {
                    data.enforceInterface(descriptor);
                    if (cancelParams.length > 0) {
                        readAidlParams(data, cancelParams);
                    }
                    cancelActiveAnimatorOnMain();
                    ackOnewayFallback(flags, reply);
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };

        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("asBinder".equals(name)) {
                return binder;
            } else if ("onAnimationStart".equals(name)) {
                handleRemoteAnimationStart(
                        context, args, sourceBounds, startCornerRadiusPx, isOpening, resolver, listener);
                return null;
            } else if ("onAnimationCancelled".equals(name)) {
                cancelActiveAnimatorOnMain();
                return null;
            }
            return defaultValueForReturn(method.getReturnType());
        };

        Object proxy = Proxy.newProxyInstance(
                runnerClass.getClassLoader(),
                new Class<?>[]{runnerClass},
                handler);
        proxyHolder[0] = (IInterface) proxy;
        binder.attachInterface(proxyHolder[0], descriptor);

        long durationMs = isOpening ? 360L : 380L;
        long statusBarDelayMs = isOpening ? 80L : 60L;

        try {
            Constructor<?> c3 = adapterClass.getConstructor(runnerClass, long.class, long.class);
            return c3.newInstance(proxy, durationMs, statusBarDelayMs);
        } catch (NoSuchMethodException ignored) {
            Constructor<?> c4 = adapterClass.getConstructor(runnerClass, long.class, long.class, boolean.class);
            return c4.newInstance(proxy, durationMs, statusBarDelayMs, false);
        }
    }

    private static void handleRemoteTransitionStart(Context context,
                                                    Object[] args,
                                                    Rect sourceBounds,
                                                    float startCornerRadiusPx,
                                                    TransitionProgressListener listener) {
        Object info = null;
        SurfaceControl.Transaction startTx = null;
        Object finishCb = null;

        if (args != null) {
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                String clsName = arg.getClass().getName();
                if ("android.window.TransitionInfo".equals(clsName)) {
                    info = arg;
                } else if (arg instanceof SurfaceControl.Transaction) {
                    startTx = (SurfaceControl.Transaction) arg;
                } else if (arg instanceof IInterface) {
                    finishCb = arg;
                }
            }
        }

        final Object finalInfo = info;
        final SurfaceControl.Transaction finalStartTx = startTx;
        final Object finalFinishCb = finishCb;

        MAIN_HANDLER.post(() -> {
            if (finalInfo == null) {
                if (finalStartTx != null) {
                    try {
                        finalStartTx.apply();
                    } catch (Throwable ignored) {
                    }
                }
                finishRemoteTransitionCallback(finalFinishCb);
                return;
            }

            try {
                @SuppressWarnings("unchecked")
                List<Object> changes = (List<Object>) finalInfo.getClass().getMethod("getChanges").invoke(finalInfo);
                Point rootOffset = new Point(0, 0);
                try {
                    Point p = (Point) finalInfo.getClass().getMethod("getRootOffset").invoke(finalInfo);
                    if (p != null) {
                        rootOffset.set(p.x, p.y);
                    }
                } catch (Throwable ignored) {
                }

                SurfaceControl openingLeash = null;
                Rect openingEndBounds = null;
                SurfaceControl closingLeash = null;
                Rect closingBounds = null;

                if (changes != null) {
                    for (Object change : changes) {
                        int flags = (int) change.getClass().getMethod("getFlags").invoke(change);
                        if ((flags & SKIP_CHANGE_FLAGS) != 0) {
                            continue;
                        }
                        Object taskInfo = change.getClass().getMethod("getTaskInfo").invoke(change);
                        if (taskInfo == null) {
                            continue;
                        }
                        int mode = (int) change.getClass().getMethod("getMode").invoke(change);
                        SurfaceControl leash = (SurfaceControl) change.getClass().getMethod("getLeash").invoke(change);
                        Rect endBounds = (Rect) change.getClass().getMethod("getEndAbsBounds").invoke(change);
                        if (leash == null || !leash.isValid()) {
                            continue;
                        }

                        boolean isHomeTask = false;
                        try {
                            int activityType = (int) taskInfo.getClass().getMethod("getActivityType").invoke(taskInfo);
                            isHomeTask = (activityType == ACTIVITY_TYPE_HOME);
                        } catch (Throwable ignored) {
                        }

                        if ((mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) && !isHomeTask && openingLeash == null) {
                            openingLeash = leash;
                            openingEndBounds = endBounds != null ? new Rect(endBounds) : null;
                        } else if ((mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) && closingLeash == null) {
                            closingLeash = leash;
                            closingBounds = endBounds != null ? new Rect(endBounds) : null;
                        }
                    }
                }

                if (finalStartTx != null) {
                    try {
                        if (openingLeash != null && openingEndBounds != null && openingEndBounds.width() > 0) {
                            applyMorphFrame(context, finalStartTx, openingLeash,
                                    sourceBounds, openingEndBounds, rootOffset,
                                    startCornerRadiusPx, 0f, 0.02f);
                        }
                        finalStartTx.apply();
                    } catch (Throwable t) {
                        Log.w(TAG, "startTx apply failed", t);
                    }
                }

                if (openingLeash == null || openingEndBounds == null || openingEndBounds.width() <= 0) {
                    finishRemoteTransitionCallback(finalFinishCb);
                    return;
                }

                startSurfaceSpring(
                        context,
                        true,
                        openingLeash,
                        openingEndBounds,
                        closingLeash,
                        closingBounds,
                        rootOffset,
                        sourceBounds,
                        startCornerRadiusPx,
                        listener,
                        () -> finishRemoteTransitionCallback(finalFinishCb)
                );
            } catch (Throwable t) {
                Log.w(TAG, "handleRemoteTransitionStart failed", t);
                finishRemoteTransitionCallback(finalFinishCb);
            }
        });
    }

    private static void handleRemoteAnimationStart(Context context,
                                                   Object[] args,
                                                   Rect fixedSourceBounds,
                                                   float startCornerRadiusPx,
                                                   boolean isOpening,
                                                   ReturnTargetResolver resolver,
                                                   TransitionProgressListener listener) {
        Object[] apps = null;
        Object finishCb = null;

        if (args != null) {
            for (Object arg : args) {
                if (arg == null) {
                    continue;
                }
                if (arg.getClass().isArray() && arg.getClass().getComponentType() != null
                        && "android.view.RemoteAnimationTarget".equals(arg.getClass().getComponentType().getName())) {
                    if (apps == null) {
                        apps = (Object[]) arg;
                    }
                } else if (arg instanceof IInterface) {
                    finishCb = arg;
                }
            }
        }

        final Object[] finalApps = apps;
        final Object finalFinishCb = finishCb;

        MAIN_HANDLER.post(() -> {
            if (finalApps == null || finalApps.length == 0) {
                finishRemoteAnimationCallback(finalFinishCb);
                return;
            }

            try {
                SurfaceControl openingLeash = null;
                Rect openingBounds = null;
                SurfaceControl closingLeash = null;
                Rect closingBounds = null;
                ComponentName closingComponent = null;
                boolean involvesOwnInternalActivity = false;

                for (Object target : finalApps) {
                    int mode = getIntField(target, "mode", -1);
                    SurfaceControl leash = (SurfaceControl) getField(target, "leash");
                    Rect bounds = extractTargetBounds(target);
                    boolean isHome = isHomeTarget(target);
                    ComponentName targetComponent = extractTargetComponent(target);

                    if (targetComponent != null && context.getPackageName().equals(targetComponent.getPackageName()) && !isHome) {
                        involvesOwnInternalActivity = true;
                    }

                    if (leash == null || !leash.isValid()) {
                        continue;
                    }

                    if (mode == MODE_OPENING && openingLeash == null) {
                        openingLeash = leash;
                        openingBounds = bounds;
                    } else if (mode == MODE_CLOSING && closingLeash == null) {
                        closingLeash = leash;
                        closingBounds = bounds;
                        closingComponent = targetComponent;
                    }
                }

                if (!isOpening && involvesOwnInternalActivity) {
                    finishRemoteAnimationCallback(finalFinishCb);
                    return;
                }

                if (isOpening) {
                    if (openingLeash == null || openingBounds == null || openingBounds.width() <= 0) {
                        finishRemoteAnimationCallback(finalFinishCb);
                        return;
                    }
                    startSurfaceSpring(
                            context,
                            true,
                            openingLeash,
                            openingBounds,
                            closingLeash,
                            closingBounds,
                            new Point(0, 0),
                            fixedSourceBounds,
                            startCornerRadiusPx,
                            listener,
                            () -> finishRemoteAnimationCallback(finalFinishCb)
                    );
                } else {
                    if (closingLeash == null || closingBounds == null || closingBounds.width() <= 0) {
                        finishRemoteAnimationCallback(finalFinishCb);
                        return;
                    }
                    ComponentName resolvedComponent = closingComponent != null
                            ? closingComponent
                            : (resolver != null ? resolver.getLastLaunchedComponent() : null);
                    Rect targetRect = resolver != null
                            ? resolver.resolveReturnBounds(resolvedComponent, closingBounds)
                            : null;
                    if (targetRect == null || targetRect.width() <= 0 || targetRect.height() <= 0) {
                        finishRemoteAnimationCallback(finalFinishCb);
                        return;
                    }

                    float targetCorner = Math.min(targetRect.width(), targetRect.height()) * 0.28f;
                    startSurfaceSpring(
                            context,
                            false,
                            closingLeash,
                            closingBounds,
                            openingLeash,
                            openingBounds,
                            new Point(0, 0),
                            targetRect,
                            targetCorner,
                            listener,
                            () -> finishRemoteAnimationCallback(finalFinishCb)
                    );
                }
            } catch (Throwable t) {
                Log.w(TAG, "handleRemoteAnimationStart failed", t);
                finishRemoteAnimationCallback(finalFinishCb);
            }
        });
    }

    private static void startSurfaceSpring(Context context,
                                           boolean isOpening,
                                           SurfaceControl primaryLeash,
                                           Rect fullBounds,
                                           SurfaceControl secondaryLeash,
                                           Rect secondaryBounds,
                                           Point rootOffset,
                                           Rect iconBounds,
                                           float iconCornerRadiusPx,
                                           TransitionProgressListener listener,
                                           Runnable onFinishedCallback) {
        cancelActiveAnimatorOnMain();

        ActiveSurfaceAnimator animator = new ActiveSurfaceAnimator(
                context,
                isOpening,
                primaryLeash,
                fullBounds,
                secondaryLeash,
                secondaryBounds,
                rootOffset,
                iconBounds,
                iconCornerRadiusPx,
                listener,
                onFinishedCallback
        );
        activeAnimator = animator;
        animator.start();
    }

    private static void cancelActiveAnimatorOnMain() {
        ActiveSurfaceAnimator current = activeAnimator;
        if (current != null) {
            MAIN_HANDLER.post(current::cancel);
        }
    }

    private static void applyMorphFrame(Context context,
                                        SurfaceControl.Transaction tx,
                                        SurfaceControl leash,
                                        Rect iconBounds,
                                        Rect fullBounds,
                                        Point rootOffset,
                                        float iconCornerRadiusPx,
                                        float openProgress,
                                        float alpha) {
        float t = Math.max(0f, Math.min(1f, openProgress));
        int fullW = Math.max(1, fullBounds.width());
        int fullH = Math.max(1, fullBounds.height());

        float iconW = Math.max(1f, iconBounds.width());
        float iconH = Math.max(1f, iconBounds.height());

        float startScale = iconW / fullW;
        float scale = startScale + (1f - startScale) * t;

        float startCropH = Math.min(fullH, fullW * (iconH / iconW));
        int cropH = Math.max(1, Math.round(startCropH + (fullH - startCropH) * t));
        int cropTop = Math.max(0, Math.round(((fullH - cropH) * 0.25f) * (1f - t)));

        float posX = (iconBounds.left - rootOffset.x) + ((fullBounds.left - rootOffset.x) - (iconBounds.left - rootOffset.x)) * t;
        float posY = (iconBounds.top - rootOffset.y) + ((fullBounds.top - rootOffset.y) - (iconBounds.top - rootOffset.y)) * t - (cropTop * scale);

        applyPosition(tx, leash, posX, posY);
        applyMatrix(tx, leash, scale, scale);
        applyWindowCrop(tx, leash, new Rect(0, cropTop, fullW, cropTop + cropH));

        float screenRadiusPx = 28f * context.getResources().getDisplayMetrics().density;
        float desiredScreenRadius = iconCornerRadiusPx + (screenRadiusPx - iconCornerRadiusPx) * t;
        float surfaceRadius = Math.min(desiredScreenRadius / Math.max(0.05f, scale), fullW * 0.5f);
        applyCornerRadius(tx, leash, surfaceRadius);

        tx.setAlpha(leash, Math.max(0f, Math.min(1f, alpha)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            tx.setVisibility(leash, true);
        }
        tx.setLayer(leash, PRIMARY_LAYER);
    }

    private static void applyPosition(SurfaceControl.Transaction tx, SurfaceControl leash, float x, float y) {
        ensureSurfaceHiddenMethods();
        if (txSetPositionMethod != null) {
            try {
                txSetPositionMethod.invoke(tx, leash, x, y);
                return;
            } catch (Throwable ignored) {
                txSetPositionMethod = null;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tx.setPosition(leash, x, y);
        }
    }

    private static void applyMatrix(SurfaceControl.Transaction tx, SurfaceControl leash, float sx, float sy) {
        ensureSurfaceHiddenMethods();
        if (txSetMatrixMethod != null) {
            try {
                txSetMatrixMethod.invoke(tx, leash, sx, 0f, 0f, sy);
                return;
            } catch (Throwable ignored) {
                txSetMatrixMethod = null;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tx.setScale(leash, sx, sy);
        }
    }

    private static void applyWindowCrop(SurfaceControl.Transaction tx, SurfaceControl leash, Rect crop) {
        ensureSurfaceHiddenMethods();
        if (txSetWindowCropMethod != null) {
            try {
                txSetWindowCropMethod.invoke(tx, leash, crop);
                return;
            } catch (Throwable ignored) {
                txSetWindowCropMethod = null;
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tx.setCrop(leash, crop);
        }
    }

    private static void applyCornerRadius(SurfaceControl.Transaction tx, SurfaceControl leash, float radius) {
        ensureSurfaceHiddenMethods();
        if (txSetCornerRadiusMethod != null) {
            try {
                txSetCornerRadiusMethod.invoke(tx, leash, Math.max(0f, radius));
            } catch (Throwable ignored) {
                txSetCornerRadiusMethod = null;
            }
        }
    }

    private static void ensureSurfaceHiddenMethods() {
        if (surfaceHiddenChecked) {
            return;
        }
        surfaceHiddenChecked = true;
        try {
            txSetPositionMethod = SurfaceControl.Transaction.class.getMethod(
                    "setPosition", SurfaceControl.class, float.class, float.class);
        } catch (Throwable ignored) {
        }
        try {
            txSetCornerRadiusMethod = SurfaceControl.Transaction.class.getMethod(
                    "setCornerRadius", SurfaceControl.class, float.class);
        } catch (Throwable ignored) {
        }
        try {
            txSetWindowCropMethod = SurfaceControl.Transaction.class.getMethod(
                    "setWindowCrop", SurfaceControl.class, Rect.class);
        } catch (Throwable ignored) {
        }
        try {
            txSetMatrixMethod = SurfaceControl.Transaction.class.getMethod(
                    "setMatrix", SurfaceControl.class, float.class, float.class, float.class, float.class);
        } catch (Throwable ignored) {
        }
    }

    private static final class ActiveSurfaceAnimator implements Choreographer.FrameCallback {
        final Context context;
        final boolean isOpening;
        final SurfaceControl primaryLeash;
        final Rect fullBounds;
        final SurfaceControl secondaryLeash;
        final Rect secondaryBounds;
        final Point rootOffset;
        final Rect iconBounds;
        final float iconCornerRadiusPx;
        final TransitionProgressListener listener;
        final Runnable onFinishedCallback;
        final AtomicBoolean finished = new AtomicBoolean(false);
        final Runnable watchdog;

        float position;
        float target;
        float velocity;
        long lastFrameTimeNanos = -1L;

        ActiveSurfaceAnimator(Context context,
                              boolean isOpening,
                              SurfaceControl primaryLeash,
                              Rect fullBounds,
                              SurfaceControl secondaryLeash,
                              Rect secondaryBounds,
                              Point rootOffset,
                              Rect iconBounds,
                              float iconCornerRadiusPx,
                              TransitionProgressListener listener,
                              Runnable onFinishedCallback) {
            this.context = context.getApplicationContext();
            this.isOpening = isOpening;
            this.primaryLeash = primaryLeash;
            this.fullBounds = new Rect(fullBounds);
            this.secondaryLeash = secondaryLeash;
            this.secondaryBounds = secondaryBounds != null ? new Rect(secondaryBounds) : null;
            this.rootOffset = new Point(rootOffset);
            this.iconBounds = new Rect(iconBounds);
            this.iconCornerRadiusPx = iconCornerRadiusPx;
            this.listener = listener;
            this.onFinishedCallback = onFinishedCallback;
            this.position = isOpening ? 0f : 1f;
            this.target = isOpening ? 1f : 0f;
            this.velocity = 0f;
            this.watchdog = this::finish;
        }

        void start() {
            if (listener != null) {
                listener.onTransitionStart(isOpening);
            }
            MAIN_HANDLER.postDelayed(watchdog, WATCHDOG_TIMEOUT_MS);
            applyFrame(position);
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (finished.get()) {
                return;
            }
            if (lastFrameTimeNanos < 0L) {
                lastFrameTimeNanos = frameTimeNanos;
                Choreographer.getInstance().postFrameCallback(this);
                return;
            }

            float dt = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000f;
            lastFrameTimeNanos = frameTimeNanos;
            dt = Math.max(0.001f, Math.min(0.032f, dt));

            float stiffness = isOpening ? LAUNCH_STIFFNESS : RETURN_STIFFNESS;
            float dampingRatio = isOpening ? LAUNCH_DAMPING : RETURN_DAMPING;
            float damping = 2f * dampingRatio * (float) Math.sqrt(stiffness);

            // Sub-step physics at 500Hz for frame-rate independent 60/90/120/144Hz stability
            int steps = Math.max(1, (int) Math.ceil(dt / 0.002f));
            float subDt = dt / steps;
            for (int i = 0; i < steps; i++) {
                float accel = -stiffness * (position - target) - damping * velocity;
                velocity += accel * subDt;
                position += velocity * subDt;
            }

            applyFrame(position);

            if (listener != null) {
                listener.onTransitionProgress(Math.max(0f, Math.min(1f, position)), isOpening);
            }

            if (Math.abs(position - target) < 0.002f && Math.abs(velocity) < 0.01f) {
                finish();
            } else {
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private void applyFrame(float rawProgress) {
            float t = Math.max(0f, Math.min(1f, rawProgress));
            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                if (primaryLeash != null && primaryLeash.isValid()) {
                    float alpha = isOpening
                            ? Math.min(1f, t * 4.5f)
                            : Math.min(1f, t * 3.5f);
                    applyMorphFrame(context, tx, primaryLeash, iconBounds, fullBounds,
                            rootOffset, iconCornerRadiusPx, t, alpha);
                }

                if (secondaryLeash != null && secondaryLeash.isValid() && secondaryBounds != null) {
                    float bgScale = 1f - 0.05f * t;
                    float bgAlpha = 1f - 0.15f * t;
                    int bw = secondaryBounds.width();
                    int bh = secondaryBounds.height();
                    float txPos = (secondaryBounds.left - rootOffset.x) + bw * (1f - bgScale) * 0.5f;
                    float tyPos = (secondaryBounds.top - rootOffset.y) + bh * (1f - bgScale) * 0.5f;
                    applyPosition(tx, secondaryLeash, txPos, tyPos);
                    applyMatrix(tx, secondaryLeash, bgScale, bgScale);
                    tx.setLayer(secondaryLeash, SECONDARY_LAYER);
                    tx.setAlpha(secondaryLeash, Math.max(0f, Math.min(1f, bgAlpha)));
                }
                tx.apply();
            } catch (Throwable tErr) {
                Log.w(TAG, "applyFrame error, finishing early", tErr);
                finish();
            }
        }

        void cancel() {
            finish();
        }

        void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            Choreographer.getInstance().removeFrameCallback(this);
            MAIN_HANDLER.removeCallbacks(watchdog);
            if (activeAnimator == this) {
                activeAnimator = null;
            }

            try (SurfaceControl.Transaction tx = new SurfaceControl.Transaction()) {
                if (primaryLeash != null && primaryLeash.isValid()) {
                    if (isOpening) {
                        applyPosition(tx, primaryLeash,
                                fullBounds.left - rootOffset.x,
                                fullBounds.top - rootOffset.y);
                        applyMatrix(tx, primaryLeash, 1f, 1f);
                        applyWindowCrop(tx, primaryLeash, new Rect(0, 0, fullBounds.width(), fullBounds.height()));
                        applyCornerRadius(tx, primaryLeash, 0f);
                        tx.setAlpha(primaryLeash, 1f);
                    } else {
                        tx.setAlpha(primaryLeash, 0f);
                    }
                }
                if (secondaryLeash != null && secondaryLeash.isValid() && secondaryBounds != null) {
                    applyPosition(tx, secondaryLeash,
                            secondaryBounds.left - rootOffset.x,
                            secondaryBounds.top - rootOffset.y);
                    applyMatrix(tx, secondaryLeash, 1f, 1f);
                    tx.setAlpha(secondaryLeash, 1f);
                }
                tx.apply();
            } catch (Throwable ignored) {
            }

            if (listener != null) {
                listener.onTransitionEnd(isOpening);
            }

            if (onFinishedCallback != null) {
                onFinishedCallback.run();
            }
        }
    }

    private static Object instantiateRemoteTransition(Class<?> remoteTransitionClass,
                                                      Class<?> iRemoteTransitionClass,
                                                      Object proxy) {
        try {
            return remoteTransitionClass.getConstructor(iRemoteTransitionClass).newInstance(proxy);
        } catch (Throwable ignored) {
        }
        try {
            Class<?> appThreadClass = Class.forName("android.app.IApplicationThread");
            return remoteTransitionClass.getConstructor(iRemoteTransitionClass, appThreadClass, String.class)
                    .newInstance(proxy, null, "StarioLaunch");
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Method findAddRemoteAnimationMethod(Class<?> defClass, Class<?> adapterClass) {
        try {
            return defClass.getMethod("addRemoteAnimation", int.class, adapterClass);
        } catch (NoSuchMethodException ignored) {
            try {
                return defClass.getMethod("addRemoteAnimation", int.class, int.class, adapterClass);
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    }

    private static void invokeAddRemoteAnimation(Method method,
                                                 Object definition,
                                                 int transition,
                                                 Object adapter) throws Exception {
        if (method.getParameterCount() == 2) {
            method.invoke(definition, transition, adapter);
        } else {
            method.invoke(definition, transition, 0, adapter);
        }
    }

    private static int getWindowManagerIntConst(String name, int fallback) {
        try {
            Field f = WindowManager.class.getField(name);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String getStaticStringField(Class<?> cls, String name, String fallback) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            Object v = f.get(null);
            return v instanceof String ? (String) v : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int getStaticIntField(Class<?> cls, String name, int fallback) {
        try {
            Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            return f.getInt(null);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Method findMethodByName(Class<?> cls, String name) {
        for (Method m : cls.getMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        for (Method m : cls.getDeclaredMethods()) {
            if (name.equals(m.getName())) {
                return m;
            }
        }
        return null;
    }

    private static Object[] readAidlParams(Parcel data, Class<?>[] paramTypes) {
        Object[] result = new Object[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> type = paramTypes[i];
            try {
                if (type == int.class) {
                    result[i] = data.readInt();
                } else if (type == long.class) {
                    result[i] = data.readLong();
                } else if (type == boolean.class) {
                    result[i] = data.readInt() != 0;
                } else if (type == IBinder.class) {
                    result[i] = data.readStrongBinder();
                } else if (IInterface.class.isAssignableFrom(type)) {
                    IBinder binder = data.readStrongBinder();
                    if (binder != null) {
                        Class<?> stubCls = Class.forName(type.getName() + "$Stub");
                        Method asInterface = stubCls.getMethod("asInterface", IBinder.class);
                        result[i] = asInterface.invoke(null, binder);
                    }
                } else if (type.isArray() && type.getComponentType() != null
                        && Parcelable.class.isAssignableFrom(type.getComponentType())) {
                    Parcelable.Creator<?> creator = getCreator(type.getComponentType());
                    result[i] = creator != null ? data.createTypedArray(creator) : null;
                } else if (Parcelable.class.isAssignableFrom(type)) {
                    Parcelable.Creator<?> creator = getCreator(type);
                    if (creator != null && data.readInt() != 0) {
                        result[i] = creator.createFromParcel(data);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "readAidlParams failed at index " + i + " (" + type.getName() + "): " + t.getMessage());
            }
        }
        return result;
    }

    private static Parcelable.Creator<?> getCreator(Class<?> cls) {
        try {
            Field f = cls.getField("CREATOR");
            return (Parcelable.Creator<?>) f.get(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void ackOnewayFallback(int flags, Parcel reply) {
        if ((flags & IBinder.FLAG_ONEWAY) == 0 && reply != null) {
            reply.writeNoException();
        }
    }

    private static Object findLastInterfaceArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i] instanceof IInterface) {
                return args[i];
            }
        }
        return null;
    }

    private static void finishRemoteAnimationCallback(Object callback) {
        if (callback == null) {
            return;
        }
        try {
            Method m = callback.getClass().getMethod("onAnimationFinished");
            m.invoke(callback);
        } catch (Throwable t) {
            Log.w(TAG, "finishRemoteAnimationCallback failed: " + t.getMessage());
        }
    }

    private static void finishRemoteTransitionCallback(Object callback) {
        if (callback == null) {
            return;
        }
        try {
            Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
            Method m = callback.getClass().getMethod(
                    "onTransitionFinished", wctClass, SurfaceControl.Transaction.class);
            m.invoke(callback, null, null);
        } catch (Throwable t) {
            Log.w(TAG, "finishRemoteTransitionCallback failed: " + t.getMessage());
        }
    }

    private static Rect extractTargetBounds(Object target) {
        Rect screenSpace = (Rect) getField(target, "screenSpaceBounds");
        if (screenSpace != null && screenSpace.width() > 0 && screenSpace.height() > 0) {
            return new Rect(screenSpace);
        }
        Rect sourceContainer = (Rect) getField(target, "sourceContainerBounds");
        if (sourceContainer != null && sourceContainer.width() > 0 && sourceContainer.height() > 0) {
            return new Rect(sourceContainer);
        }
        Rect clip = (Rect) getField(target, "clipRect");
        Point pos = (Point) getField(target, "position");
        if (clip != null && clip.width() > 0 && clip.height() > 0) {
            Rect r = new Rect(clip);
            if (pos != null) {
                r.offsetTo(pos.x, pos.y);
            }
            return r;
        }
        return null;
    }

    private static boolean isHomeTarget(Object target) {
        try {
            Object wc = getField(target, "windowConfiguration");
            if (wc != null) {
                int activityType = (int) wc.getClass().getMethod("getActivityType").invoke(wc);
                return activityType == ACTIVITY_TYPE_HOME;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static ComponentName extractTargetComponent(Object target) {
        try {
            Object taskInfo = getField(target, "taskInfo");
            if (taskInfo != null) {
                Object topActivity = getField(taskInfo, "topActivity");
                if (topActivity instanceof ComponentName) {
                    return (ComponentName) topActivity;
                }
                Object realActivity = getField(taskInfo, "realActivity");
                if (realActivity instanceof ComponentName) {
                    return (ComponentName) realActivity;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object getField(Object obj, String name) {
        if (obj == null) {
            return null;
        }
        try {
            Field f = obj.getClass().getField(name);
            return f.get(obj);
        } catch (Throwable ignored) {
            try {
                Field f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    private static int getIntField(Object obj, String name, int fallback) {
        Object val = getField(obj, name);
        return val instanceof Integer ? (Integer) val : fallback;
    }

    private static Object defaultValueForReturn(Class<?> returnType) {
        if (returnType == boolean.class) return false;
        if (returnType == int.class) return 0;
        if (returnType == long.class) return 0L;
        if (returnType == float.class) return 0f;
        if (returnType == double.class) return 0d;
        return null;
    }
}
