package com.stario.launcher.apps;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.UserHandle;

import com.stario.launcher.Stario;
import com.stario.launcher.exceptions.NoExistingInstanceException;
import com.stario.launcher.preferences.Entry;
import com.stario.launcher.testutil.FakeSharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CategoryMappingsTest {
    private Map<String, FakeSharedPreferences> prefsByName;
    private Stario mockStario;
    private UserHandle ownerHandle;
    private UserHandle workHandle;
    private MockedStatic<ProfileManager> profileManagerStatic;
    private MockedStatic<CategoryManager> categoryManagerStatic;

    @Before
    public void setUp() throws Exception {
        resetCategoryMappingsInstance();

        prefsByName = new HashMap<>();
        mockStario = mock(Stario.class);
        when(mockStario.getSharedPreferences(anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    String name = invocation.getArgument(0);
                    return prefsByName.computeIfAbsent(name, ignored -> new FakeSharedPreferences());
                });

        ownerHandle = mock(UserHandle.class);
        workHandle = mock(UserHandle.class);

        profileManagerStatic = Mockito.mockStatic(ProfileManager.class);
        profileManagerStatic.when(ProfileManager::getOwner).thenReturn(ownerHandle);

        categoryManagerStatic = Mockito.mockStatic(CategoryManager.class);
    }

    @After
    public void tearDown() throws Exception {
        if (categoryManagerStatic != null) {
            categoryManagerStatic.close();
        }
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

    private FakeSharedPreferences getPrefsFor(String subPreferenceName) {
        return prefsByName.computeIfAbsent(
                Entry.CATEGORY_MAP.toSubPreference(subPreferenceName),
                ignored -> new FakeSharedPreferences());
    }

    private static LauncherApplication app(String pkg, UserHandle handle, String label) {
        ApplicationInfo info = mock(ApplicationInfo.class);
        info.packageName = pkg;
        info.flags = 0;
        return new LauncherApplication(info, handle, label);
    }

    @Test
    public void uninitialized_throwsNoExistingInstanceException() {
        assertThrows(NoExistingInstanceException.class, CategoryMappings::getCategoryComparator);
        Category dummyCategory = mock(Category.class);
        assertThrows(NoExistingInstanceException.class,
                () -> CategoryMappings.getCategoryApplicationComparator(dummyCategory));
    }

    @Test
    public void mapComparator_placesMappedCategoriesBeforeUnmappedAndPreservesOrder() {
        FakeSharedPreferences categoriesPrefs = getPrefsFor("CATEGORIES");
        // Keep most-significant bits < 0x8000... because java.util.UUID.compareTo is signed
        UUID mappedSecond = UUID.fromString("77777777-ffff-ffff-ffff-ffffffffffff");
        UUID mappedFirst = UUID.fromString("33333333-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID unmappedLow = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID unmappedHigh = UUID.fromString("66666666-9999-9999-9999-999999999999");

        categoriesPrefs.edit()
                .putInt(mappedFirst.toString(), 0)
                .putInt(mappedSecond.toString(), 5)
                .apply();
        // Non-integer entries must be ignored gracefully
        categoriesPrefs.putRaw(unmappedLow.toString(), "not-an-int");

        CategoryMappings.from(mockStario);
        CategoryMappings.Comparator<Category> comparator = CategoryMappings.getCategoryComparator();

        Category cMappedFirst = new Category(mappedFirst);
        Category cMappedSecond = new Category(mappedSecond);
        Category cUnmappedLow = new Category(unmappedLow);
        Category cUnmappedHigh = new Category(unmappedHigh);

        List<Category> list = new ArrayList<>();
        list.add(cUnmappedHigh);
        list.add(cMappedSecond);
        list.add(cUnmappedLow);
        list.add(cMappedFirst);

        list.sort(comparator);

        assertEquals(mappedFirst, list.get(0).identifier);
        assertEquals(mappedSecond, list.get(1).identifier);
        assertEquals(unmappedLow, list.get(2).identifier);
        assertEquals(unmappedHigh, list.get(3).identifier);
    }

    @Test
    public void mapComparator_timSortTransitivityStressTest() {
        FakeSharedPreferences categoriesPrefs = getPrefsFor("CATEGORIES");
        Random random = new Random(42L);
        List<UUID> uuids = new ArrayList<>();

        for (int i = 0; i < 200; i++) {
            UUID id = new UUID(random.nextLong(), random.nextLong());
            uuids.add(id);
            if (i % 2 == 0) {
                // Deliberately include gaps, negative indices, and duplicate indices
                categoriesPrefs.edit().putInt(id.toString(), (i % 40) - 10).apply();
            }
        }

        CategoryMappings.from(mockStario);
        CategoryMappings.Comparator<Category> comparator = CategoryMappings.getCategoryComparator();

        List<Category> items = new ArrayList<>();
        for (UUID id : uuids) {
            items.add(new Category(id));
        }

        assertComparatorContractAndTimSort(items, comparator, 15, random);
    }

    @Test
    public void applicationComparator_isolatesProfilesAndPlacesMappedBeforeUnmapped() {
        CategoryMappings.from(mockStario);
        UUID categoryId = UUID.randomUUID();
        FakeSharedPreferences appPrefs = getPrefsFor(categoryId.toString());

        LauncherApplication primaryZ = app("com.example.z", ownerHandle, "Zebra");
        LauncherApplication workA = app("com.example.a", workHandle, "Alpha");
        LauncherApplication primaryA = app("com.example.a", ownerHandle, "Alpha");
        LauncherApplication primaryM = app("com.example.m", ownerHandle, "Middle");

        appPrefs.edit()
                .putInt("com.example.z", 0)
                .putInt("com.example.a:" + workHandle.hashCode(), 1)
                .apply();

        Category category = new Category(categoryId);
        CategoryMappings.Comparator<LauncherApplication> comparator =
                CategoryMappings.getCategoryApplicationComparator(category);

        List<LauncherApplication> apps = new ArrayList<>();
        apps.add(primaryM);
        apps.add(primaryA);
        apps.add(workA);
        apps.add(primaryZ);

        apps.sort(comparator);

        assertEquals(primaryZ, apps.get(0));
        assertEquals(workA, apps.get(1));
        assertEquals(primaryA, apps.get(2));
        assertEquals(primaryM, apps.get(3));
    }

    @Test
    public void applicationComparator_timSortTransitivityStressTest() {
        CategoryMappings.from(mockStario);
        UUID categoryId = UUID.randomUUID();
        FakeSharedPreferences appPrefs = getPrefsFor(categoryId.toString());

        List<LauncherApplication> apps = new ArrayList<>();
        Random random = new Random(1337L);

        for (int i = 0; i < 150; i++) {
            UserHandle handle = (i % 3 == 0) ? workHandle : ownerHandle;
            String pkg = "com.example.app" + (i % 75);
            String label = "App " + ((i * 17) % 30);
            LauncherApplication application = app(pkg, handle, label);
            apps.add(application);

            if (i % 2 == 0) {
                String key = handle.equals(ownerHandle) ? pkg : pkg + ":" + handle.hashCode();
                appPrefs.edit().putInt(key, random.nextInt(40)).apply();
            }
        }

        Category category = new Category(categoryId);
        CategoryMappings.Comparator<LauncherApplication> comparator =
                CategoryMappings.getCategoryApplicationComparator(category);

        assertComparatorContractAndTimSort(apps, comparator, 15, random);
    }

    @Test
    public void updatePermutation_persistsCurrentCategoryOrder() {
        FakeSharedPreferences categoriesPrefs = getPrefsFor("CATEGORIES");
        CategoryMappings.from(mockStario);

        Category first = new Category(UUID.fromString("77777777-0000-0000-0000-000000000001"));
        Category second = new Category(UUID.fromString("00000000-0000-0000-0000-000000000002"));

        CategoryManager mockCategoryManager = mock(CategoryManager.class);
        when(mockCategoryManager.getAll()).thenReturn(List.of(first, second));
        categoryManagerStatic.when(CategoryManager::getInstance).thenReturn(mockCategoryManager);

        CategoryMappings.Comparator<Category> comparator = CategoryMappings.getCategoryComparator();
        // Before permutation update, UUID ordering puts `second` before `first`
        assertTrue(comparator.compare(first, second) > 0);

        comparator.updatePermutation();

        assertEquals(0, categoriesPrefs.getInt(first.identifier.toString(), -1));
        assertEquals(1, categoriesPrefs.getInt(second.identifier.toString(), -1));
        assertTrue(comparator.compare(first, second) < 0);
    }

    private static <T> void assertComparatorContractAndTimSort(
            List<T> items,
            java.util.Comparator<T> comparator,
            int shuffles,
            Random random
    ) {
        for (int s = 0; s < shuffles; s++) {
            List<T> copy = new ArrayList<>(items);
            Collections.shuffle(copy, random);
            copy.sort(comparator);

            // Verify sorted pairwise order
            for (int i = 0; i < copy.size() - 1; i++) {
                assertTrue("List not sorted at index " + i,
                        comparator.compare(copy.get(i), copy.get(i + 1)) <= 0);
            }
        }

        // Verify reflexivity, antisymmetry, and transitivity across sampled triples
        int limit = Math.min(items.size(), 45);
        for (int i = 0; i < limit; i++) {
            T a = items.get(i);
            assertEquals("Reflexivity violated", 0, comparator.compare(a, a));

            for (int j = 0; j < limit; j++) {
                T b = items.get(j);
                int ab = comparator.compare(a, b);
                int ba = comparator.compare(b, a);
                assertEquals("Antisymmetry violated",
                        Integer.signum(ab), -Integer.signum(ba));

                for (int k = 0; k < limit; k++) {
                    T c = items.get(k);
                    int bc = comparator.compare(b, c);
                    int ac = comparator.compare(a, c);
                    if (ab > 0 && bc > 0) {
                        assertTrue("Transitivity (>) violated", ac > 0);
                    } else if (ab < 0 && bc < 0) {
                        assertTrue("Transitivity (<) violated", ac < 0);
                    } else if (ab == 0 && bc == 0) {
                        assertEquals("Transitivity (==) violated", 0, ac);
                    }
                }
            }
        }
    }
}
