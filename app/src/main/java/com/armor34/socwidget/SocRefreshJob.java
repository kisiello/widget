package com.armor34.socwidget;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Android itself schedules refreshes; no external computer or bridge. */
public final class SocRefreshJob extends JobService {
    private static final int PERIODIC = 34001;
    private static final int IMMEDIATE = 34002;

    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        JobInfo job = new JobInfo.Builder(PERIODIC,
                new ComponentName(context, SocRefreshJob.class))
                .setPeriodic(15L * 60 * 1000)
                .setPersisted(true)
                .build();
        scheduler.schedule(job);
    }

    public static void refreshNow(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        scheduler.schedule(new JobInfo.Builder(IMMEDIATE,
                new ComponentName(context, SocRefreshJob.class))
                .setOverrideDeadline(0).build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        SocPoller.refresh(getApplicationContext(), () -> jobFinished(params, false));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
