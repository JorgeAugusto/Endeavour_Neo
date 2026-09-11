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

import br.com.jorge.reis.endeavourneo.domain.trading.strategy.RangeBreakout;
import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.nio.file.Path;
import java.time.LocalTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A tela da Range 90.
 *
 * <p>Ela guarda cinco coisas, e a que mais importa é o <b>horário de
 * encerramento</b>: uma posição viva depois dele é uma posição levada para o
 * leilão de fechamento, e no gráfico parece posição que virou a noite.</p>
 */
@DisplayName("A tela da Range 90")
class RangeBreakoutKindTest {

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
    @DisplayName("de fabrica: 90 de formacao, 30 de janela, 4 e 20, e 17:45")
    void outOfTheBoxTheseAreTheNumbers() {
        assertEquals(90, RangeBreakoutKind.formation(), "a formacao nao e de noventa minutos");
        assertEquals(30, RangeBreakoutKind.window(), "a janela de entrada nao e de trinta");
        assertEquals(4, RangeBreakoutKind.lot(), "o lote nao e de quatro contratos");
        assertEquals(20, RangeBreakoutKind.cap(), "o teto nao e de vinte contratos");
        assertEquals(LocalTime.of(17, 45), RangeBreakoutKind.closeAt(),
                "o horario de encerramento nao e 17:45");
    }

    @Test
    @DisplayName("O HORARIO GUARDADO E O HORARIO LIDO, nos dois sentidos")
    void thetimeThatWasSavedIsTheTimeThatComesBack() {
        // Guardado em MINUTOS depois da meia-noite, e nao como "17:45": Settings
        // guarda texto, e um horario escrito por extenso tem de ser lido de
        // volta -- o que significa escolher separador e idioma para um valor que
        // ninguem fora desta classe le.
        Settings.settings().putInt("strategy.range90.closeAt", 16 * 60 + 30);

        assertEquals(LocalTime.of(16, 30), RangeBreakoutKind.closeAt(),
                "o horario guardado voltou diferente");

        Settings.settings().putInt("strategy.range90.closeAt", 9 * 60 + 5);

        assertEquals(LocalTime.of(9, 5), RangeBreakoutKind.closeAt(),
                "um horario de um digito na hora voltou errado");
    }

    @Test
    @DisplayName("horario impossivel no arquivo nao derruba a leitura")
    void animpossibleTimeInTheFileDoesNotBringTheReaderDown() {
        // O caminho do arquivo editado a mao. A trava do lado da leitura so pode
        // ser exercida por aqui, porque a tela nunca consegue escrever isto.
        Settings.settings().putInt("strategy.range90.closeAt", 5_000);

        assertEquals(LocalTime.of(23, 59), RangeBreakoutKind.closeAt(),
                "um horario alem do fim do dia nao foi contido");

        Settings.settings().putInt("strategy.range90.closeAt", -120);

        assertEquals(LocalTime.MIDNIGHT, RangeBreakoutKind.closeAt(),
                "um horario negativo nao foi contido");
    }

    @Test
    @DisplayName("o teto nunca fica abaixo do lote, nem vindo do arquivo")
    void theceilingIsNeverBelowTheLot() {
        Settings.settings().putInt("strategy.range90.lot", 6);
        Settings.settings().putInt("strategy.range90.cap", 2);

        // Um teto menor que o lote inicial e um dia que nunca entra -- e uma
        // tela que pode ficar nesse estado e uma tela que desliga a rodada em
        // silencio.
        assertEquals(6, RangeBreakoutKind.cap(),
                "o teto ficou abaixo do lote e nenhum dia entraria");
    }

    @Test
    @DisplayName("a estrategia se constroi com o que a tela deixou")
    void thestrategyIsBuiltFromWhatTheScreenLeft() {
        Settings.settings().putInt("strategy.range90.closeAt", 16 * 60);

        RangeBreakoutKind kind = new RangeBreakoutKind();

        assertNotNull(kind.build(), "a estrategia nao se constroi");
        assertNotNull(kind.page(), "a estrategia nao tem tela propria");
        assertEquals(LocalTime.of(17, 45), RangeBreakout.CLOSE_AT,
                "o padrao da propria estrategia deixou de ser 17:45");
    }
}
