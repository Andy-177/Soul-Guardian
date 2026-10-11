package org.soul.guardian.data;

import org.json.JSONArray;
import org.json.JSONObject;

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

    public String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("id", id);
            object.put("name", name);
            JSONArray array = new JSONArray();
            for (String word : words) {
                array.put(word);
            }
            object.put("words", array);
        } catch (Throwable ignored) {
            // org.json is not expected to fail on plain strings.
        }
        return object.toString();
    }

    public static BannedList fromJson(JSONObject object) {
        String id = object.optString("id");
        String name = object.optString("name");
        List<String> words = new ArrayList<>();
        JSONArray array = object.optJSONArray("words");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                String word = array.optString(i);
                if (word != null && !word.isEmpty()) {
                    words.add(word);
                }
            }
        }
        return new BannedList(id, name, words);
    }
}