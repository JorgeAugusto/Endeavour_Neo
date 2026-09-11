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
package br.com.jorge.reis.endeavourneo.ui.shell;

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyVetoException;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDesktopPane;
import javax.swing.JFrame;
import javax.swing.JInternalFrame;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.event.InternalFrameAdapter;
import javax.swing.event.InternalFrameEvent;

/**
 * Anything that wants to live inside the main window, or loose beside it.
 *
 * <p>The charts have had this since the beginning, and it was written into
 * {@link br.com.jorge.reis.endeavourneo.ui.chart.ChartHolder} together with
 * everything else a chart is — a canvas, studies, layouts, a replay. So the
 * second thing that wanted a window could only have a loose one.</p>
 *
 * <p>This is the docking half on its own, holding any component. Nothing in it
 * knows what it is carrying.</p>
 *
 * <h2>The two modes are the same pane, moved</h2>
 *
 * <p>Docking and floating do not build two copies of the content; they take it
 * out of one frame and put it into the other. A backtest with a result on
 * screen keeps the result when it is set loose — rebuilding would throw away
 * the run, which from the reader's side is the window losing their work because
 * they pressed a button about where it sits.</p>
 *
 * <h2>What it remembers</h2>
 *
 * <p>Which mode it was in, its size, and — for the floating one — where it was.
 * The position is honoured only while some screen still covers it: a window
 * last closed on a second monitor and reopened after that monitor is gone would
 * otherwise open at coordinates nothing covers, which from the reader's side is
 * the program not responding.</p>
 */
public final class DockablePane {

    private static final Settings PREFS = Settings.workspace();

    private static final int DEFAULT_WIDTH = 1_000;

    private static final int DEFAULT_HEIGHT = 680;

    private static final int CASCADE_STEP = 24;

    private static int opened;

    private final String key;

    private final String title;

    private final JPanel body = new JPanel(new BorderLayout());

    private final JDesktopPane desktop;

    private final Window owner;

    private final Runnable onClosed;

    private JToolBar bar;

    private JButton toggle;

    private JInternalFrame docked;

    private JFrame floating;

    /**
     * @param key      how this pane is named in the workspace file
     * @param title    what the frame says
     * @param content  what to carry; it is never rebuilt
     * @param desktop  where a docked pane goes
     * @param owner    the main window, for placing a loose one beside it
     * @param onClosed run after the pane is gone, for the caller to forget it
     */
    public DockablePane(String key, String title, JComponent content,
                        JDesktopPane desktop, Window owner, Runnable onClosed) {
        this.key = key;
        this.title = title;
        this.desktop = desktop;
        this.owner = owner;
        this.onClosed = onClosed == null ? () -> { } : onClosed;

        body.add(bar(), BorderLayout.NORTH);
        body.add(content, BorderLayout.CENTER);
    }

    private JToolBar bar() {
        bar = new JToolBar();
        bar.setFloatable(false);

        toggle = new JButton(Icons.undock(15));
        toggle.setToolTipText(Messages.get("action.undock"));
        toggle.setFocusable(false);
        toggle.addActionListener(e -> toggleMode());

        bar.add(toggle);

        return bar;
    }

    private void refreshToggle() {
        boolean loose = isFloating();

        toggle.setIcon(loose ? Icons.dock(15) : Icons.undock(15));
        toggle.setToolTipText(Messages.get(loose ? "action.dock" : "action.undock"));
    }

    // -------------------------------------------------------------- the modes

    /** Opens it the way it was last left, or brings it forward if it is already up. */
    public void show() {
        if (docked != null || floating != null) {
            front();

            return;
        }

        if (PREFS.getBoolean(key + ".floating", false)) {
            floatIt();
        } else {
            dock();
        }
    }

    /** Puts it inside the main window. */
    public void dock() {
        if (docked != null) {
            front();

            return;
        }

        detachFromFloating();

        docked = new JInternalFrame(title, true, true, true, true);

        docked.getContentPane().add(body, BorderLayout.CENTER);
        docked.setSize(restoredSize());
        docked.setLocation(cascadeInside());

        docked.addInternalFrameListener(new InternalFrameAdapter() {

            @Override
            public void internalFrameClosing(InternalFrameEvent e) {
                // Through close(), so the X and the programmatic path do the
                // same things in the same order.
                close();
            }
        });

        desktop.add(docked);
        docked.setVisible(true);

        PREFS.putBoolean(key + ".floating", false);

        refreshToggle();
        front();
    }

    /** Sets it free, in a window the operating system manages. */
    public void floatIt() {
        if (floating != null) {
            front();

            return;
        }

        detachFromDocked();

        floating = new JFrame(title);

        floating.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        floating.getContentPane().add(body, BorderLayout.CENTER);
        floating.setSize(restoredSize());
        floating.setLocation(restoredLocation());

        floating.addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                close();
            }
        });

        floating.setVisible(true);

        PREFS.putBoolean(key + ".floating", true);

        refreshToggle();
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

    /** @return whether it is loose rather than inside the main window */
    public boolean isFloating() {
        return floating != null;
    }

    /** Brings it to the front of whichever world it is in. */
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
            } catch (PropertyVetoException refused) {
                // Another component refused the change. Nothing to repair --
                // the pane is still on screen, just not focused.
                docked.toFront();
            }
        }
    }

    /** Takes it off screen for good. */
    public void close() {
        detachFromDocked();
        detachFromFloating();

        onClosed.run();
    }

    // ----------------------------------------------------------- moving house

    private void detachFromDocked() {
        if (docked == null) {
            return;
        }

        store(docked.getSize(), null);

        docked.getContentPane().remove(body);
        docked.dispose();
        docked = null;
    }

    private void detachFromFloating() {
        if (floating == null) {
            return;
        }

        store(floating.getSize(), floating.getLocation());

        floating.getContentPane().remove(body);
        floating.dispose();
        floating = null;
    }

    private void store(Dimension size, Point where) {
        if (size.width > 0 && size.height > 0) {
            PREFS.putInt(key + ".width", size.width);
            PREFS.putInt(key + ".height", size.height);
        }

        if (where != null) {
            PREFS.putInt(key + ".x", where.x);
            PREFS.putInt(key + ".y", where.y);
        }
    }

    private Dimension restoredSize() {
        return new Dimension(PREFS.getInt(key + ".width", DEFAULT_WIDTH),
                PREFS.getInt(key + ".height", DEFAULT_HEIGHT));
    }

    private Point restoredLocation() {
        int x = PREFS.getInt(key + ".x", Integer.MIN_VALUE);
        int y = PREFS.getInt(key + ".y", Integer.MIN_VALUE);

        if (x != Integer.MIN_VALUE && onSomeScreen(x, y)) {
            return new Point(x, y);
        }

        int step = (opened++ % 8) * CASCADE_STEP;

        return owner != null
                ? new Point(owner.getX() + 40 + step, owner.getY() + 40 + step)
                : new Point(80 + step, 80 + step);
    }

    private static Point cascadeInside() {
        int step = (opened++ % 8) * CASCADE_STEP;

        return new Point(step, step);
    }

    /** @return whether a title bar at that corner would land on some monitor */
    static boolean onSomeScreen(int x, int y) {
        for (GraphicsDevice device
                : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            Rectangle screen = device.getDefaultConfiguration().getBounds();

            if (screen.contains(x, y)) {
                return true;
            }
        }

        return false;
    }
}
