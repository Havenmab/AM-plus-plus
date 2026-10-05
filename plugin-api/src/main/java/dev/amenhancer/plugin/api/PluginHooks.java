package dev.amenhancer.plugin.api;

import java.lang.reflect.Executable;

public interface PluginHooks {
    PluginRegistration observe(Executable target, PluginObserver callback);
    PluginRegistration hook(Executable target, boolean exclusive, PluginHook callback);
}
