package org.soul.guardian;

import android.util.Log;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import org.soul.guardian.hook.GuardianEngine;

/**
 * Xposed entry point.
 *
 * <p>The text hooks are installed once in the zygote ({@link #initZygote}) so the framework
 * {@code TextView}/{@code Canvas} methods are hooked before any app forks; this is what makes the
 * hooks actually take effect on boot-class methods. {@link #handleLoadPackage} only handles this
 * module's own process, flipping {@link MainActivity#isModuleActivated()} so the UI can report
 * whether the module is active in the Xposed manager.
 */
public class HookInit implements IXposedHookZygoteInit, IXposedHookLoadPackage {

    private static final String SELF_PACKAGE = "org.soul.guardian";
    private static final String TAG = "SoulGuardian";

    @Override
    public void initZygote(StartupParam startupParam) {
        GuardianEngine.installGlobal();
    }

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) {
        if (!SELF_PACKAGE.equals(lpparam.packageName)) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    MainActivity.class.getName(),
                    lpparam.classLoader,
                    "isModuleActivated",
                    XC_MethodReplacement.returnConstant(true));
        } catch (Throwable t) {
            Log.d(TAG, "self activation hook failed -> " + t);
        }
    }
}