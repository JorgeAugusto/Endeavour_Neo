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
package br.com.jorge.reis.endeavourneo.ui.chart.study;

import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartColors;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.JComponent;

/**
 * A strip under the chart holding one or more indicators on a shared scale.
 *
 * <h2>It borrows the chart's horizontal, and nothing else</h2>
 *
 * <p>The x of a bar comes from the chart's own {@link Viewport}, so a peak here
 * sits exactly under the candle that made it — scrolling, zooming and the
 * right-hand price strip all line up without either side being told. The y is
 * this pane's alone, from the range of whatever is inside it.</p>
 *
 * <h2>One scale, several indicators</h2>
 *
 * <p>A pane has no owner. It can hold the same indicator read at several
 * scales — a stochastic on the chart's own bars beside a stochastic on five
 * minutes — or different indicators that happen to share a range, a stochastic
 * beside an RSI. What it must not hold is two things measured in different
 * units, and that is decided before anything gets in here: see
 * {@link StudyStack#fits}.</p>
 *
 * <p>The vertical range is the <b>union</b> of what is inside. With everything
 * agreeing on nought to a hundred that union is nought to a hundred, which is
 * the case this exists for; the union is what keeps the pane honest if
 * anything ever slips past the check.</p>
 *
 * <h2>The header is a legend</h2>
 *
 * <p>With three indicators in a pane the title bar has to name three, show
 * three sets of values under the cursor, and offer settings and close for each
 * — the pane's own buttons cannot stand for any of them. Those appear on the
 * entry under the pointer, in a slot that is <b>always reserved</b>: on a
 * horizontal strip, revealing them inline would shove everything to their
 * right sideways every time the pointer moved.</p>
 *
 * <h2>Minimised, never maximised</h2>
 *
 * <p>Minimising leaves the header and takes the plot away; the height it
 * releases goes to the CHART, not to the neighbouring panes, which keep theirs.
 * That is the behaviour asked for and it is the one that makes a stack
 * predictable: hiding one indicator does not silently resize the other two.</p>
 *
 * <p>There is no maximise. A pane that could swallow the window would leave the
 * price behind, and an indicator without the price it is measuring is a graph
 * of nothing.</p>
 */
public final class StudyPane extends JComponent {

    private static final long serialVersionUID = 1L;

    /** The strip along the top: the entries, and the pane's two buttons. */
    private static final int HEADER = 20;

    /** How much of the top edge grabs, for dragging the pane taller or shorter. */
    private static final int GRIP = 4;

    private static final int SHORTEST = HEADER + 30;

    /**
     * How far the pointer has to travel on the title bar before it counts as
     * carrying the pane rather than clicking it.
     *
     * <p>Without it a hand that moves one pixel between press and release
     * would reorder the stack, and the reader would have no idea what they
     * did.</p>
     */
    private static final int SLIP = 3;

    /** Side of each little button. */
    private static final int BUTTON = 14;

    /** Room kept at the end of every entry for its two buttons. */
    private static final int SLOT = 2 * BUTTON + 4 + 6;

    private final transient ChartCanvas canvas;

    /**
     * What is drawn here, left to right in the header and all on one scale.
     *
     * <p>Never empty: a pane with nothing in it is not a pane, and the one that
     * loses its last indicator asks the stack to take it away.</p>
     */
    private final transient List<Overlay> studies = new ArrayList<>();

    private final transient Runnable onChanged;

    /** Opens one study's settings; the stack knows which dialog that is. */
    private transient Consumer<Overlay> onSettings = study -> { };

    private int height = 110;

    private boolean minimised;

    /** Where the drag started, or -1 when nothing is being dragged. */
    private transient int grabbedAt = -1;

    private transient int grabbedHeight;

    /** Where the title bar was grabbed, or -1 when it was not. */
    private transient int carriedFrom = -1;

    /** Whether that grab has travelled far enough to be a carry. */
    private transient boolean carrying;

    /** Hit areas of the entries, rebuilt on every paint. */
    private final transient List<Rectangle> entries = new ArrayList<>();

    /** Which entry the pointer is on, or -1. */
    private int hovered = -1;

