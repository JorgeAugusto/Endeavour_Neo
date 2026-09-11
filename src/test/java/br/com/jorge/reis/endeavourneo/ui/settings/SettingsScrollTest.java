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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Uma página de configurações mais alta que a janela tem como ser alcançada.
 *
 * <p>A tela do rompimento de padrão cresceu com o latch e o dobrar mão e passou
 * do rodapé do diálogo: os dois últimos controles ficavam cortados, sem nada na
 * tela dizendo que existiam. Controle que não se vê é controle que não se
 * descobre.</p>
 */
@DisplayName("Rolagem das configuracoes")
class SettingsScrollTest {

    /**
     * Uma página deliberadamente altíssima, e com uma linha muito larga.
     *
     * <p>Uma classe e não um record com o painel construído na hora: {@code
     * getComponent()} é chamado uma vez pelo diálogo e outra pelo teste, e duas
     * instâncias fariam o teste procurar por um componente que não está na
     * janela. Foi o que aconteceu na primeira escrita, e o sintoma foi um
     * {@code NullPointer} em vez de uma falha explicando-se.</p>
     */
    private static final class Enorme implements SettingsPage {

        private final JPanel panel = new JPanel();

        private Enorme(int altura) {
            panel.setPreferredSize(new Dimension(2_000, altura));

            // Um rotulo largo como as dicas de verdade sao: uma frase inteira
            // numa linha so.
            panel.add(new JLabel("uma dica comprida o bastante para passar da "
                    + "largura do dialogo e obrigar a escolha entre cortar e rolar"));
        }

        @Override
        public String getTitle() {
            return "Enorme";
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
        }

        @Override
        public void apply() {
        }
    }

    /** @return o primeiro JScrollPane que contenha o componente informado */
    private static JScrollPane scrollAround(Container from, Component wanted) {
        for (Component each : from.getComponents()) {
            if (each instanceof JScrollPane scroll
                    && SwingUtilities.isDescendingFrom(wanted, scroll)) {
                return scroll;
            }

            if (each instanceof Container deeper) {
                JScrollPane found = scrollAround(deeper, wanted);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * O scroll da página, já com o viewport disposto de verdade.
     *
     * <p>{@code doLayout()} no viewport e não só {@code validate()} na janela, e
     * a diferença não é cerimônia: é o {@code JViewport} que consulta o contrato
     * {@code Scrollable} e decide o tamanho da view a partir dele. Sem essa
     * chamada, largura e altura da view são zero, e as duas asserções abaixo
     * passavam comparando zero com zero — foi o que a prova de dentes mostrou na
     * primeira escrita deste arquivo.</p>
     */
    private static JScrollPane laidOut(Enorme page, int wide, int high) throws Exception {
        JScrollPane[] found = new JScrollPane[1];

        SwingUtilities.invokeAndWait(() -> {
            SettingsDialog dialog = new SettingsDialog(null, List.of(page));

            dialog.setSize(720, 480);
            dialog.validate();

            JScrollPane scroll = scrollAround(dialog.getContentPane(), page.getComponent());

            if (scroll != null) {
                scroll.setSize(wide, high);
                scroll.doLayout();
                scroll.getViewport().doLayout();
            }

            found[0] = scroll;

            dialog.dispose();
        });

        return found[0];
    }

    @Test
    @DisplayName("UMA PAGINA MAIS ALTA QUE A JANELA ROLA, em vez de ser cortada")
    void apageTallerThanTheWindowScrolls() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        JScrollPane scroll = laidOut(new Enorme(4_000), 600, 300);

        assertNotNull(scroll, "a pagina nao esta dentro de nenhum JScrollPane");

        Component view = scroll.getViewport().getView();

        assertTrue(scroll.getViewport().getHeight() > 0,
                "o viewport ficou sem altura, entao a medida abaixo nao vale nada");

        // A PAGINA FICA COM A ALTURA QUE PEDIU, e nao com a que coube. Espremida
        // na altura do viewport nao sobraria nada para rolar, e os controles de
        // baixo seguiriam inalcancaveis com uma barra na tela dizendo o
        // contrario.
        assertTrue(view.getHeight() >= 4_000,
                "a pagina foi espremida na altura do viewport: " + view.getHeight()
                        + " para " + scroll.getViewport().getHeight() + " de espaco");

        assertTrue(scroll.getVerticalScrollBarPolicy()
                        != JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                "a barra vertical foi proibida, entao a altura extra e inalcancavel");
    }

    @Test
    @DisplayName("E NAO ROLA DE LADO: a pagina recebe a largura que ha")
    void anditDoesNotScrollSideways() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        JScrollPane scroll = laidOut(new Enorme(4_000), 600, 300);

        assertNotNull(scroll, "a pagina nao esta dentro de nenhum JScrollPane");

        int daPagina = scroll.getViewport().getView().getWidth();
        int doEspaco = scroll.getViewport().getWidth();

        assertTrue(doEspaco > 0, "o viewport ficou sem largura, e a medida nao vale nada");

        // A pagina pediu 2.000 de largura. Sem acompanhar o viewport ela ficaria
        // com os 2.000, passaria da borda direita e seria cortada la -- e com a
        // barra horizontal proibida nao haveria como alcancar o resto. As dicas
        // ja sabem se encurtar com reticencias quando a linha acaba; o que elas
        // precisam e saber ONDE a linha acaba.
        assertEquals(doEspaco, daPagina,
                "a pagina nao acompanhou a largura do viewport: ficou com " + daPagina
                        + " para um espaco de " + doEspaco);
    }
}
