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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The <b>recorte</b>: the stretch one run uses.
 *
 * <p>Level three of the vocabulary — a series is the whole history, a segment is
 * a named part of it with a role, and this is the piece a single run looks at.
 * Every one of these except {@link #CHOSEN} is counted back from the newest bar
 * there is.</p>
 *
 * <h2>A recorte never crosses two segments, and here that is free</h2>
 *
 * <p>The rule exists because a run spanning the search set and the test set
 * mixes them without anyone noticing. It costs nothing to honour: the slice is
 * applied to the series the reader already picked, and that series has already
 * been cut to its segment. "The last year of <i>busca</i>" is a year inside
 * busca — there is no way to write the other thing.</p>
 *
 * <h2>Counted in SESSIONS, not in calendar days</h2>
 *
 * <p>The previous project counted calendar durations, and it reads fine until
 * "1 dia" lands on a Sunday: the run covers no bars, reports nothing, and the
 * reader reads it as "the strategy did not trade". A day means the last session,
 * a week means the last five, a year the last two hundred and fifty-two. The
 * labels are the same and the numbers are the ones a trading calendar has.</p>
 */
public enum Slice {

    /** The last session there is. */
    DAY("slice.day", 1),

    WEEK("slice.week", 5),

    MONTH("slice.month", 21),

    QUARTER("slice.quarter", 63),

    HALF_YEAR("slice.halfYear", 126),

    YEAR("slice.year", 252),

    TWO_YEARS("slice.twoYears", 504),

    THREE_YEARS("slice.threeYears", 756),

    FOUR_YEARS("slice.fourYears", 1_008),

    FIVE_YEARS("slice.fiveYears", 1_260),

    TEN_YEARS("slice.tenYears", 2_520),

    /** Everything the series has. */
    ALL("slice.all", 0),

    /**
     * Two dates, picked by hand.
     *
     * <p>The only one that is not "the last N sessions", and the only one that
     * cannot cut anything on its own — it needs the dates, and
     * {@link #between} is where they arrive. Asked to cut without them it hands
     * the series back whole, which is the harmless answer.</p>
     */
    CHOSEN("slice.chosen", -1);

    private final String key;

    private final int sessions;

    Slice(String key, int sessions) {
        this.key = key;
        this.sessions = sessions;
    }

    /** @return how this slice is named on screen */
    public String key() {
        return key;
    }

    /** @return whether it needs two dates from the reader */
    public boolean handPicked() {
        return this == CHOSEN;
    }

    /** @return how many sessions it covers, or zero when it covers all of them */
    public int sessions() {
        return Math.max(0, sessions);
    }

    /**
     * Cuts a series to this slice, counting back from its newest bar.
     *
     * @param series what to cut
     * @param zone   the exchange's zone, which decides where a session begins
     * @return the cut series, or the same one when there is nothing to cut
     */
    public PriceSeries cut(PriceSeries series, ZoneId zone) {
        if (series == null || series.size() == 0 || sessions <= 0) {
            return series;
        }

        LocalDate first = startOf(series, zone);

        return first == null
                ? series
                : SegmentedSeries.of(series, Segment.from(name(), first), zone);
    }

    /**
     * @return the first day of the last {@code sessions} sessions, or null when
     *         the series has fewer than that and the whole of it is the answer
     */
    private LocalDate startOf(PriceSeries series, ZoneId zone) {
        ZoneId at = zone == null ? Timeframe.defaultZone() : zone;

        LocalDate seen = null;
        int counted = 0;

        // BACKWARDS from the newest bar, counting the day change rather than the
        // bar. A session is not a fixed number of bars -- a short day before a
        // holiday has fewer -- so counting bars would make "one day" mean
        // something different on every one of them.
        for (int bar = series.size() - 1; bar >= 0; bar--) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(bar)).atZone(at).toLocalDate();

            if (!day.equals(seen)) {
                seen = day;
                counted++;

                if (counted > sessions) {
                    return dayAfter(series, bar, at);
                }
            }
        }

        return null;
    }

    /** @return the day of the bar that follows the one that went too far back */
    private static LocalDate dayAfter(PriceSeries series, int bar, ZoneId at) {
        return Instant.ofEpochMilli(series.timeAt(bar + 1)).atZone(at).toLocalDate();
    }

    /**
     * Cuts a series to two days chosen by hand, both included.
     *
     * @param series what to cut
     * @param from   the first session wanted
     * @param to     the last one, or null for "to the end"
     * @param zone   the exchange's zone
     * @return the cut series, or the same one when the dates say nothing
     */
    public static PriceSeries between(PriceSeries series, LocalDate from, LocalDate to,
                                      ZoneId zone) {
        if (series == null || from == null || (to != null && to.isBefore(from))) {
            return series;
        }

        return SegmentedSeries.of(series, new Segment(CHOSEN.name(), from, to),
                zone == null ? Timeframe.defaultZone() : zone);
    }
}
