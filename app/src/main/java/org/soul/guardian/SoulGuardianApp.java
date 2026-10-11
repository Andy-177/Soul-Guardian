package org.soul.guardian;

import android.app.Application;

import org.soul.guardian.data.ConfigRepository;

public final class SoulGuardianApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        ThemeUtil.applyNightMode(this);
        new ConfigRepository(this).ensureDefaults();
    }
}
