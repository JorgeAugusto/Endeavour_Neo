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

import java.awt.BorderLayout;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;
import br.com.jorge.reis.endeavourneo.domain.market.Segment;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import java.util.List;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

/**
 * The tree on the left — the Eclipse Navigator.
 *
 * <p>This is the component that makes an application feel like it has
 * <b>content</b> rather than merely screens: what exists is listed, and clicking
 * opens it. Without one, every route in becomes a menu, and a menu does not show
 * you what is there.</p>
 *
 * <p>It starts with a sample tree. Replacing it with real content means
 * replacing the model — this class deliberately does not know what it is
 * listing.</p>
 */
public final class Navigator extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JTree tree;

    private Consumer<String> onOpen = leaf -> { };

    public Navigator() {
        super(new BorderLayout());

        tree = new JTree(treeModel());
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // Double click opens; a single click only selects. That is the
        // convention in every IDE, and breaking it makes users open things by
        // accident while browsing.
        tree.addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2) {
                    return;
                }

                Object node = tree.getLastSelectedPathComponent();

                if (!(node instanceof DefaultMutableTreeNode leaf)) {
                    return;
                }

                // Not only leaves. A series with segments has children and is
                // still openable -- as the whole thing -- so what decides is
                // whether the node carries a name, not whether it is a twig.

                // The NAME, never the label: the tree shows "winn-1m . busca"
                // and the rest of the program only knows "winn-1m". A leaf with
                // no name -- a tick session, a message -- opens nothing.
                String name = nameOf(leaf);

                if (name != null) {
                    onOpen.accept(name);
                }
            }
        });

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        add(scroll, BorderLayout.CENTER);
    }

    /** @param action what to do when a leaf is opened with a double click */
    public void onOpen(Consumer<String> action) {
        this.onOpen = action == null ? leaf -> { } : action;
    }

    public void setModel(DefaultTreeModel model) {
        tree.setModel(model);
    }

    /**
     * A leaf that shows more than it is called.
     *
     * <p>The tree writes {@code winn-1m . busca} and opening it must still ask
     * for {@code winn-1m}. Keeping the two apart is the difference between a
     * label the reader can act on and a name the rest of the program can
     * find.</p>
     *
     * @param name what to open, or null for a leaf that opens nothing
     */
    /**
     * @return what that node opens, or null when it opens nothing
     *
     * <p>The NAME, never the label: the tree shows {@code winn-1m . busca} and
     * the rest of the program only knows {@code winn-1m}. A tick session or a
     * message has no name and opens nothing.</p>
     */
    static String nameOf(DefaultMutableTreeNode node) {
        Object held = node.getUserObject();

        if (held instanceof Leaf entry) {
            return entry.name();
        }

        return node.isLeaf() ? String.valueOf(held) : null;
    }

    private record Leaf(String name, String label) {

        @Override
        public String toString() {
            return label;
        }
    }

    /**
     * @return the tree as it stands: instrument, then scale, then the files
     *
     * <p>Package-visible so a test can read it without opening a window. It was
     * called {@code sampleModel} while it really was a sample, and the name
     * outlived the truth by one commit.</p>
     */
    static DefaultTreeModel treeModel() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(Messages.get("navigator.root"));

        DefaultMutableTreeNode series =
                new DefaultMutableTreeNode(Messages.get("navigator.series"));

        // Instrument, then SCALE, then the files. The ticks of an instrument
        // are one of its scales -- the finest one -- and not a heading of their
        // own sitting beside every instrument at once. What gets picked here is
        // always "this market, at this resolution", and a tree shaped like that
        // sentence is one level deeper and one question shorter.
        List<String> available = SeriesCatalog.names();
        java.util.Map<String, java.util.Map<String, List<String>>> byInstrument =
                new java.util.LinkedHashMap<>();

        for (String name : available) {
            byInstrument
                    .computeIfAbsent(SeriesCatalog.groupOf(name),
                            key -> new java.util.LinkedHashMap<>())
                    .computeIfAbsent(SeriesCatalog.scaleOf(name),
                            key -> new java.util.ArrayList<>())
                    .add(name);
        }

        for (java.util.Map.Entry<String, java.util.Map<String, List<String>>> each
                : byInstrument.entrySet()) {
            series.add(instrumentNode(each.getKey(), each.getValue()));
        }

        if (available.isEmpty()) {
            series.add(new DefaultMutableTreeNode(new Leaf(null,
                    Messages.get("navigator.noSeries", SeriesCatalog.folder().toString()))));
        }

        DefaultMutableTreeNode studies =
                new DefaultMutableTreeNode(Messages.get("navigator.studies"));
        studies.add(new DefaultMutableTreeNode(Messages.get("document.untitled")));

        root.add(series);
        root.add(studies);

        return new DefaultTreeModel(root);
    }

    /**
     * @param instrument the market, as {@link SeriesCatalog#groupOf} gives it
     * @param byScale its series, by the scale each is stored at
     * @return that instrument with its scales beneath it
     */
    private static DefaultMutableTreeNode instrumentNode(
            String instrument, java.util.Map<String, List<String>> byScale) {
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                Messages.market(instrument));

        List<String> scales = new java.util.ArrayList<>(byScale.keySet());

        scales.sort(SeriesCatalog.coarsestFirst());

        for (String scale : scales) {
            // A series whose name does not say its scale hangs straight off the
            // instrument. A heading invented for it would be a word in the tree
            // that nothing on disk agrees with.
            DefaultMutableTreeNode under = scale.isEmpty() ? node
                    : new DefaultMutableTreeNode(
                            Messages.orElse("navigator.scale." + scale, scale));

            if (under != node) {
                node.add(under);
            }

            for (String name : byScale.get(scale)) {
                under.add(seriesNode(name));
            }
        }

        DefaultMutableTreeNode ticks = tickSessions(instrument);

        if (ticks != null) {
            node.add(ticks);
        }

        return node;
    }

    /**
     * @return that series, with its segments hanging under it
     *
     * <p>The segments are where the work actually happens -- one stretch for
     * searching, another kept unseen for testing -- so they belong in the tree
     * beside everything else that can be opened, not only inside a settings
     * window.</p>
     *
     * <p><b>A locked series carries no name</b>, which is what makes it refuse
     * to open: the reader asked for that, in the series window, and the tree is
     * where the asking has to show. It still lists its segments; it is the
     * whole of it that is out of reach.</p>
     */
    private static DefaultMutableTreeNode seriesNode(String name) {
        boolean locked = Segmentation.segmentsOnly(name);
        DefaultMutableTreeNode node = new DefaultMutableTreeNode(new Leaf(
                locked ? null : name,
                locked ? labelOf(name) + "  ·  " + Messages.get("navigator.locked")
                        : labelOf(name)));

        for (Segment segment : Segmentation.of(name)) {
            node.add(new DefaultMutableTreeNode(new Leaf(
                    Segmentation.nameOf(name, segment), labelOf(segment))));
        }

        return node;
    }

    /** @return a segment as one line: its name, then where it runs */
    private static String labelOf(Segment segment) {
        return segment.name() + "  ·  " + segment.from()
                + (segment.isOpenEnded()
                        ? "  " + Messages.get("series.onwards") : "  a  " + segment.to());
    }

    /**
     * @return the name, and what the series is FOR when that is known
     *
     * <p>The role is what changes a decision: winn is where every hypothesis
     * was mined, so no number from it proves anything alone, and winfut covers
     * the years those hypotheses never saw. A file name says none of that, and
     * opening the wrong one is the expensive mistake.</p>
     */
    private static String labelOf(String name) {
        String role = SeriesCatalog.roleOf(name);

        // The role only when it DISTINGUISHES. Every series is a source until a
        // second one arrives, and a word that is on every line is a word nobody
        // reads -- while "export" beside one of three is the thing that stops
        // the wrong file being opened.
        return role == null || "source".equals(role)
                ? displayOf(name)
                : displayOf(name) + "  ·  " + Messages.orElse("navigator.role." + role, role);
    }

    /**
     * @return the series as it is NAMED here, which is not its file name
     *
     * <p>{@code winfull-1m} sits under WINFUT and under "1 minuto", so saying
     * either again is saying it three times. What is left is what actually
     * tells this series from its neighbours -- {@code full} -- and it is read
     * with the market: <b>WINFUT-FULL</b>.</p>
     *
     * <p>A series named after the market and nothing else keeps just the
     * market's name. There is nothing to distinguish it from.</p>
     */
    private static String displayOf(String name) {
        return SeriesCatalog.displayOf(name);
    }

    /**
     * @return that instrument's tick sessions, by source, or null when it has none
     *
     * <p>One line per SOURCE, because the two hold different things and which
     * one a day came from changes what can be asked of it: the MetaTrader
     * export has the bid and the ask, the Profit tape has both brokers and the
     * aggressor, and neither has the other's. Listing them together as "ticks"
     * would hide the only thing worth knowing before opening one.</p>
     *
     * <p>A source with no sessions is not listed at all rather than listed as
     * empty. There is no tape on disk today, and a "Profit: 0" sitting under
     * every instrument would be a permanent reminder of nothing.</p>
     *
     * <p>They open nothing: a tick session is what a series is replayed FROM,
     * not a chart of its own.</p>
     */
    private static DefaultMutableTreeNode tickSessions(String instrument) {
        return tickSessions(SeriesCatalog.ticksOf(instrument), instrument);
    }

    /**
     * @param folder where that instrument's tick sessions are
     *
     * <p>Package-visible with the folder spelled out, so a test can ask this
     * question without the answer depending on where the catalog happens to be
     * pointing. That dependence produced a test that failed about one run in
     * four and passed the other three, which is the kind of failure that gets
     * re-run rather than read.</p>
     */
    static DefaultMutableTreeNode tickSessions(java.nio.file.Path folder, String instrument) {
        if (!java.nio.file.Files.isDirectory(folder)) {
            return null;
        }

        DefaultMutableTreeNode node =
                new DefaultMutableTreeNode(Messages.get("navigator.ticks"));

        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource source
                : br.com.jorge.reis.endeavourneo.domain.market.TickSource.values()) {
            List<java.time.LocalDate> days;

            // Closed. It was built for one question -- which days were exported
            // -- and then dropped with its reading thread still alive, once per
            // tick source, every time the tree was rebuilt. The tree is rebuilt
            // whenever a series changes.
            try (br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library =
                         new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                                 folder, instrument, source)) {
                days = library.exported();
            }

            if (days.isEmpty()) {
                continue;
            }

            String key = br.com.jorge.reis.endeavourneo.ui.series.Segmentable
                    .keyOfTicks(instrument, source);

            // NAMED, so it opens. It used to be a leaf that opened nothing, on
            // the argument that "a tick session is what a chart is REPLAYED
            // from, not a chart". That was wrong: an export holds every print of
            // every session it covers, which is MORE than the candle file holds,
            // and the rule made the most complete data in the program the only
            // data that could not be looked at.
            DefaultMutableTreeNode found = new DefaultMutableTreeNode(new Leaf(key,
                    Messages.get("navigator.tickSessions",
                            Messages.orElse("navigator.tickSource." + source.key(),
                                    source.key()),
                            String.valueOf(days.size()),
                            days.get(0).toString(), days.get(days.size() - 1).toString())));

            // A tick source can be segmented like any other series -- see
            // Segmentable -- and its segments open like any other series's, for
            // the same reason the source itself now does.
            for (br.com.jorge.reis.endeavourneo.domain.market.Segment segment
                    : Segmentation.of(key)) {
                found.add(new DefaultMutableTreeNode(new Leaf(
                        Segmentation.nameOf(key, segment), labelOf(segment))));
            }

            node.add(found);
        }

        return node.getChildCount() == 0 ? null : node;
    }
}
