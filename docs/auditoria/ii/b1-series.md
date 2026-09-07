# B1 — séries, arquivos e sessões

Auditoria II, 07/09/2026. Passada independente: `docs/auditoria/*.md` (primeira
passada) **não foi lida**.

## O que foi lido

Todas as linhas dos 20 arquivos da família em
`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/`:

| arquivo | linhas |
|---|---|
| `PriceSeries.java` | 100 |
| `ArraySeries.java` | 127 |
| `ConcatSeries.java` | 158 |
| `SegmentedSeries.java` | 165 |
| `Segment.java` | 85 |
| `Sessions.java` | 164 |
| `Timeframe.java` | 375 |
| `Aggregation.java` | 72 |
| `MarketFile.java` | 327 |
| `SeriesMerge.java` | 164 |
| `TickFile.java` | 470 |
| `TapeFile.java` | 595 |
| `TickSource.java` | 144 |
| `TickSeries.java` | 153 |
| `TickLibrary.java` | 352 |
| `TickBars.java` | 203 |
| `FoldedTicks.java` | 140 |
| `MetaTraderTicks.java` | 257 |
| `ProfitTrades.java` | 383 |
| `Aggressor.java` | 93 |
| **total** | **4.527** |

`SeriesMerge.java` foi acrescentado à lista do pedido: é da mesma família
(junta duas exportações cruas numa série) e ninguém o tinha alocado.

Não auditados, por serem da área B2: `Renko`, `TickRenko`, `ReplaySeries`,
`RecordedTicks`, `SyntheticTicks`, `TickPath`, `TradeTally`, `Untraded`,
`Counted`.

Lidos como apoio, para confirmar ou derrubar achados (não contam como área):
`Launcher.java` 60-135, `ui/chart/PeriodCatalog.java` 100-175,
`ui/chart/ChartCanvas.java` 2350-2385, `ui/series/Segmentable.java` 120-165,
`ui/replay/ReplaySession.java` 220-305, `ui/replay/ReplayFeed.java` 105-160, e
os testes `SessionsTest`, `TimeframeTest`, `FoldedTicksTest`, `TickLibraryTest`.

**Contagem:** 2 ALTA, 11 MÉDIA, 8 BAIXA.

---

# ALTA

### B1-1. `Sessions` responde no fuso da máquina; tudo que dobra barra responde no fuso do mercado

`Sessions.java:61-63`

```java
    public static NavigableSet<LocalDate> of(PriceSeries series) {
        return of(series, ZoneId.systemDefault());
    }
```

`Timeframe.java:159` e `Timeframe.java:179`

```java
    private static volatile ZoneId zone = ZoneId.systemDefault();
...
        return apply(source, defaultZone());
```

**Problema.** `Launcher.java:89` chama
`Timeframe.useZone(ZoneId.of(market))` a partir de `data.zone`, e o comentário
que precede a linha diz por quê: *"Every fold that is not handed a zone lands on
Timeframe.defaultZone, and until this line that was always the machine's -- so a
machine outside São Paulo cut the day at the wrong hour, in silence"*.
`TickFile.java:401` e `TapeFile.java:473` foram corrigidos para ler
`Timeframe.defaultZone()`. **`Sessions` não foi.** A sobrecarga de um argumento
continua caindo em `ZoneId.systemDefault()`, e **os cinco chamadores de produção
usam exatamente essa sobrecarga**:

```
Launcher.java:124                 Sessions.of(SeriesCatalog.open(name)...)
ui/chart/RenkoSource.java:124     Sessions.of(series)
ui/chart/SeriesSummary.java:122   Sessions.of(series).size()
ui/replay/ReplayFeed.java:163     Sessions.of(...)
ui/series/Segmentable.java:145    Sessions.of(bars)
```

A sobrecarga de dois argumentos só aparece em `SessionsTest.java:273`. É a mesma
armadilha que o javadoc de `Timeframe.useZone` já descreve a respeito de si
mesmo — *"The two-argument one was called from a test and from nowhere else: the
test proved a zone the application never used"* — repetida em `Sessions` e não
percebida.

**Consequência.** Numa máquina a leste de ~UTC+6 (Tóquio, Sydney, Auckland) o
pregão do WIN — 09:00 a 18:25 em São Paulo — atravessa a meia-noite local. As
1.494 sessões viram ~2.900 datas. Concretamente: `RenkoSource` recusa construir
tijolos porque "uma sessão da tela não tem ticks" quando a sessão inventada não
tem mesmo; o transporte do replay oferece dias que não existem;
`SeriesSummary` mostra o dobro de pregões; e `Segmentable` conta errado o que um
segmento cobre. Nada disso avisa. Pior, `RecordedTicks.java:240` já lê
`Timeframe.defaultZone()` para achar o dia de um instante, então as duas metades
do mesmo caminho discordam sobre qual é o dia da barra.

**Correção.** `return of(series, Timeframe.defaultZone());` na linha 62 —
e o mesmo em `Sessions.of(series, zone)` linha 118, onde o fallback de `zone ==
null` também é `ZoneId.systemDefault()`.

**Tentei refutar assim.** (a) Procurei um chamador de produção que passasse
fuso: não há, só o teste. (b) Verifiquei se o cache de `ANSWERED` salvaria — não:
ele guarda o fuso na `Answer` e casa por igualdade, então o erro é *consistente*,
nunca intermitente, o que o torna mais difícil de notar e não menos. (c) Verifiquei
se a máquina do autor está em BRT e o problema seria teórico — está, mas o ajuste
`data.zone` existe exatamente para a máquina que não está, e o comentário do
`Launcher` diz que essa é a razão da linha. (d) Verifiquei se para um fuso a
oeste (UTC-8) ou UTC+0 o pregão cabe num dia local — cabe, então o defeito
precisa de fuso ≥ ~UTC+6. Isso reduz o alcance, não o defeito: continua sendo
uma pergunta com duas respostas dentro do mesmo repaint.

---

### B1-2. `FoldedTicks.day` engole `IOException` e devolve série vazia — um arquivo corrompido some do gráfico sem uma palavra

`FoldedTicks.java:87-100`

```java
        Path file = source.fileFor(folder, instrument, day);

        if (!day.equals(source.sessionIn(file))) {
            return PriceSeries.empty();
        }

        try {
            return Timeframe.ONE_MINUTE.fold(TickBars.of(source.read(file)), zone);
        } catch (IOException e) {
            return PriceSeries.empty();
        }
```

**Problema.** O `catch` apaga exatamente as recusas que `TapeFile` e `TickFile`
foram construídos para produzir. `TapeFile.java:224` lança
`"the trades end at N and the file ends with them: no broker dictionary"`, e o
comentário acima dela (linhas 217-223) explica que a recusa existe porque a
alternativa *"would hand back a session whose thirty-one brokers are all null"*.
`TapeFile.java:200-203` recusa um byte de agressor fora do enum, com um
comentário de treze linhas dizendo que o certo é *"Refusing the file on read,
with its name in the message, is what MarketFile and TickFile do"*. Nada disso
chega a lugar nenhum: `FoldedTicks.day` transforma todas em `PriceSeries.empty()`.

