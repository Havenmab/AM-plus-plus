package dev.amenhancer.plugin.api;
public interface PluginHook {
    default void before(PluginCall call) throws Exception {}
    default void after(PluginCall call) throws Exception {}
}
