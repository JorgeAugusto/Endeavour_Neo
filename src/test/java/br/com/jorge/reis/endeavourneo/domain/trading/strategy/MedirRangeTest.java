package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Slice;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** A medicao do Range 90 sem selecao, alvo 1,5R. Descartavel: depende do disco. */
class MedirRangeTest {

    @Test
    void medir() throws Exception {
        ZoneId zone = Timeframe.defaultZone();
        PriceSeries whole = SeriesCatalog.open("winfull-1m").orElse(null);

        if (whole == null) {
            System.out.println("MEDIDA SEM SERIE");

            return;
        }

        System.out.println("MEDIDA celula;pregoes;operacoes;giros;bruto;custo;liquido;"
                + "bruto/op;liquido/op;acerto;queda;exposicao;desvio;t;ambiguas");

        celula("2025", whole, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31), zone);
        celula("2026", whole, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), zone);
        celula("2025+2026", whole, LocalDate.of(2025, 1, 1), LocalDate.of(2026, 12, 31), zone);
    }

    private static void celula(String nome, PriceSeries whole, LocalDate de, LocalDate ate,
                               ZoneId zone) {

        PriceSeries cut = Slice.between(whole, de, ate, zone);

        if (cut == null || cut.size() == 0) {
            System.out.println("MEDIDA " + nome + ";recorte vazio");

            return;
        }

        PriceSeries decided = Timeframe.FIVE_MINUTES.apply(cut, zone);

        // Os defaults da estrategia, com o seletor DESLIGADO, alvo fixo 1,5R e o
        // filtro de tendencia desligado -- que e o padrao de fabrica agora.
        RangeBreakout what = new RangeBreakout(zone, RangeBreakout.LOT, RangeBreakout.CAP,
                RangeBreakout.ENTRY_WINDOW, RangeBreakout.FORMATION, RangeBreakout.CLOSE_AT,
                false, 1.5, false);

        Result result = new Backtest(new Costs(6.5), RangeBreakout.LOT)
                .run(cut, decided, what, null);

        Set<LocalDate> dias = new LinkedHashSet<>();

        for (int bar = 0; bar < cut.size(); bar++) {
            dias.add(Instant.ofEpochMilli(cut.timeAt(bar)).atZone(zone).toLocalDate());
        }

        double soma = 0;
        double quadrados = 0;
        int quantas = 0;

        for (Trade trade : result.trades()) {
            soma += trade.gross();
            quadrados += trade.gross() * trade.gross();
            quantas++;
        }

        double media = quantas == 0 ? 0 : soma / quantas;
        double desvio = quantas == 0 ? 0
                : Math.sqrt(Math.max(0, quadrados / quantas - media * media));
        double t = desvio == 0 ? 0 : media / (desvio / Math.sqrt(quantas));

        System.out.printf(Locale.ROOT,
                "MEDIDA %s;%d;%d;%d;%.0f;%.0f;%.0f;%.2f;%.2f;%.1f%%;%.0f;%.1f%%;%.1f;%.2f;%d%n",
                nome, dias.size(), result.count(), result.contractsTurned(),
                result.gross(), result.cost(), result.net(),
                media, quantas == 0 ? 0 : result.net() / quantas,
                quantas == 0 ? 0 : 100.0 * result.wins() / quantas,
                result.drawdown(), 100 * result.exposure(),
                desvio, t, result.ambiguousBars());

        // A DISTRIBUICAO, e nao so a media: com desvio seis vezes maior que a
        // media, a pergunta que decide tudo e se o total vem de todo mundo ou de
        // meia duzia de dias.
        double[] brutos = new double[quantas];
        int at = 0;

        for (Trade trade : result.trades()) {
            brutos[at++] = trade.gross();
        }

        java.util.Arrays.sort(brutos);

        double mediana = quantas == 0 ? 0
                : (quantas % 2 == 1 ? brutos[quantas / 2]
                        : (brutos[quantas / 2 - 1] + brutos[quantas / 2]) / 2);

        double cinco = 0;

        for (int i = Math.max(0, quantas - 5); i < quantas; i++) {
            cinco += brutos[i];
        }

        double maiorLote = 0;

        for (Trade trade : result.trades()) {
            maiorLote = Math.max(maiorLote, trade.contracts());
        }

        System.out.printf(Locale.ROOT,
                "MEDIDA %s DISTRIBUICAO mediana=%.0f pior=%.0f melhor=%.0f "
                + "top5=%.0f (%.0f%% do bruto) maior posicao=%.0f contratos aberto=%d "
                + "trivial=%.0f%n",
                nome, mediana, quantas == 0 ? 0 : brutos[0],
                quantas == 0 ? 0 : brutos[quantas - 1], cinco,
                result.gross() == 0 ? 0 : 100 * cinco / result.gross(),
                maiorLote, result.openAtTheEnd(),
                cut.closeAt(cut.size() - 1) - cut.closeAt(0));

        // A CAUDA VEM DE ONDE? Duas hipoteses com remedios diferentes:
        //   se vem do TAMANHO DA POSICAO, limitar o teto ou dimensionar por
        //     volatilidade ataca a dependencia direto;
        //   se vem do TAMANHO DO DIA, o bruto POR CONTRATO fica igualmente
        //     torto, e nenhum ajuste de lote muda a forma -- so a escala.
        double[] porContrato = new double[quantas];
        double somaContratos = 0;
        double somaBrutos = 0;
        double somaProduto = 0;
        double somaC2 = 0;
        double somaB2 = 0;
        int j = 0;

        for (Trade trade : result.trades()) {
            double contratos = Math.max(1, trade.contracts());

            porContrato[j++] = trade.gross() / contratos;

            somaContratos += contratos;
            somaBrutos += trade.gross();
            somaProduto += contratos * trade.gross();
            somaC2 += contratos * contratos;
            somaB2 += trade.gross() * trade.gross();
        }

        double mc = somaContratos / quantas;
        double mb = somaBrutos / quantas;
        double correl = (somaProduto / quantas - mc * mb)
                / (Math.sqrt(Math.max(1e-9, somaC2 / quantas - mc * mc))
                        * Math.sqrt(Math.max(1e-9, somaB2 / quantas - mb * mb)));

        double[] ordenado = porContrato.clone();

        java.util.Arrays.sort(ordenado);

        double medianaPC = quantas % 2 == 1 ? ordenado[quantas / 2]
                : (ordenado[quantas / 2 - 1] + ordenado[quantas / 2]) / 2;
        double somaPC = 0;
        double cincoPC = 0;

        for (int i = 0; i < quantas; i++) {
            somaPC += ordenado[i];

            if (i >= quantas - 5) {
                cincoPC += ordenado[i];
            }
        }

        // E quantos contratos tinham as cinco maiores POR BRUTO.
        double[][] paresContratos = new double[quantas][2];
        int k = 0;

        for (Trade trade : result.trades()) {
            paresContratos[k][0] = trade.gross();
            paresContratos[k][1] = trade.contracts();
            k++;
        }

        java.util.Arrays.sort(paresContratos, (a, b) -> Double.compare(a[0], b[0]));

        StringBuilder cincoMaiores = new StringBuilder();

        for (int i = Math.max(0, quantas - 5); i < quantas; i++) {
            cincoMaiores.append(String.format(Locale.ROOT, "%.0f pts/%dc  ",
                    paresContratos[i][0], (int) paresContratos[i][1]));
        }

        System.out.printf(Locale.ROOT,
                "MEDIDA %s POR CONTRATO media=%.1f mediana=%.1f top5=%.0f (%.0f%% da soma) "
                + "correl(contratos,bruto)=%.2f contratos medios=%.1f%n"
                + "MEDIDA %s CINCO MAIORES: %s%n",
                nome, somaPC / quantas, medianaPC, cincoPC,
                somaPC == 0 ? 0 : 100 * cincoPC / somaPC, correl, mc,
                nome, cincoMaiores);
    }
}
