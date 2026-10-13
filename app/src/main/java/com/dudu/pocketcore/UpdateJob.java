package com.dudu.pocketcore;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

/** 자동 업데이트 작업 — 앱을 안 쓰는 동안 새 판을 받아 확인 창 없이 깐다(SelfUpdate 참고). */
public class UpdateJob extends JobService {
    @Override public boolean onStartJob(final JobParameters p) {
        new Thread(new Runnable() { @Override public void run() {
            String r;
            try { r = SelfUpdate.runBackground(UpdateJob.this); }
            catch (Throwable t) { r = "error " + t; }
            Log.i(SelfUpdate.TAG, "job " + p.getJobId() + ": " + r);
            jobFinished(p, false);
        }}, "selfupdate").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return false; }
}
