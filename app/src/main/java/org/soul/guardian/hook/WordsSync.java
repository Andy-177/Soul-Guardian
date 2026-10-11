package org.soul.guardian.hook;

import android.content.Context;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.soul.guardian.config.ConfigKeys;
import org.soul.guardian.core.FilterConfig;
import org.soul.guardian.provider.WordsContract;

import java.util.ArrayList;
import java.util.List;

/**
 * Bridge between the injected hook and the module's {@link org.soul.guardian.provider.WordListProvider}.
 *
 * <p>It registers a {@link ContentObserver} on the module's word-list URI and pulls the newest
 * list from the provider (a) once at install time and (b) every time the module fires
 * {@code notifyChange}. The initial pull is retried a few times in case the provider was not
 * reachable yet; afterwards the observer keeps the host process in sync.
 */
public final class WordsSync {

    private static final String TAG = "SoulGuardian";
    private static final int MAX_RETRIES = 12;

    private final String packageName;
    private final Context appContext;
    private final TextMaskHook hook;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            refresh("change");
        }
    };
    private int retries;

    private WordsSync(Context appContext, String packageName, TextMaskHook hook) {
        this.appContext = appContext.getApplicationContext();
        this.packageName = packageName;
        this.hook = hook;
    }

    public static void attach(Context context, String packageName, TextMaskHook hook) {
        try {
            new WordsSync(context, packageName, hook).start();
        } catch (Throwable t) {
            Log.d(TAG, "provider sync unavailable -> " + t);
        }
    }

    private void start() {
        appContext.getContentResolver().registerContentObserver(
                WordsContract.RULES_URI, false, observer);
        refresh("init");
    }

    private void refresh(String reason) {
        FilterConfig next = readConfig();
        if (next == null) {
            if (retries < MAX_RETRIES) {
                retries++;
                long delay = 500L * (1L << Math.min(retries, 6));
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refresh("retry");
                    }
                }, delay);
            } else {
                Log.d(TAG, "[" + packageName + "] giving up on provider, observer still active");
            }
            return;
        }
        retries = 0;
        hook.updateConfig(next);
        Log.i(TAG, "[" + packageName + "] " + reason + ": enabled=" + next.isEnabled()
                + " mode=" + next.getMode() + " mask=" + next.maskSymbol()
                + " rules=" + next.ruleCount());
    }

    private FilterConfig readConfig() {
        boolean enabled;
        String mode;
        String mask;
        try {
            Cursor config = appContext.getContentResolver().query(
                    WordsContract.CONFIG_URI, null, null, null, null);
            if (config == null) {
                return null;
            }
            try {
                if (!config.moveToFirst()) {
                    return null;
                }
                enabled = config.getInt(
                        config.getColumnIndexOrThrow(WordsContract.COLUMN_ENABLED)) != 0;
                mode = config.getString(
                        config.getColumnIndexOrThrow(WordsContract.COLUMN_MODE));
                mask = config.getString(
                        config.getColumnIndexOrThrow(WordsContract.COLUMN_MASK));
            } finally {
                config.close();
            }
        } catch (Throwable t) {
            Log.d(TAG, "config query failed -> " + t);
            return null;
        }
        List<String> rules = new ArrayList<>();
        try {
            Cursor cursor = appContext.getContentResolver().query(
                    WordsContract.RULES_URI, null, null, null, null);
            if (cursor == null) {
                return null;
            }
            try {
                int column = cursor.getColumnIndexOrThrow(WordsContract.COLUMN_RULE);
                while (cursor.moveToNext()) {
                    String rule = cursor.getString(column);
                    if (rule != null && !rule.isEmpty()) {
                        rules.add(rule);
                    }
                }
            } finally {
                cursor.close();
            }
        } catch (Throwable t) {
            Log.d(TAG, "rules query failed -> " + t);
            return null;
        }
        FilterConfig.Mode modeEnum = ConfigKeys.MODE_BLOCK.equals(mode)
                ? FilterConfig.Mode.BLOCK : FilterConfig.Mode.MASK;
        return new FilterConfig(enabled, modeEnum, mask, rules);
    }
}