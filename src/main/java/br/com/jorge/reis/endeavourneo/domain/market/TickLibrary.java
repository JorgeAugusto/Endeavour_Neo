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
package br.com.jorge.reis.endeavourneo.domain.market;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The tick sessions that are in memory, and never more than three of them.
 *
 * <h2>Three, and always three</h2>
 *
 * <p>The one being played, the one after and the one before. Measured on
 * January 2021: 113 MB a session on average, so this is 340 MB — and it is 340
 * MB whether the replay covers three days or three years. That constant is the
 * whole point. Loading the period would be 113 MB a day with no ceiling.</p>
 *
 * <p><b>Why the neighbours at all,</b> given that a session loads in 0,22
 * seconds. Not so the reader can go back — going back is already free, because
 * the chart keeps the bars it has drawn and the ticks only animate the bar that
 * is forming. It is so that playback crossing midnight, and a small drag
 * backwards across the same boundary, do not stop for a quarter of a second in
 * the middle of the animation. A jump to a day outside the three still waits,
 * and there is no way for it not to.</p>
 *
 * <h2>Never on the caller's thread, unless it asks</h2>
 *
 * <p>{@link #at} answers with what is in memory right now and never blocks, so
 * the painting thread can call it. {@link #request} starts the work. {@link
 * #load} is the blocking one, and is for callers that are already off the
 * interface thread.</p>
 */
public final class TickLibrary implements AutoCloseable {

    /** The session playing, one ahead, one behind. */
    public static final int RESIDENT = 3;

    private final Path folder;

    private final String instrument;

    /**
     * Which export these sessions came from.
     *
     * <p>A library holds one source. The two hold different things -- quotes
     * against the tape -- and a library that mixed them would answer "yes, I
     * have that day" without saying which of the two days it has.</p>
     */
    private final TickSource source;

    /**
     * What is loaded, in the order it arrived.
     *
     * <p><b>Insertion order, not use.</b> This said "newest use last" and the
     * map is a plain {@code LinkedHashMap} -- the access-order constructor was
     * never used, and {@code at} only calls {@code get}, which moves nothing.
     * Nothing anywhere records a use.</p>
     *
     * <p>Nor should it: what decides the discard is {@link #keep}, and it keeps
     * the days NEAREST the one being played. The class comment says why -- a
     * replay walks forwards, so the session furthest in DAYS is the one least
     * likely to be wanted next, whatever was read last.</p>
     *
     * <p>Access is synchronised on the map itself. It is touched by the loader
     * thread and by whoever is drawing, which are never the same thread.</p>
     */
    private final Map<LocalDate, TickSeries> resident = new LinkedHashMap<>();

    /** Days already being loaded, so a second request does not queue a second read. */
    private final Set<LocalDate> loading = ConcurrentHashMap.newKeySet();

    /**
     * One thread, so two sessions never read from the disk at once.
     *
     * <p>A daemon thread: a half-finished prefetch must never be the reason the
     * application will not close.</p>
     */
    private final ExecutorService loader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "ticks");

        thread.setDaemon(true);

        return thread;
    });

    private volatile LocalDate focus;

    private volatile Runnable whenLoaded = () -> { };

    public TickLibrary(Path folder, String instrument, TickSource source) {
        this.folder = folder;
        this.instrument = instrument;
        this.source = source;
    }

    /** @return which export this library reads */
    public TickSource source() {
        return source;
    }

    /** @param watcher told, on the loader's thread, whenever a session arrives */
    public void onLoaded(Runnable watcher) {
        whenLoaded = watcher == null ? () -> { } : watcher;
    }

    public Path fileFor(LocalDate day) {
        return source.fileFor(folder, instrument, day);
    }

    /** @return whether that session was exported, without reading it */
    public boolean has(LocalDate day) {
        return day.equals(source.sessionIn(fileFor(day)));
    }

    /**
     * @return the session if it is in memory, otherwise null
     *
     * <p>Never blocks and never reads the disk. Null means "not yet", not "does
     * not exist" — ask {@link #has} for that.</p>
     */
    public TickSeries at(LocalDate day) {
        synchronized (resident) {
            return resident.get(day);
        }
    }

    /**
     * Declares which session matters now, and starts the work.
     *
     * <p>The day itself first, then its neighbours, so a replay that has just
     * moved gets what it needs before what it might need.</p>
     */
    public void request(LocalDate day) {
        focus = day;

        queue(day);
        queue(day.plusDays(1));
        queue(day.minusDays(1));
    }

    /**
     * @return the session, reading it if it is not in memory
     * @throws IOException if it exists and will not read
     *
     * <p>Blocks. Never call it from the interface thread: a session is a fifth
     * of a second, which is long enough to be seen as a freeze.</p>
     */
    public TickSeries load(LocalDate day) throws IOException {
        TickSeries known = at(day);

        if (known != null) {
            return known;
        }

        if (!has(day)) {
            return null;
        }

        // MARKED AS LOADING, so a request for the same day queues nothing.
        // That set exists, in its own words, so "a second request does not queue
        // a second read" -- and this method never looked at it. A load running
        // beside a request for the same day read the file twice and built two
        // sessions, around 226 MB in flight by this class's own measurement,
        // before one overwrote the other. Not a wrong answer: a peak that the
        // whole "never more than three" design exists to avoid.
        //
        // A load already in flight elsewhere is waited for by reading anyway --
        // this one has a caller holding on for the answer, and blocking on
        // another thread's read would need a latch this class does not have.
        // What is removed is the case where the OTHER side is the one that has
        // not started yet.
        boolean mine = loading.add(day);

        try {
            TickSeries read = source.read(fileFor(day));

            keep(day, read);

            return read;
        } finally {
            if (mine) {
                loading.remove(day);
            }
        }
    }

    /**
     * Where a listing failure is said, and who decides that.
     *
     * <p><b>Not System.err by default any more.</b> The reasoning of the
     * comments below is right -- a listing that failed halfway deserves to be
     * said -- but the channel was not: it was the only text output in this
     * whole package, a reader of a Swing application never sees standard
     * error, and a sentence written in English in the code walks straight past
     * the bundle on the day it reaches a screen.</p>
     *
     * <p>So the domain says WHAT happened and the caller decides where it
     * goes. Standard error stays as the default, because a warning nobody
     * asked to receive is still better said than swallowed.</p>
     */
    public static void reportTo(java.util.function.Consumer<String> where) {
        complaints = where == null ? System.err::println : where;
    }

    private static volatile java.util.function.Consumer<String> complaints =
            System.err::println;

    private static void complain(String what) {
        complaints.accept(what);
    }

    /** @return how many sessions are in memory */
    public int residentCount() {
        synchronized (resident) {
            return resident.size();
        }
    }

    /**
     * @return the dates in memory, in no order worth relying on
     *
     * <p>This used to promise "oldest use first", and nothing here records use:
     * {@code keep} drops what is furthest in DAYS from the focus, and says why
     * -- a replay walking forwards touches yesterday, today and tomorrow in an
     * order that would make least-recently-used throw away tomorrow just before
     * reaching it. A caller that trusted the old sentence would have been
     * reasoning about a policy this class deliberately does not have.</p>
     */
    public List<LocalDate> residentDays() {
        synchronized (resident) {
            return new ArrayList<>(resident.keySet());
        }
    }

    /** Drops everything. The files are untouched. */
    public void forget() {
        synchronized (resident) {
            resident.clear();
        }
    }

    /**
     * Stops the reading thread and lets the sessions go.
     *
     * <p><b>The class says {@code AutoCloseable} so the compiler can help.</b>
     * It had this method and did not declare the interface, so try-with-resources
     * was not available and every owner had to remember a {@code finally} --
     * seven of them did, and the ones that did not held a thread and up to three
     * sessions of ticks, which is hundreds of megabytes, for as long as the
     * application ran. A resource that must be closed and does not say so leaves
     * the remembering to people.</p>
     */
    @Override
    public void close() {
        // MARKED FIRST, and this is the whole of the fix. shutdownNow interrupts
        // but does not wait: a task that had already come out of source.read and
        // was entering keep() would put a whole session into the map AFTER
        // forget() had cleared it. The library then reported itself closed and
        // was holding 113 MB, and the reader who had stopped the replay had no
        // way to know.
        //
        // The same task then ran whenLoaded, and the watcher on the other end of
        // that -- a ReplaySession, through invokeLater -- touches `preparing`,
        // announces, and moves the transport on screen. A session already over
        // could be woken up and redraw itself.
        //
        // It does not reproduce reliably, which is the worst kind.
        closed = true;

        loader.shutdownNow();

        forget();
    }

    /**
     * Whether {@link #close} has been called.
     *
     * <p>Volatile because the loader's thread reads it and the closer writes it,
     * and the whole point is that the two are racing.</p>
     */
    private volatile boolean closed;

    /**
     * @return whether this one has been closed
     *
     * <p>So an owner can be asked whether it let go, which is the only way to
     * say "this was closed" in a test without reaching for the thread. A live
     * one holds a reading thread and up to {@link #RESIDENT} sessions of
     * ticks.</p>
     */
    public boolean isClosed() {
        // The FIELD, not the executor. Asking the executor answered "closed"
        // from the instant shutdownNow returned, while a task still in flight
        // could go on to fill the map -- so "closed" and "holding a session"
        // were true at the same time and nothing could tell.
        return closed || loader.isShutdown();
    }

    private void queue(LocalDate day) {
        if (day == null || at(day) != null || !loading.add(day)) {
            return;
        }

        loader.execute(() -> {
            try {
                if (has(day) && keep(day, source.read(fileFor(day)))) {
                    announce();
                }
            } catch (IOException e) {
                // A session that will not read is not a reason to stop the
                // replay: the path falls back to synthetic ticks for that day,
                // which is exactly what happens for every day with no export.
                announce();
            } finally {
                loading.remove(day);
            }
        });
    }

    /**
     * Puts a session in memory and drops whatever is furthest from the focus.
     *
     * <p>Furthest in DAYS, not least recently used. Least-recently-used is the
     * usual answer and it is the wrong one here: a replay walking forwards
     * touches yesterday, today and tomorrow in an order that would make it
     * throw away tomorrow just before reaching it.</p>
     */
    boolean keep(LocalDate day, TickSeries session) {
        synchronized (resident) {
            if (closed) {
                // Read while this was being closed. Putting it in now would
                // leave a closed library holding a whole session, which is the
                // one thing close() exists to prevent.
                return false;
            }

            resident.put(day, session);

            while (resident.size() > RESIDENT) {
                LocalDate anchor = focus == null ? day : focus;
                LocalDate furthest = null;
                long distance = -1;

                for (LocalDate held : resident.keySet()) {
                    long away = Math.abs(held.toEpochDay() - anchor.toEpochDay());

                    if (away > distance) {
                        distance = away;
                        furthest = held;
                    }
                }

                resident.remove(furthest);
            }
        }

        return true;
    }

    /**
     * Tells the watcher, unless this was closed while the session was being read.
     *
     * <p>Package-visible, with {@code keep}, so the race can be ARRANGED. The
     * two of them are what the loader's thread does after it comes out of the
     * read, and the whole defect is that it can come out of the read after
     * {@code close} has already run -- which no test can make happen on
     * purpose. Calling them in that order is the same question, answered
     * without a stopwatch.</p>
     */
    void announce() {
        if (!closed) {
            whenLoaded.run();
        }
    }

    /**
     * @return the sessions exported for that instrument, by date, sorted
     *
     * <p>Walks, because the sessions are filed under a year and a month rather
     * than heaped in one directory. Three levels is the whole of it, and
     * refusing to go deeper keeps a folder of unrelated exports underneath from
     * being read as tick sessions.</p>
     *
     * <p>A file is kept only when it sits where its own date says it should.
     * That is what makes this listing and {@link #has} answer the same
     * question: a session offered here is one the replay can open, and a
     * misplaced file cannot become a day in the tree that nothing will
     * play.</p>
     */
    public List<LocalDate> exported() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        // Four: the source, the year, the month, the file.
        try (var files = Files.walk(folder, 4)) {
            return daysIn(files);
        } catch (IOException e) {
            // The walk could not even START -- the folder went away between the
            // check above and here. Nothing was found, so there is nothing to
            // keep, but there is still something to say.
            complain(folder + ": the tick sessions could not be listed (" + e + ")");

            return List.of();
        }
    }

    /**
     * @param files the walk, WHICH MAY THROW PARTWAY THROUGH
     * @return the sessions it managed to list, by date, sorted
     *
     * <p>A method of its own so the halfway failure can be handed to it. The
     * only test that guarded this read the SOURCE of {@code exported} and
     * matched three substrings in it: {@code return List.of();} absent, {@code
     * System.err} present, {@code days.size()} present. Three ways of putting
     * the defect straight back -- {@code Collections.emptyList()}, {@code new
     * ArrayList<>()}, {@code List.of( )} with a space -- pass all three. And the
     * slice it read ran to the end of the file, because {@code exported} happens
     * to be the last method: the day somebody adds one after it, the test starts
     * measuring something else without a word.</p>
     *
     * <p><b>And it caught the wrong exception.</b> {@code Files.walk} is lazy: a
     * subdirectory with no permission, a circular link, a network volume that
     * dropped, all throw while the stream is being CONSUMED, and the stream
     * wraps them in {@code UncheckedIOException} -- which is a {@code
     * RuntimeException} and was never a {@code IOException}. The catch below the
     * walk could only ever see the failure to START it. The very case its own
     * comment described -- "the walk is lazy, so the throw can come halfway" --
     * went straight past it and out of {@code exported}, taking the sessions
     * already found with it and the tree build after it.</p>
     */
    List<LocalDate> daysIn(java.util.stream.Stream<java.nio.file.Path> files) {
        List<LocalDate> days = new ArrayList<>();

        try {
            files.filter(file -> file.getFileName().toString()
                            .startsWith(instrument + "-"))
                    .forEach(file -> {
                        LocalDate day = source.sessionIn(file);

                        // Null covers all three ways a file is not ours: the
                        // other source's extension, a tag we do not write, and
                        // a file that was deleted between the listing and the
                        // read. None of them is worth a message.
                        if (day != null && fileFor(day).toAbsolutePath()
                                .equals(file.toAbsolutePath())) {
                            days.add(day);
                        }
                    });
        } catch (java.io.UncheckedIOException e) {
            // WHAT WAS FOUND, and a word about why the rest is missing. None of
            // the ways a walk breaks means "nothing was exported", which is what
            // an empty list says.
            //
            // The comment above says the individual nulls do not deserve a
            // message. This is not one of those: it is the whole listing
            // failing, and the reader is about to be shown a shorter list of
            // playable days with nothing to say why.
            complain(folder + ": the tick sessions could not all be listed ("
                    + e + "); showing the " + days.size() + " found");
        }

        days.sort(null);

        return days;
    }
}
