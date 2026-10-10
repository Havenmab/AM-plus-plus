package dev.amenhancer.plugin.api;

import android.app.Application;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;

public interface PluginContext {
    int API_VERSION = 1;
    Application getApplication();
    ClassLoader getHostClassLoader();
    String getHostPackageName();
    String getHostVersionName();
    long getHostVersionCode();
    PluginHooks getHooks();
    File getDataDirectory();
    File getCacheDirectory();
    InputStream openAsset(String path) throws IOException;
    void log(String message, Throwable error);
    void onClose(Runnable cleanup);
    PluginRegistration claimResource(String key, boolean exclusive);
}
