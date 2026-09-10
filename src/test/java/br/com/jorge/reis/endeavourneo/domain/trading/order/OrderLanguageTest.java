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
package br.com.jorge.reis.endeavourneo.domain.trading.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A linguagem de execução: os doze verbos do NTSL e os três comandos.
 *
 * <p>O que estes testes defendem não é o funcionamento de nada — nada aqui
 * executa. É a <b>forma</b>: que os doze nomes do Profit são exatamente as doze
 * combinações de lado × propósito × gatilho, sem sobra e sem falta, e que a
 * tradução de volta ao nome do NTSL é a identidade. É isso que faz o porte de um
 * robô ser transcrição, e não interpretação.</p>
 */
@DisplayName("A linguagem de execução")
class OrderLanguageTest {

    private static final int LOTE = 3;

    /** As doze fábricas, cada uma com o nome que o NTSL usa. */
    private static List<Order> theTwelve() {
        return List.of(
                Order.buyAtMarket(1),
                Order.buyLimit(100, 1),
                Order.buyStop(100, 100, 1),
                Order.sellShortAtMarket(1),
                Order.sellShortLimit(100, 1),
                Order.sellShortStop(100, 100, 1),
                Order.buyToCoverAtMarket(1),
                Order.buyToCoverLimit(100, 1),
                Order.buyToCoverStop(100, 100, 1),
                Order.sellToCoverAtMarket(1),
                Order.sellToCoverLimit(100, 1),
                Order.sellToCoverStop(100, 100, 1));
    }

    @Test
    @DisplayName("as doze fabricas cobrem as doze combinacoes, sem sobra e sem falta")
    void theTwelveVerbsAreTheTwelveCombinations() {
        Set<String> covered = new HashSet<>();

        for (Order order : theTwelve()) {
            covered.add(order.side() + "/" + order.purpose() + "/" + order.trigger());
        }

        // Doze fabricas distintas: se duas colidissem, uma combinacao ficaria
        // sem verbo -- e um robo do Profit que a usasse nao teria como ser
        // transcrito.
        assertEquals(12, covered.size(), "duas fabricas produzem a mesma combinacao: " + covered);

        for (Side side : Side.values()) {
            for (Purpose purpose : Purpose.values()) {
                for (Trigger trigger : Trigger.values()) {
                    assertTrue(covered.contains(side + "/" + purpose + "/" + trigger),
                            "nenhuma fabrica produz " + side + " " + purpose + " " + trigger);
                }
            }
        }
    }

    @Test
    @DisplayName("cada ordem sabe dizer o nome que o NTSL lhe da")
    void everyOrderNamesItselfAsNtslWould() {
        List<String> names = new ArrayList<>();

        for (Order order : theTwelve()) {
            names.add(order.verb());
        }

        assertEquals(List.of(
                        "BuyAtMarket", "BuyLimit", "BuyStop",
                        "SellShortAtMarket", "SellShortLimit", "SellShortStop",
                        "BuyToCoverAtMarket", "BuyToCoverLimit", "BuyToCoverStop",
                        "SellToCoverAtMarket", "SellToCoverLimit", "SellToCoverStop"),
                names,
                "o nome deixou de bater com o do manual");
    }

    @Test
    @DisplayName("preco que nao se aplica e NaN, nao zero")
    void aPriceThatDoesNotApplyIsNotAPrice() {
        Order market = Order.buyAtMarket(1);

        // Zero seria um preco -- e um otimo preco de compra. A primeira conta
        // que o tocasse devolveria um numero em vez de uma reclamacao.
        assertTrue(Double.isNaN(market.stop()), "ordem a mercado ganhou um stop");
        assertTrue(Double.isNaN(market.limit()), "ordem a mercado ganhou um limite");
        assertTrue(Double.isNaN(Order.buyLimit(100, 1).stop()), "ordem limitada ganhou um stop");
    }

