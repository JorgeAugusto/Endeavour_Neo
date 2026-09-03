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


import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import br.com.jorge.reis.endeavourneo.platform.JobService;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartHolder;
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.settings.AppearancePage;
import br.com.jorge.reis.endeavourneo.ui.settings.GeneralPage;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsDialog;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsPage;

import java.util.List;
import java.util.prefs.Preferences;
import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JDesktopPane;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * The application shell, laid out like an IDE.
 *
 * <pre>
 * +- JMenuBar -------------------------------------------+
 * +- JToolBar -------------------------------------------+
 * | Navigator |  JTabbedPane  (the "editors")            |
 * |  (JTree)  |                                          |
 * |           +------------------------------------------+
 * |           |  Console                                 |
 * +-----------+------------------------------------------+
 * | StatusBar                                            |
 * +------------------------------------------------------+
 * </pre>
 *
 * <p><b>Why no Eclipse RCP.</b> Every piece above has a direct Swing
 * equivalent, and none of them needs OSGi, Tycho or a native binary per
 * platform. What RCP charges dearly for is <i>extensibility</i> — third-party
 * plugins, extension points, p2 updates — and none of that is in play here. The
 * aesthetic and the organisation, which is what was wanted, come out of
 * {@code JSplitPane} + {@code JTabbedPane} + a decent look and feel.</p>
 *
 * <p><b>What is missing, and worth stating:</b> there is no real docking. You
 * cannot drag the console to the right edge or tear it off into its own window.
 * That is the expensive part of RCP and also the least used — worth adding when
 * its absence hurts, and not before.</p>
 *
 * <p>Divider positions and window size are stored in {@link Preferences}, which
 * ships with the JDK. That is the useful half of Eclipse perspectives: the
 * application reopens the way you left it.</p>
 */
public final class MainWindow extends JFrame {

    private static final long serialVersionUID = 1L;

    private static final Preferences PREFS = Preferences.userNodeForPackage(MainWindow.class);

    private static final String WIDTH = "window.width";

    private static final String HEIGHT = "window.height";

    private static final String LEFT_DIVIDER = "divider.left";

    private static final String BOTTOM_DIVIDER = "divider.bottom";

    private final Navigator navigator = new Navigator();

    /**
     * Where docked charts live.
     *
     * <p>A {@link JDesktopPane} rather than a tabbed pane because the charts are
     * windows even when contained: several visible at once, moved and resized
     * inside the frame. Tabs are exclusive by construction and would defeat the
     * point of having them contained at all.</p>
     */
    private final JDesktopPane desktop = new JDesktopPane();

    private final Console console = new Console();

    private final StatusBar status = new StatusBar();

    private final JSplitPane leftDivider;

    private final JSplitPane bottomDivider;

    private final transient JobService jobs;

    /**
     * The chart windows that are open, by name.
     *
     * <p>Kept so reopening a name fronts the existing window rather than
     * stacking a second one on top of it, and so closing the application takes
     * them all down. Without the registry the main window can exit while five
     * charts stay on screen, orphaned.</p>
     */
    private final transient java.util.Map<String, ChartHolder> charts =
            new java.util.LinkedHashMap<>();

    /**
     * @param title the window title
     * @param jobs where every long task goes; not null
     *
     * <p>The service is handed in rather than created here so a test can supply
     * its own, and so every window in the application shares one pool. A pool
     * per window would let two windows saturate the machine between them.</p>
     */
    public MainWindow(String title, JobService jobs) {
        super(title);

        this.jobs = jobs;

        setDefaultCloseOperation(EXIT_ON_CLOSE);

        desktop.setBackground(java.awt.Color.DARK_GRAY);

        bottomDivider = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                desktop, titled(Messages.get("view.console"), console));
        bottomDivider.setResizeWeight(1.0);
        bottomDivider.setBorder(null);

