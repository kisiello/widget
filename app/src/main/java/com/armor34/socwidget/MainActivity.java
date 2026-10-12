package com.armor34.socwidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import rikka.shizuku.Shizuku;

/** Single setup screen. No desktop ADB/bridge is used at runtime. */
public final class MainActivity extends Activity {
    private TextView status;
    private final Shizuku.OnBinderReceivedListener binderListener = this::refreshState;
    private final Shizuku.OnBinderDeadListener deadListener = this::refreshState;
    private final Shizuku.OnRequestPermissionResultListener permissionListener = (requestCode, result) -> {
        refreshState();
        if (result == PackageManager.PERMISSION_GRANTED) {
            SocRefreshJob.refreshNow(this);
            Toast.makeText(this, "Reading will update on the home screen", Toast.LENGTH_LONG).show();
        }
    };

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        SocRefreshJob.schedule(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(32), dp(22), dp(22));
        root.setBackgroundColor(Color.rgb(23, 33, 45));

        TextView title = new TextView(this);
        title.setText("Armor 34 Pro · On-device SoC");
        title.setTextSize(23);
        title.setTextColor(Color.rgb(66, 217, 180));
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(Color.WHITE);
        status.setLineSpacing(dp(4), 1.05f);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.topMargin = dp(16);
        root.addView(status, statusParams);

        TextView body = new TextView(this);
        body.setText("This widget reads the MediaTek kernel's calculated SoC using Shizuku " +
                "and a bugreport generated entirely ON THIS PHONE.\n\n" +
                "1. Install Shizuku and start it using Wireless debugging.\n" +
                "2. Grant this app Shizuku access below.\n" +
                "3. Add the widget and tap Refresh now.\n\n" +
                "Android schedules refreshes approximately every 15 minutes. " +
                "Shizuku normally needs restarting after each phone reboot. " +
                "No root or computer connection is needed during operation.\n\n" +
                "The calculated SoC is an estimate; your phone's battery gauge is still faulty.");
        body.setTextColor(Color.rgb(220,231,239));
        body.setTextSize(14);
        body.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyParams.topMargin = dp(18);
        root.addView(body, bodyParams);

        Button shizuku = new Button(this);
        shizuku.setText("Open Shizuku / grant access");
        shizuku.setOnClickListener(v -> requestAccess());
        root.addView(shizuku);

        Button add = new Button(this);
        add.setText("Add home-screen widget");
        add.setOnClickListener(v -> {
            AppWidgetManager m = AppWidgetManager.getInstance(this);
            if (m.isRequestPinAppWidgetSupported()) {
                m.requestPinAppWidget(new ComponentName(this, SocWidgetProvider.class), null, null);
            } else {
                Toast.makeText(this, "Long-press home screen → Widgets → Armor SoC",
                        Toast.LENGTH_LONG).show();
            }
        });
        root.addView(add);

        Button refresh = new Button(this);
        refresh.setText("Refresh from phone now");
        refresh.setOnClickListener(v -> {
            SocRefreshJob.refreshNow(this);
            Toast.makeText(this, "On-device capture scheduled", Toast.LENGTH_SHORT).show();
        });
        root.addView(refresh);
        setContentView(root);

        Shizuku.addBinderReceivedListenerSticky(binderListener);
        Shizuku.addBinderDeadListener(deadListener);
        Shizuku.addRequestPermissionResultListener(permissionListener);
        refreshState();
    }

    private void requestAccess() {
        try {
            if (!Shizuku.pingBinder()) {
                Intent intent = getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");
                if (intent != null) startActivity(intent);
                else Toast.makeText(this, "Install Shizuku, then start via Wireless debugging", Toast.LENGTH_LONG).show();
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                SocRefreshJob.refreshNow(this);
                Toast.makeText(this, "Access granted; requesting a new reading", Toast.LENGTH_LONG).show();
            } else Shizuku.requestPermission(340);
        } catch (Exception e) {
            Toast.makeText(this, "Shizuku is not ready", Toast.LENGTH_LONG).show();
        }
    }

    private void refreshState() {
        runOnUiThread(() -> {
            String text;
            try {
                if (!Shizuku.pingBinder()) text = "Shizuku: stopped / not installed";
                else if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                    text = "Shizuku: running, permission required";
                else text = "Shizuku: ready — on-device polling enabled";
            } catch (Exception e) { text = "Shizuku: unavailable"; }
            status.setText(text);
        });
    }

    @Override
    protected void onDestroy() {
        Shizuku.removeBinderReceivedListener(binderListener);
        Shizuku.removeBinderDeadListener(deadListener);
        Shizuku.removeRequestPermissionResultListener(permissionListener);
        super.onDestroy();
    }

    private int dp(int v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
