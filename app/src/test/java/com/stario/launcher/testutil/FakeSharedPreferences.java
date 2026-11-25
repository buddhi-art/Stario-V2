package com.stario.launcher.testutil;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lightweight in-memory {@link SharedPreferences} test double for JVM unit tests.
 */
public class FakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> store = new HashMap<>();
    private final CopyOnWriteArrayList<OnSharedPreferenceChangeListener> listeners =
            new CopyOnWriteArrayList<>();

    @Override
    public synchronized Map<String, ?> getAll() {
        return new HashMap<>(store);
    }

    @Nullable
    @Override
    public synchronized String getString(String key, @Nullable String defValue) {
        Object value = store.get(key);
        return value instanceof String ? (String) value : defValue;
    }

    @Nullable
    @Override
    @SuppressWarnings("unchecked")
    public synchronized Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object value = store.get(key);
        return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        Object value = store.get(key);
        return value instanceof Integer ? (Integer) value : defValue;
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        Object value = store.get(key);
        return value instanceof Long ? (Long) value : defValue;
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        Object value = store.get(key);
        return value instanceof Float ? (Float) value : defValue;
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        Object value = store.get(key);
        return value instanceof Boolean ? (Boolean) value : defValue;
    }

    @Override
    public synchronized boolean contains(String key) {
        return store.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public synchronized void putRaw(String key, Object value) {
        store.put(key, value);
    }

    private class FakeEditor implements Editor {
        private final Map<String, Object> staged = new HashMap<>();
        private final Set<String> removals = new HashSet<>();
        private boolean clearRequested = false;

        @Override
        public Editor putString(String key, @Nullable String value) {
            if (value == null) {
                removals.add(key);
                staged.remove(key);
            } else {
                staged.put(key, value);
                removals.remove(key);
            }
            return this;
        }

        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            if (values == null) {
                removals.add(key);
                staged.remove(key);
            } else {
                staged.put(key, Collections.unmodifiableSet(new HashSet<>(values)));
                removals.remove(key);
            }
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            staged.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            staged.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            staged.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            staged.put(key, value);
            removals.remove(key);
            return this;
        }

        @Override
        public Editor remove(String key) {
            removals.add(key);
            staged.remove(key);
            return this;
        }

        @Override
        public Editor clear() {
            clearRequested = true;
            staged.clear();
            removals.clear();
            return this;
        }

        @Override
        public boolean commit() {
            Set<String> modifiedKeys = new HashSet<>();
            synchronized (FakeSharedPreferences.this) {
                if (clearRequested) {
                    modifiedKeys.addAll(store.keySet());
                    store.clear();
                }
                for (String key : removals) {
                    if (store.remove(key) != null) {
                        modifiedKeys.add(key);
                    }
                }
                for (Map.Entry<String, Object> entry : staged.entrySet()) {
                    store.put(entry.getKey(), entry.getValue());
                    modifiedKeys.add(entry.getKey());
                }
            }
            for (String key : modifiedKeys) {
                for (OnSharedPreferenceChangeListener listener : listeners) {
                    listener.onSharedPreferenceChanged(FakeSharedPreferences.this, key);
                }
            }
            return true;
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
