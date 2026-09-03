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

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.chart.style.CandleStyle;
import br.com.jorge.reis.endeavourneo.ui.chart.style.LineStyle;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyVetoException;
import java.util.prefs.Preferences;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;

/**
 * One chart, and the two containers it can live in.
 *
 * <p>A chart is either <b>docked</b> — a {@link JInternalFrame} inside the main
 * window, movable and resizable within it — or <b>floating</b>, a real
 * {@link JFrame} that can be dragged onto another monitor. The same chart
 * switches between the two at any time.</p>
 *
 * <p><b>What makes the switch cheap is that the canvas does not know where it
 * lives.</b> Changing mode is removing the component from one parent and adding
 * it to the other; the series, the style, the zoom and the vertical factor all
 * survive because it is the same object throughout. A design where the container
 * held that state would have to copy it across, and would lose something on
 * every move.</p>
 *
 * <p><b>The trade-off, stated rather than discovered later:</b> an internal
 * frame gets no window management from the operating system — no taskbar entry,
 * no alt-tab, no edge snapping. That is the price of being inside. Floating
 * gets all of it and cannot be tiled against the other charts.</p>
 */
public final class ChartHolder {

    private static final Preferences PREFS = Preferences.userRoot()
            .node("br/com/jorge/reis/endeavourneo/charts");

    private static final int DEFAULT_WIDTH = 900;

    private static final int DEFAULT_HEIGHT = 560;

    /** How far each new chart steps down and right from the previous one. */
    private static final int CASCADE_STEP = 28;

    private static int opened;

    private final String name;

    private final String key;

    private final ChartCanvas canvas = new ChartCanvas();

    /**
     * The legend, moved between containers along with the canvas.
     *
     * <p>Built once and re-parented, for the same reason the canvas is: a legend
     * recreated on every dock and undock would lose which overlays the reader
     * had hidden.</p>
     */
    private final OverlayLegend legend = new OverlayLegend(canvas);

    private final JDesktopPane desktop;

    private final Window owner;

    private final Runnable onClosed;

    private JInternalFrame docked;

    private JFrame floating;

    /**
     * @param name the title, and the key under which geometry is remembered
     * @param desktop where a docked chart goes
     * @param owner the window a floating chart cascades from
     * @param onClosed called when the chart is closed in either mode
     */
    public ChartHolder(String name, JDesktopPane desktop, Window owner, Runnable onClosed) {
        this.name = name;
        this.key = name.replaceAll("[^A-Za-z0-9]+", "_");
        this.desktop = desktop;
        this.owner = owner;
        this.onClosed = onClosed == null ? () -> { } : onClosed;
    }

    public String name() {
        return name;
    }

    public ChartCanvas canvas() {
        return canvas;
    }

    public boolean isFloating() {
        return floating != null;
    }

    /** Shows the chart in whichever mode it was last left in. */
    public void show() {
        if (PREFS.getBoolean(key + ".floating", false)) {
            floatIt();
        } else {
            dock();
        }
    }

    // ------------------------------------------------------------ the modes

    /** Puts the chart inside the main window. */
    public void dock() {
        if (docked != null) {
            front();

            return;
        }

        detachFromFloating();

        docked = new JInternalFrame(name, true, true, true, true);

        docked.setJMenuBar(buildMenuBar());
        docked.getContentPane().add(legend, BorderLayout.NORTH);
        docked.getContentPane().add(canvas, BorderLayout.CENTER);
        docked.setSize(restoredSize());
        docked.setLocation(cascadeInside());

        docked.addInternalFrameListener(new InternalFrameAdapter() {

            @Override
            public void internalFrameClosing(InternalFrameEvent e) {
                store();
                onClosed.run();
            }
        });

        desktop.add(docked);
        docked.setVisible(true);

        PREFS.putBoolean(key + ".floating", false);

        front();
    }

