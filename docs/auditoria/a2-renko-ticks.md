# A2 — domain/market: renko, ticks e replay

**Arquivos lidos integralmente:** 12 (~2.996 linhas)
**Achados:** 2 ALTA, 12 MÉDIA, 13 BAIXA

Arquivos lidos, linha a linha: `Aggressor.java` (93), `Counted.java` (61),
`MetaTraderTicks.java` (257), `ProfitTrades.java` (383), `Renko.java` (642),
`ReplaySeries.java` (381), `SyntheticTicks.java` (170), `TickBars.java` (196),
`TickRenko.java` (423), `TickSeries.java` (153), `TradeTally.java` (159),
`Untraded.java` (78).

Lidos como apoio, não auditados: `RecordedTicks.java`, `TickFile.java`,
`TapeFile.java`, `PeriodCatalog.java`, `ChartCanvas.java`, `ReplaySession.java`
e os sete testes de renko/replay.

---

## As sete regras do renko, conferidas uma a uma

| regra | confere? | onde |
|---|---|---|
| 1. caixa = (n−1) × tick | **sim** | `PeriodCatalog.java:74-76` — `return (name - 1) * TICK;`. O domínio recebe a altura já em pontos, de propósito (`PeriodCatalog.Choice.title`, linhas 95-101). Teste com dentes: `RenkoTest.java:439` confere os quatro tamanhos lidos do Profit |
| 2. fronteiras numa grade absoluta ancorada no zero | **sim** | `Renko.java:297-299` — `Math.floor(price / brick) * brick`, aplicado só ao primeiro âncora em `Renko.java:357` |
| 3. a régua NÃO reinicia por pregão | **sim** | `Renko.Carry` (`Renko.java:220-222`) + `applyFrom(source, from)` em `Renko.java:357-371`; `TickRenko.fold` guarda o carry entre sessões em `TickRenko.java:311-313` |
| 4. fecha só quando PASSA o nível — `ceil(moved/brick − eps) − 1` | **sim** | `Renko.java:325-333` — `return (int) Math.ceil(moved / brick - 1e-9) - 1;`. Coberto por `RenkoGapTest.java:222-224` ("a move of exactly one brick does not close one"). Ver A2-13: o nome de um teste ainda afirma o contrário |
| 5. reversão desenha `steps − (reversal−1)`, deslocada `(reversal−1)` | **sim** | `Renko.java:445-450` (subida) e `Renko.java:464-469` (descida) — `int skip = upNeeded - 1; int count = upSteps - skip;` e `anchor + skip * brick` |
| 6. contagem por janela de TEMPO, não por faixa de preço | **sim no código, não na documentação** | `Renko.java:388` (`tally.seeing`), `Renko.java:488` (`tally.add` depois das caixas), `Renko.java:587` (`long trades = tally.trades();`). Nenhuma faixa de preço aparece no código. Mas o javadoc de `Renko.settle` e o de `Untraded` ainda descrevem a regra por faixa — **A2-4** |
| 7. a primeira caixa leva o acumulador inteiro; as demais, zero | **sim no código, não na documentação** | `Renko.java:545` (`laydown` põe `0.0`), `Renko.java:594` (`bricks.get(at)[4] = tally.volume();`), `Renko.java:604` (`long mine = b == at ? trades : 0L;`). Mas o javadoc da classe e o de `Untraded` afirmam que o volume é **repartido** — **A2-3** |

Nenhuma das sete regras diverge no **código**. Duas divergem na **documentação**,
e a documentação desta área é longa, afirmativa e cita medições — é lida como
especificação. Por isso A2-3 e A2-4 estão em MÉDIA e não em BAIXA.

---

## Achados ALTA

### A2-1. O replay congela para sempre quando um minuto não tem caminho de preços, e o comentário afirma o contrário

`ReplaySeries.java:173-213`

```java
        while (owed > 0) {
            if (path == null && !startForming()) {
                owed = 0;

                return;
            }
```

```java
    private boolean startForming() {
        if (completed >= day.size()) {
            return false;
        }

        path = ticks.pathFor(day, completed);

        if (path == null || path.length == 0) {
            // No path for this bar, and none invented. Happens where there are
            // no recorded ticks and the reader has turned the synthetic ones
            // off: the bar then appears whole instead of forming, which is the
            // honest picture of what is known about it.
            return false;
        }
```

**Problema.** `startForming()` devolve `false` por duas razões diferentes e o
chamador trata as duas do mesmo jeito. Quando o dia acabou (`completed >=
day.size()`), parar está certo. Quando não há caminho, o comentário diz que "a
barra então aparece inteira em vez de se formar" — e o código **não faz isso**:
`completed` não é incrementado, `path` continua nulo, `owed` é zerado e a chamada
retorna. A próxima chamada repete exatamente o mesmo. A barra nunca aparece.

O caminho nulo é alcançável em produção. `ReplaySession.java:253-255` monta o
`RecordedTicks` com um *fallback* que devolve `null` quando o leitor desligou os
ticks sintéticos:

```java
                new RecordedTicks(ticks, (bars, index) ->
                        ...ChartPreferences.syntheticTicks()
                                ? invented.pathFor(bars, index) : null));
```

e `RecordedTicks.java:79-99` devolve o *fallback* (portanto `null`) sempre que a
sessão não está na biblioteca de ticks **ou** o minuto tem menos de dois negócios
— "acontece nas bordas de uma sessão e nos dias em que a bolsa mal abriu".

**Consequência.** Com os ticks sintéticos desligados — a preferência existe
justamente para "um leitor que prefere não ver nada a ver um palpite" — o replay
para de andar no primeiro minuto sem ticks gravados e não volta mais.
`finished()` (`ReplaySeries.java:140-142`) devolve `false` porque `completed <
day.size()`, então o `Timer` de `ReplaySession.tick()` continua rodando e
`announce()` continua disparando: a interface fica viva, o botão de play fica
apertado, e nada se move. Como a base de ticks cobre um mês de 2021 e a série vai
de 2018 a 2026, esse é o caso comum, não o raro.

**Correção.** Separar os dois motivos. Quando não há caminho, revelar a barra
inteira, que é o que o comentário promete:

```java
            if (path == null && !startForming()) {
                if (completed < day.size() && owed >= barMillis) {
                    completed++;
                    owed -= barMillis;
                    continue;
                }

                owed = 0;
                return;
            }
```

