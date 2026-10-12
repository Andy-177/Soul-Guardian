package org.soul.guardian.hook;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.Log;
import android.widget.TextView;

import org.soul.guardian.core.FilterConfig;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Installs the text hooks once in the zygote, so every forked app process inherits them. Each
 * process then starts a {@link WordsSync} when its {@link Application} is created, pulling the
 * word list from the module's provider and feeding the shared {@link TextMaskHook}.
 *
 * <p>Because the hooks live in the zygote, they are not filtered by the module's scope: every
 * app process (except this module's own) gets masked text. The word list itself is still read
 * per process from the provider.
 */
public final class GuardianEngine {

    private static final String TAG = "SoulGuardian";
    private static final String SELF_PACKAGE = "org.soul.guardian";

    private static volatile boolean installed;

    private GuardianEngine() {
    }

    /** Called once from {@code initZygote}. Safe to call again; only the first call installs. */
    public static void installGlobal() {
        if (installed) {
            return;
        }
        installed = true;
        TextMaskHook hook = new TextMaskHook(null, FilterConfig.disabled());
        int ok = hookTextView(hook);
        ok += hookCanvas(hook);
        ok += hookApplicationCreate(hook);
        Log.i(TAG, "install pid=" + android.os.Process.myPid() + " hooked=" + ok);
    }

    /** Per-process bootstrap: once the Application exists, sync the word list. Returns 1 on success. */
    private static int hookApplicationCreate(final TextMaskHook hook) {
        try {
            XposedHelpers.findAndHookMethod(
                    Instrumentation.class,
                    "callApplicationOnCreate", Application.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object app = param.args[0];
                            if (!(app instanceof Context)) {
                                return;
                            }
                            Context ctx = (Context) app;
                            String pkg;
                            try {
                                pkg = ctx.getPackageName();
                            } catch (Throwable t) {
                                return;
                            }
                            if (SELF_PACKAGE.equals(pkg)) {
                                return;
                            }
                            try {
                                if (ctx.getContentResolver() != null) {
                                    Log.i(TAG, "[" + pkg + "] app created, attaching sync");
                                    hook.setPackageName(pkg);
                                    WordsSync.attach(ctx, pkg, hook);
                                }
                            } catch (Throwable t) {
                                Log.d(TAG, "[" + pkg + "] sync failed -> " + t);
                            }
                        }
                    });
            return 1;
        } catch (Throwable t) {
            Log.d(TAG, "unable to hook app create -> " + t);
            return 0;
        }
    }

    /**
     * {@link TextView} text entry points. The terminal {@code setText} overload is the one every
     * public overload funnels into (documented framework behaviour, not an implementation
     * detail); the leaf overloads are kept as a fallback for builds that inline the terminal one.
     */
    private static int hookTextView(TextMaskHook hook) {
        int ok = 0;
        if (hookMethod(TextView.class, "setText", hook,
                CharSequence.class, TextView.BufferType.class, boolean.class, int.class)) ok++;
        if (hookMethod(TextView.class, "setText", hook, CharSequence.class)) ok++;
        if (hookMethod(TextView.class, "setText", hook,
                CharSequence.class, TextView.BufferType.class)) ok++;
        if (hookMethod(TextView.class, "setText", hook,
                CharSequence.class, TextView.BufferType.class, boolean.class)) ok++;
        if (hookMethod(TextView.class, "setText", hook, char[].class, int.class, int.class)) ok++;
        if (hookMethod(TextView.class, "setText", hook, int.class)) ok++;
        if (hookMethod(TextView.class, "setText", hook, int.class, TextView.BufferType.class)) ok++;

        if (hookMethod(TextView.class, "setHint", hook, CharSequence.class)) ok++;
        if (hookMethod(TextView.class, "setHint", hook, int.class)) ok++;

        if (hookMethod(TextView.class, "append", hook, CharSequence.class)) ok++;
        if (hookMethod(TextView.class, "append", hook,
                CharSequence.class, int.class, int.class)) ok++;
        return ok;
    }

    /** Text painted directly through {@link Canvas}, for views that render their own text. */
    private static int hookCanvas(TextMaskHook hook) {
        int ok = 0;
        if (hookMethod(Canvas.class, "drawText", hook,
                String.class, float.class, float.class, Paint.class)) ok++;
        if (hookMethod(Canvas.class, "drawText", hook,
                String.class, int.class, int.class, float.class, float.class, Paint.class)) ok++;
        if (hookMethod(Canvas.class, "drawText", hook,
                CharSequence.class, int.class, int.class, float.class, float.class, Paint.class)) ok++;
        if (hookMethod(Canvas.class, "drawText", hook,
                char[].class, int.class, int.class, float.class, float.class, Paint.class)) ok++;
        return ok;
    }

    private static boolean hookMethod(Class<?> target, String methodName, XC_MethodHook callback,
                                      Object... parameterTypes) {
        try {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = callback;
            XposedHelpers.findAndHookMethod(target, methodName, args);
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "unable to hook " + target.getSimpleName()
                    + "." + methodName + "(" + parameterTypes.length + " args) -> " + t);
            return false;
        }
    }
}