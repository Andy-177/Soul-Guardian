package org.soul.guardian.config;

/** Shared preference keys for the local configuration. */
public final class ConfigKeys {

    private ConfigKeys() {
    }

    public static final String PREFS_NAME = "soul_guardian_prefs";

    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_MODE = "mode";
    public static final String KEY_MASK_SYMBOL = "mask_symbol";
    public static final String KEY_ACTIVE_LIST_IDS = "active_list_ids";
    public static final String KEY_LISTS = "lists_json";

    public static final String BUILTIN_LIST_DIR = "banned";

    public static final String MODE_MASK = "mask";
    public static final String MODE_BLOCK = "block";
}
