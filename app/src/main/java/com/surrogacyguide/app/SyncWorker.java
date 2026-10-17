package com.surrogacyguide.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Runs the upload on a WorkManager background thread, so it finishes even if
 * the user has already closed the app. Retries automatically on failure.
 */
public class SyncWorker extends Worker {

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        return AutoSync.doSync(getApplicationContext()) ? Result.success() : Result.retry();
    }
}
