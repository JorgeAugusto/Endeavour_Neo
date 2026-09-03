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
public final class TickLibrary {

    /** The session playing, one ahead, one behind. */
    public static final int RESIDENT = 3;

    private final Path folder;

    private final String instrument;

    /**
     * What is loaded, newest use last.
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

    public TickLibrary(Path folder, String instrument) {
        this.folder = folder;
        this.instrument = instrument;
    }

    /** @param watcher told, on the loader's thread, whenever a session arrives */
    public void onLoaded(Runnable watcher) {
        whenLoaded = watcher == null ? () -> { } : watcher;
    }

    public Path fileFor(LocalDate day) {
        return MetaTraderTicks.fileFor(folder, instrument, day);
    }

    /** @return whether that session was exported, without reading it */
    public boolean has(LocalDate day) {
        return TickFile.isTicks(fileFor(day));
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

        TickSeries read = TickFile.read(fileFor(day));

        keep(day, read);

        return read;
    }

    /** @return how many sessions are in memory */
    public int residentCount() {
        synchronized (resident) {
            return resident.size();
        }
    }

    /** @return the dates in memory, oldest use first */
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

    public void close() {
        loader.shutdownNow();

        forget();
    }

    private void queue(LocalDate day) {
        if (day == null || at(day) != null || !loading.add(day)) {
            return;
        }

        loader.execute(() -> {
            try {
                if (has(day)) {
                    keep(day, TickFile.read(fileFor(day)));
                    whenLoaded.run();
                }
            } catch (IOException e) {
                // A session that will not read is not a reason to stop the
                // replay: the path falls back to synthetic ticks for that day,
                // which is exactly what happens for every day with no export.
                whenLoaded.run();
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
    private void keep(LocalDate day, TickSeries session) {
        synchronized (resident) {
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
    }

    /** @return the sessions exported for that instrument, by date, sorted */
    public List<LocalDate> exported() {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        List<LocalDate> days = new ArrayList<>();

        try (var files = Files.list(folder)) {
            files.filter(file -> file.getFileName().toString()
                            .startsWith(instrument + "-"))
                    .filter(TickFile::isTicks)
                    .forEach(file -> {
                        try {
                            days.add(TickFile.dateOf(file));
                        } catch (IOException ignored) {
                            // Listed and then unreadable: it was deleted between
                            // the two, which is not worth a message.
                        }
                    });
        } catch (IOException e) {
            return List.of();
        }

        days.sort(null);

        return days;
    }
}
