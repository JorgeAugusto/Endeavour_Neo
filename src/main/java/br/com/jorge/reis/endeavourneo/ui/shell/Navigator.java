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
import br.com.jorge.reis.endeavourneo.platform.Bases;

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
     * @return the tree as it stands: the bases on disk, grouped and labelled
     *
     * <p>Package-visible so a test can read it without opening a window. It was
     * called {@code sampleModel} while it really was a sample, and the name
     * outlived the truth by one commit.</p>
     */
    static DefaultTreeModel treeModel() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(Messages.get("navigator.root"));

        DefaultMutableTreeNode series =
                new DefaultMutableTreeNode(Messages.get("navigator.series"));

        // Grouped by instrument and labelled by ROLE, because the role is what
        // changes a decision: winn is where every hypothesis was mined, so no
        // number from it proves anything alone, and winfut covers the years
        // those hypotheses never saw. Five files listed flat says none of that,
        // and opening the wrong one is the expensive mistake.
        List<String> bases = Bases.names();
        java.util.Map<String, DefaultMutableTreeNode> groups = new java.util.LinkedHashMap<>();

        for (String name : bases) {
            String group = Bases.groupOf(name);
            DefaultMutableTreeNode under = groups.computeIfAbsent(group, key -> {
                DefaultMutableTreeNode node = new DefaultMutableTreeNode(
                        Messages.orElse("navigator.group." + key, key));

                series.add(node);

                return node;
            });

            String role = Bases.roleOf(name);
            String label = role == null
                    ? name : name + "  ·  " + Messages.orElse("navigator.role." + role, role);

            under.add(new DefaultMutableTreeNode(new Leaf(name, label)));
        }

        if (bases.isEmpty()) {
            series.add(new DefaultMutableTreeNode(new Leaf(null,
                    Messages.get("navigator.noBases", Bases.folder().toString()))));
        }

        DefaultMutableTreeNode ticks = tickSessions();

        if (ticks != null) {
            series.add(ticks);
        }

        DefaultMutableTreeNode studies =
                new DefaultMutableTreeNode(Messages.get("navigator.studies"));
        studies.add(new DefaultMutableTreeNode(Messages.get("document.untitled")));

        root.add(series);
        root.add(studies);

        return new DefaultTreeModel(root);
    }

    /**
     * @return the exported tick sessions, or null when there are none
     *
     * <p>Listed because they are the difference between a renko that means
     * something and one built from candles, and there is no other way to see
     * which days have them. They open nothing: a tick session is what a base is
     * replayed FROM, not a chart of its own.</p>
     */
    private static DefaultMutableTreeNode tickSessions() {
        java.nio.file.Path folder = Bases.folder().resolve("ticks");

        if (!java.nio.file.Files.isDirectory(folder)) {
            return null;
        }

        DefaultMutableTreeNode node =
                new DefaultMutableTreeNode(Messages.get("navigator.ticks"));

        for (String name : Bases.names()) {
            String instrument = Bases.groupOf(name);

            if (node.getChildCount() > 0 && alreadyListed(node, instrument)) {
                continue;
            }

            List<java.time.LocalDate> days =
                    new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                            folder, instrument).exported();

            if (days.isEmpty()) {
                continue;
            }

            node.add(new DefaultMutableTreeNode(new Leaf(null,
                    Messages.get("navigator.tickSessions", instrument,
                            String.valueOf(days.size()),
                            days.get(0).toString(), days.get(days.size() - 1).toString()))));
        }

        return node.getChildCount() == 0 ? null : node;
    }

    private static boolean alreadyListed(DefaultMutableTreeNode node, String instrument) {
        for (int i = 0; i < node.getChildCount(); i++) {
            Object each = ((DefaultMutableTreeNode) node.getChildAt(i)).getUserObject();

            if (each instanceof Leaf leaf && leaf.label().startsWith(instrument)) {
                return true;
            }
        }

        return false;
    }
}
