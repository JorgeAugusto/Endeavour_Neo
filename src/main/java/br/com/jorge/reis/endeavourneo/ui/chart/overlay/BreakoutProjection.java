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
package br.com.jorge.reis.endeavourneo.ui.chart.overlay;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.List;

/**
 * The Fibonacci targets of a breakout, ported from the NTSL
 * {@code JorgeReis_Fibo_Projecao_Rompimento}.
 *
 * <h2>The rule, as the reference writes it</h2>
 *
 * <pre>
 *   A = the most recent bottom  -&gt; the STOP
 *   B = the top being broken    -&gt; the BASE of the ladder
 *   C = the bottom before B
 *
 *   arms when   close + proximity &gt; B      (NOT when it breaks: before)
 *               and the newest turn is a bottom
 *
 *   leg = the LARGER of |A-B| and |B-C|, if it clears the minimum
 *   targets = B + leg * 25/50/75/100/161/200/261%
 * </pre>
 *
 * <p>Three things in there are easy to get wrong and were checked against the
 * source line by line. It arms on <b>proximity</b>, fifty points before the
 * break, not on the break. The leg is the <b>larger</b> of the last two, so what
 * gets projected is sometimes the pullback and not the impulse -- on one real
 * session that was half the setups. And the ladder carries <b>161% and 261%</b>
 * and has no 150%.</p>
 *
 * <h2>It remembers, and that is new here</h2>
 *
 * <p>Every other indicator in this chart is a pure function of the series: open
 * the chart and it computes from nothing. This one has state -- whether a side
 * is armed, which top has already paid its 200% and may not arm again, and the
 * fact that the long and the short live side by side, each leaving only on its
 * own stop or its own 200%.</p>
 *
 * <p><b>The state is rebuilt by replaying the series, not carried between
 * frames.</b> In the reference product the indicator runs tick by tick and what
 * it draws depends on when it was attached to the chart -- open the same chart
 * tomorrow and it can draw something else. Here {@link #calculate} walks the
 * bars from the start of its window and applies the rule to each one, so the
 * drawing is a function of the data alone. Same chart, same picture, and the
 * replay and the backtest see exactly what the screen sees.</p>
 */
public final class BreakoutProjection implements Overlay {

    /** The ladder, in percent of the leg. */
    static final int[] TARGETS = {25, 50, 75, 100, 161, 200, 261};

    /** Where the hundred sits in {@link #TARGETS}. */
    private static final int HUNDRED = 3;

    /** And the two hundred, which is what ends a projection. */
    private static final int TWO_HUNDRED = 5;

    /** The zigzag's wing, as the reference opens. */
    public static final int WING = 2;

    /** How close the price has to come, in points, for the side to arm. */
    public static final int PROXIMITY = 50;

    /** The smallest leg worth projecting, in points. */
    public static final int LEAST_LEG = 45;

    /** How far past the base the entry line sits. */
    public static final int ENTRY = 5;

    /**
     * How far back the replay starts, in bars.
     *
     * <p>Ten hours of one-minute bars. Everything before that is out of the
     * state's reach: a top burned by a 200% eleven hours ago can arm again. The
     * alternative is replaying a hundred thousand bars on every recalculation
     * for a memory nobody is reading any more.</p>
     */
    private static final int LOOKBACK = 600;

    private static final Color RISING_INK = new Color(0x1F, 0xB6, 0xC1);

    private static final Color FALLING_INK = new Color(0xD9, 0x46, 0xEF);

    private static final Color STOP_INK = new Color(0xC6, 0x28, 0x28);

    private int wing;

    private TopsAndBottoms.Ties ties = TopsAndBottoms.Ties.LAST;

    private int proximity = PROXIMITY;

    private int leastLeg = LEAST_LEG;

    private int entry = ENTRY;

    private boolean show25;

    private boolean show75;

    private boolean show200 = true;

    private boolean show261 = true;

    private boolean showEntry = true;

    private boolean showStop = true;

    private Color risingColour;

    private Color fallingColour;

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private int thickness = 1;

    private boolean visible = true;

    private volatile Shot rising;

    private volatile Shot falling;

    /**
     * A projection as it ends up on screen.
     *
     * @param at the bar of B, where the ladder is measured from
     * @param base B's price
     * @param stop A's price
     * @param leg the size projected, always positive
     * @param targets one price per rung of {@link #TARGETS}
     */
    record Shot(int at, double base, double stop, double leg, double[] targets) {

        double entryAt(int points, boolean up) {
            return base + (up ? points : -points);
        }
    }

    /** The mutable half, alive only inside the replay. */
    private static final class Side {

        private boolean armed;

        private int at = -1;

        private double base;

        private double stop;

        private double leg;

        private double[] targets;

