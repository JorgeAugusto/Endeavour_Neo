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

import br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic;
import br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.StochasticDialog;

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import br.com.jorge.reis.endeavourneo.ui.chart.style.CandleStyle;
import br.com.jorge.reis.endeavourneo.ui.chart.style.LineStyle;

import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import javax.swing.JComponent;

/**
 * The price chart.
 *
 * <p>It paints in layers, and the order is the design:</p>
 *
 * <pre>
 *   background   the theme's surface
 *   grid         horizontal price lines, recessive
 *   style        the price itself -- see {@link ChartStyle}
 *   overlays     the indicators drawn on the price
 *   axes         prices on the right, times along the bottom
 *   lastPrice    the tag on the price axis
 *   crosshair    where the mouse is
 *   jumpButton   back to the newest bars
 *   ruler        a measurement being made
 *   readout      the bar under the pointer
 * </pre>
 *
 * <p>Ten and not five. The list stopped at the crosshair while
 * {@code paintComponent} had grown five more, and the four that were missing
 * are precisely the ones that overlap each other -- which is what the sentence
 * below means by "the order is the design".</p>
 *
 * <p><b>Each layer knows nothing of the others.</b> That is what the previous
 * project's canvas lacked: one {@code paintComponent} of 1.576 lines drawing
 * everything, where adding a style meant editing the method that also draws the
 * axis. Here a new style is a new class and this file does not change.</p>
 *
 * <p>The viewport is rebuilt on every paint rather than cached. It costs one
 * pass over the visible bars — a few hundred — and removes the entire class of
 * bug where the component resized and the scale did not.</p>
 */
public final class ChartCanvas extends JComponent {

    private static final long serialVersionUID = 1L;

    /** How many bars fill the screen before anyone zooms. */
    private static final int DEFAULT_VISIBLE_BARS = 120;

    private static final int MINIMUM_VISIBLE_BARS = 10;

    /** Roughly how many pixels apart the horizontal grid lines should sit. */
    private static final int GRID_SPACING = 56;

    /**
     * How far past the last bar the window may go, as a share of its width.
     *
     * <p>Three quarters. Half was not enough to feel like control: with a bar
     * forming at the right edge there was nowhere comfortable to put it.</p>
     */
    private static final double AIR_RIGHT = 0.75;

    /** How far the price window may slide, up or down, as a share of its span. */
    private static final double AIR_VERTICAL = 0.5;

    /**
     * Pixels of vertical wobble a sideways drag is allowed before the price
     * slides. Six is about the wander of a hand meaning to move only sideways.
     */
    private static final int VERTICAL_SLACK = 6;

    /**
     * Space kept to the right of the newest bar when a chart opens.
     *
     * <p>Ten per cent, and not zero: a chart whose last candle touches the frame
     * looks cut off, and the very first thing anybody does is drag it left.</p>
     */
    private static final double BIRTH_MARGIN = 0.10;

    /** Flatter than this and the candles are a line; taller and they leave the screen. */
    private static final double MINIMUM_STRETCH = 0.1;

    private static final double MAXIMUM_STRETCH = 20.0;

    /**
     * Width of the price strip on the right, in pixels.
     *
     * <p>Reserved rather than overlaid: the plot stops before it, so no candle
     * is ever drawn underneath. An axis painted on top of the data hides the
     * most recent bars, which are the ones being watched.</p>
     */
    private static final int AXIS_WIDTH = 62;

    /** Height of the hours strip along the bottom, in pixels. */
    private static final int TIME_HEIGHT = 20;

    /**
     * Height of the day band below the hours.
     *
     * <p>A band of its own rather than a date substituted into the hour labels.
     * Substituting costs an hour label and makes the reader notice the swap; a
     * second strip states the day continuously and never competes with the
     * time.</p>
     */
    private static final int DAY_HEIGHT = 17;

    /**
     * The time steps a label may fall on, in minutes.
     *
     * <p>Round intervals only, and the same reasoning as the 1-2-5 price grid:
     * a label at 14:00 and the next at 14:37 is arithmetic showing through. The
     * list ends at a week; beyond that the chart is showing years and the day
     * boundaries carry the information anyway.</p>
     */
    private static final int[] TIME_STEPS = {
            1, 2, 5, 10, 15, 30, 60, 120, 180, 240, 360, 720,
            1_440, 2_880, 10_080};

    /** Roughly how many pixels apart the time labels should sit. */
    private static final int TIME_SPACING = 90;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern(br.com.jorge.reis.endeavourneo.platform.Formats.DAY);

    /** The top row on a chart whose bars are days or longer. */
    private static final DateTimeFormatter DAY_OF_MONTH = DateTimeFormatter.ofPattern("dd");

    /** The band below it, then. */
    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("MMM/yy", java.util.Locale.getDefault());

    /**
     * The cursor's time tag carries the DATE as well as the clock.
     *
     * <p>The axis underneath is already showing hours; repeating just the hour
     * would say nothing the reader could not see. The date is the part the axis
     * only shows once per day, in a band the cursor is nowhere near.</p>
     */
    private static final DateTimeFormatter CURSOR_TIME =
            DateTimeFormatter.ofPattern(br.com.jorge.reis.endeavourneo.platform.Formats.DATE_TIME);

    /**
     * The footer reading, which has less room than the cursor tag.
     *
     * <p>Beside the other four rather than inline where it is used. It was the
     * fifth date pattern in this file and the only one written at the point of
     * use, which is how a set of formats drifts apart one edit at a time.</p>
     */
    private static final DateTimeFormatter FOOTER_TIME =
            DateTimeFormatter.ofPattern("dd/MM HH:mm");

    /**
     * The dotted line between one period and the next.
     *
     * <p>Short dashes with an equal gap: long dashes read as a drawing the
     * reader made, and a one-pixel dot disappears against a candle.</p>
     */
    private static final java.awt.Stroke BOUNDARY = new java.awt.BasicStroke(
            1.0f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
            1.0f, new float[]{2.0f, 3.0f}, 0.0f);

    /** Diameter of the button that jumps back to the newest bars. */
    private static final int JUMP_SIZE = 26;

    /** The size everything written on the axes and the tags is drawn at. */
    private static final float AXIS_POINTS = 11f;

    /**
     * The face the axes are written in, derived once from the component's own.
     *
     * <p>{@code Font.deriveFont} was called four times per frame -- three plain
     * and one bold -- and a repaint happens on every movement of the mouse. It
     * is not free: each call builds a font and, on the first use, has the
     * platform measure it.</p>
     *
     * <p>Kept against the font it was derived FROM, so a theme change is picked
     * up: {@code setFont} is what a look-and-feel update calls, and comparing
     * the two is how this notices without having to be told.</p>
     */
    private transient java.awt.Font axisFrom;

    private transient java.awt.Font axisPlain;

    private transient java.awt.Font axisBold;

    private java.awt.Font axisFont() {
        deriveAxisFonts();

        return axisPlain;
    }

    private java.awt.Font axisFontBold() {
        deriveAxisFonts();

        return axisBold;
    }

    private void deriveAxisFonts() {
        java.awt.Font mine = getFont();

        if (mine != axisFrom || axisPlain == null) {
            axisFrom = mine;
            axisPlain = mine.deriveFont(AXIS_POINTS);
            axisBold = mine.deriveFont(java.awt.Font.BOLD, AXIS_POINTS);
        }
    }

    /**
     * The strokes the painting uses, built once.
     *
     * <p>Every one of these was a {@code new BasicStroke} inside a paint
     * method, and a repaint happens on every movement of the mouse. None of
     * them varies with anything: the width is a literal at the call site. The
     * constant beside them, {@link #BOUNDARY}, shows the class already knew
     * how to do this.</p>
     */
    private static final java.awt.Stroke THIN = new java.awt.BasicStroke(1.0f);

    private static final java.awt.Stroke ROUNDED = new java.awt.BasicStroke(
            1.2f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);

    private static final java.awt.Stroke THICK_ROUNDED = new java.awt.BasicStroke(
            1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND);

    /** The dashes of the vertical line the ruler leaves behind. */
    private static final java.awt.Stroke DASHED = new java.awt.BasicStroke(
            1.0f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER,
            1.0f, new float[]{3.0f, 3.0f}, 0.0f);

    /**
     * How far it sits from the TOP-right corner of the plot.
     *
     * <p>It said bottom, and {@code jumpBounds} puts it at the top with a
     * comment saying so. One of the two had been wrong since the button
     * moved.</p>
     */
    private static final int JUMP_MARGIN = 14;

    /**
     * How much a pixel of horizontal drag changes the number of visible bars.
     *
     * <p>Smaller than the vertical one because the horizontal drag has the whole
     * width to work with, and because losing the place sideways is more
     * disorienting than losing the scale.</p>
     */
    private static final double TIME_DRAG_SENSITIVITY = 0.004;

    /** How far above the high and below the low still counts as touching the bar. */
    private static final int HIT_TOLERANCE = 3;

    /** What a drag on the plot does. */
    public enum Mode {

        /** Drag moves the chart. The default, and what most drags mean. */
        PAN,

        /** Drag measures between two points. */
        MEASURE
    }

    /**
     * How much a pixel of vertical drag changes the scale.
     *
     * <p>Multiplicative, so the feel is the same whether the factor is at 0,2 or
     * at 8 -- a fixed step per pixel would crawl at one end and jump at the
     * other.</p>
     */
    private static final double DRAG_SENSITIVITY = 0.006;

    private transient PriceSeries series = PriceSeries.empty();

    private transient ChartStyle style = new CandleStyle();

    /**
     * The overlays drawn on the price, in the order the reader arranged them.
     *
     * <p>The order is theirs and not the order of insertion: it is the order
     * the legend lists, and it is also the order they are DRAWN in, so the
     * last one wins where two lines cross. Moving a row in the legend is
     * therefore also deciding what sits on top.</p>
     */
    private final transient java.util.List<Overlay> overlays = new java.util.ArrayList<>();

    /**
     * The panes drawn from this chart's viewport.
     *
     * <p>Final and built at construction, because {@link #repaint} runs before
     * a subclass's fields would be assigned (this class is final now, so that
     * is no longer the reason; what remains is that Swing repaints during
     * construction, and a null list there is a crash on opening a chart.</p>
     */
    private final transient java.util.List<java.awt.Component> followers =
            new java.util.ArrayList<>();

    /**
     * Told when the overlays change in a way that should be WRITTEN DOWN.
     *
     * <p>Restored: a careless slice took this field out with the study
     * listener that sat beside it.</p>
     */
    private transient Runnable onOverlaysChanged = () -> { };

    /**
     * Opens the insert dialog and places what comes back.
     *
     * <p>The canvas cannot do it itself. A destination may be a panel under
     * the chart, and the canvas does not know what it is stacked with -- it
     * does not even know a stack exists. Whoever owns both answers this; see
     * {@code ChartHolder}.</p>
     *
     * <p>The fallback places on the price, which is all a canvas standing on
     * its own could offer anyway.</p>
     */
    private transient Runnable onInsertWanted = this::insertOnPriceOnly;

    /**
     * Told when the pointer moves over the bars, or leaves them.
     *
     * <p>The footer follows the chart the pointer is ON, not the one with
     * focus. A price under a cursor that is somewhere else is not a reading of
     * anything, and tracking focus instead would have the bar report a window
     * the reader is not looking at.</p>
     */
    private transient Runnable onCursorChanged = () -> { };

    /** Told when the overlays change in a way that has to be REDRAWN. */
    private transient Runnable onOverlaysRedrawn = () -> { };

    /** Told when the bars themselves are replaced. */
    private transient Runnable onSeriesChanged = () -> { };

    /** Told when the vertical scale stops or starts being the automatic one. */
    private transient Runnable onScaleChanged = () -> { };

    /**
     * The bars as they are STORED, before the period is applied.
     *
     * <p>Kept alongside the drawn series because changing the period has to fold
     * the original minutes again. Folding the already-folded ones would work for
     * 1m→5m→15m and be quietly wrong for anything else — renko over five-minute
     * bars is not renko over the trades that made them.</p>
     */
    private transient PriceSeries source = PriceSeries.empty();

    private transient Aggregation period = Timeframe.ONE_MINUTE;

    /**
     * How the chosen period is written in the title and inside the chart.
     *
     * <p>Kept beside the aggregation instead of asked of it: naming a renko
     * brick in ticks needs the instrument's tick size, and the domain has no
     * business knowing what a tick is worth.</p>
     */
    private transient String periodLabel = Timeframe.ONE_MINUTE.label();

    /**
     * The period as the reader would type it -- "5m", "11R".
     *
     * <p>Kept apart from the label because they answer different questions: the
     * label is for reading and the code is for rebuilding. Restoring a window
     * from "11R - 50 pts" would mean parsing prose.</p>
     */
    private transient String periodCode = Timeframe.ONE_MINUTE.label();

    /** What this chart shows, so its tick sessions can be found by name. */
    private transient String instrument;

    /**
     * Whether what is on screen was built from recorded ticks.
     *
     * <p>Worth showing the reader. A renko of candles and a renko of ticks wear
     * the same name and the same brick size and are not the same chart.</p>
     */
    private transient boolean fromTicks;

    private int firstBar;

    /**
     * Empty bars kept to the right of the last one.
     *
     * <p>Chosen by dragging the chart past its own end, and then <b>kept</b> as
     * bars arrive. Without it the newest candle is pinned against the right
     * edge, which is where the eye is and where there is no room to see it
     * form.</p>
     */
    private int rightMargin;

    private int visibleBars = DEFAULT_VISIBLE_BARS;

    private transient Point cursor;

    /**
     * The manual vertical scale, on top of the automatic one.
     *
     * <p>Kept per canvas, which means per tab: stretching one chart must not
     * reach into the others. It persists while the tab lives -- a factor that
     * reset on every repaint would be unusable -- and View offers a way back to
     * automatic, because a chart left stretched three days ago looks wrong for
     * no reason the reader can name.</p>
     */
    private double stretch = 1.0;

    /**
     * How far the price window is slid, as a share of its own span.
     *
     * <p>The vertical twin of {@link #rightMargin}: it is what makes the chart
     * something the reader puts where they want it, rather than something that
     * decides for them where it sits.</p>
     */
    private double priceOffset;

    /**
     * Kept in step with {@link RulerMode}, which owns it for the whole
     * application. Held here as a field so the paint path reads it without a
     * static call on every frame, and so the ruler can be cleared exactly when
     * the mode leaves measuring.
     */
    private transient Mode mode = RulerMode.isOn() ? Mode.MEASURE : Mode.PAN;

    /** Kept so it can be removed again: see addNotify and removeNotify. */
    private final transient Runnable followRuler = this::followGlobalMode;

