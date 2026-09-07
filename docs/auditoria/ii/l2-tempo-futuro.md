# L2 — tempo, fuso e leitura do futuro

Lente transversal da auditoria II. Não releio arquivos: parto de `grep` e leio
±40 linhas em volta de cada ocorrência. Reporto só o que as dez áreas não
viram — o índice de `00-achados.md` (215 achados) foi lido inteiro antes.

## O que foi varrido

| padrão | ocorrências |
|---|---|
| `systemDefault \| ZoneId. \| ZoneOffset \| defaultZone \| atZone \| toLocalDate \| atStartOfDay \| toInstant \| ofInstant \| ofEpoch` em `src/main` | 68 |
| `currentTimeMillis \| nanoTime \| Instant.now \| LocalDate.now \| LocalDateTime.now \| new Date(` | 9 |
| `size() - 1 \| lastBar \| .last( \| latest \| closed() \| indexOfClosed` | 53 |
| `i + 1 \| bar + 1 \| index + 1` (fora de cabeçalho de laço) | 7 |
| `countUntil \| until(` | 6 |
| `clock \| advance \| advanceMarketTime \| marketTime \| barMillis` em `ReplaySeries`/`TickRenko` | 40 |
| `OwnScale. \| aggregation() \| .apply( \| calculate( \| computeOver(` | 58 |
| `useZone \| defaultZone \| systemDefault` em `src/test` | 27 |

**241 ocorrências examinadas**, em 27 arquivos.

Nota de estado: a árvore já traz as sete ALTA da primeira leva corrigidas
(`e541da7`), entre elas **B1-1** — `domain/market/Sessions.of(series)` hoje lê
`Timeframe.defaultZone()`. Tudo abaixo foi lido **contra a árvore corrigida**, e
é precisamente o corte que aquela correção deixou aberto: o domínio passou a
responder no fuso do mercado, e **nenhum chamador da interface o acompanhou**.

---

## ALTA

### L2-1. `RenkoSource.allows` pergunta o dia no fuso da máquina, quatro métodos acima de `sessionsIn`, que pergunta no fuso do mercado

`ui/chart/RenkoSource.java:89-97`

```java
        ZoneId zone = ZoneId.systemDefault();

        // Walked once, and the calendar is only asked where the day changes. A
        // conversion per bar would be 693 thousand of them on the full series, on
        // the interface thread, for one keystroke.
        LocalDate seen = null;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();
```

`ui/chart/RenkoSource.java:122-124`

```java
        // The walk itself moved to the domain: the transport greys out the days
        // a feed cannot play using the same answer, and two copies of one walk
        // is where the second one forgets that a holiday is not a weekend.
        return new ArrayList<>(
                br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(series));
```

**Problema.** As duas metades da mesma classe percorrem a mesma série
perguntando a mesma coisa — *que dia é esta barra* — e usam calendários
diferentes. `sessionsIn` delega a `Sessions.of`, que desde `e541da7` lê
`Timeframe.defaultZone()`; `allows` continua com `ZoneId.systemDefault()`. O
comentário de `sessionsIn` diz por que a caminhada foi movida para o domínio —
*"two copies of one walk is where the second one forgets"* — e a segunda cópia
ficou onde estava, no mesmo arquivo, 33 linhas acima.

O outro lado da comparação, `library.exported()`, vem do cabeçalho dos arquivos
de tick via `LocalDate.ofEpochDay` (`TickFile.java:113`): uma data absoluta, sem
fuso. Então `allows` compara datas do fuso da máquina contra datas de arquivo.

**Consequência.** `allows` é a **tranca** de todo o caminho de renko de ticks:
`ChartCanvas.rebuildFromTicks` (linha 1163) desiste em silêncio quando ela diz
não. Com `data.zone` apontando para São Paulo numa máquina a leste de ~UTC+6, as
1.494 sessões viram ~2.900 datas locais, quase nenhuma delas exportada, e o
renko de ticks **nunca é construído** — sem mensagem, porque a desistência é o
caminho normal para quem não tem os ticks. E o pior é o outro lado: como
`sessionsIn` responde no fuso certo, a lista `days` que o `SwingWorker` dobra
(linha 1215) tem os dias certos enquanto a tranca que a autorizou olhou outros.

**Correção.** `ZoneId zone = Timeframe.defaultZone();` na linha 89. É a mesma
linha que `Sessions.of` recebeu, no arquivo que a chama.