        /** The base that already paid its 200% and may not arm again. */
        private double burned = Double.NaN;

        private void clear() {
            armed = false;
            at = -1;
            base = 0;
            stop = 0;
            leg = 0;
            targets = null;
        }

        private Shot shot() {
            return armed && targets != null
                    ? new Shot(at, base, stop, leg, targets.clone()) : null;
        }
    }

    public BreakoutProjection(int wing) {
        setWing(wing);
    }

    /** @param settings the wing -- the catalogue's factory */
    public BreakoutProjection(int... settings) {
        this(settings.length > 0 ? settings[0] : WING);
    }

    public int wing() {
        return wing;
    }

    public void setWing(int value) {
        this.wing = Math.max(1, Math.min(value, TopsAndBottoms.MOST_WING));
    }

    public TopsAndBottoms.Ties ties() {
        return ties;
    }

    public void setTies(TopsAndBottoms.Ties value) {
        this.ties = value == null ? TopsAndBottoms.Ties.LAST : value;
    }

    public int proximity() {
        return proximity;
    }

    /** @param value points before the break; zero waits for the break itself */
    public void setProximity(int value) {
        this.proximity = Math.max(0, Math.min(value, 10_000));
    }

    public int leastLeg() {
        return leastLeg;
    }

    public void setLeastLeg(int value) {
        this.leastLeg = Math.max(0, Math.min(value, 10_000));
    }

    public int entry() {
        return entry;
    }

    public void setEntry(int value) {
        this.entry = Math.max(0, Math.min(value, 10_000));
    }

    public boolean shows25() {
        return show25;
    }

    public void setShows25(boolean value) {
        this.show25 = value;
    }

    public boolean shows75() {
        return show75;
    }

    public void setShows75(boolean value) {
        this.show75 = value;
    }

    public boolean shows200() {
        return show200;
    }

    public void setShows200(boolean value) {
        this.show200 = value;
    }

    public boolean shows261() {
        return show261;
    }

    public void setShows261(boolean value) {
        this.show261 = value;
    }

    public boolean showsEntry() {
        return showEntry;
    }

    public void setShowsEntry(boolean value) {
        this.showEntry = value;
    }

    public boolean showsStop() {
        return showStop;
    }

    public void setShowsStop(boolean value) {
        this.showStop = value;
    }

    public Color chosenRisingColour() {
        return risingColour;
    }

    public void setRisingColour(Color value) {
        this.risingColour = value;
    }

    public Color chosenFallingColour() {
        return fallingColour;
    }

