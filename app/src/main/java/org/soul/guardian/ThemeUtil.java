package org.soul.guardian;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import org.soul.guardian.config.ConfigKeys;

/** Resolves the selected theme preset and night mode. */
public final class ThemeUtil {

    public static final String THEME_DYNAMIC = "dynamic";
    public static final String THEME_DEFAULT = "default";
    public static final String THEME_BLUE = "blue";
    public static final String THEME_GREEN = "green";
    public static final String THEME_ORANGE = "orange";
    public static final String THEME_PURPLE = "purple";

    private static final String KEY_THEME = "appearance_theme";
    private static final String KEY_NIGHT = "appearance_night";

    private ThemeUtil() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(ConfigKeys.PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static String getThemeKey(Context context) {
        return prefs(context).getString(KEY_THEME, THEME_DEFAULT);
    }

    public static void setThemeKey(Context context, String key) {
        prefs(context).edit().putString(KEY_THEME, key == null ? THEME_DEFAULT : key).apply();
    }

    public static boolean isDynamic(Context context) {
        return THEME_DYNAMIC.equals(getThemeKey(context));
    }

    public static int themeRes(Context context) {
        switch (getThemeKey(context)) {
            case THEME_BLUE:
                return R.style.Theme_SoulGuardian_Blue;
            case THEME_GREEN:
                return R.style.Theme_SoulGuardian_Green;
            case THEME_ORANGE:
                return R.style.Theme_SoulGuardian_Orange;
            case THEME_PURPLE:
                return R.style.Theme_SoulGuardian_Purple;
            case THEME_DEFAULT:
            default:
                return R.style.Theme_SoulGuardian_Default;
        }
    }

    public static int getNightMode(Context context) {
        return prefs(context).getInt(KEY_NIGHT, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    public static void setNightMode(Context context, int mode) {
        prefs(context).edit().putInt(KEY_NIGHT, mode).apply();
    }

    public static void applyNightMode(Context context) {
        AppCompatDelegate.setDefaultNightMode(getNightMode(context));
    }
}