**Tentei refutar assim.** (a) Procurei um `useZone` que a interface chamasse
para reconciliar: só existe um, em `Launcher.java:89`, e ele escreve o fuso do
mercado — o que torna a discordância certa, não improvável. (b) Verifiquei se
`exported()` fosse por acaso construído no fuso da máquina, o que faria os dois
lados baterem: não é — `TickFile.java:113` é `LocalDate.ofEpochDay(header.epochDay)`,
absoluto. (c) Procurei um teste que fixasse isso: `RenkoSourceTest.java:44`
declara `private static final ZoneId ZONE = ZoneId.systemDefault();` e monta a
fixture com ele — a mesma expressão que o produto lê, então o teste passa em
qualquer fuso e não pode falhar por este defeito (ver L2-6). (d) Verifiquei se a
máquina do autor está no fuso do mercado — está; o defeito é invisível para ele
e real para qualquer outro, que é exatamente o que o comentário do `Launcher`
diz ser a razão daquela linha existir.

---

### L2-2. Qual dia o replay "está pisando" é decidido no fuso da máquina, e é ele que separa o que se dobra inteiro do que se dobra até o relógio

`ui/chart/ChartCanvas.java:1383-1386`

```java
    private java.time.LocalDate dayOfClock() {
        return java.time.Instant.ofEpochMilli(clockNow())
                .atZone(java.time.ZoneId.systemDefault()).toLocalDate();
    }
```

`ui/chart/ChartCanvas.java:1198` e `:1215-1221`

```java
        java.time.LocalDate standing = replaying ? dayOfClock() : null;
...
                for (java.time.LocalDate each : days) {
                    if (standing != null && !each.isBefore(standing)) {
                        break;
                    }

                    built.add(each);
                }
```

**Problema.** `days` é `RenkoSource.sessionsIn(source)` (linha 1140), isto é
`Sessions.of` — **fuso do mercado**. `standing` é `dayOfClock()` — **fuso da
máquina**. A comparação `each.isBefore(standing)` põe as duas numa mesma
desigualdade. O comentário logo acima diz o que a linha decide: *"Every day
BEHIND the one the replay stands on, folded whole. The one it stands on is left
to advance(), which lays only what has printed by the replay's clock — folding
it whole would put bricks on screen for trades that have not happened yet."*

A mesma `dayOfClock()` volta em `extendBricks()` (linha 1408) para pedir a
sessão ao `TickLibrary` e para chamar `growing.advance(day, now + 1)` — e ali a
`day` é usada como **chave de arquivo de tick**, que é epoch-day absoluto.

**Consequência.** É a leitura do futuro que o próprio comentário nomeia. Num
fuso onde a data local da sessão adianta um dia — qualquer coisa a leste de
~UTC+6, e o `Launcher` existe para essa máquina — `standing` vale D+1 enquanto
`days` traz D. A condição `!each.isBefore(standing)` deixa de cortar em D, e o
dia que o replay está tocando **é dobrado inteiro**: tijolos na tela para
negócios que ainda não imprimiram, na sessão que o leitor está justamente
assistindo formar. No sentido contrário (fuso a oeste, `standing` = D−1), o
`break` acontece cedo demais e a sessão anterior fica sem tijolos para sempre —
que é o mesmo sintoma que o comentário das linhas 1191-1197 descreve como já
tendo custado *"a whole session of bricks went missing without a mark"*.

E em `extendBricks`, `growingFrom.request(day)` pede o arquivo do dia errado:
`TickRenko.advance` cai em `load()` na EDT, com 90 MB, ou não acha nada.

**Correção.** `Timeframe.defaultZone()` na linha 1385. Uma linha, e ela alinha
`dayOfClock` com `days`, com `TickFile` e com `RecordedTicks.dayOf`.

**Tentei refutar assim.** (a) Verifiquei se `standing` fosse comparado só contra
si mesmo em algum ramo: não — a única comparação é contra `days`, que vem do
domínio. (b) Verifiquei se a variável local `replaying` da linha 1187 (`playing
!= null`, que sombreia o campo — B3-14) pudesse anular o ramo: não. Quando o
campo `replaying` é verdadeiro, `sourceForBricks()` devolve `playing`, e um
`playing` nulo faz a linha 1145 retornar antes; então, passada a guarda, a local
e o campo valem o mesmo. O ramo é alcançado sempre que há replay de ticks.
(c) Verifiquei se `clockNow()` já devolvesse um instante corrigido: devolve
`ReplaySeries.clock()`, epoch-millis cru. (d) Procurei um teste: `TickRenkoOnChartTest.java:55`
e `ReplayBase.java:46` também fixam `ZONE = ZoneId.systemDefault()`.

