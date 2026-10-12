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
 * <p>LSPosed only injects the module into the processes inside the module's scope, so the hooks
 * take effect there. The word list itself is read per process from the provider.
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
        hookTextView(hook);
        hookCanvas(hook);
        hookApplicationCreate(hook);
    }

    /** Per-process bootstrap: once the Application exists, sync the word list. */
    private static void hookApplicationCreate(final TextMaskHook hook) {
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
                                    hook.setPackageName(pkg);
                                    WordsSync.attach(ctx, pkg, hook);
                                }
                            } catch (Throwable t) {
                                Log.d(TAG, "[" + pkg + "] sync failed -> " + t);
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.d(TAG, "unable to hook app create -> " + t);
        }
    }

    /**
     * {@link TextView} text entry points. The terminal {@code setText} overload is the one every
     * public overload funnels into (documented framework behaviour, not an implementation
     * detail); the leaf overloads are kept as a fallback for builds that inline the terminal one.
     */
    private static void hookTextView(TextMaskHook hook) {
        hookMethod(TextView.class, "setText", hook,
                CharSequence.class, TextView.BufferType.class, boolean.class, int.class);
        hookMethod(TextView.class, "setText", hook, CharSequence.class);
        hookMethod(TextView.class, "setText", hook, CharSequence.class, TextView.BufferType.class);
        hookMethod(TextView.class, "setText", hook,
                CharSequence.class, TextView.BufferType.class, boolean.class);
        hookMethod(TextView.class, "setText", hook, char[].class, int.class, int.class);
        hookMethod(TextView.class, "setText", hook, int.class);
        hookMethod(TextView.class, "setText", hook, int.class, TextView.BufferType.class);

        hookMethod(TextView.class, "setHint", hook, CharSequence.class);
        hookMethod(TextView.class, "setHint", hook, int.class);

        hookMethod(TextView.class, "append", hook, CharSequence.class);
        hookMethod(TextView.class, "append", hook, CharSequence.class, int.class, int.class);
    }

    /** Text painted directly through {@link Canvas}, for views that render their own text. */
    private static void hookCanvas(TextMaskHook hook) {
        hookMethod(Canvas.class, "drawText", hook,
                String.class, float.class, float.class, Paint.class);
        hookMethod(Canvas.class, "drawText", hook,
                String.class, int.class, int.class, float.class, float.class, Paint.class);
        hookMethod(Canvas.class, "drawText", hook,
                CharSequence.class, int.class, int.class, float.class, float.class, Paint.class);
        hookMethod(Canvas.class, "drawText", hook,
                char[].class, int.class, int.class, float.class, float.class, Paint.class);
    }

    private static void hookMethod(Class<?> target, String methodName, XC_MethodHook callback,
                                   Object... parameterTypes) {
        try {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = callback;
            XposedHelpers.findAndHookMethod(target, methodName, args);
        } catch (Throwable t) {
            Log.d(TAG, "unable to hook " + target.getSimpleName()
                    + "." + methodName + "(" + parameterTypes.length + " args) -> " + t);
        }
    }
}