E o javadoc do método (linha 80-85) declara uma condição que não é a verdadeira:

```java
     * @return that session as one-minute candles, or empty when it was not
     *         exported
```

Vazio também quando **foi** exportado e o arquivo está truncado, com o cabeçalho
mentindo, com um setor danificado ou com o dicionário de corretoras perdido.

**Consequência.** `FoldedTicks.over` (linha 116) descarta a sessão vazia e
`ConcatSeries.of` (linha 121) emenda o resto:

```java
            if (session.size() > 0) {
                parts.add(session);
            }
```

Um pregão corrompido no meio de um intervalo **desaparece da série** e o gráfico
sai contínuo, sem buraco visível e sem mensagem. É perda de dado silenciosa num
caminho cujo propósito declarado (`TickFile` linha 35) é *"Nothing the exchange
said is dropped"*.

**Correção.** Deixar a `IOException` subir — `day` já é chamado fora da EDT, o
javadoc da classe (linha 59) diz *"it is seconds, and it must not be on the
interface thread"* — ou, no mínimo, separar "não exportado" (vazio, silêncio) de
"exportado e ilegível" (erro reportado). O `catch` atual junta os dois.

**Tentei refutar assim.** (a) Procurei tratamento no chamador:
`ReplaySession.java:443` e `MainWindow.java:478` recebem só a série e não têm
como distinguir vazio-por-ausência de vazio-por-defeito. (b) Procurei teste que
cubra o caso: `FoldedTicksTest` tem `aDayThatWasNotExportedIsEmpty` (arquivo
ausente) e nenhum caso de arquivo presente e corrompido — o teste passa
igualmente com o produto quebrado neste ponto. (c) Considerei que a política da
casa pudesse ser "nunca interromper o gráfico": não é — `MarketFile.read` linha
174 lança `IOException` nomeando os dois números quando o arquivo não bate com o
cabeçalho, e `Segmentable.java:141-147` mostra que o caminho de candles trata o
erro explicitamente.

---

# MÉDIA

### B1-3. Barra de vários dias recebe a meia-noite — o instante que o javadoc do próprio método diz que não deve carregar

`Timeframe.java:304-315`

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
```

E o javadoc imediatamente acima, `Timeframe.java:299-302`:

```java
     * <p><b>Only for scales inside a day.</b> A day, week or month bucket
     * begins at midnight, and midnight is not a moment this market existed; the
     * first bar's time is the session's open, which is what a daily candle
     * should carry. So those keep it.</p>
```

**Problema.** A guarda é `minutes <= 0`, que cobre `DAY` (0), `WEEK` (-1) e
`MONTH` (-2). **Não cobre uma escala de vários dias expressa em minutos**, que
`ofMinutes` aceita até `MOST_MINUTES = 43_200` (trinta dias). Para
`ofMinutes(4320)` — três dias — `sinceMidnight` vale no máximo 1.439, então
`slot = 1439 / 4320 * 4320 = 0`, e a barra sai carimbada com a meia-noite local.
Exatamente o instante que o javadoc chama de *"not a moment this market
existed"*.

**Consequência.** Uma barra de três dias no eixo de tempo aparece às 00:00, não
às 09:00 da abertura. E o carimbo não é nem o início do balde: se o primeiro
pregão do balde cair no segundo dia dele (feriado na segunda), sai a meia-noite
desse segundo dia. Nenhuma busca por instante contra a base de minutos encontra
esse carimbo.

**Correção.** Trocar a guarda por `if (minutes <= 0 || minutes >= DAY_MINUTES)`,
que é a mesma linha que `bucketOf` já usa na 349 para decidir contar dias
inteiros.

**Tentei refutar assim.** (a) Verifiquei se `bucketOf` compensa: não — ele
agrupa certo, é só o carimbo que sai errado. (b) Procurei o caso no teste:
`TimeframeTest.aScaleAboveADayDoesNotBecomeDaily` (linha 347-382) exercita
`ofMinutes(3*24*60)` mas só chama `bucketOf` e conta baldes compartilhados;
**nunca olha o `timeAt` da barra dobrada**, então não pega isto. (c) Verifiquei
se uma escala de vários dias é alcançável: `PeriodCatalog.java:137` chama
`Timeframe.ofMinutes(number)` com o que o leitor digitou, e o comentário de
`bucketOf` linha 355 diz textualmente *"MOST_MINUTES lets a reader type up to
thirty days, so this is reachable by typing"*.

---

### B1-4. A escala de vários dias é ancorada no dia zero da era — que foi uma quinta-feira

`Timeframe.java:349-361`

```java
        if (minutes > DAY_MINUTES) {
            ...
            return local.toLocalDate().toEpochDay() / Math.max(1, minutes / DAY_MINUTES);
        }
```

**Problema.** O javadoc da classe (linhas 39-45) proíbe isto em voz alta:

```java
     *   <li><b>Weeks would run Thursday to Wednesday.</b> Epoch day zero was a
     *       Thursday, so {@code epochDay / 7} groups weeks from Thursday.</li>
```

e a linha 50 promete *"buckets are computed from local calendar fields in the
exchange's zone, **never from the epoch**"*. A linha 361 é `epochDay / N`. O
fuso é aplicado antes de virar `epochDay`, o que salva metade do problema; a
âncora continua sendo 1970-01-01. Para `ofMinutes(10080)` — sete dias — isso é
literalmente `epochDay / 7`, o caso nomeado no javadoc: semanas de quinta a
quarta. E `WEEKLY`, na mesma classe, alinha na segunda (linha 344-346). Duas
respostas para "o que é uma semana", vindas do mesmo objeto.

**Consequência.** Quem digita `10080` recebe uma semana deslocada em três dias
em relação a `W1`, sem nada dizendo. Para qualquer N de dias o corte cai num dia
da semana arbitrário, decidido por uma data de 1970.

**Correção.** Ancorar num marco do calendário do mercado (a segunda-feira da
semana do primeiro pregão da série, por exemplo) em vez do dia zero da era, e
dizer no comentário qual é a âncora escolhida.

**Tentei refutar assim.** (a) Verifiquei se `WEEKLY` intercepta o caso:
não — `ofMinutes(10080)` não bate com nenhum `known.minutes` em `common()`
(`WEEKLY.minutes` é -1), então cai no `new Timeframe("10080m", 10080)` da linha
125. (b) Verifiquei se o teste pega: `aScaleAboveADayDoesNotBecomeDaily`
declara explicitamente que mede *"without depending on where the groups happen
to start"*, ou seja, foi escrito para **não** olhar a âncora. (c) Considerei que
a âncora fosse irrelevante por convenção: não é, porque a mesma classe já
declara que para `WEEKLY` ela importa, e pelo mesmo motivo.

---

### B1-5. A ordem de declaração de `Aggressor` é o formato do arquivo, e nada em `Aggressor.java` diz isso

`TapeFile.java:346`

```java
            buffer.put((byte) (aggressor.ordinal() + 1));
