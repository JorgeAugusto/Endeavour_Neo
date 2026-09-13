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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Plotted;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Range 90 + EMA 10 + giro por pullbacks.
 *
 * <p>The strategy of {@code ESPECIFICACAO_ESTRATEGIA_RANGE90_PULLBACK.md}: the
 * first ninety minutes of a session make a range, the first break of it is
 * taken, the daily EMA 10 decides which side may be taken at all, a walk-forward
 * selector decides each morning whether to trade and whether the target is 1,5R
 * or 2R, and the position is then worked — four contracts to start, half of each
 * lot out at 0,5R, a new lot of four on every confirmed pullback, twenty
 * contracts at once at the very most.
 *
 * <h2>It is already refuted, and that is not a reason not to build it</h2>
 *
 * <p>His own note {@code range-de-abertura-e-bussola} records the range 90 as
 * <b>negative in all three cells of the blind base</b>, and the R$ 148.811 of
 * the specification's §19 comes from the base the strategy was searched on. What
 * this class is for is the machinery: a lot book, individual stops, partials and
 * a walk-forward selector are things the engine had never been asked for, and a
 * strategy that exercises all four is worth more than its own result.
 *
 * <h2>Where this cannot match the Python, and why that is right</h2>
 *
 * <p>Four differences, all of them the reference implementation reading
 * something this engine will not let a strategy read:
 *
 * <ol>
 *   <li><b>The entry is a resting stop order, not a price picked after the
 *       fact.</b> §4 enters at {@code max(open, trigger)} of the candle that
 *       broke — a candle whose shape is only known once it closed. Here a stop
 *       order rests at the trigger from the moment the formation closes, and the
 *       engine fills it at the triggered price bounded by the open, which is the
 *       same number arrived at without knowing it in advance.</li>
 *   <li><b>A day whose two sides broke inside one minute is entered here.</b>
 *       §4 calls it ambiguous and skips it. Skipping it needs the minute to have
 *       closed; only one side is ever armed here — the EMA's — so the question
 *       never comes up at order time. Days where the <i>wrong</i> side broke
 *       first in an <b>earlier</b> minute are still refused, because that can be
 *       seen as it happens.</li>
 *   <li><b>An added lot pays the gap.</b> §10.2 buys at the previous candle's
 *       high even when the confirming candle opened beyond it, and §15 admits
 *       this is an approximation. The stop order here fills at the open in that
 *       case, which is worse and is what would have happened.</li>
 *   <li><b>Several lots do not stop out inside one bar in OHLC mode.</b> Cover
 *       orders are one OCO in the Profit, so one leg fills and the rest die; the
 *       book re-arms on the next bar. Over <b>ticks</b> that next bar is the next
 *       print, so the lots do come out one after another inside the minute —
 *       which is exactly what the tick mode was built for.</li>
 * </ol>
 *
 * <p>None of these are worth "fixing": matching the Python's numbers would mean
 * making the engine read the bar it is trading on.
 *
 * <h2>It reads the scale it is given</h2>
 *
 * <p>The engine hands a strategy the <b>decision</b> series and fills its orders
 * against the <b>executed</b> one, so everything here — the range, the slope, the
 * pullback — is read at whatever scale the chart is showing, and the orders fill
 * as finely as the execution mode says. This class regrouped the bars by hand
 * before the engine learned to do it; that code is gone.
 *
 * <p>One consequence worth saying out loud: <b>the pullback candle follows the
 * chart.</b> At five minutes a pullback is a five-minute close under the previous
 * five-minute close and not a one-minute one, which makes it a different
 * strategy — the specification was written at one minute. What does not move is
 * the formation: it is ninety minutes of <i>clock</i> from the session's first
 * bar, and that is the same ninety minutes at any scale.
 */
public final class RangeBreakout implements Strategy, Plotted {

    /** Contracts in the first lot, and in each one added afterwards. */
    public static final int LOT = 4;

    /** Never more than this many contracts at once. Giro during the day is free. */
    public static final int CAP = 20;

    /** How far the trade must run before a pullback may be armed at all. */
    static final double ARM_R = 0.25;

    /** How far a lot must run before it sheds half of itself. */
    static final double PARTIAL_R = 0.5;

    /** What a fixed target is when nobody has chosen one: the nearer of the two. */
    public static final double TARGET = 1.5;

    /** The only two targets the selector is allowed to choose between. */
    static final double[] TARGETS = {TARGET, 2.0};

