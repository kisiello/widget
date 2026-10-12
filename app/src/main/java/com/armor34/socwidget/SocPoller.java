package com.armor34.socwidget;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

import rikka.shizuku.Shizuku;

/** Connects to the on-device Shizuku shell service, updates widget on result. */
public final class SocPoller {
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SocPoller() {}

    public static void refresh(Context ctx, Runnable callback) {
        Context app = ctx.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) { callback.run(); return; }
        try {
            if (!Shizuku.pingBinder()) {
                finish(app, "Shizuku offline — start it on your phone", callback);
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                finish(app, "Grant Shizuku access in the app", callback);
                return;
            }
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(app, SocUserService.class))
                    .processNameSuffix("socreader")
                    .daemon(false).version(2);

            final AtomicBoolean finished = new AtomicBoolean(false);
            final ServiceConnection[] ref = new ServiceConnection[1];
            Runnable timeout = () -> {
                if (!finished.compareAndSet(false, true)) return;
                SocWidgetProvider.setStatus(app, "Capture timed out");
                safelyUnbind(args, ref[0]);
                BUSY.set(false);
                callback.run();
            };
            ref[0] = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    ISocReader reader = ISocReader.Stub.asInterface(binder);
                    new Thread(() -> {
                        String message;
                        try { message = reader.capture(); }
                        catch (Exception e) { message = "{\"ok\":false,\"error\":\"Capture IPC failed\"}"; }
                        String result = message;
                        MAIN.post(() -> {
                            if (!finished.compareAndSet(false, true)) return;
                            MAIN.removeCallbacks(timeout);
                            apply(app, result);
                            safelyUnbind(args, ref[0]);
                            BUSY.set(false);
                            callback.run();
                        });
                    }, "SocReportWorker").start();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    // Timeout handles service termination while capture is underway.
                }
            };
            MAIN.postDelayed(timeout, 160_000);
            try {
                Shizuku.bindUserService(args, ref[0]);
            } catch (Exception e) {
                MAIN.removeCallbacks(timeout);
                if (finished.compareAndSet(false, true)) {
                    finish(app, "Shizuku error: " + e.getClass().getSimpleName(), callback);
                }
            }
        } catch (Exception e) {
            finish(app, "Shizuku error: " + e.getClass().getSimpleName(), callback);
        }
    }

    private static void safelyUnbind(Shizuku.UserServiceArgs args, ServiceConnection connection) {
        try { if (connection != null) Shizuku.unbindUserService(args, connection, true); }
        catch (Exception ignored) {}
    }

    private static void apply(Context app, String message) {
        try {
            JSONObject result = new JSONObject(message);
            if (!result.optBoolean("ok", false)) {
                SocWidgetProvider.setStatus(app, "No reading: " + result.optString("error", "unknown error"));
                return;
            }
            double exact = result.getDouble("soc");
            int rounded = (int) Math.round(exact);
            SocWidgetProvider.save(app, rounded, result.optInt("reported", -1),
                    result.optInt("internal", -1), result.optInt("age", 0),
                    String.format(java.util.Locale.US, "%.2f", exact), false);
            SocWidgetProvider.setStatus(app, "OK: " + exact + "% from " + result.optString("source", "kernel"));
        } catch (Exception e) {
            SocWidgetProvider.setStatus(app, "Bad capture response");
        }
    }

    private static void finish(Context app, String reason, Runnable callback) {
        SocWidgetProvider.setStatus(app, reason);
        BUSY.set(false);
        callback.run();
    }
}
