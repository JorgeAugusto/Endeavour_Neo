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
 * <p><b>Not thread-safe.</b> The four lists it builds and the cache in front of
 * them have no synchronisation at all, so one of these belongs to one thread and
 * nothing here checks it.</p>
 *
 * <p><b>Which thread is not the same answer for every door.</b> {@link #add} and
 * {@link #addUpTo} read a whole session each and are called from a worker; that
 * is what "off the interface thread" was written about, and it is still true of
 * them. {@link #advance} is called by the CHART, on the interface thread, once
 * per frame -- and it reads a session too when the day it lands on is not
 * resident. Ninety megabytes, in the middle of a repaint.</p>
 *
 * <p>What keeps that from happening is that the caller asks the library for the
 * day and the one after it BEFORE advancing, so the file is normally already in
 * memory. Normally, not always. The sentence here used to say the class was
 * meant to be used off the interface thread, full stop, which would have told
 * the next person that this had been settled -- and a second caller written on
 * that belief would have found four unsynchronised lists.</p>
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

    /** How many trades made each brick, or UNKNOWN. See {@link Counted}. */
    private final List<Long> counts = new ArrayList<>();

    /** Which sessions are already in, so folding one twice is impossible. */
    private final Set<LocalDate> folded = new LinkedHashSet<>();

    /**
     * The newest session ever folded, whichever door it came in by.
     *
     * <p><b>Kept, because the set could not answer this.</b> {@code folded} is a
     * {@code LinkedHashSet}, so walking it gives the last one INSERTED, not the
     * newest -- and the order guard walked it. {@code advance} inserts through a
     * door of its own, and checked only against the day it happened to be
     * advancing through; {@code addUpTo} inserted and checked nothing at all.</p>
     *
     * <p>Three ways in, one of them blind and two of them looking at different
     * things, is a sequence that passes every guard and produces what they were
     * written to prevent: {@code add(D3)}, then {@code advance(D1)} -- which
     * passes, having nothing to compare against -- then {@code add(D2)}, which
     * compares against the last INSERTED, D1, and is waved through with D3
     * already inside. Bricks out of sequence and the ruler carried backwards,
     * neither of which shows in the result: the chart is simply wrong.</p>
     */
    private LocalDate newest;

    private Renko.Carry carry;

    /** The settled bricks as a series, kept until one more is laid. */
    private transient PriceSeries builtBricks;

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

        refuseIfEarlier(day);

        TickSeries session = library.load(day);

        remember(day);

        if (session == null || session.size() == 0) {
            return false;
        }

        return fold(TickBars.of(session));
    }

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
        boolean tail = false;

        if (!day.equals(advancing)) {
            // Against EVERYTHING folded, not only against the day being advanced
            // through. Backwards would carry the ruler back with it, and nothing
            // in the result would show that it happened.
            refuseIfEarlier(day);

            // THE TAIL OF THE SESSION BEING LEFT, which used to be dropped on
            // the way out. The clock stops wherever the replay stopped looking
            // -- somewhere inside the last bar it drew -- and everything printed
            // after that instant was lost: the state was replaced, and the day
            // was already in `folded`, so add() refuses to finish it.
            //
            // What goes missing is the closing auction, the largest print of the
            // day. The carry then crosses the night from a place the market
            // never stopped at, the ruler is offset for the whole rest of the
            // replay, and nothing on the chart says so.
            tail = advancingBars != null && advanced < advancingBars.size()
                    && fold(advancingBars.range(advanced, advancingBars.size()));

            TickSeries session = library.load(day);

            advancing = day;
            advanced = 0;
            advancingBars = session == null || session.size() == 0
                    ? null : TickBars.of(session);

            // Marked as folded so a later add() of the same day cannot lay it
            // a second time on top of what advance() already laid.
            remember(day);

            // THE DAY CHANGED, SO NOTHING IS FORMING. This used to be done only
            // when the new session had no bars at all -- and the comment beside
            // it named the defect exactly: "leaving the old edge would draw
            // yesterday's half-brick over a day that has not opened". The other
            // way out is just as reachable: a new session WITH bars whose clock
            // has not yet reached the first of them makes countUntil answer
            // zero, the guard below returns early, and the forming brick on
            // screen is still yesterday's -- at a level that may be on the far
            // side of the overnight gap, served to the chart by live(), frame
            // after frame.
            //
            // The edge is rebuilt by the first frame that folds anything.
            forming = null;

            if (advancingBars == null) {
                return tail;
            }
        }

        if (advancingBars == null) {
            return false;
        }

        int upTo = advancingBars.countUntil(when);

        if (upTo <= advanced) {
            // The tail is still an answer of yes: it laid bricks, and a caller
            // told "nothing was added" would skip the repaint that shows them.
            return tail;
        }

        boolean laid = fold(advancingBars.range(advanced, upTo));

        advanced = upTo;

        // The live edge. Without it the chart moves only when a whole brick
        // closes, which reads as "it froze and now it jumps" -- and that is
        // exactly how it was reported, because the renko it replaced draws this
        // brick on every pass.
        double price = advancingBars.closeAt(upTo - 1);

        // No withForming(true): formingAt reads `wicks` and the carry, and
        // never the `forming` flag -- so the copy was a Renko allocated on every
        // frame of a replay for an identical answer. Worse, it read as though
        // the flag took part in the calculation, which is the confusion
        // formingAt exists to end ("Here so there is ONE of it").
        forming = carry == null ? null : renko.formingAt(carry, price);
        formingStamp = advancingBars.timeAt(upTo - 1);

        return laid || tail;
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
            public long tradesAt(int index) {
                // And never a count: it is not one band yet.
                return index < settled.size()
                        ? Counted.at(settled, index) : Counted.UNKNOWN;
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

    /**
     * Folds in the part of a session that had happened by then.
     *
     * @param when the instant the replay has reached
     * @return whether anything was added
     *
     * <p>For the session being played: only the ticks up to the moment on the
     * clock.</p>
     *
     * <p><b>TERMINAL for that day.</b> The javadoc used to say the session was
     * not marked as folded because the rest of it was still to come, and to
     * call {@link #add} once the day was over. Following that instruction folds
     * the WHOLE session again on top of what this already laid: {@code add}
     * reads the file from the start, and the ruler carries, so the morning is
     * laid twice and every brick after it is displaced. The guard in {@code add}
     * that would have stopped it -- "already folded, do nothing" -- was the very
     * thing this method declined to arm.
     *
     * <p>What a replay wants is {@link #advance}, which keeps its place in the
     * session and carries on from it. This is for folding a session up to an
     * instant and being done with it.</p>
     */
    public boolean addUpTo(LocalDate day, long when) throws IOException {
        // ASKED HERE TOO. This checked nothing: it was the one door of the three
        // through which a session older than what is already laid could walk in.
        refuseIfEarlier(day);

        TickSeries session = library.load(day);

        // Marked whatever happens, including for a day with no ticks: what this
        // promises is that the day will not be folded again, and a day that
        // added nothing is still a day this was asked about.
        remember(day);

        if (session == null || session.size() == 0) {
            return false;
        }

        return fold(TickBars.of(session).until(when));
    }

    /**
     * @param day the session about to go in
     * @throws IllegalArgumentException if anything newer is already folded
     */
    private void refuseIfEarlier(LocalDate day) {
        if (newest != null && !day.isAfter(newest)) {
            throw new IllegalArgumentException(day + " comes before " + newest
                    + ", which is already in this renko");
        }
    }

    /** Writes the day down as folded, and as the newest if it is. */
    private void remember(LocalDate day) {
        folded.add(day);

        if (newest == null || day.isAfter(newest)) {
            newest = day;
        }
    }

    private boolean fold(PriceSeries bars) {
        if (bars.size() == 0) {
            return false;
        }

        Renko.Continued made = renko.applyFrom(bars, carry);

        carry = made.carry();

        PriceSeries laid = made.bricks();

        if (laid.size() > 0) {
            // A brick was laid, so the built view is out of date. Nothing else
            // touches these four lists.
            builtBricks = null;
        }

        for (int i = 0; i < laid.size(); i++) {
            untraded.set(bricks.size(), Untraded.at(laid, i));
            counts.add(Counted.at(laid, i));

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

    /**
     * @return the bricks as a series the chart can draw
     *
     * <p><b>Built once and kept until a brick is laid.</b> This copied
     * everything on every call -- an array of every row, two long arrays filled
     * by unboxing an {@code ArrayList<Long>}, and a clone of the {@code BitSet}
     * -- and {@link #live} calls it, which {@code ChartCanvas.extendBricks}
     * calls on every frame of a replay. O(n) per frame with n growing all
     * session: a long replay turned a chart into a copier.</p>
     */
    public PriceSeries bricks() {
        if (builtBricks != null) {
            return builtBricks;
        }

        double[][] rows = bricks.toArray(new double[0][]);
        long[] times = new long[stamps.size()];

        for (int i = 0; i < times.length; i++) {
            times[i] = stamps.get(i);
        }

        java.util.BitSet gaps = (java.util.BitSet) untraded.clone();

        long[] made = new long[counts.size()];

        for (int i = 0; i < made.length; i++) {
            made[i] = counts.get(i);
        }

        builtBricks = new Marked() {

            @Override
            public boolean untradedAt(int index) {
                return gaps.get(index);
            }

            @Override
            public long tradesAt(int index) {
                return made[index];
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

        return builtBricks;
    }

    /** A series that says which bars hold no trade, and how many the rest hold. */
    private interface Marked extends PriceSeries, Untraded, Counted { }

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
