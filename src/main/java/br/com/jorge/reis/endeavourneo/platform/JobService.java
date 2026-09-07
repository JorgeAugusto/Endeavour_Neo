/*
 * Endeavour Neo -- a desktop application shell in Swing.
 * Copyright (C) 2026  Jorge Reis
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package br.com.jorge.reis.endeavourneo.platform;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Where every long-running task in the application goes.
 *
 * <p>The interface thread — one per application, not one per window — does two
 * things: it delivers events and it paints. Anything slow that runs on it stops
 * both, for <b>every</b> window at once, and the operating system reports the
 * program as not responding. The screen did not freeze; the thread is busy.</p>
 *
 * <p>So the rule is simple and absolute: <b>work here, never on the interface
 * thread</b>. This class exists to make following that rule easier than
 * breaking it.</p>
 *
 * <p>It is Eclipse's Jobs API in miniature, and it buys three things a bare
 * {@code SwingWorker} does not:</p>
 *
 * <ul>
 *   <li><b>Visibility.</b> Something knows what is running. Without a registry,
 *       "why is this slow" has no answer.</li>
 *   <li><b>Cancellation.</b> The complaint that follows "it freezes" is always
 *       "and I cannot stop it".</li>
 *   <li><b>Failures that surface.</b> An exception inside {@code
 *       SwingWorker.doInBackground} is <i>swallowed</i> until someone calls
 *       {@code get()} — a job dies and the interface shows nothing at all. Here
 *       a failure always reaches a handler.</li>
 * </ul>
 */
public final class JobService implements AutoCloseable {

    /** What a job does. Runs OFF the interface thread. */
    @FunctionalInterface
    public interface Work<T> {

        T run(Progress progress) throws Exception;
    }

    /**
     * A job that has been submitted.
     *
     * <p>The callbacks are always delivered on the interface thread, so they may
     * touch components directly. That is the whole point of the split: the work
     * runs where it cannot block the screen, and the result arrives where it can
     * be drawn.</p>
     */
    public final class Handle<T> {

        private final String name;

        private Future<?> future;

        private Consumer<T> onDone;

        private Consumer<Throwable> onFailed;

        /**
         * The outcome, held until someone is listening.
         *
         * <p><b>This is the fix for a race that cost a test.</b> {@code submit}
         * starts the work immediately and returns the handle; the caller then
         * chains {@code whenDone}. A job that finishes in under a millisecond —
         * and many do — completes <i>before</i> the callback exists, and the
         * result would be delivered to nobody. Silently.</p>
         *
         * <p>So the outcome is stored, and whichever happens second — the job
         * finishing or the callback being registered — triggers delivery.
         * Exactly once, guarded by {@code delivered}.</p>
         */
        private boolean settled;

        private boolean delivered;

        private boolean wasCancelled;

        private Runnable onStopped;

        private T value;

        private Throwable error;

