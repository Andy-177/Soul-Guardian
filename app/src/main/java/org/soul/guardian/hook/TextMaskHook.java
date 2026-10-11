package org.soul.guardian.hook;

import android.util.Log;
import android.widget.TextView;

import org.soul.guardian.core.FilterConfig;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Masks banned words inside {@code TextView.setText}. The active word list is swapped in
 * dynamically by {@link GuardianEngine} whenever the module signals a change, so this hook never
 * reads anything itself: it only uses whatever {@link FilterConfig} it currently holds.
 *
 * <p>Supported overloads: {@code (CharSequence)}, {@code (CharSequence, BufferType)},
 * {@code (CharSequence, BufferType, boolean)}, {@code (char[], int, int)}, {@code (int)} and
 * {@code (int, BufferType)} (resource text resolved through the view's context).
 */
public final class TextMaskHook extends XC_MethodHook {

    private static final String TAG = "SoulGuardian";

    private static final ThreadLocal<Boolean> GUARD = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private final String packageName;
    private volatile FilterConfig config;

    public TextMaskHook(String packageName, FilterConfig config) {
        this.packageName = packageName == null ? "?" : packageName;
        this.config = config;
    }

    public void updateConfig(FilterConfig config) {
        this.config = config;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (Boolean.TRUE.equals(GUARD.get())) {
            return;
        }
        try {
            FilterConfig current = config;
            if (!current.isEnabled() || !current.hasRules()) {
                return;
            }
            if (param.args[0] instanceof CharSequence) {
                maskCharSequence(param, current);
                return;
            }
            if (param.args[0] instanceof char[]) {
                maskCharArray(param, current);
                return;
            }
            if (param.args[0] instanceof Integer) {
                maskResource(param, current);
            }
        } catch (Throwable ignored) {
            // never break the host app because of a faulty rule
        }
    }

    private void maskCharSequence(MethodHookParam param, FilterConfig current) {
        CharSequence raw = (CharSequence) param.args[0];
        CharSequence filtered = current.filter(raw);
        if (filtered != raw) {
            param.args[0] = filtered;
            Log.d(TAG, "[" + packageName + "] filtered: " + filtered);
        }
    }

    private void maskCharArray(MethodHookParam param, FilterConfig current) {
        char[] source = (char[]) param.args[0];
        int start = (Integer) param.args[1];
        int length = (Integer) param.args[2];
        if (start < 0 || length < 0 || start + length > source.length) {
            return;
        }
        String region = new String(source, start, length);
        CharSequence filtered = current.filter(region);
        if (filtered != region) {
            char[] masked = filtered.toString().toCharArray();
            param.args[0] = masked;
            param.args[1] = 0;
            param.args[2] = masked.length;
            Log.d(TAG, "[" + packageName + "] filtered(char[]): " + filtered);
        }
    }

    private void maskResource(MethodHookParam param, FilterConfig current) {
        Object self = param.thisObject;
        if (!(self instanceof TextView)) {
            return;
        }
        TextView view = (TextView) self;
        String raw;
        try {
            raw = view.getContext().getString((Integer) param.args[0]);
        } catch (Throwable ignored) {
            return; // not a string resource
        }
        if (raw.isEmpty()) {
            return;
        }
        CharSequence filtered = current.filter(raw);
        if (filtered != raw) {
            boolean isHint = "setHint".equals(param.method.getName());
            GUARD.set(Boolean.TRUE);
            try {
                param.setResult(null);
                if (isHint) {
                    view.setHint(filtered);
                } else {
                    view.setText(filtered);
                }
            } finally {
                GUARD.set(Boolean.FALSE);
            }
            Log.d(TAG, "[" + packageName + "] filtered" + (isHint ? "(hint)" : "(res)")
                    + ": " + filtered);
        }
    }
}