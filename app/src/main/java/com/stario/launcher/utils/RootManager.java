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

package com.stario.launcher.utils;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.stario.launcher.Stario;
import com.stario.launcher.hidden.RemoteTransitionHelper;
import com.stario.launcher.preferences.Entry;
import com.stario.launcher.ui.utils.UiUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class RootManager {
    private static final String TAG = "RootManager";

    public static final String PREF_ROOT_MODE = "com.stario.ROOT_MODE";
    public static final String PREF_ROOT_SCREEN_LOCK = "com.stario.ROOT_SCREEN_LOCK";
    public static final String PREF_ROOT_REMOTE_TRANSITIONS = "com.stario.ROOT_REMOTE_TRANSITIONS";

    public static final String PERMISSION_CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS =
            RemoteTransitionHelper.PERMISSION_CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS;

    private static final String MODULE_ID = "stario_privapp";
    private static final String MODULES_BASE_DIR = "/data/adb/modules";
    private static final String MODULE_DIR = MODULES_BASE_DIR + "/" + MODULE_ID;
    private static final long SU_TIMEOUT_SECONDS = 5L;
    private static final Pattern SAFE_PACKAGE_PATTERN = Pattern.compile("^[a-zA-Z0-9_.]+$");

    public enum RootProvider {
        NONE,
        MAGISK,
        KERNELSU,
        APATCH,
        GENERIC_SU
    }

    public enum ModuleState {
        UNSUPPORTED,
        NOT_INSTALLED,
        PENDING_REBOOT_INSTALL,
        ACTIVE,
        PENDING_REBOOT_REMOVE,
        PRIVILEGED_EXTERNAL
    }

    public static final class RootStatus {
        public final boolean rooted;
        public final RootProvider provider;
        public final boolean privilegedPermissionGranted;
        public final boolean installedAsPrivApp;
        public final ModuleState moduleState;

        public RootStatus(boolean rooted,
                          RootProvider provider,
                          boolean privilegedPermissionGranted,
                          boolean installedAsPrivApp,
                          ModuleState moduleState) {
            this.rooted = rooted;
            this.provider = provider;
            this.privilegedPermissionGranted = privilegedPermissionGranted;
            this.installedAsPrivApp = installedAsPrivApp;
            this.moduleState = moduleState;
        }
    }

    public static final class ActionResult {
        public final boolean success;
        public final String message;

        public ActionResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    public interface Callback<T> {
        void onResult(T result);
    }

    private static volatile RootManager instance;

    private final Context appContext;
    private final SharedPreferences rootPrefs;
    private final ExecutorService executor;
    private volatile RootStatus cachedStatus;

    private RootManager(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.rootPrefs = getRootPreferences(this.appContext);
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Stario-Root");
            t.setDaemon(true);
            return t;
        });
    }

    public static RootManager getInstance(@NonNull Context context) {
        RootManager current = instance;
        if (current == null) {
            synchronized (RootManager.class) {
                current = instance;
                if (current == null) {
                    current = new RootManager(context);
                    instance = current;
                }
            }
        }
        return current;
    }

    private static SharedPreferences getRootPreferences(@NonNull Context context) {
        if (context instanceof Stario) {
            return ((Stario) context).getSharedPreferences(Entry.ROOT);
        }
        return context.getSharedPreferences(Entry.ROOT.toString(), Context.MODE_PRIVATE);
    }

    public boolean isRootModeEnabled() {
        return rootPrefs.getBoolean(PREF_ROOT_MODE, false);
    }

    public void setRootModeEnabled(boolean enabled) {
        rootPrefs.edit().putBoolean(PREF_ROOT_MODE, enabled).apply();
    }

    public boolean isRootScreenLockEnabled() {
        return isRootModeEnabled() && rootPrefs.getBoolean(PREF_ROOT_SCREEN_LOCK, false);
    }

    public void setRootScreenLockEnabled(boolean enabled) {
        rootPrefs.edit().putBoolean(PREF_ROOT_SCREEN_LOCK, enabled).apply();
    }

    public boolean isRemoteTransitionsEnabled() {
        if (!hasRemoteTransitionPermission()) {
            return false;
        }
        // Enable by default once privileged permission is actively granted, unless user disabled it
        return rootPrefs.getBoolean(PREF_ROOT_REMOTE_TRANSITIONS, true);
    }

    public void setRemoteTransitionsEnabled(boolean enabled) {
        rootPrefs.edit().putBoolean(PREF_ROOT_REMOTE_TRANSITIONS, enabled).apply();
    }

    public boolean hasRemoteTransitionPermission() {
        try {
            return appContext.checkSelfPermission(PERMISSION_CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public boolean isInstalledAsPrivApp() {
        try {
            ApplicationInfo info = appContext.getApplicationInfo();
            String sourceDir = info.sourceDir;
            return sourceDir != null && (sourceDir.startsWith("/system/priv-app/")
                    || sourceDir.startsWith("/system_ext/priv-app/")
                    || sourceDir.startsWith("/product/priv-app/")
                    || sourceDir.startsWith("/vendor/priv-app/"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Nullable
    public RootStatus getCachedStatus() {
        return cachedStatus;
    }

    public void refreshStatusAsync(@Nullable Callback<RootStatus> callback) {
        executor.execute(() -> {
            RootStatus status = queryStatusSync();
            cachedStatus = status;
            if (callback != null) {
                UiUtils.post(() -> callback.onResult(status));
            }
        });
    }

    @NonNull
    public RootStatus queryStatusSync() {
        boolean privPerm = hasRemoteTransitionPermission();
        boolean privApp = isInstalledAsPrivApp();

        CommandResult idRes = runSuCommand("id");
        boolean rooted = idRes.exitCode == 0 && idRes.output.contains("uid=0");

        if (!rooted) {
            ModuleState state = privPerm ? ModuleState.PRIVILEGED_EXTERNAL : ModuleState.UNSUPPORTED;
            return new RootStatus(false, RootProvider.NONE, privPerm, privApp, state);
        }

        CommandResult probeRes = runSuCommand(
                "if [ -d /data/adb/ksu ]; then echo KSU; " +
                        "elif [ -d /data/adb/ap ]; then echo AP; " +
                        "elif command -v magisk >/dev/null 2>&1 || [ -d /data/adb/magisk ]; then echo MAGISK; " +
                        "else echo SU; fi; " +
                        "if [ -d " + MODULES_BASE_DIR + " ]; then echo MOD_BASE=1; else echo MOD_BASE=0; fi; " +
                        "if [ -d " + MODULE_DIR + " ]; then echo MOD_DIR=1; else echo MOD_DIR=0; fi; " +
                        "if [ -f " + MODULE_DIR + "/remove ]; then echo MOD_RM=1; else echo MOD_RM=0; fi"
        );

        RootProvider provider = RootProvider.GENERIC_SU;
        boolean hasModulesBase = false;
        boolean hasModuleDir = false;
        boolean hasRemoveMarker = false;

        if (probeRes.exitCode == 0) {
            for (String line : probeRes.output.split("\n")) {
                String trimmed = line.trim();
                switch (trimmed) {
                    case "KSU":
                        provider = RootProvider.KERNELSU;
                        break;
                    case "AP":
                        provider = RootProvider.APATCH;
                        break;
                    case "MAGISK":
                        provider = RootProvider.MAGISK;
                        break;
                    case "MOD_BASE=1":
                        hasModulesBase = true;
                        break;
                    case "MOD_DIR=1":
                        hasModuleDir = true;
                        break;
                    case "MOD_RM=1":
                        hasRemoveMarker = true;
                        break;
                    default:
                        break;
                }
            }
        }

        ModuleState moduleState;
        if (!hasModulesBase && !hasModuleDir) {
            moduleState = privPerm ? ModuleState.PRIVILEGED_EXTERNAL : ModuleState.UNSUPPORTED;
        } else if (hasModuleDir) {
            if (hasRemoveMarker) {
                moduleState = ModuleState.PENDING_REBOOT_REMOVE;
            } else if (privPerm) {
                moduleState = ModuleState.ACTIVE;
            } else {
                moduleState = ModuleState.PENDING_REBOOT_INSTALL;
            }
        } else {
            moduleState = privPerm ? ModuleState.PRIVILEGED_EXTERNAL : ModuleState.NOT_INSTALLED;
        }

        return new RootStatus(true, provider, privPerm, privApp, moduleState);
    }

    public void verifyRootAccessAsync(@NonNull Callback<Boolean> callback) {
        executor.execute(() -> {
            CommandResult res = runSuCommand("id");
            boolean rooted = res.exitCode == 0 && res.output.contains("uid=0");
            if (!rooted) {
                setRootModeEnabled(false);
            }
            cachedStatus = queryStatusSync();
            UiUtils.post(() -> callback.onResult(rooted));
        });
    }

    public void lockScreenAsync(@Nullable Callback<Boolean> callback) {
        executor.execute(() -> {
            CommandResult res = runSuCommand("input keyevent 223 || input keyevent 26");
            boolean ok = res.exitCode == 0;
            if (callback != null) {
                UiUtils.post(() -> callback.onResult(ok));
            }
        });
    }

    public void forceStopPackageAsync(@NonNull String packageName, @Nullable Callback<Boolean> callback) {
        if (!SAFE_PACKAGE_PATTERN.matcher(packageName).matches()) {
            if (callback != null) {
                UiUtils.post(() -> callback.onResult(false));
            }
            return;
        }
        executor.execute(() -> {
            CommandResult res = runSuCommand("am force-stop " + packageName);
            boolean ok = res.exitCode == 0;
            if (callback != null) {
                UiUtils.post(() -> callback.onResult(ok));
            }
        });
    }

    public void rebootAsync(@Nullable Callback<Boolean> callback) {
        executor.execute(() -> {
            CommandResult res = runSuCommand("svc power reboot || reboot");
            boolean ok = res.exitCode == 0;
            if (callback != null) {
                UiUtils.post(() -> callback.onResult(ok));
            }
        });
    }

    public void installSystemizeModuleAsync(@NonNull Callback<ActionResult> callback) {
        executor.execute(() -> {
            ActionResult result = installSystemizeModuleSync();
            cachedStatus = queryStatusSync();
            UiUtils.post(() -> callback.onResult(result));
        });
    }

    @NonNull
    private ActionResult installSystemizeModuleSync() {
        CommandResult rootCheck = runSuCommand("id");
        if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
            return new ActionResult(false, "Root access unavailable or denied.");
        }

        ApplicationInfo appInfo = appContext.getApplicationInfo();
        String apkPath = appInfo.sourceDir;
        if (apkPath == null || apkPath.isEmpty()) {
            return new ActionResult(false, "Could not resolve Stario APK path.");
        }

        File stageDir = new File(appContext.getCacheDir(), MODULE_ID + "_stage");
        deleteRecursive(stageDir);
        if (!stageDir.mkdirs()) {
            return new ActionResult(false, "Failed to create staging directory.");
        }

        try {
            String packageName = appContext.getPackageName();
            String moduleProp = "id=" + MODULE_ID + "\n"
                    + "name=Stario Privileged Mode\n"
                    + "version=v1.0\n"
                    + "versionCode=1\n"
                    + "author=Stario\n"
                    + "description=Systemizes Stario as a privileged app for native RemoteTransition and RemoteAnimation support.\n";
            writeStringFile(new File(stageDir, "module.prop"), moduleProp);

            String permissionsXml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<permissions>\n"
                    + "    <privapp-permissions package=\"" + packageName + "\">\n"
                    + "        <permission name=\"" + PERMISSION_CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS + "\"/>\n"
                    + "    </privapp-permissions>\n"
                    + "</permissions>\n";
            String xmlFileName = "privapp-permissions-" + packageName + ".xml";
            writeStringFile(new File(stageDir, xmlFileName), permissionsXml);

            String stagedProp = quoteShellArg(new File(stageDir, "module.prop").getAbsolutePath());
            String stagedXml = quoteShellArg(new File(stageDir, xmlFileName).getAbsolutePath());
            String quotedApk = quoteShellArg(apkPath);
            String quotedXmlTarget = quoteShellArg(MODULE_DIR + "/system/etc/permissions/" + xmlFileName);
            String targetApk = quoteShellArg(MODULE_DIR + "/system/priv-app/Stario/Stario.apk");

            StringBuilder cmd = new StringBuilder();
            cmd.append("if [ ! -d ").append(MODULES_BASE_DIR).append(" ]; then echo NO_MODULES_DIR; exit 2; fi; ");
            cmd.append("rm -f ").append(MODULE_DIR).append("/remove ").append(MODULE_DIR).append("/disable; ");
            cmd.append("mkdir -p ").append(MODULE_DIR).append("/system/priv-app/Stario ");
            cmd.append(MODULE_DIR).append("/system/etc/permissions && ");
            cmd.append("cp -f ").append(stagedProp).append(" ").append(MODULE_DIR).append("/module.prop && ");
            cmd.append("cp -f ").append(stagedXml).append(" ").append(quotedXmlTarget).append(" && ");
            cmd.append("cp -f ").append(quotedApk).append(" ").append(targetApk).append(" && ");

            // Copy native libraries if present
            if (appInfo.nativeLibraryDir != null) {
                File nativeDir = new File(appInfo.nativeLibraryDir);
                File[] libs = nativeDir.listFiles((dir, name) -> name.endsWith(".so"));
                if (libs != null && libs.length > 0) {
                    String abi = nativeDir.getName();
                    if (SAFE_PACKAGE_PATTERN.matcher(abi).matches()) {
                        String libTargetDir = MODULE_DIR + "/system/priv-app/Stario/lib/" + abi;
                        cmd.append("mkdir -p ").append(quoteShellArg(libTargetDir)).append(" && ");
                        cmd.append("cp -f ").append(quoteShellArg(nativeDir.getAbsolutePath())).append("/*.so ")
                                .append(quoteShellArg(libTargetDir)).append("/ && ");
                    }
                }
            }

            cmd.append("chmod 0755 ").append(MODULE_DIR).append(" && ");
            cmd.append("chmod 0644 ").append(MODULE_DIR).append("/module.prop && ");
            cmd.append("chmod -R 0755 ").append(MODULE_DIR).append("/system && ");
            cmd.append("chmod 0644 ").append(targetApk).append(" ").append(quotedXmlTarget).append(" && ");
            cmd.append("(chcon -R u:object_r:system_file:s0 ").append(MODULE_DIR).append("/system 2>/dev/null || true)");

            CommandResult res = runSuCommand(cmd.toString());
            if (res.exitCode == 2 || res.output.contains("NO_MODULES_DIR")) {
                return new ActionResult(false, "Magisk, KernelSU, or APatch modules directory (/data/adb/modules) not found.");
            }
            if (res.exitCode != 0) {
                return new ActionResult(false, "Module installation failed: " + res.output.trim());
            }

            setRootModeEnabled(true);
            setRemoteTransitionsEnabled(true);
            return new ActionResult(true, "Systemize module installed. Reboot your device to apply.");
        } catch (IOException e) {
            Log.e(TAG, "installSystemizeModuleSync failed", e);
            return new ActionResult(false, "Failed to stage module files: " + e.getMessage());
        } finally {
            deleteRecursive(stageDir);
        }
    }

    public void uninstallSystemizeModuleAsync(@NonNull Callback<ActionResult> callback) {
        executor.execute(() -> {
            ActionResult result = uninstallSystemizeModuleSync();
            cachedStatus = queryStatusSync();
            UiUtils.post(() -> callback.onResult(result));
        });
    }

    @NonNull
    private ActionResult uninstallSystemizeModuleSync() {
        CommandResult rootCheck = runSuCommand("id");
        if (rootCheck.exitCode != 0 || !rootCheck.output.contains("uid=0")) {
            return new ActionResult(false, "Root access unavailable or denied.");
        }

        // If module is actively mounted as priv-app, mark for safe removal on next reboot
        // per Magisk/KernelSU/APatch specification; otherwise delete immediately.
        boolean mounted = isInstalledAsPrivApp() || hasRemoteTransitionPermission();
        String cmd = mounted
                ? "if [ -d " + MODULE_DIR + " ]; then touch " + MODULE_DIR + "/remove; fi"
                : "rm -rf " + MODULE_DIR;

        CommandResult res = runSuCommand(cmd);
        if (res.exitCode != 0) {
            return new ActionResult(false, "Failed to remove module: " + res.output.trim());
        }

        return new ActionResult(true, mounted
                ? "Module marked for removal. Reboot to complete uninstall."
                : "Systemize module removed.");
    }

    private CommandResult runSuCommand(@NonNull String command) {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append('\n');
                }
            }

            boolean completed = process.waitFor(SU_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new CommandResult(-1, "Timed out waiting for su.");
            }
            return new CommandResult(process.exitValue(), output.toString());
        } catch (IOException e) {
            return new CommandResult(-1, e.getMessage() != null ? e.getMessage() : "su not found");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CommandResult(-1, "Interrupted");
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void writeStringFile(File file, String content) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void deleteRecursive(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursive(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static String quoteShellArg(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final class CommandResult {
        final int exitCode;
        final String output;

        CommandResult(int exitCode, String output) {
            this.exitCode = exitCode;
            this.output = output != null ? output : "";
        }
    }
}