```

`TapeFile.java:587`

```java
            return KINDS[aggressor[index] - 1];
```

`ProfitTrades.java:300`

```java
        rows.aggressor[rows.count] = (byte) (aggressor.ordinal() + 1);
```

**Problema.** O byte gravado em disco é o `ordinal()` do enum. `Aggressor.java`
não tem uma palavra a respeito — e o javadoc da classe (linhas 32-39) apresenta
os cinco valores numa **tabela ordenada por frequência** (`SELLER` 37,2%,
`BUYER` 36,8%, `RLP` 25,9%, `AUCTION`, `DIRECT`) que **não** é a ordem de
declaração (`BUYER, SELLER, RLP, AUCTION, DIRECT`). Quem "arrumar" a declaração
para bater com a tabela — a coisa mais natural do mundo de se fazer — troca
comprador por vendedor em todo tape já gravado.

**Consequência.** Silenciosa e total: o `read` continua aceitando o arquivo
(o byte segue dentro da faixa 1..5), e a única coluna do tape que diz DIREÇÃO
passa a dizer o contrário. É o oposto exato do que o javadoc de `Aggressor.of`
(linhas 78-82) diz que a classe existe para garantir.

**Correção.** Dar a cada constante um código explícito
(`BUYER("Comprador", 1)`) e gravar/ler esse código, ou — mais barato — um
comentário em `Aggressor.java` dizendo que a ordem é o formato do arquivo e que
constante nova entra no fim. As demais convenções da casa mandam comentar
exatamente isso: *"o que acontece se alguém simplificar aquilo"*.

**Tentei refutar assim.** (a) Procurei o aviso em `Aggressor.java`: não existe;
os únicos comentários sobre `ordinal` estão em `TapeFile` (linha 583) e falam de
outra coisa (que a subtração é segura). (b) Procurei um teste que congele o
mapeamento: `TapeFileTest` faz ida-e-volta escrita→leitura, o que passa
igualmente com a ordem trocada, porque escrita e leitura usam o **mesmo** enum.
Um arquivo gravado antes da troca é que quebra, e não há fixture desses.
(c) Considerei que o formato ainda não tem arquivos no disco — `TickSource`
linha 68 diz *"The format is decided and not yet written"* — mas o javadoc de
`Aggressor` já cita 43,0 milhões de trades medidos em 04/09/2026, então a
conversão já rodou.

---

### B1-6. `ProfitTrades.convert` chama `Aggressor.values()` por trade — a alocação que `TapeFile.KINDS` existe para evitar

`ProfitTrades.java:127-128`

```java
                writer.add(rows.millis[i], rows.price[i], rows.quantity[i] & 0xFFFF,
                        buyer, seller, Aggressor.values()[rows.aggressor[i] - 1]);
```

`TapeFile.java:96-102`

```java
    /**
     * The aggressors, held once.
     *
     * <p>{@code values()} clones its array on every call, and this is asked per
     * trade over sessions of five million.</p>
     */
    private static final Aggressor[] KINDS = Aggressor.values();
```

**Problema.** O laço de `convert` percorre a exportação inteira e faz
exatamente o que o comentário de `KINDS`, no arquivo vizinho, diz para não
fazer. Cinco milhões de clones de um array de cinco elementos por sessão, num
laço quente — o que a convenção 1 da casa proíbe explicitamente ("nada de
alocação por elemento em laço quente").

**Consequência.** Lixo desnecessário na conversão, que é o caminho mais pesado
do programa (meio gigabyte de texto por sessão). Não muda número na tela; custa
tempo e pressão de GC no único lugar onde isso importa.

**Correção.** `KINDS` é `private static final` em `TapeFile`; torná-lo
package-private, ou declarar um `private static final Aggressor[] KINDS =
Aggressor.values();` em `ProfitTrades`.

**Tentei refutar assim.** (a) Verifiquei se o JIT elimina o clone: não pode —
o array escapa para a indexação e `values()` é obrigado por especificação a
devolver um array novo, exatamente o que `TapeFile` documentou depois de medir.
(b) Verifiquei se o laço é frio: `rows.count` é o número de trades da exportação
inteira, e o javadoc de `Rows` (linha 169) fala de sessões de ~94 MB.
(c) Verifiquei se há um caminho mais curto — sim, e é o ponto: o byte já
carrega `ordinal()+1` e é reconvertido para enum só para `TapeFile.Writer.add`
o converter de volta em byte na linha 346.

---

### B1-7. Alocação por linha nos dois conversores, numa classe cujo javadoc se gaba de não alocar por linha

`MetaTraderTicks.java:183`

```java
        // Where each field begins, found once for the row.
        int[] starts = new int[8];
```

`ProfitTrades.java:250`

```java
        int[] ends = new int[8];
```

E `MetaTraderTicks.java:41-45`:

```java
     * <p><b>Parsed by hand, from bytes.</b> Not because it is clever but because
     * the file is four gigabytes and 87 million rows: splitting each row into seven
     * strings allocates 600 million objects to read numbers that are already there
     * in the bytes.
```

**Problema.** O comentário `// found once for the row` é verdadeiro sobre a
busca e enganoso sobre o custo: o array é alocado uma vez **por linha**. São 87
milhões de `int[8]` (medida do próprio javadoc) numa classe que declara ter sido
escrita à mão justamente para não alocar 600 milhões de objetos. O ganho
declarado é real, mas 87 milhões de alocações continuam lá, escondidas atrás de
um comentário que soa como se não estivessem.

**Consequência.** Custo de GC no caminho de conversão de 4 GB. É dívida, não
resultado errado — mas é dívida no exato lugar onde a classe diz ter pago para
não a ter.

**Correção.** Um `int[8]` reaproveitado, campo do laço em `convert`/`read`,
passado para `write`/`parse`. Os métodos já recebem `row` e `length` reusados
pelo mesmo motivo.

**Tentei refutar assim.** (a) Verifiquei se o escape analysis do HotSpot
elimina a alocação: `starts` é passado adiante como argumento em
`whole(row, starts[2], starts[3] - 1)` dentro do mesmo método, então em tese é
escalarizável — mas isso depende de inline e não é uma garantia sobre a qual as
outras decisões desta classe foram tomadas (o resto dela evita alocação
explicitamente, não por confiar no JIT). (b) Verifiquei se o array é usado fora
do método: não é, o que faz a correção trivial e barata.

---

### B1-8. "A directory listing, not a read: this costs nothing" — `exported()` abre e lê o cabeçalho de todo arquivo

`ui/series/Segmentable.java:156-158`

```java
            // A directory listing, not a read: this costs nothing and is the
            // reason a tick source can be offered in the same list as a series
            // without the window becoming slow to open.
            return library.exported();
```

`FoldedTicks.java:127`

```java
     * <p>The listing is a directory read and costs nothing; the sessions are
```

O que `exported()` de fato faz, `TickLibrary.java:320-321`:

```java
                    .forEach(file -> {
                        LocalDate day = source.sessionIn(file);
```

