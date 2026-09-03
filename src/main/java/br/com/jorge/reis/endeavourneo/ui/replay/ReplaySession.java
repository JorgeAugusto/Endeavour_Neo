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
package br.com.jorge.reis.endeavourneo.ui.replay;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.ReplaySeries;
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Timer;

/**
 * One session being played back: the day, the clock, and who is watching.
 *
 * <p>The clock is a Swing {@link Timer}, so every advance happens on the
 * interface thread — the same thread that repaints the charts watching it. A
 * background thread here would buy nothing (advancing a counter is free) and
 * would put every chart's paint path in reach of another thread.</p>
 *
 * <p><b>Speed is bars per second, not a multiplier.</b> "16×" means nothing
 * without knowing the scale; "16 barras por segundo" is the same sentence at
 * every timeframe.</p>
 */
public final class ReplaySession {

    /** How many bars a second the transport can run at. */
    public static final int[] SPEEDS = {1, 2, 4, 8, 16, 32};

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** The trading day this stands in for, until a real loader exists. */
    private static final LocalTime OPEN = LocalTime.of(9, 0);

    private static final int MINUTES = 565;

    private final String instrument;

    private final LocalDate date;

    private final ReplaySeries live;

    private final transient List<Runnable> watchers = new ArrayList<>();

    private final Timer timer;

    private int speed = 4;

    /**
     * @param instrument what is being replayed
     * @param date the session
     */
    public ReplaySession(String instrument, LocalDate date) {
        this.instrument = instrument;
        this.date = date;
        this.live = ReplaySeries.of(dayOf(date));

        this.timer = new Timer(1000 / speed, e -> tick());
        this.timer.setCoalesce(true);
    }

    /**
     * @return the bars of that session
     *
     * <p><b>Synthetic, and seeded by the date</b> so the same day always replays
     * the same way — a replay that changed under the reader between two runs
     * would be useless for comparing decisions. Replaced the moment a real
     * loader exists; the rest of this class does not care which it gets.</p>
     */
    private static PriceSeries dayOf(LocalDate day) {
        long first = day.atTime(OPEN).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        return new RandomWalkSeries(MINUTES, 135_000.0, first, day.toEpochDay());
    }

    public String instrument() {
        return instrument;
    }

    public LocalDate date() {
        return date;
    }

    /** @return the series to hand a chart; it grows as the clock runs */
    public PriceSeries series() {
        return live;
    }

    public boolean isPlaying() {
        return timer.isRunning();
    }

    public int speed() {
        return speed;
    }

    public void setSpeed(int barsPerSecond) {
        this.speed = Math.max(1, barsPerSecond);

        timer.setDelay(1000 / this.speed);
        announce();
    }

    /** @param watcher told after every advance, so it can redraw */
    public void watch(Runnable watcher) {
        if (watcher != null) {
            watchers.add(watcher);
        }
    }

    public void forget(Runnable watcher) {
        watchers.remove(watcher);
    }

    public void toggle() {
        if (timer.isRunning()) {
            timer.stop();
        } else {
            // Pressing play at the close starts the day again rather than doing
            // nothing: a dead button on a finished session reads as a bug.
            if (live.finished()) {
                live.seek(0);
            }

            timer.start();
        }

        announce();
    }

    public void pause() {
        timer.stop();
        announce();
    }

    /** @param bars a nudge, forwards or back, while paused */
    public void step(int bars) {
        if (bars >= 0) {
            live.advance(bars);
        } else {
            live.seek(live.revealed() + bars);
        }

        announce();
    }

    public void seekFraction(double fraction) {
        live.seekFraction(fraction);
        announce();
    }

    public double progress() {
        return live.total() == 0 ? 0.0 : live.revealed() / (double) live.total();
    }

    /** @return the session clock, as the reader would have seen it that day */
    public String clockText() {
        return LocalTime.ofInstant(Instant.ofEpochMilli(live.clock()), ZoneId.systemDefault())
                .format(CLOCK);
    }

    /** @return when the session ends, so the transport can show where it is going */
    public String endText() {
        return OPEN.plusMinutes(MINUTES - 1L).format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    public void stop() {
        timer.stop();
        watchers.clear();
    }

    private void tick() {
        if (live.advance(1) == 0) {
            // The day is over. Stopping here rather than letting the timer run
            // on an unchanging series keeps the play button honest.
            timer.stop();
        }

        announce();
    }

    private void announce() {
        // A copy, because a watcher that detaches itself while being told would
        // otherwise change the list underneath the loop.
        for (Runnable watcher : new ArrayList<>(watchers)) {
            watcher.run();
        }
    }
}
