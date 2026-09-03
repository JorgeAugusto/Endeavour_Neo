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
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.prefs.Preferences;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;

/**
 * One chart in a window of its own.
 *
 * <p>A chart lives in a window rather than a tab so several can be open at once
 * across several monitors — which is how anyone actually watches more than one
 * instrument. Tabs are the wrong container for that: they are exclusive by
 * construction, and only one is ever visible.</p>
 *
 * <p><b>Each window carries its own menu.</b> A window with no menu cannot
 * change its own style or scale, and reaching back to the main window to act on
 * "the chart in front" is exactly the kind of indirection that goes wrong the
 * moment two of them are open.</p>
 *
 * <p><b>Remembered bounds are validated against the screens that exist now.</b>
 * A window last closed on a second monitor, reopened after that monitor is
 * unplugged, lands at coordinates no screen covers — it opens invisible, and
 * from the user's side the program simply did not respond. So the stored
 * rectangle is only honoured when some device still contains it.</p>
 */
public final class ChartWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final Preferences PREFS = Preferences.userRoot()
            .node("br/com/jorge/reis/endeavourneo/charts");

    private static final int DEFAULT_WIDTH = 900;

    private static final int DEFAULT_HEIGHT = 560;

    /** How far each new window steps down and right from the previous one. */
    private static final int CASCADE_STEP = 28;

    private static int opened;

    private final transient ChartCanvas canvas = new ChartCanvas();

    private final String key;

    /**
     * @param name shown in the title bar and used to remember the geometry
     * @param owner the window to cascade from; may be null
     */
    public ChartWindow(String name, Window owner) {
        super(name);

        this.key = name.replaceAll("[^A-Za-z0-9]+", "_");

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setJMenuBar(buildMenuBar());

        add(canvas, BorderLayout.CENTER);

        restoreBounds(owner);

        addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                storeBounds();
            }
        });
    }

    public ChartCanvas getCanvas() {
        return canvas;
    }

    public void setSeries(PriceSeries series) {
        canvas.setSeries(series);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu chart = new JMenu(Messages.get("menu.chart"));

        chart.setMnemonic(Messages.mnemonic("menu.chart"));
        chart.add(item("chart.style.candle", () -> canvas.setStyle(new CandleStyle())));
        chart.add(item("chart.style.line", () -> canvas.setStyle(new LineStyle())));
        chart.addSeparator();
        chart.add(item("chart.resetScale", canvas::resetStretch));
        chart.addSeparator();
        chart.add(item("action.close", this::closeAndStore));

        bar.add(chart);

        return bar;
    }

    private JMenuItem item(String messageKey, Runnable action) {
        JMenuItem entry = new JMenuItem(Messages.get(messageKey));

        entry.setMnemonic(Messages.mnemonic(messageKey));
        entry.addActionListener(e -> action.run());

        return entry;
    }

    private void closeAndStore() {
        storeBounds();
        dispose();
    }

    /**
     * Restores the last geometry, or cascades from the owner on first open.
     *
     * <p>Cascading rather than centring: several charts opened in a row and all
     * centred land exactly on top of each other, and the user has to drag each
     * one aside before discovering the next is underneath.</p>
     */
    private void restoreBounds(Window owner) {
        int x = PREFS.getInt(key + ".x", Integer.MIN_VALUE);
        int y = PREFS.getInt(key + ".y", Integer.MIN_VALUE);
        int width = PREFS.getInt(key + ".width", DEFAULT_WIDTH);
        int height = PREFS.getInt(key + ".height", DEFAULT_HEIGHT);

        setSize(width, height);

        if (x != Integer.MIN_VALUE && y != Integer.MIN_VALUE
                && onSomeScreen(new Rectangle(x, y, width, height))) {
            setLocation(x, y);

            return;
        }

        int step = (opened++ % 8) * CASCADE_STEP;

        if (owner != null) {
            setLocation(owner.getX() + 40 + step, owner.getY() + 40 + step);
        } else {
            setLocationRelativeTo(null);
        }
    }

    /**
     * @param bounds a remembered rectangle
     * @return whether any screen still covers its top-left corner
     *
     * <p>The corner rather than the whole rectangle: a window hanging slightly
     * off the right edge is perfectly usable, while one whose title bar is
     * nowhere cannot even be dragged back.</p>
     */
    private static boolean onSomeScreen(Rectangle bounds) {
        for (GraphicsDevice device
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();

            if (screen.contains(bounds.x + Math.min(bounds.width / 2, 60), bounds.y + 10)) {
                return true;
            }
        }

        return false;
    }

    private void storeBounds() {
        PREFS.putInt(key + ".x", getX());
        PREFS.putInt(key + ".y", getY());
        PREFS.putInt(key + ".width", getWidth());
        PREFS.putInt(key + ".height", getHeight());
    }
}