e `TickSource.java:141-142` → `TickFile.sessionOf` → `TickFile.java:133`:

```java
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
```

**Problema.** É **um `FileChannel.open` + 24 bytes lidos por arquivo
candidato**, não uma listagem de diretório. Dois comentários em dois arquivos
afirmam que não custa nada, e o de `Segmentable` usa essa afirmação como licença
para rodar na thread que abre a janela.

**Consequência.** Hoje o custo é pequeno porque há poucas sessões exportadas
(20 e 9, pelas medições em `FoldedTicks.java:55-56`). Com os seis anos que o
projeto pretende comprar são ~1.500 aberturas de arquivo — e o comentário que
autorizou colocar isso no caminho da janela continuará lá dizendo que não custa
nada. É o comentário mentindo sobre a propriedade que sustenta a decisão.

**Correção.** Corrigir os dois comentários para dizer o que é (uma abertura e um
cabeçalho por arquivo, `O(sessões)` de I/O), e decidir com o número certo se o
lugar da chamada continua sendo esse.

**Tentei refutar assim.** (a) Verifiquei se `sessionIn` filtra por extensão
antes de abrir — filtra (`TickSource.java:141`), então só os arquivos da fonte
certa são abertos; ainda é um por sessão. (b) Verifiquei se há cache: não há
nenhum em `TickLibrary.exported`; `ReplayFeed.java:150` tem um `KNOWN` próprio,
`Segmentable` não. (c) Verifiquei se o `Launcher` já aqueceu isso fora da EDT —
aquece `Sessions`, não `exported()`.

---

### B1-9. `TickLibrary.close()` corre com o carregador: a biblioteca fechada pode voltar a segurar 113 MB e avisar o observador

`TickLibrary.java:223-228`

```java
    @Override
    public void close() {
        loader.shutdownNow();

        forget();
    }
```

`TickLibrary.java:247-261`

```java
        loader.execute(() -> {
            try {
                if (has(day)) {
                    keep(day, source.read(fileFor(day)));
                    whenLoaded.run();
                }
            } catch (IOException e) {
                ...
                whenLoaded.run();
            } finally {
                loading.remove(day);
            }
        });
```

**Problema.** `shutdownNow()` interrompe, mas não espera. Uma tarefa que já
terminou `source.read` e está entrando em `keep(...)` executa `resident.put`
**depois** de `forget()` ter limpado o mapa. A biblioteca fica fechada
(`isClosed()` devolve `true`, porque olha só `loader.isShutdown()`) e ao mesmo
tempo segurando uma sessão inteira. O javadoc de `close` (linhas 215-221) diz
que o objetivo é justamente não deixar "a thread e até três sessões de ticks,
que são centenas de megabytes" presas.

No mesmo caminho, `whenLoaded.run()` roda depois do fechamento.
`ReplaySession.java:301` registra um observador que faz
`SwingUtilities.invokeLater` e toca `preparing`, `announce()` e a janela — ou
seja, uma sessão de replay já encerrada pode receber um aviso e mexer na tela.

**Consequência.** Vazamento de memória intermitente (uma sessão, ~113 MB pela
medição da linha 40) e um retorno de chamada para um objeto que se considera
morto. Não reproduz de forma confiável, que é a pior forma.

**Correção.** Um `volatile boolean closed`, testado dentro de `keep` e antes de
`whenLoaded.run()`; e `isClosed()` passando a olhar esse campo, para não dizer
"fechada" enquanto o mapa não está vazio.

**Tentei refutar assim.** (a) Verifiquei se `FileChannel` interrompível fecha a
janela: fecha para a tarefa que está **dentro** de `read` (lança
`ClosedByInterruptException`), mas não para a que já saiu dele. (b) Verifiquei
se o `catch (IOException)` engoliria a `ClosedByInterruptException` — engole, o
que é razoável, mas ele ainda chama `whenLoaded.run()` na linha 256, depois do
close. (c) Verifiquei se algum chamador protege: `FoldedTicks.all` usa
try-with-resources sem nunca chamar `request`, então lá é seguro;
`ReplaySession` é o caso exposto. (d) `TickLibraryClosingTest` (129 linhas)
prova que `close` desliga a thread, não que nada volta depois dela.

---

### B1-10. `residentDays()` promete "oldest use first"; o campo que ele lê já documenta que nada registra uso

`TickLibrary.java:198-203`

```java
    /** @return the dates in memory, oldest use first */
    public List<LocalDate> residentDays() {
        synchronized (resident) {
            return new ArrayList<>(resident.keySet());
        }
    }
```

`TickLibrary.java:79-82`, no javadoc do campo que esse método devolve:

```java
     * <p><b>Insertion order, not use.</b> This said "newest use last" and the
     * map is a plain {@code LinkedHashMap} -- the access-order constructor was
     * never used, and {@code at} only calls {@code get}, which moves nothing.
     * Nothing anywhere records a use.</p>
```

**Problema.** A correção foi feita no campo e não no método público. O javadoc
que quem usa a classe lê continua afirmando a coisa que o autor já derrubou por
escrito, dez linhas acima, no mesmo arquivo.

**Consequência.** Quem confiar em "oldest use first" para decidir o que
descartar decide errado; e é o javadoc **público** — o do campo é privado e não
aparece na documentação gerada.

**Correção.** `/** @return the dates in memory, in the order they were read */`.

**Tentei refutar assim.** (a) Reli `at`, `keep` e `forget`: nenhum deles toca a
ordem do mapa; `keep` só faz `put` e `remove`. (b) Verifiquei se algum chamador
depende da ordem: `TickLibraryTest.java:121` e `:160` chamam
`.stream().sorted()` antes de comparar, ou seja, o próprio teste já não confia
na ordem prometida.

---

### B1-11. `finally` fecha duas vezes e apaga a exceção original nos dois conversores

`ProfitTrades.java:134-147`

```java
            if (writer != null) {
                finish(written, progress, open, writer,
                        TickSource.PROFIT.fileFor(folder, instrument, open), trades, contracts);

                writer = null;
            }
        } finally {
            // Only reached when something threw: the normal path closed it
            // above. Without this a failed conversion leaves a file open and a
            // header still claiming zero trades.
            if (writer != null) {
                writer.close();
            }
        }
```

`MetaTraderTicks.java:135-145` tem a mesma forma.

**Problema.** `finish` chama `writer.close()` (linha 155) **antes** de `writer =
null` ser executado. Se esse `close` lançar — e ele faz I/O: `flush`,
`writeBrokers`, `position(0)`, `writeHeader` — a variável `writer` ainda aponta
para o escritor já fechado, e o `finally` chama `close()` de novo.
`TapeFile.Writer.close` (linha 368-379) começa por `flush()`, que escreve num
canal já fechado e lança `ClosedChannelException`. **Uma exceção lançada de
dentro de um `finally` substitui a que estava propagando**, então o erro real
— o que dizia por que a conversão falhou — some.

O mesmo vale para as chamadas de `finish` no meio do laço (`ProfitTrades:110`,
`MetaTraderTicks:107` e `:126`): ali `writer` sequer chega a ser reatribuído
antes de a exceção escapar.

