package com.tink.zaobaoeink;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single background executor for the whole app + main-thread callback delivery. */
public final class Bg {

    public interface Work<T> {
        T run() throws Exception;
    }

    public interface Done<T> {
        void onDone(T result, Exception error);
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Bg() {
    }

    public static <T> void run(final Work<T> work, final Done<T> done) {
        EXEC.execute(new Runnable() {
            @Override
            public void run() {
                T result = null;
                Exception error = null;
                try {
                    result = work.run();
                } catch (Exception e) {
                    error = e;
                }
                final T r = result;
                final Exception err = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        done.onDone(r, err);
                    }
                });
            }
        });
    }

    /** Fire-and-forget background task (no callback). */
    public static void run(final Runnable task) {
        EXEC.execute(task);
    }
}
