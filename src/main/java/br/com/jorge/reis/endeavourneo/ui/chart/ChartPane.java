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

import br.com.jorge.reis.endeavourneo.ui.chart.study.StudyStack;

import java.awt.BorderLayout;
import javax.swing.JPanel;

/**
 * A whole chart as a component: the price, the studies under it, the legend, and
 * the way in for new indicators.
 *
 * <h2>Why this is a class and not four fields in a window</h2>
 *
 * <p>It was four fields in {@link ChartHolder}, and {@code ChartHolder} is a
 * <b>window</b>: it knows about desktops, internal frames, floating on a second
 * monitor, and where the reader left the geometry. None of that is a chart, and
 * the chart could not be put anywhere else because it did not exist as a thing.
 *
 * <p>The backtest window is what showed it. It had a bare {@link ChartCanvas},
 * so indicators drawn <b>on the price</b> could be inserted and a stochastic
 * could not — a study lives in its own panel under the price, and there was no
 * stack of panels to put it in. The fix is not a second assembly beside the
 * first; it is that the assembly is one object, and a window that wants a chart
 * asks for one.
 *
 * <h2>Inserting belongs here and nowhere else</h2>
 *
 * <p>{@link #insertIndicator()} is the reason this class exists rather than
 * being a layout helper. Asking where an indicator goes needs <b>both</b> the
 * price and the panes: the canvas cannot offer a panel it does not know about,
 * and the stack cannot offer the price. This is the only object that holds the
 * two.
 *
 * <h2>It does not create the canvas</h2>
 *
 * <p>Given one instead, because a window may need the canvas before it needs the
 * chart around it — and because the canvas is where the reader's zoom, style and
 * scale live, so handing it in is what lets a window keep its chart across
 * everything else being rebuilt.
 */
public final class ChartPane extends JPanel {

    private static final long serialVersionUID = 1L;

    private final transient ChartCanvas canvas;

    private final transient StudyStack body;

    private final transient OverlayLegend legend;

    /** The key the legend and the layouts are remembered under. */
    private final String key;

    /**
     * Built on demand, as it was in the window.
     *
     * <p>A chart that never shows its layouts never builds the bar, and one that
     * does builds it once.</p>
     */
    private transient LayoutBar layoutBar;

    /**
     * @param canvas the price, already built
     * @param key    what the legend's hidden indicators and the layouts are
     *               stored under; two charts with the same key share both
     */
    public ChartPane(ChartCanvas canvas, String key) {
        super(new BorderLayout());

        this.canvas = canvas;
        this.key = key;
        this.body = new StudyStack(canvas);
        this.legend = new OverlayLegend(canvas, key);

        // The legend reads the overlays off the canvas; nothing else tells it
        // they are gone. Without this, switching layout leaves the old list on
        // screen until a stray mouse movement repaints it.
        canvas.onOverlaysRedrawn(() -> {
            legend.revalidate();
            legend.repaint();
        });

        canvas.onInsertWanted(this::insertIndicator);

        add(legend, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    public ChartCanvas canvas() {
        return canvas;
    }

    /** @return the panes under the price: a stochastic, a MACD, a volume */
    public StudyStack studies() {
        return body;
    }

    public OverlayLegend legend() {
        return legend;
    }

    public String key() {
        return key;
    }

    /**
     * @return the bar of saved layouts, built on first use
     *
     * <p>It captures the chart's indicators into the selected layout on every
     * change. Adding an indicator and then having to find a save button is how
     * work gets lost.</p>
     */
    public LayoutBar layouts() {
        if (layoutBar == null) {
            layoutBar = new LayoutBar(canvas, body, key);

            canvas.onOverlaysChanged(layoutBar::capture);

            // The order of the panes is part of the layout, and it is written
            // the moment it changes rather than when the chart closes: it is a
            // deliberate arrangement, not a size that drifted.
            body.onArrangement(layoutBar::capture);
        }

        return layoutBar;
    }

    /**
     * Asks where an indicator goes, and puts it there.
     *
     * <p>Here because this is the one object holding both the price and the
     * panes: the canvas cannot offer a panel it does not know about, and the
     * stack cannot offer the price.
     */
    public void insertIndicator() {
        InsertOverlayDialog.Placement placement = InsertOverlayDialog.ask(
                javax.swing.SwingUtilities.getWindowAncestor(canvas), body.panes());

        if (placement == null) {
            return;
        }

        if (placement.onPrice()) {
            canvas.addOverlay(placement.indicator());
        } else if (placement.inNewPane()) {
            body.show(placement.indicator());
        } else {
            body.addTo(placement.pane(), placement.indicator());
        }
    }

    /**
     * Writes the chart's indicators into the selected layout, if there is one.
     *
     * <p>Does nothing when the bar was never built, and that is the point:
     * building it here to capture would create a layout for a chart the reader
     * never asked to save.</p>
     */
    public void captureLayout() {
        if (layoutBar != null) {
            layoutBar.capture();
        }
    }

    /**
     * Recomputes every study against whatever the canvas is showing now.
     *
     * <p>Called before anything reads them: a study still holding the values of
     * the series before would draw a shape that never happened.
     */
    public void recalculate() {
        body.recalculate();
    }
}
