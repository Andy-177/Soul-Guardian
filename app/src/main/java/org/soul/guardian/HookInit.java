package org.soul.guardian;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import org.soul.guardian.hook.GuardianEngine;

/**
 * Xposed entry point.
 *
 * <p>Two jobs:
 * <ul>
 *   <li>In this module's own process it flips {@link MainActivity#isModuleActivated()} so the
 *       UI can report whether the module is active in the Xposed manager.</li>
 *   <li>In every other process it installs {@link GuardianEngine}, which masks banned words
 *       in {@code TextView} text using the word lists bundled inside this module's APK.</li>
 * </ul>
 */
public class HookInit implements IXposedHookLoadPackage {

    private static final String SELF_PACKAGE = "org.soul.guardian";
    private static final String TAG = "SoulGuardian";

    private static volatile GuardianEngine textHook;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            XposedHelpers.findAndHookMethod(
                    MainActivity.class.getName(),
                    lpparam.classLoader,
                    "isModuleActivated",
                    XC_MethodReplacement.returnConstant(true));
            return;
        }
        installTextViewHook(lpparam);
    }

    private static void installTextViewHook(LoadPackageParam lpparam) {
        try {
            hook(lpparam.packageName).install();
            Log.d(TAG, "TextView hook ready in " + lpparam.packageName);
        } catch (Throwable t) {
            Log.d(TAG, "failed to hook " + lpparam.packageName + " -> " + t);
        }
    }

    private static synchronized GuardianEngine hook(String packageName) {
        if (textHook == null) {
            textHook = new GuardianEngine(packageName);
        }
        return textHook;
    }
}
