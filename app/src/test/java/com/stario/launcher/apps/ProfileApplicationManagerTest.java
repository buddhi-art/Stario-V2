package com.stario.launcher.apps;

import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.os.UserHandle;

import com.stario.launcher.Stario;
import com.stario.launcher.preferences.Entry;
import com.stario.launcher.testutil.AndroidStubsHelper;
import com.stario.launcher.testutil.FakeSharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ProfileApplicationManagerTest {
    private FakeSharedPreferences hiddenPrefs;
    private FakeSharedPreferences labelsPrefs;
    private LauncherApps mockLauncherApps;
    private Stario mockStario;
    private UserHandle ownerHandle;
    private final List<ProfileApplicationManager> createdManagers = new ArrayList<>();

    @BeforeClass
    public static void initAndroidStubs() {
        AndroidStubsHelper.ensureInitialized();
    }

    @Before
    public void setUp() throws Exception {
        AndroidStubsHelper.clearUiCallbacks();

        ownerHandle = mock(UserHandle.class);
        labelsPrefs = new FakeSharedPreferences();
        hiddenPrefs = new FakeSharedPreferences();

        PackageManager mockPackageManager = mock(PackageManager.class);
        mockLauncherApps = mock(LauncherApps.class);

        mockStario = mock(Stario.class);
        when(mockStario.getPackageManager()).thenReturn(mockPackageManager);
        when(mockStario.getSystemService(LauncherApps.class)).thenReturn(mockLauncherApps);
        when(mockStario.getSharedPreferences(Entry.APPLICATION_LABELS)).thenReturn(labelsPrefs);
        when(mockStario.getSharedPreferences(Entry.HIDDEN_APPS,
                Integer.toString(ownerHandle.hashCode()))).thenReturn(hiddenPrefs);

        ProfileManager mockProfileManager = mock(ProfileManager.class);
        setStaticField(ProfileManager.class, "instance", mockProfileManager);
        setStaticField(ProfileManager.class, "owner", ownerHandle);

        CategoryManager mockCategoryManager = mock(CategoryManager.class);
        when(mockCategoryManager.getCategoryIdentifier(any(), any()))
                .thenReturn(UUID.randomUUID());
        setStaticField(CategoryManager.class, "instance", mockCategoryManager);

        IconPackManager mockIconPacks = mock(IconPackManager.class);
        when(mockIconPacks.checkPackValidity(anyString())).thenReturn(false);
        setStaticField(IconPackManager.class, "instance", mockIconPacks);
    }

    @After
    public void tearDown() throws Exception {
        for (ProfileApplicationManager manager : createdManagers) {
            manager.destroy();
        }
        createdManagers.clear();
        AndroidStubsHelper.clearUiCallbacks();

        setStaticField(IconPackManager.class, "instance", null);
        setStaticField(CategoryManager.class, "instance", null);
        setStaticField(ProfileManager.class, "instance", null);
        setStaticField(ProfileManager.class, "owner", null);
    }

    private static void setStaticField(Class<?> clazz, String name, Object value) throws Exception {
        Field field = clazz.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static LauncherActivityInfo mockActivityInfo(String pkg, String label) {
        ApplicationInfo appInfo = mock(ApplicationInfo.class);
        appInfo.packageName = pkg;
        appInfo.flags = 0;
        appInfo.enabled = true;
        when(appInfo.loadLabel(any(PackageManager.class))).thenReturn(label);

        LauncherActivityInfo activityInfo = mock(LauncherActivityInfo.class);
        when(activityInfo.getApplicationInfo()).thenReturn(appInfo);
        return activityInfo;
    }

    private ProfileApplicationManager createAndAwaitReady(List<LauncherActivityInfo> activities)
            throws InterruptedException {
        when(mockLauncherApps.getActivityList(null, ownerHandle)).thenReturn(activities);

        ProfileApplicationManager manager = new ProfileApplicationManager(mockStario, ownerHandle);
        createdManagers.add(manager);

        CountDownLatch latch = new CountDownLatch(1);
        manager.addOnReadyListener(m -> latch.countDown());

        long deadline = System.currentTimeMillis() + 5_000L;
        while (!manager.isReady() || latch.getCount() > 0) {
            AndroidStubsHelper.drainUiCallbacks();
            if (System.currentTimeMillis() > deadline) {
                fail("Timed out waiting for ProfileApplicationManager to finish loading");
            }
            Thread.sleep(10L);
        }
        return manager;
    }

    @Test
    public void get_onEmptyManager_returnsFallbackAtBoundaries() throws InterruptedException {
        ProfileApplicationManager manager = createAndAwaitReady(Collections.emptyList());

        assertTrue(manager.isReady());
        assertEquals(0, manager.getSize());
        assertEquals(0, manager.getActualSize());

        assertNull(manager.get(-1));
        assertNull(manager.get(0));
        assertNull(manager.get(5));
        assertNull(manager.get(Integer.MIN_VALUE));
        assertNull(manager.get(Integer.MAX_VALUE));

        assertNull(manager.get(-1, true));
        assertNull(manager.get(0, true));
        assertNull(manager.get("com.example.missing"));
    }

    @Test
    public void get_withVisibleAndHiddenApps_enforcesBoundsAndSeparatesLists()
            throws InterruptedException {
        hiddenPrefs.edit().putBoolean("com.example.hidden", true).apply();

        List<LauncherActivityInfo> activities = List.of(
                mockActivityInfo("com.example.zeta", "Zeta"),
                mockActivityInfo("com.example.hidden", "Hidden"),
                mockActivityInfo("com.example.alpha", "Alpha")
        );

        ProfileApplicationManager manager = createAndAwaitReady(activities);

        assertEquals(2, manager.getSize());
        assertEquals(3, manager.getActualSize());

        // Visible list boundaries
        assertNull(manager.get(-1));
        assertNull(manager.get(manager.getSize()));
        assertEquals("Alpha", manager.get(0).getLabel());
        assertEquals("Zeta", manager.get(1).getLabel());

        // Full (including hidden) list boundaries
        assertNull(manager.get(-1, true));
        assertNull(manager.get(manager.getActualSize(), true));
        assertEquals("Alpha", manager.get(0, true).getLabel());
        assertEquals("Hidden", manager.get(1, true).getLabel());
        assertEquals("Zeta", manager.get(2, true).getLabel());

        // Package lookup boundaries
        assertSame(manager.get(0), manager.get("com.example.alpha"));
        assertNull(manager.get("com.example.unknown"));
    }

    @Test
    public void hideAndShowApplication_updatesVisibleBoundsWithoutChangingActualSize()
            throws InterruptedException {
        List<LauncherActivityInfo> activities = List.of(
                mockActivityInfo("com.example.alpha", "Alpha"),
                mockActivityInfo("com.example.beta", "Beta")
        );
        ProfileApplicationManager manager = createAndAwaitReady(activities);

        LauncherApplication alpha = manager.get("com.example.alpha");
        LauncherApplication beta = manager.get("com.example.beta");
        assertEquals(2, manager.getSize());

        manager.hideApplication(alpha);
        assertEquals(1, manager.getSize());
        assertEquals(2, manager.getActualSize());
        assertSame(beta, manager.get(0));
        assertNull(manager.get(1));
        assertNull(manager.get(-1));

        manager.showApplication(alpha);
        assertEquals(2, manager.getSize());
        assertSame(alpha, manager.get(0));
        assertSame(beta, manager.get(1));
        assertNull(manager.get(2));
    }
}
