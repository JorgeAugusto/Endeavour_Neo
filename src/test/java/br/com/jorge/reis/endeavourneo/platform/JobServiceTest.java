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

    @Test
    @DisplayName("com DOIS trabalhos, o estagio e a fracao sao do mesmo")
    void twojobsAtOnceDoNotMixTheirProgress() throws Exception {
        // The pool has max(2, cores - 1) threads and nothing serialises the
        // submissions -- the launcher already starts one while the window is
        // opening, and the reader can start another. Both used to write into ONE
        // stage and ONE fraction on the service, so the status bar showed the
        // stage of one with the progress of the other, and nothing said which.
        //
        // The assertion is not "which job wins": it is that the two numbers
        // belong to the SAME job. Showing one of them completely is an answer;
        // showing half of each is not.
        CountDownLatch bothReported = new CountDownLatch(2);
        CountDownLatch hold = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            jobs.submit("A", progress -> {
                progress.say("stage-A");
                progress.report(0.25);
                bothReported.countDown();
                hold.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                return null;
            });

            jobs.submit("B", progress -> {
                progress.say("stage-B");
                progress.report(0.75);
                bothReported.countDown();
                hold.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);

                return null;
            });

            assertTrue(bothReported.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "the two jobs did not both report");

            String stage = jobs.stage();
            double fraction = jobs.fraction();

            assertTrue("stage-A".equals(stage) || "stage-B".equals(stage),
                    "the stage belongs to no job at all: " + stage);
            assertEquals("stage-A".equals(stage) ? 0.25 : 0.75, fraction, 1e-9,
                    "the status bar is showing the stage of one job with the progress of "
                            + "the other: " + stage + " at " + fraction);
        } finally {
            hold.countDown();
        }
    }

    @Test
    @DisplayName("uma falha sem ouvinte e dita QUANDO acontece, nao no fim do programa")
    void anunhandledFailureIsReportedWhenItHappens() throws Exception {
        // The class javadoc promises that "a failure always reaches a handler".
        // Without a whenFailed the outcome stays pending on purpose -- which is
        // right, so a handler chained later still gets it -- and the only report
        // happened at close(), which is hooked to the JVM shutdown. Between the
        // death and the report the job had left the running list, the status bar
        // had cleared, and the reader had watched a job finish normally. The
        // launcher's own job registers no failure handler, so it is the case.
        CountDownLatch ran = new CountDownLatch(1);

        try (JobService jobs = new JobService()) {
            JobService.Handle<Object> handle = jobs.submit("doomed", progress -> {
                ran.countDown();

                throw new IllegalStateException("on purpose");
            });

            assertTrue(ran.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never ran");

            for (int tries = 0; tries < 200 && !handle.wasReported(); tries++) {
                Thread.sleep(25L);
            }

            assertTrue(handle.wasReported(),
                    "a job died with nobody listening and nothing said so until the "
                            + "program was closing");

            // And the outcome is still pending: a handler chained afterwards
            // must receive it, which is what the report must not consume.
            AtomicReference<Throwable> caught = new AtomicReference<>();

            handle.whenFailed(caught::set);

            for (int tries = 0; tries < 200 && caught.get() == null; tries++) {
                Thread.sleep(25L);
            }

            assertNotNull(caught.get(),
                    "saying the failure out loud consumed it, so a handler registered "
                            + "afterwards got nothing");
        }
    }
/**
     * A callback chained onto a job that has already finished runs free of the
     * handle's monitor.
     *
     * <p>{@code submit(...).whenDone(...)} is chained on the interface thread,
     * and for a short job the work is often over by then — so the delivery is
     * decided immediately, and {@code onEdt} runs its argument INLINE when it is
     * already on that thread. The callback therefore ran inside the handle's
     * {@code synchronized}: interface code holding a lock that a pool thread
     * waits on in {@code settle}. Today those callbacks write console lines; the
     * day one of them opens a modal dialog it is a deadlock, with the pool
     * thread and then {@code close()} queued behind a window somebody has to
     * dismiss.</p>
     *
     * <p>{@code Thread.holdsLock} asks the question directly, which is better
     * than trying to provoke the deadlock and better than measuring time.</p>
     */
    @Test
    @DisplayName("o retorno de um job ja terminado nao roda segurando o cadeado do handle")
    void thecallbackDoesNotRunHoldingTheHandlesLock() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean held =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicBoolean ran =
                new java.util.concurrent.atomic.AtomicBoolean();

        try (JobService jobs = new JobService()) {
            CountDownLatch done = new CountDownLatch(1);

            JobService.Handle<Integer> handle = jobs.submit("curto", progress -> {
                done.countDown();

                return 1;
            });

            assertTrue(done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "the job never ran");

            // And settled, not merely finished: the delivery is decided in the
            // step that follows the work, and chaining before it would take the
            // other path -- the one that leaves the outcome pending.
            for (int tries = 0; tries < 200 && jobs.isBusy(); tries++) {
                Thread.sleep(10L);
            }

            SwingUtilities.invokeAndWait(() -> handle.whenDone(value -> {
                ran.set(true);
                held.set(Thread.holdsLock(handle));
            }));

            assertTrue(ran.get(),
                    "the callback never ran, so this test is asking about nothing");
            assertFalse(held.get(),
                    "the callback ran inside the handle's monitor: any modal dialog in it "
                            + "would hold the pool thread and the shutdown behind it");
        }
    }
}
