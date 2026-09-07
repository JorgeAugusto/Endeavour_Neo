# L2 — tempo, lookahead e escala

**A pergunta:** algum número desenhado ou medido sabe de algo que o mercado
ainda não disse?

**Resposta curta:** não. Não achei uma única leitura de preço futuro no
`endeavour_neo`. Os dois achados ALTA são de **fuso**, não de lookahead — e um
deles reproduz, no eixo do tempo, exatamente o erro que este projeto já pagou
para descobrir (a semana começando na quinta).

## Como varri

Rodei os padrões pedidos sobre `src/main` (169 arquivos, 38.737 linhas), li
±40 linhas em volta de cada ocorrência suspeita e não abri nenhum arquivo
inteiro.

| padrão | ocorrências | lidas | veredito |
|---|---|---|---|
| `size() *- *1` | 28 | 13 (as do caminho de dados) | 12 seguras, 1 é a própria guarda |
| `At\([^)]*\+ *1\)` (leitura adiantada de série) | 5 | 5 | 1 real (`RecordedTicks:126`), refutada; 4 são a guarda do `OwnScale` |
| `\[(i\|bar\|index)\s*\+\s*1\]` | 3 | 3 | soma de prefixos, varredura de bytes de CSV — nenhuma é série de mercado |
| `systemDefault\|atZone\|ofEpochMilli\|toLocalDate` | 43 (main) | 43 | 2 novos, o resto já visto ou consistente |
| `currentTimeMillis\|Instant.now\|LocalDate.now` | 8 | 8 | 1 BAIXA, 7 são valor inicial de campo de diálogo |
| `shift\|displac\|desloc` | 22 | 22 | o deslocamento existe em **um** indicador só |
| `OwnScale\|ownPeriod\|indexOfClosed\|isInterpolated` | 61 | 61 | limpo, e é o melhor código desta pergunta no repositório |
| `bucketOf\|fold(\|apply(` | 10 chamadas de `apply` em `main` | 10 | **todas** sem fuso — vira o L2-1 |
| divisão de carimbo (`60_000`, `1_440`, `toEpochDay`) | 25 | 25 | 1 novo (`ChartCanvas:1896`) |
| loops para trás (`i--`) | 3 | 3 | todos varrem passado, nenhum futuro |
| `Backtest\|Engine\|Strategy` | **0** | — | não existe motor de backtest no neo ainda |

---

## Achados ALTA

### L2-1 — O único caminho de dobra que a aplicação usa não aceita fuso, e por isso nunca usa o do mercado

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Aggregation.java:55`,
com `domain/market/Timeframe.java:140-149`

**Trecho:**

```java
// Aggregation.java:55 -- a interface inteira, e não há sobrecarga com fuso
    PriceSeries apply(PriceSeries source);
```

```java
// Timeframe.java:140-149
    /** @return the zone this aggregation uses when nothing else is said */
    public static ZoneId defaultZone() {
        return ZoneId.systemDefault();
    }
...
    @Override
    public PriceSeries apply(PriceSeries source) {
        return apply(source, defaultZone());
    }