---

### L2-3. A dobra de ticks em barras é feita, pela interface, no fuso da máquina — o defeito que `RecordedTicks.dayOf` foi reescrito para fechar, reaberto no chamador

`ui/shell/MainWindow.java:477-481`

```java
            protected PriceSeries doInBackground() {
                return br.com.jorge.reis.endeavourneo.domain.market.FoldedTicks.all(
                        SeriesCatalog.ticksOf(instrument), instrument, source,
                        java.time.ZoneId.systemDefault());
            }
```

`ui/replay/ReplaySession.java:443-444`

```java
        return br.com.jorge.reis.endeavourneo.domain.market.FoldedTicks.day(
                tickFolder, feed.instrument(), ticks.source(), day, ZoneId.systemDefault());
```

`domain/market/FoldedTicks.java:79` e `:102`

```java
     * @param zone the calendar that decides where a minute begins
...
            return Timeframe.ONE_MINUTE.fold(TickBars.of(source.read(file)), zone);
```

**Problema.** O parâmetro não é decorativo: é o calendário que decide **onde
começa cada minuto**, e é ele que `Timeframe.fold` passa a `bucketOf` e a
`startOf`. Os dois únicos chamadores de produção entregam o fuso da máquina.

`RecordedTicks.java:225-238` documenta, em cinco linhas, exatamente este
defeito no nível de baixo — *"the session inside computed its own midnight in
the machine's zone regardless... a bar was matched to a session whose ticks were
hours away from it... silently, because 'no ticks for this bar' is a normal
answer"* — e conclui *"One zone now, `Timeframe.defaultZone`, read by this and
by the session alike. Two places that must agree cannot be given two answers."*
São duas respostas outra vez, um andar acima.

**Consequência.** Um gráfico aberto sobre uma exportação de ticks sai com toda
barra carimbada num minuto que ela não cobre, e com os minutos cortados na hora
errada — 693 mil barras cujo carimbo não corresponde ao intervalo agregado. Pior
no replay: `foldedFromTicks` é a sessão que o `ReplaySeries` anima, e
`ReplaySeries` cruza esses carimbos com os instantes reais dos ticks
(`spendStamped`, `when[]` vindo de `RecordedTicks`, que usa
`Timeframe.defaultZone()`). O candle e o tick passam a andar em réguas
diferentes — que é literalmente o que `OneClockTest` existe para proibir, e o
que o comentário de `clock()` diz ter causado o congelamento do renko.

**Correção.** `Timeframe.defaultZone()` nos dois pontos de chamada. Nenhum dos
dois tem fuso próprio para oferecer: o único fuso que a aplicação conhece é o de
`data.zone`.

**Tentei refutar assim.** (a) Procurei um terceiro chamador que passasse o fuso
certo, o que faria disto inconsistência e não defeito: `FoldedTicks.all` e
`.day` só são chamados nestes dois lugares em `src/main`. (b) Verifiquei se
`FoldedTicks` tratasse `zone` nulo caindo no default — trataria, mas ninguém
passa nulo: passam `systemDefault()` explicitamente, o que **desliga** a queda.
(c) Verifiquei se `Timeframe.ONE_MINUTE.fold` ignorasse o fuso a um minuto (há
um atalho em `apply` a um minuto): não — `fold` é justamente a porta que não tem
o atalho, e `bucketOf`/`startOf` recebem o fuso passado.

---

### L2-4. O corte de um segmento e a âncora da janela são medidos no fuso da máquina, e o javadoc diz isso em voz alta em vez de corrigi-lo

`ui/shell/MainWindow.java:439-450`

```java
    /**
     * @return the instant just past the segment's last day, in the machine's zone
     *
     * <p>Exclusive, which is what a count-until wants: a segment ending on
     * 11/11/2024 includes every bar of that day.</p>
     */
    private static long endOf(br.com.jorge.reis.endeavourneo.domain.market.Segment segment) {
        return segment.to() == null
                ? Long.MAX_VALUE
                : segment.to().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli();
    }
```

