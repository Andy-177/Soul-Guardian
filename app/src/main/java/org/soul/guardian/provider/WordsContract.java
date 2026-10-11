package org.soul.guardian.provider;

import android.net.Uri;

/** Well-known URIs and columns for the word-list {@link WordListProvider}. */
public final class WordsContract {

    public static final String AUTHORITY = "org.soul.guardian.words";

    public static final Uri RULES_URI = Uri.parse("content://" + AUTHORITY + "/rules");
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    public static final String COLUMN_RULE = "rule";
    public static final String COLUMN_ENABLED = "enabled";
    public static final String COLUMN_MODE = "mode";
    public static final String COLUMN_MASK = "mask";

    private WordsContract() {
    }
}