    /** Kept so it can be removed again: see addNotify and removeNotify. */
    private final transient Runnable followDrawing = this::repaint;

    /** Told when the mode changes, so a menu can tick the right entry. */
    private transient Runnable onModeChanged = () -> { };

    /** The measurement being drawn, or the last one, until cleared. */
    private transient Measurement measurement;

    /** Where the ruler drag started, in bar and price. */
    private int rulerBar = -1;

    private double rulerPrice;

    public ChartCanvas() {
        setOpaque(true);
        setPreferredSize(new Dimension(640, 360));
        setDoubleBuffered(true);

        Mouse mouse = new Mouse();

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);

        installContextMenu();
        installControlToggle();
        installDigits();

        // Set at construction, not on the first mouse move: until the pointer
        // moves, the canvas would show the parent's cursor and the mode would be
        // invisible.
        setCursor(java.awt.Cursor.getPredefinedCursor(cursorForMode()));
    }

    /**
     * The right-click menu: for now, only the indicators.
     *
     * <p>Rebuilt on every click rather than kept: the "remove" list changes with
     * every insertion and removal, and a menu that is only correct if each of
     * those paths remembered to update it will eventually be wrong.</p>
     *
     * <p>The price under the click is not used yet. It is what the order tickets
     * will need -- right-clicking a level and sending an order there is one
     * gesture instead of reading the number off the axis and typing it back.</p>
     */
    private void installContextMenu() {
        addMouseListener(new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                // isPopupTrigger, not "right button": the gesture differs
                // between systems, and Swing already knows which one it is.
                if (e.isPopupTrigger()) {
                    buildContextMenu().show(ChartCanvas.this, e.getX(), e.getY());
                }
            }
        });
    }

    /**
     * @return the menu the right button opens
     *
     * <p>Package-private so the test can read what it says. The entries are
     * built from the indicators on the chart, and what they are CALLED is the
     * whole of what there is to get wrong.</p>
     */
    javax.swing.JPopupMenu buildContextMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        // ONE item. Which indicators exist and where each may go are two
        // different questions now, and the second one is asked in the dialog
        // rather than answered by which menu entry was clicked.
        javax.swing.JMenuItem insert =
                new javax.swing.JMenuItem(Messages.get("overlay.insertItem"));

        insert.addActionListener(e -> onInsertWanted.run());

        menu.add(insert);

        javax.swing.JMenu remove = new javax.swing.JMenu(Messages.get("overlay.removeItem"));

        // Disabled rather than hidden when there is nothing to remove. Hidden,
        // the reader looks for it where it is not; disabled, they see it exists
        // and infer the condition.
        remove.setEnabled(!overlays.isEmpty());

        for (Overlay overlay : overlays) {
            javax.swing.JMenuItem entry = new javax.swing.JMenuItem(
                    overlay.title());

            entry.addActionListener(e -> removeOverlay(overlay));
            remove.add(entry);
        }

        menu.add(remove);

        return menu;
    }

    /**
     * @return the chart a window shortcut should act on, or null when there is none
     *
     * <p><b>"The chart is the window" was never true here.</b> The charts are
     * internal frames inside one desktop, and every canvas registers the same
     * digit in {@code WHEN_IN_FOCUSED_WINDOW}. Swing walks the registered
     * bindings from the last to the first and stops at the one that consumes,
     * so with two charts open the digit was answered by whichever canvas
     * happened to register last -- not the one in front, not the one with
     * focus. Typing 5 changed the period of a chart the reader was not looking
     * at, and left the one they were alone, with no sign anywhere.</p>
     *
     * <p>So the binding stays on the window -- there is no text field here to
     * steal digits from, and making the reader click first is how shortcuts go
     * unused -- and the ACTION asks which chart is in front. Whichever canvas
     * the binding lands on, the answer is the same one.</p>
     */
    ChartCanvas frontChart() {
        javax.swing.JDesktopPane desktop = (javax.swing.JDesktopPane)
                javax.swing.SwingUtilities.getAncestorOfClass(
                        javax.swing.JDesktopPane.class, this);

        if (desktop == null) {
            // A floating chart is alone in its window, and the binding only
            // fires for the window that has focus.
            return this;
        }

        javax.swing.JInternalFrame selected = desktop.getSelectedFrame();

        return selected == null ? null : canvasIn(selected);
    }

    /** @return the first canvas inside that container, or null */
    private static ChartCanvas canvasIn(java.awt.Container where) {
        for (java.awt.Component each : where.getComponents()) {
            if (each instanceof ChartCanvas canvas) {
                return canvas;
            }

            if (each instanceof java.awt.Container inside) {
                ChartCanvas found = canvasIn(inside);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Typing a digit anywhere on the chart opens the period window.
     *
     * <p>Bound for the whole window rather than the focused component: there is
     * no text field here for the digits to be stolen from, and asking the reader
     * to click a chart before a shortcut works is the kind of thing that makes
     * shortcuts go unused. Which chart it acts on is decided by {@link
     * #frontChart}.</p>
     */
    private void installDigits() {
        for (char digit = '0'; digit <= '9'; digit++) {
            String typed = String.valueOf(digit);

            getInputMap(WHEN_IN_FOCUSED_WINDOW)
                    .put(javax.swing.KeyStroke.getKeyStroke(digit), "period" + typed);
            getActionMap().put("period" + typed, new javax.swing.AbstractAction() {

                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    ChartCanvas front = frontChart();

                    if (front == null) {
                        return;
                    }

                    front.askForPeriod(
                            javax.swing.SwingUtilities.getWindowAncestor(front), typed);
                }
            });
        }
    }

    /**
     * Control on its own toggles the mode.
     *
     * <p><b>On its own is the whole difficulty.</b> Binding the release of
     * Control would also fire after Ctrl+C, Ctrl+V and every other shortcut, so
     * copying text would silently switch the chart into measuring mode. The
     * dispatcher below watches for another key arriving while Control is held
     * and, if one does, treats the release as the end of a shortcut rather than
     * as the gesture.</p>
     *
     * <p>Registered only while the canvas is on screen. A dispatcher left
     * installed after the chart closes keeps a reference to it and keeps
     * reacting to keys for a window that is gone.</p>
     */
    private void installControlToggle() {
        addHierarchyListener(event -> {
            if (isDisplayable() == watching) {
                return;
            }

            watching = isDisplayable();

            if (watching) {
                armControlToggle();
            } else {
                disarmControlToggle();
            }
        });
    }

    /** Whether THIS canvas is currently counted among the ones on screen. */
    private transient boolean watching;

    /**
     * How many canvases are on screen, so the gesture is installed exactly once.
     *
     * <p><b>The count was the defect.</b> Every canvas installed a dispatcher of
     * its own, and none of them consumed the event -- so one release of Control
     * passed through all of them and each called {@code RulerMode.toggle}, which
     * is ONE global boolean. Two charts docked in the window, which is the
     * arrangement the shell offers in a grid, and the shortcut did NOTHING; with
     * three it worked. The behaviour depended on the parity of the number of
     * open charts, and nothing on screen said so.</p>
     *
     * <p>The comment that justified the old guard was wrong twice over: the
     * reason it gave -- "otherwise every chart on screen would flip together" --
     * stopped existing the day the mode became one switch in {@code RulerMode}
     * rather than one per canvas, and the guard it justified did not prevent
     * what it described.</p>
     */
    private static int watchers;

    private static java.awt.KeyEventDispatcher controlGesture;

    /** Whether Control has been held with nothing else pressed since. */
    private static boolean controlAloneNow;

    private static synchronized void armControlToggle() {
        if (watchers++ > 0) {
            return;
        }

        controlGesture = event -> {
            if (event.getKeyCode() != java.awt.event.KeyEvent.VK_CONTROL) {
                if (event.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    controlAloneNow = false;
                }

                return false;
            }

            if (event.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                controlAloneNow = true;
            } else if (event.getID() == java.awt.event.KeyEvent.KEY_RELEASED
                    && controlAloneNow) {
                controlAloneNow = false;

                // Only while one of this application's windows has focus: the
                // mode is global, and flipping it because Control was released
                // in another program would be a switch nobody touched.
                if (java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                        .getActiveWindow() != null) {
                    RulerMode.toggle();
                }
            }

            return false;
        };

        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(controlGesture);
    }

    private static synchronized void disarmControlToggle() {
        if (watchers > 0 && --watchers > 0) {
            return;
        }

        watchers = 0;

        if (controlGesture != null) {
            // A dispatcher left installed after the last chart closes goes on
            // reacting to keys for windows that are gone.
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removeKeyEventDispatcher(controlGesture);

            controlGesture = null;
        }
    }

    /** @return how many Control gestures are installed; for the test that says it is one */
    static synchronized int controlGestures() {
        return controlGesture == null ? 0 : 1;
    }

    /** @return how many canvases are counted as on screen; for the same test */
    static synchronized int watchingCanvases() {
        return watchers;
    }

    /**
     * Replaces every overlay at once, WITHOUT reporting a change.
     *
     * <p>Silent on purpose: this is a layout being applied, and telling the
     * layout bar about it would have it capture back what it has just written.</p>
     *
     * @param replacements the new set; calculated here
     */
    public void setOverlays(java.util.List<Overlay> replacements) {
        overlays.clear();

        for (Overlay overlay : replacements) {
            if (!overlay.fitsOnPrice()) {
                // Skipped, not drawn flat and not fatal. One catalogue means a
                // stored layout CAN name a panel indicator among the price
                // ones -- a workspace edited by hand, or written before the
                // two lists became one -- and the check belongs on the way in
                // rather than being trusted to whoever wrote the file.
                continue;
            }

            overlay.calculate(series);
            overlays.add(overlay);
        }

        repaint();

        // Redrawn but not written down. Everything showing this set has to hear
        // about it -- the legend above all, which otherwise keeps listing the
        // indicators of the layout that was left behind until a stray mouse
        // movement happens to repaint it.
        onOverlaysRedrawn.run();
    }

    /** @return the bars this chart is drawing */
    public PriceSeries series() {
        return series;
    }

    /**
     * @return whether the vertical scale is the one the chart chose for itself
     *
     * <p>What the toolbar button shows. Stretching or sliding the price leaves
     * automatic; the button unticks, and that tick is the only thing on screen
     * that says the scale is now the reader's and not the chart's.</p>
     *
     * <p>Compared exactly, and that is right here: both are ASSIGNED the
     * literal, by {@code resetStretch}, and neither is the result of
     * arithmetic. An epsilon would say "automatic" for a scale the reader
     * dragged by a hair, which is the opposite of what the caller needs -- it
     * asks in order to show that the scale is no longer being chosen for
     * them.</p>
     *
     * <p>Written down because the house rule is the other way round, and the
     * next reader would otherwise be right to "fix" it.</p>
     */
    public boolean isAutomaticScale() {
        return stretch == 1.0 && priceOffset == 0.0;
    }

    /** @param listener told when the vertical scale becomes manual, or automatic again */
    public void onScaleChanged(Runnable listener) {
        this.onScaleChanged = listener == null ? () -> { } : listener;
    }

    /** @param listener told when the bar under the pointer changes */
    public void onCursorChanged(Runnable listener) {
        this.onCursorChanged = listener == null ? () -> { } : listener;
    }

    /**
     * @return the bar under the pointer on one line, or empty when there is none
     *
     * <p>The time and the close, and nothing else. The full reading -- open,
     * high, low, change, volume -- is the box that follows the cursor; a footer
     * repeating it would be the same nine numbers in a place where they are
     * harder to read.</p>
     */
    public String cursorReading() {
        if (cursor == null || series.size() == 0) {
            return "";
        }

        // ONE VIEWPORT, and there used to be two: hoveredBar() builds one to
        // answer which bar is under the pointer, and the line below built
        // another to work out the grid step. This runs on every movement of the
        // mouse.
        Viewport viewport = viewport();
        int bar = viewport.barAt(cursor.x);

        if (bar < 0 || bar >= series.size()) {
            return "";
        }

        // THE SAME ROUNDING THE AXIS USES, and for the reason formatFor states:
        // fixed decimals either print 177.600,00 on an index or round a currency
        // pair away. This was a third copy of the rule with two places hard
        // coded, and it had already drifted from the axis and from the cursor
        // tag, which both read formatFor(gridStep(...)).
        return java.time.Instant.ofEpochMilli(series.timeAt(bar))
                .atZone(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone())
                .format(FOOTER_TIME)
                + "   " + formatFor(gridStep(viewport)).format(series.closeAt(bar));
    }

    /**
     * @return what the chart is doing, or empty when it is doing the usual thing
     *
     * <p>Empty for {@link Mode#PAN}. A section that permanently reads "normal"
     * is a section that never says anything, and the reader stops seeing it --
     * including on the day it says something else.</p>
     */
    public String modeLabel() {
        return mode == Mode.MEASURE ? Messages.get("status.mode.measure") : "";
    }

    /** @param listener opens the insert dialog and places the result */
    public void onInsertWanted(Runnable listener) {
        this.onInsertWanted = listener == null ? this::insertOnPriceOnly : listener;
    }

    private void insertOnPriceOnly() {
        InsertOverlayDialog.Placement placement = InsertOverlayDialog.ask(
                javax.swing.SwingUtilities.getWindowAncestor(this), java.util.List.of());

        if (placement != null && placement.onPrice()) {
            addOverlay(placement.indicator());
        }
    }

    /** @param listener told when the bars are replaced */
    public void onSeriesChanged(Runnable listener) {
        this.onSeriesChanged = listener == null ? () -> { } : listener;
    }

    /**
     * @param listener told when the overlays change in a way that should be
     *                 WRITTEN DOWN
     *
     * <p>Separate from {@link #onOverlaysRedrawn} because the two questions are
     * not the same one, and answering them together was a bug: applying a layout
     * has to redraw everything showing the overlays, and must NOT be written
     * down -- the layout bar would capture back what it has just applied.</p>
     */
    public void onOverlaysChanged(Runnable listener) {
        this.onOverlaysChanged = listener == null ? () -> { } : listener;
    }

    /** @param listener told whenever the overlays change, storable or not */
    public void onOverlaysRedrawn(Runnable listener) {
        this.onOverlaysRedrawn = listener == null ? () -> { } : listener;
    }

    /**
     * Reports that the overlays changed.
     *
     * <p>Public because the legend toggles visibility on the overlay object
     * itself, where the canvas cannot see it. Having the legend say so is
     * honest; polling the overlays on every paint to detect it would not be.</p>
     */
    public void overlaysChanged() {
        onOverlaysChanged.run();
        onOverlaysRedrawn.run();
    }

    /**
     * Swaps one overlay for another IN PLACE.
     *
     * <p>In place, keeping the position, because the legend is read top to
     * bottom: changing a period should not send the row to the end of the list
     * and make the reader hunt for it.</p>
     *
     * @param existing the one to replace
     * @param replacement the new one; calculated here
     */
    public void replaceOverlay(Overlay existing, Overlay replacement) {
        int at = overlays.indexOf(existing);

        if (at < 0 || replacement == null) {
            return;
        }

        replacement.setVisible(existing.isVisible());

        // AND ITS LOOK, not only whether it is shown. What comes back from
        // the insert dialog is built by the catalog's factory, so it wears
        // the defaults: the colour, the stroke, the scale of its own and the
        // interpolation the reader had chosen were all dropped by an edit
        // that was only meant to change the period.
        //
        // Not reachable today -- OverlayLegend.edit sends the moving average
        // and the bands to their own dialogs, and those two are the only
        // overlays that answer fitsOnPrice() -- so nothing gets here. It is
        // the third price indicator that would have paid for it.
        replacement.applyAppearance(existing.appearance());
        replacement.calculate(series);
        overlays.set(at, replacement);

        repaint();
        overlaysChanged();
    }

    /** Removes an overlay and redraws without it. */
    public void removeOverlay(Overlay overlay) {
        if (overlays.remove(overlay)) {
            repaint();
            overlaysChanged();
        }
    }

    /**
     * @param overlay something drawn on the price; calculated immediately
     * @return whether it went on
     *
     * <p>Refused when the indicator says it does not belong on a price axis.
     * That check is the reason {@link Overlay#fitsOnPrice()} exists: without
     * it, a stochastic put here would be drawn as a flat line along the floor
     * of the chart -- present in the legend, present in the layout, and saying
     * nothing. The type alone cannot stop it, because a panel indicator and a
     * price indicator are now the same type.</p>
     */
    public boolean addOverlay(Overlay overlay) {
        if (overlay == null || !overlay.fitsOnPrice()) {
            return false;
        }

        overlay.calculate(series);
        overlays.add(overlay);

        repaint();
        overlaysChanged();

        return true;
    }

    /**
     * Moves one overlay to a gap in the list.
     *
     * @param overlay the one being carried
     * @param gap where it should land, counted in the gaps BETWEEN overlays
     * @return whether anything actually moved
     *
     * <p>This is the same drop the layout tabs and the indicator panes answer
     * to, so the arithmetic is {@link Reordering}'s and not another copy.</p>
     *
     * <p>It reports the change as storable, which is what puts the new order
     * in the layout. An arrangement that had to be redone at every launch
     * would not be worth making.</p>
     */
    public boolean moveOverlay(Overlay overlay, int gap) {
        if (!Reordering.move(overlays, overlays.indexOf(overlay), gap)) {
            return false;
        }

        repaint();
        overlaysChanged();

        return true;
    }

    /** @return the overlays, for the legend and the show/hide toggles */
    public java.util.List<Overlay> overlays() {
        return java.util.List.copyOf(overlays);
    }

    /**
     * @param newSeries the bars as stored, at their own scale
     *
     * <p>The period is applied on top and survives: opening another instrument
     * on a chart set to renko keeps it on renko, which is what the reader
     * arranged the window for.</p>
     */
    public void setSeries(PriceSeries newSeries) {
        this.source = newSeries == null ? PriceSeries.empty() : newSeries;

        refold();
    }

    /**
     * How many bars of the file sit BEFORE the window that was loaded.
     *
     * <p>Zero means the chart is holding the whole file and there is nothing
     * behind it, which is also what a series with no file behind it says.</p>
     */
    private transient int behind;

    /** Told when the reader nears the loaded start and there is more file back there. */
    private transient Runnable onWantsHistory = () -> { };

    /** So one approach to the edge asks once, and not once per repaint. */
    private transient boolean asking;

    /**
     * How close to the loaded start the reader may come before more is fetched.
     *
     * <p><b>This is the warm-up, and it is the same mechanism as the paging.</b>
     * An average of two hundred has nothing to say for its first two hundred
     * bars, so at the loaded start it draws a gap -- and the bars that would
     * fill it are in the file, simply unread. Fetching before the reader arrives
     * pushes that gap off the left of the screen. The number is the widest
     * period any indicator here can be set to, which the moving average's own
     * spinner caps at two thousand, plus room to arrive.</p>
     */
    private static final int HISTORY_SLACK = 2_500;

    /**
     * @param bars how many bars of the file were not loaded, at the front
     * @param wanted what to run when the reader comes near them
     */
    public void setHistoryBehind(int bars, Runnable wanted) {
        this.behind = Math.max(0, bars);
        this.onWantsHistory = wanted == null ? () -> { } : wanted;
        this.asking = false;
    }

    /** @return how many bars of the file sit before what is loaded */
    public int historyBehind() {
        return behind;
    }

    /**
     * Puts older bars in front of the ones already here, without moving the view.
     *
     * @param longer the same window with more history at the front
     * @param added how many bars were added at the front
     */
    public void growHistory(PriceSeries longer, int added) {
        if (longer == null || added <= 0) {
            asking = false;

            return;
        }

        // Where the reader is looking, counted from the RIGHT, because that is
        // what stays still. The bars are being added at the other end.
        int fromRight = Math.max(0, this.series.size() - firstBar);

        this.source = longer;
        this.behind = Math.max(0, this.behind - added);
        this.asking = false;

        refold();

        this.firstBar = clampFirstBar(this.series.size() - fromRight);

        repaint();
        onSeriesChanged.run();
    }

    /** @param newPeriod the scale to look at, from {@link PeriodCatalog} */
    public void setPeriod(Aggregation newPeriod) {
        setPeriod(newPeriod, newPeriod == null ? null : newPeriod.label(),
                newPeriod == null ? null : newPeriod.label());
    }

    public void setPeriod(Aggregation newPeriod, String label) {
        setPeriod(newPeriod, label, label);
    }

    /** @return the period as the reader would type it, for reopening this chart */
    public String periodCode() {
        return periodCode;
    }

    /**
     * @param newPeriod the scale to look at
     * @param label how to write it, or null to let the scale name itself
     */
    public void setPeriod(Aggregation newPeriod, String label, String code) {
        // EQUALS, not identity. Renko and Timeframe are values, and
        // PeriodCatalog.byCode builds a new one on every call -- so choosing the
        // period already on screen compared two different objects, said "this
        // is a change", and paid for a whole refold: the fold itself, the tick
        // rebuild with its directory listings, and a SwingWorker. Not a wrong
        // answer; all the work again for nothing.
        if (newPeriod == null || newPeriod.equals(period)) {
            return;
        }

        if (newPeriod instanceof br.com.jorge.reis.endeavourneo.domain.market.Renko
                && !renkoAllowed()) {
            // REFUSED HERE, not only in the dialog. This guard used to live in
            // askForPeriod alone, so it protected the one door a reader knocks
            // on and no other -- and restoring a workspace goes straight to this
            // method. A reader who turned "fill in the ticks that are missing"
            // OFF, whose whole purpose is "I would rather be told than shown a
            // chart that is not what it says", got exactly the chart the setting
            // refuses, on every launch, without a word.
            //
            // Silent here because there is no window to speak from: the reader
            // is being handed a workspace, not answering a question. The chart
            // keeps the period it had, which is the honest fallback -- and the
            // dialog still says why when the refusal is an answer to something
            // that was asked.
            return;
        }

        this.period = newPeriod;
        this.periodLabel = label == null ? newPeriod.label() : label;
        this.periodCode = code == null ? this.periodLabel : code;

        refold();
    }

    /** @return the period as the title and the chart header write it */
    public String periodLabel() {
        return periodLabel;
    }

    /** @return the bars as stored, before the period is applied */
    public PriceSeries source() {
        return source;
    }

    public Aggregation period() {
        return period;
    }

    /**
     * @param show whether renko bricks carry the tail of the move against them
     *
     * <p>Does nothing unless the chart is on renko: nothing else has a tail to
     * show. The button is disabled rather than hidden in that case, so it does
     * not appear and vanish as the period changes.</p>
     */
    public void setWicks(boolean show) {
        if (period instanceof br.com.jorge.reis.endeavourneo.domain.market.Renko renko) {
            // The label is carried across: turning the tails off does not change
            // which period this is, and the title must not start saying
            // something else because a switch was flipped.
            setPeriod(renko.withWicks(show), periodLabel, periodCode);
        }
    }

    /** @return whether the tails are on, or false when this is not renko */
    public boolean hasWicks() {
        return period instanceof br.com.jorge.reis.endeavourneo.domain.market.Renko renko
                && renko.hasWicks();
    }

    /** @return whether the chart is on renko at all */
    public boolean isRenko() {
        return period instanceof br.com.jorge.reis.endeavourneo.domain.market.Renko;
    }

    /**
     * Opens the period window, and applies whatever comes back.
     *
     * @param owner the window it should sit over
     * @param typed the digit that opened it, or null when a double click did
     */
    public void askForPeriod(java.awt.Window owner, String typed) {
        PeriodCatalog.Choice choice = PeriodDialog.ask(owner, typed);

        if (choice == null) {
            return;
        }

        if (choice.aggregation() instanceof br.com.jorge.reis.endeavourneo.domain.market.Renko
                && !renkoAllowed()) {
            // Refused rather than quietly drawn from candles. See RenkoSource
            // for the measurement: the two differ by 5% to 23%, and
            // the reader has said in the settings that they would rather be
            // told than shown a chart that is not what it claims.
            javax.swing.JOptionPane.showMessageDialog(owner,
                    Messages.get("chart.renkoNeedsTicks", instrument == null ? "" : instrument),
                    Messages.get("chart.renkoNeedsTicks.title"),
                    javax.swing.JOptionPane.INFORMATION_MESSAGE);

            return;
        }

        setPeriod(choice.aggregation(), choice.title(), choice.code());
    }

    /**
     * Which export the bricks come from, and the renko growing out of it.
     *
     * <p>Set when a replay is dropped on this chart, because the replay already
     * asked the reader which export to play and answering that twice would be
     * the confusion the single list removed. Null on an ordinary chart, where
     * the source is worked out from what is on screen.</p>
     */
    private transient br.com.jorge.reis.endeavourneo.domain.market.TickSource playing;

    /**
     * The tick renko of a replay, extended rather than rebuilt.
     *
     * <p>Measured on the tape of 01/09/2026: folding the session again costs
     * 0,105 s, so rebuilding every frame would want 315% of a core at thirty
     * frames a second. Extending costs 0,018 ms a frame -- 0,1% of one -- and
     * lays exactly the same 1.090 bricks.</p>
     */
    private transient br.com.jorge.reis.endeavourneo.domain.market.TickRenko growing;

    /** The library the growing renko reads from; closed when the replay ends. */
    private transient br.com.jorge.reis.endeavourneo.domain.market.TickLibrary growingFrom;

    /**
     * Whether a replay is attached at all, which a null {@link #playing} cannot say.
     *
     * <p>Null used to mean two different things — no replay, and a replay of a
     * BAR series — and {@link #sourceForBricks} read it as the first. So a bar
     * replay of a day the tape covers put a renko built from the exchange's own
     * trades beside a candle animated by an invented walk: two panels of one
     * window off two different sources, with nothing on screen saying so.</p>
     *
     * <p>One rule now, and it is the feed: what a replay shows comes from what
     * the reader chose to play. The other half of it is in {@code
     * ReplaySession}, which no longer reaches for ticks under a bar feed.</p>
     */
    private transient boolean replaying;

    /**
     * @param source the export a replay is playing, or null for a bar feed
     * @param onReplay whether a replay is attached at all
     */
    public void setTickSource(br.com.jorge.reis.endeavourneo.domain.market.TickSource source,
            boolean onReplay) {
        this.playing = source;
        this.replaying = onReplay;

        stopGrowing();
    }

    /**
     * @param source the export a replay is playing, or null for an ordinary chart
     *
     * <p>For callers with no replay in hand: a non-null source is a replay by
     * definition, and a null one is a chart on its own.</p>
     */
    public void setTickSource(br.com.jorge.reis.endeavourneo.domain.market.TickSource source) {
        setTickSource(source, source != null);
    }

    /**
     * Lets go of the tick export this chart was growing bricks from.
     *
     * <p>For the holder to call when the chart CLOSES, and not from
     * removeNotify: re-parenting between docked and floating passes through
     * removeNotify too, and a replay would lose its renko every time the reader
     * undocked the window. Until this existed, the library survived the chart --
     * a reading thread and up to three sessions of ticks, for the life of the
     * application, per chart ever closed with a replay on it.</p>
     */
    public void releaseTicks() {
        stopGrowing();
    }

    /**
     * Puts a growing renko and the library it reads from in place.
     *
     * <p><b>Closing whatever was there first.</b> Two workers landing one after
     * the other dropped the earlier library on the floor -- a reading thread and
     * up to three sessions of ticks, 340 MB, held for the life of the
     * application, once per race.</p>
     *
     * <p>A method rather than three lines at the one site that needs them,
     * because the only test that guarded this read the SOURCE: it asked whether
     * the words {@code stopGrowing();} appeared within 600 characters before the
     * assignment. A second assignment somewhere else -- a new replay path, say
     * -- would never have been looked at, since {@code indexOf} finds the first
     * and stops. Here there is one door, and the test measures the object that
     * goes through it.</p>
     */
    void growFrom(br.com.jorge.reis.endeavourneo.domain.market.TickRenko built,
            br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library) {
        stopGrowing();

        growing = built;
        growingFrom = library;
    }

    /** @return the library the growing renko reads from, or null; for the test of the swap */
    br.com.jorge.reis.endeavourneo.domain.market.TickLibrary growingLibrary() {
        return growingFrom;
    }

    private void stopGrowing() {
        growing = null;

        if (growingFrom != null) {
            growingFrom.close();
            growingFrom = null;
        }
    }

    /**
     * @return the export whose ticks should build this chart's bricks
     *
     * <p>What a replay is playing, when there is one. Otherwise the export that
     * has EVERY session on screen -- and the tape first, because it is the
     * trades themselves rather than a quote stream. A chart half of whose days
     * came from one export and half from another would change density in the
     * middle, which is the same mistake as mixing candles with ticks.</p>
     */
    private br.com.jorge.reis.endeavourneo.domain.market.TickSource sourceForBricks() {
        if (replaying) {
            // The feed decides, and only the feed. A bar replay answers null
            // here and its bricks are folded from the bars on screen -- the same
            // bars the reader chose to play, animated by the same walk. Falling
            // through to the search below is what put a tape renko beside a
            // synthetic candle.
            return playing;
        }

        Bricks bricks = libraryForBricks();

        if (bricks == null) {
            return null;
        }

        // Asked and let go: this caller wants only the NAME of the export, so it
        // closes what the search opened. rebuildFromTicks wants the library
        // itself, and that is why the search hands it over rather than closing
        // it on the way out.
        bricks.library().close();

        return bricks.source();
    }

    /**
     * An export that can lay this chart's bricks, and the library open on it.
     *
     * @param source which export it is
     * @param library the library already open on it -- <b>the caller closes it</b>
     */
    record Bricks(br.com.jorge.reis.endeavourneo.domain.market.TickSource source,
                  br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library) { }

    /**
     * @return the export that has every session on screen, and its open library
     *
     * <p><b>The library comes back open, and that is the point.</b> The search
     * opens one per export to ask whether it covers the sessions on screen, and
     * it used to close every one of them and answer with a name -- so {@link
     * #rebuildFromTicks} opened a THIRD library on the export just chosen and
     * asked it the same question a second time. Three libraries and three
     * directory listings, on the interface thread, for an answer already in
     * hand, on every fold: every change of period, every setSeries, every page
     * of history that arrives.</p>
     *
     * <p>The tape first, because it is the trades themselves rather than a quote
     * stream. A chart half of whose days came from one export and half from
     * another would change density in the middle, which is the same mistake as
     * mixing candles with ticks.</p>
     */
    Bricks libraryForBricks() {
        if (replaying) {
            return playing == null ? null : new Bricks(playing, libraryOn(playing));
        }

        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource each
                : new br.com.jorge.reis.endeavourneo.domain.market.TickSource[]{
                    br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT,
                    br.com.jorge.reis.endeavourneo.domain.market.TickSource.METATRADER}) {
            br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library = libraryOn(each);

            if (RenkoSource.allows(source, library, false)) {
                return new Bricks(each, library);
            }

            library.close();
        }

        return null;
    }

    private br.com.jorge.reis.endeavourneo.domain.market.TickLibrary libraryOn(
            br.com.jorge.reis.endeavourneo.domain.market.TickSource which) {
        // ownership: opened here and CLOSED BY THE CALLER -- libraryForBricks
        // closes the exports it rejects and hands the one it accepts on, and
        // from there sourceForBricks closes it at once while rebuildFromTicks
        // gives it to the worker. Said out loud because a factory of a thing
        // that must be closed is exactly what no try-with-resources here can
        // express, and because TickLibraryClosingTest reads this line.
        return new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.ticksOf(
                        RenkoSource.rootOf(instrument)),
                RenkoSource.rootOf(instrument), which);
    }

    /**
     * How many times a tick rebuild has finished caring, one way or the other.
     *
     * <p>Package-visible, and it exists for the tests that assert an ABSENCE:
     * that the chart did NOT switch to tick bricks. Waiting a fixed 150 or 300
     * milliseconds and then checking made those pass by arriving early on a
     * loaded machine, a cold disk or a first run of the JVM -- a guard that is
     * sometimes there is worse than one that is missing, because the times it
     * is not get blamed on the machine.</p>
     *
     * <p>What they need is a positive thing to wait FOR, and this is it: the
     * count goes up when the rebuild refuses, when it is dropped as stale, when
     * it fails, and when it puts bricks on screen. So a test waits until the
     * attempt has landed and only then asks what it did.</p>
     */
    int ticksSettled() {
        return ticksSettled;
    }

    private transient int ticksSettled;

    /**
     * Builds the renko from the exchange's own ticks, if it can, off this thread.
     *
     * <p>Only when EVERY session on screen has ticks: a renko built partly from
     * ticks and partly from candles would change density halfway across and look
     * like the market did it. Measured, the two differ by 5% to 23% — see
     * {@link RenkoSource}.</p>
     *
     * <p>The result is dropped if the period changed while it was being built.
     * A reader who types 11 and then 55 must not be shown the eleven, arriving
     * late and looking authoritative.</p>
     */
    private void rebuildFromTicks() {
        // Dropped FIRST, on every path out of here. It used to be dropped only
        // where a new one was about to be built, so leaving renko for minutes
        // returned early and left the old one growing -- and every frame after
        // that took the extend path and put its BRICKS on screen while the
        // chart was set to minutes. The chart never came back, which is exactly
        // how it was reported: "go to renko and it stops, and then it will not
        // go back to minutes".
        stopGrowing();

        if (!(period instanceof br.com.jorge.reis.endeavourneo.domain.market.Renko renko)
                || instrument == null || source == null || source.size() == 0) {
            ticksSettled++;

            return;
        }

        java.util.List<java.time.LocalDate> onScreen = RenkoSource.sessionsIn(source);
        Bricks bricks = libraryForBricks();

        if (bricks == null) {
            ticksSettled++;

            // No export holds every session on screen. "Every" and not "some":
            // bricks laid from ticks and bricks laid from candles differ by 5%
            // to 22% on the tape, so a chart built half one way would change
            // density in the middle and look like the market did it.
            return;
        }

        // ownership: taken from libraryForBricks OPEN -- it is the library the
        // search already had in its hand -- and handed to the worker below,
        // which either closes it or gives it to growingFrom; and stopGrowing
        // closes that, from setTickSource, from the next rebuild, and from the
        // holder when the chart closes. Said out loud because it is the one
        // construction here that no try-with-resources can express, and because
        // TickLibraryClosingTest reads these lines looking for exactly this.
        br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library = bricks.library();

        if (!RenkoSource.allows(source, library, false)) {
            // "false" and not the setting: this asks whether the ticks are
            // THERE, which is a fact. What the setting decides is whether a
            // renko may be drawn without them, and that is decided elsewhere.
            library.close();
            ticksSettled++;

            return;
        }

        Object asked = period;

        // The SOURCE as well as the period. The guard in done() compared only
        // the period, and attachReplay and detachReplay swap the source without
        // touching it -- so a build started before a replay was dropped came
        // back afterwards, passed the guard, and put bricks of the plain series
        // on top of the replay's. That is the candle-and-tick mixing this whole
        // path exists to prevent, arriving by the one door it did not watch.
        PriceSeries askedOf = source;

        // A replay KEEPS its renko, so the next frame can extend it instead of
        // folding the whole session again -- 0,018 ms against 0,105 s. An
        // ordinary chart has no next frame, so it folds once and lets go.
        // NOT called `replaying`, which is the name of a field twenty lines
        // above that answers a DIFFERENT question -- and whose javadoc spends
        // five lines on the damage that confusion did. The two happen to agree
        // here, because the path already returned when the export was null; a
        // local that shadows the field with the semantics the field replaced is
        // a trap for whoever edits this worker next.
        boolean keepsItsRenko = playing != null;

        // Which day the replay is STANDING on, so the fold below stops one day
        // short of it and not one day short of the list.
        //
        // Those are different at the only moment that matters. When a replay is
        // dropped, nothing of the session has been revealed yet, so the last
        // day on screen is the last day of HISTORY -- and stopping one short of
        // the list left that day unfolded for ever, because the clock then
        // moved into the next one and never came back. A whole session of
        // bricks went missing without a mark.
        java.time.LocalDate standing = keepsItsRenko ? dayOfClock() : null;

        new javax.swing.SwingWorker<
                br.com.jorge.reis.endeavourneo.domain.market.TickRenko, Void>() {

            @Override
            protected br.com.jorge.reis.endeavourneo.domain.market.TickRenko
                    doInBackground() throws Exception {
                br.com.jorge.reis.endeavourneo.domain.market.TickRenko built =
                        new br.com.jorge.reis.endeavourneo.domain.market.TickRenko(
                                renko, library);

                // Every day BEHIND the one the replay stands on, folded whole.
                // The one it stands on is left to advance(), which lays only
                // what has printed by the replay's clock -- folding it whole
                // would put bricks on screen for trades that have not happened
                // yet.
                for (java.time.LocalDate each : onScreen) {
                    if (standing != null && !each.isBefore(standing)) {
                        break;
                    }

                    built.add(each);
                }

                return built;
            }

            @Override
            protected void done() {
                ticksSettled++;

                if (asked != period || askedOf != source) {
                    library.close();

                    return;
                }

                try {
                    br.com.jorge.reis.endeavourneo.domain.market.TickRenko built = get();

                    if (keepsItsRenko) {
                        growFrom(built, library);

                        // The session in progress is carried in by the next
                        // frame, which is a fortieth of a second away.
                        extendBricks();
                        repaint();

                        return;
                    }

                    library.close();

                    PriceSeries bricks = built.bricks();

                    if (bricks == null || bricks.size() == 0) {
                        return;
                    }

                    show(bricks);
                } catch (java.util.concurrent.ExecutionException
                        | InterruptedException e) {
                    // A session that will not read leaves the candle renko on
                    // screen, which is what was already drawn. Interrupting the
                    // reader with a dialog over an indicator would be worse
                    // than the indicator being the coarser of the two.
                    library.close();

                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }.execute();
    }

    /** Puts a freshly built series on screen, keeping the reader where they were. */
    private void show(PriceSeries bricks) {
        int wasFromRight = Math.max(0, this.series.size() - firstBar);

        this.series = bricks;
        this.fromTicks = true;

        for (Overlay overlay : overlays) {
            overlay.calculate(this.series);
        }

        this.firstBar = clampFirstBar(bricks.size() - wasFromRight);

        repaint();
        onSeriesChanged.run();
    }

    /** A restore that arrived before the bars did, held until they do. */
    private transient br.com.jorge.reis.endeavourneo.platform.Settings wanted;

    private transient String wantedPrefix;

    /**
     * Replays a restore that could not be applied when it was asked for.
     *
     * <p>Called from the fold, which is where bars first exist. Cleared before
     * the replay so a restore that still cannot be applied does not loop.</p>
     */
    private void restoreWhatWasWanted() {
        if (wanted == null || series.size() == 0) {
            return;
        }

        br.com.jorge.reis.endeavourneo.platform.Settings from = wanted;
        String prefix = wantedPrefix;

        wanted = null;
        wantedPrefix = null;

        restoreView(from, prefix);
    }

    /** @return whether what is drawn came from the exchange's own ticks */
    public boolean isFromTicks() {
        return fromTicks;
    }

    /**
     * @return whether a renko may be built for what this chart is showing
     *
     * <p><b>The same question the builder asks, asked the same way.</b> This
     * used to ask a narrower one -- only the MetaTrader export, while {@link
     * #sourceForBricks} tries the Profit tape first and says why. An instrument
     * whose sessions exist only on the tape was refused here by a guard that the
     * builder beside it would have satisfied, and the reader was told there are
     * no recorded ticks for the sessions on screen when there are.</p>
     *
     * <p>With the invented walk allowed -- which is the default -- any series
     * may be drawn as renko, and that is what RenkoSource.allows answers for a
     * reader who has said missing ticks may be filled in.</p>
     */
    boolean renkoAllowed() {
        return ChartPreferences.syntheticTicks() || sourceForBricks() != null;
    }

    /** @param name what the chart is showing, so its tick sessions can be found */
    public void setInstrument(String name) {
        this.instrument = name;
    }

    public String instrument() {
        return instrument;
    }

    /**
     * The series got longer; fold it again and stay looking at the end.
     *
     * <p>Separate from {@link #setSeries} because that one re-frames the chart,
     * and re-framing on every replay tick would throw away the reader's zoom
     * several times a second. Here the window keeps its width and slides to the
     * right, which is what watching a market do something looks like.</p>
     */
    public void seriesGrew() {
        if (growing != null && extendBricks()) {
            // The bricks grew from the ticks themselves. Falling through to the
            // candle fold below would throw them away and replace them with a
            // renko of the replay's bars, which is the mixing this exists to
            // stop. The overlays were recalculated inside extendBricks, which is
            // where every caller gets it and none can forget it.
            this.visibleBars = Math.max(1, Math.min(visibleBars,
                    Math.max(1, this.series.size())));
            this.firstBar = clampFirstBar(this.series.size() - visibleBars + rightMargin);

            repaint();

            return;
        }

        this.series = period.apply(source);

        for (Overlay overlay : overlays) {
            overlay.calculate(this.series);
        }

        this.visibleBars = Math.max(1, Math.min(visibleBars, Math.max(1, this.series.size())));
        this.firstBar = clampFirstBar(this.series.size() - visibleBars + rightMargin);

        repaint();
    }

    /**
     * @return the replay's own clock, which moves inside a bar as well as
     *         between bars
     *
     * <p>The bar's timestamp is its bucket's START, so reading that would ask
     * "what time is it" and be told 09:02 for a whole minute of market -- and
     * the renko would have nothing new to lay until the minute turned over.
     * That is exactly how it froze.</p>
     */
    private long clockNow() {
        if (source instanceof br.com.jorge.reis.endeavourneo.domain.market.ReplaySeries live) {
            return live.clock();
        }

        return source == null || source.size() == 0
                ? 0L : source.timeAt(source.size() - 1);
    }

    private java.time.LocalDate dayOfClock() {
        return java.time.Instant.ofEpochMilli(clockNow())
                .atZone(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone()).toLocalDate();
    }

    /**
     * Asks the loader for the day the clock is in AND the one after it.
     *
     * <p>On the loader thread, before {@code advance()} goes looking. {@code
     * TickRenko.advance} calls {@code load()}, which reads the session from disk
     * when it is not resident -- ninety megabytes, on the interface thread, at
     * the instant the replay crosses midnight. Asking for both means the file is
     * already in memory when the clock gets there.</p>
     *
     * <p><b>The second call is belt and braces, and saying so is the point.</b>
     * {@code TickLibrary.request} already queues the day, the one after and the
     * one before -- "the day itself first, then its neighbours" -- so tomorrow
     * is on its way from the first call alone. What the second one changes is
     * {@code focus}, which decides what is evicted last, and it moves it to
     * tomorrow while the clock is still on today. A report asked for a test that
     * "the next session is asked for, or midnight blocks"; midnight does not
     * block without it, and a test written to that sentence would have been
     * measuring a line that does not carry the guarantee. What carries it is
     * that BOTH sessions are resident before the clock arrives, which is what
     * the test asserts.</p>
     *
     * @param library where the sessions come from, or null when none is growing
     * @param day where the replay clock is now
     */
    static void requestAround(br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library,
            java.time.LocalDate day) {
        if (library == null) {
            return;
        }

        library.request(day);
        library.request(day.plusDays(1));
    }

    /**
     * @return whether the growing renko could be carried to the replay's clock
     *
     * <p>Only what printed since the last frame is folded. The ruler carries
     * across, so the bricks line up with the ones already laid -- that property
     * is what makes the cheap path give the same answer as the expensive
     * one.</p>
     */
    private boolean extendBricks() {
        if (source == null || source.size() == 0) {
            return false;
        }

        // The replay's own clock, which moves inside a bar as well as between
        // bars. The bar's timestamp is its bucket's START, so reading that
        // would ask "what time is it" and be told 09:02 for a whole minute of
        // market -- and the renko would have nothing new to lay until the
        // minute turned over. That is exactly how it froze.
        long now = clockNow();
        java.time.LocalDate day = dayOfClock();

        try {
            if (growing.advancing() != null && day.isBefore(growing.advancing())) {
                // The replay was stopped and started somewhere earlier. The
                // renko cannot walk backwards, so it is built again from
                // scratch rather than carried into a past it already left --
                // and "built again" has to actually happen. It did not: this
                // returned false and nothing rebuilt anything, so the caller
                // fell back to the candle fold and left fromTicks saying the
                // bricks came from ticks. Permanently, until the chart was
                // reopened.
                stopGrowing();
                refold();

                return false;
            }

            requestAround(growingFrom, day);

            if (!growing.advance(day, now + 1) && fromTicks) {
                // NOTHING PRINTED since the last frame, so there is nothing new
                // to show. This used to rebuild the whole view anyway -- every
                // brick copied into a new series, twenty-five times a second,
                // for a chart that had not changed.
                //
                // TRUE, not false. The renko is still good and still on screen;
                // false is the answer for "this renko has to be abandoned", and
                // returning it here made a quiet frame look like a failure --
                // the caller swapped the tick renko for the candle renko, which
                // lays 5% to 23% more bricks, and fromTicks went on claiming
                // otherwise.
                return true;
            }
        } catch (java.io.IOException | IllegalArgumentException e) {
            // A session that will not read, or an order this renko cannot take.
            // Dropping back to the candle fold is wrong-but-visible; carrying
            // on with bricks that skipped a day would be wrong and invisible.
            stopGrowing();

            return false;
        }

        // live(), not bricks(): the one still being built belongs on screen.
        // Showing only the settled ones is what made the replay jump.
        this.series = growing.live();
        this.fromTicks = true;

        // HERE, and not in the callers. Both of them change the series to a
        // renko of ticks, and an overlay still holding what it worked out from
        // the candles is a moving average of minutes drawn across bricks --
        // wrong, and wrong in a way that looks like an indicator rather than
        // like a fault. seriesGrew did recalculate; the SwingWorker that first
        // builds the renko did not, and its comment excused it by saying the
        // next frame is a fortieth of a second away. It is -- while the replay
        // is PLAYING. Paused, there is no next frame, and the stale line stays
        // on the screen for as long as the reader looks at it.
        for (Overlay each : overlays) {
            each.calculate(this.series);
        }

        return true;
    }

    private void refold() {
        // WHERE THE READER WAS, kept across a fold that changes no bar. Turning
        // the renko tails on and off goes through setPeriod and lands here, and
        // it threw the zoom and the position away every time -- a switch that
        // decorates the bricks was re-framing the chart. The tails are
        // Renko.withWicks, decoration of a bar, and the brick count is the same
        // either side of the switch.
        int wasSize = this.series == null ? 0 : this.series.size();
        int wasVisible = this.visibleBars;
        int wasMargin = this.rightMargin;
        int wasFirst = this.firstBar;

        // The candles first, always. They are instant, so the chart is never
        // blank; when the ticks are available the bricks arrive a moment later
        // and replace them. Waiting for the ticks instead would freeze the
        // interface for a fifth of a second per session.
        this.series = period.apply(source);
        this.fromTicks = false;

        rebuildFromTicks();

        // Recalculated here, not lazily on the next paint: an overlay still
        // holding values from the previous series would draw a line that
        // belongs to other data, and it would look plausible.
        for (Overlay overlay : overlays) {
            overlay.calculate(this.series);
        }
        if (wasSize > 0 && this.series.size() == wasSize) {
            // Same bars, different decoration: the reader stays exactly where
            // they were.
            this.visibleBars = wasVisible;
            this.rightMargin = wasMargin;
            this.firstBar = clampFirstBar(wasFirst);

            repaint();
            onSeriesChanged.run();

            return;
        }

        this.visibleBars = Math.min(DEFAULT_VISIBLE_BARS, Math.max(1, this.series.size()));

        // A fresh series opens with air on the right, not glued to the frame: a
        // chart whose last candle touches the edge looks cut off, and the first
        // thing anybody does is drag it left.
        this.rightMargin = birthMargin();
        this.firstBar = clampFirstBar(this.series.size() - visibleBars + rightMargin);

        // AFTER the defaults, so a restore that was waiting for bars overwrites
        // them rather than being overwritten by them. See restoreView.
        restoreWhatWasWanted();

        repaint();
        onSeriesChanged.run();
    }

    public void setStyle(ChartStyle newStyle) {
        if (newStyle != null) {
            this.style = newStyle;

            repaint();
            onStyleChanged.run();
        }
    }

    /**
     * @param listener told whenever the drawing style changes
     *
     * <p><b>There was no such listener, and the toolbar kept its own answer.</b>
     * The holder wrote down which button it had pressed and drew the toolbar
     * from that, so after a workspace restored a line chart the button still
     * showed candles: two answers to "what style is this chart", and the one on
     * screen was the wrong one.</p>
     */
    public void onStyleChanged(Runnable listener) {
        this.onStyleChanged = listener == null ? () -> { } : listener;
    }

    private transient Runnable onStyleChanged = () -> { };

    /**
     * @return how the price is drawn: candles or a line
     *
     * <p>Two styles and not three. Hollow candles are a SETTING of the candle
     * style -- {@code ChartPreferences.hollowCandles}, read by {@code
     * CandleStyle} as it draws -- and this javadoc listed them as a third,
     * which is the very mistake that setting's own javadoc says it exists to
     * avoid.</p>
     */
    public ChartStyle style() {
        return style;
    }

    /**
     * @return the bar under the mouse, or -1 when the mouse is elsewhere
     *
     * <p>"Elsewhere" includes the two axis strips, and it did not. {@code
     * Viewport.barAt} clamps, so a pointer over the price axis was answered with
     * the last visible bar -- a bar it is not on -- while the javadoc promised
     * minus one.</p>
     */
    public int hoveredBar() {
        if (cursor == null || series.size() == 0
                || onAxis(cursor.x) || onTimeAxis(cursor.y)) {

            return -1;
        }

        return viewport().barAt(cursor.x);
    }

    /**
     * @return where every visible bar sits horizontally, or null before there
     *         is anything to place
     *
     * <p><b>Shared with the panes below.</b> A study draws its own values on
     * its own vertical scale, but the x of a bar has to be the SAME x, or a
     * peak in the indicator sits beside the candle that made it instead of
     * under it. One viewport, handed out, is the only way that stays true
     * through scrolling, zooming and a window being resized.</p>
     */
    public Viewport plotViewport() {
        return series == null || series.size() == 0 ? null : viewport();
    }

    /** @return how wide the strip on the right is; the panes leave the same */
    public int axisWidth() {
        return AXIS_WIDTH;
    }

    /** @return the last bar on screen, which is what a legend reads when the mouse is away */
    public int lastVisibleBar() {
        return Math.max(0, Math.min(series.size() - 1, firstBar + visibleBars - 1));
    }

    /**
     * Repaints this, and anything drawn from this.
     *
     * <p>The panes under the chart borrow its viewport, so every reason to
     * redraw the chart is a reason to redraw them -- scrolling, zooming, a new
     * series, a replay tick. Catching it HERE rather than at the twenty-three
     * places that move the view is not laziness: those twenty-three will become
     * twenty-four, and the one that forgets would leave an indicator drawn
     * against the wrong bars with nothing on screen admitting it.</p>
     *
     * <p>No loop: repainting a sibling does not repaint this.</p>
     */
    @Override
    public void repaint(long delay, int x, int y, int width, int height) {
        super.repaint(delay, x, y, width, height);

        for (java.awt.Component each : followers) {
            each.repaint();
        }
    }

    /** @param pane redrawn whenever this chart is */
    public void follow(java.awt.Component pane) {
        if (pane != null && !followers.contains(pane)) {
            followers.add(pane);
        }
    }

    public void unfollow(java.awt.Component pane) {
        followers.remove(pane);
    }

    private Viewport viewport() {
        return Viewport.of(series, plotBounds(), firstBar, visibleBars, stretch, priceOffset);
    }

    /** @return how much the bottom strips take together */
    private int axisHeight() {
        return TIME_HEIGHT + DAY_HEIGHT;
    }

    /** @return the drawing area, which stops before both strips */
    private Rectangle plotBounds() {
        return new Rectangle(0, 0, Math.max(1, getWidth() - AXIS_WIDTH),
                Math.max(1, getHeight() - axisHeight()));
    }

    /** @param x a horizontal pixel
     *  @return whether it falls in the price strip */
    private boolean onAxis(int x) {
        return x >= getWidth() - AXIS_WIDTH;
    }

    /** @param y a vertical pixel
     *  @return whether it falls in the time strip */
    private boolean onTimeAxis(int y) {
        return y >= getHeight() - axisHeight();
    }

    /**
     * @return where the jump button sits, or null when there is nowhere to jump
     *
     * <p>Absent rather than disabled when already at the end: a permanent
     * control that does nothing most of the time trains the reader to ignore
     * it, and this one matters precisely on the rare occasion it appears.</p>
     */
    private Rectangle jumpBounds() {
        if (series.size() == 0 || firstBar + visibleBars >= series.size()) {
            return null;
        }

        Rectangle plot = plotBounds();

        // Top right, not bottom: the eye goes to the top-right corner for the
        // most recent data, and a control that returns you there belongs where
        // you are already looking. It also stays clear of the time axis.
        return new Rectangle(plot.width - JUMP_SIZE - JUMP_MARGIN, JUMP_MARGIN,
                JUMP_SIZE, JUMP_SIZE);
    }

    /**
     * @return where the leftmost visible bar may be
     *
     * <p>Past the end by up to {@link #AIR_RIGHT} of a screen, which is what
     * makes room on the right. This used to say "half a screen" while the
     * constant said three quarters, and the constant's own javadoc explains the
     * choice -- "Half was not enough to feel like control". Fifteen hundred
     * lines apart, contradicting each other: whoever read this one first would
     * "correct" the constant back to the number that was rejected.</p>
     */
    private int clampFirstBar(int candidate) {
        int air = Math.max(1, (int) Math.round(visibleBars * AIR_RIGHT));
        int furthest = Math.max(0, series.size() - visibleBars + air);
        int settled = Math.max(0, Math.min(candidate, furthest));

        // HERE, because every way of moving the chart ends up here: the drag,
        // the wheel, the keyboard, going to the end. Watching each of them
        // instead would mean the one added next year does not ask.
        if (!asking && behind > 0 && settled < HISTORY_SLACK) {
            asking = true;

            onWantsHistory.run();
        }

        return settled;
    }

    /**
     * @param bar which bar to put at the left of the screen
     *
     * <p>Package-private. The reader moves a chart with the mouse and the
     * keyboard, and every one of those paths ends at {@link #clampFirstBar} --
     * which is also where the chart notices it is near the history it has not
     * loaded. This is that same door, opened for a test that has to stand
     * somewhere without a mouse.</p>
     */
    void scrollTo(int bar) {
        firstBar = clampFirstBar(bar);

        repaint();
    }

    /** @return the bar at the left of the screen */
    int firstVisibleBar() {
        return firstBar;
    }

    /** Scrolls back to the newest bars, keeping whatever air was left on the right. */
    public void goToEnd() {
        firstBar = clampFirstBar(series.size() - visibleBars + rightMargin);

        repaint();
    }

    /**
     * @param currentBars how many bars are visible now
     * @param deltaX how far the mouse moved right since the drag started
     * @param available how many bars the series has
     * @return the new count, clamped
     *
     * <p>Dragging LEFT pulls more history in, so more bars fit and they get
     * narrower. It matches the gesture of hauling the axis towards the past.
     * Separate from the mouse handling so the arithmetic can be tested without
     * a window.</p>
     */
    static int barsForDrag(int currentBars, int deltaX, int available) {
        double scaled = currentBars * Math.exp(deltaX * TIME_DRAG_SENSITIVITY);

        return (int) Math.max(MINIMUM_VISIBLE_BARS,
                Math.min(Math.round(scaled), Math.max(MINIMUM_VISIBLE_BARS, available)));
    }

    /**
     * @param anchor the bar under the cursor before the zoom
     * @param x where the cursor is, in pixels from the left of the component
     * @param plotWidth how wide the PLOT is -- the component less the price axis
     * @param bars how many bars will be visible after the zoom
     * @return where the window should start so that bar stays under the cursor
     *
     * <p>The bar under the cursor stays under the cursor. Zooming around the
     * centre instead makes the reader chase what they were looking at, and it is
     * the single thing that most makes a chart feel wrong.</p>
     *
     * <p><b>Over the PLOT, not over the component.</b> {@code Viewport.barAt}
     * maps across {@code plotBounds().width}, and this divided by the whole
     * width instead -- some sixty pixels more. The fraction came out about a
     * tenth too small at 640 wide, so the anchored bar slid left at every step
     * of zoom, against the promise in the paragraph above.</p>
     *
     * <p>Apart from the listener so the arithmetic can be checked without a
     * window, the same way {@link #stretchForDrag} is.</p>
     */
    static int firstBarForZoom(int anchor, int x, int plotWidth, int bars) {
        double share = x / (double) Math.max(1, plotWidth);

        return (int) Math.round(anchor - share * bars);
    }

    /**
     * @param current the factor now
     * @param deltaY how far the mouse moved down since the drag started
     * @return the new factor, clamped
     *
     * <p>Dragging UP stretches. It matches the gesture: pulling the axis taller
     * makes the chart taller. Separate from the mouse handling so the arithmetic
     * can be tested without a window.</p>
     */
    static double stretchForDrag(double current, int deltaY) {
        double scaled = current * Math.exp(-deltaY * DRAG_SENSITIVITY);

        return Math.max(MINIMUM_STRETCH, Math.min(scaled, MAXIMUM_STRETCH));
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Starts following the application-wide mode.
     *
     * <p>Registered here rather than in the constructor, and dropped again in
     * {@link #removeNotify}, so a closed chart does not keep the window it lived
     * in alive through a listener nobody can reach. Re-parenting between docked
     * and floating passes through both, which is exactly right: it leaves and
     * comes back.</p>
     */
    @Override
    public void addNotify() {
        super.addNotify();

        RulerMode.listen(followRuler);
        ChartPreferences.listen(followDrawing);
        followGlobalMode();
    }

    @Override
    public void removeNotify() {
        RulerMode.forget(followRuler);
        ChartPreferences.forget(followDrawing);

        // NOT stopGrowing() here, on purpose: re-parenting between docked and
        // floating passes through removeNotify too, and a replay would lose its
        // renko every time the reader undocked the window. The holder calls
        // releaseTicks() when the chart actually closes -- see there.

        super.removeNotify();
    }

    private void followGlobalMode() {
        applyMode(RulerMode.isOn() ? Mode.MEASURE : Mode.PAN);
    }

    /**
     * @return the cursor the current mode uses over the plot
     *
     * <p>The hand says "this surface moves"; the crosshair says "you are
     * pointing at an exact spot". A mode with no cursor of its own leaves the
     * reader to discover what a drag does by trying it.</p>
     *
     * <p>AWT has no open-and-closing grab hand, so the pointing hand stands in.
     * A real grab cursor would mean shipping an image, and an image cursor does
     * not follow the theme.</p>
     */
    private int cursorForMode() {
        return mode == Mode.MEASURE
                ? java.awt.Cursor.CROSSHAIR_CURSOR
                : java.awt.Cursor.HAND_CURSOR;
    }

    /**
     * @param newMode what a drag should do from now on
     *
     * <p>Switching away from measuring clears the ruler. A line left on screen
     * in panning mode cannot be removed by any gesture that mode has, so it
     * would sit there until the chart was closed.</p>
     */
    public void setMode(Mode newMode) {
        if (newMode != null) {
            // Through the application-wide switch, never straight into the
            // field: every other chart has to change with this one, and the
            // preferences checkbox has to end up ticked.
            RulerMode.set(newMode == Mode.MEASURE);
        }
    }

    /** Flips between moving and measuring, everywhere. */
    public void toggleMode() {
        RulerMode.toggle();
    }

    private void applyMode(Mode newMode) {
        if (newMode == null || newMode == mode) {
            return;
        }

        mode = newMode;

        if (mode == Mode.PAN) {
            measurement = null;
            rulerBar = -1;
        }

        setCursor(java.awt.Cursor.getPredefinedCursor(cursorForMode()));

        onModeChanged.run();
        repaint();
    }

    public void onModeChanged(Runnable listener) {
        this.onModeChanged = listener == null ? () -> { } : listener;
    }

    /** @return the manual vertical factor; 1 is automatic */
    public double getStretch() {
        return stretch;
    }

    /** Back to the automatic vertical scale, centred, with no slide. */
    public void resetStretch() {
        stretch = 1.0;
        priceOffset = 0.0;

        repaint();
        onScaleChanged.run();
    }

    /**
     * Puts the chart back where it opens: newest bars, automatic scale, and the
     * ten per cent of air on the right.
     */
    public void centreChart() {
        stretch = 1.0;
        priceOffset = 0.0;
        rightMargin = birthMargin();
        firstBar = clampFirstBar(series.size() - visibleBars + rightMargin);

        repaint();
        onScaleChanged.run();
    }

    /**
     * Writes down everything about how this chart is being LOOKED AT.
     *
     * @param into where to write
     * @param prefix what to write it under
     *
     * <p>Not the data and not the indicators -- the view: how far in, how far
     * along, how stretched, how slid, and drawn how. Reopening a chart that
     * comes back at the default zoom is a chart that has to be set up again
     * every morning, and setting it up is most of the work.</p>
     */
    public void storeView(br.com.jorge.reis.endeavourneo.platform.Settings into, String prefix) {
        into.putInt(prefix + "visibleBars", visibleBars);
        into.putInt(prefix + "rightMargin", rightMargin);
        into.put(prefix + "stretch", String.valueOf(stretch));
        into.put(prefix + "priceOffset", String.valueOf(priceOffset));
        into.put(prefix + "period", periodCode);
        into.put(prefix + "style", style.code());

        // HOW FAR ALONG, which the javadoc above promises and this did not
        // write: a chart left in March 2021 came back on the last bar, and
        // setting one up again is most of the work.
        //
        // The distance from the END, not firstBar itself. The series grows
        // between one session and the next -- a night of minutes is 566 bars --
        // so a raw index would point somewhere else every morning.
        into.putInt(prefix + "fromEnd", Math.max(0, series.size() - firstBar));
    }

    /** Puts the chart back the way {@link #storeView} found it. */
    public void restoreView(br.com.jorge.reis.endeavourneo.platform.Settings from, String prefix) {
        PeriodCatalog.Choice period = PeriodCatalog.byCode(from.get(prefix + "period", null));

        if (period != null) {
            setPeriod(period.aggregation(), period.title(), period.code());
        }

        // BOTH SIDES. Only "line" used to call anything, so a canvas already
        // drawing a line -- one that is being docked after floating, which goes
        // through this again -- kept the line however "candle" was stored.
        setStyle(ChartStyles.byCode(from.get(prefix + "style", null)));

        stretch = readDouble(from, prefix + "stretch", 1.0, MINIMUM_STRETCH, MAXIMUM_STRETCH);
        priceOffset = clampOffset(readDouble(from, prefix + "priceOffset", 0.0,
                -AIR_VERTICAL, AIR_VERTICAL));

        if (series.size() == 0) {
            // THE SERIES HAS NOT ARRIVED. A chart OF a tick export is filled in
            // the background -- it says so of itself, "empty now, filled in the
            // background", and it takes four to eight seconds -- so this runs
            // against an empty series and every number below clamps to nothing:
            // the window collapses to the minimum and the position to zero. The
            // worker then calls setSeries, whose shortcut for "the size did not
            // change" cannot fire either, and the view lands at the default zoom
            // at the end of the series.
            //
            // Zoom, position, period and style, lost in silence for that whole
            // family of charts -- the exact opposite of what storeView promises.
            // So the request is kept and replayed by the first fold that brings
            // bars.
            wanted = from;
            wantedPrefix = prefix;

            return;
        }

        visibleBars = Math.max(MINIMUM_VISIBLE_BARS,
                Math.min(from.getInt(prefix + "visibleBars", visibleBars),
                        Math.max(MINIMUM_VISIBLE_BARS, series.size())));

        rightMargin = Math.max(0, from.getInt(prefix + "rightMargin", rightMargin));

        // Back to where the reader was, measured from the end. Absent -- a
        // workspace written before this was stored -- falls back to the end of
        // the series, which is where every chart used to reopen.
        int fromEnd = from.getInt(prefix + "fromEnd", visibleBars - rightMargin);

        firstBar = clampFirstBar(series.size() - Math.max(0, fromEnd));

        repaint();
        onScaleChanged.run();
    }

    /**
     * @return a stored decimal, clamped, or the fallback when it is not one
     *
     * <p>The file can be edited by hand, and a chart that refuses to open
     * because somebody typed a letter into it would be a poor trade for a
     * setting nobody would miss.</p>
     */
    private static double readDouble(br.com.jorge.reis.endeavourneo.platform.Settings from,
                                     String key, double fallback, double least, double most) {
        try {
            double value = Double.parseDouble(from.get(key, String.valueOf(fallback)));

            return Double.isFinite(value) ? Math.max(least, Math.min(value, most)) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int birthMargin() {
        return Math.max(1, (int) Math.round(visibleBars * BIRTH_MARGIN));
    }

    private double clampOffset(double candidate) {
        if (!Double.isFinite(candidate)) {
            return 0.0;
        }

        return Math.max(-AIR_VERTICAL, Math.min(candidate, AIR_VERTICAL));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            if (series.size() == 0 || getWidth() < 2 || getHeight() < 2) {
                return;
            }

            Viewport viewport = viewport();

            paintGrid(g, viewport);
            style.paint(g, series, viewport);
            paintOverlays(g, viewport);
            paintPriceAxis(g, viewport);
            paintTimeAxis(g, viewport);
            paintLastPrice(g, viewport);
            paintCrosshair(g, viewport);
            paintJumpButton(g);
            paintRuler(g, viewport);
            paintReadout(g, viewport);
        } finally {
            g.dispose();
        }
    }

    /**
     * Horizontal lines at round prices.
     *
     * <p>Round, not evenly spaced: a grid at 104.317 and 104.822 is arithmetic
     * showing through. The step is chosen from the 1-2-5 sequence, which is what
     * produces the numbers a reader expects to see on an axis.</p>
     */
    private void paintGrid(Graphics2D g, Viewport viewport) {
        if (!ChartPreferences.horizontalGrid()) {
            return;
        }

        double step = gridStep(viewport);

        g.setColor(ChartColors.grid());
        g.setStroke(THIN);

        double price = Math.ceil(viewport.lowestPrice() / step) * step;

        while (price <= viewport.highestPrice()) {
            int y = (int) Math.round(viewport.y(price));

            g.drawLine(0, y, getWidth() - AXIS_WIDTH, y);

            price += step;
        }
    }

    /**
     * The overlays, each as one polyline per value it reports.
     *
     * <p>Drawn after the price so a moving average sits on top of the candles,
     * which is where the eye expects it. Before them the candles would cover the
     * line at exactly the places it matters -- where price and average meet.</p>
     *
     * <p>A NaN breaks the line rather than being plotted as zero. During an
     * average's warm-up there is nothing to say, and a line dragged up from the
     * bottom of the chart to the first real value reads as a move that never
     * happened.</p>
     */
    private void paintOverlayLevels(Graphics2D g, Viewport viewport) {
        java.util.List<Integer> drawn = new java.util.ArrayList<>();


        for (Overlay overlay : overlays) {
            if (!overlay.isVisible()) {
                continue;
            }

            for (Overlay.Level level : overlay.levels()) {
                int at = (int) Math.round(viewport.y(level.at()));

                // Two indicators asking for the same line draw one line. The
                // study pane says the same thing and for the same reason.
                if (drawn.contains(at)) {
                    continue;
                }

                drawn.add(at);

                g.setColor(level.colour());
                g.setStroke(level.stroke());
                g.drawLine(viewport.bounds().x, at,
                        viewport.bounds().x + viewport.bounds().width, at);
            }
        }
    }

    private void paintOverlays(Graphics2D g, Viewport viewport) {
        int from = viewport.firstBar();
        int to = Math.min(viewport.lastBar(), series.size());

        Object previous = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        paintOverlayLevels(g, viewport);

        for (Overlay overlay : overlays) {
            if (!overlay.isVisible()) {
                continue;
            }

            // Areas first, so the lines land on top of whatever they shade.
            overlay.paintUnder(g, viewport, from, to);

            // Each indicator draws with its OWN stroke: thickness and dash are
            // settings now, and a single stroke set for all of them would make
            // every one of those settings do nothing.
            //
            // ONE PER LINE, and it used to be one for the whole indicator.
            // Overlay.strokes() exists precisely so an indicator can dress each
            // of its lines apart, and StudyPane honours it -- so the setting
            // worked in a pane and did nothing on the price chart. That is not
            // hypothetical any more: the Bollinger bands answer three strokes so
            // the middle line can carry the reader's own choice of style and
            // thickness, and the bands are drawn HERE.
            java.util.List<java.awt.Stroke> strokes = overlay.strokes();
            java.util.List<java.awt.Color> colours = overlay.colours();
            int lines = colours.size();

            // ONE valueAt PER BAR, and it used to be one per bar PER LINE. The
            // bar loop sat inside the line loop, and every implementation of
            // valueAt builds its answer -- MovingAverage returns `new
            // double[]{...}`, BollingerBands a `new double[3]` -- so a
            // three-line indicator over N visible bars allocated 3xN arrays
            // every frame where N would do. Allocating per element inside a
            // painting loop is the thing this file says in writing not to do,
            // and every chart opens with three averages on it.
            //
            // The line's own continuity is what forced the order: each line
            // remembers where its last point was, so with the bars outside they
            // have to be remembered side by side rather than one at a time.
            int[] lastX = new int[lines];
            int[] lastY = new int[lines];

            java.util.Arrays.fill(lastX, Integer.MIN_VALUE);

            for (int i = from; i < to; i++) {
                double[] row = overlay.valueAt(i);
                int x = (int) Math.round(viewport.x(i));

                for (int line = 0; line < lines; line++) {
                    if (line >= row.length || !Double.isFinite(row[line])) {
                        lastX[line] = Integer.MIN_VALUE;

                        continue;
                    }

                    int y = (int) Math.round(viewport.y(row[line]));

                    if (lastX[line] != Integer.MIN_VALUE) {
                        g.setStroke(line < strokes.size()
                                ? strokes.get(line) : overlay.stroke());
                        g.setColor(colours.get(line));
                        g.drawLine(lastX[line], lastY[line], x, y);
                    }

                    lastX[line] = x;
                    lastY[line] = y;
                }
            }

            // NOT stepped by barsPerColumn, unlike the candle loops below, and
            // that is a decision rather than an oversight. Those step because
            // they AGGREGATE: a column keeps the high and the low of every bar
            // that fell in it. A line has nothing to aggregate with, so sampling
            // one bar per column would quietly drop the range the indicator
            // covered inside that column -- and at the zoom where the saving
            // would matter, that range is most of what the line is showing.
        }

        if (previous != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previous);
        }
    }

    /**
     * The prices, down the reserved strip.
     *
     * <p>Same steps as the grid, so every line has its number and no number
     * floats without a line. Computing them twice from the same rule would
     * eventually drift apart when one of the two is edited.</p>
     */
    private void paintPriceAxis(Graphics2D g, Viewport viewport) {
        int left = getWidth() - AXIS_WIDTH;
        double step = gridStep(viewport);

        int bottom = getHeight() - axisHeight();

        g.setColor(ChartColors.background());
        g.fillRect(left, 0, AXIS_WIDTH, bottom);

        g.setColor(ChartColors.grid());
        g.drawLine(left, 0, left, bottom);

        g.setColor(ChartColors.foreground());
        g.setFont(axisFont());

        FontMetrics metrics = g.getFontMetrics();
        DecimalFormat format = formatFor(step);
        double price = Math.ceil(viewport.lowestPrice() / step) * step;

        while (price <= viewport.highestPrice()) {
            String text = format.format(price);
            int y = (int) Math.round(viewport.y(price));

            g.drawString(text, getWidth() - 6 - metrics.stringWidth(text),
                    y + metrics.getAscent() / 2 - 1);

            price += step;
        }
    }

    /**
     * The times, along the bottom, on round intervals.
     *
     * <p>A label lands on the first visible bar that crosses a step boundary,
     * which is what makes them fall on 14:00 and 14:30 rather than on whatever
     * bar happens to be there. Iterating the bars rather than the clock also
     * handles gaps for free: after a session break the next bar simply starts a
     * new boundary, and nothing is drawn over the hours the market was shut.</p>
     *
     * <p><b>A change of day is marked differently</b> — a stronger line and the
     * date instead of the time. On an intraday chart the session boundary is the
     * most important vertical there is, and a bare "09:00" after "18:00" makes
     * the reader work out for themselves that a night went by.</p>
     */
    private void paintTimeAxis(Graphics2D g, Viewport viewport) {
        int top = getHeight() - axisHeight();
        int step = timeStep(viewport);

        g.setColor(ChartColors.background());
        g.fillRect(0, top, getWidth(), axisHeight());

        g.setColor(ChartColors.grid());
        g.drawLine(0, top, getWidth(), top);
        g.drawLine(0, top + TIME_HEIGHT, getWidth(), top + TIME_HEIGHT);

        paintDayBand(g, viewport, top + TIME_HEIGHT);

        g.setFont(axisFont());

        FontMetrics metrics = g.getFontMetrics();
        ZoneId zone = br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone();

        int last = Integer.MIN_VALUE;
        long previousStep = Long.MIN_VALUE;
        ZonedDateTime previousTime = null;

        boolean byDays = axisSpeaksInDays(viewport);

        // A PIXEL COLUMN AT A TIME, not a bar: with the default window of a
        // hundred thousand bars this built a ZonedDateTime per bar per frame,
        // and the label is thrown away the moment it touches the previous one.
        // The two style classes already step this way, and Viewport.barsPerColumn
        // exists for exactly this.
        int perColumn = Math.max(1, viewport.barsPerColumn());

        for (int i = viewport.firstBar(); i < viewport.lastBar() && i < series.size();
                i += perColumn) {
            ZonedDateTime time = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone);
            long bucket = axisBucket(time, step);

            boolean newDay = previousTime != null
                    && !time.toLocalDate().equals(previousTime.toLocalDate());
            boolean boundary = previousStep != Long.MIN_VALUE && bucket != previousStep;

            previousStep = bucket;
            previousTime = time;

            if (!boundary && !newDay) {
                continue;
            }

            int x = (int) Math.round(viewport.x(i));
            String text = time.format(byDays ? DAY_OF_MONTH : CLOCK);
            int width = metrics.stringWidth(text);

            // Skip a label that would touch the previous one. Drawing both and
            // letting them overlap is the single thing that makes an axis look
            // broken.
            if (x - width / 2 < last + 8) {
                continue;
            }

            last = x + width / 2;

            // The boundary between one period and the next is DOTTED, and the
            // ordinary grid line solid. Both being solid made the separator
            // just a darker line among many; dotted, it reads as a division
            // rather than as another division of the same scale.
            java.awt.Stroke was = g.getStroke();

            if (newDay && !ChartPreferences.periodLine()) {
                // Asked for, and off by default. On a chart of a few hundred
                // bars the boundary falls often enough to become a second grid,
                // and a grid on top of the grid is noise however faint. The day
                // band underneath already says where the day changed, and says
                // it with a name instead of a line.
                g.setStroke(was);
            } else {
                if (newDay) {
                    g.setColor(ChartColors.foreground());
                    g.setStroke(BOUNDARY);
                    g.drawLine(x, 0, x, top);
                } else if (ChartPreferences.verticalGrid()) {
                    g.setColor(ChartColors.grid());
                    g.drawLine(x, 0, x, top);
                }

                g.setStroke(was);
            }

            g.setColor(ChartColors.foreground());
            g.drawString(text, x - width / 2, top + metrics.getAscent() + 3);
        }
    }

    /**
     * The day band: one label per day, centred over the bars of that day.
     *
     * <p>Centred over its own span rather than placed at the boundary, so the
     * label says "these bars are this day" instead of "the day changed here".
     * The divider between days carries the boundary; the label carries the
     * name.</p>
     */
    private void paintDayBand(Graphics2D g, Viewport viewport, int top) {
        ZoneId zone = br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone();

        g.setFont(axisFont());

        FontMetrics metrics = g.getFontMetrics();
        int limit = Math.min(viewport.lastBar(), series.size());
        int spanStart = viewport.firstBar();

        // One level up from whatever the row above is saying: days under hours,
        // months under days. A band repeating what the row already says is a
        // band that costs height and gives nothing.
        boolean byMonths = axisSpeaksInDays(viewport);

        java.time.LocalDate current = null;

        // Same reason as the time axis above: the band is dropped the moment
        // it is too narrow for its own name, so the work per bar is thrown away.
        int perColumn = Math.max(1, viewport.barsPerColumn());

        for (int i = viewport.firstBar(); i <= limit; i += perColumn) {
            java.time.LocalDate day = null;

            if (i < limit) {
                day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();

                if (byMonths) {
                    day = day.withDayOfMonth(1);
                }
            }

            if (current == null) {
                current = day;

                continue;
            }

            if (day != null && day.equals(current)) {
                continue;
            }

            drawDay(g, metrics, viewport, current, spanStart, i, top, byMonths);

            if (i < limit) {
                java.awt.Stroke was = g.getStroke();

                g.setColor(ChartColors.foreground());
                g.setStroke(BOUNDARY);
                g.drawLine((int) Math.round(viewport.x(i) - viewport.barWidth() / 2), top,
                        (int) Math.round(viewport.x(i) - viewport.barWidth() / 2),
                        top + DAY_HEIGHT);
                g.setStroke(was);
            }

            spanStart = i;
            current = day;
        }
    }

    /**
     * @return whether the axis should speak in days rather than in hours
     *
     * <p>Decided from what is ON SCREEN, not from the chosen period. A daily
     * chart is the obvious case, but a one-minute chart zoomed out to three
     * months wants dates too, and a renko has no fixed bar length to ask
     * about.</p>
     *
     * <p>The defect this fixes was reported from a daily chart: every bar being
     * a new day, the top row wrote a clock label for each of them -- a row of
     * 09:00, 09:03, 09:02, which is the minute the session happened to open and
     * says nothing -- while the band below, one narrow day per bar, had no room
     * for a single label and came out blank.</p>
     */
    boolean axisSpeaksInDaysFor(Viewport viewport) {
        return axisSpeaksInDays(viewport);
    }

    private boolean axisSpeaksInDays(Viewport viewport) {
        int first = viewport.firstBar();
        // No null check on the series: the line above already dereferenced it,
        // and the field is born PriceSeries.empty() and never assigned null.
        int last = Math.min(viewport.lastBar(), series.size()) - 1;

        if (last <= first) {
            return false;
        }

        return series.timeAt(last) - series.timeAt(first) > 2 * 24 * 60 * 60_000L;
    }

    private void drawDay(Graphics2D g, FontMetrics metrics, Viewport viewport,
                         java.time.LocalDate day, int from, int to, int top, boolean asMonth) {
        double left = viewport.x(from) - viewport.barWidth() / 2;
        double right = viewport.x(to - 1) + viewport.barWidth() / 2;

        String text = day.format(asMonth ? MONTH : DAY);
        int width = metrics.stringWidth(text);

        // A span too narrow for its own label is left blank. Drawing it anyway
        // would spill over the neighbouring days and make the band unreadable
        // exactly when the chart is zoomed out and the band matters most.
        if (right - left < width + 10) {
            return;
        }

        g.setColor(ChartColors.foreground());
        g.drawString(text, (int) Math.round((left + right) / 2 - width / 2.0),
                top + metrics.getAscent() + 2);
    }

    /**
     * @param time the bar's instant, already in the reader's zone
     * @param step the label interval in minutes, one of {@code TIME_STEPS}
     * @return which slot of that interval the bar falls in; a label is drawn
     *         wherever this number changes from one bar to the next
     *
     * <p><b>In the zone, never in UTC.</b> The obvious form is
     * {@code millis / (step * 60_000)}, and it was what stood here. It is wrong
     * for every step that a person names after a calendar: at the weekly step it
     * is exactly {@code epochDay / 7}, and since 1970-01-01 was a Thursday, the
     * weeks it drew began on Thursdays. {@code Timeframe.bucketOf} forbids that
     * in a comment, in the same words, for the same reason -- and the day band
     * painted directly beneath this axis already used the zone, so the two
     * disagreed inside one repaint.</p>
     *
     * <p><b>ASKED OF THE DOMAIN, not worked out again here.</b> Three branches
     * of arithmetic stood here, with a {@code DAY_MINUTES} of their own beside
     * them, and the paragraph above already said that {@code Timeframe.bucketOf}
     * forbids the epoch-anchored form "in a comment, in the same words, for the
     * same reason" -- and then wrote the third copy instead of calling the
     * first. Two truths about where a day ends means the next correction to one
     * of them does not reach the other, and this file has now been corrected
     * twice on exactly that subject.</p>
     *
     * <p>The copy also had an arithmetic difference of its own. Its multiplier
     * was {@code DAY_MINUTES / step}, an integer division: for a step that does
     * not divide 1.440 -- seven minutes, say -- the last slot of one day and the
     * first of the next came out with the SAME key, so no label was drawn at the
     * turn of the day. Unreachable on this market, where the session closes at
     * 18:25, and gone now either way: {@code bucketOf} multiplies by a fixed
     * 1.440 and cannot collide.</p>
     */
    static long axisBucket(ZonedDateTime time, int step) {
        return br.com.jorge.reis.endeavourneo.domain.market.Timeframe.ofMinutes(step)
                .bucketOf(time.toInstant().toEpochMilli(), time.getZone());
    }

    /**
     * @return the smallest round interval that does not crowd the labels
     *
     * <p>Derived from the bars actually visible rather than from the timeframe,
     * because the series does not say what timeframe it is -- and should not
     * have to.</p>
     */
    private int timeStep(Viewport viewport) {
        int first = viewport.firstBar();
        int lastBar = Math.min(viewport.lastBar(), series.size()) - 1;

        if (lastBar <= first) {
            return TIME_STEPS[0];
        }

        long minutes = (series.timeAt(lastBar) - series.timeAt(first)) / 60_000L;

        return niceTimeStep(minutes, Math.max(1, plotBounds().width / TIME_SPACING));
    }

    /**
     * @param spanMinutes how much time the visible bars cover
     * @param wantedLabels how many labels fit across the width
     * @return the smallest round interval that keeps the count at or under that
     *
     * <p>Separate from the painting so it can be checked without a window: the
     * part that can be wrong is which interval gets chosen, not the drawing.</p>
     */
    static int niceTimeStep(long spanMinutes, int wantedLabels) {
        long target = Math.max(1, spanMinutes / Math.max(1, wantedLabels));

        for (int step : TIME_STEPS) {
            if (step >= target) {
                return step;
            }
        }

        return TIME_STEPS[TIME_STEPS.length - 1];
    }

    /**
     * The last close, as a filled tag on the price axis.
     *
     * <p>It answers the question the chart is opened for -- what is it worth --
     * without the reader tracing a grid line across with their eye. Every
     * terminal has it, and its absence is felt immediately.</p>
     *
     * <p><b>The last VISIBLE bar, not the last bar of the series.</b> Scrolled
     * back to 2021 the tag reads a price of 2021, and that is the honest answer
     * for a chart of 2021: the alternative is a number floating over a chart it
     * does not belong to. The javadoc used to say "what is it worth NOW", which
     * is the one reading it does not give.</p>
     */
    private void paintLastPrice(Graphics2D g, Viewport viewport) {
        int index = Math.min(viewport.lastBar(), series.size()) - 1;

        if (index < 0) {
            return;
        }

        double price = series.closeAt(index);
        int y = (int) Math.round(viewport.y(price));

        if (y < 0 || y > getHeight() - axisHeight()) {
            // The last bar is scrolled out of the visible price range. Drawing
            // the tag clamped to an edge would claim a price that is not there.
            return;
        }

        g.setFont(axisFontBold());

        FontMetrics metrics = g.getFontMetrics();
        String text = formatFor(gridStep(viewport)).format(price);
        int width = metrics.stringWidth(text);
        int height = metrics.getHeight();
        int left = getWidth() - AXIS_WIDTH + 1;

        g.setColor(series.closeAt(index) >= series.openAt(index)
                ? ChartColors.up() : ChartColors.down());
        g.fillRect(left, y - height / 2, AXIS_WIDTH - 1, height);

        g.setColor(ChartColors.background());
        g.drawString(text, getWidth() - 6 - width, y - height / 2 + metrics.getAscent());
    }

    /**
     * The ruler: a line between the two points, with a box of what it measured.
     *
     * <p>Round handles on both ends. Without them the line is ambiguous about
     * where it actually starts and finishes, which matters because the numbers
     * are read against those points and not against the line.</p>
     */
    private void paintRuler(Graphics2D g, Viewport viewport) {
        if (measurement == null) {
            return;
        }

        int x1 = (int) Math.round(viewport.x(measurement.fromBar()));
        int y1 = (int) Math.round(viewport.y(measurement.fromPrice()));
        int x2 = (int) Math.round(viewport.x(measurement.toBar()));
        int y2 = (int) Math.round(viewport.y(measurement.toPrice()));

        Object previous = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(ChartColors.foreground());
        g.setStroke(ROUNDED);
        g.drawLine(x1, y1, x2, y2);

        g.fillOval(x1 - 3, y1 - 3, 6, 6);
        g.fillOval(x2 - 3, y2 - 3, 6, 6);

        if (previous != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previous);
        }

        RulerReadout.paint(g, measurement, new java.awt.Point(x2, y2),
                new Rectangle(0, 0, getWidth(), getHeight()));
    }

    /**
     * The summary of the bar under the cursor.
     *
     * <p>Painted last so nothing covers it, and only while the mouse is over the
     * plot -- not over the axes, where there is no bar to describe.</p>
     */
    private void paintReadout(Graphics2D g, Viewport viewport) {
        // While measuring, the ruler box already occupies the corner and says
        // more. Two boxes chasing the same cursor is one too many.
        if (mode == Mode.MEASURE || cursor == null || onAxis(cursor.x) || onTimeAxis(cursor.y)) {
            return;
        }

        int bar = barUnder(viewport, cursor.x, cursor.y);

        if (bar < 0) {
            return;
        }

        BarReadout.paint(g, series, bar, cursor,
                new Rectangle(0, 0, getWidth(), getHeight()),
                formatFor(gridStep(viewport)));
    }

    /**
     * @return the bar the cursor is actually ON, or -1 when it is over empty space
     *
     * <p><b>Different from {@link Viewport#barAt} on purpose.</b> That one asks
     * "which column is this", clamps, and always answers — right for a crosshair,
     * which follows the mouse everywhere. This one asks "is the pointer on the
     * drawing", and says no over the empty space above and below the candle. A
     * readout that appears anywhere in the plot is on screen permanently, which
     * is the same as not being a readout at all.</p>
     *
     * <p>The vertical tolerance is not politeness: a wick is one pixel wide and a
     * doji's body is one pixel tall. Demanding an exact hit would make the box
     * almost impossible to summon on the bars where it is most wanted.</p>
     */
    private int barUnder(Viewport viewport, int x, int y) {
        int bar = viewport.barAt(x);

        if (bar < 0 || bar >= series.size()) {
            return -1;
        }

        // Horizontally: inside the column the bar owns.
        if (Math.abs(x - viewport.x(bar)) > Math.max(2.0, viewport.barWidth() / 2.0)) {
            return -1;
        }

        double top = viewport.y(series.highAt(bar)) - HIT_TOLERANCE;
        double bottom = viewport.y(series.lowAt(bar)) + HIT_TOLERANCE;

        return y >= top && y <= bottom ? bar : -1;
    }

    /**
     * The button that returns to the newest bars.
     *
     * <p>Once panning can take the chart years into the past, getting back is a
     * long drag with no landmark. Every terminal has this, and its absence is
     * noticed the first time someone scrolls too far.</p>
     */
    private void paintJumpButton(Graphics2D g) {
        Rectangle where = jumpBounds();

        if (where == null) {
            return;
        }

        Object previous = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g.setColor(ChartColors.grid());
        g.fillOval(where.x, where.y, where.width, where.height);

        g.setColor(ChartColors.foreground());
        g.setStroke(THICK_ROUNDED);

        int cx = where.x + where.width / 2;
        int cy = where.y + where.height / 2;

        g.drawLine(cx - 3, cy - 5, cx + 3, cy);
        g.drawLine(cx + 3, cy, cx - 3, cy + 5);

        if (previous != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previous);
        }
    }

    /**
     * @param step the distance between grid lines
     * @return a format with just enough decimals for that step
     *
     * <p>A step of 500 needs none; a step of 0,05 needs two. Fixing the decimals
     * would either print 177.600,00 on an index or round a currency pair to
     * uselessness.</p>
     *
     * <p><b>Kept, one per number of decimals.</b> There are seven possible
     * answers -- nought to six -- and this built a new {@code DecimalFormat}
     * every time it was asked, which is three times a frame: the price axis, the
     * last-price tag and the cursor's label. A DecimalFormat parses its pattern
     * and builds its symbols on construction, inside the painting loop this file
     * spends comments telling the reader not to allocate in.</p>
     *
     * <p>The map is not synchronised because painting is the interface thread's
     * and nothing else asks. {@code DecimalFormat} is not thread-safe, and this
     * is the reason that is fine.</p>
     */
    static DecimalFormat formatFor(double step) {
        int decimals = step >= 1.0 ? 0 : (int) Math.min(6, Math.ceil(-Math.log10(step)));

        return FORMATS.computeIfAbsent(decimals, places -> {
            StringBuilder pattern = new StringBuilder("#,##0");

            if (places > 0) {
                pattern.append('.');
                pattern.append("0".repeat(places));
            }

            return new DecimalFormat(pattern.toString(),
                    DecimalFormatSymbols.getInstance(Locale.getDefault()));
        });
    }

    /** @see #formatFor(double) */
    private static final java.util.Map<Integer, DecimalFormat> FORMATS =
            new java.util.HashMap<>();

    double gridStep(Viewport viewport) {
        double span = viewport.highestPrice() - viewport.lowestPrice();

        return niceStep(span * GRID_SPACING / Math.max(1, getHeight()));
    }

    /** @return the closest 1, 2 or 5 times a power of ten at or above {@code target} */
    static double niceStep(double target) {
        if (!(target > 0.0)) {
            return 1.0;
        }

        double magnitude = Math.pow(10, Math.floor(Math.log10(target)));
        double normalised = target / magnitude;

        if (normalised <= 1.0) {
            return magnitude;
        }
        if (normalised <= 2.0) {
            return 2.0 * magnitude;
        }
        if (normalised <= 5.0) {
            return 5.0 * magnitude;
        }

        return 10.0 * magnitude;
    }

    /**
     * The crosshair, snapped to the centre of the bar under the mouse.
     *
     * <p>Snapped rather than free: a vertical line between two candles invites
     * the reader to attribute a price to a bar that is not there. Landing it on
     * the bar's centre says which bar is being read.</p>
     */
    private void paintCrosshair(Graphics2D g, Viewport viewport) {
        if (cursor == null || onAxis(cursor.x) || onTimeAxis(cursor.y)) {
            // THE SAME GUARD paintReadout has, and for the same reason. Over the
            // price strip there is no bar under the pointer: barAt clamps, so
            // the cross jumped to the last visible bar and the label beside it
            // named a price the pointer was not on.
            return;
        }

        int bar = viewport.barAt(cursor.x);
        int x = (int) Math.round(viewport.x(bar));

        g.setColor(ChartColors.grid());
        g.setStroke(DASHED);

        g.drawLine(x, 0, x, getHeight() - axisHeight());
        g.drawLine(0, cursor.y, getWidth() - AXIS_WIDTH, cursor.y);

        paintCursorTags(g, viewport, bar, x);
    }

    /**
     * The price and the time the cursor is on, written on the two axes.
     *
     * <p>Without them the crosshair says "here" and nothing else: reading a
     * level off it means following the line across to the axis and guessing
     * between two labels. These put the number where the eye already is.</p>
     *
     * <p>They are painted <b>over</b> the axis labels rather than beside them.
     * A tag that made room for itself would push the whole scale about as the
     * mouse moved, and a scale that moves is not a scale.</p>
     */
    private void paintCursorTags(Graphics2D g, Viewport viewport, int bar, int x) {
        g.setStroke(THIN);
        g.setFont(br.com.jorge.reis.endeavourneo.platform.Appearance.monospaced(11));

        FontMetrics metrics = g.getFontMetrics();

        // The price, on the vertical strip.
        // The same rounding the axis beside it uses: two labels of the same
        // price that disagree in the last digit read as a bug in the chart.
        String price = formatFor(gridStep(viewport)).format(viewport.priceAt(cursor.y));
        int height = metrics.getHeight() + 2;
        int top = Math.max(0, Math.min(cursor.y - height / 2,
                getHeight() - axisHeight() - height));

        tag(g, metrics, price, getWidth() - AXIS_WIDTH + 3, top, AXIS_WIDTH - 6);

        // The time, on the horizontal band. Bars beyond the end of the series
        // have no time to show -- the chart is scrolled into the air past the
        // last one -- and an invented label there would be a claim.
        if (bar < 0 || bar >= series.size()) {
            return;
        }

        String moment = java.time.Instant.ofEpochMilli(series.timeAt(bar))
                .atZone(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone())
                .format(CURSOR_TIME);

        int width = metrics.stringWidth(moment) + 10;
        int left = Math.max(0, Math.min(x - width / 2, getWidth() - AXIS_WIDTH - width));

        tag(g, metrics, moment, left, getHeight() - axisHeight() + 1, width);
    }

    /** A filled box with the text in it, in the accent used by the last-price tag. */
    private void tag(Graphics2D g, FontMetrics metrics, String text, int x, int y, int width) {
        g.setColor(ChartColors.foreground());
        g.fillRect(x, y, width, metrics.getHeight() + 2);

        g.setColor(ChartColors.background());
        g.drawString(text, x + (width - metrics.stringWidth(text)) / 2,
                y + metrics.getAscent() + 1);
    }

    /** Wheel zooms around the cursor; dragging pans. */
    private final class Mouse extends MouseAdapter {

        private int grabbedAt = -1;

        private int grabbedFirstBar;

        private int grabbedY = -1;

        private double grabbedOffset;

        /** Where a scale drag started, and the factor it started from. */
        private int scalingFrom = -1;

        private double scalingBase;

        /** Where a time drag started, and the bar count it started from. */
        private int timingFrom = -1;

        private int timingBase;

        @Override
        public void mouseMoved(MouseEvent e) {
            cursor = e.getPoint();
            onCursorChanged.run();

            // The cursor is the only thing that says the strip is interactive.
            // Without it the area reads as decoration and nobody discovers it.
            setCursor(Cursor.getPredefinedCursor(cursorFor(e.getX(), e.getY())));

            repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            cursor = null;
            onCursorChanged.run();

            repaint();
        }

        private int cursorFor(int x, int y) {
            Rectangle jump = jumpBounds();

            if (jump != null && jump.contains(x, y)) {
                return Cursor.HAND_CURSOR;
            }
            if (!onAxis(x) && !onTimeAxis(y)) {
                return cursorForMode();
            }
            if (onTimeAxis(y) && !onAxis(x)) {
                return Cursor.E_RESIZE_CURSOR;
            }
            if (onAxis(x) && !onTimeAxis(y)) {
                return Cursor.N_RESIZE_CURSOR;
            }

            return Cursor.DEFAULT_CURSOR;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            // THE LEFT BUTTON ONLY. installContextMenu registers a second
            // adapter for the popup trigger, and JPopupMenu.show does not stop
            // the event reaching this one: in measuring mode, a right click to
            // open the menu wiped the measurement, and outside it the right
            // button still armed the drag.
            if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                return;
            }

            Rectangle jump = jumpBounds();

            if (jump != null && jump.contains(e.getPoint())) {
                goToEnd();

                return;
            }

            if (onTimeAxis(e.getY()) && !onAxis(e.getX())) {
                timingFrom = e.getX();
                timingBase = visibleBars;
                scalingFrom = -1;
                grabbedAt = -1;

                return;
            }

            timingFrom = -1;

            if (mode == Mode.MEASURE && !onAxis(e.getX()) && !onTimeAxis(e.getY())) {
                Viewport viewport = viewport();

                rulerBar = viewport.barAt(e.getX());
                rulerPrice = viewport.priceAt(e.getY());
                measurement = null;
                grabbedAt = -1;
                scalingFrom = -1;

                repaint();

                return;
            }

            if (onAxis(e.getX()) && !onTimeAxis(e.getY())) {
                // A drag that starts on the strip scales and never pans, even
                // when it wanders over the plot. Deciding by where the mouse IS
                // rather than where the drag BEGAN would switch behaviour
                // mid-gesture.
                scalingFrom = e.getY();
                scalingBase = stretch;
                grabbedAt = -1;

                return;
            }

            scalingFrom = -1;
            grabbedAt = e.getX();
            grabbedFirstBar = firstBar;
            grabbedY = e.getY();
            grabbedOffset = priceOffset;
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                // A double right click used to recentre the chart, throwing the
                // reader zoom away on the way to a context menu.
                return;
            }

            // Two clicks on the plot put everything back: scale, slide and
            // position. It is the way out of any arrangement the reader has got
            // themselves into, and it needs no button and no menu.
            if (e.getClickCount() == 2 && !onAxis(e.getX()) && !onTimeAxis(e.getY())
                    && (jumpBounds() == null || !jumpBounds().contains(e.getPoint()))) {
                centreChart();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            // THE SAME BUTTON THE PRESS ASKED ABOUT. This was the only one of
            // the four handlers without the check, so a right-click released
            // over the plot cleared the drag state of a gesture the LEFT button
            // was still making -- and a ruler being measured vanished for a
            // gesture that has nothing to do with it.
            //
            // getButton, and not the isLeftMouseButton the three siblings use:
            // that one reads the down-mask, and on a RELEASE the button being
            // released is already up. A release carries which button it was in
            // getButton and nowhere else.
            if (e.getButton() != MouseEvent.BUTTON1) {
                return;
            }

            scalingFrom = -1;
            timingFrom = -1;
            grabbedAt = -1;
            rulerBar = -1;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (rulerBar >= 0) {
                Viewport viewport = viewport();

                measurement = Measurement.between(series, rulerBar, rulerPrice,
                        viewport.barAt(e.getX()), viewport.priceAt(e.getY()));
                cursor = e.getPoint();
            onCursorChanged.run();

                repaint();

                return;
            }

            if (timingFrom >= 0) {
                // The newest visible bar stays put while the count changes.
                // Anchoring on the left instead would walk the chart away from
                // the present, which is where the reader almost always is.
                int end = firstBar + visibleBars;

                visibleBars = barsForDrag(timingBase, e.getX() - timingFrom, series.size());
                firstBar = clampFirstBar(end - visibleBars);

                repaint();

                return;
            }

            if (scalingFrom >= 0) {
                stretch = stretchForDrag(scalingBase, e.getY() - scalingFrom);

                repaint();
                onScaleChanged.run();

                return;
            }

            if (grabbedAt < 0 || series.size() == 0) {
                return;
            }

            // Panning moves by BARS, not pixels: at four pixels per bar a
            // ten-pixel drag must move two bars and a half, not ten.
            double perBar = (double) plotBounds().width / visibleBars;
            int moved = (int) Math.round((grabbedAt - e.getX()) / perBar);

            firstBar = clampFirstBar(grabbedFirstBar + moved);

            // Dragging down slides the price window up, which draws the bars
            // lower: the content follows the hand, which is the only direction
            // that ever feels right.
            int height = Math.max(1, plotBounds().height);
            int dy = e.getY() - grabbedY;

            // A DEAD ZONE before the price starts sliding. Every sideways drag
            // carries a few pixels of vertical wobble, and without this the
            // chart left automatic scale -- and the toolbar untoggled -- every
            // time somebody scrolled left. Reported from the screen exactly
            // that way.
            //
            // The zone is subtracted rather than jumped over, so the slide
            // starts from nothing instead of leaping the moment it is crossed.
            int slack = Math.abs(dy) <= VERTICAL_SLACK ? 0
                    : dy - Integer.signum(dy) * VERTICAL_SLACK;

            priceOffset = clampOffset(grabbedOffset + slack / (double) height);
            cursor = e.getPoint();
            onCursorChanged.run();

            onScaleChanged.run();

            repaint();
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (series.size() == 0) {
                return;
            }

            if (e.isShiftDown()) {
                // Shift plus wheel is what TradingView and MetaTrader use for
                // the vertical scale, so the hand already knows it.
                stretch *= e.getWheelRotation() > 0 ? 0.85 : 1.0 / 0.85;
                stretch = Math.max(MINIMUM_STRETCH, Math.min(stretch, MAXIMUM_STRETCH));

                repaint();
                onScaleChanged.run();

                return;
            }

            int anchor = viewport().barAt(e.getX());
            int zoomed = (int) Math.round(visibleBars * (e.getWheelRotation() > 0 ? 1.25 : 0.8));

            visibleBars = Math.max(MINIMUM_VISIBLE_BARS, Math.min(zoomed, series.size()));
            firstBar = clampFirstBar(firstBarForZoom(anchor, e.getX(),
                    plotBounds().width, visibleBars));

            repaint();
        }

        private int clampFirstBar(int candidate) {
            int clamped = ChartCanvas.this.clampFirstBar(candidate);

            // Remember how much air the reader left, so it survives the next
            // bar arriving. Zero while scrolled back into history: air is only
            // air when it is past the end.
            rightMargin = Math.max(0, clamped + visibleBars - Math.max(1, series.size()));

            return clamped;
        }
    }
}