    /** The break must come sooner than this for the day to be taken. */
    public static final int ENTRY_WINDOW = 30;

    /** The minutes of clock the range is built from: the 90 of the name. */
    public static final int FORMATION = OpeningRange.FORMATION_MINUTES;

    /**
     * Everything is out by this time, whatever the session does afterwards.
     *
     * <p>The specification's §14 manages to the last candle of the date, which
     * on the WIN is 18:24 — and a position alive until the last minute of the
     * day is one held through the closing auction, when the book thins and a
     * stop is filled wherever it lands. It also <b>reads</b> as an overnight
     * position on the chart: a band running to 18:24 and the next day's starting
     * at 10:50 look like one mark.</p>
     *
     * <p>17:45, then: late enough that the afternoon is still traded, early
     * enough to be out before the close does its own thing.</p>
     */
    public static final LocalTime CLOSE_AT = LocalTime.of(17, 45);

    /** The selector scores a wider window than the strategy trades. */
    static final int SELECTOR_WINDOW = 60;

    static final int RECENT_SESSIONS = 20;

    static final int STRUCTURAL_MONTHS = 24;

    static final int LEAST_STRUCTURAL = 20;

    static final int LEAST_RECENT = 5;

    /** What the specification charges: R$ 1,00 the round trip, per contract. */
    static final double COST_BRL = 1.0;

    /** WIN: twenty centavos a point, per contract. */
    static final double PER_POINT = 0.20;

    private final ZoneId zone;

    private final int lot;

    private final int cap;

    private final int window;

    private final int formation;

    private final LocalTime closeAt;

    /** Whether the walk-forward selector decides the day and the target. */
    private final boolean selecting;

    /** The target used when it does not, in R. */
    private final double fixedTarget;

    // ---------------------------------------------------------------- the run

    /** The decision bars, at whatever scale the reader is looking at. */
    private PriceSeries candles;

    private List<OpeningRange.Session> sessions;

    /** The session each bar belongs to, or {@code -1} when it is in none. */
    private int[] sessionOfBar;

    /** The target the selector authorised for each session, or NaN for none. */
    private double[] authorised;

    private int[] leaning;

    private int bars;

    // ---------------------------------------------------------------- the day

    /** One lot of the book: contracts opened together, with their own stop. */
    private static final class Lot {

        private int quantity;

        private final double entry;

        private final double stop;

        private boolean shed;

        private Lot(int quantity, double entry, double stop) {
            this.quantity = quantity;
            this.entry = entry;
            this.stop = stop;
        }
    }

    private final List<Lot> book = new ArrayList<>();

    private int today = -1;

    /** {@code +1} long, {@code -1} short, {@code 0} while the day has no side. */
    private int side;

    private double risk;

    private double entry;

    private double target;

    private boolean entered;

    private boolean refused;

    private boolean armed;

    private double pullbackExtreme;

    private double previousClose;

    /** Where the entry stop rests while the window is open, or NaN. */
    private double trigger;

    /** Where an added lot's stop rests while a pullback is armed, or NaN. */
    private double adding;

    private double addStop;

    // -------------------------------------------------------------- the lines

    private double[] rangeHigh;

    private double[] rangeLow;

    private double[] stopLine;

    private double[] targetLine;

    public RangeBreakout() {
        this(null, LOT, CAP, ENTRY_WINDOW, FORMATION, CLOSE_AT);
    }

    RangeBreakout(ZoneId zone) {
        this(zone, LOT, CAP, ENTRY_WINDOW, FORMATION, CLOSE_AT);
    }

    /**
     * @param zone      the exchange's zone, which decides where a session begins
     * @param lot       contracts in the first lot and in each one added
     * @param cap       the most contracts that may be held at once
     * @param window    the break must come sooner than this, in minutes
     * @param formation minutes of clock the range is built from
     */
    public RangeBreakout(ZoneId zone, int lot, int cap, int window, int formation) {
        this(zone, lot, cap, window, formation, CLOSE_AT);
    }

    /**
     * @param closeAt everything is out by this time of day
     * @see #RangeBreakout(ZoneId, int, int, int, int)
     */
    public RangeBreakout(ZoneId zone, int lot, int cap, int window, int formation,
                         LocalTime closeAt) {
        this(zone, lot, cap, window, formation, closeAt, true, TARGET);
    }