**Consequência.** Uma conversão que falha ao fechar uma sessão reporta
`ClosedChannelException` em vez da causa. O comentário do `finally` diz "Only
reached when something threw", o que é verdade, e não previu que ele próprio
seria o que trocaria a mensagem.

**Correção.** Anular a referência antes de fechar (`var closing = writer; writer
= null; closing.close();` dentro de `finish`, ou passar a responsabilidade de
anular para o chamador antes da chamada), ou tornar `close()` idempotente com
uma guarda `if (!channel.isOpen()) return;`.

**Tentei refutar assim.** (a) Verifiquei se `TapeFile.Writer.close`/
`TickFile.Writer.close` já são idempotentes: não são — ambos chamam `flush()`
incondicionalmente e só o `channel.close()` está em `finally`. (b) Verifiquei se
Java faz supressão automática: só em try-with-resources; aqui é `try/finally` à
mão, então a original é descartada, não suprimida. (c) Verifiquei se `close` de
fato pode lançar: sim — é onde o cabeçalho definitivo é reescrito, o momento
mais provável de um disco cheio se manifestar.

---

### B1-12. `SeriesMerge.stepAt` devolve 0 — o valor de "junção sadia" — quando a exportação antiga inteira foi descartada

`SeriesMerge.java:89-95`

```java
        int keep = countBefore(older, newer.timeAt(0));

        if (keep == 0) {
            return 0;
        }

        return Math.abs(newer.openAt(0) - older.closeAt(keep - 1));
```

e o javadoc da classe, linha 43-44:

```java
     * measures the step so the caller can refuse: it is the one number that says
     * whether a join is sound.
```

**Problema.** `keep == 0` significa que **nenhuma barra da exportação antiga
sobreviveu** — a "mais antiga" começa depois da "mais nova", isto é, os dois
argumentos vieram trocados ou não se sobrepõem como o método supõe. Devolver 0
diz ao chamador "o degrau é zero, pode juntar", que é a leitura oposta.
`SeriesMerge.of` faz a mesma coisa em silêncio na linha 69-71: `if (keep == 0)
return newer;` — a série antiga inteira é jogada fora sem uma palavra.

É a "guarda que protege menos do que diz": o número que existe para permitir a
recusa devolve o valor que autoriza.

**Consequência.** Perda silenciosa de toda a série mais antiga, com o indicador
de sanidade dizendo que está tudo bem. O javadoc da classe (linhas 29-36) é
inteiro sobre por que a junção errada é cara — 1.970 pontos de discrepância em
dia de rolagem.

**Achado auxiliar, do mesmo arquivo:** `SeriesMerge` **não tem nenhum chamador
de produção**. `grep -rn SeriesMerge src/` acha só `SeriesMerge.java` e
`SeriesMergeTest.java`. As 164 linhas — e as medições que carregam (97,6% dos
minutos idênticos, as duas sessões de rolagem de 13/10/2021 e 15/12/2021) —
estão vivas só pelo teste. Vale decidir se entra em uso ou se o conhecimento vai
para `docs/medicoes/` antes que o código se perca.

**Correção.** `Double.NaN` (ou uma exceção) para `keep == 0`, e em `of` recusar
ou ao menos distinguir "sem sobreposição" de "junção normal".

**Tentei refutar assim.** (a) Procurei a checagem no chamador: não há chamador.
(b) Reli `SeriesMergeTest` (180 linhas) procurando um caso de argumentos
trocados: o teste cobre sobreposição, ausência de sobreposição pelo lado certo e
o degrau, mas não o caso em que `keep == 0` com dados reais dos dois lados.
(c) Considerei que 0 fosse convenção de "não se aplica": seria defensável se o
javadoc não dissesse que este é *"the one number that says whether a join is
sound"* — dito assim, 0 é uma resposta afirmativa.

---

### B1-13. A coluna `Ativo` da exportação do Profit é descartada, num formato que declara guardar todas

`ProfitTrades.java:249-262` — a coluna 0 é localizada e nunca lida:

```java
        int[] ends = new int[8];
        int fields = 0;

        for (int i = 0; i < length && fields < 8; i++) {
            if (row[i] == ';') {
                ends[fields++] = i;
            }
        }
...
        int date = ends[0] + 1;
```

O primeiro campo usado é `ends[0] + 1`, que é a **Data**. O que vem antes de
`ends[0]` — `Ativo` — nunca é olhado. E `TapeFile.java:36-37` afirma:

```java
 * <p>Every column of Profit's Times &amp; Trades export is kept: the time, the
 * price, the size, both brokers and who crossed.
```

**Problema.** O nome do instrumento vem do parâmetro `instrument` de `convert`,
nunca do arquivo. Se a exportação contiver mais de um ativo — ou se o operador
digitar `win` ao converter um export de `WDO` — os trades vão para o arquivo
errado e ninguém consegue detectar depois, porque a coluna que diria não foi
gravada.

Pior: uma exportação com dois ativos volta a visitar a mesma data. O laço de
`convert` (linhas 108-120) só compara com a data anterior:

```java
                if (!date.equals(open)) {
                    ...
                    writer = new TapeFile.Writer(
                            TickSource.PROFIT.fileFor(folder, instrument, date), date);
```

e `TapeFile.Writer` abre com `TRUNCATE_EXISTING` (linha 299-300). O segundo
bloco da mesma data **apaga o arquivo do primeiro**, e `written` reporta as duas
sessões como escritas. `MetaTraderTicks` tem a mesma forma (linhas 106-112 e
125-130).

**Consequência.** Perda total e silenciosa de uma sessão, com o relatório de
conversão dizendo que ela foi escrita.

**Correção.** Ler `Ativo` e recusar a conversão quando ele não bater com
`instrument` — a seção "What it refuses" do javadoc (linhas 61-67) já diz que é
essa a política da classe. E recusar uma data que reapareça depois de fechada, o
que custa um `Set<LocalDate>`.

**Tentei refutar assim.** (a) Verifiquei se a exportação do Profit é sempre de
um ativo só: é o caso normal (a exportação sai de um gráfico), o que reduz o
alcance — mas o argumento `instrument` continua sendo digitado por quem chama, e
nada confere. (b) Verifiquei se `TapeFile.read` detectaria depois: não — o
arquivo fica bem formado, só com trades do ativo errado. (c) Verifiquei se
`TapeFile.Writer.add` recusaria a ordem: a linha 313 recusa `millis <
lastMillis` **dentro de uma sessão**, mas cada bloco abre um `Writer` novo, que
zera `lastMillis` em -1.

---

### B1-14. `ChartCanvas.axisBucket` é uma segunda resposta para o que `Timeframe.bucketOf` decide

`ui/chart/ChartCanvas.java:2366-2380` (fora da lista da área B1, mas é o mesmo
cálculo — registro aqui para não cair entre as áreas)

