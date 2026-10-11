package org.soul.guardian.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Helpers for reading rule files and (de)serialising the selected list ids. */
public final class ConfigParser {

    private ConfigParser() {
    }

    /**
     * A line is a comment only when {@code #} is the very first character. A {@code #}
     * anywhere else is part of the regular expression.
     */
    public static boolean isComment(String line) {
        return line != null && !line.isEmpty() && line.charAt(0) == '#';
    }

    /**
     * Reads rule lines (one regular expression per line) from the given reader.
     * Comment lines ({@code #} at column 0) and blank lines are skipped; duplicates are
     * collapsed while preserving order.
     */
    public static List<String> readRules(BufferedReader reader) throws IOException {
        List<String> rules = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (isComment(line)) {
                continue;
            }
            String rule = line.trim();
            if (rule.isEmpty()) {
                continue;
            }
            if (seen.add(rule)) {
                rules.add(rule);
            }
        }
        return rules;
    }

    /**
     * Parses a persisted JSON array of selected list ids. Returns {@code null} when nothing is
     * stored yet, which means every list is considered active.
     */
    public static Set<String> toIdSet(String idsJson) {
        if (idsJson == null || idsJson.isEmpty()) {
            return null;
        }
        try {
            JSONArray array = new JSONArray(idsJson);
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < array.length(); i++) {
                String id = array.optString(i);
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
            return ids;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static String toIdJson(Collection<String> ids) {
        JSONArray array = new JSONArray();
        if (ids != null) {
            for (String id : ids) {
                if (id != null && !id.isEmpty()) {
                    array.put(id);
                }
            }
        }
        return array.toString();
    }

    /**
     * Collects the rules of every selected list, in list order. A {@code null} selection means
     * all lists are active.
     */
    public static List<String> activeWords(String listsJson, Set<String> activeIds) {
        if (listsJson == null || listsJson.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<>();
        try {
            JSONArray lists = new JSONArray(listsJson);
            for (int i = 0; i < lists.length(); i++) {
                JSONObject list = lists.getJSONObject(i);
                String id = list.optString("id");
                boolean selected = activeIds == null || activeIds.contains(id);
                if (!selected) {
                    continue;
                }
                JSONArray words = list.optJSONArray("words");
                if (words != null) {
                    for (int j = 0; j < words.length(); j++) {
                        String word = words.optString(j);
                        if (word != null && !word.isEmpty()) {
                            out.add(word);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            return Collections.emptyList();
        }
        return out;
    }
}