`ui/shell/MainWindow.java:489-490`, `:651-652` e `ui/replay/ReplaySession.java:403-404`

```java
                            br.com.jorge.reis.endeavourneo.domain.market.SegmentedSeries.of(
                                    bars, segment, java.time.ZoneId.systemDefault()));
...
        return SegmentedSeries.of(whole, new Segment(instrument, day, day),
                ZoneId.systemDefault());
```

`domain/market/SegmentedSeries.java:75`

```java
        ZoneId at = zone == null ? ZoneId.systemDefault() : zone;
```

**Problema.** As datas de um segmento nascem de `Segmentable.sessionsIn` →
`Sessions.of(bars)` (`ui/series/Segmentable.java:145`), **fuso do mercado**. O
instante em que essas datas viram corte é calculado no **fuso da máquina**, em
quatro lugares. O javadoc de `endOf` até declara *"in the machine's zone"* — é
um comentário honesto sobre um valor errado, o que o torna pior, não melhor: ele
transforma o defeito em decisão aparente.

`endOf` alimenta `SeriesCatalog.openUntil(name, endOf(segment), ...)` (linha
524), a âncora binária do `MarketFile.countUntil`, cujo javadoc conta o que já
custou: *"a segment of 2020 to 2024 against a window starting in December 2025 —
and the chart came up blank with the console reporting a clean load"*.

**Consequência.** Deslocamento de horas entre a data que o leitor escolheu e o
corte que ele recebe: um segmento que termina em 11/11/2024 perde as últimas
horas do pregão, ou ganha as primeiras do seguinte, conforme o sinal do
deslocamento. Num fuso que troca a data, perde ou ganha um pregão inteiro. E o
mesmo deslocamento entra na âncora da janela de 100 mil barras, onde o preço de
errar é a tela em branco com o console dizendo que carregou. Em `ReplaySession.dayOf`
o efeito é direto: a sessão que o replay vai tocar é fatiada por uma meia-noite
que não é a do mercado.

**Correção.** `Timeframe.defaultZone()` nos quatro pontos, e o mesmo no fallback
de `SegmentedSeries.java:75` — o parâmetro pode continuar existindo (o domínio
não deve depender do launcher), mas a queda de `zone == null` tem de ser a do
mercado, como já é em `Sessions.java:132` e em `Timeframe.java:222`.

**Tentei refutar assim.** (a) Verifiquei se `Segment` guardasse fuso próprio, o
que faria a data auto-suficiente: guarda só `LocalDate`. (b) Verifiquei se algum
chamador passasse fuso: os três de `SegmentedSeries.of` em `src/main` passam
`systemDefault()`. (c) Verifiquei se a inclusão do "dia inteiro" salvasse o
caso: salva o arredondamento, não o fuso — `plusDays(1).atStartOfDay(máquina)`
continua sendo outra meia-noite. (d) Verifiquei se `SeriesWindow.ZONE`
(`ui/series/SeriesWindow.java:76`, também `systemDefault`) piorasse a conta: é
campo morto, nunca lido — não reporto, e B7b-14 já cobre campos sem uso ali.

---

## MÉDIA

### L2-5. O que o leitor lê da tela é carimbado no fuso da máquina; as barras que ele está lendo foram dobradas no do mercado — e num mesmo painel as duas respostas convivem

Nove pontos, todos em caminho de pintura ou de rótulo:

| arquivo:linha | o que carimba |
|---|---|
| `ui/chart/ChartCanvas.java:2183` | o eixo de tempo |
| `ui/chart/ChartCanvas.java:2267` | a faixa de dias |
| `ui/chart/ChartCanvas.java:2728` | a etiqueta sob o cursor |
| `ui/chart/ChartCanvas.java:650` | o relógio do rodapé |
| `ui/chart/BarReadout.java:93` | o título da leitura da barra |
| `ui/chart/SeriesSummary.java:69` | o primeiro e o último dia do resumo |
| `ui/chart/ChartHolder.java:353` | a variação do dia, no título da janela |
| `ui/replay/ReplaySession.java:706` | o relógio do transporte |
| `ui/replay/ReplaySession.java:722` | a hora de fechamento do transporte |

O caso mais claro é `SeriesSummary`, porque as duas respostas saem lado a lado
na mesma tabela:

