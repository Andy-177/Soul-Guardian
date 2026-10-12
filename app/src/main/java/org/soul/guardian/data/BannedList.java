package org.soul.guardian.data;

import org.json.JSONArray;
import org.json.JSONObject;

import org.soul.guardian.config.ConfigParser;

import java.util.ArrayList;
import java.util.List;

/** A named collection of banned words. */
public final class BannedList {

    public final String id;
    public String name;
    public final List<String> words;

    public BannedList(String id, String name, List<String> words) {
        this.id = id;
        this.name = name;
        this.words = words != null ? words : new ArrayList<String>();
    }

    /** Serialises the list, keeping plain-text rules and regex rules in separate arrays. */
    public String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("name", name);
            JSONArray plain = new JSONArray();
            JSONArray regex = new JSONArray();
            for (String word : words) {
                if (ConfigParser.isRegexLine(word)) {
                    regex.put(word);
                } else {
                    plain.put(word);
                }
            }
            object.put("words", plain);
            object.put("regex", regex);
        } catch (Throwable ignored) {
            // org.json is not expected to fail on plain strings.
        }
        return object.toString();
    }

    public static BannedList fromJson(JSONObject object) {
        String id = object.optString("id");
        String name = object.optString("name");
        List<String> words = new ArrayList<>();
        readWords(object.optJSONArray("words"), words);
        readWords(object.optJSONArray("regex"), words);
        return new BannedList(id, name, words);
    }

    private static void readWords(JSONArray array, List<String> words) {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            String word = array.optString(i);
            if (word != null && !word.isEmpty()) {
                words.add(word);
            }
        }
    }
}