package org.soul.guardian.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import org.json.JSONArray;
import org.json.JSONObject;
import org.soul.guardian.config.ConfigKeys;
import org.soul.guardian.config.ConfigParser;
import org.soul.guardian.core.FilterConfig;
import org.soul.guardian.provider.WordsContract;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Persists module settings and appearance preferences in the app's private storage. */
public final class ConfigRepository {

    private static final String KEY_THEME = "appearance_theme";
    private static final String KEY_NIGHT = "appearance_night";

    private final Context appContext;
    private final SharedPreferences prefs;

    public ConfigRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(ConfigKeys.PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Commits synchronously so changes are visible before this process may be killed. */
    private void write(SharedPreferences.Editor editor) {
        editor.commit();
    }

    /**
     * Signals hooked processes that the word list changed. Observers registered by the host
     * process then pull the fresh list through the provider.
     */
    private void notifyWordsChanged() {
        try {
            appContext.getContentResolver().notifyChange(WordsContract.RULES_URI, null);
        } catch (Throwable ignored) {
            // the provider is reachable only inside the module app itself
        }
    }

    // ---- module behaviour -------------------------------------------------

    public boolean isEnabled() {
        return prefs.getBoolean(ConfigKeys.KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        write(prefs.edit().putBoolean(ConfigKeys.KEY_ENABLED, enabled));
        notifyWordsChanged();
    }

    public FilterConfig.Mode getMode() {
        return ConfigKeys.MODE_BLOCK.equals(prefs.getString(ConfigKeys.KEY_MODE, ConfigKeys.MODE_MASK))
                ? FilterConfig.Mode.BLOCK : FilterConfig.Mode.MASK;
    }

    public void setMode(FilterConfig.Mode mode) {
        write(prefs.edit().putString(ConfigKeys.KEY_MODE,
                mode == FilterConfig.Mode.BLOCK ? ConfigKeys.MODE_BLOCK : ConfigKeys.MODE_MASK));
        notifyWordsChanged();
    }

    public String getMaskSymbol() {
        String value = prefs.getString(ConfigKeys.KEY_MASK_SYMBOL, FilterConfig.DEFAULT_MASK_SYMBOL);
        return (value == null || value.isEmpty()) ? FilterConfig.DEFAULT_MASK_SYMBOL : value;
    }

    public void setMaskSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) {
            symbol = FilterConfig.DEFAULT_MASK_SYMBOL;
        }
        write(prefs.edit().putString(ConfigKeys.KEY_MASK_SYMBOL, symbol));
        notifyWordsChanged();
    }

    // ---- banned lists -----------------------------------------------------

    public List<BannedList> getLists() {
        List<BannedList> lists = new ArrayList<>();
        String json = prefs.getString(ConfigKeys.KEY_LISTS, "[]");
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                lists.add(BannedList.fromJson(array.getJSONObject(i)));
            }
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
        return lists;
    }

    public void saveLists(List<BannedList> lists) {
        JSONArray array = new JSONArray();
        for (BannedList list : lists) {
            try {
                array.put(new JSONObject(list.toJson()));
            } catch (Throwable ignored) {
                // skip malformed entry
            }
        }
        Set<String> currentIds = new LinkedHashSet<>();
        for (BannedList list : lists) {
            currentIds.add(list.id);
        }
        String storedIds = prefs.getString(ConfigKeys.KEY_ACTIVE_LIST_IDS, "");
        Set<String> active;
        if (storedIds == null || storedIds.isEmpty()) {
            // Nothing stored yet: keep the "all lists active" default.
            active = currentIds;
        } else {
            active = getActiveListIds();
            active.retainAll(currentIds);
        }
        write(prefs.edit()
                .putString(ConfigKeys.KEY_LISTS, array.toString())
                .putString(ConfigKeys.KEY_ACTIVE_LIST_IDS, ConfigParser.toIdJson(active)));
        notifyWordsChanged();
    }

    public void addList(BannedList list) {
        List<BannedList> lists = getLists();
        lists.add(list);
        saveLists(lists);
    }

    public void deleteList(String id) {
        List<BannedList> lists = getLists();
        List<BannedList> remaining = new ArrayList<>();
        for (BannedList list : lists) {
            if (!list.id.equals(id)) {
                remaining.add(list);
            }
        }
        saveLists(remaining);
    }

    public Set<String> getActiveListIds() {
        String json = prefs.getString(ConfigKeys.KEY_ACTIVE_LIST_IDS, "");
        Set<String> stored = ConfigParser.toIdSet(json);
        if (stored != null) {
            return new LinkedHashSet<>(stored);
        }
        Set<String> all = new LinkedHashSet<>();
        for (BannedList list : getLists()) {
            all.add(list.id);
        }
        return all;
    }

    public boolean isListActive(String id) {
        return getActiveListIds().contains(id);
    }

    public void setListSelected(String id, boolean selected) {
        Set<String> ids = getActiveListIds();
        if (selected) {
            ids.add(id);
        } else {
            ids.remove(id);
        }
        write(prefs.edit()
                .putString(ConfigKeys.KEY_ACTIVE_LIST_IDS, ConfigParser.toIdJson(ids)));
        notifyWordsChanged();
    }

    public List<String> getActiveWords() {
        String json = prefs.getString(ConfigKeys.KEY_LISTS, "[]");
        return ConfigParser.activeWords(json, getActiveListIds());
    }

    /**
     * Seeds the built-in word lists the first time the app runs. Every file under
     * {@code assets/banned/} becomes one list, named after the file's complete name
     * (extension included, no filtering by type). Content is read from disk so nothing is
     * hard-coded in the program. When the folder is missing or empty no list is created and
     * the app starts with no rules.
     */
    public void ensureDefaults() {
        if (prefs.contains(ConfigKeys.KEY_LISTS)) {
            return;
        }
        List<BannedList> lists = readBuiltInLists();
        if (lists.isEmpty()) {
            return;
        }
        saveLists(lists);
    }

    private List<BannedList> readBuiltInLists() {
        List<BannedList> lists = new ArrayList<>();
        try {
            String[] files = appContext.getAssets().list(ConfigKeys.BUILTIN_LIST_DIR);
            if (files == null || files.length == 0) {
                return lists;
            }
            Arrays.sort(files);
            for (String file : files) {
                List<String> rules = readAsset(ConfigKeys.BUILTIN_LIST_DIR + "/" + file);
                if (rules.isEmpty()) {
                    continue;
                }
                lists.add(new BannedList(UUID.randomUUID().toString(), file, rules));
            }
        } catch (Throwable ignored) {
            // No bundled lists available; caller falls back to an empty configuration.
        }
        return lists;
    }

    private List<String> readAsset(String path) {
        try (InputStream is = appContext.getAssets().open(path);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return ConfigParser.readRules(reader);
        } catch (Throwable ignored) {
            return new ArrayList<>();
        }
    }

    public BannedList createList(String name, List<String> words) {
        String safeName = (name == null || name.trim().isEmpty()) ? "未命名词库" : name.trim();
        return new BannedList(UUID.randomUUID().toString(), safeName, words);
    }

    // ---- appearance -------------------------------------------------------

    public String getThemeKey() {
        return prefs.getString(KEY_THEME, "default");
    }

    public void setThemeKey(String key) {
        write(prefs.edit().putString(KEY_THEME, key == null ? "default" : key));
    }

    public int getNightMode() {
        return prefs.getInt(KEY_NIGHT, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public void setNightMode(int mode) {
        write(prefs.edit().putInt(KEY_NIGHT, mode));
    }
}