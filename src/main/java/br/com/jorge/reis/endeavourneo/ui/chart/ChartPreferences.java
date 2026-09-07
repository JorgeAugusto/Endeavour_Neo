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
package br.com.jorge.reis.endeavourneo.ui.chart;

import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What every chart draws, decided once for all of them.
 *
 * <p>Settings that belong to the drawing rather than to one window. A chart that
 * showed the period line while the chart beside it did not would make two
 * pictures of the same market that cannot be compared, which is the whole reason
 * these are not per window.</p>
 *
 * <p>Same shape as {@link RulerMode}: a value, a preference behind it, and
 * listeners that the charts add and drop with their own Swing lifecycle.</p>
 */
public final class ChartPreferences {

    private static final Settings PREFS = Settings.settings();

    private static final String PERIOD_LINE = "periodLine";

    private static final String VERTICAL_GRID = "verticalGrid";

    private static final String HORIZONTAL_GRID = "horizontalGrid";

    private static final String SYNTHETIC = "syntheticTicks";

    private static final String HOLLOW = "hollowCandles";

    /**
     * Copy-on-write because charts add and drop listeners as windows open and
     * close, on the same thread that is walking the list to tell them.
     */
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private static boolean periodLine = PREFS.getBoolean(PERIOD_LINE, false);

    private static boolean verticalGrid = PREFS.getBoolean(VERTICAL_GRID, true);

    private static boolean horizontalGrid = PREFS.getBoolean(HORIZONTAL_GRID, true);

    /**
     * Whether a bar with no recorded ticks may be animated with invented ones.
     *
     * <p>On by default, and that is a deliberate choice rather than a shrug:
     * one month of the base has real ticks and eight years do not, so refusing
     * to draw a path would leave almost every replay jumping bar to bar.</p>
     *
     * <p><b>Turning it off does more than stop the animation.</b> Renko built
     * from candles is not the same renko: measured over January 2021 on WINFUT,
     * brick 55 gives 9.718 bricks from one-minute candles and 8.076 from the
     * exchange's own ticks. The candles lay 27% MORE, because reading a bar as
     * "the high then the low" manufactures a swing the real path did not make.
     * So with this off, renko is offered only where there are ticks to build it
     * from.</p>
     */
    private static boolean syntheticTicks = PREFS.getBoolean(SYNTHETIC, true);

    private static boolean hollowCandles = PREFS.getBoolean(HOLLOW, true);

    private ChartPreferences() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @return whether to draw the vertical line where one period gives way to
     *         the next — a month on a monthly chart, a session on an intraday one
     *
     * <p><b>Off unless asked.</b> On a chart of a few hundred bars the boundary
     * falls often enough to become a second grid, and a grid drawn on top of the
     * grid is noise however faint it is. The day band underneath already says
     * where the day changed, and it says it with a name rather than with a
     * line.</p>
     */
    public static boolean periodLine() {
        return periodLine;
    }

    public static void setPeriodLine(boolean show) {
        if (periodLine == show) {
            return;
        }

        periodLine = show;

        PREFS.putBoolean(PERIOD_LINE, show);
        announce();
    }

    private static void announce() {
        for (Runnable listener : LISTENERS) {
            listener.run();
        }
    }

    /**
     * @return whether the price lines are drawn across the chart
     *
     * <p>On by default, and both are: a chart with no grid at all is a picture
     * rather than a measurement, and reading a level off it means running a
     * finger across to the axis. Off is for a screenshot.</p>
     */
    public static boolean horizontalGrid() {
        return horizontalGrid;
    }

    public static void setHorizontalGrid(boolean show) {
        if (horizontalGrid != show) {
            horizontalGrid = show;

            PREFS.putBoolean(HORIZONTAL_GRID, show);
            announce();
        }
    }

    /**
     * @return whether a rising candle is drawn as an outline
     *
     * <p>A setting and not a drawing style of its own. Hollow-or-filled is how
     * the SAME chart is drawn; putting it beside "candles" and "line" in a list
     * made it look like a third kind of chart, and the reader had to know that
     * two of the three were the same thing.</p>
     */
    public static boolean syntheticTicks() {
        return syntheticTicks;
    }

    public static void setSyntheticTicks(boolean allow) {
        if (syntheticTicks == allow) {
            return;
        }

        syntheticTicks = allow;

        PREFS.putBoolean(SYNTHETIC, allow);
        announce();
    }

    private static final String WINDOW = "chart.window";

    /** The smallest window worth offering; below this a chart shows less than a month. */
    public static final int LEAST_WINDOW = 1_000;

    /**
     * The largest.
     *
     * <p>Five hundred thousand one-minute bars is around a thousand trading
     * sessions -- four years -- and 24 MB. Past that the reader is no longer
     * looking at a chart, they are measuring over the whole base, and that is
     * what a measurement loads the file for.</p>
     */
    public static final int MOST_WINDOW = 500_000;

    /**
     * A hundred thousand: the reference product's top licence.
     *
     * <p>About two hundred trading sessions of one-minute bars, and 4,8 MB.</p>
     */
    public static final int WINDOW_DEFAULT = 100_000;

    /**
     * @return how many of the most recent bars a chart loads
     *
     * <p><b>A window, not the file.</b> Six years of one-minute bars is 39 MB
     * read to draw a screen that shows a month of them, and every terminal
     * worth the name loads a window instead: the reference product does 10.000
     * by default and 100.000 at its top licence, MetaTrader 4 stops the chart at
     * 65.000 while keeping 512.000 on disk, NinjaTrader loads five days of
     * minutes. A hundred thousand one-minute bars is around two hundred trading
     * sessions and 4,8 MB.</p>
     *
     * <p>Zero or less means the whole file, which is what a measurement over the
     * entire base wants and what the reader can still ask for.</p>
     */
    public static int window() {
        int kept = PREFS.getInt(WINDOW, WINDOW_DEFAULT);

        // Clamped on the way OUT as well as on the way in: a hand-edited file,
        // or one written by a version with other limits, must not make a chart
        // read four hundred megabytes because a digit was typed twice.
        return Math.max(LEAST_WINDOW, Math.min(kept, MOST_WINDOW));
    }

    public static void setWindow(int bars) {
        int wanted = Math.max(LEAST_WINDOW, Math.min(bars, MOST_WINDOW));

        if (window() == wanted) {
            return;
        }

        PREFS.putInt(WINDOW, wanted);
        announce();
    }

    public static boolean hollowCandles() {
        return hollowCandles;
    }

    public static void setHollowCandles(boolean hollow) {
        if (hollowCandles != hollow) {
            hollowCandles = hollow;

            PREFS.putBoolean(HOLLOW, hollow);
            announce();
        }
    }

    /** @return whether the time lines are drawn down the chart */
    public static boolean verticalGrid() {
        return verticalGrid;
    }

    public static void setVerticalGrid(boolean show) {
        if (verticalGrid != show) {
            verticalGrid = show;

            PREFS.putBoolean(VERTICAL_GRID, show);
            announce();
        }
    }

    /**
     * @param listener told whenever a drawing setting changes
     *
     * <p>Must be paired with {@link #forget}: a chart that is closed and leaves
     * its listener here keeps the whole window alive, and every later change
     * repaints something nobody can see.</p>
     */
    public static void listen(Runnable listener) {
        if (listener != null) {
            LISTENERS.add(listener);
        }
    }

    public static void forget(Runnable listener) {
        LISTENERS.remove(listener);
    }

    /** @return how many are listening — for the test that this does not leak */
    static int listenerCount() {
        return LISTENERS.size();
    }
}
