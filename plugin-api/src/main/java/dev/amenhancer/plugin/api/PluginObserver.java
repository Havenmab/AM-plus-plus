package dev.amenhancer.plugin.api;
public interface PluginObserver {
    default void before(PluginObservation call) throws Exception {}
    default void after(PluginObservation call) throws Exception {}
}