    @Test
    @DisplayName("a ordem recusa o que o NTSL nao aceitaria")
    void theOrderRefusesWhatNtslWouldNot() {
        assertThrows(IllegalArgumentException.class,
                () -> new Order(Side.BUY, Purpose.OPEN, Trigger.MARKET, 100, Double.NaN, 1),
                "ordem a mercado aceitou um preco proprio");

        assertThrows(IllegalArgumentException.class,
                () -> new Order(Side.BUY, Purpose.OPEN, Trigger.LIMIT, Double.NaN, Double.NaN, 1),
                "ordem limitada aceitou nao ter limite");

        assertThrows(IllegalArgumentException.class,
                () -> new Order(Side.BUY, Purpose.OPEN, Trigger.STOP, 100, Double.NaN, 1),
                "ordem stop aceitou nao ter ate onde executar");

        assertThrows(IllegalArgumentException.class,
                () -> Order.buyAtMarket(0),
                "uma ordem de zero contratos nao e uma ordem");
    }

    @Test
    @DisplayName("a mesa resolve o lote quando a quantidade e omitida")
    void theDeskResolvesTheLotWhenTheQuantityIsLeftOut() {
        Desk desk = new Desk(LOTE);

        desk.buyAtMarket();
        desk.sellToCoverLimit(200);
        desk.buyStop(100, 100);

        // NTSL: quantidade omitida = campo "Quantidade por Ordem". Ela e' uma
        // propriedade da MESA, entao e' resolvida aqui -- e nenhuma Order que
        // existe carrega tamanho que dependa de ajuste que ela nunca viu.
        for (Instruction instruction : desk.instructions()) {
            assertEquals(LOTE, ((Order) instruction).quantity(),
                    "a mesa nao resolveu o lote de " + instruction.verb());
        }
    }

    @Test
    @DisplayName("a quantidade explicita ignora o lote da mesa")
    void anExplicitQuantityIgnoresTheLot() {
        Desk desk = new Desk(LOTE);

        desk.buyAtMarket(7);

        assertEquals(7, ((Order) desk.instructions().get(0)).quantity(),
                "a quantidade escrita no codigo foi sobreposta pelo lote");
    }

    @Test
    @DisplayName("a ordem entre ordens e comandos e preservada")
    void theOrderBetweenOrdersAndCommandsSurvives() {
        Desk desk = new Desk(1);

        // A inversao escrita como duas ordens -- que e' como os robos dele
        // fazem. Lidas fora de ordem, a segunda fecharia a posicao que a
        // primeira acabou de abrir.
        desk.closePosition();
        desk.buyAtMarket(2);

        List<Instruction> asked = desk.instructions();

        assertEquals(2, asked.size(), "a mesa perdeu uma instrucao");
        assertEquals(Command.CLOSE_POSITION, asked.get(0), "o fechamento saiu de lugar");
        assertEquals("BuyAtMarket", asked.get(1).verb(), "a abertura saiu de lugar");
    }

    @Test
    @DisplayName("os tres comandos tambem sabem o proprio nome")
    void theThreeCommandsNameThemselvesToo() {
        assertEquals("ClosePosition", Command.CLOSE_POSITION.verb());
        assertEquals("ReversePosition", Command.REVERSE_POSITION.verb());
        assertEquals("CancelPendingOrders", Command.CANCEL_PENDING_ORDERS.verb());
    }

    @Test
    @DisplayName("a mesa esquece o que foi pedido quando mandam esquecer")
    void theDeskForgetsBetweenBars() {
        Desk desk = new Desk(1);

        desk.buyAtMarket();
        desk.clear();

        assertTrue(desk.instructions().isEmpty(), "a mesa levou para a barra seguinte o pedido da anterior");
    }

    @Test
    @DisplayName("trocar a quantidade nao troca mais nada")
    void changingTheQuantityChangesNothingElse() {
        Order original = Order.sellToCoverLimit(138_500, 2);
        Order resized = original.of(5);

        assertEquals(5, resized.quantity(), "a quantidade nao mudou");
        assertEquals(original.verb(), resized.verb(), "o verbo mudou junto");
        assertEquals(original.limit(), resized.limit(), 0.0, "o preco mudou junto");
    }

    @Test
    @DisplayName("uma mesa que negocia zero contratos nao e uma mesa")
    void aDeskThatTradesNothingIsNotADesk() {
        assertThrows(IllegalArgumentException.class, () -> new Desk(0));
    }
}
