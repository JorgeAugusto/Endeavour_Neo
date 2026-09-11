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
package br.com.jorge.reis.endeavourneo.ui.settings;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;

/**
 * The preferences dialog: category tree on the left, page on the right.
 *
 * <pre>
 * +------------------------------------------------------+
 * | Preferences                                           |
 * +--------------+---------------------------------------+
 * | Appearance   |  Theme                                |
 * | ...          |   (o) Light                           |
 * |              |   ( ) Dark                            |
 * |              |   ( ) Night                           |
 * +--------------+---------------------------------------+
 * |                     [ Cancel ] [ Apply ] [ OK ]      |
 * +------------------------------------------------------+
 * </pre>
 *
 * <p>This is the Eclipse and IntelliJ shape, and it is worth copying for a
 * reason that is not imitation: <b>one place for every setting</b>. Scattering
 * options across menus is what produces the application where nobody can find
 * the one switch they need.</p>
 *
 * <p><b>Apply and OK are not the same button.</b> Apply commits and keeps the
 * dialog open, so the user can see the effect and keep adjusting; OK commits and
 * closes. Cancel closes without committing anything, which is only meaningful
 * because pages defer their writes — see {@link SettingsPage}.</p>
 */
public final class SettingsDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient List<SettingsPage> pages;

    private final JPanel cards = new JPanel(new CardLayout());

    public SettingsDialog(Window owner, List<SettingsPage> pages) {
        super(owner, Messages.get("settings.title"), ModalityType.APPLICATION_MODAL);

        this.pages = List.copyOf(pages);

        for (SettingsPage page : this.pages) {
            page.load();
            cards.add(wrap(page), page.getTitle());
        }

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildCategoryTree(), cards);
        split.setDividerLocation(180);
        split.setBorder(null);

        add(split, BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);

        closeOnEscape();

        setSize(new Dimension(720, 480));
        setLocationRelativeTo(owner);
    }

    /** Opens the dialog and blocks until it is dismissed. */
    public static void show(Window owner, List<SettingsPage> pages) {
        SettingsDialog dialog = new SettingsDialog(owner, pages);

        // DISPOSED when the X closes it, which is the default a JDialog does
        // NOT have: DO_NOTHING would hang and HIDE keeps the whole tree of
        // pages -- and their listeners -- alive for the life of the
        // application, one copy per time the reader opened preferences.
        dialog.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        dialog.setVisible(true);
    }

    private JComponent buildCategoryTree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(Messages.get("settings.title"));

        for (SettingsPage page : pages) {
            root.add(new DefaultMutableTreeNode(page.getTitle()));
        }

        JTree tree = new JTree(new DefaultTreeModel(root));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(false);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        for (int i = 0; i < tree.getRowCount(); i++) {
            tree.expandRow(i);
        }

        tree.addTreeSelectionListener(e -> {
            Object node = tree.getLastSelectedPathComponent();

            if (node != null) {
                ((CardLayout) cards.getLayout()).show(cards, String.valueOf(node));
            }
        });

        tree.setSelectionRow(0);

        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        return scroll;
    }

    /**
     * One page, under its heading, with somewhere to scroll to.
     *
     * <p><b>The page scrolls and the heading does not.</b> A page taller than
     * the dialog used to be cut off with nothing to say so — the breakout's
     * screen lost its last two rows, and a control you cannot see is one you
     * cannot discover is there. It is done here rather than in each page
     * because every page can outgrow the dialog, and the one that does it next
     * should not have to remember this.</p>
     *
     * <p>Sideways it does NOT scroll. A settings page that is too wide is a
     * layout to fix, not a page to drag around; and a horizontal bar that
     * appears for four pixels of overflow steals a row from every page that
     * does fit.</p>
     */
    private static JComponent wrap(SettingsPage page) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        JLabel heading = new JLabel(page.getTitle());
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

        JScrollPane scroll = new JScrollPane(new Tall(page.getComponent()),
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // NO BORDER: a JScrollPane brings its own etched line, and inside a
        // panel that already has a heading and padding it reads as a box drawn
        // around the settings for no reason.
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /**
     * A page that may be taller than the window but never wider.
     *
     * <p>A plain panel inside a viewport is given its PREFERRED size, and the
     * preferred width of a settings page is the width of its longest hint —
     * a whole sentence on one line. The page would then reach past the right
     * edge and be cut there, with no horizontal bar to reach the rest by.
     *
     * <p>Tracking the viewport's width hands the page exactly the room there
     * is, which is what the hints already assume: they shorten themselves with
     * an ellipsis when the line runs out. Height is the opposite — it must be
     * free to grow past the viewport, because growing is the whole point of
     * being in one.</p>
     */
    private static final class Tall extends JPanel implements javax.swing.Scrollable {

        private static final long serialVersionUID = 1L;

        private Tall(JComponent page) {
            super(new BorderLayout());

            add(page, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(java.awt.Rectangle seen, int axis, int way) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(java.awt.Rectangle seen, int axis, int way) {
            return Math.max(16, seen.height - 16);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    private JComponent buildButtons() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 8));

        JButton cancel = new JButton(Messages.get("settings.cancel"));
        cancel.addActionListener(e -> dispose());

        JButton apply = new JButton(Messages.get("settings.apply"));
        apply.addActionListener(e -> applyAll());

        JButton ok = new JButton(Messages.get("settings.ok"));
        ok.addActionListener(e -> {
            applyAll();
            dispose();
        });

        row.add(cancel);
        row.add(apply);
        row.add(ok);

        getRootPane().setDefaultButton(ok);

        return row;
    }

    private void applyAll() {
        for (SettingsPage page : pages) {
            page.apply();
        }
    }

    /**
     * Escape closes without committing.
     *
     * <p>Expected of every modal dialog, and its absence is felt immediately:
     * a dialog that traps the keyboard reads as a bug even when everything
     * else works.</p>
     */
    private void closeOnEscape() {
        getRootPane().registerKeyboardAction(new AbstractAction() {

            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JRootPane.WHEN_IN_FOCUSED_WINDOW);
    }
}
