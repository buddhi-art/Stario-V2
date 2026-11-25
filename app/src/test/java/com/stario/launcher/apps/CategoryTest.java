package com.stario.launcher.apps;

import android.content.pm.ApplicationInfo;
import android.os.UserHandle;

import com.stario.launcher.Stario;
import com.stario.launcher.testutil.FakeSharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class CategoryTest {
    private UserHandle ownerHandle;
    private UserHandle workHandle;
    private MockedStatic<ProfileManager> profileManagerStatic;

    @Before
    public void setUp() throws Exception {
        resetCategoryMappingsInstance();

        Map<String, FakeSharedPreferences> prefsByName = new HashMap<>();
        Stario mockStario = mock(Stario.class);
        when(mockStario.getSharedPreferences(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    return prefsByName.computeIfAbsent(name, ignored -> new FakeSharedPreferences());
                });

        ownerHandle = mock(UserHandle.class);
        workHandle = mock(UserHandle.class);

        profileManagerStatic = Mockito.mockStatic(ProfileManager.class);
        profileManagerStatic.when(ProfileManager::getOwner).thenReturn(ownerHandle);

        CategoryMappings.from(mockStario);
    }

    @After
    public void tearDown() throws Exception {
        if (profileManagerStatic != null) {
            profileManagerStatic.close();
        }
        resetCategoryMappingsInstance();
    }

    private static void resetCategoryMappingsInstance() throws Exception {
        Field instanceField = CategoryMappings.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, null);
    }

    private static LauncherApplication app(String pkg, UserHandle handle, String label) {
        ApplicationInfo info = mock(ApplicationInfo.class);
        info.packageName = pkg;
        info.flags = 0;
        return new LauncherApplication(info, handle, label);
    }

    @Test
    public void get_onEmptyCategory_returnsFallbackForAllIndices() {
        Category category = new Category(UUID.randomUUID());

        assertEquals(0, category.getSize());
        assertNull(category.get(-1));
        assertNull(category.get(0));
        assertNull(category.get(1));
        assertNull(category.get(Integer.MIN_VALUE));
        assertNull(category.get(Integer.MAX_VALUE));
    }

    @Test
    public void get_withPopulatedCategory_enforcesBoundsAndSortedInsertion() {
        Category category = new Category(UUID.randomUUID());
        LauncherApplication zeta = app("com.example.z", ownerHandle, "Zeta");
        LauncherApplication alpha = app("com.example.a", ownerHandle, "Alpha");
        LauncherApplication middle = app("com.example.m", ownerHandle, "Middle");

        category.addApplication(zeta);
        category.addApplication(alpha);
        category.addApplication(middle);
        // Duplicate package in same profile should be ignored
        category.addApplication(app("com.example.a", ownerHandle, "Alpha"));
        // Same package in different profile must be kept (distinct label for deterministic ordering)
        LauncherApplication workAlpha = app("com.example.a", workHandle, "Alpha Work");
        category.addApplication(workAlpha);

        assertEquals(4, category.getSize());
        assertNull(category.get(-1));
        assertNull(category.get(-100));
        assertNull(category.get(category.getSize()));
        assertNull(category.get(category.getSize() + 10));

        assertSame(alpha, category.get(0));
        assertSame(workAlpha, category.get(1));
        assertSame(middle, category.get(2));
        assertSame(zeta, category.get(3));
    }

    @Test
    public void removeAndSwap_updateSizeBoundsAndNotifyListeners() {
        Category category = new Category(UUID.randomUUID());
        Category.CategoryItemListener listener = mock(Category.CategoryItemListener.class);
        category.addCategoryItemListener(listener);

        LauncherApplication first = app("com.example.a", ownerHandle, "Alpha");
        LauncherApplication second = app("com.example.b", ownerHandle, "Beta");
        category.addApplication(first);
        category.addApplication(second);

        category.swap(0, 1);
        assertSame(second, category.get(0));
        assertSame(first, category.get(1));
        verify(listener).onSwapped(0, 1);

        category.removeApplication(second);
        assertEquals(1, category.getSize());
        assertSame(first, category.get(0));
        assertNull(category.get(1));
        assertNull(category.get(-1));
    }
}
