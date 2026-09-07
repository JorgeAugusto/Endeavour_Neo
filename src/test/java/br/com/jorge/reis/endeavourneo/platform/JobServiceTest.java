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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The threading contract, asserted rather than trusted.
 *
 * <p>These are the four properties the whole design rests on. Each one fails
 * silently in production if broken — work on the interface thread looks like a
 * freeze, a callback off it corrupts the screen in ways that do not reproduce,
 * and a swallowed exception looks like a job that simply never finished.</p>
 */
@DisplayName("Job service")
class JobServiceTest {

    private static final int TIMEOUT_SECONDS = 10;

    @Test
    @DisplayName("the work runs OFF the interface thread -- otherwise it would freeze the screen")
    void workRunsOffTheInterfaceThread() throws Exception {
        AtomicBoolean onEdt = new AtomicBoolean(true);
        CountDownLatch done = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            jobs.submit("test", progress -> {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                done.countDown();

                return null;
            });

            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never ran");
            assertFalse(onEdt.get(),
                    "the work ran on the interface thread, which is what this class exists "
                            + "to prevent");
        }
    }

    @Test
    @DisplayName("the result callback runs ON the interface thread, so it may touch components")
    void resultArrivesOnTheInterfaceThread() throws Exception {
        AtomicBoolean onEdt = new AtomicBoolean(false);
        AtomicReference<Integer> value = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            jobs.submit("test", progress -> 42).whenDone(result -> {
                onEdt.set(SwingUtilities.isEventDispatchThread());
                value.set(result);
                done.countDown();
            });

            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the callback never fired");
            assertTrue(onEdt.get(), "the callback ran off the interface thread");
            assertEquals(42, value.get(), "the result did not come through");
        }
    }

    @Test
    @DisplayName("a failure reaches the handler instead of vanishing")
    void failureDoesNotVanish() throws Exception {
        // This is the trap a bare SwingWorker sets: an exception inside
        // doInBackground is held until someone calls get(), and if nobody does,
        // the job dies in silence and the interface shows nothing at all.
        AtomicReference<Throwable> caught = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            jobs.submit("test", progress -> {
                throw new IllegalStateException("boom");
            }).whenFailed(error -> {
                caught.set(error);
                done.countDown();
            });

            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the failure never reached the handler");
            assertNotNull(caught.get(), "no error was delivered");
            assertEquals("boom", caught.get().getMessage(), "a different error came through");
        }
    }

    @Test
    @DisplayName("running out of memory is a FAILURE, not a success carrying null")
    void anErrorIsAFailureAndNotAResult() throws Exception {
        // The likeliest failure this service will ever see, and for a while the
        // only one it could not report. The catch read
        // `Exception | StackOverflowError`, and OutOfMemoryError is neither: it
        // went past the catch, the finally ran with failure still null, and the
        // job settled as a SUCCESS whose value happened to be null. A caller
        // reading that saw a series that loaded fine and came back empty.
        //
        // The work here reads a million bars at a time; this is not a theoretical
        // Error. The test throws one by hand rather than exhausting the heap,
        // which would take the test runner with it.
        AtomicReference<Throwable> caught = new AtomicReference<>();
        AtomicBoolean succeeded = new AtomicBoolean(false);
        CountDownLatch done = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            jobs.submit("test", progress -> {
                throw new OutOfMemoryError("pretend heap");
            }).whenDone(result -> succeeded.set(true)).whenFailed(error -> {
                caught.set(error);
                done.countDown();
            });

            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "an Error never reached the failure handler");
            assertNotNull(caught.get(), "no error was delivered");
            assertEquals("pretend heap", caught.get().getMessage());
            assertFalse(succeeded.get(),
                    "the job that ran out of memory was reported as a success");
        }
    }

    @Test
    @DisplayName("cancelling is seen by the job, which stops on its own")
    void cancellationIsCooperative() throws Exception {
        // Cancellation has to be cooperative: Java has no safe way to stop a
        // thread from outside. The test therefore asserts that the job SAW the
        // request, not that the thread died.
        AtomicInteger steps = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            JobService.Handle<Integer> handle = jobs.submit("test", progress -> {
                for (int i = 0; i < 1_000; i++) {
                    if (progress.cancelled()) {
                        return steps.get();
                    }

                    steps.incrementAndGet();
                    started.countDown();

                    Thread.sleep(2);
                }

                return steps.get();
            });

            handle.whenStopped(finished::countDown);

            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never started");

            handle.cancel();

            // WHAT THIS LINE USED TO BE, and why it is worth the paragraph:
            //
            //   assertTrue(finished.await(...) || steps.get() < 1_000, ...);
            //   assertTrue(steps.get() < 1_000, ...);
            //
            // The second operand of that disjunction is asserted alone on the
            // very next line, so the disjunction asserted nothing at all. And
            // because the callback it waited on was whenDone -- which a
            // cancelled job never reaches -- the await always ran its full ten
            // seconds and always timed out. The test paid ten seconds a run to
            // check nothing, and the defect it was pointed at (a cancelled job
            // telling nobody it had stopped) lived behind it untouched.
            assertTrue(finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the job was cancelled and nobody was told: a caller that raised a "
                            + "spinner on submit has no moment at which to lower it");
            assertTrue(steps.get() < 1_000,
                    "the job completed all 1000 steps despite being cancelled after the first");
        }
    }

    @Test
    @DisplayName("a failure nobody handled is reported where output really goes")
    void theLastChanceReportSurvivesTheRedirect() throws Exception {
        // close() is called from a shutdown hook, and the console window
        // replaces System.err with a stream that hands each line to the
        // interface thread. The JVM does not wait for that thread to drain, so
        // the one report this class exists to never lose was being posted to a
        // queue that would not run again.
        //
        // Standing in for the console here: a stream that swallows everything.
        // The report has to reach the stream the class captured at load, not
        // this one.
        java.io.PrintStream real = System.err;

        // The class captures its stream at load, so it has to be loaded BEFORE
        // the redirect below -- otherwise this test would prove only that the
        // capture happened after it, which is not the property.
        new JobService().close();

        java.io.ByteArrayOutputStream swallowed = new java.io.ByteArrayOutputStream();

        System.setErr(new java.io.PrintStream(swallowed, true, java.nio.charset.StandardCharsets.UTF_8));

        try {
            CountDownLatch ran = new CountDownLatch(1);

            try (JobService jobs = new JobService()) {
                // No whenFailed: the failure is nobody's, which is the case the
                // last-chance report exists for.
                jobs.submit("orphan", progress -> {
                    ran.countDown();

                    throw new IllegalStateException("nobody is listening");
                });

                assertTrue(ran.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never ran");

                // The latch counts down BEFORE the throw, so the job has not
                // failed yet at this point. Closing here would find nothing
                // unclaimed and report nothing -- which is how the first draft
                // of this test passed whether the fix was in or out.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);

                while (jobs.isBusy() && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }

                assertFalse(jobs.isBusy(), "the job never finished failing");
            }

            assertEquals(0, swallowed.size(),
                    "the report was written to whatever System.err happened to be at the "
                            + "time, which during shutdown is a queue nothing will drain: "
                            + swallowed.toString(java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            System.setErr(real);
        }
    }

    @Test
    @DisplayName("a cancelled job is not a result and not a failure")
    void cancellingIsItsOwnOutcome() throws Exception {
        // The distinction the third callback exists to keep. Handing a
        // cancellation to whenDone would make "it finished" and "I stopped it"
        // the same event, and a caller cannot tell a half-written answer from a
        // whole one that way.
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            JobService.Handle<Integer> handle = jobs.submit("test", progress -> {
                started.countDown();

                while (!progress.cancelled()) {
                    Thread.sleep(2);
                }

                return 7;
            });

            handle.whenDone(value -> done.set(true))
                    .whenFailed(error -> failed.set(true))
                    .whenStopped(stopped::countDown);

            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never started");

            handle.cancel();

            assertTrue(stopped.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the stop was never announced");

            // Drain the interface thread: if a result or a failure were also
            // posted, this is where they would arrive.
            SwingUtilities.invokeAndWait(() -> { });

            assertFalse(done.get(), "a cancelled job was delivered as a result");
            assertFalse(failed.get(), "a cancelled job was delivered as a failure");
        }
    }

    @Test
    @DisplayName("the service reports what is running, so the status bar can say")
    void reportsWhatIsRunning() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            assertFalse(jobs.isBusy(), "it claimed to be busy before anything was submitted");

            jobs.submit("carregando serie", progress -> {
                started.countDown();
                hold.await();

                return null;
            });

            assertTrue(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never started");
            assertTrue(jobs.isBusy(), "a job is running and it says it is idle");
            assertEquals(java.util.List.of("carregando serie"), jobs.runningNames(),
                    "the running job is not being reported by name");

            hold.countDown();
        }
    }
}