        private Handle(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        /** @param action what to do with the result, on the interface thread */
        public synchronized Handle<T> whenDone(Consumer<T> action) {
            this.onDone = action;

            deliver();

            return this;
        }

        /**
         * @param action what to do with the failure, on the interface thread
         *
         * <p>Not setting this is legitimate — the failure is then printed to
         * standard output, which the console captures. What is never acceptable
         * is the failure vanishing, which is what a bare {@code SwingWorker}
         * does.</p>
         */
        public synchronized Handle<T> whenFailed(Consumer<Throwable> action) {
            this.onFailed = action;

            deliver();

            return this;
        }

        /**
         * @param action what to do when the job stopped because it was asked to
         *
         * <p><b>A third outcome, and not a fourth kind of failure.</b> Stopping
         * on request is neither a result nor a fault, which is why deliver()
         * hands it to neither -- and for a long time that meant it was handed to
         * NOBODY, so a caller that had put a spinner up on submit had no moment
         * at which to take it down. Cancelling looked exactly like a job that
         * never finished.</p>
         *
         * <p>Optional, like the other two: a caller with nothing to undo simply
         * does not set it.</p>
         */
        public synchronized Handle<T> whenStopped(Runnable action) {
            this.onStopped = action;

            deliver();

            return this;
        }

        /** Last chance for a failure nobody handled to be seen at all. */
        synchronized void reportIfUnclaimed() {
            if (settled && !delivered && error != null) {
                delivered = true;

                FAILURES.println("job \"" + name + "\" failed and nobody handled it:");
                error.printStackTrace(FAILURES);
                FAILURES.flush();
            }
        }

        synchronized void settle(T result, Throwable failure, boolean cancelled) {
            this.settled = true;
            this.value = result;
            this.error = failure;
            this.wasCancelled = cancelled;

            deliver();
        }

        /**
         * Hands the outcome to whoever is listening, at most once.
         *
         * <p>Called from both sides of the race and idempotent, so it does not
         * matter which arrives first.</p>
         */
        private synchronized void deliver() {
            if (!settled || delivered) {
                return;
            }

            if (wasCancelled) {
                // Stopping on request is not a result and not a failure -- and
                // for a long time that was taken to mean it was nothing at all,
                // so nobody was told. A caller that raised a spinner on submit
                // had no moment at which to lower it, and a cancelled job was
                // indistinguishable from one still running.
                //
                // With no handler registered yet, stay pending, exactly as the
                // other two outcomes do: whoever chains whenStopped next gets it.
                if (onStopped == null) {
                    return;
                }

                Runnable handler = onStopped;

                delivered = true;

                onEdt(handler);

                return;
            }

            // With no handler registered YET, stay pending. Whoever chains the
            // callback next triggers this again and receives the outcome. The
            // job finishing first must never consume it.
            if (error != null) {
                if (onFailed == null) {
                    return;
                }

                Consumer<Throwable> handler = onFailed;
                Throwable failure = error;

                delivered = true;

                onEdt(() -> handler.accept(failure));

                return;
            }

            if (onDone == null) {
                return;
            }

            Consumer<T> handler = onDone;
            T result = value;

            delivered = true;

            onEdt(() -> handler.accept(result));
        }

        public void cancel() {
            cancelled.add(this);

            if (future != null) {
                // false: never interrupt. Interrupting a thread mid-write can
                // leave shared state torn, and the cooperative check is enough
                // for jobs that follow the contract.
                future.cancel(false);
            }
        }

        public boolean isCancelled() {
            return cancelled.contains(this);
        }
    }

    /**
     * Where a last-chance failure is written, taken before anything redirects it.
     *
     * <p><b>Captured at class load, and that is the whole point.</b> The console
     * window replaces {@code System.err} with a stream that hands each line to
     * the interface thread, and {@code close()} is called from a shutdown hook.
     * The JVM does not wait for the interface thread to drain, so the one report
     * this class exists to never lose was being posted to a queue that would not
     * run again -- printed into a window that was already going away.</p>
     *
     * <p>This class is loaded when the Launcher builds the service, which is
     * before the first window and therefore before that redirect. Holding the
     * stream from then on means the report reaches wherever the application's
     * output actually goes.</p>
     */
    private static final java.io.PrintStream FAILURES = System.err;

    /** Leaves one core for the interface thread, and never fewer than two. */
    private static final int THREADS =
            Math.max(2, Runtime.getRuntime().availableProcessors() - 1);

    private final ExecutorService pool;

    private final List<Handle<?>> running = new CopyOnWriteArrayList<>();

    private final List<Handle<?>> cancelled = new CopyOnWriteArrayList<>();

    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    /**
     * Jobs that failed and whose failure nobody claimed.
     *
     * <p>Staying pending is what lets a late {@code whenFailed} still receive
     * the error. But pending forever would mean a job died and nothing ever
     * said so — the exact silence this class exists to prevent. So the unclaimed
     * ones are reported on {@link #close()}.</p>
     */
    private final List<Handle<?>> unclaimed = new CopyOnWriteArrayList<>();

    private volatile String stage = "";

    private volatile double fraction = -1.0;