    /** Sets the chart free, in a window the operating system manages. */
    public void floatIt() {
        if (floating != null) {
            front();

            return;
        }

        detachFromDocked();

        floating = new JFrame(name);

        floating.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        floating.setJMenuBar(buildMenuBar());
        floating.getContentPane().add(legend, BorderLayout.NORTH);
        floating.getContentPane().add(canvas, BorderLayout.CENTER);
        floating.setSize(restoredSize());
        floating.setLocation(restoredLocation());

        floating.addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                store();
                onClosed.run();
            }
        });

        floating.setVisible(true);

        PREFS.putBoolean(key + ".floating", true);

        front();
    }

    /** Switches to the other mode. */
    public void toggleMode() {
        if (isFloating()) {
            dock();
        } else {
            floatIt();
        }
    }

    public void front() {
        if (floating != null) {
            floating.setExtendedState(floating.getExtendedState() & ~JFrame.ICONIFIED);
            floating.toFront();
            floating.requestFocus();
        } else if (docked != null) {
            try {
                docked.setIcon(false);
                docked.setSelected(true);
                docked.toFront();
            } catch (PropertyVetoException e) {
                // Another component refused the change. Nothing to repair --
                // the chart is still on screen, just not focused.
                docked.toFront();
            }
        }
    }

    public void close() {
        store();
        detachFromDocked();
        detachFromFloating();
    }

    // ------------------------------------------------------- moving the canvas

    /**
     * Takes the canvas out of the internal frame and disposes of it.
     *
     * <p>Removing the canvas <b>before</b> disposing matters: disposing a
     * container that still holds it can leave the component with a stale peer,
     * and the next {@code add} paints nothing.</p>
     */
    private void detachFromDocked() {
        if (docked == null) {
            return;
        }

        storeDockedBounds();

        Container content = docked.getContentPane();

        content.remove(legend);
        content.remove(canvas);
        docked.dispose();
        docked = null;
    }

    private void detachFromFloating() {
        if (floating == null) {
            return;
        }

        storeFloatingBounds();

        floating.getContentPane().remove(legend);
        floating.getContentPane().remove(canvas);
        floating.dispose();
        floating = null;
    }

    // ------------------------------------------------------------------ menu

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu chart = new JMenu(Messages.get("menu.chart"));

        chart.setMnemonic(Messages.mnemonic("menu.chart"));
        chart.add(item("chart.style.candle", () -> canvas.setStyle(new CandleStyle())));
        chart.add(item("chart.style.candleHollow",
                () -> canvas.setStyle(new CandleStyle(true))));
        chart.add(item("chart.style.line", () -> canvas.setStyle(new LineStyle())));
        chart.addSeparator();
        chart.add(item("chart.resetScale", canvas::resetStretch));
        chart.addSeparator();
        chart.add(modeItem());
        chart.addSeparator();
        // The label names the destination, not the current state: "Float" when
        // docked. A toggle labelled with where you are rather than where you go
        // is read backwards by half the people who see it.
        chart.add(item(isFloating() ? "chart.dock" : "chart.float", this::toggleMode));

        bar.add(chart);

        return bar;
    }

    /**
     * The ruler toggle, ticked to show which mode the chart is in.
     *
     * <p>Visible state on purpose. Control alone flips the mode, and a shortcut
     * with no indicator leaves the reader guessing why a drag did something
     * else -- the classic complaint about modal tools. The tick answers it
     * before it is asked.</p>
     */
    private JMenuItem modeItem() {
        javax.swing.JCheckBoxMenuItem entry =
                new javax.swing.JCheckBoxMenuItem(Messages.get("chart.measureMode"));

        entry.setMnemonic(Messages.mnemonic("chart.measureMode"));
        entry.setSelected(canvas.getMode() == ChartCanvas.Mode.MEASURE);
        entry.setAccelerator(javax.swing.KeyStroke.getKeyStroke("control CONTROL"));
        entry.addActionListener(e -> canvas.setMode(entry.isSelected()
                ? ChartCanvas.Mode.MEASURE : ChartCanvas.Mode.PAN));

        canvas.onModeChanged(() -> entry.setSelected(canvas.getMode() == ChartCanvas.Mode.MEASURE));

        return entry;
    }

    private JMenuItem item(String messageKey, Runnable action) {
        JMenuItem entry = new JMenuItem(Messages.get(messageKey));

        entry.setMnemonic(Messages.mnemonic(messageKey));
        entry.addActionListener(e -> action.run());

        return entry;
    }

    // -------------------------------------------------------------- geometry

    private Dimension restoredSize() {
        return new Dimension(PREFS.getInt(key + ".width", DEFAULT_WIDTH),
                PREFS.getInt(key + ".height", DEFAULT_HEIGHT));
    }

    /**
     * Where a floating chart opens.
     *
     * <p>A window last closed on a second monitor, reopened after that monitor
     * is unplugged, lands at coordinates no screen covers: it opens invisible,
     * and from the user's side the program simply did not respond. So the stored
     * position is only honoured while some device still covers its title bar.</p>
     */
    private java.awt.Point restoredLocation() {
        int x = PREFS.getInt(key + ".x", Integer.MIN_VALUE);
        int y = PREFS.getInt(key + ".y", Integer.MIN_VALUE);

        if (x != Integer.MIN_VALUE && onSomeScreen(x, y)) {
            return new java.awt.Point(x, y);
        }

        int step = (opened++ % 8) * CASCADE_STEP;

        return owner != null
                ? new java.awt.Point(owner.getX() + 40 + step, owner.getY() + 40 + step)
                : new java.awt.Point(80 + step, 80 + step);
    }

    private java.awt.Point cascadeInside() {
        int step = (opened++ % 8) * CASCADE_STEP;

        return new java.awt.Point(step, step);
    }

    private static boolean onSomeScreen(int x, int y) {
        for (GraphicsDevice device
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();

            if (screen.contains(x + 60, y + 10)) {
                return true;
            }
        }

        return false;
    }

    private void store() {
        if (floating != null) {
            storeFloatingBounds();
        } else if (docked != null) {
            storeDockedBounds();
        }
    }

    private void storeFloatingBounds() {
        PREFS.putInt(key + ".x", floating.getX());
        PREFS.putInt(key + ".y", floating.getY());
        PREFS.putInt(key + ".width", floating.getWidth());
        PREFS.putInt(key + ".height", floating.getHeight());
    }

    /**
     * Only the size is kept from a docked chart, never the position.
     *
     * <p>Coordinates inside a desktop pane and coordinates on a screen are
     * different things. Storing one and restoring the other is how a chart ends
     * up in the top-left corner of the monitor after being undocked.</p>
     */
    private void storeDockedBounds() {
        PREFS.putInt(key + ".width", docked.getWidth());
        PREFS.putInt(key + ".height", docked.getHeight());
    }
}
