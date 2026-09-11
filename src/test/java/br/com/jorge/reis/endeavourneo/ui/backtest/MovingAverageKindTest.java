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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.Average;
import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A estratégia do cruzamento e os ajustes dela.
 *
 * <p>Os parâmetros de uma estratégia são <b>dela</b>, e não da barra de
 * comando — a barra teria de crescer uma fila por estratégia e mostrar a fila
 * errada na maior parte do tempo. O que estes testes defendem é que a tela dela
 * guarda o que guarda, e que o que sai de lá é sempre uma estratégia
 * construível.</p>
 */
@DisplayName("A estrategia do cruzamento")
class MovingAverageKindTest {

    /** Ajustes deste teste, nunca os do leitor. */
    @TempDir
    Path folder;

    @BeforeEach
    void useMyOwnSettings() {
        Settings.useForTest(folder);
    }

    @AfterEach
    void putThemBack() {
        Settings.stopUsingTestStore();
    }

    @Test
    @DisplayName("de fabrica sao as periodos dele: 17 e 34, exponencial")
    void outOfTheBoxTheyAreHisPeriods() {
        assertEquals(17, MovingAverageKind.fastPeriod(), "o rapido nao e 17");
        assertEquals(34, MovingAverageKind.slowPeriod(), "o lento nao e 34");
        assertEquals(Average.EXPONENTIAL, MovingAverageKind.kindOfAverage(),
                "o padrao nao e a exponencial");
    }

    @Test
    @DisplayName("o lento e SEMPRE mais lento que o rapido, venha do arquivo o que vier")
    void theSlowOneIsAlwaysSlower() {
        // Invertidos no arquivo -- a mao, ou por um ajuste antigo. A estrategia
        // RECUSA a ordem trocada, e ela recusa lancando: do lado do leitor isso
        // e um botao Rodar que nao faz nada e nao diz nada.
        Settings.settings().putInt("strategy.crossing.fast", 50);
        Settings.settings().putInt("strategy.crossing.slow", 9);

        assertEquals(50, MovingAverageKind.fastPeriod(), "o rapido nao veio do arquivo");
        assertTrue(MovingAverageKind.slowPeriod() > MovingAverageKind.fastPeriod(),
                "o lento (" + MovingAverageKind.slowPeriod() + ") nao ficou mais lento que o rapido");
    }

    @Test
    @DisplayName("e por isso a estrategia sempre se constroi")
    void andThatIsWhyTheStrategyAlwaysBuilds() {
        Settings.settings().putInt("strategy.crossing.fast", 80);
        Settings.settings().putInt("strategy.crossing.slow", 3);

        Strategy built = new MovingAverageKind().build();

        assertNotNull(built, "nao construiu a estrategia com os periodos trocados");
    }

    @Test
    @DisplayName("o tipo da media vai e volta")
    void thekindOfAverageSurvives() {
        Settings.settings().put("strategy.crossing.kind", Average.SIMPLE.name());

        assertEquals(Average.SIMPLE, MovingAverageKind.kindOfAverage(), "a simples nao voltou");

        // E o nome dela chega ao grafico: a legenda diz SMA, nao EMA.
        assertTrue(new MovingAverageKind().build().toString().contains("SMA"),
                "a estrategia nao se diz simples: " + new MovingAverageKind().build());
    }

    @Test
    @DisplayName("um tipo que nao existe cai na exponencial, e nao estoura")
    void anunknownKindFallsBackInsteadOfThrowing() {
        Settings.settings().put("strategy.crossing.kind", "TRIANGULAR");

        assertEquals(Average.EXPONENTIAL, MovingAverageKind.kindOfAverage(),
                "um tipo desconhecido no arquivo derrubou a leitura");
    }

    @Test
    @DisplayName("a estrategia tem nome, tela e construcao")
    void thekindOffersTheThreeThingsAStrategyNeeds() {
        StrategyKind kind = new MovingAverageKind();

        assertTrue(kind.label() != null && !kind.label().isBlank(), "a estrategia nao tem nome");
        assertNotNull(kind.page(), "a estrategia nao tem tela propria");
        assertNotNull(kind.build(), "a estrategia nao se constroi");

        assertEquals(1, StrategyKind.available().size(),
                "a lista de estrategias mudou de tamanho e este teste nao soube");
    }
}
