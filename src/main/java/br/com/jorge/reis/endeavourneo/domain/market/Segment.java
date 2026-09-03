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

import java.time.LocalDate;

/**
 * A named slice of a base, given by dates.
 *
 * <p>It exists so that <i>base, then slice, then scale</i> is a choice made in
 * one place, instead of separate files on disk and the reader remembering which
 * one is open. The names — <i>Study</i>, <i>Proof</i> — mean whatever the reader
 * decides they mean; nothing here enforces anything.</p>
 *
 * <p><b>Dates and not indices.</b> The base grows with every import. A slice
 * written as "bars 0 to 300,000" changes meaning every night; one written as a
 * date range does not change at all. The end is optional for the same reason: an
 * open slice grows along with the base, which is usually what a slice covering
 * the recent past is for.</p>
 *
 * <p>This is data and knows about no series. {@link SegmentedSeries} is what
 * puts the two together.</p>
 *
 * @param name what the reader calls it
 * @param from first day included
 * @param to last day included, or null for "onwards"
 */
public record Segment(String name, LocalDate from, LocalDate to) {

    public Segment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a segment without a name cannot be chosen");
        }

        if (from == null) {
            throw new IllegalArgumentException("a segment needs a first day");
        }

        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException(
                    "a segment that ends before it starts covers nothing: " + from + " to " + to);
        }

        name = name.trim();
    }

    /** @return a slice running from that day onwards, growing with the base */
    public static Segment from(String name, LocalDate first) {
        return new Segment(name, first, null);
    }

    public boolean isOpenEnded() {
        return to == null;
    }

    public boolean covers(LocalDate day) {
        return day != null && !day.isBefore(from) && (to == null || !day.isAfter(to));
    }

    /**
     * @return the slice written the way it is shown beside a result
     *
     * <p>Years and not full dates: it sits in a window title beside the base and
     * the scale, where "Study 2021–2024" is read at a glance and
     * "Study 2021-08-30 to 2024-12-31" is not read at all.</p>
     */
    public String label() {
        return name + " " + from.getYear() + (to == null ? "–" : "–" + to.getYear());
    }
}
