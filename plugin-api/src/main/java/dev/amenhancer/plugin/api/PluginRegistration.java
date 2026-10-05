package dev.amenhancer.plugin.api;

/** Logical lifetime; does not promise physical framework unhook. */
public interface PluginRegistration extends AutoCloseable {
    @Override void close();
}