`ui/chart/SeriesSummary.java:69-79`

```java
        ZoneId zone = ZoneId.systemDefault();
        LocalDate first = Instant.ofEpochMilli(series.timeAt(0)).atZone(zone).toLocalDate();
...
        rows.add(new String[]{Messages.get("summary.sessions"), count(sessionsIn(series))});
```

**Problema.** `first`/`last` no fuso da máquina, `sessionsIn` (→ `Sessions.of`)
no fuso do mercado, três linhas abaixo, no mesmo painel.

**Consequência.** Não muda cálculo: muda **o número na tela**. Um resumo que diz
"de 04/01/2021 a 02/09/2026, 2.900 sessões" quando o intervalo tem 1.494. Um
eixo cujos rótulos de dia caem no meio do pregão. Um relógio de replay que
mostra 22:00 enquanto o mercado está às 09:00. Nenhum deles reclama.

**Correção.** `Timeframe.defaultZone()` nos nove. `ChartCanvas` já importa
`ZoneId`; nos demais é um import.

**Tentei refutar assim.** (a) Considerei que carimbo de tela deva mesmo seguir a
máquina — é a leitura defensável, e ela cai por dois motivos: `data.zone` existe
justamente para dizer *em que fuso este mercado se lê*, e a mistura dentro de um
mesmo painel (`SeriesSummary`) não é defensável em leitura nenhuma. (b) Verifiquei
se `ChartHolder:353` só fosse alcançado fora do replay — é (`replayLabel != null`
retorna antes), o que limita o dano dessa linha à variação do dia, não o remove.
(c) Verifiquei se algum destes já estivesse coberto: B4-4 é sobre `RenkoSource.allows`
converter data por barra na **EDT**, e B1-14 sobre `axisBucket` duplicar
`Timeframe.bucketOf` — nenhum dos dois é o fuso.

---

### L2-6. Todo teste de interface fixa a fixture em `ZoneId.systemDefault()` — a mesma expressão que o produto lê, então nenhum deles pode falhar por fuso

`ui/chart/RenkoSourceTest.java:44`, `ui/chart/TimeAxisTest.java:46`,
`ui/chart/SeriesSummaryTest.java:40`, `ui/chart/TickRenkoOnChartTest.java:55`,
`ui/chart/OverlayLegendTest.java:47`, `ui/chart/overlay/OwnPeriodTest.java:42`,
`ui/chart/overlay/BollingerBandsTest.java:38`, `ui/replay/ReplayBase.java:46`

```java
    private static final ZoneId ZONE = ZoneId.systemDefault();
```

`ui/chart/RenkoSourceTest.java:61`

```java
            long open = day.atStartOfDay(ZONE).toInstant().toEpochMilli() + 9 * 3_600_000L;
```

**Problema.** A fixture constrói os instantes com `systemDefault()` e o produto
os relê com `systemDefault()`. As duas pontas se movem juntas: o teste dá a
mesma resposta em São Paulo, em Brisbane e em UTC. É licença falsa exatamente
para os quatro achados ALTA acima.

O contraste está no domínio, e é instrutivo: `SessionsTest.theOneArgumentOverloadUsesTheMarketZone`
(linha 293) e `TickLibraryTest.theTicksAndTheBarsShareOneZone` (linha 420)
**trocam** `Timeframe.useZone` para Brisbane e Kiritimati e verificam que a
diferença apareceu — e a de `Sessions` ainda assere que a fixture prova algo
(*"the session did not cross local midnight, so this fixture proves nothing"*).
Nenhum teste sob `ui/` faz isso, e é sob `ui/` que estão os dezoito
`systemDefault()` restantes.

**Consequência.** A suíte cobre o fuso onde ele já foi corrigido e não cobre
onde ele não foi. Corrigir L2-1 a L2-4 não vai quebrar nada — e quebrá-los de
novo também não.

**Correção.** Um teste no espírito do de `Sessions`: fixar `Timeframe.useZone`
num fuso a leste, montar uma sessão do WIN e exigir que `RenkoSource.allows`,
`ChartCanvas.dayOfClock` e o corte de `SegmentedSeries` continuem falando de um
dia só. Onde a fixture tiver de escolher um fuso, que seja
`Timeframe.defaultZone()`, não `systemDefault()` — assim ela se move com a
correção e não com o defeito.

