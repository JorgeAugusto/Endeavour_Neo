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
        new SettingsDialog(owner, pages).setVisible(true);
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

    private static JComponent wrap(SettingsPage page) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));

        JLabel heading = new JLabel(page.getTitle());
        heading.setFont(heading.getFont().deriveFont(java.awt.Font.BOLD));
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

        panel.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        panel.add(heading, BorderLayout.NORTH);
        panel.add(page.getComponent(), BorderLayout.CENTER);

        return panel;
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
