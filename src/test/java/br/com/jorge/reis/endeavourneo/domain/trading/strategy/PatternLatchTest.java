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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.indicator.Stochastic;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O latch do estocástico e o dobrar mão.
 *
 * <p>Os dois módulos mudam <b>quantas</b> entradas acontecem e <b>de que
 * tamanho</b>, e nenhum deles toca nos níveis: entrada, stop e alvo continuam
 * sendo o que {@code PatternBreakoutTest} já defende. O último teste aqui é o
 * que garante isso — com os dois desligados, a estratégia executa preço por
 * preço o que executava antes de eles existirem.</p>
 */
@DisplayName("Latch e dobrar mao")
class PatternLatchTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Barras ditadas, uma por minuto, todas do mesmo pregão. */
    private record Bars(double[][] ohlc) implements PriceSeries {

        @Override
        public int size() {
            return ohlc.length;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2025, 1, 6), LocalTime.of(10, 0), SP)
                    .toInstant().toEpochMilli() + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return ohlc[index][0];
        }

        @Override
        public double highAt(int index) {
            return ohlc[index][1];
        }

        @Override
        public double lowAt(int index) {
            return ohlc[index][2];
        }

        @Override
        public double closeAt(int index) {
            return ohlc[index][3];
        }
    }

    /**
     * Um crédito grande e armado desde a primeira barra: o latch sai da frente.
     *
     * <p>Período e suavização de <b>um</b>, e não os oito e três de verdade, por
     * causa do aquecimento: o estocástico de 8/3 só tem o primeiro valor na
     * barra nove, e um latch que só pode armar dali em diante recusa as entradas
     * anteriores por não ter leitura nenhuma — o que é correto, e não é o que
     * estes testes querem medir.</p>
     *
     * <p>O desarme fica em 101 pelo mesmo motivo do nivel de venda: e
     * inalcancavel. Com o desarme de verdade, em 50, um nivel de compra de
     * 100 armaria e o proprio minuto seguinte desarmaria — o credito nunca
     * chegaria a um padrao.</p>
     */
    private static final PatternBreakout.Latch ABERTO =
            new PatternBreakout.Latch(true, 1, 1, 100, 101, 101, 99);

    /**
     * Blocos de seis barras, cada um com um PFR de alta e o rompimento dele.
     *
     * <p>Todo bloco começa e termina no mesmo nível, de propósito: um bloco que
     * terminasse mais alto faria a primeira barra do seguinte marcar máxima nova
     * de três barras, o que é um PFR de BAIXA — e o plano vendido dele viveria
     * por cima do PFR de alta que vem depois, que é o que este arquivo quer
     * medir. O pregão foi construído até os padrões pararem de interferir uns
     * nos outros.</p>
     *
     * @param ganha para cada bloco, se o rompimento vai ao alvo ou ao stop
     */
    private static double[][] blocos(boolean... ganha) {
        List<double[]> feitas = new ArrayList<>();
        double l = 100_000;

        for (boolean alvo : ganha) {
            feitas.add(new double[] {l, l + 100, l - 50, l + 50});
            feitas.add(new double[] {l + 50, l + 120, l - 100, l});
            // O PFR: menor minima das tres, fecha em alta. Gatilho em l+105,
            // stop em l-200 -- a minima da estrutura.
            feitas.add(new double[] {l - 150, l + 100, l - 200, l + 80});

            if (alvo) {
                feitas.add(new double[] {l + 80, l + 700, l + 70, l + 650});
                feitas.add(new double[] {l + 650, l + 700, l + 600, l + 620});
                feitas.add(new double[] {l + 620, l + 640, l - 10, l});
            } else {
                feitas.add(new double[] {l + 80, l + 200, l - 300, l - 250});
                feitas.add(new double[] {l - 250, l - 200, l - 300, l - 260});
                feitas.add(new double[] {l - 260, l - 200, l - 20, l});
            }
        }

        // Cauda: a estrategia manda fechar duas barras antes do fim.
        feitas.add(new double[] {l, l + 50, l - 50, l});
        feitas.add(new double[] {l, l + 50, l - 50, l});

        return feitas.toArray(new double[0][]);
    }

    private static Result run(double[][] ohlc, PatternBreakout.Latch latch,
                              PatternBreakout.Doubling doubling) {

        PriceSeries bars = new Bars(ohlc);
        PatternBreakout what = new PatternBreakout(SP, CandlePattern.Family.PFR,
                1.5, 3, 1, latch, doubling);

        what.sourcedFrom(bars);

        return new Backtest(Costs.NONE, 1).run(bars, bars, what, null);
    }

    /** Só as execuções que ABRIRAM posição, na ordem. */
    private static List<Fill> entradas(Result result) {
        List<Fill> abriu = new ArrayList<>();

        for (Fill fill : result.fills()) {
            if (fill.verb().startsWith("Buy") && !fill.verb().contains("Cover")) {
                abriu.add(fill);
            }
        }

        return abriu;
    }

    private static List<Integer> maos(List<Fill> fills) {
        List<Integer> quantas = new ArrayList<>();

        for (Fill fill : fills) {
            quantas.add(fill.quantity());
        }

        return quantas;
    }

    @Test
    @DisplayName("O ESTOCASTICO E QUEM LIBERA: sem chegar em 20, nao compra")
    void thestochasticIsWhatOpensTheGate() {
        double[][] ohlc = blocos(true, true, true);

        // O PREGAO NUNCA FICA SOBREVENDIDO, e isso e lido do proprio indicador
        // em vez de suposto: sem esta medida o teste abaixo passaria tambem se o
        // filtro estivesse recusando tudo por engano.
        double[] slow = Stochastic.standard().over(new Bars(ohlc)).slow();
        double menor = Double.MAX_VALUE;

        for (double each : slow) {
            if (!Double.isNaN(each)) {
                menor = Math.min(menor, each);
            }
        }

        assertTrue(menor > 20, "o pregao chega a ficar sobrevendido (%K=" + menor + ")");

        // Sem filtro os tres padroes operam. Com o filtro padrao, nenhum: o
        // estocastico nunca desceu ao nivel que arma a compra.
        assertEquals(3, entradas(run(ohlc, PatternBreakout.Latch.off(),
                PatternBreakout.Doubling.off())).size(),
                "o pregao inventado nao gera os tres padroes esperados");

        assertEquals(0, entradas(run(ohlc, PatternBreakout.Latch.standard(),
                PatternBreakout.Doubling.off())).size(),
                "comprou sem o estocastico ter chegado em 20");
    }

    @Test
    @DisplayName("O NIVEL DE COMPRA GOVERNA A COMPRA, e o de venda nao")
    void thebuyLevelGovernsBuyingAndTheSellLevelDoesNot() {
        double[][] ohlc = blocos(true, true, true);

        // Nivel de compra alcancavel, de venda inalcancavel: compra.
        assertEquals(3, entradas(run(ohlc, ABERTO, PatternBreakout.Doubling.off())).size(),
                "com a compra armada ainda assim nao comprou");

        // Nivel de compra INALCANCAVEL e o de venda sempre atingido: se os dois
        // estivessem trocados no codigo, este seria o caso que opera.
        PatternBreakout.Latch trocado =
                new PatternBreakout.Latch(true, 1, 1, -1, 0, 101, 99);

        assertEquals(0, entradas(run(ohlc, trocado, PatternBreakout.Doubling.off())).size(),
                "comprou com o nivel de compra inalcancavel: o lado esta invertido");
    }

    @Test
    @DisplayName("UMA ARMADA PAGA N ENTRADAS, e a seguinte espera outra armada")
    void onearmingPaysForNentriesAndNoMore() {
        double[][] ohlc = blocos(true, true, true);

        // O estocastico entra na zona uma vez e fica la, entao o latch arma UMA
        // vez no pregao inteiro -- e o credito daquela armada e tudo que existe.
        for (int credito = 1; credito <= 3; credito++) {
            PatternBreakout.Latch latch =
                    new PatternBreakout.Latch(true, 1, 1, 100, 101, 101, credito);

            assertEquals(credito,
                    entradas(run(ohlc, latch, PatternBreakout.Doubling.off())).size(),
                    "um credito de " + credito + " nao pagou exatamente " + credito);
        }
    }

    @Test
    @DisplayName("DOBRAR A MAO DOBRA A CADA STOP, e para no limite")
    void doublingDoublesAfterEachStopAndStopsAtTheLimit() {
        double[][] ohlc = blocos(false, false, false, false);

        List<Fill> semDobrar = entradas(run(ohlc, ABERTO, PatternBreakout.Doubling.off()));

        assertEquals(4, semDobrar.size(), "o pregao nao entrou quatro vezes");
        assertEquals(List.of(1, 1, 1, 1), maos(semDobrar),
                "sem o modulo a mao mudou de tamanho: " + maos(semDobrar));

        // N = 2: 1, 2, 4 e PARA em 4. A quarta entrada e o teto se mostrando --
        // sem ele seria 8.
        List<Fill> dobrando = entradas(run(ohlc, ABERTO,
                new PatternBreakout.Doubling(true, 2)));

        assertEquals(semDobrar.size(), dobrando.size(),
                "o modulo mudou QUANTAS entradas houve, e ele so muda o tamanho");
        assertEquals(List.of(1, 2, 4, 4), maos(dobrando),
                "a sequencia de maos nao foi 1, 2, 4, 4: " + maos(dobrando));
    }

    @Test
    @DisplayName("UM GANHO ZERA A SEQUENCIA")
    void awinResetsTheSequence() {
        // Stop, stop, ALVO, stop. Depois do ganho a mao volta a um.
        double[][] ohlc = blocos(false, false, true, false);

        List<Fill> dobrando = entradas(run(ohlc, ABERTO,
                new PatternBreakout.Doubling(true, 4)));

        assertEquals(4, dobrando.size(), "o pregao nao entrou quatro vezes");
        assertEquals(List.of(1, 2, 4, 1), maos(dobrando),
                "o ganho da terceira nao zerou a sequencia: " + maos(dobrando));
    }

    @Test
    @DisplayName("VOLTAR AO MEIO DESARMA: o credito nao sobrevive ao fim do estição")
    void comingBackToTheMiddleDisarms() {
        double[][] ohlc = blocos(true, true, true);

        // Tres padroes e credito de sobra. O que muda entre as duas rodadas e SO
        // onde fica o desarme.
        //
        // Com o desarme inalcancavel (101), a armada da primeira barra vale o
        // pregao inteiro e os tres padroes operam.
        PatternBreakout.Latch semDesarme =
                new PatternBreakout.Latch(true, 1, 1, 100, 101, 101, 99);

        assertEquals(3, entradas(run(ohlc, semDesarme, PatternBreakout.Doubling.off())).size(),
                "o credito sem desarme nao pagou os tres padroes");

        // Com o desarme em ZERO, ele passa a ser alcancavel: qualquer leitura do
        // estocastico em zero ou acima joga o credito fora, e como o estocastico
        // vive em zero ou acima, ele e jogado fora em toda barra. Nenhum padrao
        // encontra credito.
        PatternBreakout.Latch semprDesarma =
                new PatternBreakout.Latch(true, 1, 1, 100, 101, 0, 99);

        assertEquals(0,
                entradas(run(ohlc, semprDesarma, PatternBreakout.Doubling.off())).size(),
                "operou depois de o estocastico ter voltado ao nivel de desarme");
    }

    @Test
    @DisplayName("UM PADRAO QUE O LATCH RECUSA TAMBEM LARGA O PLANO ANTIGO")
    void apatternTheLatchRefusesAlsoDropsTheOlderPlan() {
        // Compra armada, venda nunca: um PFR de ALTA vira plano, e o de BAIXA da
        // barra seguinte e recusado por falta de credito vendido. O plano velho
        // morre assim mesmo -- o mercado acabou de recusar do outro lado, e o
        // nivel de ontem nao volta a valer so porque nao podemos operar o de
        // hoje.
        double[][] ohlc = {
            {100, 110, 95, 105},
            {105, 112, 90, 100},
            {85, 110, 80, 108},     // PFR de alta: gatilho comprado em 115
            {112, 114, 100, 105},   // PFR de baixa, e a maxima de 114 nao toca 115
            {105, 130, 104, 128},   // sobe atravessando 115
            {128, 132, 126, 130},
            {128, 132, 126, 130},
            {128, 132, 126, 130},
        };

        // Credito de compra existe; o de venda nao pode existir, porque o nivel
        // de venda esta acima do maximo que o estocastico alcanca.
        PatternBreakout.Latch soCompra =
                new PatternBreakout.Latch(true, 1, 1, 100, 101, 101, 1);

        assertEquals(0, entradas(run(ohlc, soCompra, PatternBreakout.Doubling.off())).size(),
                "o plano comprado sobreviveu ao padrao de baixa da barra 3");

        // A prova de que o pregao TEM a entrada quando nao ha padrao novo por
        // cima dela: sem o PFR de baixa na barra 3, a compra acontece.
        double[][] semOdeBaixa = {
            {100, 110, 95, 105},
            {105, 112, 90, 100},
            {85, 110, 80, 108},
            {108, 109, 100, 105},   // barra morna: nao e padrao nenhum
            {105, 130, 104, 128},
            {128, 132, 126, 130},
            {128, 132, 126, 130},
            {128, 132, 126, 130},
        };

        assertEquals(1,
                entradas(run(semOdeBaixa, soCompra, PatternBreakout.Doubling.off())).size(),
                "sem o padrao de baixa no meio a compra tambem nao aconteceu");
    }

    @Test
    @DisplayName("UM MODULO DESLIGADO NAO TEM DOBRAS, nem que lhe peçam")
    void amoduleThatIsOffHasNoDoublings() {
        // O estado em que os dois campos discordam nao existe: quem desliga o
        // modulo zera o limite, e o limite e o unico numero que a contagem le.
        // Sem isso, "desligado" seria uma coisa que todo leitor do limite tem de
        // lembrar de conferir tambem.
        assertEquals(0, new PatternBreakout.Doubling(false, 5).most(),
                "um modulo desligado guardou um limite de dobras");

        double[][] ohlc = blocos(false, false, false, false);

        List<Fill> pedindo = entradas(run(ohlc, ABERTO,
                new PatternBreakout.Doubling(false, 5)));

        assertEquals(List.of(1, 1, 1, 1), maos(pedindo),
                "a mao dobrou com o modulo desligado: " + maos(pedindo));
    }

    /** Blocos com PFR de alta, sobre uma tendência que se pode escolher. */
    private static double[][] emTendencia(int blocos, double passoPorBarra) {
        List<double[]> feitas = new ArrayList<>();
        double l = 100_000;

        for (int bloco = 0; bloco < blocos; bloco++) {
            feitas.add(new double[] {l, l + 100, l - 50, l + 50});
            feitas.add(new double[] {l + 50, l + 120, l - 100, l});
            feitas.add(new double[] {l - 150, l + 100, l - 200, l + 80});
            feitas.add(new double[] {l + 80, l + 700, l + 70, l + 650});
            feitas.add(new double[] {l + 650, l + 700, l + 600, l + 620});
            feitas.add(new double[] {l + 620, l + 640, l - 10, l});

            l += passoPorBarra * 6;
        }

        feitas.add(new double[] {l, l + 50, l - 50, l});
        feitas.add(new double[] {l, l + 50, l - 50, l});

        return feitas.toArray(new double[0][]);
    }

    private static Result run(double[][] ohlc, PatternBreakout.Trend trend) {
        PriceSeries bars = new Bars(ohlc);
        PatternBreakout what = new PatternBreakout(SP, CandlePattern.Family.PFR,
                1.5, 3, 1, PatternBreakout.Latch.off(), PatternBreakout.Doubling.off(), trend);

        what.sourcedFrom(bars);

        return new Backtest(Costs.NONE, 1).run(bars, bars, what, null);
    }

    @Test
    @DisplayName("AS MEDIAS CRUZADAS PRA BAIXO NAO DEIXAM COMPRAR")
    void averagesCrossedDownDoNotLetAbuyThrough() {
        // Vinte blocos subindo: o pregao anda o bastante para as duas medias
        // sairem do aquecimento e se cruzarem para cima.
        double[][] subindo = emTendencia(20, 6);

        int semPortao = entradas(run(subindo, PatternBreakout.Trend.off())).size();

        assertTrue(semPortao > 0, "o pregao inventado nao gera entrada nenhuma");

        // Subindo, o portao deixa comprar -- que e o que torna a recusa abaixo
        // uma medida do portao e nao do pregao.
        assertTrue(entradas(run(subindo, PatternBreakout.Trend.standard())).size() > 0,
                "com as medias cruzadas pra cima a compra foi recusada");

        // O MESMO desenho de padroes, num pregao que CAI: as medias cruzam pra
        // baixo e nenhum PFR de alta passa.
        double[][] caindo = emTendencia(20, -6);

        assertTrue(entradas(run(caindo, PatternBreakout.Trend.off())).size() > 0,
                "a versao caindo nao gera entrada nem sem portao");

        assertEquals(0, entradas(run(caindo, PatternBreakout.Trend.standard())).size(),
                "comprou com as medias cruzadas pra baixo");
    }

    @Test
    @DisplayName("no aquecimento das medias o portao fica FECHADO dos dois lados")
    void whiletheAveragesWarmUpTheGateIsShut() {
        // Poucos blocos: a media de 21 em cinco minutos nem chega a ter valor.
        // Sem resposta, o portao recusa -- e nao "deixa passar porque NaN
        // compara falso", que e o jeito de um filtro virar ruido silencioso.
        double[][] curto = emTendencia(3, 6);

        assertTrue(entradas(run(curto, PatternBreakout.Trend.off())).size() > 0,
                "o recorte curto nao gera entrada nem sem portao");

        assertEquals(0, entradas(run(curto, PatternBreakout.Trend.standard())).size(),
                "operou com as medias ainda aquecendo");
    }

    @Test
    @DisplayName("os dois modulos desligados nao mudam nada")
    void bothmodulesOffChangeNothing() {
        double[][] ohlc = blocos(true, false, true);

        Result antigo = new Backtest(Costs.NONE, 1).run(new Bars(ohlc), new Bars(ohlc),
                new PatternBreakout(SP, CandlePattern.Family.PFR, 1.5, 3, 1), null);

        Result agora = run(ohlc, PatternBreakout.Latch.off(), PatternBreakout.Doubling.off());

        assertFalse(antigo.fills().isEmpty(), "a comparacao foi entre duas rodadas vazias");
        assertEquals(antigo.fills().size(), agora.fills().size(),
                "o construtor curto e o longo com tudo desligado divergiram");

        for (int i = 0; i < antigo.fills().size(); i++) {
            assertEquals(antigo.fills().get(i).price(), agora.fills().get(i).price(), 1e-9,
                    "a execucao " + i + " saiu em outro preco");
            assertEquals(antigo.fills().get(i).quantity(), agora.fills().get(i).quantity(),
                    "a execucao " + i + " saiu com outra quantidade");
        }
    }
}
