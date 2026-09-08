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

        /**
         * What this job is doing, and how far it has got.
         *
         * <p><b>Per job, and they used to be per SERVICE.</b> The pool has
         * {@code max(2, cores - 1)} threads and nothing serialises the
         * submissions -- the launcher already starts one while the window is
         * opening, and the reader can start another. Both wrote into one {@code
         * stage} and one {@code fraction}, so the status bar showed the stage of
         * one with the progress of the other and there was no way to tell.</p>
         *
         * <p>Worse at the end: the reset only happened when the running list
         * went EMPTY, so the job that finished first left its stage on screen,
         * describing something that was over, while another was still going.</p>
         */
        private volatile String stage = "";

        private volatile double fraction = -1.0;

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
        public Handle<T> whenDone(Consumer<T> action) {
            synchronized (this) {
                this.onDone = action;
            }

            // Outside the block. See deliver(): registering here and finding the
            // job already over is the ordinary case, and the callback would then
            // run holding this object's monitor.
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
        public Handle<T> whenFailed(Consumer<Throwable> action) {
            synchronized (this) {
                this.onFailed = action;
            }

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
        public Handle<T> whenStopped(Runnable action) {
            synchronized (this) {
                this.onStopped = action;
            }

            deliver();

            return this;
        }

        /**
         * Says out loud that a job died with nobody listening.
         *
         * <p><b>Called when it dies, and it used to be called only at {@code
         * close}.</b> The class javadoc promises that "a failure always reaches
         * a handler", and without a {@code whenFailed} the outcome stayed
         * pending on purpose -- so the only report happened on the way out of
         * the program, which is hooked to the JVM shutdown. Between the death
         * and the report the job had left the running list, the status bar had
         * cleared, and the reader had watched a job finish normally. The
         * launcher's own job registers no failure handler at all, so it is the
         * case.</p>
         *
         * <p>It does NOT consume the outcome. A handler chained afterwards still
         * receives the failure -- the pending behaviour is deliberate and stays
         * -- and {@code reported} is what stops the same failure being written
         * twice, once here and once at close.</p>
         */
        synchronized void reportIfUnclaimed() {
            if (settled && !delivered && !reported && error != null) {
                reported = true;

                FAILURES.println("job \"" + name + "\" failed and nobody handled it:");
                error.printStackTrace(FAILURES);
                FAILURES.flush();
            }
        }

        /** Whether the unhandled failure has already been written out. */
        private boolean reported;

        /** @return whether this one died with nobody listening and said so */
        synchronized boolean wasReported() {
            return reported;
        }

        void settle(T result, Throwable failure, boolean cancelled) {
            synchronized (this) {
                this.settled = true;
                this.value = result;
                this.error = failure;
                this.wasCancelled = cancelled;
            }

            deliver();

            // Now, not at the end of the program.
            reportIfUnclaimed();
        }

        /**
         * Hands the outcome to whoever asked for it, OUTSIDE this monitor.
         *
         * <p>The chaining -- {@code submit(...).whenDone(...)} -- is done on the
         * interface thread, and for a short job the work is often already over
         * by then. {@code onEdt} runs its argument INLINE when it is already on
         * that thread, so the caller's callback used to execute inside this
         * object's {@code synchronized}: interface code, holding a lock that a
         * pool thread waits on in {@code settle}. Today they are console lines;
         * the day one of them opens a modal dialog it becomes a deadlock, with
         * the pool thread and then {@code close()} queued behind a window the
         * reader has to dismiss.</p>
         *
         * <p>So the decision is taken under the lock and the delivery happens
         * after it: {@link #ready()} answers what to run, or null.</p>
         */
        private void deliver() {
            Runnable now = ready();

            if (now != null) {
                onEdt(now);
            }
        }

        /**
         * @return what to hand over, or null when there is nothing to hand over yet
         *
         * <p>Called from both sides of the race and idempotent, so it does not
         * matter which arrives first.</p>
         */
        private synchronized Runnable ready() {
            if (!settled || delivered) {
                return null;
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
                    return null;
                }

                Runnable handler = onStopped;

                delivered = true;

                return handler;
            }

            // With no handler registered YET, stay pending. Whoever chains the
            // callback next triggers this again and receives the outcome. The
            // job finishing first must never consume it.
            if (error != null) {
                if (onFailed == null) {
                    return null;
                }

                Consumer<Throwable> handler = onFailed;
                Throwable failure = error;

                delivered = true;

                return () -> handler.accept(failure);
            }

            if (onDone == null) {
                return null;
            }

            Consumer<T> handler = onDone;
            T result = value;

            delivered = true;

            return () -> handler.accept(result);
        }

        public void cancel() {
            if (settled) {
                // ALREADY FINISHED, so there is nothing to cancel and nothing to
                // remember. Adding it here left the handle in the list for the
                // life of the service: the settle path removes it, and that path
                // had already run. A cancel arriving late is normal -- the
                // reader presses the cross while the last write is finishing.
                return;
            }

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


                notifyListeners();
            }
        });

        return handle;
    }

    private Progress progressFor(Handle<?> handle) {
        return new Progress() {

            @Override
            public void report(double value) {
                handle.fraction = value;
                notifyListeners();
            }

            @Override
            public boolean cancelled() {
                return handle.isCancelled();
            }

            @Override
            public void say(String text) {
                handle.stage = text == null ? "" : text;
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
        Handle<?> first = first();

        return first == null ? "" : first.stage;
    }

    /**
     * @return the job the status bar speaks for, or null when nothing is running
     *
     * <p>The FIRST still running, which is the one {@code runningNames} already
     * names when there is one. Two jobs at once is the case this exists for, and
     * showing one of them completely beats showing half of each.</p>
     */
    private Handle<?> first() {
        for (Handle<?> each : running) {
            return each;
        }

        return null;
    }

    /** @return progress from 0 to 1, or negative when unknown */
    public double fraction() {
        Handle<?> first = first();

        return first == null ? -1.0 : first.fraction;
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