    public void setFallingColour(Color value) {
        this.fallingColour = value;
    }

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        this.line = value == null ? MovingAverage.Line.SOLID : value;
    }

    public int thickness() {
        return thickness;
    }

    public void setThickness(int value) {
        this.thickness = Math.max(1, Math.min(value, 8));
    }

    // ------------------------------------------------------------ the numbers

    /** @return the long projection on screen, or null */
    public Shot rising() {
        return rising;
    }

    /** @return the short one, or null */
    public Shot falling() {
        return falling;
    }

    // ------------------------------------------------------------ the working

    /**
     * Replays the rule over the bars and keeps what it ends on.
     *
     * @param series the bars
     * @param pivots the zigzag, in time order
     * @param from the first bar of the replay
     *
     * <p>Package-private so a test can drive the whole state machine -- arm,
     * hold, stop, pay, burn -- over a series it wrote itself, and read the
     * answer without a chart.</p>
     */
    void replay(PriceSeries series, List<TopsAndBottoms.Pivot> pivots, int from) {
        Side up = new Side();
        Side down = new Side();
        int known = 0;

        for (int bar = Math.max(0, from); bar < series.size(); bar++) {
            // THE PIVOT HORIZON, and it is the reference product's "Periodo+1":
            // a fractal is only settled `wing` bars after its own bar, so at bar
            // i the replay may only see pivots that had closed by then. Without
            // it the replay would arm on a top the market had not made yet, and
            // every backtest built on it would be worth nothing.
            while (known < pivots.size() && pivots.get(known).bar() + wing <= bar) {
                known++;
            }

            List<TopsAndBottoms.Pivot> seen = pivots.subList(0, known);
            double close = series.closeAt(bar);

            step(up, true, seen, close, bar);
            step(down, false, seen, close, bar);
        }

        this.rising = up.shot();
        this.falling = down.shot();
    }

    /**
     * One bar, one side.
     *
     * <p>The order is the reference's and it matters: arm first, then the exits
     * -- which read the stop and the 200% MEMORISED on an earlier bar, not the
     * ones about to be computed -- and only then recompute the ladder from the
     * turns as they stand now.</p>
     */
    private void step(Side side, boolean up, List<TopsAndBottoms.Pivot> pivots,
            double close, int bar) {

        TopsAndBottoms.Pivot broken = newest(pivots, up);
        TopsAndBottoms.Pivot pullback = newest(pivots, !up);

        boolean shaped = broken != null && pullback != null
                && pullback.bar() > broken.bar();

        if (shaped && !burned(side, broken.price())
                && (up ? close + proximity > broken.price()
                        : close - proximity < broken.price())) {
            side.armed = true;
        }

        if (side.armed && side.targets != null) {
            // THE REFERENCE GUARDS THIS with "and the bar is past the leg's
            // start", so that the bars of the leg itself -- which were below
            // the stop, that being what made it a stop -- do not cancel the
            // projection the moment it is drawn. That guard is NOT copied,
            // because here it cannot fire: a pivot is only known `wing` bars
            // after its own bar, so the replay never reaches the exit test at a
            // bar at or before the base. Written down rather than kept, because
            // a guard that cannot fire is a guard no test can defend, and the
            // next reader would restore it for the same good reason.
            if (up ? close < side.stop : close > side.stop) {
                side.clear();
            } else if (up ? close >= side.targets[TWO_HUNDRED]
                    : close <= side.targets[TWO_HUNDRED]) {
                side.burned = side.base;

                side.clear();
            }
        }

        if (!side.armed || !shaped) {
            return;
        }

        TopsAndBottoms.Pivot older = before(pivots, broken.bar(), !up);

        if (older == null) {
            return;
        }

        double leg = Math.max(Math.abs(pullback.price() - broken.price()),
                Math.abs(broken.price() - older.price()));

        if (leg <= leastLeg) {
            return;
        }

        side.at = broken.bar();
        side.base = broken.price();
        side.stop = pullback.price();
        side.leg = leg;
        side.targets = new double[TARGETS.length];

        for (int i = 0; i < TARGETS.length; i++) {
            side.targets[i] = side.base + (up ? 1 : -1) * leg * TARGETS[i] / 100.0;
        }
    }

    private static boolean burned(Side side, double price) {
        return !Double.isNaN(side.burned) && Math.abs(side.burned - price) < 1e-9;
    }

    private static TopsAndBottoms.Pivot newest(List<TopsAndBottoms.Pivot> pivots, boolean top) {
        TopsAndBottoms.Pivot found = null;

        for (TopsAndBottoms.Pivot each : pivots) {
            if (each.top() == top) {
                found = each;
            }
        }

        return found;
    }

    private static TopsAndBottoms.Pivot before(List<TopsAndBottoms.Pivot> pivots,
            int bar, boolean top) {

        TopsAndBottoms.Pivot found = null;

        for (TopsAndBottoms.Pivot each : pivots) {
            if (each.top() == top && each.bar() < bar) {
                found = each;
            }
        }

        return found;
    }

    // ------------------------------------------------------------ the contract

    @Override
    public String nameKey() {
        return "overlay.breakout";
    }

    @Override
    public List<Integer> parameters() {
        return List.of(wing);
    }

    @Override
    public boolean fitsOnPrice() {
        // Prices of the index, drawn as horizontal lines.
        return true;
    }

    /**
     * @return two colours, for the two sides
     *
     * <p>Only the 100% of each side comes through {@link #valueAt}; the rest of
     * the ladder is drawn in {@link #paintUnder}. Eighteen numbers in a legend
     * row is a legend nobody reads, and the 100% is the one a reader wants
     * beside the name.</p>
     */
    @Override
    public List<Color> colours() {
        return List.of(risingColour == null ? RISING_INK : risingColour,
                fallingColour == null ? FALLING_INK : fallingColour);
    }

    @Override
    public Stroke stroke() {
        return line.stroke(thickness + 1f);
    }

    @Override
    public List<Stroke> strokes() {
        return List.of(stroke(), stroke());
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean value) {
        this.visible = value;
    }

    @Override
    public void calculate(PriceSeries series) {
        PriceSeries source = series == null ? PriceSeries.empty() : series;

        this.rising = null;
        this.falling = null;

        if (source.size() == 0) {
            return;
        }

        int from = Math.max(0, source.size() - LOOKBACK);

        replay(source, TopsAndBottoms.alternating(TopsAndBottoms.candidates(
                source, wing, ties, from, source.size())), from);
    }

    /**
     * @param bar an index into the series
     * @return the two 100% targets, NaN before the base of each
     */
    @Override
    public double[] valueAt(int bar) {
        return new double[]{at(rising, bar), at(falling, bar)};
    }

    private static double at(Shot shot, int bar) {
        return shot == null || bar < shot.at() ? Double.NaN : shot.targets()[HUNDRED];
    }

    /**
     * Draws the stop, the entry and the rest of the ladder.
     *
     * <p>Each line starts at the bar of B and runs to the right edge: before
     * that bar the base did not exist, and a level drawn back over bars that
     * could not have known it reads as one the market already respected.</p>
     */
    @Override
    public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
        paintSide(g, viewport, rising, true);
        paintSide(g, viewport, falling, false);
    }

    private void paintSide(Graphics2D g, Viewport viewport, Shot shot, boolean up) {
        if (shot == null) {
            return;
        }

        Color ink = colours().get(up ? 0 : 1);
        int left = (int) Math.round(viewport.x(shot.at()));
        int right = viewport.bounds().x + viewport.bounds().width;

        for (int i = 0; i < TARGETS.length; i++) {
            if (i == HUNDRED || !wanted(TARGETS[i])) {
                // The hundred is drawn by the chart itself, from valueAt.
                continue;
            }

            g.setColor(ink);
            g.setStroke(faint(TARGETS[i]) ? MovingAverage.Line.DOTTED.stroke(thickness)
                    : line.stroke(thickness));

            rule(g, viewport, left, right, shot.targets()[i], TARGETS[i] + "%", ink);
        }

        if (showEntry) {
            g.setColor(ink);
            g.setStroke(MovingAverage.Line.DASH_DOT.stroke(thickness));

            rule(g, viewport, left, right, shot.entryAt(entry, up), "entrada", ink);
        }

        if (showStop) {
            g.setColor(STOP_INK);
            g.setStroke(MovingAverage.Line.DASHED.stroke(thickness + 1f));

            rule(g, viewport, left, right, shot.stop(), "stop", STOP_INK);
        }
    }

    /** @return whether that rung is switched on */
    private boolean wanted(int percent) {
        return switch (percent) {
            case 25 -> show25;
            case 75 -> show75;
            case 200 -> show200;
            case 261 -> show261;
            default -> true;
        };
    }

    /** @return whether that rung is drawn as a hint rather than a level */
    private static boolean faint(int percent) {
        return percent == 25 || percent == 75;
    }

    private static void rule(Graphics2D g, Viewport viewport, int left, int right,
            double price, String label, Color ink) {

        int y = (int) Math.round(viewport.y(price));

        g.drawLine(left, y, right, y);

        // THE LABEL IS THE POINT of drawing these here rather than as eight more
        // lines through valueAt: eight parallel lines with no numbers on them
        // are eight lines nobody can name.
        g.setColor(ink);

        FontMetrics metrics = g.getFontMetrics();

        g.drawString(label, right - metrics.stringWidth(label) - 6, y - 3);
    }

    @Override
    public String appearance() {
        return ties + ";" + proximity + ";" + leastLeg + ";" + entry
                + ";" + line + ";" + thickness
                + ";" + hex(risingColour) + ";" + hex(fallingColour)
                + ";" + show25 + ";" + show75 + ";" + show200 + ";" + show261
                + ";" + showEntry + ";" + showStop;
    }

    private static String hex(Color colour) {
        return colour == null ? "auto" : Integer.toHexString(colour.getRGB() & 0xFFFFFF);
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] parts = text.split(";", -1);

        if (parts.length > 3) {
            setTies(TopsAndBottoms.Ties.STRICT.name().equals(parts[0])
                    ? TopsAndBottoms.Ties.STRICT : TopsAndBottoms.Ties.LAST);
            setProximity(number(parts[1], PROXIMITY));
            setLeastLeg(number(parts[2], LEAST_LEG));
            setEntry(number(parts[3], ENTRY));
        }

        if (parts.length > 5) {
            setLine(readLine(parts[4]));
            setThickness(number(parts[5], 1));
        }

        if (parts.length > 7) {
            setRisingColour(readColour(parts[6]));
            setFallingColour(readColour(parts[7]));
        }

        if (parts.length > 13) {
            setShows25(Boolean.parseBoolean(parts[8]));
            setShows75(Boolean.parseBoolean(parts[9]));
            setShows200(Boolean.parseBoolean(parts[10]));
            setShows261(Boolean.parseBoolean(parts[11]));
            setShowsEntry(Boolean.parseBoolean(parts[12]));
            setShowsStop(Boolean.parseBoolean(parts[13]));
        }
    }

    private static MovingAverage.Line readLine(String text) {
        for (MovingAverage.Line each : MovingAverage.Line.values()) {
            if (each.name().equals(text)) {
                return each;
            }
        }

        return MovingAverage.Line.SOLID;
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            // A hand-edited line beats losing the rest of the appearance.
            return fallback;
        }
    }

    private static Color readColour(String text) {
        if (text == null || "auto".equals(text)) {
            return null;
        }

        try {
            return new Color(Integer.parseInt(text, 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
