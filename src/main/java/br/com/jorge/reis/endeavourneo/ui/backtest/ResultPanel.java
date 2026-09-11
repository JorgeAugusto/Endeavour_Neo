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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import br.com.jorge.reis.endeavourneo.domain.trading.Metrics;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;

/**
 * The quadro: everything a run is, down the right-hand side.
 *
 * <p>Six blocks, and the order is the order they should be read in — result,
 * then the shape of it, then whether the strategy could have paid for itself,
 * then how good it was, then what doing nothing would have got, and last
 * whether any of it can be believed.</p>
 *
 * <h2>The last block is the one nobody else builds</h2>
 *
 * <p><i>Honestidade</i> holds the four figures that say whether the five blocks
 * above it mean anything: what a round trip was charged, how much of the result
 * was decided by the tie-break rather than by the data, what was left open, and
 * over which series it ran. <b>It turns red and says what is wrong</b>, rather
 * than leaving the reader to notice. A backtest that reports a profit and
 * quietly charged nothing is the failure this block exists for.</p>
 */
final class ResultPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final Color UP = new Color(0x2E8B57);

    private static final Color DOWN = new Color(0xC6453F);

    private static final Color WARN = new Color(0xB26A00);

    private static final Color BALANCE = new Color(0x2E8B57);

    private static final Color WORTH = new Color(0x2F74B5);

    private static final Color COST = new Color(0xB26A00);

    /** Above this share of bars, the tie-break is deciding the result. */
    private static final double TOO_AMBIGUOUS = 0.01;

    private final JLabel hero = new JLabel("-");

    private final JLabel heroSub = new JLabel(Messages.get("backtest.noRun"));

    private final EquityChart curve = new EquityChart();

    private final List<JLabel> values = new ArrayList<>();

    private final JLabel warning = new JLabel(" ");

    ResultPanel() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(2, 8, 8, 8));

        add(result());
        add(worth());
        add(block("backtest.block.exposure", "backtest.trades", "backtest.timeIn",
                "backtest.barsHeld", "backtest.perSession"));
        add(block("backtest.block.quality", "backtest.hitRate", "backtest.breakEven",
                "backtest.profitFactor", "backtest.avgWin", "backtest.avgLoss",
                "backtest.drawdown", "backtest.losingRun"));
        add(block("backtest.block.reference", "backtest.buyAndHold", "backtest.difference"));
        add(honesty());
        add(Box.createVerticalGlue());

        setPreferredSize(new Dimension(330, 640));
        setMinimumSize(new Dimension(240, 200));
    }

    // ------------------------------------------------------------ the blocks

    private JComponent result() {
        JPanel panel = shell("backtest.block.result");

        hero.setFont(hero.getFont().deriveFont(Font.BOLD, 22f));
        heroSub.setFont(small());
        heroSub.setForeground(muted());

        panel.add(hero);
        panel.add(heroSub);

        return panel;
    }

    private JComponent worth() {
        JPanel panel = shell("backtest.block.worth");

        panel.add(curve);
        panel.add(legend());

        return panel;
    }

    private JComponent legend() {
        JPanel row = new JPanel();

        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);

        row.add(swatch(BALANCE, "backtest.legend.balance"));
        row.add(Box.createHorizontalStrut(10));
        row.add(swatch(WORTH, "backtest.legend.worth"));
        row.add(Box.createHorizontalStrut(10));
        row.add(swatch(COST, "backtest.legend.cost"));
        row.add(Box.createHorizontalGlue());

        return row;
    }

    private JComponent swatch(Color colour, String key) {
        JLabel label = new JLabel(Messages.get(key), new Dash(colour), JLabel.LEFT);

        label.setFont(small());
        label.setForeground(muted());
        label.setIconTextGap(4);

        return label;
    }

    /** A short coloured rule, so the legend says which line it names. */
    private record Dash(Color colour) implements javax.swing.Icon {

        @Override
        public void paintIcon(Component host, Graphics g, int x, int y) {
            g.setColor(colour);
            g.fillRect(x, y + 4, 11, 2);
        }

        @Override
        public int getIconWidth() {
            return 11;
        }

        @Override
        public int getIconHeight() {
            return 10;
        }
    }

    private JComponent honesty() {
        JPanel panel = block("backtest.block.honesty", "backtest.costPerTurn",
                "backtest.series", "backtest.ambiguous", "backtest.openAtEnd");

        warning.setFont(small());
        warning.setForeground(muted());
        warning.setAlignmentX(LEFT_ALIGNMENT);

        panel.add(Box.createVerticalStrut(3));
        panel.add(warning);

        return panel;
    }

    private JPanel block(String titleKey, String... rowKeys) {
        JPanel panel = shell(titleKey);

        for (String key : rowKeys) {
            JLabel value = new JLabel("-");

            value.setFont(small());
            values.add(value);

            panel.add(row(Messages.get(key), value));
        }

        return panel;
    }

    private JPanel shell(String titleKey) {
        JPanel panel = new JPanel();

        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Separator.foreground")),
                BorderFactory.createEmptyBorder(8, 0, 9, 0)));

        JLabel title = new JLabel(Messages.get(titleKey).toUpperCase());

        title.setFont(title.getFont().deriveFont(Font.BOLD, 10f));
        title.setForeground(muted());
        title.setAlignmentX(LEFT_ALIGNMENT);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

        panel.add(title);

        return panel;
    }

    private JPanel row(String caption, JLabel value) {
        JPanel row = new JPanel(new BorderLayout(8, 0));

        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 18));

        JLabel left = new JLabel(caption);

        left.setFont(small());
        left.setForeground(muted());

        value.setHorizontalAlignment(JLabel.RIGHT);

        row.add(left, BorderLayout.WEST);
        row.add(value, BorderLayout.CENTER);

        return row;
    }

    private Font small() {
        return getFont().deriveFont(Font.PLAIN, 11f);
    }

    private static Color muted() {
        Color known = UIManager.getColor("Label.disabledForeground");

        return known == null ? Color.GRAY : known;
    }

    // ----------------------------------------------------------------- filling

    /**
     * @param result what the run produced
     * @param metrics what was derived from it
     * @param series what to call the series it ran over
     */
    void show(Result result, Metrics metrics, String series) {
        double net = result.net();

        hero.setText(String.format("%+,.0f pts", net));
        hero.setForeground(net >= 0 ? UP : DOWN);
        heroSub.setText(String.format("%+,.2f %s  ·  %s %+,.0f  ·  %s %,.0f",
                result.perTrade(), Messages.get("backtest.perTrade"),
                Messages.get("backtest.gross"), result.gross(),
                Messages.get("backtest.costs"), result.cost()));

        curve.show(result.balancePerBar(), result.worth(), result.costPerBar());

        int i = 0;

        // Exposicao
        set(i++, String.format("%,d", metrics.trades()));
        set(i++, String.format("%.1f%%", metrics.exposure() * 100));
        set(i++, String.format("%.1f", metrics.barsHeld()));
        set(i++, String.format("%.1f", metrics.tradesPerSession()));

        // Qualidade
        set(i++, String.format("%.1f%%", metrics.hitRate() * 100));
        setBreakEven(i++, metrics);
        set(i++, Double.isInfinite(metrics.profitFactor())
                ? "∞" : String.format("%.2f", metrics.profitFactor()));
        set(i, String.format("%+,.0f", metrics.averageWin()));
        values.get(i++).setForeground(UP);
        set(i, String.format("%+,.0f", -metrics.averageLoss()));
        values.get(i++).setForeground(DOWN);
        set(i, String.format("%,.0f pts", result.drawdown()));
        values.get(i++).setForeground(DOWN);
        set(i++, String.valueOf(metrics.longestLosingRun()));

        // Referencia
        set(i++, String.format("%+,.0f", metrics.buyAndHold()));
        set(i, String.format("%+,.0f", net - metrics.buyAndHold()));
        values.get(i++).setForeground(net >= metrics.buyAndHold() ? UP : DOWN);

        // Honestidade
        set(i++, String.format("%.1f %s%s", result.costs().pointsPerRoundTrip(),
                Messages.get("backtest.points"),
                result.costs().measured() ? "  " + Messages.get("backtest.measured") : ""));
        set(i++, series);
        set(i++, String.format("%,d  (%.2f%%)", result.ambiguousBars(),
                result.count() == 0 ? 0 : 100.0 * result.ambiguousBars() / result.count()));
        set(i++, result.endedHolding()
                ? String.format("%+d", result.openAtTheEnd()) : Messages.get("backtest.flat"));

        sayWhatIsWrong(result);
    }

    /**
     * The hit rate that would have broken even, coloured by whether it was met.
     *
     * <p>Red when the real hit rate is below it, and that is the whole point of
     * the row: it is the one figure that says "this loses" without knowing
     * anything about the size of the account.</p>
     */
    private void setBreakEven(int index, Metrics metrics) {
        set(index, String.format("%.1f%%", metrics.breakEvenHitRate() * 100));

        values.get(index).setForeground(metrics.edge() >= 0 ? UP : DOWN);
    }

    private void set(int index, String text) {
        JLabel value = values.get(index);

        value.setText(text);
        value.setForeground(UIManager.getColor("Label.foreground"));
    }

    /**
     * Names every reason the numbers above may not mean what they look like.
     *
     * <p>Said out loud rather than left for the reader to check, because the
     * dangerous run is not the one that is obviously broken — it is the one that
     * looks ordinary and charged the wrong cost.</p>
     */
    private void sayWhatIsWrong(Result result) {
        List<String> wrong = new ArrayList<>();

        if (!result.costs().measured()) {
            wrong.add(Messages.get(result.costs().pointsPerRoundTrip() == 0
                    ? "backtest.wrong.free" : "backtest.wrong.cost"));
        }

        if (result.count() > 0
                && (double) result.ambiguousBars() / result.count() > TOO_AMBIGUOUS) {
            wrong.add(Messages.get("backtest.wrong.ambiguous"));
        }

        if (result.endedHolding()) {
            wrong.add(Messages.get("backtest.wrong.holding"));
        }

        warning.setText(wrong.isEmpty()
                ? Messages.get("backtest.nothingWrong") : "⚠ " + String.join("  ·  ", wrong));
        warning.setForeground(wrong.isEmpty() ? muted() : WARN);
    }

    /** @param bar which bar to mark on the curve, or -1 */
    void highlight(int bar) {
        curve.highlight(bar);
    }

    /** Puts it back to the state before any run. */
    void clear() {
        hero.setText("-");
        hero.setForeground(UIManager.getColor("Label.foreground"));
        heroSub.setText(Messages.get("backtest.noRun"));

        for (JLabel value : values) {
            value.setText("-");
            value.setForeground(UIManager.getColor("Label.foreground"));
        }

        curve.show(null, null, null);
        warning.setText(" ");
    }
}