    /**
     * @param selecting whether the selector decides the day and the target
     * @param target    the target in R when it does not, at least a tick of one
     * @see #RangeBreakout(ZoneId, int, int, int, int)
     *
     * <p>With the selector OFF every session that breaks is traded, at one
     * fixed target. That is a different animal and the reader has to know it:
     * the selector is the only thing in the strategy that ever refuses a day,
     * and §6 of the specification exists because the range operated blind lost
     * money on this series. Switching it off is how you SEE that — it is not a
     * setting to leave off and then read the curve as a result.</p>
     */
    public RangeBreakout(ZoneId zone, int lot, int cap, int window, int formation,
                         LocalTime closeAt, boolean selecting, double target) {
        this.selecting = selecting;

        // A TARGET OF ZERO IS AN EXIT AT THE ENTRY, and a negative one is an
        // exit behind it -- both of them trades that close the instant they
        // open. The floor keeps the screen from being able to say that.
        this.fixedTarget = Double.isNaN(target) || target <= 0 ? TARGET : target;
        this.closeAt = closeAt == null ? CLOSE_AT : closeAt;
        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.lot = Math.max(1, lot);

        // THE CAP IS NEVER BELOW THE LOT. One below and the first entry itself
        // would not fit, and the strategy would sit out every day without ever
        // saying why -- a screen that reads "4 contracts, ceiling 2" has to mean
        // something, and the only meaning available is four.
        this.cap = Math.max(this.lot, cap);
        this.window = Math.max(1, window);
        this.formation = Math.max(1, formation);
    }

    @Override
    public void start(PriceSeries series) {
        candles = series == null ? PriceSeries.empty() : series;
        bars = candles.size();
        sessions = OpeningRange.of(candles, zone, formation);
        leaning = DailyTrend.directions(candles, sessions);
        sessionOfBar = new int[bars];

        Arrays.fill(sessionOfBar, -1);

        for (int day = 0; day < sessions.size(); day++) {
            OpeningRange.Session each = sessions.get(day);

            for (int bar = each.first(); bar <= each.last(); bar++) {
                sessionOfBar[bar] = day;
            }
        }

        authorised = choose();

        rangeHigh = blank(bars);
        rangeLow = blank(bars);
        stopLine = blank(bars);
        targetLine = blank(bars);

        book.clear();

        today = -1;
        side = 0;
        entered = false;
        refused = false;
        armed = false;
    }