**Tentei refutar assim.** (a) Verifiquei se algum teste de `ui` chamasse
`useZone`: `grep -rn "useZone" src/test` devolve só `SessionsTest`,
`TickLibraryTest` e `TimeframeTest`, os três no domínio. (b) Verifiquei se os
achados B8b já cobrissem isto: cobrem headless, isolamento de configurações,
laços vacuáveis e casamento de strings — nenhum é sobre fuso. (c) Verifiquei se
`ZONE` fosse usado só para formatar saída em algum deles: em `RenkoSourceTest`
ele constrói os instantes das barras, que é o dado sobre o qual o produto
decide.

---

### L2-7. O transporte do replay decide "ontem" pelo calendário da máquina, e a lista de dias que ele oferece vem do calendário do mercado

`ui/replay/ReplayPanel.java:55-57` e `:226`

```java
    private final DatePicker date = new DatePicker(LocalDate.now().minusDays(1));

    private final DatePicker until = new DatePicker(LocalDate.now().minusDays(1));
...
        LocalDate from = readDate("replay.from", LocalDate.now().minusDays(1));
```

`ui/replay/DatePicker.java:95` e `:187`

```java
        setDate(initial == null ? LocalDate.now() : initial);
...
        LocalDate current = date() == null ? LocalDate.now() : date();
```

**Problema.** `LocalDate.now()` sem argumento é `LocalDate.now(ZoneId.systemDefault())`.
Os dias jogáveis que o transporte cinzenta vêm de `ReplayFeed` →
`Sessions.of(...)` (`ui/replay/ReplayFeed.java:163`), fuso do mercado. O padrão
oferecido e o conjunto do qual ele teria de sair são calculados em calendários
diferentes.

**Consequência.** À noite em São Paulo já é o dia seguinte em boa parte do
mundo: o transporte abre num dia que não existe na lista, o leitor vê a data
recusada sem explicação, e o `DatePicker` navega a partir de um "hoje" que não é
o do mercado. Não corrompe dado — custa um clique e uma dúvida, toda vez.

**Correção.** `LocalDate.now(Timeframe.defaultZone())` nos quatro pontos.

