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
package br.com.jorge.reis.endeavourneo.ui.chart;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;

/**
 * The layout tabs, below the time axis.
 *
 * <p>A layout is a named group of indicators. Switching the tab switches what is
 * drawn on the chart, which is what makes it worth naming: <i>Clean</i>,
 * <i>Tops and bottoms</i>, <i>Trend</i> — one click apart instead of six
 * insertions apart.</p>
 *
 * <p><b>Changes are saved into the layout as they happen.</b> Adding an
 * indicator and then hunting for a save button is how work gets lost; saving
 * silently is how a layout gets ruined. The reference product resolves this the
 * same way and its answer is visible in its own tab names — you <b>duplicate
 * first</b>, then experiment on the copy. So duplicate is one click, and the
 * copy is selected immediately.</p>
 *
 * <p>Layouts are shared by every chart. That is what makes a name useful, and it
 * means editing here changes the other charts on the same layout.</p>
 */
public final class LayoutBar extends JComponent {

    private static final long serialVersionUID = 1L;

    private final transient ChartCanvas canvas;

    private final transient String chartKey;

    private final JPanel tabs = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));

    private final transient List<ChartLayout> layouts = new ArrayList<>();

    /** Where the indicator panes live, or null for a chart that has no stack. */
    private final transient br.com.jorge.reis.endeavourneo.ui.chart.study.StudyStack studies;

    private int selected;

    /**
     * @param canvas the chart these layouts drive
     * @param chartKey identifies the chart, so it reopens on the same layout
     */
    public LayoutBar(ChartCanvas canvas,
                     br.com.jorge.reis.endeavourneo.ui.chart.study.StudyStack studies,
                     String chartKey) {
        this.studies = studies;
        this.canvas = canvas;
        this.chartKey = chartKey;

        setLayout(new java.awt.BorderLayout());
        setBorder(BorderFactory.createEmptyBorder());

        JScrollPane scroll = new JScrollPane(tabs,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        scroll.setBorder(BorderFactory.createEmptyBorder());

        add(scroll, java.awt.BorderLayout.CENTER);

        layouts.addAll(ChartLayouts.all());
        selected = indexOf(ChartLayouts.selectedFor(chartKey));

        rebuild();
        apply();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(200, 28);
    }

    /** Writes the chart's current indicators back into the selected layout. */
    public void capture() {
        if (selected < 0 || selected >= layouts.size()) {
            return;
        }

        layouts.set(selected, ChartLayout.of(layouts.get(selected).name(),
                canvas.overlays(), studies == null ? List.of() : studies.remembered()));

        ChartLayouts.save(layouts);
    }

    // ------------------------------------------------------------- the tabs

    private void rebuild() {
        tabs.removeAll();

        for (int i = 0; i < layouts.size(); i++) {
            tabs.add(tabFor(i));
        }

        JButton add = new JButton("+");

        add.setToolTipText(Messages.get("layout.add"));
        add.setFocusable(false);
        add.setMargin(new java.awt.Insets(1, 6, 1, 6));
        add.addActionListener(e -> addLayout());

        tabs.add(add);
        tabs.revalidate();
        tabs.repaint();
    }

    private Component tabFor(int index) {
        ChartLayout layout = layouts.get(index);
        JToggleButton tab = new JToggleButton(layout.name());

        tab.setSelected(index == selected);
        tab.setFocusable(false);
        tab.setMargin(new java.awt.Insets(1, 8, 1, 8));
        tab.addActionListener(e -> select(index));

        tab.addMouseListener(new MouseAdapter() {

            @Override
            public void mousePressed(MouseEvent e) {
                maybeMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeMenu(e);
            }

            private void maybeMenu(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    menuFor(index).show(tab, e.getX(), e.getY());
                }
            }
        });

        return tab;
    }

    private JPopupMenu menuFor(int index) {
        JPopupMenu menu = new JPopupMenu();

        JMenuItem duplicate = new JMenuItem(Messages.get("layout.duplicate"));
        duplicate.addActionListener(e -> duplicate(index));

        JMenuItem rename = new JMenuItem(Messages.get("layout.rename"));
        rename.addActionListener(e -> rename(index));

        JMenuItem remove = new JMenuItem(Messages.get("layout.remove"));

        // The last layout cannot be removed: a chart with no layout has nowhere
        // to keep the indicators the reader is about to add.
        remove.setEnabled(layouts.size() > 1);
        remove.addActionListener(e -> removeLayout(index));

        menu.add(duplicate);
        menu.add(rename);
        menu.addSeparator();
        menu.add(remove);

        return menu;
    }

    // ------------------------------------------------------------ the actions

    private void select(int index) {
        if (index == selected) {
            rebuild();

            return;
        }

        // Capture BEFORE leaving, or every change made since arriving is lost on
        // the way out -- silently, which is the worst way to lose work.
        capture();

        selected = index;

        ChartLayouts.remember(chartKey, layouts.get(index).name());

        apply();
        rebuild();
    }

    private void apply() {
        boolean real = selected >= 0 && selected < layouts.size();

        canvas.setOverlays(real ? layouts.get(selected).build() : List.of());

        // The panes go with the overlays. A layout is one answer to "how am I
        // looking at this", so switching to one without a stochastic takes the
        // stochastic away exactly as it takes the averages away.
        if (studies != null) {
            studies.restore(real ? layouts.get(selected).panes() : List.of());
        }
    }

    private void addLayout() {
        capture();

        String name = JOptionPane.showInputDialog(this, Messages.get("layout.namePrompt"),
                Messages.get("layout.newDefault"));

        if (name == null || name.isBlank()) {
            return;
        }

        layouts.add(ChartLayout.empty(name.trim()));
        selected = layouts.size() - 1;

        ChartLayouts.save(layouts);
        ChartLayouts.remember(chartKey, name.trim());

        apply();
        rebuild();
    }

    private void duplicate(int index) {
        capture();

        List<String> names = new ArrayList<>();

        for (ChartLayout layout : layouts) {
            names.add(layout.name());
        }

        ChartLayout copy = layouts.get(index)
                .renamedTo(ChartLayouts.copyName(names, layouts.get(index).name()));

        layouts.add(index + 1, copy);
        selected = index + 1;

        // Selected immediately: duplicating exists so the copy can be changed,
        // and leaving the original selected means the next edit lands on the
        // thing that was being protected.
        ChartLayouts.save(layouts);
        ChartLayouts.remember(chartKey, copy.name());

        apply();
        rebuild();
    }

    private void rename(int index) {
        String name = JOptionPane.showInputDialog(this, Messages.get("layout.namePrompt"),
                layouts.get(index).name());

        if (name == null || name.isBlank()) {
            return;
        }

        layouts.set(index, layouts.get(index).renamedTo(name.trim()));

        ChartLayouts.save(layouts);

        if (index == selected) {
            ChartLayouts.remember(chartKey, name.trim());
        }

        rebuild();
    }

    /**
     * Removes a layout.
     *
     * <p>Named {@code removeLayout} and not {@code remove}: a component already
     * inherits {@code Container.remove(int)}, and a private method of the same
     * shape does not compile. Worth the longer name -- the alternative is a
     * method that quietly means something else to anything holding this as a
     * Container.</p>
     */
    private void removeLayout(int index) {
        if (layouts.size() <= 1) {
            return;
        }

        int answer = JOptionPane.showConfirmDialog(this,
                Messages.get("layout.confirmRemove", layouts.get(index).name()),
                Messages.get("layout.remove"), JOptionPane.YES_NO_OPTION);

        if (answer != JOptionPane.YES_OPTION) {
            return;
        }

        layouts.remove(index);
        selected = Math.min(selected, layouts.size() - 1);

        ChartLayouts.save(layouts);
        ChartLayouts.remember(chartKey, layouts.get(selected).name());

        apply();
        rebuild();
    }

    private int indexOf(String name) {
        for (int i = 0; i < layouts.size(); i++) {
            if (layouts.get(i).name().equals(name)) {
                return i;
            }
        }

        return 0;
    }
}
