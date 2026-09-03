package br.com.jorge.reis.endeavourneo.ui.shell;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
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

        tree = new JTree(sampleModel());
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

                if (node instanceof DefaultMutableTreeNode leaf && leaf.isLeaf()) {
                    onOpen.accept(String.valueOf(leaf.getUserObject()));
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

    private static DefaultTreeModel sampleModel() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(Messages.get("navigator.root"));

        DefaultMutableTreeNode series =
                new DefaultMutableTreeNode(Messages.get("navigator.series"));
        series.add(new DefaultMutableTreeNode("winn-1m"));
        series.add(new DefaultMutableTreeNode("winfut-1m"));
        series.add(new DefaultMutableTreeNode("btcusdt-1m"));

        DefaultMutableTreeNode studies =
                new DefaultMutableTreeNode(Messages.get("navigator.studies"));
        studies.add(new DefaultMutableTreeNode(Messages.get("document.untitled")));

        root.add(series);
        root.add(studies);

        return new DefaultTreeModel(root);
    }
}
