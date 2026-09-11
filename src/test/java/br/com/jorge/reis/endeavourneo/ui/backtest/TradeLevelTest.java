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

import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Command;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Order;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Purpose;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Trigger;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O que cada verbo vira no gráfico: entrada, stop, alvo ou saída.
 *
 * <p>A cor diz o <b>desfecho</b>, não a direção — vermelho no stop e verde no
 * alvo valem para compra e para venda igualmente, porque o que se lê é "aqui eu
 * morria" e "aqui eu realizava". Este teste é exaustivo de propósito: <b>todo</b>
 * verbo que a linguagem consegue produzir tem de ter um nível, ou um dia
 * aparece uma linha sem cor e sem nome no gráfico.</p>
 */
@DisplayName("O nivel de cada verbo")
class TradeLevelTest {

    private static Fill fillOf(String verb) {
        return new Fill(0, Side.BUY, 100, 1, verb);
    }

    private static TradeLevel of(String verb) {
        return TradeLevel.of(fillOf(verb));
    }

    @Test
    @DisplayName("abrir posicao e entrada, nas tres formas e nos dois lados")
    void openingIsAnEntryInAllThreeFormsAndBothSides() {
        for (String verb : List.of("BuyAtMarket", "BuyLimit", "BuyStop",
                "SellShortAtMarket", "SellShortLimit", "SellShortStop")) {
            assertEquals(TradeLevel.ENTRY, of(verb), verb + " nao foi lido como entrada");
        }
    }

    @Test
    @DisplayName("cobertura por stop e stop; por limite e alvo; a mercado e saida")
    void coveringSplitsIntoStopTargetAndExit() {
        assertEquals(TradeLevel.STOP, of("SellToCoverStop"), "o stop da compra nao e stop");
        assertEquals(TradeLevel.STOP, of("BuyToCoverStop"), "o stop da venda nao e stop");

        assertEquals(TradeLevel.TARGET, of("SellToCoverLimit"), "o alvo da compra nao e alvo");
        assertEquals(TradeLevel.TARGET, of("BuyToCoverLimit"), "o alvo da venda nao e alvo");

        // Saida a mercado nao e alvo: e saida por TEMPO -- fim de pregao,
        // circuit breaker. Pintada de verde leria como plano que deu certo.
        assertEquals(TradeLevel.EXIT, of("SellToCoverAtMarket"), "a saida a mercado virou alvo");
        assertEquals(TradeLevel.EXIT, of("BuyToCoverAtMarket"), "a saida a mercado virou alvo");
    }

    @Test
    @DisplayName("os comandos de posicao sao saida")
    void thePositionCommandsAreExits() {
        assertEquals(TradeLevel.EXIT, of("ClosePosition"), "ClosePosition nao e saida");
        assertEquals(TradeLevel.EXIT, of("ReversePosition"), "ReversePosition nao e saida");
    }

    @Test
    @DisplayName("TODO verbo da linguagem tem um nivel, e nenhum cai no lado errado")
    void everyVerbTheLanguageCanProduceHasALevel() {
        List<String> verbs = new ArrayList<>();

        for (Side side : Side.values()) {
            for (Purpose purpose : Purpose.values()) {
                for (Trigger trigger : Trigger.values()) {
                    double stop = trigger == Trigger.STOP ? 100 : Double.NaN;
                    double limit = trigger == Trigger.MARKET ? Double.NaN : 100;

                    verbs.add(new Order(side, purpose, trigger, stop, limit, 1).verb());
                }
            }
        }

        for (Command command : Command.values()) {
            verbs.add(command.verb());
        }

        assertEquals(15, verbs.size(), "a linguagem mudou de tamanho e este teste nao soube");

        for (String verb : verbs) {
            TradeLevel level = TradeLevel.of(fillOf(verb));

            assertNotNull(level, verb + " nao tem nivel");

            // Uma ordem que ABRE nunca pode virar stop ou alvo: seria uma linha
            // vermelha onde ninguem morreu.
            boolean opens = !verb.contains("ToCover") && !verb.startsWith("Close")
                    && !verb.startsWith("Reverse");

            assertEquals(opens, level == TradeLevel.ENTRY,
                    verb + " foi lido como " + level);
        }
    }

    @Test
    @DisplayName("cada nivel tem cor e nome proprios")
    void eachLevelHasItsOwnColourAndWord() {
        for (TradeLevel level : TradeLevel.values()) {
            assertNotNull(level.colour(), level + " sem cor");
            assertNotNull(level.label(), level + " sem nome");
        }

        assertEquals(TradeLevel.values().length,
                java.util.Arrays.stream(TradeLevel.values()).map(TradeLevel::colour).distinct().count(),
                "dois niveis dividem a mesma cor");
    }
}