**Tentei refutar assim:** (a) Procurei um teste que exercitasse o caminho nulo.
`ReplaySeriesTest.theClockMovesInsideTheBar` (linha 178) passa uma lambda
`(day, index) -> new double[]{100, ...}` que nunca devolve nulo; nenhum outro
teste passa um `TickPath` que devolva nulo. Não há cobertura. (b) Procurei um
tratamento acima, no `ReplaySession`: `tick()` (linha 706) só chama
`advanceMarketTime` e testa `finished()`; não há relógio de guarda nem detecção
de parada. (c) Verifiquei se `RecordedTicks` alguma vez devolve nulo com o
*fallback* presente — não devolve (`SyntheticTicks.pathFor` sempre monta pelo
menos quatro preços), então o defeito só aparece com a preferência desligada; isso
reduz a frequência, não a gravidade, porque a preferência é exposta ao leitor.

---

### A2-2. O segundo extremo de uma barra que assentou caixa é apagado da calda corrente

`Renko.java:419-484`

```java
            boolean lowFirst = direction >= 0;
            int made = 0;

            for (int step = 0; step < 2; step++) {
```

```java
                if (made > 0) {
                    sinceLow = anchor;
                    sinceHigh = anchor;
                }
            }
```

**Problema.** `made` é declarado **fora** do laço dos dois extremos e nunca é
zerado entre eles. O reinício `sinceLow = sinceHigh = anchor` deveria valer para
o passo que assentou caixas — e vale também para o passo seguinte, que não
assentou nada. Resultado: quando o primeiro extremo da barra assenta caixa, o
segundo extremo é dobrado em `sinceLow`/`sinceHigh` (linhas 425 e 428) e logo em
seguida jogado fora pelo mesmo `if (made > 0)`.

Traço completo, `new Renko(10, 2)`, barras como `{open, high, low, close}`:

- `{100,100,100,100}` — âncora 100 (grade), direção 0.
- `{100,115,100,115}` — sobe uma caixa 100→110, âncora 110, direção +1;
  `sinceLow = sinceHigh = 110`.
- `{110,108,85,108}` — direção +1, logo `lowFirst = true`.
  - passo 0 (mínima 85): `downSteps = steps(25) = 2 >= downNeeded = 2` → assenta
    a caixa de reversão 100→90, âncora 90, direção −1, `made = 1`;
    `sinceLow = sinceHigh = 90`.
  - passo 1 (máxima 108): `sinceHigh = max(90, 108) = 108`;
    `upSteps = steps(18) = 1`, mas `upNeeded = reversal = 2`, então nada é
    assentado. Aí `if (made > 0)` — ainda 1, do passo 0 — **zera `sinceHigh` de
    volta para 90**. Os 108 somem.
- `{90,95,78,78}` — direção −1, `lowFirst = false`.
  - passo 0 (máxima 95): `sinceHigh = 95`.
  - passo 1 (mínima 78): assenta 90→80, e a calda superior sai
    `max(90, sinceHigh) = 95`.

A caixa 90→80 desenha calda até 95. O preço realmente chegou a 108 depois da
caixa anterior, dentro do limite de reversão (2 × 10 = 20 acima da âncora 90). A
calda verdadeira é 108.

**Consequência.** Toda caixa cuja calda deveria vir do segundo extremo de uma
barra que já assentou algo sai **curta**. A classe diz que a calda existe para
mostrar que "o movimento foi disputado — que o preço foi trinta pontos para o
outro lado antes de passar" (`Renko.java:59-64`, 116-120): o gráfico passa a
subestimar essa disputa sem nada indicar. E não é só desenho: `highAt`/`lowAt`
das caixas alimentam qualquer *overlay* calculado sobre a série de renko.

**Correção.** Tornar `made` por passo, mantendo o total se ele for preciso em
outro lugar (não é):

```java
            for (int step = 0; step < 2; step++) {
                int made = 0;
                ...
                if (made > 0) {
                    sinceLow = anchor;
                    sinceHigh = anchor;
                }
            }
```

