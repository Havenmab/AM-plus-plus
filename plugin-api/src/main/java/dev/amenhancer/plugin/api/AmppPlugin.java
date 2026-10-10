package dev.amenhancer.plugin.api;

/** Independently compiled plugin entry. Public no-argument constructor required. */
public abstract class AmppPlugin {
    public abstract void onLoad(PluginContext context) throws Exception;
    public void onStart() throws Exception {}
    public void onStop() throws Exception {}
    /** Called on the UI thread, only while this plugin is active. */
    public PluginSettingsSession createSettings(android.content.Context context) throws Exception { return null; }
}
