package example;

import android.app.Activity;
import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Switch;
import dev.amenhancer.plugin.api.*;
import java.io.*;

public final class BasicPlugin extends AmppPlugin {
    private PluginContext runtime;
    @Override public void onLoad(PluginContext context) throws Exception {
        runtime = context;
        context.getHooks().observe(Activity.class.getDeclaredMethod("onResume"), new PluginObserver() {
            @Override public void after(PluginObservation call) { runtime.log("Activity resumed: " + call.getReceiver().getClass().getName(), null); }
        });
        context.log("Prepared for " + context.getHostPackageName() + " " + context.getHostVersionName(), null);
    }
    @Override public void onStart() { runtime.log("Example started", null); }
    @Override public void onStop() { runtime.log("Example stopped", null); }
    @Override public PluginSettingsSession createSettings(Context context) throws Exception {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 24);
        TextView title = new TextView(context);
        try (InputStream in = runtime.openAsset("welcome.txt"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            title.setText(out.toString("UTF-8"));
        }
        layout.addView(title);
        File saved = new File(runtime.getDataDirectory(), "example-enabled");
        Switch option = new Switch(context); option.setText("示例持久化选项"); option.setChecked(saved.exists());
        option.setOnCheckedChangeListener((view, enabled) -> {
            try { if (enabled) { try (FileOutputStream out = new FileOutputStream(saved)) { out.write(1); } } else if (saved.exists() && !saved.delete()) throw new IOException("Delete failed"); }
            catch (IOException error) { runtime.log("Settings write failed", error); }
        });
        layout.addView(option);
        return new PluginSettingsSession() {
            @Override public android.view.View getView() { return layout; }
            @Override public void close() { option.setOnCheckedChangeListener(null); }
        };
    }
}