        leftDivider = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                titled(Messages.get("view.navigator"), navigator), bottomDivider);
        leftDivider.setResizeWeight(0.0);
        leftDivider.setBorder(null);

        setJMenuBar(buildMenuBar());

        add(buildToolBar(), BorderLayout.NORTH);
        add(leftDivider, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        navigator.onOpen(this::open);
        status.bind(jobs);

        restoreLayout();

        addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                closeCharts();
                storeLayout();
            }
        });
    }

    // ------------------------------------------------------------ public

    /**
     * Opens a chart in a window of its own, or fronts the one already open.
     *
     * <p><b>A window rather than a tab, deliberately.</b> Tabs are exclusive by
     * construction — only one is ever visible — and the whole point here is to
     * watch several charts at once, spread across monitors. Reopening the same
     * name brings the existing window forward instead of creating a second one,
     * which is the behaviour the tabs had and the one people expect.</p>
     */
    public void open(String series) {
        // ALWAYS a new chart, never fronting an existing one. A terminal is
        // expected to show the same instrument at several timeframes at once,
        // and two charts of the same series side by side is how one compares
        // zoom levels. Fronting instead -- the semantics of an IDE tab, one
        // editor per file -- is wrong for a chart and was the previous
        // behaviour.
        String title = uniqueTitle(series);
        ChartHolder holder = new ChartHolder(title, desktop, this, () -> charts.remove(title));

        // Synthetic bars for now. Replaced the moment a real series is wired in
        // -- see RandomWalkSeries.
        holder.canvas().setSeries(new RandomWalkSeries(2_000, 135_000.0));
        holder.canvas().addOverlay(
                new br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverages(17, 55, 200));

        charts.put(title, holder);

        // Opens in whichever mode it was last left in -- docked on first open.
        holder.show();

        console.write(Messages.get("console.opened", title));
        status.say(title);
    }

    /**
     * @param series the series name
     * @return that name, or it followed by a number when charts of it are open
     *
     * <p>The title has to distinguish them: two windows both called
     * {@code winn-1m} are indistinguishable in the Window menu, and their
     * remembered geometry would collide -- moving one would move the other on
     * the next launch.</p>
     */
    private String uniqueTitle(String series) {
        if (!charts.containsKey(series)) {
            return series;
        }

        for (int n = 2; ; n++) {
            String candidate = series + " (" + n + ")";

            if (!charts.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    /** Brings the named chart to the front. */
    public void front(String title) {
        ChartHolder holder = charts.get(title);

        if (holder != null) {
            holder.front();
        }
    }

    /** @return whether the named chart is floating; false when it is not open */
    public boolean isFloating(String name) {
        ChartHolder holder = charts.get(name);

        return holder != null && holder.isFloating();
    }

    /** Switches the named chart between docked and floating. */
    public void toggleChartMode(String name) {
        ChartHolder holder = charts.get(name);

        if (holder != null) {
            holder.toggleMode();
        }
    }

    /** @return the names of the chart windows open now, in the order opened */
    public java.util.List<String> openCharts() {
        return java.util.List.copyOf(charts.keySet());
    }

    /** Closes every chart; also the "close all" action. */
    public void closeCharts() {
        for (ChartHolder holder : new java.util.ArrayList<>(charts.values())) {
            holder.close();
        }

        charts.clear();
    }

    /**
     * Arranges the docked charts in a grid, filling the desktop.
     *
     * <p>Only the docked ones. A floating chart is managed by the operating
     * system, and moving it from here would fight the window manager -- and
     * would move a window the user deliberately put on another monitor.</p>
     */
    public void tileCharts() {
        java.util.List<javax.swing.JInternalFrame> frames = new java.util.ArrayList<>();

        for (javax.swing.JInternalFrame frame : desktop.getAllFrames()) {
            if (frame.isVisible() && !frame.isIcon()) {
                frames.add(frame);
            }
        }

        for (int i = 0; i < frames.size(); i++) {
            frames.get(i).setBounds(tileBounds(i, frames.size(),
                    desktop.getWidth(), desktop.getHeight()));
        }

        status.say(Messages.get("status.tiled", frames.size()));
    }

    /**
     * @param index which window, from zero
     * @param count how many there are
     * @param width the desktop's width
     * @param height the desktop's height
     * @return the cell that window should occupy
     *
     * <p>Columns come from the square root, so the cells stay close to square
     * rather than becoming letterbox strips. The last row absorbs the remainder,
     * which is what stops a leftover window from being squeezed into a sliver.</p>
     *
     * <p>Separate from the window handling so the arithmetic can be checked
     * without a desktop: the part that can be wrong is the layout, not the
     * setBounds call.</p>
     */
    static java.awt.Rectangle tileBounds(int index, int count, int width, int height) {
        if (count <= 0) {
            return new java.awt.Rectangle(0, 0, width, height);
        }

        int rows = Math.max(1, (int) Math.round(Math.sqrt(count)));

        // Rows hold as close to the same number as divides: with three windows
        // and two rows, two on top and one below. A fixed grid would put three
        // in a 2x2 and leave a quadrant empty, which is a visible hole rather
        // than a layout.
        int base = count / rows;
        int extra = count % rows;

        int row = 0;
        int before = 0;

        while (row < rows) {
            int inThisRow = base + (row < extra ? 1 : 0);

            if (index < before + inThisRow) {
                break;
            }

            before += inThisRow;
            row++;
        }

        int columns = base + (row < extra ? 1 : 0);
        int column = index - before;

        int cellWidth = width / columns;
        int cellHeight = height / rows;

        // The last cell of a row and the last row take whatever integer division
        // left behind, so the grid reaches the edges instead of leaving a strip
        // that reads as a misalignment.
        int w = column == columns - 1 ? width - cellWidth * column : cellWidth;
        int h = row == rows - 1 ? height - cellHeight * row : cellHeight;

        return new java.awt.Rectangle(cellWidth * column, cellHeight * row,
                Math.max(1, w), Math.max(1, h));
    }

    /** Brings every floating chart back inside the main window. */
    public void dockCharts() {
        for (ChartHolder holder : charts.values()) {
            holder.dock();
        }
    }

    public Console getConsole() {
        return console;
    }

    public StatusBar getStatus() {
        return status;
    }

    public JDesktopPane getDesktop() {
        return desktop;
    }

    // ------------------------------------------------------------ assembly

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu file = menu("menu.file");
        file.add(item("action.new", KeyEvent.VK_N, () -> open(Messages.get("document.untitled"))));
        file.addSeparator();
        file.add(item("action.preferences", KeyEvent.VK_COMMA, this::openPreferences));
        file.addSeparator();
        file.add(item("action.exit", KeyEvent.VK_Q, this::exit));

        JMenu view = menu("menu.view");
        view.add(item("action.clearConsole", KeyEvent.VK_L, console::clear));
        view.add(item("action.resetLayout", 0, this::defaultLayout));
        view.addSeparator();
        view.add(item("action.tileCharts", 0, this::tileCharts));
        view.add(item("action.dockCharts", 0, this::dockCharts));
        view.add(item("action.closeCharts", 0, this::closeCharts));

        JMenu run = menu("menu.run");
        run.add(item("action.sampleJob", 0, this::runSampleJob));
        run.add(item("action.cancelAll", 0, jobs::cancelAll));

        bar.add(file);
        bar.add(view);
        bar.add(run);
        bar.add(buildWindowMenu());

        return bar;
    }

    /**
     * The list of open charts, rebuilt each time it is shown.
     *
     * <p>Rebuilt rather than kept in step with events: the list changes whenever
     * a chart opens or closes, and a menu that is only correct if every one of
     * those paths remembered to update it will eventually be wrong. Building it
     * on the way open costs nothing at this size and cannot drift.</p>
     *
     * <p>It exists because once several charts are docked, one behind another,
     * the menu is the only way to reach the one underneath.</p>
     */
    private JMenu buildWindowMenu() {
        JMenu menu = menu("menu.window");

        menu.addMenuListener(new javax.swing.event.MenuListener() {

            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                menu.removeAll();

                if (charts.isEmpty()) {
                    JMenuItem empty = new JMenuItem(Messages.get("window.none"));

                    empty.setEnabled(false);
                    menu.add(empty);

                    return;
                }

                for (String title : charts.keySet()) {
                    JMenuItem entry = new JMenuItem(title);

                    entry.addActionListener(chosen -> front(title));
                    menu.add(entry);
                }
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
                // nothing to undo
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
                // nothing to undo
            }
        });

        return menu;
    }

    /**
     * Opens the preferences dialog.
     *
     * <p>Every setting lives in one dialog, the way Eclipse and IntelliJ do it.
     * That is worth copying for a reason that is not imitation: options
     * scattered across menus produce the application where nobody can find the
     * one switch they need. Adding a settings page means writing a {@link
     * SettingsPage} and adding it to the list below.</p>
     */
    private void openPreferences() {
        SettingsDialog.show(this, List.of(
                new GeneralPage(),
                new AppearancePage(installed -> {
                    console.write(Messages.get("console.appearance", installed));
                    status.say(installed);
                })));
    }

    /**
     * A task that does nothing useful, and proves the whole chain works.
     *
     * <p>It exists so the threading contract can be seen rather than trusted:
     * the window stays responsive while it runs, the status bar names it, the
     * cancel button stops it, and the result arrives on the interface thread.
     * Delete it once there is real work to run — but not before.</p>
     */
    private void runSampleJob() {
        jobs.submit(Messages.get("job.sample"), progress -> {
            long total = 0;

            for (int i = 1; i <= 100; i++) {
                if (progress.cancelled()) {
                    // Return what we have. Throwing would be wrong: the user
                    // asked to stop, which is not an error.
                    return total;
                }

                progress.say(Messages.get("job.sampleStage", i));
                progress.report(i / 100.0);

                Thread.sleep(40);

                total += i;
            }

            return total;
        }).whenDone(sum -> {
            console.write(Messages.get("job.done", sum));
            status.say(Messages.get("status.ready"));
        }).whenFailed(error -> {
            console.write(Messages.get("job.failed", String.valueOf(error)));
            status.say(Messages.get("job.failed", error.getClass().getSimpleName()));
        });
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        bar.add(button("action.new", () -> open(Messages.get("document.untitled"))));
        bar.addSeparator();
        bar.add(iconButton("action.tileCharts", Icons.tile(16), this::tileCharts));
        bar.addSeparator();
        bar.add(button("action.clearConsole", console::clear));

        return bar;
    }

    /**
     * A menu item with a shortcut, built from an {@link Action}.
     *
     * <p>This is the practical equivalent of Eclipse <i>commands</i>: the action
     * exists once and shows up in as many places as needed — menu, toolbar,
     * keystroke, context menu — without duplicating the code it runs.</p>
     */
    private static JMenu menu(String key) {
        JMenu menu = new JMenu(Messages.get(key));

        menu.setMnemonic(Messages.mnemonic(key));

        return menu;
    }

    private static JMenuItem item(String key, int accelerator, Runnable action) {
        Action wrapped = new AbstractAction(Messages.get(key)) {

            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };

        if (accelerator != 0) {
            wrapped.putValue(Action.ACCELERATOR_KEY, KeyStroke.getKeyStroke(accelerator,
                    Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        }

        JMenuItem menuItem = new JMenuItem(wrapped);

        menuItem.setMnemonic(Messages.mnemonic(key));

        return menuItem;
    }

    /**
     * A toolbar button that is only an icon.
     *
     * <p>The label moves to the tooltip rather than disappearing. An icon with
     * no name is a guess for anyone who did not draw it, and a toolbar of
     * unlabelled glyphs is learned by trial.</p>
     */
    private static JButton iconButton(String key, javax.swing.Icon icon, Runnable action) {
        JButton button = new JButton(icon);

        button.setToolTipText(Messages.get(key));
        button.setFocusable(false);
        button.addActionListener(e -> action.run());

        return button;
    }

    private static JButton button(String key, Runnable action) {
        JButton button = new JButton(Messages.get(key));

        button.setFocusable(false);
        button.addActionListener(e -> action.run());

        return button;
    }

    /** A titled panel, standing in for an Eclipse view tab. */
    private static JComponent titled(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());

        JLabel caption = new JLabel(title);
        caption.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

        panel.add(caption, BorderLayout.NORTH);
        panel.add(content, BorderLayout.CENTER);

        return panel;
    }

    // ------------------------------------------------------------ state

    private void restoreLayout() {
        int width = PREFS.getInt(WIDTH, 1280);
        int height = PREFS.getInt(HEIGHT, 800);

        setSize(new Dimension(width, height));
        setLocationRelativeTo(null);

        // Dividers only accept a position once the window has a size.
        SwingUtilities.invokeLater(() -> {
            leftDivider.setDividerLocation(PREFS.getInt(LEFT_DIVIDER, 260));
            bottomDivider.setDividerLocation(
                    PREFS.getInt(BOTTOM_DIVIDER, (int) (height * 0.68)));
        });
    }

    private void storeLayout() {
        PREFS.putInt(WIDTH, getWidth());
        PREFS.putInt(HEIGHT, getHeight());
        PREFS.putInt(LEFT_DIVIDER, leftDivider.getDividerLocation());
        PREFS.putInt(BOTTOM_DIVIDER, bottomDivider.getDividerLocation());
    }

    private void defaultLayout() {
        leftDivider.setDividerLocation(260);
        bottomDivider.setDividerLocation((int) (getHeight() * 0.68));

        console.write(Messages.get("console.layoutReset"));
    }

    private void exit() {
        closeCharts();
        storeLayout();
        dispose();
        System.exit(0);
    }
}
