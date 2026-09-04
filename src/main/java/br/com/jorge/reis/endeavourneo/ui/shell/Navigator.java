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

                if (!(node instanceof DefaultMutableTreeNode leaf) || !leaf.isLeaf()) {
                    return;
                }

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
                Messages.orElse("navigator.group." + instrument, instrument));

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
                under.add(new DefaultMutableTreeNode(new Leaf(name, labelOf(name))));
            }
        }

        DefaultMutableTreeNode ticks = tickSessions(instrument);

        if (ticks != null) {
            node.add(ticks);
        }

        return node;
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

        return role == null
                ? name : name + "  ·  " + Messages.orElse("navigator.role." + role, role);
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
            List<java.time.LocalDate> days =
                    new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                            folder, instrument, source).exported();

            if (days.isEmpty()) {
                continue;
            }

            node.add(new DefaultMutableTreeNode(new Leaf(null,
                    Messages.get("navigator.tickSessions",
                            Messages.orElse("navigator.tickSource." + source.key(),
                                    source.key()),
                            String.valueOf(days.size()),
                            days.get(0).toString(), days.get(days.size() - 1).toString()))));
        }

        return node.getChildCount() == 0 ? null : node;
    }
}
