package dev.amenhancer.plugin.api;
import android.view.View;
public interface PluginSettingsSession extends AutoCloseable {
    View getView();
    @Override void close();
}
