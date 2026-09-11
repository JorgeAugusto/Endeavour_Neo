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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O custo e o tamanho da ordem, guardados.
 *
 * <p>São ajustes, e não campos ao lado do botão de rodar: um custo num
 * <i>spinner</i> encostado no Rodar é um custo que muda por acidente, e custo
 * mudado por acidente é resultado errado com cara de normal.</p>
 */
@DisplayName("Ajustes do backtest")
class BacktestPreferencesTest {

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
    @DisplayName("de fabrica, o custo e o medido e a ordem e de um contrato")
    void outOfTheBoxTheCostIsTheMeasuredOne() {
        assertEquals(6.5, BacktestPreferences.cost(), 1e-9, "o padrao nao e o custo medido");
        assertTrue(BacktestPreferences.costs().measured(), "o padrao nao se reconhece como medido");
        assertEquals(1, BacktestPreferences.contracts(), "o padrao nao e um contrato");
    }

    @Test
    @DisplayName("meio ponto vai e volta inteiro")
    void halfAPointSurvivesTheRoundTrip() {
        // Guardado em DECIMOS porque Settings guarda inteiro, e um texto teria
        // de ser interpretado -- e interpretar decimal e escolher um idioma:
        // 6,5 e 6.5 sao o mesmo numero escrito pela mesma pessoa em dois dias.
        BacktestPreferences.setCostTenths(125);

        assertEquals(12.5, BacktestPreferences.cost(), 1e-9, "12,5 nao sobreviveu");

        BacktestPreferences.setCostTenths(0);

        assertEquals(0, BacktestPreferences.cost(), 1e-9, "zero nao sobreviveu");
    }

    @Test
    @DisplayName("um custo sem sentido e aparado, nao aceito")
    void anAbsurdCostIsClampedRatherThanStored() {
        BacktestPreferences.setCostTenths(-40);

        assertEquals(0, BacktestPreferences.costTenths(), "custo negativo entrou");

        BacktestPreferences.setCostTenths(999_999);

        assertEquals(BacktestPreferences.MOST_COST_TENTHS, BacktestPreferences.costTenths(),
                "custo alem do teto entrou");
    }

    @Test
    @DisplayName("uma ordem de zero contratos nao e uma ordem")
    void anOrderOfNoContractsIsNotAnOrder() {
        BacktestPreferences.setContracts(0);

        assertEquals(1, BacktestPreferences.contracts(), "aceitou negociar zero contratos");

        BacktestPreferences.setContracts(9_999);

        assertEquals(BacktestPreferences.MOST_CONTRACTS, BacktestPreferences.contracts(),
                "aceitou um lote alem do teto");
    }

    @Test
    @DisplayName("valor escrito A MAO no arquivo tambem e aparado")
    void aValueTypedStraightIntoTheFileIsClampedToo() {
        // O guarda do LEITOR nao dispara pelo caminho normal: quem escreve ja
        // apara. Ele existe para o outro caminho -- o arquivo de ajustes e
        // texto, e alguem o abre. Sem isto aqui, os dois guardas seriam codigo
        // que parece segurar algo e nao segura nada.
        Settings.settings().putInt("backtestCostTenths", -400);
        Settings.settings().putInt("backtestContracts", 0);

        assertEquals(0, BacktestPreferences.costTenths(), "custo negativo do arquivo passou");
        assertEquals(1, BacktestPreferences.contracts(), "lote zero do arquivo passou");

        Settings.settings().putInt("backtestCostTenths", 999_999);
        Settings.settings().putInt("backtestContracts", 9_999);

        assertEquals(BacktestPreferences.MOST_COST_TENTHS, BacktestPreferences.costTenths(),
                "custo absurdo do arquivo passou");
        assertEquals(BacktestPreferences.MOST_CONTRACTS, BacktestPreferences.contracts(),
                "lote absurdo do arquivo passou");
    }

    @Test
    @DisplayName("um custo que nao e o medido se declara assim")
    void aCostThatIsNotTheMeasuredOneSaysSo() {
        BacktestPreferences.setCostTenths(0);

        // E o que faz o bloco de honestidade ficar vermelho: uma rodada sem
        // custo nenhum da lucro e parece comum.
        assertTrue(!BacktestPreferences.costs().measured(),
                "uma rodada de graca passou por medida");
    }
}