    public JobService() {
        AtomicInteger counter = new AtomicInteger();

        ThreadFactory factory = task -> {
            // Named and daemon, both deliberately. Named so a thread dump says
            // which pool is stuck; daemon so a forgotten job cannot keep the
            // JVM alive after the last window closes.
            Thread thread = new Thread(task, "job-" + counter.incrementAndGet());

            thread.setDaemon(true);

            return thread;
        };

        this.pool = Executors.newFixedThreadPool(THREADS, factory);
    }

    /**
     * @param name shown in the status bar; short and in the user's words
     * @param work what to do, off the interface thread
     * @return the handle, for callbacks and cancellation
     */
    public <T> Handle<T> submit(String name, Work<T> work) {
        Handle<T> handle = new Handle<>(name);

        running.add(handle);
        notifyListeners();

        handle.future = pool.submit(() -> {
            boolean stopped = false;
            T value = null;
            Throwable failure = null;

            try {
                value = work.run(progressFor(handle));
                stopped = handle.isCancelled();
            } catch (Throwable e) {
                // Caught here rather than left to the pool: an exception that
                // reaches the executor disappears without a trace.
                //
                // Throwable, not Exception: the likeliest failure this service
                // will ever see is OutOfMemoryError, because the work it runs
                // reads a million bars at a time. Catching Exception left that
                // one -- and only that one -- to escape past the catch and run
                // the finally with failure still null, which settled the job as
                // a SUCCESS carrying a null value. The single failure this
                // class exists to report was the single one it could not.
                failure = e;
            } finally {
                // Settle BEFORE leaving the running list, so a listener woken
                // by the change already sees a consistent outcome.
                handle.settle(value, failure, stopped);

                if (failure != null) {
                    unclaimed.add(handle);
                }

                running.remove(handle);
                cancelled.remove(handle);

                if (running.isEmpty()) {
                    stage = "";
                    fraction = -1.0;
                }

                notifyListeners();
            }
        });

        return handle;
    }

    private Progress progressFor(Handle<?> handle) {
        return new Progress() {

            @Override
            public void report(double value) {
                fraction = value;
                notifyListeners();
            }

            @Override
            public boolean cancelled() {
                return handle.isCancelled();
            }

            @Override
            public void say(String text) {
                stage = text == null ? "" : text;
                notifyListeners();
            }
        };
    }

    /** @param listener called on the interface thread whenever anything changes */
    public void onChange(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * @param listener the one to stop calling; the same object handed to
     *                 {@link #onChange}, so a caller that wants this has to keep it
     *
     * <p><b>There has to be a way out.</b> This only ever added, and the one
     * window that registers is built again on every change of language: each one
     * left a dead status bar in here, holding the whole component tree of a
     * window already disposed, and every job's progress went on refreshing
     * components nobody can see. The javadoc of the hook in {@code
     * SeriesCatalog} says when adding alone is fair -- "registered once at
     * startup and never removed" -- and that was never true here.</p>
     *
     * <p>Silent when it was never registered: unsubscribing twice, or after the
     * service was already shut, is a caller being careful.</p>
     */
    public void removeOnChange(Runnable listener) {
        listeners.remove(listener);
    }

    /** @return how many are subscribed; for the test that says a discarded window let go */
    public int listenerCount() {
        return listeners.size();
    }

    /** @return the names of the jobs running now, in submission order */
    public List<String> runningNames() {
        return running.stream().map(Handle::name).toList();
    }

    /** @return the current stage text, or empty */
    public String stage() {
        return stage;
    }

    /** @return progress from 0 to 1, or negative when unknown */
    public double fraction() {
        return fraction;
    }

    public boolean isBusy() {
        return !running.isEmpty();
    }

    /** Asks every running job to stop. */
    public void cancelAll() {
        for (Handle<?> handle : running) {
            handle.cancel();
        }
    }

    private void notifyListeners() {
        onEdt(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    @Override
    public void close() {
        for (Handle<?> handle : unclaimed) {
            handle.reportIfUnclaimed();
        }

        unclaimed.clear();
        cancelAll();
        pool.shutdown();

        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
