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
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.style.CandleStyle;
import br.com.jorge.reis.endeavourneo.ui.chart.style.LineStyle;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.settings.AppearancePage;
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
import javax.swing.JTabbedPane;
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

    private final JTabbedPane editors = new JTabbedPane();

    private final Console console = new Console();

    private final StatusBar status = new StatusBar();

    private final JSplitPane leftDivider;

    private final JSplitPane bottomDivider;

    private final transient JobService jobs;

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

        bottomDivider = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                borderless(editors), titled(Messages.get("view.console"), console));
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
                storeLayout();
            }
        });
    }

    // ------------------------------------------------------------ public

    /** Opens a tab, or brings the existing one with that name to the front. */
    public void open(String name) {
        for (int i = 0; i < editors.getTabCount(); i++) {
            if (editors.getTitleAt(i).equals(name)) {
                editors.setSelectedIndex(i);

                return;
            }
        }

        // Every tab is a chart for now, on synthetic bars. Replaced the moment
        // a real series is wired in -- see RandomWalkSeries.
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(new RandomWalkSeries(2_000, 135_000.0));

        JPanel holder = new JPanel(new BorderLayout());
        holder.add(canvas, BorderLayout.CENTER);

        editors.addTab(name, holder);
        editors.setSelectedComponent(holder);

        console.write(Messages.get("console.opened", name));
        status.say(name);
    }

    public Console getConsole() {
        return console;
    }

    public StatusBar getStatus() {
        return status;
    }

    public JTabbedPane getEditors() {
        return editors;
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
        view.add(item("chart.style.candle", 0, () -> applyStyle(new CandleStyle())));
        view.add(item("chart.style.line", 0, () -> applyStyle(new LineStyle())));

        JMenu run = menu("menu.run");
        run.add(item("action.sampleJob", 0, this::runSampleJob));
        run.add(item("action.cancelAll", 0, jobs::cancelAll));

        bar.add(file);
        bar.add(view);
        bar.add(run);

        return bar;
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

    /**
     * Switches the drawing style of the chart in front.
     *
     * <p>Only the visible one: a style is a per-chart choice, the way it is in
     * every terminal. Changing all of them at once would be a preference, and
     * this is not one.</p>
     */
    private void applyStyle(br.com.jorge.reis.endeavourneo.ui.chart.ChartStyle style) {
        java.awt.Component tab = editors.getSelectedComponent();

        if (tab instanceof JPanel panel && panel.getComponentCount() > 0
                && panel.getComponent(0) instanceof ChartCanvas canvas) {
            canvas.setStyle(style);
            status.say(Messages.get(style.nameKey()));
        }
    }

    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        bar.add(button("action.new", () -> open(Messages.get("document.untitled"))));
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

    private static JComponent borderless(JComponent component) {
        component.setBorder(BorderFactory.createEmptyBorder());

        return component;
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
        storeLayout();
        dispose();
        System.exit(0);
    }
}
