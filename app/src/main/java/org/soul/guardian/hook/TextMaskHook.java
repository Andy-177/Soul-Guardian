package org.soul.guardian.hook;

import android.graphics.Canvas;
import android.util.Log;
import android.widget.TextView;

import org.soul.guardian.core.FilterConfig;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;

/**
 * Masks banned words inside TextView/Canvas text calls. The active word list is swapped in
 * dynamically by {@link GuardianEngine} whenever the module signals a change, so this hook never
 * reads anything itself: it only uses whatever {@link FilterConfig} it currently holds.
 *
 * <p>Handled shapes: plain text ({@code (CharSequence)}, {@code append(CharSequence)}), ranged
 * text ({@code append(CharSequence,int,int)}, {@code Canvas.drawText(...,int,int,...)}), char
 * arrays ({@code setText(char[],int,int)}, {@code Canvas.drawText(char[],...)}), and resource ids
 * ({@code setText(int)} / {@code setHint(int)}) resolved through the view's context.
 */
public final class TextMaskHook extends XC_MethodHook {

    private static final String TAG = "SoulGuardian";
    private static final boolean DEBUG = false;

    private static final ThreadLocal<Boolean> GUARD = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    /** Processes whose first text call has already been logged, for a one-line-per-process diagnostic. */
    private static final Set<String> FIRE_LOGGED =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private volatile String packageName;
    private volatile FilterConfig config;

    public TextMaskHook(String packageName, FilterConfig config) {
        this.packageName = packageName == null ? "?" : packageName;
        this.config = config;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName == null ? "?" : packageName;
    }

    public void updateConfig(FilterConfig config) {
        this.config = config;
    }

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (Boolean.TRUE.equals(GUARD.get()) || param.args.length == 0) {
            return;
        }
        if (FIRE_LOGGED.add(packageName)) {
            Log.i(TAG, "[" + packageName + "] hook fired: " + param.method.getName()
                    + " pid=" + android.os.Process.myPid());
        }
        try {
            FilterConfig current = config;
            if (!current.isTestMode() && (!current.isEnabled() || !current.hasRules())) {
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
        int start = 0;
        int end = raw.length();
        boolean ranged = isRange(param);
        if (ranged) {
            start = (Integer) param.args[1];
            end = (Integer) param.args[2];
            if (start < 0 || end < start || end > raw.length()) {
                return;
            }
        }
        CharSequence region = ranged ? raw.subSequence(start, end) : raw;
        CharSequence filtered = isCanvas(param)
                ? current.filterEqualLength(region) : current.filter(region);
        if (filtered == region) {
            return;
        }
        param.args[0] = filtered;
        if (ranged) {
            param.args[1] = 0;
            param.args[2] = filtered.length();
        }
        if (DEBUG) {
            Log.d(TAG, "[" + packageName + "] filtered: " + filtered);
        }
    }

    private void maskCharArray(MethodHookParam param, FilterConfig current) {
        if (param.args.length < 3) {
            return;
        }
        char[] source = (char[]) param.args[0];
        int start = (Integer) param.args[1];
        int length = (Integer) param.args[2];
        if (start < 0 || length < 0 || start + length > source.length) {
            return;
        }
        String region = new String(source, start, length);
        CharSequence filtered = isCanvas(param)
                ? current.filterEqualLength(region) : current.filter(region);
        if (filtered != region) {
            char[] masked = filtered.toString().toCharArray();
            param.args[0] = masked;
            param.args[1] = 0;
            param.args[2] = masked.length;
            if (DEBUG) {
                Log.d(TAG, "[" + packageName + "] filtered(char[]): " + filtered);
            }
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
            TextView.BufferType bufferType = null;
            for (Object arg : param.args) {
                if (arg instanceof TextView.BufferType) {
                    bufferType = (TextView.BufferType) arg;
                    break;
                }
            }
            GUARD.set(Boolean.TRUE);
            try {
                param.setResult(null);
                if (isHint) {
                    view.setHint(filtered);
                } else if (bufferType != null) {
                    view.setText(filtered, bufferType);
                } else {
                    view.setText(filtered);
                }
            } finally {
                GUARD.set(Boolean.FALSE);
            }
            if (DEBUG) {
                Log.d(TAG, "[" + packageName + "] filtered" + (isHint ? "(hint)" : "(res)")
                        + ": " + filtered);
            }
        }
    }

    private static boolean isRange(MethodHookParam param) {
        return param.args.length >= 3
                && param.args[1] instanceof Integer
                && param.args[2] instanceof Integer;
    }

    private static boolean isCanvas(MethodHookParam param) {
        return param.method != null && param.method.getDeclaringClass() == Canvas.class;
    }
}