```

**Problema.** `Timeframe` tem um `apply(source, zone)` correto, e `bucketOf`
carrega toda a regra de calendário (D1 pelo dia local, W1 voltando à segunda
local, M1 por ano+mês). Mas a **interface** `Aggregation` — que é o tipo por
onde os indicadores e o gráfico enxergam a dobra — só declara `apply(source)`.
Nenhum chamador pode passar um fuso porque não existe assinatura para isso.
Levantei todos os chamadores de `.apply(` em `src/main`:

| chamador | o que dobra |
|---|---|
| `ui/chart/ChartCanvas.java:1200` | a escala do próprio gráfico |
| `ui/chart/ChartCanvas.java:1287` | idem, no caminho do replay |
| `ui/chart/overlay/MovingAverage.java:346` | média em escala maior |
| `ui/chart/overlay/BollingerBands.java:383` | bandas em escala maior |
| `ui/chart/study/rsi/RelativeStrength.java:342` | RSI em escala maior |
| `ui/chart/study/stochastic/SlowStochastic.java:490` | estocástico em escala maior |

Os seis passam pelo `apply(source)` de um argumento, ou seja, pelo fuso da
máquina. O `apply(source, zone)` de dois argumentos **só é chamado de testes** —
`TimeframeTest:117, 136, 156, 182, 198`, todos com
`ZoneId.of("America/Sao_Paulo")` (`TimeframeTest:46`).

**Consequência.** O teste `theZoneMatters` prova um comportamento que a
aplicação nunca executa: ele fixa São Paulo, o aplicativo usa a máquina. Numa
máquina em UTC, `D1` corta o dia às 21:00 de São Paulo — o pregão de terça vira
a cauda do balde de segunda — e `W1` volta à segunda **UTC**, que é domingo
21:00 local. Um indicador de escala maior sobre a série de 1m sai carimbado numa
grade que não é a do pregão, e a linha desenhada muda de valor sem que nada na
tela diga por quê. É a mesma família de erro que a memória do projeto registra
como já medida e cara (`agregacao-por-fuso`: W1 de quinta a quarta, D1 com a
data do dia anterior). Não é leitura do futuro; é carimbo errado no caminho de
agregação.

**Correção.** Duas partes, e a segunda é a que fecha o buraco:
(a) declarar o fuso do pregão em um lugar só — uma constante
`Market.ZONE = ZoneId.of("America/Sao_Paulo")` — em vez de `systemDefault()`
espalhado; (b) dar a `Aggregation` um `default PriceSeries apply(PriceSeries
source, ZoneId zone) { return apply(source); }` e sobrescrevê-lo em
`Timeframe`, para que os seis chamadores acima **possam** passar o fuso. Sem
(b), (a) não tem por onde entrar no caminho dos indicadores.

**Tentei refutar:** (1) procurei um `America/Sao_Paulo` em `src/main` — não há
nenhum; as três únicas ocorrências no repositório estão em testes
(`SegmentedSeriesTest:40`, `TimeframeTest:46,55,100`). (2) Procurei uma
sobrecarga com fuso em `Aggregation` — a interface tem exatamente um método
abstrato e um `default label()`; o javadoc diz explicitamente que ela é mantida
usável como lambda, o que é a razão pela qual ninguém acrescentou o parâmetro.
(3) Procurei se algum chamador desviava para `Timeframe.fold(bars, zone)`
diretamente — sim, um: `ui/replay/ReplaySession.java:410-413`, e ele passa
`ZoneId.systemDefault()` de qualquer forma. (4) Verifiquei se a área A1 já tinha
reportado isto: ela reportou que "`SegmentedSeries`, `Sessions` e `Timeframe`
recebem `ZoneId`" (`a1-series.md:575-577`) — ou seja, contou `Timeframe` como
**parametrizado**, que é verdade para o método e falso para o caminho que a
aplicação percorre. Sobreviveu.

---

### L2-2 — O eixo do tempo agrupa epoch millis por divisão, sem fuso nenhum: a semana do gráfico começa na quinta

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartCanvas.java:1896`

**Trecho:**

```java
        for (int i = viewport.firstBar(); i < viewport.lastBar() && i < series.size(); i++) {
            ZonedDateTime time = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone);
            long bucket = series.timeAt(i) / (step * 60_000L);
```

com a lista de passos em `ChartCanvas.java:147-149`:

```java
    private static final int[] TIME_STEPS = {
            1, 2, 5, 10, 15, 30, 60, 120, 180, 240, 360, 720,
            1_440, 2_880, 10_080};
```

**Problema.** A linha 1895 converte com o fuso (`zone`, linha 1886) para
**escrever** a etiqueta; a linha 1896 decide **onde** a etiqueta e a divisão
caem dividindo o epoch cru. Divisão de epoch millis agrupa em UTC. Este é o
único ponto do repositório inteiro onde um instante é agrupado sem fuso — nem o
da máquina, nem o do mercado.

O domínio já sabe que isso é errado e diz por quê, a três arquivos de distância
(`domain/market/Timeframe.java:297-302`):

```java
        if (minutes == WEEK) {
            // Back to the local Monday. Not epochDay / 7, which starts weeks on
            // a Thursday because 1970-01-01 was one.
```

`10_080 * 60_000` é uma semana em millis, e `epochMillis / semana` é
literalmente `epochDay / 7`. O eixo faz o que o comentário do domínio proíbe.

**Consequência.** Medida por passo, com o pregão em UTC−3:

| passo | fronteira em UTC | onde cai em São Paulo | dano |
|---|---|---|---|
| 1–60, 180 | múltiplos exatos | 09:00, 09:15, 14:00, 14:30 | nenhum |
| 120, 240, 360, 720 | deslocada 180 min mod passo | ainda em hora cheia (09:00, 13:00, 15:00…) | cosmético |
| **1.440 (dia)** | 00:00 UTC | **21:00**, dentro do intervalo entre pregões | inofensivo por acidente — o mercado está fechado |
| **2.880 (2 dias)** | dias de epoch pares | paridade arbitrária | a etiqueta pula um dia sim, um não, sem relação com o calendário |
| **10.080 (semana)** | quinta 00:00 UTC | quarta 21:00 | **a divisão semanal do eixo marca quintas-feiras** |

Nas séries longas — e a base vai de 2020 a hoje — o eixo escolhe 1.440, 2.880 e
10.080 exatamente onde o leitor está procurando a fronteira de semana. E o
javadoc do método (`ChartCanvas.java:1858-1862`) promete o contrário: *"which is
what makes them fall on 14:00 and 14:30 rather than on whatever bar happens to
be there."*

Não é leitura do futuro: é carimbo posto no lugar errado, na única leitura que o
usuário tem para datar uma barra.

**Correção.** Trocar a divisão pela chave que o domínio já produz —
`Timeframe.bucketOf(series.timeAt(i), zone)` para o `Timeframe` do passo, ou, se
o `bucketOf` continuar pacote-privado, derivar o balde do `ZonedDateTime time`
que a linha 1895 **já construiu** (`time.toLocalDate().toEpochDay()`,
`time.toLocalTime().toSecondOfDay() / 60 / step`). O objeto já está na mão; a
linha 1896 é mais barata e mais errada do que a alternativa. Cuidado: corrigir
isto sozinho **não** basta se o A1-1 continuar de pé, porque acima de 1.440 o
`bucketOf` colapsa.

**Tentei refutar:** (1) Verifiquei se `paintDayBand` — a faixa logo abaixo —
compensa: não, ela usa o fuso corretamente (`ChartCanvas.java:1962, 1981`), o
que significa que a faixa e a divisão do eixo **discordam entre si** na mesma
pintura. (2) Verifiquei se o ramo `newDay` salva o caso do dia: `newDay` compara
`time.toLocalDate()` e é correto, mas ele desenha a *virada de dia*, não a
divisão de período; a divisão continua vindo de `bucket`. (3) Verifiquei se
`axisSpeaksInDays` desliga os passos grandes: `ChartCanvas.java:2042` só troca o
formato da etiqueta quando o intervalo passa de dois dias — não muda o balde.
(4) Procurei um teste: nenhum teste em `MeasurementTest`, `ChartCanvasTest` ou
`a8b` desenha o eixo com passo de semana. (5) Conferi o índice das nove áreas:
a A3 lista `paintTimeAxis` na linha 1894 — só pelo custo, *"um `ZonedDateTime`
por barra"* (`a3-chartcanvas.md:630`); ninguém olhou a linha seguinte.
Sobreviveu inteiro.

---

## Achados MÉDIA

### L2-3 — Dentro de um mesmo quadro do replay há dois "agoras": o renko anda por tempo de negócio, o candle anda por contagem de ticks

**Onde:** `domain/market/ReplaySeries.java:180 e :302-306` contra
`ui/chart/ChartCanvas.java:1264` e `domain/market/TickBars.java:160-177`

**Trecho:**

```java
// ReplaySeries.java:180 -- o cursor consome tempo em fatias IGUAIS
            long perPrice = Math.max(1L, barMillis / path.length);
```

```java
// ReplaySeries.java:302-306 -- e o relógio é a inversa disso
        if (path == null || path.length <= 1) {
            return start;
        }

        return start + (long) ((double) cursor / path.length * barMillis);
```

```java
// ChartCanvas.java:1264 -- o renko é avançado por esse relógio
            growing.advance(day, now + 1);
```

```java
// TickBars.java:168 -- e lá dentro o corte é por tempo REAL do negócio
            if (timeAt(middle) < when) {
```

**Problema.** O candle em formação anda **por índice**: `cursor` é o k-ésimo
preço de `path`, e o relógio o converte em tempo assumindo que os negócios
chegam espaçados por igual dentro do minuto. O renko de ticks, alimentado pelo
mesmo relógio, corta por **tempo verdadeiro do negócio** (`TickBars.countUntil`,
busca binária sobre o carimbo real). `path` e `TickBars` percorrem exatamente o
mesmo conjunto de negócios do mesmo minuto — `RecordedTicks.pathFor:87-89` e
`TickBars.isTrade:102` usam o mesmo filtro `hasLast(i) && lastAt(i) > 0` — então
os dois painéis estão indexando a mesma lista por réguas diferentes.

Onde os negócios não chegam uniformes — a abertura, o leilão, um estouro — as
duas réguas divergem. Num minuto de 1.000 negócios em que 900 saem nos primeiros
5 segundos: em `cursor = 450` (45% do caminho) o relógio diz `start + 27 s`, e
`countUntil(start + 27 s)` devolve ~900. O renko dobrou 900 negócios enquanto o
candle mostrou 450 — e a máxima/mínima do minuto já está toda no renko enquanto
o candle ainda está na metade. Com os negócios concentrados no fim do minuto, o
erro inverte e o renko fica atrasado.

**Consequência.** É lookahead **dentro de uma barra**, não além dela — o renko
nunca passa do minuto corrente, e a A2 já provou isso (`a2-renko-ticks.md:889`).
Mas os dois painéis da mesma janela discordam sobre o que já aconteceu, e é
justamente na abertura — onde a distribuição de negócios é mais torta — que o
replay existe para ser usado. Quem decide olhando o renko decide com preços que
o candle ao lado ainda não imprimiu.

**Correção.** Fazer o `cursor` andar pelo carimbo do negócio em vez de pela
posição: `RecordedTicks.pathFor` já lê `ticks.timeAt(i)` para achar a fatia
(`RecordedTicks.java:87`) e joga fora o tempo, guardando só o preço. Devolver um
`long[] when` junto do `double[] path` e fazer `advanceMarketTime` avançar
enquanto `when[cursor] <= start + decorrido` alinha as duas réguas — e faz
`clock()` virar `when[cursor]`, que também mata o A2-7 (o relógio andando para
trás na virada da barra) porque deixa de existir a conversão que o produz.

**Tentei refutar:** (1) Verifiquei se o renko e o candle leem conjuntos
diferentes de negócios, o que tornaria a comparação sem sentido — não: mesmo
filtro, mesmo arquivo de sessão, e `pathFor` carrega `library.at(dayOf(from))`
(`RecordedTicks.java:77`), a mesma sessão que `TickRenko.advance` carrega em
`library.load(day)`. (2) Procurei um teste que fixasse a relação — `TickRenkoTest
.nothingFromTheFuture` prova o limite superior (nada além do relógio) e nada
sobre o alinhamento com o candle. (3) Procurei se a A2 já tinha reportado: ela
tem o A2-7 (`clock()` anda para trás) e a tabela de LIMPO em `a2-renko-ticks.md:
888-889`, que verifica cada lado **contra si mesmo** e nunca um contra o outro.
(4) Considerei se a distribuição real do WIN é uniforme o bastante para o erro
ser desprezível — a própria javadoc de `TickBars` cita 5,8 milhões de negócios
numa sessão de 566 minutos, o que é ~10 mil por minuto na média contra os poucos
de um minuto morto; a variação intraminuto de um mercado com leilão de abertura
não é uniforme. Sobreviveu.

---

## Achados BAIXA

### L2-4 — A série de demonstração se carimba pelo relógio de parede

**Onde:** `ui/chart/RandomWalkSeries.java:56-58`, usada em `ui/shell/MainWindow.java:369`

**Trecho:**

```java
    public RandomWalkSeries(int bars, double start) {
        this(bars, start, System.currentTimeMillis() - bars * 60_000L, 20_260_902L);
    }
```

**Problema.** A semente dos preços é fixa (`20_260_902L`) — o javadoc explica
por quê, e está certo — mas o **primeiro instante** vem do relógio. As 2.000
barras são minutos corridos para trás a partir de agora, sem intervalo entre
pregões. Duas execuções do aplicativo produzem a mesma caminhada de preços com
carimbos diferentes, e quantos "dias" ela contém depende da hora em que o
aplicativo abriu.

**Consequência.** Qualquer coisa medida sobre o gráfico de demonstração —
fronteira de dia, `Sessions.of`, a faixa de dias do eixo, e agora também o balde
do L2-2 — não repete entre execuções. É a série que aparece quando o carregador
real falha (`ui/shell/MainWindow.java:369`, e a A7b mostra que ela é o `return`
depois do `catch`), então ela chega à tela em produção, não só em demonstração.

**Correção.** Passar um instante fixo, como os testes já fazem
(`RenkoContinuedTest:106` usa `0L`), ou o começo do dia corrente. Não é urgente:
a classe está marcada para morrer (`RandomWalkSeries.java:36`, já no índice das
áreas).

**Tentei refutar:** verifiquei se os testes usam o construtor de dois
argumentos — não usam nenhum; `RenkoContinuedTest` usa sempre o de quatro com
`firstBar = 0L`, e o de `a8b-testes-interface.md:463` usa
`RandomWalkSeries(200, 100.0)` mas não pinta o eixo. Então o defeito só aparece
na tela, o que é exatamente por que ninguém tropeçou nele.

---

## O balanço do fuso

43 conversões de epoch em `src/main`. **Nenhuma usa o fuso do mercado, porque o
fuso do mercado não existe declarado no código de produção** (`grep
"America/"` em `src/main`: zero). O padrão é uniforme — sempre
`systemDefault()` — e essa uniformidade é o que salva quase tudo: escrita e
leitura se cancelam, e numa máquina em São Paulo o resultado é certo.

| onde | o que decide | perigoso? |
|---|---|---|
| `Timeframe.java:143` (`defaultZone`) | **a dobra de escala inteira** | **SIM — L2-1.** É o único caminho que os seis chamadores de `apply` têm |
| `ChartCanvas.java:1896` | balde do eixo do tempo | **SIM — L2-2.** Nem máquina nem mercado: UTC puro |
| `RecordedTicks.java:52, 157` | de que dia são os ticks | já visto — **A1-5** |
| `TickFile.java:394` / `TapeFile.java:400` | meia-noite de onde os ticks contam | já visto — **A1-5**; se anula com a leitura, só quebra entre máquinas |
| `ReplaySession.java:362, 411, 646` | de que dia é cada barra do replay | já visto — **A6-19** |
| `SegmentedSeries.java:75` + `MainWindow.java:444` | qual barra entra no recorte de datas | não — o `null` cai no padrão e todos passam `systemDefault()`; consistente com o resto |
| `domain/market/Sessions.java:59, 70, 74` | quais pregões a série tem | não — mesma fronteira do resto (`a4-layout-eixos.md:15` já conferiu contra a cópia em `ui`) |
| `ui/chart/Sessions.java:125` | fechamento do pregão anterior | não — idem |
| `ChartCanvas.java:643, 1240, 1886, 1962, 1981, 2380` | etiqueta, faixa de dias, dia do relógio | não — só exibição, e a faixa está certa |
| `RenkoSource.java:89, 97` | se a série toda tem ticks exportados | não — compara `LocalDate` contra `LocalDate` derivadas do mesmo jeito |
| `SeriesSummary.java:69, 122` / `BarReadout.java:93` / `ChartHolder.java:350` / `SeriesWindow.java:76` | exibição | não |
| `Settings.java:268` (`LocalDateTime.now()`) | comentário de cabeçalho de arquivo | não — já anotado em `a7a-platform.md:1162` |

**O ponto que vale reter:** a dívida é conhecida (A1-5, A6-19, e o diagnóstico
de `a1-series.md:575-579`), mas o que ninguém viu é que ela **não tem como ser
paga** enquanto `Aggregation` não aceitar um fuso. Declarar
`Market.ZONE = America/Sao_Paulo` amanhã corrige `SegmentedSeries`, `Sessions` e
`RecordedTicks`, e não toca em um único indicador de escala maior.

---

## O balanço do índice adiantado

### `size() - 1` — 28 ocorrências, 13 no caminho de dados

| onde | uso | veredito |
|---|---|---|
| `OwnScale.java:116` | `while (closed + 1 < coarse.size() - 1 ...)` | **é a guarda.** Limita `closed` a `size-2`, que é o que impede a barra grossa em formação de ser lida. Load-bearing |
| `OwnScale.java:77` | limite alto da busca binária | seguro — o teste `middle + 1 < coarse.size()` corta em `size-2` de qualquer forma |
| `ReplaySeries.java:300` | `day.timeAt(size() - 1)` | seguro — `size()` é `completed + forming`, **não** o fim do arquivo. Regra 2 cumprida |
| `Renko.java:493, 511` | preço e carimbo do tijolo em formação | seguro — `source` é a série que o chamador entrega; no replay é a `ReplaySeries`, cujo `size()` é o presente |
| `ChartCanvas.java:1235` | `source.timeAt(source.size() - 1)` | seguro — é o **ramo de fallback**; o ramo do replay (1231-1233) devolve `live.clock()` antes de chegar aqui |
| `ChartCanvas.java:1357` | grampeia `lastBar` | seguro — `series` é a série que está na tela, replay incluído |
| `ui/chart/Sessions.java:84` | `changeOnDay` lê a última barra | seguro — no replay é a barra em formação, que é o presente |
| `Viewport.java:113` | grampeia `firstBar` | seguro |
| `RecordedTicks.java:139`, `SeriesMerge.java:101` | limite alto de busca binária | seguros |
| `SeriesSummary.java:71` | data da última barra, para exibir | seguro |
| `PriceSeries.java:32` | javadoc | — |
| `LayoutBar`, `Measurement:134`, `Reordering`, `SegmentDialog`, `SeriesMap`, `SeriesWindow`, `Navigator`, `StatusBar`, `StudyPane` (15 ocorrências) | índices de listas de interface | nenhuma toca série de mercado |

### Leituras à direita — 8 ocorrências, 1 real

| onde | trecho | veredito |
|---|---|---|
| `RecordedTicks.java:126` | `return series.timeAt(index + 1);` | **a única leitura adiantada de uma série de mercado no repositório — e é segura.** Lê um *carimbo*, não um preço, para saber onde a barra termina. Tentei quebrar pelo caso ruim: no replay a série passada é a `ConcatSeries` de todos os dias (`ReplaySession.java:253`), então na última barra de um pregão o `index + 1` devolve a abertura do dia **seguinte**. Isso não vaza nada, porque a linha 77 já fixou `ticks = library.at(dayOf(from))` — a varredura da linha 87 esbarra no fim do arquivo daquele dia antes de chegar ao `to`. Refutado |
| `OwnScale.java:86, 156` | `coarse.timeAt(middle + 1)`, `coarse.timeAt(closed + 1)` | são a **definição** de "a barra grossa fechou": a de índice `k` só está fechada quando a `k+1` começou. Ler `timeAt(k+1)` é ler o passado, não o futuro |
| `ConcatSeries.java:77` | `starts[i + 1] = starts[i] + ...` | soma de prefixos sobre tamanhos, não sobre preços |
| `ProfitTrades.java:315` | `row[i] == ' ' && row[i + 1] == '-'` | varredura de bytes de uma linha de CSV |
| `SeriesCatalog.java:252`, `BollingerBands.java:584` | `substring(equals + 1)` | texto |

---

## Já visto pelas áreas

| id | o quê | onde encostei |
|---|---|---|
| **A1-1** | `Timeframe:307` — escala > 1.440 min colapsa em meia-noite | confirmei a forma; procurei irmãos e achei **um**, o L2-2 |
| **A5-3** | `MovingAverage:279-283` — deslocamento negativo lê à direita | confirmado: `int at = bar - shift;` com `shift` aceitando −500 (`MovingAverageDialog:114`) |
| **A8b-4** | o deslocamento só é testado para a frente | — |
| **A5-1** | `OwnScale.smooth` nunca interpolava | **o código no disco hoje já está corrigido** e traz a explicação completa em `OwnScale.java:143-155`; a rampa agora sai de `timeAt(closed + 1)`. Vale conferir se a área leu uma versão anterior ou se o achado é histórico |
| **A1-5 / A6-19** | fuso do sistema nos ticks e no replay | consolidados na tabela de fuso acima |
| **A2-7** | `ReplaySeries.clock()` anda para trás na virada de barra | é o mesmo mecanismo do L2-3, visto por outro lado; a correção que proponho no L2-3 mata os dois |
| **A8b-1/2/3, A8a-2, A8a-4** | os testes que não pegam a leitura do futuro | não repeti |

---

## O que está LIMPO — e como conferi

**1. `OwnScale` cumpre a regra 1, e é o melhor código do repositório nesta
pergunta.** `indexOfClosed` (`:74-93`) acha o maior `k` com
`coarse.timeAt(k+1) <= when`; como o teste exige `middle + 1 < coarse.size()`,
`k` nunca passa de `size-2` — a barra grossa em formação é inalcançável.
`map` (`:105-127`) chega ao mesmo `k` com um ponteiro corrido em vez de busca, e
a condição `closed + 1 < coarse.size() - 1` dá o mesmo teto. Fiz a aritmética
das duas independentemente e elas coincidem em todo índice. Um indicador de 15m
lido às 09:05 devolve o valor da barra fechada às 09:00 — nunca a que contém
09:05.

**2. `OwnScale.smooth` inclina para trás, não para a frente.** A rampa vai de
`slow[closed-1]` a `slow[closed]` no intervalo `[timeAt(closed+1),
timeAt(closed+2))`. Em `timeAt(closed+1)` — o instante em que `slow[closed]`
passou a ser conhecível — a linha ainda vale `slow[closed-1]`; ela só *chega* a
`slow[closed]` no fim da barra seguinte. Ou seja: interpolar deixa a linha
**mais atrasada**, nunca mais adiantada. Tentei quebrar com a última barra
(`ends = shut + (shut - timeAt(closed))`, largura estimada pela anterior) e com
`span <= 0` (retorna sem escrever); os dois estão guardados.

**3. Os irmãos do A5-3 não existem.** O pedido era procurar deslocamento
negativo nas bandas, no RSI e no estocástico. `grep -i "shift|displac|desloc"`
em `src/main` devolve 22 linhas e **todas** são de `MovingAverage` ou do seu
diálogo, mais dois `isShiftDown()` de mouse e três comentários de renko. Conferi
os três indicadores um a um: `BollingerBands.valueAt` (`:348-359`) indexa por
`bar` cru; `RelativeStrength` e `SlowStochastic` não têm campo `shift` nem
controle no diálogo. E `BollingerBands.computeOver` (`:472-476`) constrói uma
`MovingAverage` nova a cada cálculo, com `shift` no valor de fábrica (0) — então
nem por herança o deslocamento entra nas bandas.

**4. Nenhuma média, banda ou oscilador inclui barra à direita.** Li os seis
laços de cálculo: `MovingAverage.arithmetic/exponential/weighted`
(`:395-440`, todos `i - back` ou `values[i-1]`), `BollingerBands.computeOver`
(`:430-457`, `for (int back = i - period + 1; back <= i; back++)`),
`RelativeStrength.computeOver` (`:365-390`, `closeAt(i) - closeAt(i-1)`),
`SlowStochastic.computeOver` (`:509-541`, mesma janela para trás; o
`smooth` (`:553-600`) só empilha valores já emitidos). Todos aquecem com NaN em
vez de janela parcial, o que é o oposto do erro procurado. Os únicos três laços
para trás do repositório (`ProfitTrades:105`, `BollingerBands:526`,
`Sessions:66`) varrem passado.

**5. `ReplaySeries` não deixa ler além do presente, e diz por quê.** Todo
acessor passa por `check(index)` (`:369-377`), que lança
`"bar N has not happened yet"`; o ramo `forming` também passa, porque
`forming(index)` chama `check` antes de comparar. `openAt` devolve o aberto
**guardado** (fixo no instante em que a barra começou) e só `high`/`low`/`close`/
`volume` se movem. `size()` é `completed + (path == null ? 0 : 1)` e `completed`
é grampeado a `[origin, day.size()]`. A regra 2 está cumprida por construção,
não por convenção.

**6. `TickBars` e `TickRenko` cortam pelo tempo, estritamente.**
`TickBars.countUntil` (`:160-177`) é busca binária por `timeAt(middle) < when` —
estritamente anterior; `ChartCanvas` chama `advance(day, now + 1)`
(`ChartCanvas:1264`) para incluir o instante corrente e nada além. `TickBars` é
uma barra por **negócio**, com `timeAt` = o carimbo do próprio negócio
(`TickBars:112-114`), então não há balde onde um preço futuro pudesse se
esconder. O ressalva está no L2-3, que é sobre *qual* `now` chega aqui.

**7. Renko cumpre a regra 5.** `gridUnder` (`Renko.java:297-299`) é
`Math.floor(price / brick) * brick` — grade absoluta ancorada no zero, e o
javadoc (`:273-295`) registra a medição que provou que ancorar na abertura
estava errado (nenhuma de 1.651 aberturas caía na grade). `grep` por `LocalDate`
e `Zone` em `Renko.java`: **zero ocorrências** — não há como o renko reiniciar
por pregão, porque ele não sabe o que é um pregão.

**8. Nada compara com `now()` no caminho de dados.** As 8 ocorrências de
relógio de parede são: um comentário de cabeçalho de arquivo (`Settings:268`), o
valor inicial de três campos de data de diálogo (`DatePicker:88,164`,
`ReplayPanel:55,57,215`), um `LocalDate.now()` como fallback de lista vazia
(`SeriesWindow:384`) e o L2-4. Nenhuma decide o que uma série contém ou o que um
indicador vale.

**9. Não há motor de backtest no neo.** `grep -l "Backtest|Engine|Strategy"` em
`src/main`: nenhum arquivo. A pergunta "o 'agora' do relógio contra o 'agora' da
série num backtest" não tem alvo aqui **ainda** — e é exatamente por isso que o
L2-1 vale ser corrigido antes de o motor chegar: ele vai chamar
`Aggregation.apply` como todo o resto.

---

## O que eu faria primeiro

O L2-2 é meia hora: o `ZonedDateTime` correto já está construído uma linha
acima. O L2-1 é a que muda o desenho — um `default` em `Aggregation` e uma
constante de fuso do mercado — e é a única correção nesta lente que precisa ser
feita **antes** de haver mais código pendurado no `apply` de um argumento.