**Tentei refutar assim:** (a) `RenkoWickBoundsTest` existe exatamente para as
caldas — e os três testes só checam **limites superiores**:
`against(bricks, i) > reversal * brick` e `beyond(bricks, i) > 1e-6`. Uma calda
curta demais satisfaz os dois. O terceiro,
`theOtherExtremeIsNotYetReached`, afirma `bricks.lowAt(up) >= 80.0 - 2 * 10` —
também um piso, também satisfeito por uma calda encurtada. Nenhum teste tem
dentes contra calda curta. (b) Reli o comentário de 400-417, que justifica o
reinício: ele explica por que o segundo extremo não pode entrar na calda das
caixas **já assentadas** ("um preço que, no caminho suposto, ainda não tinha
acontecido") — e isso continua correto e é o que o `settle`/`wicks` faz na hora.
Não explica jogar o extremo fora para a caixa **seguinte**, que no caminho
suposto vem depois dele. (c) Rodei o traço acima duas vezes conferindo cada
`steps()` e cada `upNeeded`/`downNeeded`; o resultado se mantém. (d) Não consegui
compilar para confirmar em execução: só há JRE 1.8 nesta máquina
(`javac: command not found`), e o projeto é Java 17. O traço é manual.

---

## Achados MÉDIA

### A2-3. O javadoc afirma, três vezes, que o volume é repartido entre as caixas do lote — a regra 7 e o código dizem o oposto

`Renko.java:72-74`

```java
 * <p>Volume is accumulated between bricks and split equally among however many
 * complete at once. That split is a convention, not a measurement: the data does
 * not say which part of a minute's volume belonged to which brick.</p>
```

`Renko.java:87-89`

```java
 *   <li><b>It gives all the volume to the first brick</b> of a batch and zero to
 *       the rest. Zero is as much a claim as a share is; we spread it, and say
 *       here that it is a convention.</li>
```

`Untraded.java:54-57`

```java
 * <p>This is measured from prices, not from volume. Volume is spread across a
 * batch of bricks by a convention the data does not support (see {@link
 * Renko}), so a grey brick can still show a share of it — the count is what is
 * measured and the share is what is guessed.</p>
```

**Problema.** O código faz o contrário: `laydown` (`Renko.java:545`) grava `0.0`
no campo de volume de toda caixa, e `settle` (`Renko.java:594`) escreve
`bricks.get(at)[4] = tally.volume()` só na primeira. Isto é, o programa hoje faz
exatamente aquilo que o javadoc atribui ao ta4j e diz ter recusado. A terceira
citação chega a construir uma consequência falsa: "uma caixa cinza ainda pode
mostrar uma fração dele" — uma caixa cinza mostra volume zero.

**Consequência.** Quem for medir volume por caixa lê o javadoc, acredita que os
números estão repartidos, e divide de novo — ou, pior, conclui que uma caixa de
gap com volume zero é um defeito e "conserta" na direção errada, desfazendo a
regra 7. A área já perdeu uma medição publicada por causa de uma suposição
parecida (`TickBars.java:89-101`).

**Correção.** Trocar os três parágrafos pela regra medida: o primeiro do lote leva
o acumulador inteiro, os demais levam zero, porque só o primeiro foi a caixa que
estava sendo construída — que é o que `TradeTally` (linhas 48-58) já explica
corretamente para a contagem.

**Tentei refutar assim:** Procurei um teste de repartição de volume — não existe;
`RenkoTest.absentVolumeStaysAbsent` (linha 417) só confere que ausência continua
ausente. Procurei outro caminho que repartisse o volume depois: `assemble`
(`Renko.java:627-638`) copia `one[4]` como está, e `TickRenko.fold`
(`TickRenko.java:321-322`) também. Não há repartição em lugar nenhum.

---

### A2-4. O javadoc de `settle` e o de `Untraded` ainda descrevem a regra por FAIXA DE PREÇO, que é o defeito já corrigido — e listam parâmetros que não existem

`Renko.java:556-579`

```java
     * @param at where this batch starts in {@code bricks}
     * @param barLow the low of the bar that laid it
     * @param barHigh its high
     * @param coverLow the lowest price traded since the previous brick
     * @param coverHigh the highest
     *
     * <p><b>A brick is a band of price, and the question is whether anybody
     * traded inside that band.</b> Nothing else: not how far the bar moved,
     * not which extreme laid the brick, not how many bricks came at once.
```

A assinatura logo abaixo é:

```java
    private void settle(List<double[]> bricks, List<Long> stamps,
                        List<Boolean> untraded, List<Long> counts, int at,
                        TradeTally tally) {
```

`Untraded.java:38-52`

```java
 * <p><b>A brick is a band of price. It is traded when somebody traded inside
 * that band, and untraded when nobody did.</b> That is the whole rule.
```

```java
 * <p>A band owns its bottom edge and not its top, because every boundary is
 * shared by two bricks and a price on one has to belong to exactly one of
 * them.
```

**Problema.** Quatro `@param` — `barLow`, `barHigh`, `coverLow`, `coverHigh` —
sobraram de uma assinatura que não existe mais. E a regra que os dois javadocs
enunciam é a regra por faixa de preço, refutada e medida como errada: a marca de
"não negociada" hoje sai de `long mine = b == at ? trades : 0L; untraded.set(b,
mine == 0);` (`Renko.java:604-607`), isto é, da janela de tempo, igual à
contagem. Não existe mais nenhum código que compare preço com faixa, e portanto
"uma faixa é dona da sua borda de baixo e não da de cima" não descreve nada.

**Consequência.** `Untraded` é a interface pública que documenta a marca cinza. O
próximo leitor que precisar mexer nisso vai reimplementar a comparação por faixa
— que é literalmente o defeito medido de que "caixas contavam negócios fora do
próprio corpo". O javadoc é a única especificação escrita e ela aponta para o
lado errado.

**Correção.** Apagar os quatro `@param` mortos e reescrever os dois blocos com a
regra por janela de tempo, apontando para `TradeTally`, que já a descreve certo.

**Tentei refutar assim:** Procurei se `settle` recebe as faixas por outra via —
recebe só `at` e `tally`; nenhum preço entra. Procurei um teste que dependa da
faixa: `RenkoGapTest.aPriceOnTheEdgeStaysBelow` (linha 221) tem nome de regra por
faixa mas afirma coisa de janela de tempo — `assertEquals("#.", marks(bricks))`,
que é "o primeiro leva tudo". Ou seja, nem o teste sustenta a documentação.

---

### A2-5. O primeiro javadoc de `TickRenko.advance` está órfão E mente: diz que a sessão NÃO é marcada como dobrada, e a linha 192 marca

`TickRenko.java:145-154`

```java
    /**
     * Folds in the part of a session that had happened by then.
     *
     * @param when the instant the replay has reached
     * @return whether anything was added
     *
     * <p>For the session being played: only the ticks up to the moment on the
     * clock. The session is NOT marked as folded, because the rest of it is
     * still to come — call {@link #add} once the day is over.</p>
     */
    /**
     * Lays whatever the market has printed since the last call.
```

`TickRenko.java:189-192`

```java
            // Marked as folded so a later add() of the same day cannot lay it
            // a second time on top of what advance() already laid.
            folded.add(day);
```

**Problema.** Dois blocos `/** */` seguidos antes do mesmo método: o Java só liga
o último, então o primeiro é descartado pelo javadoc e fica invisível a quem lê a
documentação gerada — mas bem visível a quem lê o fonte. E o que ele diz é o
oposto do que o código faz: manda "chamar `add` quando o dia acabar", e `add`
(`TickRenko.java:114-116`) devolve `false` de saída para um dia já em `folded`.

**Consequência.** Seguir a instrução do javadoc não produz erro nenhum — produz
silêncio. `add(day)` devolve `false`, nenhuma caixa é assentada, o rabo da sessão
some e nada indica. Ver A2-8, que é o mesmo buraco visto pelo lado do resultado.

**Correção.** Apagar o bloco órfão (o texto que ele carrega já está errado) e
deixar só o segundo javadoc, acrescentando nele que a sessão fica marcada e por
quê.

**Tentei refutar assim:** Conferi que o Java realmente descarta o primeiro de dois
javadocs consecutivos — descarta; só o comentário imediatamente anterior à
declaração é associado. Conferi se `add` tem uma saída para dia parcial: não tem,
`folded.contains(day)` é a primeira linha. O mesmo padrão de javadoc duplicado
aparece em `ChartCanvas.java:1211-1228` (fora da minha área).

---

### A2-6. O javadoc de `applyFrom` — que carrega a propriedade em que a construção sessão a sessão se apoia — está órfão sobre `gridUnder`

`Renko.java:264-297`

```java
    /**
     * @param from where a previous stretch left the renko, or null to begin
     * @return the bricks this source laid, and where the renko now stands
     *
     * <p>Splitting a source in two and running this over each half gives the
     * same bricks as running it over the whole. That is the property the
     * session-by-session build rests on, and it is a test.</p>
     */
    /**
     * @return the grid level at or below that price
     ...
     */
    private double gridUnder(double price) {
```

**Problema.** Mesmo padrão do A2-5. `applyFrom` está declarado 38 linhas abaixo
(`Renko.java:335`) e fica **sem javadoc nenhum**, apesar de ser o método público
central da classe e o único ponto onde `Carry` entra e sai. Os dois `@param
from` / `@return` órfãos ainda descrevem uma assinatura (`gridUnder(double
price)`) que não tem parâmetro `from` — `-Xdoclint` reclamaria se o bloco fosse o
associado; como não é, nem isso avisa.

**Consequência.** A propriedade "metade + metade = inteiro" é o que autoriza
`TickRenko` a jogar cada sessão fora depois de dobrá-la (`TickRenko.java:30-41`).
Ela sumiu da documentação do método que a garante. Quem for otimizar `applyFrom`
não é avisado de que essa propriedade existe, e `RenkoContinuedTest` só a checa
para dois cortes num passeio aleatório.

**Correção.** Mover o bloco de 264-271 para logo antes de `Renko.java:335`.

**Tentei refutar assim:** Verifiquei se `applyFrom` tem javadoc em outro lugar —
não tem; a linha 335 vem direto depois do fecho de `steps`. Verifiquei se
`Aggregation` documenta `apply` de forma a cobrir isso — `Aggregation.java` só
tem `label()` e `none()` no trecho relevante; `apply` não fala de carry.

---

### A2-7. `ReplaySeries.clock()` anda para trás quase uma barra inteira quando uma barra termina

`ReplaySeries.java:288-307`

```java
        long start = day.timeAt(size() - 1);

        if (path == null || path.length <= 1) {
            return start;
        }

        return start + (long) ((double) cursor / path.length * barMillis);
```

**Problema.** Com uma barra em formação, `size() - 1 == completed` e o relógio é
`início da barra + fração`. Assim que `step()` (`ReplaySeries.java:215-225`)
termina a barra — `completed++; path = null;` — `size() - 1` passa a ser
`completed - 1`, a **mesma** barra, e o `if (path == null)` devolve o `start`
dela sem fração. O relógio recua até quase `barMillis`.

Isso acontece sempre que `owed` chega exatamente a zero no passo que fecha a
barra: o `while (owed > 0)` sai com `path == null` em vez de iniciar a barra
seguinte na mesma chamada. Com minutos movimentados `perPrice` vale
`Math.max(1L, barMillis / path.length)` = 1 (`ReplaySeries.java:180`), então
`owed` sempre drena até zero e a saída com `path == null` é a regra, não a
exceção.

**Consequência.** Duas. (a) O relógio do replay, que o cabeçalho mostra, pisca
para trás um minuto. (b) `ChartCanvas.extendBricks` (linha 1253-1264) monta
`dayOfClock()` a partir desse relógio; na virada de sessão o dia pode voltar ao
anterior, cair no `day.isBefore(growing.advancing())` e devolver `false`,
derrubando o chart para as caixas de candle naquele quadro. Nada disso é
sinalizado.

**Correção.** Com `path == null` e alguma barra completa, devolver o **fim** da
última barra completa, que é o instante corrente e não o futuro:

```java
        if (path == null) {
            return size() == 0 ? day.timeAt(0) : day.timeAt(size() - 1) + barMillis;
        }
```

**Tentei refutar assim:** (a) `ReplaySeriesTest.theClockMovesInsideTheBar` avança
15.000 ms duas vezes numa barra de 60.000 com caminho de 8 preços — `perPrice` =
7.500, o cursor vai a 2 e depois a 4, nunca cruza o fim da barra. Não cobre.
(b) Procurei um amortecedor no chamador: `ChartCanvas.clockNow()` (linha 1229)
devolve `live.clock()` cru, sem memória do último valor. (c) Verifiquei se o
recuo pode fazer o renko de ticks assentar caixa duas vezes — não pode:
`TickRenko.advance` devolve `false` quando `upTo <= advanced`
(`TickRenko.java:201`). O prejuízo é o relógio e a queda para candles, não caixa
duplicada.

---

### A2-8. O rabo da sessão que estava sendo avançada nunca é dobrado quando o replay entra no dia seguinte

`TickRenko.java:174-193`

```java
    public boolean advance(LocalDate day, long when) throws IOException {
        if (!day.equals(advancing)) {
            ...
            TickSeries session = library.load(day);

            advancing = day;
            advanced = 0;
            advancingBars = session == null || session.size() == 0
                    ? null : TickBars.of(session);

            // Marked as folded so a later add() of the same day cannot lay it
            // a second time on top of what advance() already laid.
            folded.add(day);
        }
```

**Problema.** Ao trocar de dia, o estado do dia anterior (`advancingBars`,
`advanced`) é substituído sem que os negócios entre `advanced` e o fim da sessão
sejam dobrados. E como o dia anterior já entrou em `folded`, `add()` recusa
completá-lo (`TickRenko.java:114-116`), exatamente como o javadoc órfão do A2-5
mandaria fazer.

**Consequência.** Os negócios do último trecho de cada pregão — do instante em
que o relógio do replay parou dentro da última barra até o fim do arquivo,
incluindo o leilão de fechamento — não assentam caixa nenhuma, e o `Carry` segue
para o dia seguinte de um lugar que não é onde o mercado realmente parou. A
régua fica deslocada para todo o resto do replay, sem marca. A quantidade é
limitada (um minuto de ticks mais o que houver depois da última barra do dia),
mas é justamente o trecho de leilão, que é o de maior volume do dia.

**Correção.** Ao detectar a troca de dia, dobrar o que sobrou antes de trocar:

```java
        if (!day.equals(advancing)) {
            if (advancingBars != null && advanced < advancingBars.size()) {
                fold(advancingBars.range(advanced, advancingBars.size()));
            }
            ...
```

**Tentei refutar assim:** (a) Procurei um teste multi-dia com `advance`:
`TickRenkoTest.advancingInPiecesIsTheSameRenko` (linha 274) avança dentro de UM
dia e compara com `add` do mesmo dia; `thePiecesAgreeWithTheWhole` (linha 84)
usa `add`, não `advance`. Nenhum teste cruza um dia com `advance`. (b) Procurei
uma descarga no chamador: `ChartCanvas.extendBricks` (linha 1241-1264) só chama
`growing.advance(day, now + 1)` com o dia do relógio; não avisa a troca.
(c) Verifiquei se o `now + 1` do chamador já cobre o fim do dia — não: o relógio
para no último instante do dia anterior que o replay chegou a mostrar, e nunca
mais volta lá.

---

### A2-9. `Renko.Carry` não valida invariante, guarda um `TradeTally` mutável, e por isso `equals` é identidade — o teste que compara dois carries só passa por acidente

`Renko.java:220-222`

```java
    public record Carry(double anchor, int direction,
                        double sinceLow, double sinceHigh, double pending,
                        TradeTally tally) { }
```

`RenkoContinuedTest.java:167-169`

```java
        assertEquals(first.carry(), nothing.carry(),
                "an empty session moved the ruler");
```

**Problema.** Três coisas ao mesmo tempo. (a) Construtor compacto ausente: nada
verifica `direction ∈ {-1,0,+1}`, `sinceLow <= sinceHigh`, `tally != null` — e
`applyFrom` (`Renko.java:370`) precisa se defender de `from.tally() == null`, o
que prova que o estado inválido é construível. (b) `TradeTally` é mutável e não é
copiado na entrada do record, então dois `Carry` podem compartilhar o mesmo
acumulador; `applyFrom` compensa com `copy()`, mas a garantia mora no chamador e
não no tipo. (c) `TradeTally` não redefine `equals`/`hashCode`, então
`Carry.equals` gerado pelo record compara *tallies* por identidade: dois carries
com números idênticos são diferentes.

**Consequência.** O teste acima só passa porque `applyFrom` devolve **o mesmo
objeto** `from` no caminho vazio (`Renko.java:336-341`). Ele afirma verificar que
"uma sessão vazia não moveu a régua" e na verdade verifica que o objeto voltou
inalterado — um teste sem dentes. Trocar aquele `return from` por um `Carry`
novo com os mesmos valores, que é uma refatoração inteiramente razoável, quebra o
teste sem que nada tenha piorado. E o `equals` fica indisponível para qualquer
uso real.

**Correção.** Construtor compacto validando direção e ordem dos extremos e
copiando o `tally` (`tally == null ? new TradeTally() : tally.copy()`), e
`equals`/`hashCode` de valor em `TradeTally`. Depois disso o teste passa a
afirmar o que o nome dele diz.

**Tentei refutar assim:** (a) Procurei `equals` em `TradeTally` — as 159 linhas
não têm nenhum; só `copy()`. (b) Procurei outro ponto que dependa da igualdade
de `Carry` — só o teste. (c) Confirmei que o caminho vazio devolve o mesmo
objeto: `Renko.java:338-340`, `from == null ? new Carry(...) : from`. Portanto o
teste passa hoje e passaria mesmo se `applyFrom` estivesse errado em tudo o que
não é identidade.

---

### A2-10. Nada limita quantas caixas uma barra pode assentar; `steps()` satura o `int` em silêncio

`Renko.java:325-333`

```java
        return (int) Math.ceil(moved / brick - 1e-9) - 1;
```

`Renko.java:536-551`

```java
        for (int b = 0; b < count; b++) {
            double open = level;
            double close = level + step * brick;

            bricks.add(new double[]{open, Math.max(open, close), Math.min(open, close), close, 0.0});
```

**Problema.** O construtor só exige `brick > 0` e finito (`Renko.java:141-144`).
Não há teto para `count`. Um `brick` muito pequeno ou um preço fora de faixa faz
`moved / brick` estourar o `int` — o cast satura em `Integer.MAX_VALUE` sem
avisar — e `laydown` entra num laço que aloca um `double[5]` por caixa até
esgotar a memória. Tudo isso na thread da interface, porque o renko de candles é
dobrado em `ChartCanvas.refold` de forma síncrona.

**Consequência.** Trava e/ou `OutOfMemoryError` no lugar de uma mensagem. E o
precedente está documentado dentro da própria área: `TickBars.java:89-94` conta
que linhas de preço zero fizeram "um renko subir de zero a 120.000 assentando
duas mil caixas que nenhum negócio fez". `TickBars.isTrade` filtra aquela fonte
específica; `Renko` continua sem defesa própria, e agora aceita candles, ticks e
tape.

**Correção.** Um teto explícito em `applyFrom`, com exceção nomeada — por exemplo
recusar um lote acima de algumas dezenas de milhares de caixas dizendo o preço e
o `brick` que o produziram. Recusar alto é o que a casa já faz em `ProfitTrades`
("um valor em que ninguém pode confiar é pior que uma conversão que precisa
rodar de novo").

**Tentei refutar assim:** (a) Procurei um teto no chamador: `PeriodCatalog`
limita o *nome* (`SMALLEST_BRICK = 3`, `LARGEST_BRICK = 101`, linhas 84 e 87),
mas `Renko` é público e `new Renko(brick, reversal)` aceita qualquer double
positivo; `RenkoTest.edges` (linha 423) só testa 0 e −5. (b) Procurei se o preço
é validado antes: `TickBars.isTrade` exige `> 0`, mas não exige faixa; um preço
corrompido de 10⁹ passa. (c) Verifiquei se `assemble` limitaria — não, ele só
copia o que já foi alocado.

---

### A2-11. `TickBars.volumeAt` ignora `hasVolume`: "não disse nada" vira zero, contra o contrato explícito de `TickSeries`

`TickBars.java:137-140`

```java
    @Override
    public double volumeAt(int index) {
        return ticks.volumeAt(trades[index]);
    }
```

**Problema.** `TickSeries` existe, entre outras coisas, para não confundir as duas
(`TickSeries.java:25-37`): "todo campo tem um `has` ao lado, e ler um campo que
não está lá é erro de programação e não um número (...) o caso ruim é uma média
que inclui aqueles zeros em silêncio". `TickBars` não consulta `hasVolume`, e
`TickFile.java:452-455` devolve `volume[index]` sem checar presença — que
`MetaTraderTicks.java:209` gravou como `Math.max(volume, 0)`, ou seja, 0 para
campo ausente.

Pior: as duas camadas usam convenções **diferentes** para ausência. `Renko` trata
ausência como `NaN` (`Renko.java:376`, `Double.isFinite(volume)`, e
`assemble` na linha 637 escreve `NaN` quando nada apareceu); `ReplaySeries`
respeita isso (`ReplaySeries.java:358`, devolve `Double.NaN`). `TickBars` é a
única fonte que não pode produzir `NaN` nunca.

**Consequência.** Um negócio sem campo de volume entra na soma da caixa como zero
contratos e é indistinguível de um negócio de zero contratos. `anyVolume` fica
sempre verdadeiro para fonte de ticks, então o renko afirma um volume que pode
estar subestimado, sem marca. É exatamente a "média que inclui aqueles zeros em
silêncio" contra a qual `TickSeries` foi escrita.

**Correção.**

```java
    public double volumeAt(int index) {
        int at = trades[index];

        return ticks.hasVolume(at) ? ticks.volumeAt(at) : Double.NaN;
    }
```

**Tentei refutar assim:** (a) Verifiquei se a fonte garante volume em toda linha
de negócio: `TapeFile` sim (`hasVolume` devolve `true` sempre, linha 492), mas
`TickFile` não — o bit `HAS_VOLUME` existe e é testado (linha 448), logo linhas
sem volume são representáveis e o conversor MetaTrader as produz quando o campo
vem vazio (`MetaTraderTicks.java:211`, `mask(..., volume >= 0)`). (b) Procurei um
filtro em `isTrade` — só checa `hasLast(index) && lastAt(index) > 0`; volume não
entra. (c) A exposição prática é baixa porque o export costuma trazer volume em
toda linha com LAST; por isso MÉDIA e não ALTA.

---

### A2-12. `TickRenko.live()` remonta a série inteira de caixas a cada quadro

`TickRenko.java:340-354`

```java
    public PriceSeries bricks() {
        double[][] rows = bricks.toArray(new double[0][]);
        long[] times = new long[stamps.size()];

        for (int i = 0; i < times.length; i++) {
            times[i] = stamps.get(i);
        }

        java.util.BitSet gaps = (java.util.BitSet) untraded.clone();
        long[] made = new long[counts.size()];

        for (int i = 0; i < made.length; i++) {
            made[i] = counts.get(i);
        }
```

**Problema.** `live()` (linha 230) começa com `PriceSeries settled = bricks();`, e
`ChartCanvas.extendBricks` (linha 1274) chama `live()` a cada quadro do replay.
Cada chamada percorre e copia **todas** as caixas já assentadas: um `double[][]`
de referências, dois `long[]` preenchidos com *unboxing* de `ArrayList<Long>`, e
um clone do `BitSet`. Custo O(n) por quadro, com n crescendo por todo o replay.

**Consequência.** A trinta quadros por segundo, um replay longo transforma um
custo que deveria ser O(caixas novas) em O(todas as caixas) por quadro. A classe
se apresenta como a solução justamente para isso — "dobrar a sessão de novo custa
0,105 s, então refazer a cada quadro pediria 315% de um núcleo"
(`TickRenko.java:163-168`) — e a economia é desfeita uma camada acima. O renko é
construído fora da EDT, mas `live()` é chamado *na* EDT.

**Correção.** Guardar a `PriceSeries` montada num campo e invalidá-la em `fold`;
`live()` então só embrulha a instância guardada com a borda viva.

**Tentei refutar assim:** (a) Confirmei que `live()` chama `bricks()` toda vez —
linha 230, sem cache. (b) Confirmei a frequência da chamada:
`ChartCanvas.java:1274`, `this.series = growing.live();`, dentro de
`extendBricks`, chamado pelo laço de quadros. (c) Estimei a magnitude para ser
honesto: a poucos milhares de caixas (alguns dias a 55 pontos) são dezenas de KB
por quadro — perceptível mas não fatal; o problema é que cresce sem teto com a
extensão do replay. Por isso MÉDIA e não ALTA.

---

### A2-13. Um teste afirma no nome o oposto da regra 4 — "tocar o nível é suficiente" — e a fixture nem exercita o caso discriminante

`RenkoTest.java:169-182`

```java
    @Test
    @DisplayName("a level TOUCHED lays a brick, even if the close comes back")
    void touchingTheLevelIsEnough() {
        // The discriminating case. Price reaches 115 and closes back at 102.
        // Reading closes alone, nothing happened; reading what was reached, the
        // level went through and the brick belongs on the chart.
        PriceSeries bricks = Renko.of(10).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 115, 100, 102}));
```

**Problema.** A regra medida é que tocar **não** basta: `steps` fecha caixa só
quando o preço PASSA o nível (`Renko.java:301-333`, e o texto ali é explícito —
"a primeira versão fechou no negócio 2.464 e mandou os outros cinquenta para
cima"). O nome do teste, o `@DisplayName` e o comentário "o caso discriminante"
afirmam a regra derrubada. A fixture usa 115, que passa de 110 com folga, então o
teste passa sob as duas regras e não discrimina coisa nenhuma.

**Consequência.** Um leitor procurando a regra 4 nos testes encontra primeiro
este, que diz o contrário do que o código faz, e uma "correção" para fazer o
código bater com o nome do teste reintroduz o defeito medido. É a mesma armadilha
que o próprio arquivo já registrou duas vezes
(`RenkoTest.java:388-392` e `RenkoTest.java:234-240`, testes que "passavam
afirmando o oposto").

**Correção.** Renomear para o que a fixture prova ("passar do nível assenta caixa
mesmo que o fechamento volte") e, se o caso discriminante for desejado aqui,
juntar a asserção de nível exato — que hoje mora sozinha em
`RenkoGapTest.java:222-224`.

**Tentei refutar assim:** (a) Procurei se a regra 4 tem cobertura em algum lugar —
tem, `RenkoGapTest.aPriceOnTheEdgeStaysBelow` afirma "a move of exactly one brick
does not close one". Por isso o código não corre risco hoje, e o achado é MÉDIA e
não ALTA. (b) Reconferi a fixture: `Renko.of(10)`, âncora 100, máxima 115 →
`steps(15) = ceil(1,5 − ε) − 1 = 1`. Passa do nível, não toca. O nome está
errado.

---

### A2-14. `Renko.label()` devolve texto de interface em português, de dentro de `domain/`, e escrito errado

`Renko.java:188-197`

```java
    @Override
    public String label() {
        ...
        return height + (wicks ? " renko" : " renko sem calda");
    }
```

**Problema.** Três coisas. (a) É a única cadeia de interface em português no
`domain/` inteiro — conferido: `grep` por `endeavourneo.ui|javax.swing` em
`src/main/java/.../domain/` não devolve nada, o `LayerBoundaryTest` guarda a
fronteira de importação, mas texto de tela escapa por aqui, fora do
`ResourceBundle`. Compare com `Timeframe.label()`
(`Timeframe.java:137-139`), que devolve rótulos neutros ("1m", "5m").
(b) "calda" é xarope; a palavra é "cauda". O erro está congelado também no teste
(`RenkoTest.java:365`). (c) `ChartCanvas.java:806-807` usa
`newPeriod.label()` como rótulo **e** como `periodCode`, e o código é o que
`PeriodCatalog.byCode` tenta reabrir depois de um reinício — "10 renko sem calda"
não casa com nenhum código conhecido, então o gráfico reabre na escala padrão.

**Consequência.** Um erro de português na barra de título, e uma escala que não
volta depois de reiniciar quando o período foi definido por esse caminho.

**Correção.** Devolver algo neutro e estável ("10R" / "10 renko"), deixar a
palavra "sem cauda" para a camada de interface via `Messages`, e ajustar o teste.

**Tentei refutar assim:** (a) Verifiquei se `Renko.label()` chega mesmo à tela —
chega: `ChartCanvas.setPeriod(newPeriod, newPeriod.label(), newPeriod.label())`
na linha 806, e `periodLabel()` é lido por `ChartHeader.java:178` e
`ChartHolder.java:342`. O caminho de `PeriodCatalog` monta o próprio texto e não
usa `label()`, então o defeito só aparece por `setPeriod` direto — mas esse é o
caminho de `setWicks` (`ChartCanvas.java:861`, que preserva o rótulo) e de
qualquer chamada externa. (b) Procurei "cauda" no repositório — não existe;
"calda" existe nas duas ocorrências acima.

---

## Achados BAIXA

- `Renko.java:359`, `Renko.java:377` e `Renko.java:489` — `pending` é uma cópia
  morta de `tally.volume()`: tudo o que a linha 377 acumula é sobrescrito por
  `pending = tally.volume();` no fim de cada barra, e `from.pending()` da linha
  359 também. Dois acumuladores do mesmo número, e um campo redundante em `Carry`.
- `Renko.java:590` — `counts.set(at, trades);` é escrita morta: o laço das linhas
  596-608 grava o mesmo valor em seguida. Só o `stamps.set(at, tally.first())` da
  linha 591 depende daquele `if`.
- `Renko.java:596-608` — quando `tally.summarised()`, o laço percorre o lote
  inteiro só para dar `continue`; a checagem deveria estar antes do laço, e o
  nome `settle` não avisa que pode não assentar nada.
- `Renko.java:344-346` e `TickRenko.java:61,75` — `List<Long>` para carimbos e
  contagens: um `Long` empacotado por caixa (o `List<Boolean>` é grátis, o cache
  de `Boolean` cobre). Com centenas de milhares de caixas num renko de faixa
  longa é lixo evitável; `long[]` com crescimento manual, como `ProfitTrades.Rows`
  já faz, resolveria.
- `Renko.java:589` — `if (!tally.summarised() && trades > 0)` protege o carimbo,
  mas nada garante que `tally.first()` seja `!= Long.MAX_VALUE`; a garantia vem
  indiretamente de `trades > 0`. Vale um comentário, já que `first` é inicializado
  em `Long.MAX_VALUE` (`TradeTally.java:70`).
- `TradeTally.java:125-129` — `seeing` decide "é resumo" por `high != low`. Uma
  candle plana (minuto de preço único) não marca `summarised`, então uma fonte de
  candles cujas primeiras barras sejam planas conta CANDLES como negócios até a
  primeira barra com faixa chegar. Improvável no WIN; latente para outro papel.
- `TradeTally.java:132-134` — `add` chama `seeing` de novo, depois de `Renko` já
  ter chamado na linha 388. Inofensivo e confuso: dá a impressão de que a ordem
  não importa, e o javadoc de `seeing` diz que importa.
- `ReplaySeries.java:162-169` — com `ticks == null`, `advance((int) (millis /
  barMillis))` **descarta** o resto, o que contradiz o javadoc quatro linhas
  acima ("o que sobra é lembrado em vez de descartado (...) descartar o resto
  significaria que nada nunca chegou"). A quadros de 40 ms e barras de 60.000 ms
  o quociente é sempre zero. Só é alcançável por testes hoje
  (`ReplaySession` sempre passa um `TickPath`), mas os construtores públicos
  `ReplaySeries(day, completed)` e `ReplaySeries.of(day)` oferecem esse caminho.
- `ReplaySeries.java:122-130` — `measureBar` lê o intervalo das duas primeiras
  barras. Com `origin > 0` essas duas são histórico de dias anteriores, e se
  caírem sobre a noite ou o fim de semana o replay inteiro roda na velocidade
  errada sem nada indicar.
- `MetaTraderTicks.java:222-242` — `whole()` ignora em silêncio qualquer byte que
  não seja dígito ou ponto: `-5` lê 5, `1e5` lê 15. O irmão `ProfitTrades.number`
  e `ProfitTrades.grouped` lançam exceção no mesmo caso. Duas convenções opostas
  para o mesmo problema, nos dois conversores da mesma pasta.
- `MetaTraderTicks.java:171-180` — data e hora são lidas em deslocamentos FIXOS
  de byte (0-10, 11-23) enquanto todo o resto da linha é localizado por tabulação.
  Uma linha sem os milissegundos produz `millis` errado em silêncio, porque
  `number` pula não-dígitos em vez de recusar.
- `MetaTraderTicks.java:106-112` e `ProfitTrades.java:108-120` — a troca de
  arquivo é disparada por `!date.equals(open)`. Uma data que reapareça não
  contígua abre um segundo `Writer` sobre o mesmo caminho e sobrescreve a
  sessão já escrita, sem aviso. Ambos os conversores confiam na ordenação do
  export sem verificá-la.
- `TickRenko.java:118-132` — para achar a última data dobrada, `add` percorre o
  `LinkedHashSet` inteiro a cada chamada. n é pequeno; um campo `LocalDate last`
  dizia a mesma coisa sem o laço, e o laço faz parecer que a ordem do `Set` é
  garantia semântica.
- `RenkoContinuedTest.java` e `RenkoWickBoundsTest.java` importam
  `br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries`, que vive em
  `src/main` e portanto no artefato de produção: os testes de domínio dependem da
  camada de interface, e um gerador de fixtures viaja no jar.

---

## O que foi auditado e está LIMPO

| o quê | como conferi |
|---|---|
| Regra 4, a fórmula `ceil(moved/brick − eps) − 1` | Li `Renko.steps` inteiro e avaliei à mão: `moved = brick` → 0; `moved = 2·brick` → 1; `moved = 2,5·brick` → 2. Coincide com o exemplo medido do javadoc (nível impresso 51 vezes, negócio 2.515 é que fecha) e com `RenkoGapTest.aPriceOnTheEdgeStaysBelow` |
| Regra 5, o deslocamento da reversão | Tracei `{100,100,100,100} / {95,95,78,80} / {80,125,50,70}` com `new Renko(10,2)` passo a passo: a primeira caixa de alta abre em 90, uma caixa longe da âncora 80, e são 3 e não 4. Bate com `RenkoWickBoundsTest.theOtherExtremeIsNotYetReached` |
| Regra 2, a grade absoluta | `gridUnder` só é chamado em `Renko.java:357`, e só quando `from == null`; toda âncora seguinte é `level + step * brick`, que preserva a grade. `RenkoTest.everyBoundaryIsOnTheGrid` confere os quatro tamanhos lidos do Profit e mais 20 caixas a partir de 189.480 |
| Regra 3, a régua atravessando a noite | Segui o `Carry` de ponta a ponta: `applyFrom` devolve estado, `TickRenko.fold` guarda no campo `carry`, `TickRenko.add` e `advance` passam o mesmo campo. Nenhum caminho reancora por sessão. `TickRenkoTest.anEmptySessionIsHarmless` cobre a sessão quase vazia |
| Regra 7, primeira caixa leva tudo | `laydown` grava `0.0` em `[4]` de toda caixa; `settle` grava `tally.volume()` só em `at`; o laço seguinte nunca toca `[4]`. Para a contagem, `long mine = b == at ? trades : 0L;`. Confirmado também por `RenkoGapTest.openingGapUp`, que espera `"#.........#"` |
| `TradeTally.clear()` não reseta `summarised` | `TradeTally.java:154-158` zera `trades`, `volume` e `first` e deixa `summarised`. O javadoc explica por quê (uma barra pode assentar dois lotes) e `copy()` (linha 86) preserva o campo. Intacto |
| Leitura do futuro em `ReplaySeries` | Conferi que `timeAt`, `openAt`, `highAt`, `lowAt`, `closeAt` e `volumeAt` passam todos por `check(index)` — inclusive nos ramos "forming", porque `forming(index)` chama `check` antes. `size()` é `completed + (path == null ? 0 : 1)` e `completed` é `clamp`ado a `[origin, day.size()]`; `startForming` exige `completed < day.size()`, logo `size() <= day.size()` sempre. `clock()` nunca ultrapassa `início + barMillis` porque `cursor <= path.length − 1` |
| Leitura do futuro em `TickRenko` | `advance` só dobra `advancingBars.range(advanced, countUntil(when))`, e `countUntil` é busca binária por `timeAt(middle) < when` — estritamente anterior. A borda viva vem de `closeAt(upTo − 1)`, dentro do que já chegou. `TickRenkoTest.nothingFromTheFuture` cobre |
| Preços ajustados | Nenhum arquivo dos 12 aplica fator, razão ou divisão por qualquer coisa que não seja `brick`, `tick` ou contagem. `grep` por multiplicação de preço não encontra ajuste. A base crua atravessa intacta |
| `Counted.UNKNOWN` tratado por quem lê | `Renko.assemble` copia `counts.get(i)` cru, incluindo `-1`; `TickRenko.fold` faz `counts.add(Counted.at(laid, i))`, que devolve `UNKNOWN` para série não-`Counted`; a borda viva de `live()` devolve `Counted.UNKNOWN` explicitamente (linha 251). `BarReadout.java:208` trata o desconhecido na interface. `RenkoCountTest.candlesAnswerUnknown` e `plainSeriesCannotCount` cobrem |
| `Untraded` na borda viva | `live()` linha 242-245 e `Renko.applyFrom` linha 514 (`untraded.add(Boolean.FALSE)`) concordam: o tijolo em formação nunca é gap. `RenkoGapTest.formingIsNeverAGap` cobre |
| Classes utilitárias recusam instanciação | `MetaTraderTicks.java:59-61` e `ProfitTrades.java:76-78`, ambas com construtor privado lançando `AssertionError` |
| `domain/` não importa `ui/` nem Swing | `grep -rn "endeavourneo.ui\|javax.swing"` em `src/main/java/.../domain/` devolve zero linhas; `LayerBoundaryTest` guarda a fronteira. `TickRenko` usa `java.util.BitSet` qualificado, não Swing |
| Vazamento de arquivo nos conversores | Ambos usam `try (InputStream ...)`; ambos fecham o `Writer` no caminho normal e o anulam, com `finally` só para o caso de exceção. Nenhum descritor escapa. Único resto: fechar duas vezes se `finish()` lançar depois do `close()` |
| Retenção de memória em `TickBars` | É uma vista: guarda o `TickSeries` e um `int[]` dos índices negociados; `range` copia só o índice. `TickRenko` solta a sessão trocando `advancingBars`. A retenção de 113 MB durante o pregão avançado é deliberada e documentada (`TickRenko.java:169-172`) |
| Ordem e limites em `SyntheticTicks` | `path[0] = open` sempre, cada perna força o próprio destino (`path[at+steps−1] = to`), `each[i] >= 1` garante comprimento >= 4, e `counts` trata faixa zero. `snap` só age nos pontos intermediários, então `open/high/low/close` saem exatos — que é a regra 4 do javadoc da classe, e ela confere |
| Determinismo do caminho sintético | `new Random(seed * 1_000_003L + index)` por barra: reavançar sobre o mesmo minuto redesenha o mesmo minuto, como o javadoc afirma |
| `Aggressor` | Cinco valores, `of(String)` devolve `null` para desconhecido em vez de inventar um sexto, e `ProfitTrades.java:294-298` realmente recusa a conversão nesse caso. Fim a fim, coerente |
| `Renko` imutável e composto | `final`, campos `final`, `withWicks`/`withForming` devolvem instância nova e devolvem `this` quando nada muda. Nenhum estado de instância atravessa `applyFrom` — todo o estado da passada é local ou vai no `Carry` |

---

## Observações sobre a área

A qualidade do código é alta e incomum: quase todo comentário aqui cita uma
medição contra o produto de referência, com data e número. As sete regras do
renko estão **todas** implementadas corretamente — nenhuma diverge no código.

O risco estrutural desta área não é o algoritmo, é a **documentação divergindo do
algoritmo**. São 642 linhas em `Renko.java` das quais boa parte é javadoc
afirmativo, e já há três lugares onde ela descreve regras que foram medidas,
refutadas e substituídas (A2-3, A2-4) ou comportamento que o código não tem
(A2-1, A2-5). Como esse javadoc é a única especificação escrita das regras, e
como ele soa exatamente igual quando está certo e quando está errado, ele é hoje
a via mais provável de reintroduzir um defeito já pago. Sugiro tratar cada bloco
que enuncia uma regra medida como um artefato versionado junto do teste que a
prova, e não como comentário.

O segundo padrão: **dois javadocs consecutivos** antes da mesma declaração
(`Renko.applyFrom`, `TickRenko.advance`, e também `ChartCanvas.extendBricks`,
fora da minha área). É o rastro de uma edição que acrescentou o novo sem apagar o
velho, e nas duas ocorrências da minha área o bloco descartado é o que carrega a
informação errada. Um `-Xdoclint` no build, ou um simples teste de estilo, pega
todos de uma vez.

Merecem segundo olhar, em ordem:

1. **`ReplaySeries.advanceMarketTime`** — o A2-1 é o único achado que trava a
   ferramenta, e as três saídas do laço (`owed = 0; return`, `return` com `owed`
   preservado, e o fim natural do `while`) têm consequências bem diferentes para
   `path`, `completed` e `clock()`. O A2-7 sai da mesma confusão. As dezoito
   linhas desse método merecem uma máquina de estados explícita.
2. **A calda em `Renko.applyFrom`** — o laço dos dois extremos já teve dois
   defeitos corrigidos (documentados em 400-417) e tem um terceiro (A2-2). O
   escopo de `made` e o de `lowFirst` estão errados um em relação ao outro. Vale
   reescrever com o estado por passo explícito, e acrescentar aos testes de calda
   pelo menos uma asserção de **piso** — hoje todos são teto, e por isso nenhum
   viu o A2-2.
3. **A fronteira `TickRenko` ↔ `ChartCanvas`** — o A2-8 mora exatamente ali:
   quem sabe que o dia virou é o chart, quem sabe o que falta dobrar é o renko, e
   nenhum dos dois avisa o outro. Um método `finishDay()` explícito no `TickRenko`
   resolveria e daria onde pendurar um teste.
