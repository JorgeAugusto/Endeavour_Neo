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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse;
import br.com.jorge.reis.endeavourneo.domain.indicator.Vwap;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Plotted;
import br.com.jorge.reis.endeavourneo.domain.trading.Sourced;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fading the levels of the two opening ranges and the VWAP bands.
 *
 * <p>The deterministic half of the integrated fade model, ported from its
 * specification. The model has two halves and only one of them is a robot:
 *
 * <ul>
 *   <li>a <b>deterministic engine</b> that decides <i>where</i> an order rests
 *       — the levels, the arming, the book — which is what this class is;</li>
 *   <li>a <b>network</b> that picks one of nine target/stop pairs per
 *       opportunity and abstains when its best estimate is negative, which this
 *       class does not have.</li>
 * </ul>
 *
 * <h2>Why the network's half could not simply be ported with it</h2>
 *
 * <p>Not because a network is hard. Because of where the specification's money
 * comes from: its profit and loss is <b>counterfactual</b> (section 6). For each
 * opportunity it walks forward and computes what each of the nine pairs would
 * have produced, and the network then picks one of those already-known numbers.
 * That is a way of scoring a decision, not a way of trading: nothing rests in a
 * book, nothing competes inside a bar, and a stop and a target that are both
 * reachable are settled by a rule written for the scoring pass.
 *
 * <p>Here the lot is real. Its stop and its target are orders, the broker
 * executes them by the platform's rules, and the result is whatever the market
 * reached. So the pair is a <b>parameter</b> — {@link #targetPoints()} and
 * {@link #stopPoints()}, defaulting to the specification's first action,
 * {@code T100_S150} — and the number this produces is not the golden number,
 * nor should it be compared to it as though it were the same measurement.
 *
 * <h2>What is faithful, line by line</h2>
 *
 * <ul>
 *   <li><b>Direction (section 3.3).</b> The day trades one side, and only when
 *       the first break of the opening range and the first break of the Range 90
 *       point the same way. That is {@link RangeGate} in {@code BOTH}, which was
 *       already here and is the same rule.</li>
 *   <li><b>Levels (section 4.1).</b> Extensions of both ranges at
 *       {@code k in {0, 0.25, 0.5, 0.75, 1, 1.5, 2}} above the high and below
 *       the low, plus each midpoint; sorted ascending and thinned so that no
 *       kept level sits within {@link #MIN_SPACING} of the previous one. The
 *       sort is stable and the opening range is inserted first, so a tie keeps
 *       the opening range's name — which is what the specification's Python
 *       does, and it decides which name a shared price carries.</li>
 *   <li><b>Bands (section 4.2).</b> The VWAP's own deviation, one to four out,
 *       read from the PREVIOUS closed bar as the specification requires.</li>
 *   <li><b>Arming (section 4.3).</b> A source arms once the price has gone
 *       {@link #ARM_DISTANCE} points BEYOND its level — above it to buy, below
 *       it to sell — and only then may the level be touched for an entry. The
 *       order is a limit at the level, so the broker fills it at
 *       {@code min(open, level)} to buy: the specification's fill rule and the
 *       platform's are the same rule.</li>
 *   <li><b>The book (section 10).</b> Lots do not share a stop or an average.
 *       Each entry is its own lot of {@link #QTY} contracts with its own target
 *       and its own stop, and the book only limits how many, how close and how
 *       often.</li>
 * </ul>
 *
 * <h2>Where it cannot be faithful, and which way the difference runs</h2>
 *
 * <p><b>The choice among candidates.</b> The specification picks the level
 * nearest the open of the candle the fill happens in — it is a scanner, so it
 * has already seen that open and knows which levels the candle touched. A robot
 * decides at a close and its order rests through the NEXT bar, whose open does
 * not exist yet. This picks the candidate nearest the close it is deciding on.
 * The difference runs the conservative way: the scanner chooses knowing what was
 * touched, and this one chooses before.
 *
 * <p><b>The stop can refuse a gap.</b> The counterfactual pass always fills a
 * stop at {@code min(open, stop)}, however far the bar opened past it. A real
 * stop carries a limit, and past that limit it does not fill. {@link #slip()}
 * says how far, and at zero it refuses exactly the bar a stop exists for.
 *
 * <p><b>Volume.</b> {@link PriceSeries} carries none, so the VWAP is weighted by
 * one — see {@link Vwap}. The bands are therefore not the specification's bands
 * on a series whose volume varies, and any parity check has to start there.
 */
public final class LevelFade implements Strategy, Sourced, Plotted {

    /** Points beyond a level before that level may be faded. */
    public static final double ARM_DISTANCE = 75;

    /** How far apart kept levels must sit, and how far an entry must be from an open lot. */
    public static final double MIN_SPACING = 100;

    /** Bars that must pass since a source's last entry, and since any fill. */
    public static final int COOLDOWN = 3;

    /** Contracts per entry. */
    public static final int QTY = 2;

    public static final int MAX_ENTRIES_DAY = 20;

    public static final int MAX_POSITION = 40;

    /** After this, the day has stopped being tradeable. */
    public static final LocalTime END = LocalTime.of(17, 45);

    /** {@code T100} — the first of the specification's nine actions. */
    public static final double TARGET = 100;

    /** {@code S150} — its stop. */
    public static final double STOP = 150;

    /** How far past its trigger a protective stop may still fill. */
    public static final double SLIP = 200;

    /** The extensions of section 4.1, in multiples of the range's own size. */
    private static final double[] EXTENSIONS = {0, 0.25, 0.5, 0.75, 1, 1.5, 2};

    private final ZoneId zone;

    private final double targetPoints;

    private final double stopPoints;

    private final double slip;

    private final int lot;

    /** One level, and the name the report knows it by. */
    private record Level(String source, double price) { }

    /** One entry: its own contracts, its own target, its own stop. */
    private record Lot(double entry, double target, double stop, int quantity) { }

    private PriceSeries bars = PriceSeries.empty();

    private PriceSeries source = PriceSeries.empty();

    private int size;

    /** Which side the two ranges agree on, per decision bar; zero is no day. */
    private int[] gate = new int[0];

    private Vwap.Lines vwap = new Vwap.Lines(new double[0], new double[0]);

    private List<OpeningRange.Session> clock = List.of();

    private List<OpeningImpulse.Fight> fights = List.of();

    // ------------------------------------------------------------- the day

    private LocalDate today;

    private List<Level> statics = new ArrayList<>();

    private final Map<String, Boolean> armed = new LinkedHashMap<>();

    private final Map<String, Integer> lastEntry = new LinkedHashMap<>();

    private final List<Lot> open = new ArrayList<>();

    private int entriesToday;

    private int lastFillBar = Integer.MIN_VALUE;

    /** The level the order resting right now was sent for, so a fill can be matched. */
    private double resting = Double.NaN;

    private double[] middle;

    private double[] upper;

    private double[] lower;

    public LevelFade() {
        this(null, TARGET, STOP, SLIP, QTY);
    }

    /**
     * @param zone   the exchange's zone, which decides where a session begins
     * @param target points from the entry to the target
     * @param stop   points from the entry to the stop
     * @param slip   how far past its trigger the stop may still fill
     * @param lot    contracts per entry
     */
    public LevelFade(ZoneId zone, double target, double stop, double slip, int lot) {
        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.targetPoints = Math.max(1, target);
        this.stopPoints = Math.max(1, stop);
        this.slip = Math.max(0, slip);
        this.lot = Math.max(1, lot);
    }

    @Override
    public void sourcedFrom(PriceSeries stored) {
        this.source = stored == null ? PriceSeries.empty() : stored;
    }

    @Override
    public void start(PriceSeries series) {
        bars = series == null ? PriceSeries.empty() : series;
        size = bars.size();

        PriceSeries stored = source.size() == 0 ? bars : source;

        gate = RangeGate.of(bars, stored, zone, RangeGate.Mode.BOTH);
        vwap = Vwap.standard().over(bars, zone);

        clock = OpeningRange.of(stored, zone, RangeGate.FORMATION);
        fights = OpeningImpulse.standard()
                .of(Timeframe.ofMinutes(OpeningImpulse.MINUTES).apply(stored, zone), zone);

        middle = blank(size);
        upper = blank(size);
        lower = blank(size);

        today = null;
        statics = new ArrayList<>();
        armed.clear();
        lastEntry.clear();
        open.clear();
        entriesToday = 0;
        // FAR ENOUGH BACK TO ALLOW THE FIRST ENTRY, and no further. Integer's
        // minimum was the obvious sentinel and it was wrong: bar - MIN_VALUE
        // OVERFLOWS to a negative number, so the cooldown refused every
        // candidate of every day and the strategy never traded once.
        lastFillBar = -COOLDOWN - 1;
        resting = Double.NaN;
    }

    private static double[] blank(int many) {
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    // ------------------------------------------------------------------ bar

    @Override
    public void onBar(Market market, Desk desk) {
        if (size == 0) {
            return;
        }

        int bar = market.bar();

        settle(market.filled(), bar);
        draw(bar);

        LocalDate now = dateOf(bar);

        if (!now.equals(today)) {
            startTheDay(now);
        }

        int side = gate[bar];

        // EVERY LOT ASKS AGAIN, EVERY BAR. The book is rebuilt from what the
        // strategy says at each close, and the broker's OCO is a set of legs --
        // so four open lots rest four stops and four targets, and a fill on one
        // leaves the other three standing.
        cover(market, desk);

        if (closing(bar)) {
            // THE SPECIFICATION ENDS THE DAY AT THE LAST CLOSE (section 6), so
            // nothing crosses the night. The order goes out one bar early
            // because ClosePosition is a market order and fills at the next
            // open -- sent on the last bar, that open is tomorrow's gap.
            if (market.hasPosition()) {
                desk.closePosition();
            }

            open.clear();
            resting = Double.NaN;

            return;
        }

        if (side == 0 || market.hasPosition() && lotsHeld() * lot >= MAX_POSITION) {
            resting = Double.NaN;

            return;
        }

        buildLevelsOnce(bar, side);

        Level wanted = choose(bar, side);

        resting = wanted == null ? Double.NaN : wanted.price();

        if (wanted == null) {
            return;
        }

        // A LIMIT AT THE LEVEL, which is the specification's own fill rule: a
        // buy fills at min(open, level) and a sell at max(open, level), and
        // that is exactly what a limit order does in this engine.
        if (side > 0) {
            desk.buyLimit(wanted.price(), lot);
        } else {
            desk.sellShortLimit(wanted.price(), lot);
        }
    }

    // ---------------------------------------------------------------- lots

    private void settle(List<Fill> fills, int bar) {
        for (Fill fill : fills) {
            String verb = fill.verb();

            if (verb == null) {
                continue;
            }

            lastFillBar = bar;

            if (verb.contains("Cover") || verb.contains("Close")) {
                closeTheLotThatWentAt(fill.price());

                continue;
            }

            // AN OPENING FILL. Its target and its stop are measured from the
            // price it actually got, not from the level that was asked for:
            // a limit fills at the open when the open is already better, and
            // measuring from the level would put the risk somewhere the trade
            // never was.
            int side = verb.startsWith("Buy") ? 1 : -1;
            double entry = fill.price();

            open.add(new Lot(entry, entry + side * targetPoints,
                    entry - side * stopPoints, fill.quantity()));

            entriesToday++;
        }
    }

    /**
     * Drops the lot whose target or stop that price is.
     *
     * <p>Matched by level rather than by order: the engine reports the price a
     * cover filled at, and each lot's two levels are its own. The nearest is
     * taken so a stop that filled past its trigger -- the gap it exists for --
     * still finds its lot.</p>
     */
    private void closeTheLotThatWentAt(double price) {
        Lot nearest = null;
        double best = Double.POSITIVE_INFINITY;

        for (Lot each : open) {
            double away = Math.min(Math.abs(each.target() - price),
                    Math.abs(each.stop() - price));

            if (away < best) {
                best = away;
                nearest = each;
            }
        }

        if (nearest != null) {
            open.remove(nearest);
        }
    }

    private void cover(Market market, Desk desk) {
        int held = market.buyPositionQty() + market.sellPositionQty();

        if (held == 0 || open.isEmpty()) {
            return;
        }

        boolean bought = market.isBought();
        int left = held;

        for (Lot each : open) {
            int many = Math.min(each.quantity(), left);

            if (many < 1) {
                break;
            }

            left -= many;

            if (bought) {
                desk.sellToCoverStop(each.stop(), each.stop() - slip, many);
                desk.sellToCoverLimit(each.target(), many);
            } else {
                desk.buyToCoverStop(each.stop(), each.stop() + slip, many);
                desk.buyToCoverLimit(each.target(), many);
            }
        }
    }

    private int lotsHeld() {
        int many = 0;

        for (Lot each : open) {
            many += each.quantity();
        }

        return many / Math.max(1, lot);
    }

    // -------------------------------------------------------------- the day

    private void startTheDay(LocalDate now) {
        today = now;
        statics = new ArrayList<>();
        armed.clear();
        lastEntry.clear();
        open.clear();
        entriesToday = 0;
        // FAR ENOUGH BACK TO ALLOW THE FIRST ENTRY, and no further. Integer's
        // minimum was the obvious sentinel and it was wrong: bar - MIN_VALUE
        // OVERFLOWS to a negative number, so the cooldown refused every
        // candidate of every day and the strategy never traded once.
        lastFillBar = -COOLDOWN - 1;
        resting = Double.NaN;
    }

    /**
     * The static levels of section 4.1, built once the day has a side.
     *
     * <p>Once, and not per bar: they come from two ranges that stopped moving
     * before the first break, and rebuilding them every minute would be the same
     * list computed four hundred times a session.</p>
     */
    private void buildLevelsOnce(int bar, int side) {
        if (!statics.isEmpty()) {
            return;
        }

        List<Level> all = new ArrayList<>();

        // OPENING RANGE FIRST, RANGE 90 AFTER, because the thinning below keeps
        // the FIRST of two levels at the same price and drops the second. The
        // order decides which name a shared price carries, and the
        // specification names that order explicitly.
        OpeningImpulse.Fight fight = fightOf(today);

        if (fight != null && fight.known()) {
            levelsOf(all, "abertura", fight.high(), fight.low());
        }

        OpeningRange.Session session = clockOf(today);

        if (session != null) {
            levelsOf(all, "range90", session.high(), session.low());
        }

        all.sort(Comparator.comparingDouble(Level::price));

        List<Level> kept = new ArrayList<>();

        for (Level each : all) {
            if (kept.isEmpty()
                    || each.price() - kept.get(kept.size() - 1).price() >= MIN_SPACING) {
                kept.add(each);
            }
        }

        statics = kept;
    }

    private static void levelsOf(List<Level> into, String name, double high, double low) {
        double span = high - low;

        if (!(span > 0)) {
            return;
        }

        for (double each : EXTENSIONS) {
            into.add(new Level(name + "_ext_sup_" + each, high + each * span));
            into.add(new Level(name + "_ext_inf_" + each, low - each * span));
        }

        into.add(new Level(name + "_mid", (high + low) / 2));
    }

    private OpeningImpulse.Fight fightOf(LocalDate day) {
        for (OpeningImpulse.Fight each : fights) {
            if (each.day().equals(day)) {
                return each;
            }
        }

        return null;
    }

    private OpeningRange.Session clockOf(LocalDate day) {
        for (OpeningRange.Session each : clock) {
            if (each.day().equals(day)) {
                return each;
            }
        }

        return null;
    }

    // ------------------------------------------------------------ the choice

    /**
     * @return the level to rest an order at, or null when nothing qualifies
     *
     * <p>Every gate of section 4.3 in one place, and the order matters only in
     * that the cheapest tests come first.</p>
     */
    private Level choose(int bar, int side) {
        if (entriesToday >= MAX_ENTRIES_DAY) {
            return null;
        }

        if (bar - lastFillBar < COOLDOWN) {
            return null;
        }

        Level best = null;
        double nearest = Double.POSITIVE_INFINITY;
        double close = bars.closeAt(bar);

        for (Level each : candidates(bar)) {
            if (!arms(each, bar, side)) {
                continue;
            }

            if (tooCloseToAnOpenLot(each.price())) {
                continue;
            }

            Integer when = lastEntry.get(each.source());

            if (when != null && bar - when < COOLDOWN) {
                continue;
            }

            double away = Math.abs(each.price() - close);

            if (away < nearest) {
                nearest = away;
                best = each;
            }
        }

        return best;
    }

    /** The static levels of the day, plus the bands as they stand on this bar. */
    private List<Level> candidates(int bar) {
        List<Level> all = new ArrayList<>(statics);

        // THE PREVIOUS CLOSED BAR's VWAP, as section 4.2 requires. Read on this
        // bar it would be a number the bar had not finished making.
        for (int away = 1; away <= Vwap.BANDS; away++) {
            double below = vwap.bandAt(bar - 1, -away);
            double above = vwap.bandAt(bar - 1, away);

            if (!Double.isNaN(below)) {
                all.add(new Level("vwap_m" + away, below));
            }

            if (!Double.isNaN(above)) {
                all.add(new Level("vwap_p" + away, above));
            }
        }

        return all;
    }

    /**
     * Whether this source has been armed, and arms it when this bar does it.
     *
     * <p>Armed by THIS bar counts for the NEXT one, which is the
     * specification's rule and also this engine's: what is decided at a close
     * rests through the following bar.</p>
     */
    private boolean arms(Level level, int bar, int side) {
        boolean reached = side > 0
                ? bars.highAt(bar) >= level.price() + ARM_DISTANCE
                : bars.lowAt(bar) <= level.price() - ARM_DISTANCE;

        if (reached) {
            armed.put(level.source(), Boolean.TRUE);
        }

        // THIS BAR COUNTS. The specification says the current high and low arm
        // an entry for the NEXT candle, and the next candle is exactly where an
        // order sent from this close rests -- so reading only the state from
        // before this bar would hold every entry back by one bar it is entitled
        // to, and the level would often have been and gone.
        return Boolean.TRUE.equals(armed.get(level.source()));
    }

    private boolean tooCloseToAnOpenLot(double price) {
        for (Lot each : open) {
            if (Math.abs(price - each.entry()) < MIN_SPACING) {
                return true;
            }
        }

        return false;
    }

    // ------------------------------------------------------------- the clock

    private LocalDate dateOf(int bar) {
        return Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();
    }

    private LocalTime timeOf(int bar) {
        return Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalTime();
    }

    /** @return whether this bar is the last of its session, or past the end */
    private boolean closing(int bar) {
        if (!timeOf(bar).isBefore(END)) {
            return true;
        }

        return bar + 2 >= size || !dateOf(Math.min(bar + 2, size - 1)).equals(today);
    }

    // ------------------------------------------------------------ the report

    private void draw(int bar) {
        if (bar < 1) {
            return;
        }

        middle[bar] = vwap.bandAt(bar - 1, 0);
        upper[bar] = vwap.bandAt(bar - 1, 2);
        lower[bar] = vwap.bandAt(bar - 1, -2);
    }

    public double targetPoints() {
        return targetPoints;
    }

    public double stopPoints() {
        return stopPoints;
    }

    public double slip() {
        return slip;
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("VWAP", middle);
        drawn.put("+2 dp", upper);
        drawn.put("-2 dp", lower);

        return drawn;
    }

    @Override
    public String toString() {
        return "Fade de níveis";
    }

    // ------------------------------------------------------- for a test to read

    /** @return the level an order is resting at right now, or NaN */
    double restingAt() {
        return resting;
    }

    /** @return how many entries the current session has taken */
    int entriesTaken() {
        return entriesToday;
    }

    /** @return the day's thinned static levels, in price order */
    List<Double> levelsNow() {
        List<Double> made = new ArrayList<>();

        for (Level each : statics) {
            made.add(each.price());
        }

        return made;
    }

    /** @return the side the two ranges agreed on at that bar */
    int sideAt(int bar) {
        return bar >= 0 && bar < gate.length ? gate[bar] : 0;
    }
}
