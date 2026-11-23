package com.ccrstech.openandroiddex.launcher;

import android.app.job.JobParameters;
import android.app.job.JobService;

/**
 * "Email me when an address changes" ({@link EmailNotice}): the job that
 * looks, waits for things to settle and sends, and the look every quarter of
 * an hour. Persisted and network-bound, so Android brings both back after a
 * restart (once the phone is unlocked) and an update, and holds a send until
 * there is a network. In the app's own process; the work is on a thread of
 * its own, since a send can take a minute.
 */
public final class EmailNoticeJob extends JobService {

    @Override
    public boolean onStartJob(JobParameters params) {
        Thread t = new Thread(() -> {
            long next = EmailNotice.IDLE;
            try {
                next = EmailNotice.step(this);
            } catch (RuntimeException e) {
                DexLog.warn("email", "the email's job: " + e);
            }
            // Finished before the next is asked for: scheduling the job that
            // is running would stop it.
            jobFinished(params, false);
            if (next >= 0) EmailNotice.scheduleStep(this, next);
        }, "email-notice-job");
        t.setDaemon(true);
        t.start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // The network went, or a newer look took the job's place: the state
        // is in files, and the next run carries on from there.
        return params.getJobId() == EmailNotice.JOB_STEP;
    }
}