    public StudyPane(ChartCanvas canvas, Overlay study, Runnable onChanged) {
        this.canvas = canvas;
        this.onChanged = onChanged == null ? () -> { } : onChanged;

        studies.add(study);

        setToolTipText(Messages.get("study.paneHint"));

        MouseAdapter mouse = new MouseAdapter() {

            @Override
            public void mouseMoved(MouseEvent e) {
                int was = hovered;

                hovered = entryAt(e.getX(), e.getY());

                if (hovered != was) {
                    repaint();
                }

                setCursor(Cursor.getPredefinedCursor(cursorFor(e.getX(), e.getY())));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (carrying) {
                    // Still being carried; the pointer left on its way
                    // somewhere and the drag is not over.
                    return;
                }

                hovered = -1;

                repaint();
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (closeAt(e.getX(), e.getY())) {
                    close();

                    return;
                }

                if (minimiseAt(e.getX(), e.getY())) {
                    setMinimised(!minimised);

                    return;
                }

                int entry = entryAt(e.getX(), e.getY());

                if (entry >= 0 && crossAt(e.getX(), e.getY(), entry)) {
                    drop(studies.get(entry));

                    return;
                }

                if (entry >= 0 && gearAt(e.getX(), e.getY(), entry)) {
                    onSettings.accept(studies.get(entry));

                    return;
                }

                if (e.getY() <= GRIP && !minimised) {
                    grabbedAt = e.getYOnScreen();
                    grabbedHeight = height;

                    return;
                }

                if (titleAt(e.getX(), e.getY())) {
                    carriedFrom = e.getYOnScreen();
                    carrying = false;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grabbedAt >= 0) {
                    // Dragging the TOP edge, so pulling up makes it taller.
                    setHeight(grabbedHeight + (grabbedAt - e.getYOnScreen()));

                    return;
                }

                if (carriedFrom < 0 || !(getParent() instanceof StudyStack stack)) {
                    return;
                }

                if (!carrying && Math.abs(e.getYOnScreen() - carriedFrom) < SLIP) {
                    return;
                }

                if (!carrying) {
                    carrying = true;

                    stack.beginDrag(StudyPane.this);
                }

                stack.dragTo(javax.swing.SwingUtilities
                        .convertPoint(StudyPane.this, e.getPoint(), stack).y);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                grabbedAt = -1;
                carriedFrom = -1;

                if (carrying && getParent() instanceof StudyStack stack) {
                    stack.endDrag();
                }

                carrying = false;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                int entry = entryAt(e.getX(), e.getY());

                if (e.getClickCount() == 2 && entry >= 0 && e.getY() > GRIP
                        && !gearAt(e.getX(), e.getY(), entry)
                        && !crossAt(e.getX(), e.getY(), entry)) {
                    // The name, double-clicked, opens the settings of THAT
                    // indicator -- the same gesture the overlay legend answers
                    // to, and the only one that stays unambiguous when a pane
                    // holds three.
                    onSettings.accept(studies.get(entry));
                }
            }
        };

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /**
     * @return how this pane is named where it has to be picked from a list
     *
     * <p>What it holds, because that is all it is: the first indicator's name,
     * and how many others are in there with it. A pane numbered "panel 2"
     * would make the reader count strips on screen to find out which one that
     * was.</p>
     */
    public String title() {
        String name = labelOf(studies.get(0));

        return studies.size() == 1 ? name : name + " +" + (studies.size() - 1);
    }

    /** @return what is drawn here, in the order the header lists it */
    public List<Overlay> studies() {
        return List.copyOf(studies);
    }

    /** @return the first one, which is all of it when a pane holds one */
    public Overlay study() {
        return studies.get(0);
    }

    /**
     * Adds another indicator to this pane.
     *
     * <p>Whether it BELONGS here is not decided at this depth: the pane draws
     * what it is given. {@link StudyStack#fits} is the one place that answers
     * that, so there is one rule and not one per caller.</p>
     */
    public void add(Overlay study) {
        if (study == null || studies.contains(study)) {
            return;
        }

        studies.add(study);

        // Same reason as in drop(): the hit areas describe the row before this
        // one, and the mouse reads them until the next paint runs.
        entries.clear();

        onChanged.run();
        repaint();
    }

    /**
     * @return how many entry hit areas the last paint left behind
     *
     * <p>Package-visible for the test that says a dropped indicator takes its
     * clickable row with it. Reading it off the screen would be reading
     * pixels; this is the list the mouse actually consults.</p>
     */
    int hitAreas() {
        return entries.size();
    }

    /** Takes one indicator out, and the pane with it when it was the last. */
    public void drop(Overlay study) {
        if (!studies.remove(study)) {
            return;
        }

        hovered = -1;

        // The hit areas go with it. They are rebuilt on every paint from the
        // studies that are left, and until that paint runs they still describe
        // the row that was just taken away -- so entryAt() could answer an
        // index that studies.get() no longer has. Cleared here, an empty list
        // means "not painted yet", which every reader of it already handles,
        // instead of "painted something that is gone".
        entries.clear();

        if (studies.isEmpty()) {
            // A pane drawing nothing is a strip of empty ground with two
            // buttons on it, and no way to put anything back into it.
            close();

            return;
        }

        onChanged.run();
        repaint();
    }

    public void onSettings(Consumer<Overlay> listener) {
        onSettings = listener == null ? study -> { } : listener;
    }

    /** @return how tall this pane wants to be right now */
    public int wantedHeight() {
        return minimised ? HEADER : Math.max(SHORTEST, height);
    }

    /**
     * @return the height it would take if it were open
     *
     * <p>Not {@link #wantedHeight()}, which is the header alone while
     * minimised: storing that would restore a pane that unfolds to nothing.</p>
     */
    public int storedHeight() {
        return height;
    }

    public void setHeight(int pixels) {
        int wanted = Math.max(SHORTEST, pixels);

        if (wanted != height) {
            height = wanted;

            onChanged.run();
        }
    }

    public boolean isMinimised() {
        return minimised;
    }

    public void setMinimised(boolean value) {
        if (minimised != value) {
            minimised = value;

            onChanged.run();
        }
    }

    /**
     * Called when this pane's own close button is pressed, or its last
     * indicator is taken away.
     *
     * <p>Through the stack, never straight out of the container: the canvas
     * repaints its panes from a list it keeps, and a pane torn off the screen
     * without leaving that list is a component being painted for ever after it
     * stopped existing on screen.</p>
     */
    private void close() {
        if (getParent() instanceof StudyStack stack) {
            stack.hide(this);
        }
    }

    // ------------------------------------------------------------- the boxes

    private int closeLeft() {
        return getWidth() - 18;
    }

    private int minimiseLeft() {
        return getWidth() - 36;
    }

    private boolean closeAt(int x, int y) {
        return y < HEADER && x >= closeLeft() && x < closeLeft() + BUTTON;
    }

    private boolean minimiseAt(int x, int y) {
        return y < HEADER && x >= minimiseLeft() && x < minimiseLeft() + BUTTON;
    }

    /** @return which entry a point is on, or -1 for anywhere else */
    private int entryAt(int x, int y) {
        if (y >= HEADER || x >= minimiseLeft()) {
            return -1;
        }

        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).contains(x, y)) {
                return i;
            }
        }

        return -1;
    }

    private boolean gearAt(int x, int y, int entry) {
        return y < HEADER && x >= gearLeft(entry) && x < gearLeft(entry) + BUTTON;
    }

    private boolean crossAt(int x, int y, int entry) {
        return y < HEADER && x >= gearLeft(entry) + BUTTON + 4
                && x < gearLeft(entry) + 2 * BUTTON + 4;
    }

    /** @return where the reserved slot of an entry begins */
    private int gearLeft(int entry) {
        Rectangle box = entries.get(entry);

        return box.x + box.width - SLOT + 3;
    }

    /**
     * @return whether a point is on the draggable part of the title bar
     *
     * <p>Not the buttons, and not the top few pixels, which resize. What is
     * left is the names and the numbers -- exactly the part of a window's
     * title bar that carries the window.</p>
     */
    private boolean titleAt(int x, int y) {
        return y < HEADER && x < minimiseLeft() && (minimised || y > GRIP);
    }

    /** @return which pointer the header should show at a point */
    private int cursorFor(int x, int y) {
        if (closeAt(x, y) || minimiseAt(x, y)) {
            return Cursor.HAND_CURSOR;
        }

        int entry = entryAt(x, y);

        if (entry >= 0 && (gearAt(x, y, entry) || crossAt(x, y, entry))) {
            return Cursor.HAND_CURSOR;
        }

        if (y <= GRIP && !minimised) {
            return Cursor.N_RESIZE_CURSOR;
        }

        // A title bar that can be carried has to LOOK like one before it is
        // grabbed; there is nothing else on screen saying the stack reorders.
        return titleAt(x, y) ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR;
    }

    // ----------------------------------------------------------- the drawing

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            paintHeader(g);

            if (!minimised) {
                paintPlot(g);
            }

            // AFTER the plot, because the scale strip on the right fills its
            // whole column -- drawn before, the buttons were painted and then
            // covered by it, which is a bug that only shows on screen.
            paintButton(g, minimiseLeft(), minimised);
            paintClose(g, closeLeft());

            // The seam with whatever is above, and the thing the pointer grabs.
            g.setColor(ChartColors.grid());
            g.drawLine(0, 0, getWidth(), 0);
        } finally {
            g.dispose();
        }
    }

    /**
     * The names and the numbers, one entry per indicator.
     *
     * <p>An entry that would run into the pane's own buttons is not drawn, and
     * neither is anything after it. Clipping is the only honest answer on a
     * strip that cannot grow or scroll -- half a name and half a number would
     * be worse than a name that is not there.</p>
     */
    private void paintHeader(Graphics2D g) {
        entries.clear();

        g.setColor(band());
        g.fillRect(0, 0, getWidth(), HEADER);

        g.setColor(ChartColors.grid());
        g.drawLine(0, HEADER - 1, getWidth(), HEADER - 1);

        FontMetrics metrics = g.getFontMetrics();
        int baseline = HEADER - 6;
        int at = 6;
        int limit = minimiseLeft() - 6;
        int bar = readAt();

        for (int i = 0; i < studies.size(); i++) {
            Overlay study = studies.get(i);
            String name = labelOf(study);
            List<String> numbers = numbersOf(study, bar);
            int wide = metrics.stringWidth(name) + 10 + SLOT;

            for (String each : numbers) {
                wide += metrics.stringWidth(each) + 8;
            }

            if (i > 0 && at + wide > limit) {
                // The first one always goes in, however narrow the pane: a
                // header naming nothing says less than one that is full.
                //
                // And the ones left out SAY they were left out. Their lines
                // are still being drawn down there, and a header that simply
                // stopped would be a pane showing six lines and naming four
                // of them, with nothing on screen admitting it.
                paintMore(g, at, baseline, studies.size() - i);

                break;
            }

            entries.add(new Rectangle(at - 4, 0, wide, HEADER));

            if (i == hovered) {
                g.setColor(hoverTint());
                g.fillRect(at - 4, 1, wide, HEADER - 2);
            }

            g.setColor(ChartColors.foreground());
            g.drawString(name, at, baseline);

            int text = at + metrics.stringWidth(name) + 10;
            List<Color> colours = study.colours();

            for (int n = 0; n < numbers.size(); n++) {
                g.setColor(n < colours.size() ? colours.get(n) : ChartColors.foreground());
                g.drawString(numbers.get(n), text, baseline);

                text += metrics.stringWidth(numbers.get(n)) + 8;
            }

            if (i == hovered) {
                paintSettings(g, gearLeft(i));
                paintClose(g, gearLeft(i) + BUTTON + 4);
            }

            at += wide;
        }
    }

    /**
     * Says how many indicators did not fit in the header.
     *
     * <p>Dimmed, because it is not one of them -- it is the header admitting
     * what it could not show. Widening the pane brings the names back.</p>
     */
    private void paintMore(Graphics2D g, int at, int baseline, int left) {
        g.setColor(blend(1));
        g.drawString("+" + left, at, baseline);
    }

    /** @return the indicator's name as the header says it */
    private static String labelOf(Overlay study) {
        String name = study.label();
        String scale = study.ownPeriod();

        // The scale only when there IS one. Two stochastics of the same shape,
        // one on the chart's bars and one on five minutes, are otherwise the
        // same word over two different lines -- and that pairing is a large
        // part of why a pane holds more than one.
        return scale == null || scale.isBlank() ? name : name + " · " + scale;
    }

    /** @return the values under the cursor, as they are written */
    private static List<String> numbersOf(Overlay study, int bar) {
        List<String> found = new ArrayList<>();

        for (double each : study.valueAt(bar)) {
            if (!Double.isNaN(each)) {
                found.add(format().format(each));
            }
        }

        return found;
    }

    /**
     * @return the title bar's own ground
     *
     * <p>A step from the chart's background towards its ink, so the band shows
     * up in either theme without either being named here. A fixed grey would
     * be invisible in one of them.</p>
     */
    private static Color band() {
        return blend(8);
    }

    /** A step further, for the entry under the pointer. */
    private static Color hoverTint() {
        return blend(4);
    }

    private static Color blend(int parts) {
        Color back = ChartColors.background();
        Color fore = ChartColors.foreground();

        return new Color((back.getRed() * parts + fore.getRed()) / (parts + 1),
                (back.getGreen() * parts + fore.getGreen()) / (parts + 1),
                (back.getBlue() * parts + fore.getBlue()) / (parts + 1));
    }

    private void paintButton(Graphics2D g, int left, boolean restore) {
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.2f));

        int middle = HEADER / 2;

        if (restore) {
            // A box: what it will become. A minus sign on a pane that is
            // already flat would offer to do what it has done.
            g.drawRect(left + 2, middle - 4, 10, 8);

            return;
        }

        g.drawLine(left + 2, middle, left + 12, middle);
    }

    /**
     * Three sliders, which is what a settings control looks like everywhere.
     *
     * <p>Drawn rather than shipped, like the transport's glyphs: one shape
     * anybody can describe in a sentence does not need a file per size and a
     * palette per theme.</p>
     */
    private void paintSettings(Graphics2D g, int left) {
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.2f));

        int middle = HEADER / 2;

        for (int row = -1; row <= 1; row++) {
            int at = middle + row * 4;

            g.drawLine(left + 2, at, left + 12, at);
            g.fillRect(left + (row == 0 ? 8 : 4), at - 1, 3, 3);
        }
    }

    private void paintClose(Graphics2D g, int left) {
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(left + 3, HEADER / 2 - 4, left + 11, HEADER / 2 + 4);
        g.drawLine(left + 11, HEADER / 2 - 4, left + 3, HEADER / 2 + 4);
    }

    /** @return the bar whose values the header shows */
    private int readAt() {
        int under = canvas.hoveredBar();

        return under >= 0 ? under : canvas.lastVisibleBar();
    }

    private void paintPlot(Graphics2D g) {
        Viewport viewport = canvas.plotViewport();

        if (viewport == null) {
            return;
        }

        int top = HEADER;
        int bottom = getHeight() - 2;
        double[] range = range(viewport);

        if (range[1] - range[0] <= 0) {
            return;
        }

        paintLevels(g, top, bottom, range[0], range[1]);
        paintLines(g, viewport, top, bottom, range[0], range[1]);
        paintScale(g, top, bottom, range[0], range[1]);
    }

    /**
     * @return {@code {low, high}} covering everything the pane is SHOWING
     *
     * <p>The union, so nothing inside is ever drawn off the top. With every
     * indicator here agreeing on its range -- which is what
     * {@link StudyStack#fits} exists to guarantee -- the union IS that range,
     * and this costs nothing.</p>
     *
     * <p>Package-visible for the test that says a hidden study stops stretching
     * it. Reading it off the screen would be reading pixels; this is the number
     * the pixels come from.</p>
     */
    double[] range(Viewport viewport) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;

        for (Overlay study : showing()) {
            double[] fixed = study.bounds();

            if (fixed != null) {
                low = Math.min(low, fixed[0]);
                high = Math.max(high, fixed[1]);

                continue;
            }

            for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
                for (double each : study.valueAt(bar)) {
                    if (!Double.isNaN(each)) {
                        low = Math.min(low, each);
                        high = Math.max(high, each);
                    }
                }
            }
        }

        return low == Double.MAX_VALUE ? new double[] {0, 1} : new double[] {low, high};
    }

    private double y(double value, int top, int bottom, double low, double high) {
        return bottom - (value - low) / (high - low) * (bottom - top);
    }

    /**
     * @return the studies that take part in the drawing
     *
     * <p><b>One answer, because there were four questions.</b> Four loops walked
     * the same list and exactly one of them asked whether the study was visible.
     * Hiding a line therefore hid the line and nothing else: the study went on
     * stretching the pane's vertical scale, went on drawing its twenty and
     * eighty across it, and went on dictating the numbers written up the right
     * edge. Hiding an indicator that deforms the scale did not give the scale
     * back.</p>
     *
     * <p>The header is deliberately NOT filtered. It is where a hidden study is
     * turned back on, and one that vanished from it could not be -- so it lists
     * everything, and showing which are off is the header's own job.</p>
     */
    private List<Overlay> showing() {
        List<Overlay> found = new ArrayList<>(studies.size());

        for (Overlay study : studies) {
            if (study.isVisible()) {
                found.add(study);
            }
        }

        return found;
    }

    private void paintLevels(Graphics2D g, int top, int bottom, double low, double high) {
        List<Integer> drawn = new ArrayList<>();

        for (Overlay study : showing()) {
            for (Overlay.Level level : study.levels()) {
                int at = (int) Math.round(y(level.at(), top, bottom, low, high));

                // Two stochastics in one pane both want twenty and eighty, and
                // the same line drawn twice is the same line.
                if (drawn.contains(at)) {
                    continue;
                }

                drawn.add(at);

                g.setColor(level.colour());
                g.setStroke(level.stroke());
                g.drawLine(0, at, plotWidth(), at);
            }
        }
    }

    private void paintLines(Graphics2D g, Viewport viewport,
                            int top, int bottom, double low, double high) {
        for (Overlay study : showing()) {
            List<Color> colours = study.colours();
            List<java.awt.Stroke> strokes = study.strokes();
            int lines = colours.size();

            // ONE valueAt PER BAR, and it used to be one per bar PER LINE: the
            // bar loop sat inside the line loop, and every implementation of
            // valueAt builds its answer. A stochastic draws two lines, so this
            // was two arrays per visible bar on every repaint -- and a repaint
            // happens on every movement of the mouse.
            //
            // The line's own continuity is what forced the old order, so the
            // last point of each is now remembered side by side.
            int[] lastX = new int[lines];
            int[] lastY = new int[lines];

            java.util.Arrays.fill(lastX, Integer.MIN_VALUE);

            for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
                double[] values = study.valueAt(bar);
                int x = (int) Math.round(viewport.x(bar));

                for (int line = 0; line < lines; line++) {
                    if (line >= values.length || Double.isNaN(values[line])) {
                        lastX[line] = Integer.MIN_VALUE;

                        continue;
                    }

                    int at = (int) Math.round(y(values[line], top, bottom, low, high));

                    if (lastX[line] != Integer.MIN_VALUE) {
                        g.setColor(colours.get(line));
                        g.setStroke(line < strokes.size() ? strokes.get(line) : study.stroke());
                        g.drawLine(lastX[line], lastY[line], x, at);
                    }

                    lastX[line] = x;
                    lastY[line] = at;
                }
            }
        }
    }

    /**
     * The numbers down the right, in the strip the chart leaves for its own.
     *
     * <p>Only the ends and the levels. A pane sixty pixels tall with five
     * labels down it is five labels nobody can read; the ends say the scale and
     * the levels are the only other numbers that mean anything here.</p>
     */
    private void paintScale(Graphics2D g, int top, int bottom, double low, double high) {
        g.setColor(ChartColors.background());
        g.fillRect(plotWidth(), top, getWidth() - plotWidth(), getHeight() - top);

        g.setColor(ChartColors.grid());
        g.drawLine(plotWidth(), top, plotWidth(), getHeight());

        g.setColor(ChartColors.foreground());

        DecimalFormat format = format();
        List<Integer> written = new ArrayList<>();

        // The levels first, because they are the ones worth reading: twenty and
        // eighty are where a stochastic says something, and the ends of the
        // scale only say how tall the pane is.
        for (Overlay study : showing()) {
            for (Overlay.Level level : study.levels()) {
                int at = (int) Math.round(y(level.at(), top, bottom, low, high));

                if (written.contains(at)) {
                    continue;
                }

                g.drawString(format.format(level.at()), plotWidth() + 6, at + 4);
                written.add(at);
            }
        }

        // And an end only when nothing is already written across it. Two
        // numbers one pixel apart are two numbers nobody can read, and on a
        // stochastic the ends sit right beside the levels by default.
        drawIfClear(g, format.format(high), top + 10, written);
        drawIfClear(g, format.format(low), bottom - 2, written);
    }

    private void drawIfClear(Graphics2D g, String text, int at, List<Integer> taken) {
        for (int each : taken) {
            if (Math.abs(each - at) < 16) {
                return;
            }
        }

        g.drawString(text, plotWidth() + 6, at);
    }

    private int plotWidth() {
        return Math.max(1, getWidth() - canvas.axisWidth());
    }

    /**
     * @return the format every number in this pane is written with
     *
     * <p>Built once and kept, and it used to be built on every call -- which is
     * every number drawn, on every repaint. A {@code DecimalFormat} parses its
     * pattern and reads the locale's symbols each time; the pane writes one per
     * level, plus the two ends of the scale, on every frame of a replay.</p>
     *
     * <p>Rebuilt when the language changes, because the decimal separator does.
     * The check is a reference comparison against the locale in force, which
     * costs nothing next to what it replaces.</p>
     */
    private static DecimalFormat format() {
        Locale now = Locale.getDefault();

        if (format == null || !now.equals(formatFor)) {
            formatFor = now;
            format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(now));
        }

        return format;
    }

    private static DecimalFormat format;

    private static Locale formatFor;
}
