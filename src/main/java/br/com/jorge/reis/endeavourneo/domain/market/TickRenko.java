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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A renko built from the exchange's own ticks, one session at a time.
 *
 * <h2>Why it is built in pieces</h2>
 *
 * <p>A session is 4,4 million ticks and 113 MB. Ten of them will not sit in
 * memory together, and a renko over ten days is a perfectly ordinary thing to
 * want. So each session is read, folded into bricks, and let go — and what is
 * kept is the bricks, which are 594 a day at brick 55. Four orders of
 * magnitude smaller, and the memory does not grow with the range.</p>
 *
 * <p>That only works because a renko continued from where the last one stopped
 * is the same renko: see {@link Renko#applyFrom} and the property test beside
 * it. Without the carried anchor each day would start its ruler at its own
 * opening price and the bricks would not line up across the night.</p>
 *
 * <h2>Extending, not rebuilding</h2>
 *
 * <p>A replay advances into a new session and the chart must follow. Folding in
 * one more session costs one read and one pass; rebuilding from the start would
 * cost the whole range again, every day, for ever.</p>
 *
 * <p><b>Not thread-safe, and meant to be used off the interface thread.</b>
 * Each {@link #add} reads a file.</p>
 */
public final class TickRenko {

    private final Renko renko;

    private final TickLibrary library;

    /** The bricks so far, as {open, high, low, close, volume}. */
    private final List<double[]> bricks = new ArrayList<>();

    private final List<Long> stamps = new ArrayList<>();

    /**
     * Which of those bricks no trade went through.
     *
     * <p>Carried alongside rather than folded into the row: a row is what the
     * chart reads as prices, and a sixth number in it that is really a flag is
     * the kind of thing that ends up drawn. Over ticks this matters more than
     * anywhere else -- the overnight gap is the one place a tick renko lays
     * bricks nobody traded. See {@link Untraded}.</p>
     */
    private final java.util.BitSet untraded = new java.util.BitSet();

    /** Which sessions are already in, so folding one twice is impossible. */
    private final Set<LocalDate> folded = new LinkedHashSet<>();

    private Renko.Carry carry;

    /** The session being advanced through, and how far into it. */
    private LocalDate advancing;

    private TickBars advancingBars;

    private int advanced;

    /** The brick still being built at the live edge, and when it was last moved. */
    private double[] forming;

    private long formingStamp;

    /**
     * @param renko the brick size and reversal; its forming brick is ignored
     * @param library where the sessions come from
     */
    public TickRenko(Renko renko, TickLibrary library) {
        // Without forming, always. A forming brick is provisional, and a
        // provisional brick in the middle of a range -- at the end of every
        // session but the last -- would be a brick that never existed.
        this.renko = renko.withForming(false);
        this.library = library;
    }

    /**
     * Folds one session in.
     *
     * @param day the session to add; must be after every session already in
     * @return whether it had ticks to add
     * @throws IOException if the session exists and will not read
     */
    public boolean add(LocalDate day) throws IOException {
        if (folded.contains(day)) {
            return false;
        }

        if (!folded.isEmpty()) {
            LocalDate last = null;

            for (LocalDate each : folded) {
                last = each;
            }

            if (last != null && !day.isAfter(last)) {
                // Out of order would put bricks in the wrong sequence AND carry
                // the ruler backwards, and neither is visible in the result --
                // the chart would simply be wrong.
                throw new IllegalArgumentException(day + " comes before " + last
                        + ", which is already in this renko");
            }
        }

        TickSeries session = library.load(day);

        folded.add(day);

        if (session == null || session.size() == 0) {
            return false;
        }

        // A new session: the ruler carries, the trades do not. See
        // Renko.Carry.atNewSession.
        if (carry != null) {
            carry = carry.atNewSession();
        }

        return fold(TickBars.of(session));
    }

    /**
     * Folds in the part of a session that had happened by then.
     *
     * @param when the instant the replay has reached
     * @return whether anything was added
     *
     * <p>For the session being played: only the ticks up to the moment on the
     * clock. The session is NOT marked as folded, because the rest of it is
     * still to come — call {@link #add} once the day is over.</p>
     */
    /**
     * Lays whatever the market has printed since the last call.
     *
     * @param day the session being played
     * @param when the replay's clock, in epoch milliseconds
     * @return whether any brick was laid
     * @throws IOException if the session exists and will not read
     *
     * <p>What a replay needs, and the reason {@link #addUpTo} is not it.
     * Measured on the tape of 01/09/2026 -- 5,8 million trades -- folding the
     * session again costs 0,105 s, so redoing it every frame would want 315% of
     * a core at thirty frames a second. This folds only the trades that arrived
     * since the last frame, and carries the ruler across so the bricks line up
     * with the ones already laid.</p>
     *
     * <p>The session's bars are held while it is being advanced through, since
     * finding the traded rows in 5,8 million is 0,056 s and doing that per
     * frame would be the same mistake one layer down.</p>
     */
    public boolean advance(LocalDate day, long when) throws IOException {
        if (!day.equals(advancing)) {
            if (advancing != null && day.isBefore(advancing)) {
                // Backwards would carry the ruler back with it, and nothing in
                // the result would show that it happened.
                throw new IllegalArgumentException(day + " comes before " + advancing
                        + ", which this renko is already advancing through");
            }

            TickSeries session = library.load(day);

            advancing = day;
            advanced = 0;
            advancingBars = session == null || session.size() == 0
                    ? null : TickBars.of(session);

            // Same as add(): a session starts with nothing traded yet. Only
            // here, where the day actually changes -- advance() is called every
            // frame and the trades inside one session do carry.
            if (carry != null) {
                carry = carry.atNewSession();
            }

            // Marked as folded so a later add() of the same day cannot lay it
            // a second time on top of what advance() already laid.
            folded.add(day);
        }

        if (advancingBars == null) {
            return false;
        }

        int upTo = advancingBars.countUntil(when);

        if (upTo <= advanced) {
            return false;
        }

        boolean laid = fold(advancingBars.range(advanced, upTo));

        advanced = upTo;

        // The live edge. Without it the chart moves only when a whole brick
        // closes, which reads as "it froze and now it jumps" -- and that is
        // exactly how it was reported, because the renko it replaced draws this
        // brick on every pass.
        double price = advancingBars.closeAt(upTo - 1);

        forming = carry == null ? null : renko.withForming(true).formingAt(carry, price);
        formingStamp = advancingBars.timeAt(upTo - 1);

        return laid;
    }

    /**
     * @return the bricks as a chart should show them right now
     *
     * <p>The settled ones plus the one still being built. Apart from {@link
     * #bricks} because the forming brick is provisional: it belongs on screen
     * and it must never be counted as laid, compared against a finished renko,
     * or carried into the next stretch.</p>
     */
    public PriceSeries live() {
        PriceSeries settled = bricks();

        if (forming == null) {
            return settled;
        }

        double[] edge = forming;
        long when = formingStamp;

        return new Marked() {

            @Override
            public boolean untradedAt(int index) {
                // The forming brick is where the price IS, so never a gap.
                return index < settled.size() && Untraded.at(settled, index);
            }

            @Override
            public int size() {
                return settled.size() + 1;
            }

            @Override
            public long timeAt(int index) {
                return index < settled.size() ? settled.timeAt(index) : when;
            }

            @Override
            public double openAt(int index) {
                return index < settled.size() ? settled.openAt(index) : edge[0];
            }

            @Override
            public double highAt(int index) {
                return index < settled.size() ? settled.highAt(index) : edge[1];
            }

            @Override
            public double lowAt(int index) {
                return index < settled.size() ? settled.lowAt(index) : edge[2];
            }

            @Override
            public double closeAt(int index) {
                return index < settled.size() ? settled.closeAt(index) : edge[3];
            }

            @Override
            public double volumeAt(int index) {
                return index < settled.size() ? settled.volumeAt(index) : edge[4];
            }
        };
    }

    /** @return the session being advanced through, or null before the first */
    public LocalDate advancing() {
        return advancing;
    }

    public boolean addUpTo(LocalDate day, long when) throws IOException {
        TickSeries session = library.load(day);

        if (session == null || session.size() == 0) {
            return false;
        }

        return fold(TickBars.of(session).until(when));
    }

    private boolean fold(PriceSeries bars) {
        if (bars.size() == 0) {
            return false;
        }

        Renko.Continued made = renko.applyFrom(bars, carry);

        carry = made.carry();

        PriceSeries laid = made.bricks();

        for (int i = 0; i < laid.size(); i++) {
            untraded.set(bricks.size(), Untraded.at(laid, i));

            bricks.add(new double[]{laid.openAt(i), laid.highAt(i),
                    laid.lowAt(i), laid.closeAt(i), laid.volumeAt(i)});
            stamps.add(laid.timeAt(i));
        }

        return laid.size() > 0;
    }

    /** @return the sessions folded in, in order */
    public List<LocalDate> sessions() {
        return List.copyOf(folded);
    }

    public int size() {
        return bricks.size();
    }

    /** @return the bricks as a series the chart can draw */
    public PriceSeries bricks() {
        double[][] rows = bricks.toArray(new double[0][]);
        long[] times = new long[stamps.size()];

        for (int i = 0; i < times.length; i++) {
            times[i] = stamps.get(i);
        }

        java.util.BitSet gaps = (java.util.BitSet) untraded.clone();

        return new Marked() {

            @Override
            public boolean untradedAt(int index) {
                return gaps.get(index);
            }

            @Override
            public int size() {
                return rows.length;
            }

            @Override
            public long timeAt(int index) {
                return times[index];
            }

            @Override
            public double openAt(int index) {
                return rows[index][0];
            }

            @Override
            public double highAt(int index) {
                return rows[index][1];
            }

            @Override
            public double lowAt(int index) {
                return rows[index][2];
            }

            @Override
            public double closeAt(int index) {
                return rows[index][3];
            }

            @Override
            public double volumeAt(int index) {
                return rows[index][4];
            }
        };
    }

    /** A series that also answers which of its bars hold no trade. */
    private interface Marked extends PriceSeries, Untraded { }

    /**
     * @param days the sessions to cover, in order
     * @return the renko over all of them
     *
     * <p>The whole-range build, for a chart that is not replaying anything.</p>
     */
    public static PriceSeries over(Renko renko, TickLibrary library, List<LocalDate> days)
            throws IOException {
        TickRenko building = new TickRenko(renko, library);

        for (LocalDate day : days) {
            building.add(day);
        }

        return building.bricks();
    }
}