**Tentei refutar assim.** (a) Verifiquei se `keepInWindow`/`readDate`
normalizassem contra a lista de sessões antes de mostrar: `readDate` lê a
configuração e `keepInWindow` só limita a largura da janela — nenhum dos dois
consulta os dias jogáveis. (b) Verifiquei se B6-14 ("o calendário fala o idioma
da máquina") já cobrisse: é sobre `Locale`, não sobre `ZoneId`. (c) Verifiquei
se `B6-6` ("as datas lembradas atropelam a correção que `followFeed` acabou de
fazer") cobrisse o padrão: cobre o caminho da data **lembrada**; o
`LocalDate.now()` é o caminho de quando não há lembrada.

---

## BAIXA

### L2-8. `Timeframe.startOf` soma minutos de parede à meia-noite: numa virada de horário de verão a barra recebe um carimbo que ela não cobre

`domain/market/Timeframe.java:302-314`

```java
    private long startOf(long millis, ZoneId zone) {
        if (minutes <= 0) {
            return millis;
        }

        ZonedDateTime local = Instant.ofEpochMilli(millis).atZone(zone);
        long sinceMidnight = local.toLocalTime().toSecondOfDay() / 60L;
        long slot = sinceMidnight / minutes * minutes;

        return local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                + slot * 60_000L;
    }
```

**Problema.** `atStartOfDay(zone) + slot × 60.000` é aritmética de instante sobre
uma origem de parede. Nos dias em que o fuso pula, os dois não se compõem:
`atStartOfDay` já devolve 01:00 quando a meia-noite não existiu — que é
precisamente como o Brasil entrava no horário de verão até 2019 — e todo `slot`
do dia sai deslocado de uma hora. `bucketOf`, logo abaixo, agrupa pelos campos do
calendário e **não** tem esse deslocamento: a barra fica agrupada num balde e
carimbada com o instante de outro.

O javadoc da classe se apoia nisto: *"Built from local calendar fields, never
from the epoch — that is the whole point of this class."* `bucketOf` cumpre;
`startOf` sai do calendário no meio da conta.

**Consequência.** Restrita: `data.zone` para o WIN é `America/Sao_Paulo`, que
não observa horário de verão desde 2019, e a fonte começa em set/2020. Então
hoje isto não morde nenhuma série do projeto. Morde no dia em que `data.zone`
apontar para um mercado que ainda usa o mecanismo, e o sintoma seria um dia por
ano com todas as barras uma hora fora — visível como um degrau no eixo, não como
um erro.

**Correção.** `local.toLocalDate().atStartOfDay(zone).plusMinutes(slot).toInstant().toEpochMilli()`
— `plusMinutes` num `ZonedDateTime` respeita a virada; a soma em millis não.

**Tentei refutar assim.** (a) Verifiquei se B1-3 já cobrisse: B1-3 é sobre a
barra de **vários dias** receber a meia-noite (o ramo `slot == 0`), e B1-4 sobre
a âncora da era. Nenhum dos dois é a soma de parede. (b) Verifiquei se o ramo
fosse inalcançável para escalas dentro do dia: não — é o ramo normal, de 1 a
1.440 minutos. (c) Verifiquei se a série do projeto atravessasse uma virada:
não, e digo isso acima em vez de inflar a severidade.

---

### L2-9. O relógio do replay anda `FRAME × speed` por quadro, e o javadoc chama `FRAME` de "milissegundos de tempo de parede"

`ui/replay/ReplaySession.java:85-93` e `:778-779`

```java
    /**
     * How often the clock ticks, in milliseconds of wall time.
...
    private static final int FRAME = 40;
...
    private void tick() {
        live.advanceMarketTime((long) FRAME * speed);
```

**Problema.** O `javax.swing.Timer` coalesce eventos e não garante 25 disparos
por segundo; sob carga na EDT — e esta auditoria já anotou muito trabalho pesado
na EDT no mesmo caminho (B5-4, B3-9, B3-10, B4-4) — os quadros atrasam. Como
cada quadro credita 40 ms de mercado independentemente de quanto tempo de parede
passou, o relógio de mercado anda mais devagar que o de parede e nada diz. O
javadoc afirma o vínculo (*"milliseconds of wall time"*) que o código não mantém.

**Consequência.** "1×" não é tempo real, e o desvio cresce com a carga. Para
estudar não estraga número nenhum — a série é a mesma; estraga a única coisa que
a velocidade prometia, que é a cadência.

**Correção.** Ou medir o decorrido (`System.nanoTime()` entre `tick()`s) e
creditar isso vezes a velocidade, ou trocar o javadoc para dizer o que o código
faz: `FRAME` é a fatia de **tempo de mercado** por quadro, e o passo de parede é
o que o Timer conseguir. A segunda é mais barata e provavelmente a certa — uma
cadência determinística vale mais num replay do que uma fiel — mas então a frase
tem de dizer isso.

**Tentei refutar assim.** (a) Verifiquei se `timer.setCoalesce(false)` estivesse
ligado em algum lugar, o que faria os quadros atrasados serem repostos:
`grep -n "setCoalesce" src/main` não devolve nada. (b) Verifiquei se o comentário
das linhas 88-91 já ressalvasse: ressalva por que o intervalo é **fixo** e a
velocidade é que muda — não que o intervalo pode não ser cumprido. (c) Considerei
não reportar por ser só comentário: a convenção da casa é explícita em que
comentário que mente é achado, e este é o campo que define o significado de "1×".

---

## LIMPO

O que conferi e está certo, e como.

**`OwnScale` — a regra da escala maior.** Li `map` (linha 98), `smooth` (136) e
`indexOfClosed` (74) inteiros e refiz a aritmética. A condição em todos os três é
`coarse.timeAt(k + 1) <= fine.timeAt(i)` — a barra grossa `k` só entra depois que
a **seguinte já começou**, o que é conservador em relação a "fechou": num buraco
de sessão ela demora mais, nunca menos. O ponteiro corrente de `map`/`smooth`
teto em `coarse.size() - 2`, então a barra em formação **nunca** é usada; testei
o limite mentalmente com `coarse.size() == 1` (nada é usado, tudo NaN) e `== 2`
(só a primeira, e só depois de `timeAt(1)`). Em `smooth`, a rampa parte de
`shut = coarse.timeAt(closed + 1)` — o instante em que `slow[closed]` se tornou
conhecível — e o comentário das linhas 160-170 explica que medir de
`timeAt(closed)` já foi tentado e punha a rampa inteira no passado. Está certo, e
a explicação também. Verifiquei ainda o caso em que a escala própria é um
**renko** (`PeriodCatalog.forText:146` e `common():193` oferecem `11R` ao mesmo
diálogo que escolhe a escala do indicador): vários tijolos podem compartilhar
carimbo, o que produz `span == 0` em `smooth` — tratado pelo `if (span <= 0)
continue` — e nunca faz `closed` avançar para um tijolo que ainda não existe,
porque avançar exige que o carimbo do **seguinte** já tenha passado.

**`ReplaySeries` — o que ainda não chegou.** Li `clock()` (406), `size()` (452),
`timeAt`/`openAt`/`highAt`/`lowAt`/`closeAt`/`volumeAt` (455-493), `check` (503),
`spendStamped` (274), `startForming` (305), `step` (333), `advance` (356) e
`seek` (370). `check(index)` recusa qualquer índice `>= size()` com uma mensagem
que nomeia o que aconteceu ("bar N has not happened yet"), e `size()` é
`completed + (path == null ? 0 : 1)` — a barra em formação e nada além. `openAt`
devolve o aberto armazenado com a justificativa certa (o aberto está fixo desde o
primeiro tick); os outros três devolvem o acumulado só para a barra em formação.
`clock()` com `path == null` devolve `start + barMillis`, o **fim** da última
barra revelada, e o comentário conta por que o começo mandava o relógio para
trás. Nenhum caminho lê `day` além de `completed`.

**`TickBars.countUntil` e `MarketFile.countUntil` — a fronteira.** Ambos são
"estritamente antes" (`timeAt(middle) < when`), e o chamador do replay pede
`growing.advance(day, now + 1)` (`ChartCanvas.java:1437`), isto é *até e
incluindo* `now`. A soma de um confere com a busca estrita: nem uma barra a mais
nem a de menos. Conferi as duas buscas binárias linha a linha, inclusive o
`found` inicial (0 e `size()`, respectivamente, cada um o valor certo para o caso
"nenhuma satisfaz").

**Onde cada indicador calcula.** Todos os `calculate(...)`/`computeOver(...)` de
`src/main` (58 ocorrências) recebem `this.series` ou `canvas.series()` — a série
já dobrada e já recortada pelo replay —, nunca `source`. Não achei um só caminho
em que um estudo veja mais barras do que o gráfico.

**`RecordedTicks.endOf` (linha 192).** Lê `series.timeAt(index + 1)`, uma barra
que no replay ainda não foi revelada. Conferi e **não** reporto: o que é lido é o
carimbo do balde seguinte, que é uma fronteira de calendário conhecida de
antemão, não um preço; e o valor só delimita `[from, to)` para escolher ticks da
própria barra. A alternativa (largura da barra anterior) daria o mesmo número em
série regular e um pior nos buracos.

**`Timeframe.bucketOf`.** Refiz os cinco ramos: dia (`toEpochDay`), mês
(`ano × 12 + mês`, com o comentário certo sobre setembros), semana (recuo até a
segunda local, com a nota de que `epochDay / 7` cai numa quinta), acima de um dia
e intradiário (`epochDay × 1440 + slot`). Todos partem de campos de calendário,
como o javadoc promete. O único desvio dessa promessa está em `startOf`, e é o
L2-8.

**O que a correção de B1-1 fechou.** Confirmei na árvore atual que
`domain/market/Sessions.java:76` e `:132` já leem `Timeframe.defaultZone()`, e
que `RecordedTicks.java:240`, `TickFile.java:402` e `TapeFile.java:474` também.
Os dezoito `systemDefault()` que sobram em `src/main` estão todos sob `ui/`,
mais o fallback de `SegmentedSeries.java:75` e as duas ocorrências legítimas de
`Timeframe.java:159`/`:174` (o valor inicial e a queda de `useZone(null)`).

**O que procurei e não achei.** Nenhum `System.currentTimeMillis()` no domínio
(o único de `src/main` está em `RandomWalkSeries.java:57`, gerador de fixture, já
apanhado por B4-30). Nenhum `Instant.now()`. Nenhum `ZoneOffset` cru. Nenhum laço
de indicador olhando `i + 1` (os sete casos de `+ 1` são índice de vetor de
fronteira, parser de CSV, e o `endOf` acima).