    private static double[] blank(int size) {
        double[] made = new double[size];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    @Override
    public void onBar(Market market, Desk desk) {
        if (bars == 0) {
            return;
        }

        int bar = market.bar();
        int day = sessionOfBar[bar];

        // EVERYTHING THAT FILLED SINCE THE LAST TURN, which over a tick path is
        // a whole bar of them and not one. A book that took only the last fill
        // would keep re-emitting orders for contracts that left four ticks ago.
        settle(market.filled());

        // E DEPOIS O LIVRO SE CURVA A POSICAO. O livro e um modelo e a posicao
        // do motor e o fato; um modelo que afirme mais contratos do que existem
        // passa o resto do dia emitindo stop para papel que ja saiu.
        trim(Math.abs(market.buyPositionQty() - market.sellPositionQty()));

        if (day < 0) {
            return;
        }

        if (day != today) {
            begin(day);
        }

        draw(bar, day);
        decide(bar, day);
        emit(market, desk);
    }

    /** Opens a fresh day: nothing of the previous one survives into it. */
    private void begin(int day) {
        today = day;
        side = 0;
        risk = Double.NaN;
        entry = Double.NaN;
        target = Double.NaN;
        entered = false;
        refused = Double.isNaN(authorised[day]) || leaning[day] == 0;
        armed = false;
        pullbackExtreme = Double.NaN;
        previousClose = Double.NaN;
        trigger = Double.NaN;
        adding = Double.NaN;
        addStop = Double.NaN;

        book.clear();
    }

    /**
     * What the strategy decides, once a minute.
     *
     * <p>The engine's own steps — the stops, the partials, the target — are
     * orders, and orders are settled where orders are settled. What is left here
     * is the part that is genuinely a decision: whether the day may be entered at
     * all, and where the pullback stands.
     */
    private void decide(int bar, int day) {
        OpeningRange.Session session = sessions.get(day);

        // OUT AT 17:45, or at the session's last minute if it ends sooner.
        //
        // Both are decided one minute EARLY, because nothing here can execute at
        // a close: the order goes out at the close of the minute that ends at
        // the deadline, and fills at the open of the minute that starts on it.
        // Waiting until the deadline itself would fill a minute after it.
        LocalTime ends = Instant.ofEpochMilli(endOf(bar)).atZone(zone).toLocalTime();

        if (!ends.isBefore(closeAt) || bar >= session.last() - 1) {
            trigger = Double.NaN;
            adding = Double.NaN;

            if (!book.isEmpty()) {
                book.clear();
                entered = true;
                refused = true;
            }

            return;
        }

        if (!entered) {
            watch(bar, session);

            return;
        }

        if (book.isEmpty()) {
            // Everything came out: the day is over, and it does not re-enter.
            refused = true;
            trigger = Double.NaN;
            adding = Double.NaN;

            return;
        }

        pullback(bar);
    }

    /**
     * @return when that bar ends, in epoch millis
     *
     * <p>The NEXT bar's start where there is one, because a bar's length is only
     * known from its neighbour: at one minute this is the same as adding sixty
     * seconds and at five minutes it is not. The last bar of all has no
     * neighbour, and a minute is the smallest honest guess.</p>
     */
    private long endOf(int bar) {
        return bar + 1 < bars ? candles.timeAt(bar + 1) : candles.timeAt(bar) + 60_000L;
    }

    /**
     * Before the entry: arms the trigger, and gives the day up when the wrong
     * side breaks first.
     *
     * <p>Both of those can be done as they happen. Refusing a day because its two
     * sides broke inside <b>one</b> minute cannot, and is not done — see the
     * class note.
     */
    private void watch(int bar, OpeningRange.Session session) {
        long ends = endOf(bar);

        // NOT "<=". The formation is [first, formedAt), so its last bar ENDS
        // exactly at formedAt -- and that close is the first moment the range
        // is known and the first chance to rest the trigger. Refusing the
        // boundary here left the order out of the whole first tradable bar and
        // pushed every entry one bar late.
        if (refused || ends < session.formedAt()) {
            return;
        }

        if ((ends - session.formedAt()) / 60_000L >= window) {
            trigger = Double.NaN;
            refused = true;

            return;
        }

        int wanted = leaning[today];
        double up = session.high() + OpeningRange.TICK;
        double down = session.low() - OpeningRange.TICK;

        // The FIRST break decides the day, whichever side it was on. A break
        // against the EMA kills the day rather than leaving the trigger armed
        // for a second break the specification would never have taken.
        boolean brokeUp = candles.highAt(bar) >= up;
        boolean brokeDown = candles.lowAt(bar) <= down;

        if ((brokeUp && wanted < 0) || (brokeDown && wanted > 0)) {
            trigger = Double.NaN;
            refused = true;

            return;
        }

        side = wanted;
        trigger = wanted > 0 ? up : down;
    }

    /** §10: arms, continues and confirms the pullback, in normalised prices. */
    private void pullback(int bar) {
        double close = side * candles.closeAt(bar);
        double high = side * (side > 0 ? candles.highAt(bar) : candles.lowAt(bar));
        double low = side * (side > 0 ? candles.lowAt(bar) : candles.highAt(bar));
        double advance = high - side * entry;

        if (!Double.isNaN(previousClose) && advance >= ARM_R * risk && close < previousClose) {
            armed = true;
            pullbackExtreme = Double.isNaN(pullbackExtreme)
                    ? low : Math.min(pullbackExtreme, low);
        }

        // THE EXTREME OF THE BAR THAT JUST CLOSED, not the one before it.
        // §10.2 confirms at candle t against the high of t-1, and t-1 is the
        // candle this call is looking at -- the order is being placed for t.
        // Resting it a bar further back was one pullback leg too deep, and the
        // confirmation fired on a level the market had already left behind.
        //
        // The level being exceeded IS the confirmation: written as an order
        // rather than as a test, so it fires when it happens and not at a close.
        adding = armed && held() < cap ? side * high : Double.NaN;
        addStop = side * pullbackExtreme;

        previousClose = close;
    }

    /** Rebuilds the book of orders, which in NTSL is what standing still means. */
    private void emit(Market market, Desk desk) {
        if (!entered) {
            if (!Double.isNaN(trigger)) {
                if (side > 0) {
                    desk.buyStop(trigger, trigger, lot);
                } else {
                    desk.sellShortStop(trigger, trigger, lot);
                }
            }

            return;
        }

        if (book.isEmpty()) {
            if (market.hasPosition()) {
                // The book says flat and the engine says otherwise: close it and
                // say so, rather than leaving contracts nobody is managing.
                desk.closePosition();
            }

            return;
        }

        for (Lot lot : book) {
            if (side > 0) {
                desk.sellToCoverStop(lot.stop, lot.stop, lot.quantity);
            } else {
                desk.buyToCoverStop(lot.stop, lot.stop, lot.quantity);
            }

            int half = lot.quantity / 2;

            if (!lot.shed && half >= 1) {
                double price = lot.entry + side * PARTIAL_R * risk;

                if (side > 0) {
                    desk.sellToCoverLimit(price, half);
                } else {
                    desk.buyToCoverLimit(price, half);
                }
            }
        }

        if (side > 0) {
            desk.sellToCoverLimit(target, held());
        } else {
            desk.buyToCoverLimit(target, held());
        }

        if (!Double.isNaN(adding)) {
            int room = Math.min(lot, cap - held());

            if (room > 0) {
                if (side > 0) {
                    desk.buyStop(adding, adding, room);
                } else {
                    desk.sellShortStop(adding, adding, room);
                }
            }
        }
    }

    /**
     * Brings the book in line with what actually executed.
     *
     * <p>A fill is matched to the order that produced it by its <b>verb and its
     * price</b>, which is a matching and not a guess: every level in the book is
     * emitted once. Two lots that happen to share a stop price are the one case
     * it cannot separate, and there it does not matter — they are the same
     * contracts at the same price, and closing either is closing either half of
     * an identical pair.
     */
    private void settle(List<Fill> fills) {
        for (Fill fill : fills) {
            String verb = fill.verb();

            if (verb == null) {
                continue;
            }

            if (verb.contains("Cover")) {
                covered(fill, verb.endsWith("Stop"));
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                opened(fill);
            } else {
                // ClosePosition and the like take the whole lot book with them.
                book.clear();
            }
        }
    }

    private void opened(Fill fill) {
        if (!entered) {
            double stop = stopOfTheDay();

            entered = true;
            entry = fill.price();
            risk = Math.abs(entry - stop);
            target = entry + side * authorised[today] * risk;
            trigger = Double.NaN;

            book.add(new Lot(fill.quantity(), entry, stop));

            return;
        }

        book.add(new Lot(fill.quantity(), fill.price(), addStop));

        armed = false;
        pullbackExtreme = Double.NaN;
        adding = Double.NaN;
    }

    private double stopOfTheDay() {
        OpeningRange.Session session = sessions.get(today);

        return side > 0 ? session.low() : session.high();
    }

    /**
     * Cuts the book down to what the engine really holds.
     *
     * <p>Newest lot first, because their stops are the nearest to the market —
     * a pullback only arms after the trade has advanced, so every lot added is
     * protected closer than the one before it, and the one that goes first when
     * the price turns is the last one in.
     */
    private void trim(int held) {
        for (int at = book.size() - 1; at >= 0 && held() > held; at--) {
            Lot lot = book.get(at);
            int gone = Math.min(lot.quantity, held() - held);

            lot.quantity -= gone;

            if (lot.quantity <= 0) {
                book.remove(at);
            }
        }
    }

    private void covered(Fill fill, boolean stopped) {
        // THE GLOBAL TARGET IS NOT A CASE HERE. It used to be -- recognised by
        // its price -- and that was the bug: a limit order fills at the better of
        // its level and the open, so on a gap the target came out at a price the
        // book did not recognise, was booked as somebody's partial, and the day
        // carried on with a book full of contracts that had already left.
        //
        // The answer is not a better test for it. The target takes the whole
        // position, so trim() empties the book on the very same bar whatever
        // this method decided -- and a second guard for the same failure only
        // means neither of the two can ever be shown to work.
        Lot chosen = null;
        double nearest = Double.MAX_VALUE;

        for (Lot lot : book) {
            double level = stopped ? lot.stop : lot.entry + side * PARTIAL_R * risk;
            double away = Math.abs(level - fill.price());

            if (away < nearest) {
                nearest = away;
                chosen = lot;
            }
        }

        if (chosen == null) {
            return;
        }

        chosen.quantity -= fill.quantity();

        if (!stopped) {
            chosen.shed = true;
        }

        if (chosen.quantity <= 0) {
            book.remove(chosen);
        }
    }

    private int held() {
        int many = 0;

        for (Lot lot : book) {
            many += lot.quantity;
        }

        return many;
    }

    // --------------------------------------------------------- the selector

    /**
     * §6, once for the whole run: which target each session is authorised for.
     *
     * <p>The hypothetical results are the crude one-contract simulation of
     * {@link OpeningRange#hypothetical}, on purpose — a selector fed by the full
     * lot machinery would be ranking the machinery rather than the target.
     *
     * @return the target per session, or NaN where the session is not authorised
     */
    private double[] choose() {
        int many = sessions.size();
        double[] picked = new double[many];

        // WITH THE SELECTOR OFF, EVERY SESSION IS AUTHORISED, at the one
        // target that was set. Written as a full array rather than as a test
        // further down so that everything reading `authorised` -- the refusal
        // at the open, the target when a lot goes on, the curve on the chart
        // -- keeps reading exactly one thing. A second source for "which
        // target" is how the drawing and the fill start disagreeing.
        if (!selecting) {
            Arrays.fill(picked, fixedTarget);

            return picked;
        }

        double[][] hypothetical = new double[TARGETS.length][many];
        boolean[] eligible = new boolean[many];

        Arrays.fill(picked, Double.NaN);

        for (int day = 0; day < many; day++) {
            OpeningRange.Session session = sessions.get(day);

            eligible[day] = session.broke() && session.afterMinutes() >= 0
                    && session.afterMinutes() < SELECTOR_WINDOW;

            for (int which = 0; which < TARGETS.length; which++) {
                hypothetical[which][day] = eligible[day]
                        ? OpeningRange.hypothetical(candles, session, TARGETS[which],
                                COST_BRL, PER_POINT)
                        : Double.NaN;
            }
        }

        for (int day = RECENT_SESSIONS; day < many; day++) {
            LocalDate recentStart = sessions.get(day - RECENT_SESSIONS).day();
            LocalDate structuralStart = recentStart.minusMonths(STRUCTURAL_MONTHS);

            double best = Double.NEGATIVE_INFINITY;
            double bestRecent = Double.NEGATIVE_INFINITY;
            double chosen = Double.NaN;

            for (int which = 0; which < TARGETS.length; which++) {
                double structural = 0;
                int structuralCount = 0;
                double recent = 0;
                int recentCount = 0;

                for (int other = 0; other < day; other++) {
                    if (!eligible[other]) {
                        continue;
                    }

                    LocalDate when = sessions.get(other).day();

                    if (other >= day - RECENT_SESSIONS) {
                        recent += hypothetical[which][other];
                        recentCount++;
                    } else if (!when.isBefore(structuralStart) && when.isBefore(recentStart)) {
                        structural += hypothetical[which][other];
                        structuralCount++;
                    }
                }

                if (structuralCount < LEAST_STRUCTURAL || recentCount < LEAST_RECENT) {
                    continue;
                }

                double recentMean = recent / recentCount;
                double score = 0.5 * (structural / structuralCount) + 0.5 * recentMean;

                // The tie-break is the reference's: the higher recent mean, and
                // then 2R -- which is simply the later of the two in TARGETS.
                if (score > best || (score == best && recentMean >= bestRecent)) {
                    best = score;
                    bestRecent = recentMean;
                    chosen = TARGETS[which];
                }
            }

            // AUTHORISED ONLY WHEN THE SCORE IS POSITIVE. The better of two
            // losing targets is still a losing target, and taking it because it
            // lost less is how a selector turns into a coin.
            picked[day] = best > 0 ? chosen : Double.NaN;
        }

        return picked;
    }

    // -------------------------------------------------------------- the lines

    private void draw(int bar, int day) {
        OpeningRange.Session session = sessions.get(day);

        rangeHigh[bar] = session.high();
        rangeLow[bar] = session.low();

        if (!book.isEmpty()) {
            double worst = Double.NaN;

            for (Lot lot : book) {
                worst = Double.isNaN(worst) ? lot.stop
                        : (side > 0 ? Math.min(worst, lot.stop) : Math.max(worst, lot.stop));
            }

            stopLine[bar] = worst;
            targetLine[bar] = target;
        }
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("Range alta", rangeHigh);
        drawn.put("Range baixa", rangeLow);
        drawn.put("Stop", stopLine);
        drawn.put("Alvo", targetLine);

        return drawn;
    }

    @Override
    public String toString() {
        return "Range 90";
    }

    /** @return the index of the session on that date, or {@code -1} */
    int sessionOn(LocalDate day) {
        for (int at = 0; at < sessions.size(); at++) {
            if (sessions.get(at).day().equals(day)) {
                return at;
            }
        }

        return -1;
    }

    /** @return the target authorised for that session, or NaN when it was not */
    double targetFor(int day) {
        return authorised[day];
    }
}
