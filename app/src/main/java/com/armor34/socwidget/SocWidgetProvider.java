package com.armor34.socwidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.SystemClock;
import android.widget.RemoteViews;

import java.util.Locale;

/** Home-screen widget backed by an on-device Shizuku UserService. */
public final class SocWidgetProvider extends AppWidgetProvider {
    private static final String PREFS = "soc_readings";
    private static final long STALE_AFTER_SECONDS = 25 * 60;

    @Override
    public void onEnabled(Context context) {
        SocRefreshJob.schedule(context);
        SocRefreshJob.refreshNow(context);
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        SocRefreshJob.schedule(context);
        onUpdateWidgets(context, manager, ids);
    }

    static void setStatus(Context context, String status) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("status", status).apply();
        updateAll(context);
    }

    static void save(Context context, int soc, int reported, int internal,
                     int logAge, String exact, boolean demo) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt("soc", soc)
                .putInt("reported", reported)
                .putInt("internal", internal)
                .putInt("age", logAge)
                .putString("exact", exact == null ? "" : exact)
                .putLong("receivedElapsed", SystemClock.elapsedRealtime())
                .putBoolean("demo", demo)
                .apply();
    }

    static void updateAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        ComponentName component = new ComponentName(context, SocWidgetProvider.class);
        int[] ids = manager.getAppWidgetIds(component);
        onUpdateWidgets(context, manager, ids);
    }

    private static void onUpdateWidgets(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) manager.updateAppWidget(id, makeViews(context));
    }

    private static RemoteViews makeViews(Context context) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_soc);
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int soc = p.getInt("soc", -1);
        int reported = p.getInt("reported", -1);
        int internal = p.getInt("internal", -1);
        int originalAge = p.getInt("age", 0);
        long receivedElapsed = p.getLong("receivedElapsed", -1);
        boolean demo = p.getBoolean("demo", false);

        String percent = "--%";
        String label = "NO DATA";
        String detail = "No calculated SoC yet";
        String ageLabel = p.getString("status", "Open app to connect Shizuku");
        int color = Color.rgb(255, 202, 126);
        int progress = 0;

        if (soc >= 0 && soc <= 100 && receivedElapsed >= 0) {
            long elapsed = SystemClock.elapsedRealtime() - receivedElapsed;
            boolean deviceRebooted = elapsed < 0;
            long ageSeconds = deviceRebooted ? STALE_AFTER_SECONDS + 1 :
                    originalAge + elapsed / 1000;
            boolean stale = ageSeconds > STALE_AFTER_SECONDS;

            percent = String.format(Locale.US, "%d%%", soc);
            progress = soc;
            label = demo ? "DEMO" : (stale ? "STALE" : "ESTIMATE");
            color = stale || demo ? Color.rgb(255, 202, 126) : Color.rgb(66, 217, 180);
            String reportedText = reported >= 0 ? String.format(Locale.US, "Android %d%%", reported) : "Android N/A";
            String internalText = internal >= 0 ? String.format(Locale.US, " · UI %d%%", internal) : "";
            detail = reportedText + internalText;
            if (demo) {
                ageLabel = "Test data — not a battery reading";
            } else if (deviceRebooted) {
                ageLabel = "Device restarted · waiting for new sample";
            } else if (stale) {
                ageLabel = String.format(Locale.US, "STALE · last log %d min ago", ageSeconds / 60);
            } else if (ageSeconds < 120) {
                ageLabel = String.format(Locale.US, "Kernel log %ds old", ageSeconds);
            } else {
                ageLabel = String.format(Locale.US, "Kernel log %d min old", ageSeconds / 60);
            }
        }

        views.setTextViewText(R.id.widget_percent, percent);
        views.setTextViewText(R.id.widget_marker, label);
        views.setTextViewText(R.id.widget_subtitle, detail);
        views.setTextViewText(R.id.widget_age, ageLabel);
        views.setTextColor(R.id.widget_percent, color);
        views.setProgressBar(R.id.widget_bar, 100, progress, false);

        Intent open = new Intent(context, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, pi);
        return views;
    }
}
