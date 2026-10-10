package dev.amenhancer.plugin.api;

import java.lang.reflect.Executable;

/** Changes commit only after the callback returns successfully. */
public final class PluginCall {
    private final Executable method;
    private final Object receiver;
    private final Object[] arguments;
    private Object result;
    private Throwable throwable;
    private boolean outcomeChanged;
    public PluginCall(Executable method, Object receiver, Object[] arguments, Object result, Throwable throwable) {
        this.method = method; this.receiver = receiver; this.arguments = arguments.clone(); this.result = result; this.throwable = throwable;
    }
    public Executable getMethod() { return method; }
    public Object getReceiver() { return receiver; }
    public Object[] getArguments() { return arguments; }
    public Object getResult() { return result; }
    public Throwable getThrowable() { return throwable; }
    public void returnResult(Object value) { result = value; throwable = null; outcomeChanged = true; }
    public void throwException(Throwable error) { if (error == null) throw new IllegalArgumentException("error"); throwable = error; outcomeChanged = true; }
    public boolean isOutcomeChanged() { return outcomeChanged; }
}
