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

/**
 * A day, revealed one bar at a time.
 *
 * <p>The whole day is already in memory — it happened years ago — and this
 * decides how much of it exists <b>as far as anything reading it can tell</b>.
 * A chart drawing this series sees a market that is still going on, because
 * {@link #size()} answers with the bars revealed so far and nothing else is
 * reachable.</p>
 *
 * <p><b>That unreachability is the point, and it is why this is a series and not
 * a counter the chart consults.</b> If the future were merely "not drawn", every
 * indicator, every measurement and every strategy would still be able to read
 * it, and the first accidental look would be silent. Here a bar that has not
 * arrived yet is out of bounds, the way a bar tomorrow is out of bounds.</p>
 *
 * <p>Not thread-safe, and does not need to be: the clock that advances it and
 * the chart that reads it both live on the interface thread. Making it
 * synchronised would put a lock on the paint path for no benefit.</p>
 */
public final class ReplaySeries implements PriceSeries {

    private final PriceSeries day;

    private int revealed;

    /**
     * @param day the whole session
     * @param revealed how many bars have arrived; clamped into the day
     */
    public ReplaySeries(PriceSeries day, int revealed) {
        this.day = day == null ? PriceSeries.empty() : day;
        this.revealed = clamp(revealed);
    }

    /** @param day the whole session, with nothing revealed yet */
    public static ReplaySeries of(PriceSeries day) {
        return new ReplaySeries(day, 0);
    }

    /** @return how many bars the day holds in total, revealed or not */
    public int total() {
        return day.size();
    }

    public int revealed() {
        return revealed;
    }

    /** @return true once the whole session has been played out */
    public boolean finished() {
        return revealed >= day.size();
    }

    /**
     * @param bars how many more to reveal
     * @return how many were ACTUALLY revealed, which is fewer at the end
     *
     * <p>The count matters to the caller: a clock that keeps ticking after the
     * close would leave the play button lit on a session that ended.</p>
     */
    public int advance(int bars) {
        int before = revealed;

        revealed = clamp(revealed + Math.max(0, bars));

        return revealed - before;
    }

    /** @param bar where to jump to, for dragging the scrubber */
    public void seek(int bar) {
        revealed = clamp(bar);
    }

    /**
     * @param fraction 0 for the open, 1 for the close
     */
    public void seekFraction(double fraction) {
        if (!Double.isFinite(fraction)) {
            return;
        }

        seek((int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * day.size()));
    }

    /** @return the instant the session would show on its clock right now */
    public long clock() {
        if (day.size() == 0) {
            return 0L;
        }

        // The last revealed bar, or the first bar's time before anything has
        // arrived -- so the clock reads the session's opening time rather than
        // 1970 while the reader is still deciding whether to press play.
        return revealed == 0 ? day.timeAt(0) : day.timeAt(revealed - 1);
    }

    private int clamp(int bar) {
        return Math.max(0, Math.min(bar, day.size()));
    }

    // ------------------------------------------------ what has arrived so far

    @Override
    public int size() {
        return revealed;
    }

    @Override
    public long timeAt(int index) {
        return day.timeAt(check(index));
    }

    @Override
    public double openAt(int index) {
        return day.openAt(check(index));
    }

    @Override
    public double highAt(int index) {
        return day.highAt(check(index));
    }

    @Override
    public double lowAt(int index) {
        return day.lowAt(check(index));
    }

    @Override
    public double closeAt(int index) {
        return day.closeAt(check(index));
    }

    @Override
    public double volumeAt(int index) {
        return day.volumeAt(check(index));
    }

    /**
     * @throws IndexOutOfBoundsException saying the bar has not happened yet
     *
     * <p>Worth its own message. "Index 300 out of bounds for length 42" sends
     * the reader looking for a bug in the chart; "bar 300 has not happened yet"
     * says what it is — something read ahead of the clock.</p>
     */
    private int check(int index) {
        if (index < 0 || index >= revealed) {
            throw new IndexOutOfBoundsException(
                    "bar " + index + " has not happened yet: " + revealed + " of "
                            + day.size() + " so far");
        }

        return index;
    }
}
