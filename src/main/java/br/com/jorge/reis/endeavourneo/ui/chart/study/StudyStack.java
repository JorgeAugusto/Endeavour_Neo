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

    public StudyStack(ChartCanvas canvas) {
        super(null);

        this.canvas = canvas;

        setLayout(new Stacked());
        add(canvas);
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
    public StudyPane show(Study study) {
        study.calculate(canvas.source());

        StudyPane pane = new StudyPane(canvas, study, this::relayout);

        pane.onSettings(() -> settingsFor(study));
        canvas.follow(pane);
        add(pane);
        relayout();

        return pane;
    }

    /**
     * Opens the settings of one study, and redraws it with what came back.
     *
     * <p>The dispatch lives here rather than in the pane because the pane's job
     * is to draw a {@link Study} and nothing else -- teaching it which dialog
     * belongs to which implementation would make every new indicator a change
     * to the pane as well as an addition beside it.</p>
     */
    private void settingsFor(Study study) {
        if (!(study instanceof br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic
                .SlowStochastic stochastic)) {
            return;
        }

        if (br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.StochasticDialog
                .edit(javax.swing.SwingUtilities.getWindowAncestor(this), stochastic)) {
            stochastic.calculate(canvas.source());
            relayout();
        }
    }

    /** @return what is open, in the shape a layout stores */
    public List<br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane> remembered() {
        List<br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane> found =
                new ArrayList<>();

        for (StudyPane pane : panes()) {
            Study study = pane.study();

            found.add(new br.com.jorge.reis.endeavourneo.ui.chart.ChartLayout.Pane(
                    study.nameKey(), study.parameters(), study.appearance(),
                    pane.storedHeight(), pane.isMinimised()));
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
            Study study = each.build();

            if (study == null) {
                // A kind this version does not have. Skipped, not fatal.
                continue;
            }

            StudyPane pane = show(study);

            if (each.height() > 0) {
                pane.setHeight(each.height());
            }

            pane.setMinimised(each.minimised());
        }

        relayout();
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
            pane.study().calculate(canvas.source());
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
