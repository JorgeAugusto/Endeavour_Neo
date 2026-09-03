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

import br.com.jorge.reis.endeavourneo.ui.chart.style.CandleStyle;

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
 *   axes         prices on the right, times along the bottom
 *   crosshair    where the mouse is
 * </pre>
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

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM");

    /** Diameter of the button that jumps back to the newest bars. */
    private static final int JUMP_SIZE = 26;

    /** How far it sits from the bottom-right corner of the plot. */
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

    /** The overlays drawn on the price, in the order they were added. */
    private final transient java.util.List<Overlay> overlays = new java.util.ArrayList<>();

    /**
     * Told when the overlays change in a way that should be WRITTEN DOWN.
     *
     * <p>Separate from {@link #onOverlaysRedrawn} because the two questions are
     * not the same one, and answering them together was a bug: applying a layout
     * has to redraw everything showing the overlays, and must NOT be written
     * down -- the layout bar would capture back what it has just applied.</p>
     */
    private transient Runnable onOverlaysChanged = () -> { };

    /** Told when the overlays change in a way that has to be REDRAWN. */
    private transient Runnable onOverlaysRedrawn = () -> { };

    private int firstBar;

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

    private transient Mode mode = Mode.PAN;

    /** Told when the mode changes, so a menu can tick the right entry. */
    private transient Runnable onModeChanged = () -> { };

    /** The measurement being drawn, or the last one, until cleared. */
    private transient Measurement measurement;

    /** Where the ruler drag started, in bar and price. */
    private int rulerBar = -1;

    private double rulerPrice;

    /** Whether Control has been held with no other key since it went down. */
    private transient boolean controlAlone;

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

    private javax.swing.JPopupMenu buildContextMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();

        javax.swing.JMenuItem insert =
                new javax.swing.JMenuItem(Messages.get("overlay.insertItem"));

        insert.addActionListener(e -> {
            Overlay overlay = InsertOverlayDialog.ask(
                    javax.swing.SwingUtilities.getWindowAncestor(this));

            addOverlay(overlay);
        });

        menu.add(insert);

        javax.swing.JMenu remove = new javax.swing.JMenu(Messages.get("overlay.removeItem"));

        // Disabled rather than hidden when there is nothing to remove. Hidden,
        // the reader looks for it where it is not; disabled, they see it exists
        // and infer the condition.
        remove.setEnabled(!overlays.isEmpty());

        for (Overlay overlay : overlays) {
            javax.swing.JMenuItem entry = new javax.swing.JMenuItem(
                    Messages.get(overlay.nameKey()) + " " + overlay.parameters());

            entry.addActionListener(e -> removeOverlay(overlay));
            remove.add(entry);
        }

        menu.add(remove);

        return menu;
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
        java.awt.KeyEventDispatcher dispatcher = event -> {
            if (event.getKeyCode() != java.awt.event.KeyEvent.VK_CONTROL) {
                if (event.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                    controlAlone = false;
                }

                return false;
            }

            if (event.getID() == java.awt.event.KeyEvent.KEY_PRESSED) {
                controlAlone = true;
            } else if (event.getID() == java.awt.event.KeyEvent.KEY_RELEASED && controlAlone) {
                controlAlone = false;

                // Only the window this canvas is in. Otherwise every chart on
                // screen would flip together.
                if (isShowing() && javax.swing.SwingUtilities.getWindowAncestor(this) != null
                        && javax.swing.SwingUtilities.getWindowAncestor(this).isActive()) {
                    toggleMode();
                }
            }

            return false;
        };

        addHierarchyListener(event -> {
            java.awt.KeyboardFocusManager keyboard =
                    java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager();

            if (isDisplayable()) {
                keyboard.removeKeyEventDispatcher(dispatcher);
                keyboard.addKeyEventDispatcher(dispatcher);
            } else {
                keyboard.removeKeyEventDispatcher(dispatcher);
            }
        });
    }

    /** @param newSeries the data to draw; showing the most recent bars */
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

    /** @param listener told when the overlays change and should be stored */
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

    /** @param overlay something drawn on the price; calculated immediately */
    public void addOverlay(Overlay overlay) {
        if (overlay == null) {
            return;
        }

        overlay.calculate(series);
        overlays.add(overlay);

        repaint();
        overlaysChanged();
    }

    /** @return the overlays, for the legend and the show/hide toggles */
    public java.util.List<Overlay> overlays() {
        return java.util.List.copyOf(overlays);
    }

    public void setSeries(PriceSeries newSeries) {
        this.series = newSeries == null ? PriceSeries.empty() : newSeries;

        // Recalculated here, not lazily on the next paint: an overlay still
        // holding values from the previous series would draw a line that
        // belongs to other data, and it would look plausible.
        for (Overlay overlay : overlays) {
            overlay.calculate(this.series);
        }
        this.visibleBars = Math.min(DEFAULT_VISIBLE_BARS, Math.max(1, this.series.size()));
        this.firstBar = Math.max(0, this.series.size() - visibleBars);

        repaint();
    }

    public void setStyle(ChartStyle newStyle) {
        if (newStyle != null) {
            this.style = newStyle;

            repaint();
        }
    }

    public ChartStyle getStyle() {
        return style;
    }

    /** @return the bar under the mouse, or -1 when the mouse is elsewhere */
    public int hoveredBar() {
        if (cursor == null || series.size() == 0) {
            return -1;
        }

        return viewport().barAt(cursor.x);
    }

    private Viewport viewport() {
        return Viewport.of(series, plotBounds(), firstBar, visibleBars, stretch);
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

    /** Scrolls back to the newest bars. */
    public void goToEnd() {
        firstBar = Math.max(0, series.size() - visibleBars);

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

    /** Flips between moving and measuring. */
    public void toggleMode() {
        setMode(mode == Mode.PAN ? Mode.MEASURE : Mode.PAN);
    }

    public void onModeChanged(Runnable listener) {
        this.onModeChanged = listener == null ? () -> { } : listener;
    }

    /** @return the manual vertical factor; 1 is automatic */
    public double getStretch() {
        return stretch;
    }

    /** Back to the automatic vertical scale. */
    public void resetStretch() {
        stretch = 1.0;

        repaint();
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
        double step = gridStep(viewport);

        g.setColor(ChartColors.grid());
        g.setStroke(new BasicStroke(1.0f));

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
    private void paintOverlays(Graphics2D g, Viewport viewport) {
        int from = viewport.firstBar();
        int to = Math.min(viewport.lastBar(), series.size());

        g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        Object previous = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (Overlay overlay : overlays) {
            if (!overlay.isVisible()) {
                continue;
            }

            java.util.List<java.awt.Color> colours = overlay.colours();
            int lines = colours.size();

            for (int line = 0; line < lines; line++) {
                g.setColor(colours.get(line));

                int lastX = Integer.MIN_VALUE;
                int lastY = 0;

                for (int i = from; i < to; i++) {
                    double[] row = overlay.valueAt(i);

                    if (line >= row.length || !Double.isFinite(row[line])) {
                        lastX = Integer.MIN_VALUE;

                        continue;
                    }

                    int x = (int) Math.round(viewport.x(i));
                    int y = (int) Math.round(viewport.y(row[line]));

                    if (lastX != Integer.MIN_VALUE) {
                        g.drawLine(lastX, lastY, x, y);
                    }

                    lastX = x;
                    lastY = y;
                }
            }
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
        g.setFont(getFont().deriveFont(11f));

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

        g.setFont(getFont().deriveFont(11f));

        FontMetrics metrics = g.getFontMetrics();
        ZoneId zone = ZoneId.systemDefault();

        int last = Integer.MIN_VALUE;
        long previousStep = Long.MIN_VALUE;
        ZonedDateTime previousTime = null;

        for (int i = viewport.firstBar(); i < viewport.lastBar() && i < series.size(); i++) {
            ZonedDateTime time = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone);
            long bucket = series.timeAt(i) / (step * 60_000L);

            boolean newDay = previousTime != null
                    && !time.toLocalDate().equals(previousTime.toLocalDate());
            boolean boundary = previousStep != Long.MIN_VALUE && bucket != previousStep;

            previousStep = bucket;
            previousTime = time;

            if (!boundary && !newDay) {
                continue;
            }

            int x = (int) Math.round(viewport.x(i));
            String text = time.format(CLOCK);
            int width = metrics.stringWidth(text);

            // Skip a label that would touch the previous one. Drawing both and
            // letting them overlap is the single thing that makes an axis look
            // broken.
            if (x - width / 2 < last + 8) {
                continue;
            }

            last = x + width / 2;

            g.setColor(newDay ? ChartColors.foreground() : ChartColors.grid());
            g.drawLine(x, 0, x, top);

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
        ZoneId zone = ZoneId.systemDefault();

        g.setFont(getFont().deriveFont(11f));

        FontMetrics metrics = g.getFontMetrics();
        int limit = Math.min(viewport.lastBar(), series.size());
        int spanStart = viewport.firstBar();

        java.time.LocalDate current = null;

        for (int i = viewport.firstBar(); i <= limit; i++) {
            java.time.LocalDate day = i < limit
                    ? Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate()
                    : null;

            if (current == null) {
                current = day;

                continue;
            }

            if (day != null && day.equals(current)) {
                continue;
            }

            drawDay(g, metrics, viewport, current, spanStart, i, top);

            if (i < limit) {
                g.setColor(ChartColors.foreground());
                g.drawLine((int) Math.round(viewport.x(i) - viewport.barWidth() / 2), top,
                        (int) Math.round(viewport.x(i) - viewport.barWidth() / 2),
                        top + DAY_HEIGHT);
            }

            spanStart = i;
            current = day;
        }
    }

    private void drawDay(Graphics2D g, FontMetrics metrics, Viewport viewport,
                         java.time.LocalDate day, int from, int to, int top) {
        double left = viewport.x(from) - viewport.barWidth() / 2;
        double right = viewport.x(to - 1) + viewport.barWidth() / 2;

        String text = day.format(DAY);
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
     * <p>It answers the question the chart is opened for -- what is it worth
     * now -- without the reader tracing a grid line across with their eye. Every
     * terminal has it, and its absence is felt immediately.</p>
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

        g.setFont(getFont().deriveFont(java.awt.Font.BOLD, 11f));

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
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
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
                new Rectangle(0, 0, getWidth(), getHeight()));
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
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

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
     */
    private static DecimalFormat formatFor(double step) {
        int decimals = step >= 1.0 ? 0 : (int) Math.min(6, Math.ceil(-Math.log10(step)));

        StringBuilder pattern = new StringBuilder("#,##0");

        if (decimals > 0) {
            pattern.append('.');
            pattern.append("0".repeat(decimals));
        }

        return new DecimalFormat(pattern.toString(),
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
    }

    private double gridStep(Viewport viewport) {
        double span = viewport.highestPrice() - viewport.lowestPrice();

        return niceStep(span * GRID_SPACING / Math.max(1, getHeight()));
    }

    /** @return the closest 1, 2 or 5 times a power of ten at or above {@code target} */
    private static double niceStep(double target) {
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
        if (cursor == null) {
            return;
        }

        int bar = viewport.barAt(cursor.x);
        int x = (int) Math.round(viewport.x(bar));

        g.setColor(ChartColors.grid());
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1.0f, new float[]{3.0f, 3.0f}, 0.0f));

        g.drawLine(x, 0, x, getHeight() - axisHeight());
        g.drawLine(0, cursor.y, getWidth() - AXIS_WIDTH, cursor.y);
    }

    /** Wheel zooms around the cursor; dragging pans. */
    private final class Mouse extends MouseAdapter {

        private int grabbedAt = -1;

        private int grabbedFirstBar;

        /** Where a scale drag started, and the factor it started from. */
        private int scalingFrom = -1;

        private double scalingBase;

        /** Where a time drag started, and the bar count it started from. */
        private int timingFrom = -1;

        private int timingBase;

        @Override
        public void mouseMoved(MouseEvent e) {
            cursor = e.getPoint();

            // The cursor is the only thing that says the strip is interactive.
            // Without it the area reads as decoration and nobody discovers it.
            setCursor(Cursor.getPredefinedCursor(cursorFor(e.getX(), e.getY())));

            repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            cursor = null;

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
        }

        @Override
        public void mouseReleased(MouseEvent e) {
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
            cursor = e.getPoint();

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

                return;
            }

            // The bar under the cursor stays under the cursor. Zooming around
            // the centre instead makes the reader chase what they were looking
            // at, and it is the single thing that most makes a chart feel wrong.
            int anchor = viewport().barAt(e.getX());
            double share = (e.getX() - 0.0) / Math.max(1, getWidth());

            int zoomed = (int) Math.round(visibleBars * (e.getWheelRotation() > 0 ? 1.25 : 0.8));

            visibleBars = Math.max(MINIMUM_VISIBLE_BARS, Math.min(zoomed, series.size()));
            firstBar = clampFirstBar((int) Math.round(anchor - share * visibleBars));

            repaint();
        }

        private int clampFirstBar(int candidate) {
            return Math.max(0, Math.min(candidate, Math.max(0, series.size() - visibleBars)));
        }
    }
}