```java
    static long axisBucket(ZonedDateTime time, int step) {
        ...
        if (step >= DAY_MINUTES) {
            return time.toLocalDate().toEpochDay() / (step / DAY_MINUTES);
        }

        long minuteOfDay = time.getHour() * 60L + time.getMinute();

        return time.toLocalDate().toEpochDay() * (DAY_MINUTES / step) + minuteOfDay / step;
    }
```

contra `Timeframe.java:366-368`:

```java
        int minuteOfDay = local.getHour() * 60 + local.getMinute();

        return local.toLocalDate().toEpochDay() * 1_440L + (minuteOfDay / minutes) * (long) minutes;
```

**Problema.** Duas fórmulas para "em que balde essa barra cai", em dois
arquivos, com um `DAY_MINUTES = 1_440` declarado em cada. O próprio javadoc de
`axisBucket` (linha 2357) diz que `Timeframe.bucketOf` proíbe o erro *"in a
comment, in the same words, for the same reason"* — e resolve escrevendo a
terceira cópia em vez de chamar a primeira.

A cópia da UI ainda tem uma diferença aritmética: o multiplicador é
`DAY_MINUTES / step`, divisão inteira. Para um passo que não divide 1.440 — 7
minutos, por exemplo — `1440/7 = 205` e `minuteOfDay/7` chega a 205, então o
último slot de um dia e o primeiro do dia seguinte recebem a **mesma** chave.
`Timeframe.bucketOf` multiplica por 1.440 fixo e não colide.

**Consequência.** No WIN a colisão não é alcançável (exige barra depois das
23:55 e o pregão fecha 18:25), então hoje o dano é só a duplicação. A
duplicação, porém, é o defeito: são duas verdades sobre onde o dia termina, e a
próxima correção numa delas não chega na outra.

**Correção.** `ChartCanvas` chamar `Timeframe.bucketOf` — que é
package-private no domínio, então precisaria virar público, ou ganhar um método
`bucketOf(long, ZoneId)` na `Timeframe` visível para a UI (a dependência anda no
sentido permitido: `ui` → `domain`).

**Tentei refutar assim.** (a) Verifiquei se as duas concordam para todas as
escalas oferecidas em `PeriodCatalog.common()`: concordam, porque todas elas
(1, 5, 15, 30, 60) dividem 1.440. Diverge só no que `ofMinutes` deixa digitar.
(b) Verifiquei se a colisão é alcançável com dados de mercado: não é, e digo
isso acima em vez de reportar um resultado errado que não acontece.

---

### B1-15. `whole()` engole caractere inválido; `grouped()`, no outro conversor, recusa

`MetaTraderTicks.java:229-241`

```java
        for (int i = from; i < to; i++) {
            byte b = row[i];

            if (b == '.') {
                break;
            }

            if (b >= '0' && b <= '9') {
                value = value * 10 + (b - '0');
            }
        }

        return value;
```

`ProfitTrades.java:349-362`

```java
            if (at == '.') {
                continue;
            }

            if (at < '0' || at > '9') {
                throw new IllegalArgumentException(csv + ", line " + line + ": \""
                        + new String(row, from, to - from, ENCODING) + "\" is not a number");
            }
```

**Problema.** Dois conversores da mesma família, duas políticas opostas para
"um caractere que não é dígito no meio de um número". `MetaTraderTicks.whole`
simplesmente pula: `"1x2"` vira 12, `"-5"` vira 5, `"1 2"` vira 12. E o mesmo
arquivo, na linha 195-197, declara a política contrária para a contagem de
colunas: *"a row half read is a tick with numbers in the wrong places, which
nothing downstream could detect"*.

**Consequência.** Um preço com lixo no meio entra na base como um número
plausível e errado, sem nada detectar. É o caso exato que o comentário de cima
descreve, tratado do jeito que ele diz não tratar.

**Correção.** Alinhar com `grouped`: recusar, nomeando arquivo e linha. O custo
é uma comparação por byte já presente.

**Tentei refutar assim.** (a) Verifiquei se `-1` como sentinela de campo vazio
depende da leniência: não — o campo vazio é detectado por `to <= from` na linha
223, antes do laço. (b) Verifiquei se a exportação real tem sinal ou separador:
não tem, pelo javadoc da linha 217-220 ("not one price or volume has a
fraction"), o que faz o caso ser sobre arquivo corrompido — e é justamente o que
o outro conversor decidiu recusar.

---

# BAIXA

### B1-16. `PriceSeries.empty()` responde volume, e recusa as outras cinco perguntas

`PriceSeries.java:66-98` — a série vazia sobrescreve `timeAt`, `openAt`,
`highAt`, `lowAt` e `closeAt` para lançar `IndexOutOfBoundsException("empty
series")`, e **não** sobrescreve `volumeAt`, que herda o `default` da linha 61-63
e devolve `Double.NaN` para qualquer índice.

**Problema.** Seis acessores, cinco recusam e um responde. Quem varrer uma série
vazia por engano descobre pelo `size()` ou pela primeira exceção — exceto se o
que ele ler primeiro for o volume.

**Correção.** Sobrescrever `volumeAt` na anônima da linha 67 com o mesmo lance.

**Tentei refutar assim.** Verifiquei se NaN é intencional aqui — o javadoc do
`default` (linhas 57-59) explica NaN para "série que não tem volume", que é uma
afirmação sobre a *série*, não sobre um índice inexistente. São duas coisas
diferentes e o vazio devolve a primeira para a segunda.

### B1-17. `ArraySeries` não valida nada

`ArraySeries.java:70-81` guarda seis arrays sem conferir que têm o mesmo
comprimento nem que `volumes` é não-nulo, enquanto `size()` (linha 94) devolve
`times.length`. A convenção 6 da casa — "record com invariante validada no
construtor", 45 arquivos do domínio seguindo — não é atendida. O javadoc (linhas
28-30) justifica não *copiar*, o que é outra decisão. Hoje os três construtores
(`MarketFile:214`, `Timeframe:280`, `Renko:733`) montam tudo do mesmo tamanho, o
que é o que impede o defeito. Um `if (opens.length != times.length) throw` sai
de graça na construção, que acontece uma vez por série.

### B1-18. `SegmentedSeries` e `ConcatSeries` não carregam `Untraded` nem `Counted`

`Untraded.java:76` e `Counted.java:59` decidem por `instanceof`:

```java
        return series instanceof Untraded marked && marked.untradedAt(index);
```

`SegmentedSeries` (linha 39) e `ConcatSeries` (linha 34) declaram só
`PriceSeries`. Uma série de renko embrulhada em qualquer um dos dois passa a
responder "nenhuma barra sem negócio" e "número de negócios desconhecido", em
silêncio e sem exceção. Hoje não é alcançável: o pipeline é *base → recorte →
escala*, e o renko é sempre o último passo — `MainWindow:489` recorta antes de
agregar. É uma armadilha guardada só pela ordem, não pelos tipos. Delegar as
duas interfaces nos dois embrulhos custa oito linhas.

### B1-19. `TapeFile.isTape` valida a versão contra a constante de `TickFile`

`TapeFile.java:118-120`

