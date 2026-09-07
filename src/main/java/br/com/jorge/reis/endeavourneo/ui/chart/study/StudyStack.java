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
import br.com.jorge.reis.endeavourneo.ui.chart.Forms;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodDialog;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JPanel;

/**
 * The chart, and under it however many indicator panes there are.
 *
 * <h2>Who gives way</h2>
 *
 * <p><b>The chart absorbs whatever is left.</b> Every pane keeps the height it
 * was given, and the price gets the remainder — so minimising one indicator
 * hands its pixels to the CHART and leaves the other panes exactly where they
 * were.</p>
 *
 * <p>The other arrangement — the panes sharing the freed space between
 * themselves — is what the reference implementation does, and it means hiding
 * one indicator silently resizes two others. Nobody asked for that and nobody
 * would predict it.</p>
 *
 * <p>When there is not enough room for everything, the panes are shortened from
 * the bottom up and the chart is guaranteed a floor. A window dragged small
 * should end up showing a small chart, not no chart.</p>
 */
public final class StudyStack extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The chart never gets less than this, however many panes are open. */
    private static final int CHART_FLOOR = 80;

    private final transient ChartCanvas canvas;

    /**
     * Which pane is being dragged by its title bar, or null when none is.
     *
     * <p>The reorder happens on RELEASE, not while the pointer moves. Moving
     * the panes as it passes over each one would relayout the stack underneath
     * the gesture -- and the pane the drag started on would be somewhere else
     * halfway through, which is how a drag ends up dropping the wrong
     * indicator. So the stack draws where the pane WOULD land and commits
     * once.</p>
     */
    private transient StudyPane dragging;

    /** Where it would land, as a gap between panes; -1 while nothing is dragged. */
    private transient int dropAt = -1;

    /**
     * Told when the panes are rearranged, so the layout can be written.
     *
     * <p>Height and minimising ride along with the layout when the chart is
     * closed, which is soon enough for a size. The ORDER is different: it is a
     * deliberate arrangement, and losing it to a crash would cost the reader
     * the one thing they had just decided.</p>
     */
    private transient Runnable onArrangement = () -> { };

    public StudyStack(ChartCanvas canvas) {
        super(null);

        this.canvas = canvas;

        setLayout(new Stacked());
        add(canvas);
    }

    public void onArrangement(Runnable listener) {
        onArrangement = listener == null ? () -> { } : listener;
    }

    /** @return the panes, top to bottom */
    public List<StudyPane> panes() {
        List<StudyPane> found = new ArrayList<>();

        for (Component each : getComponents()) {
            if (each instanceof StudyPane pane) {
                found.add(pane);
            }
        }

        return found;
    }

    /**
     * Puts an indicator in a pane of its own, at the bottom.
     *
     * @return the pane, so the caller can size it or open its settings
     */
    public StudyPane show(Overlay study) {
        study.calculate(canvas.series());

        StudyPane pane = new StudyPane(canvas, study, this::relayout);

        pane.onSettings(this::settingsFor);
        canvas.follow(pane);
        add(pane);
        relayout();

        return pane;
    }

    /**
     * Puts an indicator into a pane that already holds one.
     *
     * @return whether it went in
     *
     * <p>Refused rather than squeezed when it does not belong: two things
     * measured in different units share an axis only by one of them being
     * flattened against an edge, and a line drawn flat is a line that lies
     * about the market rather than about the pane.</p>
     */
    public boolean addTo(StudyPane pane, Overlay study) {
        if (pane == null || !fits(pane.studies(), study)) {
            return false;
        }

        study.calculate(canvas.series());
        pane.add(study);
        relayout();

        return true;
    }

    /**
     * @param present what a pane already holds
     * @param wanted what is being added to it
     * @return whether they can share one vertical scale
     *
     * <p><b>The one place this is decided.</b> Two answers count as yes, and
     * they are the two readings a pane exists for:</p>
     *
     * <ul>
     * <li><b>The same indicator at another scale.</b> A stochastic on the
     * chart's own bars beside a stochastic on five minutes is the same
     * measurement twice, whatever its range turns out to be — reading one
     * against the other is the point.</li>
     * <li><b>Different indicators whose range is fixed and equal.</b> A
     * stochastic and an RSI both run nought to a hundred by their own
     * definition, so they share an axis honestly.</li>
     * </ul>
     *
     * <p>Everything else is no, and the strictest case is worth naming: two
     * DIFFERENT indicators that both fit themselves to the data. Their ranges
     * might agree today and disagree tomorrow, and a rule that depends on the
     * bars on screen is a rule that changes when the reader scrolls.</p>
     */
    public static boolean fits(List<Overlay> present, Overlay wanted) {
        if (wanted == null) {
            return false;
        }

        for (Overlay each : present) {
            if (each.nameKey().equals(wanted.nameKey())) {
                continue;
            }

            double[] mine = wanted.bounds();
            double[] theirs = each.bounds();

            if (mine == null || theirs == null
                    || mine[0] != theirs[0] || mine[1] != theirs[1]) {
                return false;
            }
        }

        return true;
    }

    /**
     * Opens the settings of one study, and redraws it with what came back.
     *
     * <p>The dispatch lives here rather than in the pane because the pane's job
     * is to draw its studies and nothing else -- teaching it which dialog
     * belongs to which implementation would make every new indicator a change
     * to the pane as well as an addition beside it.</p>
     */
    private void settingsFor(Overlay study) {
        java.awt.Window owner = javax.swing.SwingUtilities.getWindowAncestor(this);
        boolean changed;

        if (study instanceof br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic
                .SlowStochastic stochastic) {
            changed = br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic
                    .StochasticDialog.edit(owner, stochastic);
        } else if (study instanceof br.com.jorge.reis.endeavourneo.ui.chart.study.rsi
                .RelativeStrength rsi) {
            changed = br.com.jorge.reis.endeavourneo.ui.chart.study.rsi.RsiDialog
                    .edit(owner, rsi);
        } else {
            // An indicator with no dialog of its own yet. Nothing to open, and
            // nothing broken by asking.
            return;
        }

        if (changed) {
            study.calculate(canvas.series());
            relayout();
        }
    }

    /** @return what is open, in the shape a layout stores */
    public List<br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane> remembered() {
        List<br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane> found =
                new ArrayList<>();

        for (StudyPane pane : panes()) {
            List<br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Entry> inside =
                    new ArrayList<>();

            for (Overlay study : pane.studies()) {
                inside.add(new br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Entry(
                        study.nameKey(), study.parameters(), true, study.appearance()));
            }

            found.add(new br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane(
                    List.copyOf(inside), pane.storedHeight(), pane.isMinimised()));
        }

        return found;
    }

    /**
     * Replaces every pane with what a layout describes.
     *
     * <p>Replaces, never merges: switching layouts has to leave the chart
     * showing that layout and nothing else, or two switches would accumulate
     * indicators nobody asked for.</p>
     */
    public void restore(List<br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane> wanted) {
        for (StudyPane pane : panes()) {
            canvas.unfollow(pane);
            remove(pane);
        }

        for (br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane each : wanted) {
            List<Overlay> inside = each.build();

            if (inside.isEmpty()) {
                // Every kind in it is one this version does not have. Skipped,
                // not fatal.
                continue;
            }

            StudyPane pane = show(inside.get(0));

            // Put back without asking whether they fit. What was stored was
            // legal when it was written, and a rule that grew stricter since
            // should not silently empty somebody's pane; the union range keeps
            // the drawing honest either way.
            for (int i = 1; i < inside.size(); i++) {
                inside.get(i).calculate(canvas.series());
                pane.add(inside.get(i));
            }

            if (each.height() > 0) {
                pane.setHeight(each.height());
            }

            pane.setMinimised(each.minimised());
        }

        relayout();
    }

    // ------------------------------------------------------ dragging a pane

    /** Begins a reorder; called by the pane whose title bar was grabbed. */
    void beginDrag(StudyPane pane) {
        dragging = pane;
        dropAt = -1;
    }

    /**
     * @param y a pixel down THIS component, from the pointer
     *
     * <p>Nothing moves here. Only the mark showing where the release would put
     * it changes, and only when it actually changes -- a repaint per pixel of
     * pointer movement would redraw every study in the stack.</p>
     */
    void dragTo(int y) {
        if (dragging == null) {
            return;
        }

        int wanted = gapAt(y);

        if (wanted != dropAt) {
            dropAt = wanted;

            repaint();
        }
    }

    /** Ends a reorder, moving the pane if the pointer landed on a gap. */
    void endDrag() {
        StudyPane pane = dragging;
        int gap = dropAt;

        dragging = null;
        dropAt = -1;

        repaint();

        if (pane == null || gap < 0) {
            return;
        }

        // After the release has finished being delivered. The move takes the
        // pane out of this container and puts it back, and doing that to the
        // component whose event is still on the stack is asking for trouble.
        javax.swing.SwingUtilities.invokeLater(() -> moveTo(pane, gap));
    }

    /**
     * @param y a pixel down this component
     * @return which GAP between panes it points at, from zero
     *
     * <p>Gaps and not panes: dropping is answering "above which one", and the
     * answer has to include "below the last", which no pane index can say.</p>
     */
    private int gapAt(int y) {
        List<StudyPane> panes = panes();

        for (int i = 0; i < panes.size(); i++) {
            StudyPane pane = panes.get(i);

            if (y < pane.getY() + pane.getHeight() / 2) {
                return i;
            }
        }

        return panes.size();
    }

    /**
     * Moves one pane to a gap, and says so, so the layout can be written.
     *
     * @param pane the one being carried
     * @param gap where it should sit, counted in gaps between panes
     */
    private void moveTo(StudyPane pane, int gap) {
        List<StudyPane> panes = panes();
        int from = panes.indexOf(pane);

        // The same drop the layout tabs answer to, on the other axis.
        if (!br.com.jorge.reis.endeavourneo.ui.chart.Reordering.move(panes, from, gap)) {
            return;
        }

        // Every pane out and back in, rather than one moved past the others.
        // The canvas sits among these children too, and counting around it is
        // the kind of arithmetic that is right until someone adds a third kind
        // of child.
        for (StudyPane each : panes) {
            remove(each);
        }

        for (StudyPane each : panes) {
            add(each);
        }

        relayout();
        onArrangement.run();
    }

    @Override
    protected void paintChildren(java.awt.Graphics graphics) {
        super.paintChildren(graphics);

        if (dropAt < 0) {
            return;
        }

        List<StudyPane> panes = panes();

        // A line in the gap, which is the one thing that says "here" without
        // moving anything: the panes stay where they are until released, so
        // this mark is the only feedback the gesture has.
        int y = dropAt < panes.size() ? panes.get(dropAt).getY() : getHeight();

        graphics.setColor(ChartColors.up());
        graphics.fillRect(0, Math.max(0, Math.min(y - 1, getHeight() - 3)), getWidth(), 3);
    }

    /** Takes an indicator away, with its pane. */
    public void hide(StudyPane pane) {
        canvas.unfollow(pane);
        remove(pane);
        relayout();
    }

    /**
     * Recomputes every study against the chart's current bars.
     *
     * <p>Called when the series is replaced -- a different segment, a replay
     * arriving, a period change that refolds. A study still holding the values
     * of the series before would draw a shape that never happened, at bars that
     * are not the ones it was measured on.</p>
     */
    public void recalculate() {
        for (StudyPane pane : panes()) {
            for (Overlay study : pane.studies()) {
                study.calculate(canvas.series());
            }
        }

        repaint();
    }

    private void relayout() {
        revalidate();
        repaint();
    }

    /**
     * The chart on top taking the slack, the panes below at their own heights.
     */
    private final class Stacked implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) {
            // Nothing: which component is which is decided by its type.
        }

        @Override
        public void removeLayoutComponent(Component component) {
            // Nothing to forget.
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Dimension wanted = canvas.getPreferredSize();
            int tall = wanted.height;

            for (StudyPane pane : panes()) {
                tall += pane.wantedHeight();
            }

            return new Dimension(wanted.width, tall);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(120, CHART_FLOOR);
        }

        @Override
        public void layoutContainer(Container parent) {
            int width = parent.getWidth();
            int height = parent.getHeight();
            List<StudyPane> panes = panes();
            int wanted = 0;

            for (StudyPane pane : panes) {
                wanted += pane.wantedHeight();
            }

            // The chart's floor comes first. Past it the panes are trimmed from
            // the BOTTOM up, so the one nearest the price -- the one being read
            // against it -- is the last to lose room.
            int forPanes = Math.min(wanted, Math.max(0, height - CHART_FLOOR));
            int top = Math.max(CHART_FLOOR, height - forPanes);

            canvas.setBounds(0, 0, width, top);

            int at = top;
            int left = forPanes;

            for (StudyPane pane : panes) {
                int tall = Math.min(pane.wantedHeight(), Math.max(0, left));

                pane.setBounds(0, at, width, tall);

                at += tall;
                left -= tall;
            }
        }
    }
}
