package org.soul.guardian.hook;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import android.widget.TextView;

import org.soul.guardian.core.FilterConfig;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hooks {@link TextView#setText} in a host process and keeps a live {@link TextMaskHook}
 * in sync with the module's word list via {@link WordsSync}.
 */
public final class GuardianEngine {

    private static final String TAG = "SoulGuardian";

    private final String packageName;

    public GuardianEngine(String packageName) {
        this.packageName = packageName == null ? "?" : packageName;
    }

    public void install() {
        final TextMaskHook hook = new TextMaskHook(packageName, FilterConfig.disabled());
        hookSetText(hook, "setText", CharSequence.class);
        hookSetText(hook, "setText", CharSequence.class, TextView.BufferType.class);
        hookSetText(hook, "setText", CharSequence.class, TextView.BufferType.class,
                boolean.class);
        hookSetText(hook, "setText", char[].class, int.class, int.class);
        hookSetText(hook, "setText", int.class);
        hookSetText(hook, "setText", int.class, TextView.BufferType.class);

        Context context = currentApplication();
        Log.d(TAG, "[" + packageName + "] install: setText hooked, context="
                + (context != null));
        if (context != null) {
            WordsSync.attach(context, packageName, hook);
        } else {
            hookApplicationAttach(hook);
        }
    }

    /** The Application is created after {@code handleLoadPackage}; sync once it is usable. */
    private void hookApplicationAttach(final TextMaskHook hook) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Instrumentation", null,
                    "callApplicationOnCreate", Application.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object app = param.args[0];
                            if (app instanceof Context) {
                                Context ctx = (Context) app;
                                try {
                                    if (ctx.getContentResolver() != null) {
                                        WordsSync.attach(ctx, packageName, hook);
                                    }
                                } catch (Throwable t) {
                                    Log.d(TAG, "[" + packageName + "] sync failed -> " + t);
                                }
                            }
                        }
                    });
        } catch (Throwable t) {
            Log.d(TAG, "[" + packageName + "] unable to hook app create -> " + t);
        }
    }

    /**
     * Boot-classpath safe replacement for Xposed's {@code AndroidAppHelper}: the API jar only
     * carries a compile stub for that class, so calling it on device throws
     * {@code NoClassDefFoundError}.
     */
    private static Context currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object app = XposedHelpers.callStaticMethod(activityThread, "currentApplication");
            if (app instanceof Context) {
                return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private void hookSetText(XC_MethodHook callback, String methodName,
                             Object... parameterTypes) {
        try {
            Object[] args = new Object[parameterTypes.length + 1];
            System.arraycopy(parameterTypes, 0, args, 0, parameterTypes.length);
            args[parameterTypes.length] = callback;
            XposedHelpers.findAndHookMethod(TextView.class, methodName, args);
        } catch (Throwable t) {
            Log.d(TAG, "[" + packageName + "] unable to hook " + methodName
                    + "(" + parameterTypes.length + " args) -> " + t);
        }
    }
}