```java
    public static boolean isTape(Path file) {
        return TickFile.sessionOf(file, "ENDVTAPE") != null;
    }
```

`TickFile.sessionOf` → `header(channel, file, wanted)` → `TickFile.java:336`
compara com `TickFile.VERSION`. Hoje `TickFile.VERSION` e `TapeFile.VERSION`
valem 1 os dois, então funciona por coincidência. No dia em que o tape virar
versão 2, `isTape` devolve `false` para todo tape válido e a fonte PROFIT some
da lista sem erro. O javadoc de `sessionOf` (linhas 122-126) justifica o
compartilhamento pelos "primeiros vinte e quatro bytes iguais" — que é verdade
para o *layout*, não para o *valor* da versão. `sessionOf` deveria receber a
versão junto do tag.

### B1-20. Linha maior que 512 bytes é truncada em silêncio, e os offsets fixos leem bytes velhos

`MetaTraderTicks.java:95-97`

```java
                        if (b != '\r' && inRow < row.length) {
                            row[inRow++] = b;
                        }
```

O buffer `row` (512 bytes, linha 77) nunca é limpo entre linhas, e `dateOf`
(linha 173) e o cálculo de `millis` (linhas 177-180) leem posições **fixas** 0-23
sem consultar `length`. Uma linha curta ou truncada faz `number(row, 0, 4)` ler
bytes da linha anterior e produzir uma data plausível e errada — que abre um
`TickFile.Writer` para uma sessão inventada. A guarda de colunas (linha 194) pega
a maioria dos truncamentos, porque cortar a linha corta tabulações; não pega o
corte dentro da última coluna nem a linha curta. `ProfitTrades.read` (linha 225)
tem a mesma truncagem, mas ali o corte reduz o número de `;` e a linha 259 recusa
— falha alto. Alinhar: recusar a linha que exceder o buffer.

### B1-21. "Fifteen bytes a trade" — são dezenove

`ProfitTrades.java:169`

```java
     * <p>Fifteen bytes a trade, so a session is around eighty megabytes and the
```

Os sete arrays de `Rows` (linhas 177-189) somam `int[]×3 + short[]×3 + byte[]` =
4+4+4+2+2+2+1 = **19 bytes** por trade. Número magro sem origem, num javadoc que
justifica a escolha de arrays primitivos com aritmética. O "oitenta megabytes"
até bate — mas com 19 bytes e ~4,4 milhões de trades, não com 15. Vale também
notar que `Rows` aloca 1<<20 posições de cada array de saída (≈19 MB) mesmo para
um arquivo de uma linha.

### B1-22. `TickLibrary.load` não olha o conjunto `loading`

`TickLibrary.java:173-189` verifica `at(day)` e `has(day)` mas não consulta
`loading` (linha 95), que existe precisamente para que "a second request does not
queue a second read". Um `load` concorrente com um `request` do mesmo dia lê o
arquivo duas vezes e aloca duas sessões — ~226 MB transitórios pela medição da
linha 40 — antes de uma sobrescrever a outra em `keep`. Não produz resultado
errado; produz um pico de memória que o desenho de "nunca mais que três" existe
para evitar.

### B1-23. O domínio escreve em `System.err`

`TickLibrary.java:344-345`

```java
            System.err.println(folder + ": the tick sessions could not all be"
                    + " listed (" + e + "); showing the " + days.size() + " found");
```

O raciocínio do comentário acima (linhas 333-343) está certo — a falha da
listagem inteira merece ser dita. O canal é que não: é a única saída de texto do
pacote `domain/market`, o leitor não vê `System.err` numa aplicação Swing, e a
mensagem em inglês fixo no código passa ao largo do `ResourceBundle` caso um dia
chegue à tela. Um `Consumer<String>` opcional, ou o mecanismo de rodapé que a
convenção 2 descreve, entrega o aviso a quem precisa dele.

---

# LIMPO

O que foi conferido e está certo, e como.

**`Timeframe.fold`, o NaN de volume por balde** (linhas 236-278). Segui o
`measured` à mão para quatro casos: balde com todas as barras NaN, balde com uma
finita no meio, balde de uma barra só, e o último balde da série. A ordem está
certa — o `if (out >= 0 && !measured)` da linha 245 fecha o balde **anterior**
antes do `out++`, e a repetição da linha 276 fecha o último, que o laço nunca
fecha. `volumes[out] = 0.0` na linha 257 e não `NaN` porque o acumulador precisa
começar somável, e o `measured` é quem decide se aquele zero vira NaN no fim.
A regra "volume ausente é NaN, nunca zero" é respeitada, e por balde, como o
comentário da linha 233-235 promete.

**`Timeframe.bucketOf` para dia, semana e mês** (linhas 331-347). Conferi que
`MONTH` usa `year*12 + month` (setembros de anos diferentes não colidem, como o
comentário da linha 336-337 diz), que `WEEK` volta para a segunda local em vez
de `epochDay/7`, e que a escala dentro do dia multiplica por 1.440 fixo — o que
impede a colisão entre o último slot de um dia e o primeiro do seguinte (é
exatamente onde a cópia da UI, B1-14, erra). Também confirmei que o javadoc do
método está agora **no** método: o comentário da linha 322-326 registra que ele
estava grudado em `startOf` e que o Java só guarda o último — o defeito descrito
está corrigido no código que li.

**Busca binária, quatro implementações.** Testei os invariantes de
`ConcatSeries.partOf` (99-107, forma `(low+high+1)>>>1` com `low=middle`, que é
a correta para "último começo ≤ índice" e não tem laço infinito),
`SegmentedSeries.firstAtOrAfter` (97-105, `high = size()` e `high = middle`,
correta para "primeiro ≥ millis", devolve `size()` quando todas são anteriores),
`MarketFile.countUntil` (119-134) e `SeriesMerge.countBefore` (106-115), que são
a mesma forma "quantas estritamente antes". As quatro estão certas e nenhuma
sofre de estouro (`>>>` em vez de `/2`). `MarketFile.countUntil` faz um
`channel.position` + 8 bytes por sonda, ~20 sondas — o custo que o javadoc
promete.

**Faixas e limites de `SegmentedSeries`.** Confirmei que a linha 86 protege o
caso de recorte fora da base: `Math.max(0, until - from)` com `from == until ==
size()` dá `count = 0`, e `translate` (157-163) recusa qualquer índice. O
javadoc (62-64) promete "série vazia em vez de exceção" para um intervalo fora
da base, e é o que acontece. Confirmei também que a data final é incluída
inteira: a linha 84 usa `segment.to().plusDays(1).atStartOfDay`, não
`to().atStartOfDay`, que cortaria o último pregão na meia-noite.

**`Segment` como record com invariante.** Nome nulo/em branco, `from` nulo e
`to` antes de `from` são recusados no construtor compacto (45-57), e o `trim` da
linha 59 acontece depois das checagens, então `"  "` não passa por ser não-nulo.
Segue a convenção 6.

