package com.surrogacyguide.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Runs the upload on a WorkManager background thread so it finishes even if the
 * user closed the app. The daily job passes force=true for a heartbeat write.
 */
public class SyncWorker extends Worker {

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        boolean force = getInputData().getBoolean("force", false);
        return AutoSync.doSync(getApplicationContext(), force)
                ? Result.success() : Result.retry();
    }
}
