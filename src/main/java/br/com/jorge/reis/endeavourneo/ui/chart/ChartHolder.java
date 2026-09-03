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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
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
import br.com.jorge.reis.endeavourneo.ui.shell.Icons;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JMenu;
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

    /**
     * The shape of what is stored here.
     *
     * <p>Raised when a stored value stops meaning what it used to. Version 2:
     * before it, the size written for a maximised chart was the desktop's size,
     * so every chart reopened merely large and never maximised. Those entries
     * cannot be told apart from a chart the reader deliberately made that big,
     * so they are dropped rather than interpreted -- one lost window size, once,
     * against a wrong one for ever.</p>
     */
    private static final int SCHEMA = 2;

    private static final String SCHEMA_KEY = "schema";

    static {
        if (PREFS.getInt(SCHEMA_KEY, 0) < SCHEMA) {
            try {
                for (String stale : PREFS.keys()) {
                    PREFS.remove(stale);
                }
            } catch (java.util.prefs.BackingStoreException e) {
                // Nothing to do and nothing worth saying: the worst outcome is
                // that charts open at the size they opened at yesterday.
            }

            PREFS.putInt(SCHEMA_KEY, SCHEMA);
        }
    }

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
    private final OverlayLegend legend;

    /** Instrument and period, inside the chart and below the title bar. */
    private final ChartHeader chartHeader;

    /**
     * Toolbar and legend together, so the pair moves between containers as one.
     *
     * <p>A toolbar and not a menu: everything in it is one click away and stays
     * visible, which for four controls used constantly beats a menu that has to
     * be opened to find out what is in it. What did NOT come across is the ruler
     * -- it is one setting for the whole application now, and lives in the
     * preferences.</p>
     */
    private final JPanel header = new JPanel(new BorderLayout());

    private JToolBar toolBar;

    /** What the title says after the period while a replay is feeding this chart. */
    private String replayLabel;

    /** Undoes the subscription when another replay arrives, or the window closes. */
    private transient Runnable detachReplay = () -> { };

    /** Which drawing style the buttons should show as chosen. */
    private String styleChoice = "candle";

    /** The layout tabs, below the time axis. Built on first use. */
    private LayoutBar layoutBar;

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
        this.legend = new OverlayLegend(canvas, this.key);
        this.chartHeader = new ChartHeader(canvas, name);

        // The legend reads the overlays off the canvas; nothing else tells it
        // they are gone. Without this, switching layout leaves the old list on
        // screen until a stray mouse movement repaints it.
        canvas.onOverlaysRedrawn(() -> {
            legend.revalidate();
            legend.repaint();
        });

        canvas.onSeriesChanged(this::retitle);

        JPanel stack = new JPanel();

        stack.setLayout(new javax.swing.BoxLayout(stack, javax.swing.BoxLayout.Y_AXIS));
        stack.add(chartHeader);
        stack.add(legend);

        header.add(stack, BorderLayout.CENTER);
        this.desktop = desktop;
        this.owner = owner;
        this.onClosed = onClosed == null ? () -> { } : onClosed;
    }

    public String name() {
        return name;
    }

    /**
     * Feeds this chart from a replay instead of from stored history.
     *
     * @param label what to show in the title
     * @param live a series that grows as the session plays
     * @param detach called when this chart stops following that session
     *
     * <p>A second drop replaces the first, and unsubscribes from it: without
     * that, a chart dropped on twice would be redrawn by two clocks and would
     * flicker between two days.</p>
     */
    public void attachReplay(String label, PriceSeries live, Runnable detach) {
        detachReplay.run();

        this.replayLabel = label;
        this.detachReplay = detach == null ? () -> { } : detach;

        canvas.setSeries(live);
        retitle();
    }

    /**
     * The title, with how far the price is from the last session's close.
     *
     * <p>Beside the instrument's name because that is where a quote screen puts
     * it and where the eye goes first — and because the title is the one part of
     * a chart still readable when the window is behind three others. The percent
     * is dropped, not shown as zero, when the series carries a single day: there
     * is nothing to compare against, and a "0,00%" would be a claim.</p>
     */
    private String title() {
        if (replayLabel != null) {
            // The change against the previous close is dropped while replaying:
            // there IS no previous close in a session played on its own, and a
            // percentage measured against the first bar of the day would look
            // like the real thing and not be it.
            return name + "  " + canvas.period().label()
                    + "   " + Messages.get("replay.inTitle", replayLabel);
        }

        // The period in the title as well as inside the chart: the title is what
        // is readable when the window is behind two others.
        String scale = "  " + canvas.period().label();
        String change = Sessions.formatChange(
                Sessions.changeOnDay(canvas.series(), java.time.ZoneId.systemDefault()));

        return change.isEmpty() ? name + scale : name + scale + "   " + change;
    }

    private void retitle() {
        String title = title();

        if (docked != null) {
            docked.setTitle(title);
        }

        if (floating != null) {
            floating.setTitle(title);
        }
    }

    public ChartCanvas canvas() {
        return canvas;
    }

    /**
     * @return the layout bar, built on first use
     *
     * <p>It captures the chart's indicators into the selected layout on every
     * change. Adding an indicator and then having to find a save button is how
     * work gets lost.</p>
     */
    private LayoutBar layouts() {
        if (layoutBar == null) {
            layoutBar = new LayoutBar(canvas, key);

            canvas.onOverlaysChanged(layoutBar::capture);
        }

        return layoutBar;
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

        docked = new JInternalFrame(title(), true, true, true, true);

        docked.getContentPane().add(header(), BorderLayout.NORTH);
        docked.getContentPane().add(canvas, BorderLayout.CENTER);
        docked.getContentPane().add(layouts(), BorderLayout.SOUTH);
        boolean firstInside = countInside() == 0;
        boolean remembered = PREFS.getInt(key + ".width", -1) > 0;
        boolean wasMaximised = PREFS.getBoolean(key + ".maximised", false);

        if (remembered) {
            docked.setSize(restoredSize());
            docked.setLocation(cascadeInside());
        } else {
            docked.setSize(birthSize(firstInside));
            docked.setLocation(firstInside ? new java.awt.Point(0, 0) : cascadeInside());
        }

        docked.addInternalFrameListener(new InternalFrameAdapter() {

            @Override
            public void internalFrameClosing(InternalFrameEvent e) {
                store();
                onClosed.run();
            }
        });

        desktop.add(docked);
        docked.setVisible(true);

        if (opensMaximised(firstInside, remembered, wasMaximised)) {
            try {
                // Maximised rather than merely sized to fill: maximised, it
                // follows the desktop when the main window is resized, and the
                // frame offers the restore button. Sizing it to the current
                // bounds would leave a chart the size of yesterday's window.
                docked.setMaximum(true);
            } catch (PropertyVetoException e) {
                docked.setSize(desktop.getWidth(), desktop.getHeight());
            }
        }

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

        floating = new JFrame(title());

        floating.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        floating.getContentPane().add(header(), BorderLayout.NORTH);
        floating.getContentPane().add(canvas, BorderLayout.CENTER);
        floating.getContentPane().add(layouts(), BorderLayout.SOUTH);
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
        detachReplay.run();

        if (layoutBar != null) {
            layoutBar.capture();
        }

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

        content.remove(header);
        content.remove(canvas);
        content.remove(layouts());
        docked.dispose();
        docked = null;
    }

    private void detachFromFloating() {
        if (floating == null) {
            return;
        }

        storeFloatingBounds();

        floating.getContentPane().remove(header);
        floating.getContentPane().remove(canvas);
        floating.getContentPane().remove(layouts());
        floating.dispose();
        floating = null;
    }

    // ------------------------------------------------------------------ menu

    /**
     * @return the toolbar and legend, with the toolbar rebuilt for this frame
     *
     * <p>Rebuilt because one of its buttons names where the window is going --
     * "float" while docked, "dock" while floating -- and that is decided by the
     * frame being built. The legend is not rebuilt: it carries which indicators
     * the reader has hidden.</p>
     */
    private JPanel header() {
        if (toolBar != null) {
            header.remove(toolBar);
        }

        toolBar = buildToolBar();

        header.add(toolBar, BorderLayout.NORTH);

        return header;
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();

        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        ButtonGroup styles = new ButtonGroup();

        bar.add(styleButton(styles, "chart.style.candle", Icons.candle(15),
                () -> new CandleStyle()));
        bar.add(styleButton(styles, "chart.style.candleHollow", Icons.candleHollow(15),
                () -> new CandleStyle(true)));
        bar.add(styleButton(styles, "chart.style.line", Icons.line(15),
                () -> new LineStyle()));

        bar.addSeparator();

        JToggleButton wicks = new JToggleButton(Icons.wick(15));

        wicks.setToolTipText(Messages.get("chart.renkoWicks"));
        wicks.setFocusable(false);
        wicks.setSelected(canvas.hasWicks());
        wicks.setEnabled(canvas.isRenko());
        wicks.addActionListener(e -> canvas.setWicks(wicks.isSelected()));

        bar.add(wicks);
        bar.addSeparator();
        bar.add(button("chart.resetScale", Icons.fitVertical(15), canvas::resetStretch));
        bar.addSeparator();

        // The label names the DESTINATION, not the current state: "float" while
        // docked. A toggle labelled with where you are rather than where you go
        // is read backwards by half the people who see it.
        boolean floating = isFloating();

        bar.add(button(floating ? "chart.dock" : "chart.float",
                floating ? Icons.dock(15) : Icons.undock(15), this::toggleMode));

        return bar;
    }

    private JButton button(String key, javax.swing.Icon icon, Runnable action) {
        JButton entry = new JButton(icon);

        // Icon and tooltip, no text: five labelled buttons make a toolbar wider
        // than the chart it sits above. The tooltip carries the whole sentence,
        // which a menu label could not.
        entry.setToolTipText(Messages.get(key));
        entry.setFocusable(false);
        entry.addActionListener(e -> action.run());

        return entry;
    }

    private JToggleButton styleButton(ButtonGroup group, String key,
                                      javax.swing.Icon icon,
                                      java.util.function.Supplier<ChartStyle> style) {
        JToggleButton entry = new JToggleButton(icon);

        entry.setToolTipText(Messages.get(key));
        entry.setFocusable(false);
        entry.setSelected(key.equals(styleChoice) || key.endsWith(styleChoice));
        entry.addActionListener(e -> {
            styleChoice = key;

            canvas.setStyle(style.get());
        });

        group.add(entry);

        return entry;
    }

    private JMenuItem item(String messageKey, Runnable action) {
        JMenuItem entry = new JMenuItem(Messages.get(messageKey));

        entry.setMnemonic(Messages.mnemonic(messageKey));
        entry.addActionListener(e -> action.run());

        return entry;
    }

    // -------------------------------------------------------------- geometry

    /**
     * @return how many charts are already docked and on screen
     *
     * <p>Counted at the moment of docking rather than kept as a field: charts
     * close from several paths, and a counter that any one of them forgot to
     * decrement would size every later window wrongly, quietly.</p>
     */
    private int countInside() {
        int inside = 0;

        for (JInternalFrame frame : desktop.getAllFrames()) {
            if (frame.isVisible()) {
                inside++;
            }
        }

        return inside;
    }

    /**
     * @param firstInside whether this is the only chart in the desktop
     * @return the size a chart is born with, having none remembered
     *
     * <p>Alone it fills the window: one chart in a corner of an empty desktop
     * wastes the screen and reads as unfinished. With others already there it
     * takes a <b>quarter of the area</b> — half the width by half the height —
     * so it is large enough to read and small enough that arriving does not bury
     * what was being watched.</p>
     *
     * <p>Only when nothing is remembered. A chart that was resized and reopened
     * keeps the size it was given, which is the whole point of remembering
     * it.</p>
     */
    private Dimension birthSize(boolean firstInside) {
        int width = desktop.getWidth();
        int height = desktop.getHeight();

        // The desktop has no size before the window is laid out. Falling back to
        // the fixed default is better than a chart one pixel across.
        if (width < 40 || height < 40) {
            return new Dimension(DEFAULT_WIDTH, DEFAULT_HEIGHT);
        }

        return firstInside
                ? new Dimension(width, height)
                : new Dimension(width / 2, height / 2);
    }

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
     *
     * <p><b>Maximised is a state, not a size.</b> The bounds of a maximised frame
     * are the desktop's bounds, and storing them turns the state into yesterday's
     * measurements: the chart reopens the size the window happened to be last
     * night, no longer maximised, and no longer following the window when it is
     * resized. So the flag is stored, and the size stored alongside it is the one
     * the frame had BEFORE being maximised -- the size the restore button gives
     * back.</p>
     */
    private void storeDockedBounds() {
        boolean maximised = docked.isMaximum();
        Dimension size = sizeToRemember(maximised, docked.getNormalBounds(), docked.getBounds());

        PREFS.putBoolean(key + ".maximised", maximised);

        if (size != null) {
            PREFS.putInt(key + ".width", size.width);
            PREFS.putInt(key + ".height", size.height);
        }
    }

    /**
     * Which size to write down for a docked chart.
     *
     * @param maximised whether the frame is maximised right now
     * @param normal the bounds the restore button would give back
     * @param current the bounds the frame occupies
     * @return the size to remember, or null when neither is usable
     *
     * <p>Maximised, the current bounds are the desktop's -- writing them stores
     * last night's window as though the reader had chosen it. The normal bounds
     * are the answer, except when the frame was maximised before ever having a
     * size of its own: then they are empty, and a zero written here would reopen
     * a chart no pixels across that the restore button could not undo. Nothing
     * written is better than that; the birth size takes over.</p>
     */
    static Dimension sizeToRemember(boolean maximised, Rectangle normal, Rectangle current) {
        Rectangle chosen = maximised ? normal : current;

        return usable(chosen) ? new Dimension(chosen.width, chosen.height) : null;
    }

    /**
     * Whether a chart opens maximised.
     *
     * @param firstInside whether it is the only chart in the desktop
     * @param remembered whether a size was stored for it
     * @param wasMaximised whether it was maximised when last closed
     *
     * <p>Two independent reasons, and the remembered one wins over the count:
     * a chart the reader maximised comes back maximised even with five others
     * around it, because that is what was asked for. The lone-chart rule only
     * fills a first, empty desktop.</p>
     */
    static boolean opensMaximised(boolean firstInside, boolean remembered, boolean wasMaximised) {
        return wasMaximised || (firstInside && !remembered);
    }

    private static boolean usable(Rectangle bounds) {
        return bounds != null && bounds.width > 40 && bounds.height > 40;
    }
}