**`ConcatSeries.of` e a fronteira de feriado.** Confirmei que partes vazias são
descartadas antes de montar `starts` (linhas 57-64) e que, portanto, nenhum
offset aponta para uma fronteira em que barra nenhuma cai — que é o que o
comentário da linha 58-60 diz. Também que uma parte só é devolvida como ela
mesma (linha 70-72), sem embrulho.

**`Sessions`: cache, cópia e crescimento.** O caminho de acréscimo (linhas
130-134) só é tomado quando o fuso bate **e** a série cresceu; série que encolheu
cai na varredura completa, como o javadoc promete. Confirmei que o `seen`
inicial vem de `days.last()` no caminho de acréscimo, então a primeira data do
trecho novo é comparada com a última já conhecida, e não com `null` — o que
duplicaria trabalho mas não erraria. E que o retorno é sempre `new TreeSet<>`
(linhas 127 e 151), então quem limpar ou ordenar o que recebeu não estraga a
resposta guardada; `SessionsTest` linha 250-257 prova isso com dentes (limpa o
conjunto recebido e pede de novo). O `WeakHashMap` não guarda referência à série
dentro da `Answer`, então a entrada realmente pode sair.

**`MarketFile`: formato, magia e truncagem.** `RECORD_BYTES` = 8 + 5×8 = 48
bate com o `<pre>` do javadoc (linhas 43-48). `read` compara `channel.size()`
com o tamanho prometido pelo cabeçalho **antes** de ler qualquer barra (linha
169), então um arquivo cortado por cópia falhada é recusado com os dois números,
e não desenhado pela metade. `header` recusa magia errada, versão errada e
contagem fora de `int`. `fill` (319-325) lança `EOFException` em vez de
devolver dados parciais. `write` e `read` fazem ida-e-volta preservando NaN,
porque `putDouble`/`getDouble` guardam o padrão IEEE — testei o raciocínio
contra a regra "volume ausente é NaN".

**`TickFile` / `TapeFile`: presença, ordem e cabeçalho reescrito.** A máscara de
presença (83-89, e `mask` em 267-270) é o que mantém "disse zero" separado de
"não disse nada", e `Session.hasBid` etc. leem os bits corretos. Os dois
`Writer` gravam cabeçalho com contagem zero, escrevem o corpo e reescrevem o
cabeçalho no `close` — e conferi que um processo morto no meio deixa um arquivo
cujo `size()` não bate com o cabeçalho, que o `read` recusa (não um arquivo que
lê "limpo e vazio"). Os dois recusam relógio andando para trás (`TickFile:240`,
`TapeFile:313`). `TapeFile.read` recusa byte de agressor fora do enum (200-203)
e recusa arquivo sem dicionário de corretoras (216-226) — as duas recusas com o
nome do arquivo na mensagem, como as outras.

**`TapeFile`, os dois bytes.** `LARGEST = 65_535`, e `add` recusa quantidade
e código fora de faixa (323-334) em vez de truncar; a leitura reconstrói com
`& 0xFFFF`. Conferi a ida-e-volta para 65.535 (grava `(short) -1`, lê 65.535) e
para 1. O javadoc (80-88) explica a escolha com o número medido, e o código faz
o que ele diz.

**`TickBars.isTrade` e a linha de marcação de sessão.** O `hasLast && lastAt > 0`
(linha 104) é a correção documentada nas linhas 84-101, e conferi que ela
elimina exatamente as linhas que declaram zero para tudo — não as que declaram
volume zero num negócio real, porque a condição olha o **preço**. `volumeAt`
(138-147) pergunta `hasVolume` antes, devolvendo NaN quando a linha não trouxe
tamanho: a regra "zero é uma afirmação" respeitada também aqui.

**`TickLibrary.keep`: descarte pela distância, não pelo uso.** Segui o laço
(276-291) para o caso do replay andando para frente: com foco em D e residentes
D-1, D, D+1, a chegada de D+2 descarta D-1, que é o certo. Confirmei que
`RESIDENT` é respeitado como teto (o `while`, não um `if`) e que o desempate
pega o primeiro dos mais distantes, o que é determinístico dada a ordem de
inserção.

**`TickSource`: caminho computado e leitura por switch.** `fileFor` (112-118) é
o único lugar que transforma data em caminho, com pasta por fonte e extensão
distinta — então os dois formatos não colidem para o mesmo pregão, que é o que o
javadoc (98-110) promete. O `switch` de `read` é exaustivo sobre o enum, então
uma terceira fonte não compila sem dizer como se lê.

**`Aggregation`: contrato fraco de propósito.** Conferi que os três
implementadores citados no javadoc (tempo, renko, ticks sintéticos) realmente
não caberiam num contrato de agrupamento, e que `none()` (linha 69-71) trata
null devolvendo vazio em vez de propagar.

**O domínio não importa a interface.** `grep` por `import
br.com.jorge.reis.endeavourneo.ui` nos vinte arquivos: zero. As referências a
Swing que existem estão do outro lado (`ReplaySession` chamando
`TickLibrary.onLoaded`), no sentido permitido. A convenção 4 vale para esta área.

**Zero dependência de runtime fora do JDK.** Os vinte arquivos importam só
`java.*`. Convenção 5 atendida.

**Classes utilitárias recusam instanciação.** `Sessions:53`, `MarketFile:75`,
`SeriesMerge:48`, `TickFile:91`, `TapeFile:113`, `FoldedTicks:70`,
`MetaTraderTicks:59`, `ProfitTrades:76` — as oito com
`throw new AssertionError("Utility class must not be instantiated")`. Convenção 7
atendida.

**Idioma.** Comentário e javadoc em inglês nos vinte arquivos, código em inglês.
Convenção 9 atendida. O único texto de usuário que sai do pacote é o
`System.err` de B1-23, e as mensagens de exceção — que não são texto de tela.

**Onde procurei e não achei.** (a) Leitura de futuro: nenhum dos vinte arquivos
lê índice maior que o corrente; `Timeframe.fold` só avança, `TickBars.countUntil`
é estritamente "antes de", `SegmentedSeries` traduz para trás. (b) Recurso não
fechado: todo `FileChannel` e `InputStream` da área está em try-with-resources
(`MarketFile` 81/88/112/164/230/278, `TickFile` 101/112/133/148,
`TapeFile` 128, `MetaTraderTicks` 87, `ProfitTrades` 213), e os dois `Writer`
são `AutoCloseable`; a única falha de fechamento é a dupla de B1-11, que é o
oposto. (c) `Timeframe` sem `equals`/`hashCode`: procurei quem compararia duas
instâncias — a persistência de escala vai por texto (`PeriodCatalog.byCode`,
linha 162-172, compara `choice.code()` com `equalsIgnoreCase`), e nenhum
`Set`/`Map` de `Timeframe` existe. **Refutado, não entra.** (d) `synchronized`
no caminho de pintura: `Sessions` sincroniza só o mapa e nunca a varredura
(linhas 76-89 explicam), `TickLibrary.at` sincroniza um `get`. Nenhum cadeado
segurado durante trabalho longo. Convenção 8 atendida nesta área.
