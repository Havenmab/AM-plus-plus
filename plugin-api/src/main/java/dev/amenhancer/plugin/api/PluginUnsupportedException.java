package dev.amenhancer.plugin.api;
/** Author-provided diagnosis for an unsupported host version or missing target. */
public final class PluginUnsupportedException extends Exception {
    public PluginUnsupportedException(String reason) { super(reason); }
}
