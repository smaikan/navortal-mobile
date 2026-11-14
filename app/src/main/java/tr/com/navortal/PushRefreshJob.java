package tr.com.navortal;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.TimeUnit;

/** Uygulama açılmasa da günde iki kez FCM token'ını ve cihaz kaydını yoklar (süreli, ağ gerektirir, yeniden başlatmada sürer). */
public final class PushRefreshJob extends JobService {
    private static final int JOB_ID = 9301;

    static void schedule(Context context) {
        var scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null || scheduler.getPendingJob(JOB_ID) != null) return;
        var info = new JobInfo.Builder(JOB_ID, new ComponentName(context, PushRefreshJob.class))
                .setPeriodic(TimeUnit.HOURS.toMillis(12))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build();
        try { scheduler.schedule(info); } catch (RuntimeException ignored) { /* zamanlanamazsa açılışta yine denenir */ }
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (PushStore.boundUserId(this) != null) PushRegistrar.request(this, false);
        return false;
    }

    @Override public boolean onStopJob(JobParameters params) { return false; }
}
