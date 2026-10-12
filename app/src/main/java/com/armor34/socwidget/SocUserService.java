package com.armor34.socwidget;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Executed through Shizuku UserService as Android shell UID, NOT as a normal app. */
public final class SocUserService extends ISocReader.Stub {
    private static final Pattern TIME = Pattern.compile("^<\\d+>\\[\\s*(\\d+)\\.\\d+\\]");
    private static final Pattern CAR = Pattern.compile("\\bcar\\[[^]]+\\].*?\\bsoc:(\\d+)");
    private static final Pattern FGADC = Pattern.compile("\\[FGADC_intr_end\\].*?\\bsoc:(\\d+)");
    private static final Pattern OK = Pattern.compile("(?m)^OK:(/[^\\r\\n]+)\\s*$");
    private static final int MAX_AGE_SECONDS = 1200;
    private static final String BUGREPORT_DIR = "/data/user_de/0/com.android.shell/files/bugreports/";

    public SocUserService() {}

    @Override
    public String capture() {
        File report = null;
        try {
            // Identical entrypoint used by `adb bugreport`; output includes OK:<path>.
            Process process = new ProcessBuilder("/system/bin/bugreportz", "-p")
                    .redirectErrorStream(true).start();
            StringBuilder message = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (message.length() < 100000) message.append(line).append('\n');
                }
            }
            if (!process.waitFor(150, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return error("bugreportz timed out");
            }
            Matcher ok = OK.matcher(message);
            if (!ok.find()) return error("bugreportz failed: " + truncate(message.toString()));

            String path = ok.group(1).trim();
            report = new File(path).getCanonicalFile();
            if (!report.getPath().startsWith(BUGREPORT_DIR) || !report.getName().endsWith(".zip")) {
                return error("bugreportz returned an unexpected path");
            }
            if (!report.isFile()) return error("Generated bugreport not readable");

            long uptime = SystemClock.elapsedRealtime() / 1000;
            Measurement newest = new Measurement();
            try (ZipFile zip = new ZipFile(report)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (!entry.getName().matches("(?:.*/)?bugreport-[^/]+\\.txt")) continue;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            zip.getInputStream(entry), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Matcher time = TIME.matcher(line);
                            if (!time.find()) continue;
                            long timestamp = Long.parseLong(time.group(1));
                            if (timestamp > uptime || uptime - timestamp > MAX_AGE_SECONDS ||
                                    timestamp < newest.timestamp) continue;
                            Matcher car = CAR.matcher(line);
                            Matcher fg = FGADC.matcher(line);
                            double soc;
                            String source;
                            if (car.find()) {
                                soc = Integer.parseInt(car.group(1));
                                source = "car";
                            } else if (fg.find()) {
                                soc = Integer.parseInt(fg.group(1)) / 100.0;
                                source = "FGADC";
                            } else continue;
                            if (soc < 0 || soc > 100) continue;
                            newest.timestamp = timestamp;
                            newest.soc = soc;
                            newest.source = source;
                        }
                    }
                }
            }
            if (newest.timestamp < 0) return error("No recent kernel soc samples in bugreport");
            int published = integerFromFile("/sys/class/power_supply/battery/capacity");
            int counter = integerFromFile("/sys/class/power_supply/battery/charge_counter");
            int full = integerFromFile("/sys/class/power_supply/battery/charge_full");
            int internal = full > 0 && counter >= 0 ? (int)(100L * counter / full) : -1;
            return new JSONObject()
                    .put("ok", true)
                    .put("soc", newest.soc)
                    .put("age", uptime - newest.timestamp)
                    .put("reported", published)
                    .put("internal", internal)
                    .put("source", newest.source)
                    .toString();
        } catch (Exception e) {
            Log.w("ArmorSoc", "On-device capture failed", e);
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            // Our shell process generated this report. Avoid retaining private reports.
            if (report != null && report.getPath().startsWith(BUGREPORT_DIR)) {
                //noinspection ResultOfMethodCallIgnored
                report.delete();
            }
        }
    }

    private static int integerFromFile(String path) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8))) {
            return Integer.parseInt(r.readLine().trim());
        } catch (Exception ex) { return -1; }
    }

    private static String error(String detail) {
        try { return new JSONObject().put("ok", false).put("error", detail).toString(); }
        catch (Exception ex) { return "{\"ok\":false}"; }
    }

    private static String truncate(String s) {
        return s.length() <= 200 ? s : s.substring(s.length() - 200);
    }

    private static class Measurement {
        long timestamp = -1;
        double soc;
        String source;
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
