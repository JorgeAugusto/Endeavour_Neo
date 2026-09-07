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
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import br.com.jorge.reis.endeavourneo.platform.JobService;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartHolder;
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;
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

    /** The log, which folds down to its caption. */
    private final transient CollapsiblePane consolePane;

    /** Where the divider was before the log was folded, to put it back. */
    private transient int consoleWasAt = -1;

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
    /**
     * True while the remembered charts are being reopened.
     *
     * <p>Opening a chart remembers what is open. During a restore that would
     * rewrite the list being read from, one chart shorter than it should be --
     * and the restore would bring back only the first.</p>
     */
    private transient boolean restoring;

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

        consolePane = new CollapsiblePane(Messages.get("view.console"), console, "console");

        bottomDivider = new JSplitPane(JSplitPane.VERTICAL_SPLIT, desktop, consolePane);
        bottomDivider.setResizeWeight(1.0);
        bottomDivider.setBorder(null);

        // The pane says it folded; moving the divider is this window's job,
        // because only this window knows the pane is in a split at all.
        consolePane.onToggle(this::followConsoleFold);

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

        // After the frame is on screen: a chart works out its opening size from
        // the desktop, and the desktop has no size until the window is laid out.
        // Restoring here in the constructor would give every chart the fallback
        // size instead of a quarter of the window.
        javax.swing.SwingUtilities.invokeLater(this::restoreCharts);

        addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                leave();
            }
        });
    }

    /**
     * Everything a way out of the application does, before the way out itself.
     *
     * <p><b>One method because there is more than one way out</b>, and they had
     * drifted. The X on the title bar ran these three; the File menu ran
     * closeCharts and storeLayout without prepareToLeave, and every close inside
     * closeCharts then called rememberCharts, which rewrote the open list one
     * chart shorter until it was empty. Leaving by the menu silently discarded
     * every chart the reader had arranged. The guard in rememberCharts describes
     * that failure exactly and was simply never armed on that path.</p>
     *
     * <p>Package-private so the test can run the real sequence rather than
     * retype it — a test that retypes the steps passes while the application
     * takes a different route, which is what happened here.</p>
     */
    void leave() {
        prepareToLeave();
        closeCharts();
        storeLayout();
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
    /**
     * Writes down which charts are open, so they come back.
     *
     * <p>On every open and close rather than at exit: an application that saves
     * on the way out saves nothing when it does not get to leave, and this one
     * is meant to be left running overnight.</p>
     *
     * <p>The NAME is stored, not the window's own title. "winn-1m (2)" is what
     * the second chart of a series is called, and reopening has to ask for the
     * series and let the numbering happen again -- otherwise a restart leaves
     * "(2)" with no "(1)" beside it.</p>
     */
    /**
     * Writes what is open, then freezes the list.
     *
     * <p>In that order and as one step, because the two halves are only correct
     * together. Closing the application closes every chart, and each close asks
     * for the list to be rewritten one chart shorter -- so without the freeze it
     * would end empty and nothing would come back. It used to be written out at
     * each of the two exits, which is how the test ended up simulating an exit
     * that the application never performs.</p>
     */
    void prepareToLeave() {
        rememberCharts();

        leaving = true;
    }

    void rememberCharts() {
        if (leaving || restoring) {
            // Two reasons, both of them a list being rewritten while it is
            // being read. Closing the application closes every chart, and each close would
            // rewrite this list one chart shorter until it was empty. The list
            // was already written the moment before; the closing itself must
            // not touch it. Without this the feature saves nothing and looks
            // like it works, because it is only ever read after a restart.
            return;
        }

        Settings workspace = Settings.workspace();

        workspace.removeStartingWith("chart.open.");

        int at = 0;

        for (java.util.Map.Entry<String, ChartHolder> each : charts.entrySet()) {
            workspace.put("chart.open." + at + ".series", seriesOf(each.getKey()));
            workspace.put("chart.open." + at + ".period", each.getValue().canvas().periodCode());

            at++;
        }
    }

    /** @return the series a chart title came from, with any "(2)" taken off */
    private static String seriesOf(String title) {
        int bracket = title.lastIndexOf(" (");

        return bracket > 0 && title.endsWith(")") ? title.substring(0, bracket) : title;
    }

    /**
     * Reopens the charts that were open last time.
     *
     * <p>Nothing at all on the first run, which is right: an empty desktop with
     * the menus in it says "open something" more clearly than a chart of
     * whatever the application decided to guess.</p>
     */
    void restoreCharts() {
        Settings workspace = Settings.workspace();

        // The WHOLE list, read before a single chart is opened. Opening one
        // remembers what is open, and remembering erases and rewrites these
        // very keys -- so reading them as it went, the loop destroyed the entry
        // it was about to reach. Two charts came back as one, which is exactly
        // how it was reported.
        List<String[]> wanted = new java.util.ArrayList<>();

        for (String key : workspace.keysStartingWith("chart.open.")) {
            if (!key.endsWith(".series")) {
                continue;
            }

            String series = workspace.get(key, null);

            if (series == null || series.isBlank()) {
                continue;
            }

            wanted.add(new String[]{series,
                    workspace.get(key.replace(".series", ".period"), null)});
        }

        restoring = true;

        try {
            for (String[] each : wanted) {
                String title = open(each[0]);
                PeriodCatalog.Choice choice = PeriodCatalog.byCode(each[1]);

                if (choice != null) {
                    ChartHolder holder = charts.get(title);

                    if (holder != null) {
                        holder.canvas().setPeriod(choice.aggregation(),
                                choice.title(), choice.code());
                    }
                }
            }
        } finally {
            restoring = false;
        }

        // Written once, at the end, rather than once per chart opened.
        rememberCharts();
    }

    /**
     * @param name a series's name
     * @param title what the window will be called, for the message if it fails
     * @return the bars to draw
     *
     * <p>The synthetic walk is the answer only when there is no series at all —
     * on a machine where the data folder has not been found yet, the
     * application still opens and still draws. It is never the answer when a
     * series exists and fails to read: that says so out loud, because prices that
     * are not the market's, drawn without a word, are the one thing a chart
     * must never do.</p>
     */
    private PriceSeries seriesFor(String name, String title) {
        try {
            java.util.Optional<PriceSeries> series = SeriesCatalog.open(name);

            if (series.isPresent()) {
                console.write(Messages.get("console.seriesLoaded", name,
                        String.valueOf(series.get().size())));

                return series.get();
            }
        } catch (java.io.IOException e) {
            console.write(Messages.get("console.seriesFailed", name, String.valueOf(e.getMessage())));
            status.say(Messages.get("console.seriesFailed", name, String.valueOf(e.getMessage())));

            // EMPTY, and not the walk. The paragraph above says the walk is
            // never the answer when a series exists and fails to read, and this
            // catch used to fall straight through to it: a file that would not
            // open became two thousand invented prices drawn under the
            // instrument's name. The message went to the console, where it
            // scrolls away; the chart stayed, looking like a market.
            //
            // Empty draws nothing, which is what is known. Whoever reads the
            // window sees no prices and the reason beside them, instead of
            // prices that were never traded.
            return PriceSeries.empty();
        }

        // Only here: no series of that name at all. On a machine where the data
        // folder has not been found yet, the application still opens and still
        // draws something.
        console.write(Messages.get("console.seriesMissing", SeriesCatalog.folder().toString()));

        return new RandomWalkSeries(2_000, 135_000.0);
    }

    /** @return the title the chart just opened for that series ended up with */
    private String uniqueTitleOf(String series) {
        String last = series;

        for (String title : charts.keySet()) {
            if (title.equals(series) || title.startsWith(series + " (")) {
                last = title;
            }
        }

        return last;
    }

    /**
     * @param series a series's name, or any name at all
     * @return the title the chart ended up with, which is NOT always what was
     *         asked for -- see below
     */
    public String open(String series) {
        // A name that is not a series opens the default one instead, and is
        // titled after it. Workspaces written before there was a series hold
        // names like "Sem título", and showing prices under a title that names
        // no instrument is worse than quietly correcting it -- which also
        // repairs the entry, since what is open is what gets remembered.
        // A name can ask for a stretch: "winfull-1m#treino". See Segmentation.
        String asked = br.com.jorge.reis.endeavourneo.platform.Segmentation.seriesIn(series);
        br.com.jorge.reis.endeavourneo.domain.market.Segment segment =
                br.com.jorge.reis.endeavourneo.platform.Segmentation.segmentIn(series);

        // THE LOCK. A series the reader marked as segments-only does not open
        // whole, and saying so out loud is the entire point: the alternative is
        // a window full of test data that looks like every other window.
        if (segment == null && SeriesCatalog.has(asked)
                && br.com.jorge.reis.endeavourneo.platform.Segmentation.segmentsOnly(asked)) {
            console.write(Messages.get("console.locked", asked));
            status.say(Messages.get("console.locked", asked));

            return null;
        }

        String name = SeriesCatalog.has(asked) ? asked : SeriesCatalog.defaultName();

        // ALWAYS a new chart, never fronting an existing one. A terminal is
        // expected to show the same instrument at several timeframes at once,
        // and two charts of the same series side by side is how one compares
        // zoom levels. Fronting instead -- the semantics of an IDE tab, one
        // editor per file -- is wrong for a chart and was the previous
        // behaviour.
        // The guard is on ASKED, not on name: name has already fallen back to
        // defaultName, so has(name) is true either way and the ternary compared
        // nothing -- both its branches were the same expression. When the asked
        // series is gone, the window has to carry the name of what actually
        // opened, or rememberCharts writes the dead name back and the entry
        // never repairs itself, which is what the comment above promises.
        String title = uniqueTitle(SeriesCatalog.has(asked) ? series : name);

        // The NAME, not the key. See ChartHolder.label.
        String shown = SeriesCatalog.displayOf(name)
                + (segment == null ? "" : "  \u00b7  " + segment.name());
        ChartHolder holder = new ChartHolder(title, shown, desktop, this, () -> {
            charts.remove(title);
            rememberCharts();
        });

        // The canvas needs the instrument to find its tick sessions: they are
        // named winfut-2021-01-04.bin and the chart calls itself winfut-1m.
        holder.canvas().setInstrument(name);

        // Sliced, or not: SegmentedSeries hands back the base itself when there
        // is no segment, so nothing below has to know which of the two it got.
        holder.canvas().setSeries(
                br.com.jorge.reis.endeavourneo.domain.market.SegmentedSeries.of(
                        seriesFor(name, title), segment, java.time.ZoneId.systemDefault()));

        // Every chart accepts a replay dropped on it, from the moment it opens.
        br.com.jorge.reis.endeavourneo.ui.replay.ReplayDrop.enable(holder);
        // Three averages, three indicators. One indicator drawing three lines
        // meant they shared one set of settings: no colouring one, no hiding
        // one, no changing one.
        for (int period : new int[]{17, 55, 200}) {
            holder.canvas().addOverlay(
                    new br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage(period));
        }

        charts.put(title, holder);
        rememberCharts();

        // The footer follows the chart the POINTER is on, not the one with
        // focus: a price under a cursor that is somewhere else is not a
        // reading of anything.
        holder.canvas().onCursorChanged(() -> report(holder));

        // The same opener the tree uses. A segment picked in the header and one
        // picked in the tree have to land on the same window, or the reader
        // ends up with two charts of the same thing.
        holder.onOpenWanted(this::open);

        // Opens in whichever mode it was last left in -- docked on first open.
        holder.show();

        console.write(Messages.get("console.opened", title));
        status.say(title);

        // The title, because the caller cannot work it out: it depends on
        // whether the name was a series and on what was already open. Restoring a
        // workspace looked the chart up by the name it asked for, and quietly
        // found nothing the moment that name stopped being the title.
        return title;
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
    /**
     * @return the chart with that title, or null
     *
     * <p>Package-visible for the test that proves a closed chart stays closed.
     * Closing it through the map instead would prove nothing about the wiring
     * that a reader's click actually goes through.</p>
     */
    ChartHolder chartNamed(String title) {
        return charts.get(title);
    }

    public java.util.List<String> openCharts() {
        return java.util.List.copyOf(charts.keySet());
    }

    /** Closes every chart; also the "close all" action. */
    /** True from the moment the window starts closing, so the list stops moving. */
    private transient boolean leaving;

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
        int series = count / rows;
        int extra = count % rows;

        int row = 0;
        int before = 0;

        while (row < rows) {
            int inThisRow = series + (row < extra ? 1 : 0);

            if (index < before + inThisRow) {
                break;
            }

            before += inThisRow;
            row++;
        }

        int columns = series + (row < extra ? 1 : 0);
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

    /**
     * Writes one chart's state into the footer.
     *
     * <p>Here rather than inside the chart because the footer belongs to the
     * shell: a chart that wrote to it directly would be one of several windows
     * all writing to the same line, and the last one to move a mouse would
     * win.</p>
     */
    private void report(ChartHolder holder) {
        var canvas = holder.canvas();
        String reading = canvas.cursorReading();

        if (reading.isEmpty()) {
            // The pointer left. The chart's NAME stays -- it is still the one
            // being looked at, and blanking it would make the bar flicker
            // every time the mouse crossed the axis.
            status.chart(identityOf(holder), "", canvas.modeLabel());

            return;
        }

        status.chart(identityOf(holder), reading, canvas.modeLabel());
    }

    private static String identityOf(ChartHolder holder) {
        String scale = holder.canvas().periodLabel();

        return scale == null || scale.isBlank()
                ? holder.label() : holder.label() + "  ·  " + scale;
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
        file.add(item("action.new", KeyEvent.VK_N, () -> open(SeriesCatalog.defaultName())));
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

        JMenu tools = menu("menu.tools");
        tools.add(item("action.series", 0, this::openSeries));
        tools.add(item("action.replay", 0, this::openReplay));

        JMenu run = menu("menu.run");
        run.add(item("action.sampleJob", 0, this::runSampleJob));
        run.add(item("action.cancelAll", 0, jobs::cancelAll));

        bar.add(file);
        bar.add(view);
        bar.add(tools);
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
    /**
     * Opens the replay transport, or brings it back to the front.
     *
     * <p>One window and not one per chart: a session is dragged onto as many
     * charts as wanted, and they all run off the same clock. Two transports
     * would be two clocks, and the charts would disagree about what time it is.</p>
     */
    /** The transport, built on first use and kept: one clock for every chart. */
    private transient br.com.jorge.reis.endeavourneo.ui.replay.ReplayWindow replay;

    private void openReplay() {
        if (replay == null) {
            replay = new br.com.jorge.reis.endeavourneo.ui.replay.ReplayWindow(this);
        }

        replay.setVisible(true);
        replay.toFront();
    }

    /**
     * Builds this window again, in the language just chosen.
     *
     * <p>Every label is read once, when its component is built, so there is
     * nothing to re-read: the window is made again. It costs nothing extra
     * because restoring the open charts is what the application does on every
     * launch anyway — this is that path, run without leaving.</p>
     */
    private void relaunch() {
        prepareToLeave();
        storeLayout();

        if (replay != null) {
            replay.dispose();
            replay = null;
        }

        closeCharts();
        dispose();

        br.com.jorge.reis.endeavourneo.platform.Language.install();

        // Built after the locale changes, or the title would still be the old
        // language while everything inside it was the new one.
        MainWindow fresh = new MainWindow(Messages.get("app.title"), jobs);

        fresh.setVisible(true);
    }

    /** Opens the window where a series is divided into segments. */
    private void openSeries() {
        br.com.jorge.reis.endeavourneo.ui.series.SeriesWindow.open(this,
                () -> navigator.setModel(Navigator.treeModel()));
    }

    private void openPreferences() {
        br.com.jorge.reis.endeavourneo.platform.Language before =
                br.com.jorge.reis.endeavourneo.platform.Language.remembered();

        SettingsDialog.show(this, List.of(
                new GeneralPage(),
                new br.com.jorge.reis.endeavourneo.ui.settings.ChartPage(),
                new br.com.jorge.reis.endeavourneo.ui.settings.ReplayPage(),
                new AppearancePage(installed -> {
                    console.write(Messages.get("console.appearance", installed));
                    status.say(installed);
                })));

        if (br.com.jorge.reis.endeavourneo.platform.Language.remembered() != before) {
            relaunch();
        }
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

        bar.add(button("action.new", () -> open(SeriesCatalog.defaultName())));
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
    /**
     * Moves the divider so a folded log shows only its caption.
     *
     * <p>And puts it back where the reader had it. A fold that reopened at some
     * default height would cost them the size they chose every time they peeked
     * at the desktop -- which is most of the reason to fold it at all.</p>
     */
    private void followConsoleFold() {
        if (consolePane.isFolded()) {
            consoleWasAt = bottomDivider.getDividerLocation();

            consoleWasAt = PREFS.getInt(BOTTOM_DIVIDER, -1);
            bottomDivider.setDividerLocation(bottomDivider.getHeight()
                    - consolePane.foldedHeight() - bottomDivider.getDividerSize());

            return;
        }

        bottomDivider.setDividerLocation(consoleWasAt > 0
                ? consoleWasAt : (int) (getHeight() * 0.68));
    }

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
        leave();
        dispose();
        System.exit(0);
    }
}
