package dev.amenhancer.plugin.api;

import java.lang.reflect.Executable;

/** Argument array is copied. Referenced host objects are not deep copied. */
public final class PluginObservation {
    private final Executable method;
    private final Object receiver;
    private final Object[] arguments;
    private final Object result;
    private final Throwable throwable;
    public PluginObservation(Executable method, Object receiver, Object[] arguments, Object result, Throwable throwable) {
        this.method = method; this.receiver = receiver; this.arguments = arguments.clone(); this.result = result; this.throwable = throwable;
    }
    public Executable getMethod() { return method; }
    public Object getReceiver() { return receiver; }
    public Object[] getArguments() { return arguments.clone(); }
    public Object getResult() { return result; }
    public Throwable getThrowable() { return throwable; }
}
