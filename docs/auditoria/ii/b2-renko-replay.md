# B2 — renko, ticks sintéticos e replay do domínio

Auditoria II, 07/09/2026. Área B2. Segunda passada, independente da primeira
(`docs/auditoria/*.md` não foi lido).

## O que foi lido

Todas as linhas de, em
`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/`:

| arquivo | linhas |
|---|---|
| `Renko.java` | 735 |
| `TickRenko.java` | 474 |
| `TradeTally.java` | 195 |
| `Untraded.java` | 78 |
| `Counted.java` | 61 |
| `ReplaySeries.java` | 517 |
| `TickPath.java` | 83 |
| `RecordedTicks.java` | 242 |
| `SyntheticTicks.java` | 389 |
| **total** | **2.774** |

Lidos como apoio, para tentar derrubar achados (não auditados): `RenkoGapTest`,
`TickRenkoTest` (trecho), `SyntheticTicksTest` (assinaturas e asserts),
`OneClockTest`, `ArraySeries` (declaração), `ChartCanvas.extendBricks`,
`ReplaySession` (construção do replay).

Aritmética conferida com `awk` onde havia número para bater.

---

# ALTA

### B2-1. O teste do "um relógio só" é unilateral: passa com leitura do futuro

`src/test/java/br/com/jorge/reis/endeavourneo/domain/market/OneClockTest.java:169`

```java
                assertTrue(replay.highAt(0) >= high - 5.0,
                        "frame " + frame + ": the renko has seen " + high
                                + " and the candle is still at " + replay.highAt(0));
                assertTrue(replay.lowAt(0) <= low + 5.0,
```

**Problema.** O comentário do teste afirma medir a propriedade inteira: *"The
property itself, and not one of its symptoms. At every instant the clock reads,
what the candle has taken in must be what the renko would have folded: same
trades, same minute, one ruler."* (`OneClockTest.java:136-138`). Os dois asserts
medem só uma metade: que o candle **não está atrás** do renko. Nada mede que o
candle não está **à frente** — isto é, que ele não mostrou preço que ainda não
imprimiu.

Uma regressão que trocasse `when[cursor + 1] <= now` por `when[cursor + 1] <=
now + 5_000` em `ReplaySeries.spendStamped` (linha 281) faria o candle mostrar
cinco segundos de futuro e **os dois asserts continuariam passando** — o candle
só ficaria ainda mais "à frente ou igual". A tolerância de `5.0` não muda isso:
ela é folga no eixo do preço, não no do tempo.

**Consequência.** A classe existe para uma regra do briefing — *"o que não
chegou não pode ser lido"* — e o único teste que afirma guardá-la dá licença
falsa exatamente na direção que importa. O `ReplaySeries` inteiro se justifica
por essa regra (`ReplaySeries.java:33-36`).

**Correção.** Acrescentar o lado que falta no mesmo laço, com o conjunto de
negócios que o relógio autoriza: `assertTrue(replay.highAt(0) <= high + 1e-9)` e
`assertTrue(replay.lowAt(0) >= low - 1e-9)`, e derrubar o `- 5.0`/`+ 5.0` para a
igualdade se o cálculo do `folded` já for exato (é: `countUntil(clock() + 1)`
seleciona `time <= now`, e `spendStamped` toma `when <= now`; os dois conjuntos
são o mesmo). Se a folga for mesmo necessária, ela precisa ser simétrica.

**Tentei refutar assim.** (a) Procurei outro assert no método — não há, o teste
termina na linha 180. (b) Procurei outro teste que cubra o lado do futuro:
`ReplaySeriesTest` cobre `check()` (índice além de `size()`), que é o futuro
*entre barras*, não *dentro* da barra; `theCandleShowsWhatHasPrinted`
(`OneClockTest.java:96`) também só verifica que o candle **alcançou** o pico.
(c) Verifiquei se `check()` sozinho já impediria o vazamento: não — o vazamento
aqui é `highAt(0)` de uma barra que **existe**, com um valor que ainda não
aconteceu; `check()` não olha para dentro da barra. Não derrubei.

### B2-2. `addUpTo` seguido de `add`, como o javadoc manda, assenta o começo do pregão duas vezes

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/TickRenko.java:320-333`

```java
     * <p>For the session being played: only the ticks up to the moment on the
     * clock. The session is NOT marked as folded, because the rest of it is
     * still to come — call {@link #add} once the day is over.</p>
     */
    public boolean addUpTo(LocalDate day, long when) throws IOException {
        TickSeries session = library.load(day);

        if (session == null || session.size() == 0) {
            return false;
        }

        return fold(TickBars.of(session).until(when));
    }
```

**Problema.** `addUpTo` dobra os negócios até `when` e **não** registra o dia em
`folded`. O javadoc instrui a chamar `add(day)` quando o pregão acabar. Mas
`add` não dobra o resto — dobra o pregão **inteiro** (`TickRenko.java:145`:
`return fold(TickBars.of(session));`), partindo do `carry` que já contém a
primeira parte. Não há `until`, nem `from`, nem marca de onde `addUpTo` parou.

**Consequência.** Os tijolos do começo do dia são assentados uma segunda vez, em
cima do que `addUpTo` já assentou, e a régua sai deslocada de todo o percurso
repetido. Isso é resultado errado e invisível: nada no gráfico diz que aconteceu.
O contorno existe (não chamar `add` depois de `addUpTo`) — mas é o javadoc do
método que manda fazer o contrário.

**Correção.** Ou `addUpTo` guarda o ponto de parada por dia e `add` retoma dele
(o mesmo par `advancing`/`advanced` que `advance` já mantém), ou o javadoc passa
a dizer que `addUpTo` é terminal para aquele dia e `add` é proibido depois. A
segunda é mais barata e é o que o código já faz.

**Tentei refutar assim.** (a) Procurei um chamador em produção:
`grep -rn "addUpTo" src/main` só encontra a própria declaração e a citação no
javadoc de `advance`. Só os testes chamam. (b) Procurei uma guarda em `add`:
existe, `if (folded.contains(day)) return false` (linha 117) — e é exatamente a
que `addUpTo` desliga de propósito ao não registrar o dia. (c) Procurei no
`TickRenkoTest`: `nothingFromTheFuture` (linha 126) chama `addUpTo` e nunca
chama `add` depois, então a sequência documentada não é testada. Não derrubei.
Mantenho ALTA por ser resultado errado seguindo o contrato publicado, com a
ressalva registrada de que hoje só testes chamam o método.

---

# MÉDIA

### B2-3. Os números resolvidos de `BUSY` não saem da fórmula que o código usa

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/SyntheticTicks.java:98-106`

```java
    /**
     * The fit: {@code changes = 30,1 × ticks^1,241}.
     *
     * <p>It reads 163 changes for a 4-tick minute against a measured 163, 498
     * for ten against 534, and 2.716 for forty against 2.745.</p>
     */
    private static final double BUSY = 30.1;

    private static final double GROWTH = 1.241;
```

**Problema.** Com `BUSY = 30.1` e `GROWTH = 1.241`, `countFor` calcula:

| amplitude | o javadoc diz que lê | o código lê | medido (o javadoc) |
|---|---|---|---|
| 4 | 163 | **168,2** | 163 |
| 10 | 498 | **524,3** | 534 |
| 40 | 2.716 | **2.929,0** | 2.745 |

Os três números do javadoc são reproduzidos por expoente **≈ 1,2187**
(163,0 / 498,0 / 2.697,7) — não por 1,241. Ou a constante mudou e o javadoc
ficou, ou o javadoc foi calculado de outro ajuste. O expoente do código erra o
medido em +6,7% em 40 ticks; o do javadoc erra −1,7%.

**Consequência.** O único parágrafo que diz de onde vem o parâmetro descreve um
parâmetro diferente do que está lá. É o caso literal do briefing — comentário
que mente sobre número medido — e o número decide quantos preços cada minuto do
replay recebe. Ninguém que releia isso daqui a três meses vai saber qual dos
dois foi medido.

**Correção.** Recalcular os três exemplos com a constante que ficar, e dizer no
javadoc qual das duas foi o ajuste (o resíduo em 40 ticks separa as duas).

**Tentei refutar assim.** (a) Conferi se "ticks" no javadoc podia ser outra
unidade: `countFor` (linha 155) divide `high − low` pelo `tick`, então é
amplitude em ticks, como o javadoc diz. (b) Conferi o arredondamento:
`Math.round(BUSY * Math.pow(ticks, GROWTH))` — 168, 524, 2929; nenhum vira 163,
498 ou 2716. (c) Conferi se o teste pegaria: `SyntheticTicksTest:243-245` aceita
`ten > 420 && ten < 640` e `twenty > 980 && twenty < 1_450` — as duas fórmulas
passam. Não derrubei.

### B2-4. "Os três compartilham um carimbo. Nada mais é verdade" — e o código faz outra coisa

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Renko.java:55-57`

```java
 * <p><b>A brick carries the time of the source bar that completed it.</b> When
 * one minute completes three bricks, the three share a timestamp. Nothing else
 * is true — they really did all happen inside that minute.</p>
```

contra `Renko.java:682-685`:

```java
        if (!tally.summarised() && trades > 0) {
            counts.set(at, trades);
            stamps.set(at, tally.first());
        }
```

**Problema.** Sobre ticks, o primeiro tijolo do lote **não** fica com o horário
da barra que o fechou: `settle` sobrescreve o carimbo com `tally.first()`, o
horário do primeiro negócio *desde o tijolo anterior*. Os três de um lote **não**
compartilham carimbo — o primeiro leva `first()`, os outros levam
`source.timeAt(i)`. E quando a caixa vinha se formando desde a véspera (o caso
que `TradeTally` mede em 02–03/09/2026), `first()` é da **tarde anterior**.

O comportamento do código é o medido contra o produto de referência
(`TradeTally.java:33-46`: a caixa das 17:16:03 é a que o primeiro print da manhã
fecha). A afirmação do javadoc é que está errada — e ela é enfática ("Nothing
else is true"), o que é pior do que ser omissa.

**Consequência.** Quem lê o javadoc conclui que `timeAt(i)` de um tijolo é o
instante em que ele fechou, e não é: pode ser horas antes. Qualquer coisa que
alinhe tijolo com relógio — eixo de tempo, `BarReadout`, cruzamento com outra
escala — parte de uma premissa falsa. Note que o javadoc é verdadeiro para
fonte de candles (`summarised` bloqueia a sobrescrita) e falso para ticks, sem
que nada diga qual dos dois está descrevendo.

**Correção.** Reescrever o parágrafo: o tijolo carrega o instante em que **começou
a ser construído** quando a fonte é negociada uma a um, e o da barra que o
completou quando é candle; e dizer por que (é a leitura do produto de
referência, que já está no javadoc de `TradeTally`).

**Tentei refutar assim.** (a) Verifiquei se `stamps.set` podia ser inalcançável:
não — `RenkoGapTest` inteiro roda por essa via (`trades(...)` produz
`high == low`, logo `summarised == false`). (b) Verifiquei se `tally.first()`
podia coincidir com `source.timeAt(i)`: só quando o lote é fechado pela primeira
barra depois do tijolo anterior; no caso da noite são horas de diferença.
(c) Verifiquei se algum outro javadoc corrige: `TradeTally` explica a contagem,
não o carimbo. Não derrubei.

### B2-5. A regra do "tijolo cinza" documentada não está implementada em lugar nenhum

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Untraded.java:40-49`

```java
 * <p><b>A brick is a band of price. It is traded when somebody traded inside
 * that band, and untraded when nobody did.</b> That is the whole rule. Not how
 * far the bar moved, not which extreme laid the brick, not how many bricks
 * arrived at once — the reference product answers it as a count, and the brick
 * it draws grey reads <i>Contratos Neg: 0,00</i>.</p>
 *
 * <p>A band owns its bottom edge and not its top, because every boundary is
 * shared by two bricks and a price on one has to belong to exactly one of
 * them.</p>
```

contra a única implementação, `Renko.java:689-701`:

```java
        for (int b = at; b < bricks.size(); b++) {
            if (tally.summarised()) {
                continue;
            }

            long mine = b == at ? trades : 0L;

            counts.set(b, mine);
            untraded.set(b, mine == 0);
        }
```

**Problema.** Não há, em nenhum ponto do arquivo, comparação entre o preço de um
negócio e a faixa de um tijolo. `untraded` é decidido por **posição no lote** —
o primeiro leva tudo, os outros não levam nada — que é exatamente a coisa que o
javadoc lista como *não* sendo a regra ("not how many bricks arrived at once").
A frase "a band owns its bottom edge and not its top" descreve uma comparação
que não existe: em `RenkoGapTest.whatWasSeenBeforeCounts` o negócio a 186.000
está na borda inferior da faixa `[186.000, 186.100)` e o teste afirma
`"#........#"` — aquele tijolo sai **cinza**. Os dois parágrafos do javadoc dão
respostas opostas para o mesmo caso.

Segundo sinal, no mesmo arquivo: o javadoc de `settle` (`Renko.java:653-657`)
ainda documenta `@param barLow`, `@param barHigh`, `@param coverLow`,
`@param coverHigh` — os quatro preços que a regra de faixa precisaria e que a
assinatura não tem (ver B2-13). É o fóssil da implementação que o javadoc
descreve e que foi substituída.

Terceiro: sobre candles nenhum tijolo é marcado (`summarised` faz o `continue`),
e é o comportamento testado e querido (`RenkoGapTest`: *"candles are never
marked"*). Mas o javadoc de `Renko` (linhas 66-70) e o de `Untraded` (linhas
26-30) abrem os dois com o mesmo exemplo — *"when the market reopens 1.400
points above where it closed"* — que num gráfico de minutos é justamente o caso
de candle, onde a marca nunca aparece.

**Consequência.** Três textos de especificação descrevem um algoritmo que o
programa não tem, e o programa é o que decide o que sai cinza na tela. Quem for
mexer nisso vai partir do javadoc.

**Correção.** Ou implementar a regra de faixa (os quatro preços do `@param`
fóssil já dizem o que ela precisa, e ela funcionaria também para candles), ou
reescrever os três textos para o que o código faz: o primeiro tijolo do lote
leva o que houver, os outros são cinza, e sobre candles não se afirma nada.

**Tentei refutar assim.** (a) Procurei a regra de faixa em outro lugar:
`grep -rn "Untraded.at|untradedAt" src/main` dá `ArraySeries` (só devolve o
`boolean[]`), `TickRenko` (só repassa), `BarReadout` e `CandleStyle` (só
desenham). A decisão é feita uma vez, em `settle`. (b) Verifiquei se
`RenkoGapTest.steadyClimbIsAllTraded` provaria a regra de faixa: não — naquele
caminho todo lote tem um tijolo só, então "primeiro do lote" e "faixa negociada"
coincidem. (c) Verifiquei o caso da borda inferior no próprio teste do projeto,
citado acima: a regra de faixa e o código discordam. Não derrubei.

### B2-6. `advanceMarketTime` descarta o resto que o próprio javadoc promete guardar

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/ReplaySeries.java:193-206`

```java
     * <p>What is left over is remembered rather than dropped. At one times
     * speed a frame is forty milliseconds and a price arrives every few seconds;
     * discarding the remainder would mean nothing ever arrived at all.</p>
     */
    public void advanceMarketTime(long millis) {
        if (ticks == null) {
            // No tick generator: fall back to whole bars, which is what the
            // chart got before this existed.
            advance((int) Math.max(0, millis / barMillis));

            return;
        }
```

**Problema.** O primeiro ramo do método faz exatamente o que o parágrafo acima
dele diz que seria fatal: `40 / 60_000 == 0`, `advance(0)` não revela nada, e o
resto de 40 ms é perdido. Chamado quadro a quadro, esse ramo **nunca** avança.
`owed`, o campo que existe só para isso, não é tocado aqui.

**Consequência.** Um `ReplaySeries` construído com `ticks == null` — que é o que
o construtor de dois argumentos e `ReplaySeries.of` produzem, e que o javadoc do
construtor descreve como *"null to jump bar by bar"* (linha 100) — fica parado
para sempre no transporte, com o ícone de tocar aceso. É o mesmo sintoma que o
comentário longo das linhas 220-229 descreve como um defeito já corrigido no
outro ramo.

**Correção.** Passar esse ramo por `owed` também: `owed += max(0, millis); int
bars = (int)(owed / barMillis); owed -= bars * barMillis; if (bars > 0)
advance(bars);` — cuidando de que `advance` hoje zera `owed` (linha 364).

**Tentei refutar assim.** (a) Verifiquei se o ramo é alcançável em produção:
`ReplaySession.java:288-289` sempre passa um `TickPath` não nulo (ou
`RecordedTicks`, ou o lambda que devolve `null` como *prices*, o que cai no
outro ramo). Então na aplicação de hoje não trava. (b) Verifiquei se algum teste
cobre: `grep -rn advanceMarketTime src/test` — nenhuma das cinco chamadas usa
uma série com `ticks == null`; `HistoryBeforeReplayTest` constrói com `null` mas
só chama `advance(int)`/`seek`. Ou seja: ramo público, documentado, quebrado e
sem teste. Não derrubei; baixei de ALTA para MÉDIA só porque nenhum caminho de
produção passa por ele hoje.

### B2-7. `startForming` devolve `false` deixando `path` apontando para um vetor vazio

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/ReplaySeries.java:310-324`

```java
        path = timed.prices();
        when = timed.when();
        now = day.timeAt(completed);

        if (path == null || path.length == 0) {
            when = null;
            ...
            return false;
        }
```

**Problema.** O guarda testa `path.length == 0`, mas no ramo de saída zera só o
`when`. `path` fica sendo o vetor de comprimento zero. A partir daí:

- `size()` (linha 453) devolve `completed + (path == null ? 0 : 1)` → **conta uma
  barra em formação que não existe**;
- `forming(index)` passa a valer `true` para `index == completed`, e `highAt` /
  `lowAt` / `closeAt` devolvem `high`/`low`/`close` **da barra anterior**;
- em `advanceMarketTime`, a iteração seguinte não entra em `startForming` (porque
  `path != null`), cai em `long perPrice = Math.max(1L, barMillis / path.length)`
  (linha 250) e **divide por zero**.

**Consequência.** Ou preço da barra errada na tela, ou `ArithmeticException` no
laço do relógio. O autor considerou o caso possível (escreveu o teste
`path.length == 0`); o tratamento é que está pela metade.

**Correção.** `path = null;` junto com `when = null;` no ramo de saída.

**Tentei refutar assim.** (a) Procurei quem pode devolver um vetor vazio hoje:
`SyntheticTicks.pathFor` devolve no mínimo `1 + 3` posições (`steps()` é sempre
≥ 1 por perna — ver LIMPO); `RecordedTicks.timedPathFor` exige `count >= 2`
(linha 98) e o `fallen` devolve `prices == null`, que o guarda trata. Então hoje
é inalcançável — por isso MÉDIA e não ALTA. (b) Verifiquei se algum chamador
normaliza depois: `advanceMarketTime` é o único, e é ele quem divide por zero.
Não derrubei: o defeito é o guarda deixar o objeto inconsistente, e a barreira
que o esconde é uma propriedade de duas outras classes, não deste arquivo.

### B2-8. Ao trocar de pregão, o tijolo em formação de ontem fica na tela

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/TickRenko.java:202-222`

```java
            if (advancingBars == null) {
                // The session that closed is finished, so nothing is being
                // built any more. Leaving the old edge would draw yesterday's
                // half-brick over a day that has not opened.
                forming = null;

                return tail;
            }
        }
        ...
        int upTo = advancingBars.countUntil(when);

        if (upTo <= advanced) {
            ...
            return tail;
        }
```

**Problema.** O comentário nomeia o defeito — desenhar o meio-tijolo de ontem
sobre um dia que não abriu — e o corrige só num dos dois caminhos. Quando o novo
pregão **tem** barras mas o relógio ainda não chegou à primeira,
`countUntil(when)` devolve 0, o `if (upTo <= advanced)` (0 ≤ 0) devolve cedo, e
`forming` continua com a aresta do dia anterior.

**Consequência.** O tijolo em formação desenhado é o de ontem, num pregão novo,
num nível que pode estar do outro lado do gap da noite. E é o `live()` (linha
248) que o serve ao gráfico, quadro a quadro.

**Correção.** No bloco de troca de dia, `forming = null` antes de sair, nos dois
ramos — a aresta é reconstruída no primeiro quadro que dobrar alguma coisa.

**Tentei refutar assim.** (a) Verifiquei quão fácil é chegar em `upTo == 0`:
`ChartCanvas.extendBricks` chama `advance(day, now + 1)` com `now` vindo do
relógio do replay, que só entra no dia novo quando a primeira barra dele começa
a se formar; se o primeiro negócio do arquivo de ticks for depois do carimbo
dessa barra (abertura de pregão contra candle de pré-abertura), `countUntil` dá
0. É um gatilho estreito, e é por isso que está em MÉDIA e não em ALTA.
(b) Verifiquei se `live()` filtra: não — `forming != null` é a única condição
(linha 251). Não derrubei.

### B2-9. A guarda de ordem de `add` lê o último **inserido**, e `advance` insere sem a mesma checagem

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/TickRenko.java:121-135`

```java
        if (!folded.isEmpty()) {
            LocalDate last = null;

            for (LocalDate each : folded) {
                last = each;
            }

            if (last != null && !day.isAfter(last)) {
                // Out of order would put bricks in the wrong sequence AND carry
                // the ruler backwards, and neither is visible in the result --
                // the chart would simply be wrong.
```

**Problema.** `folded` é um `LinkedHashSet`, então o laço pega o **último
inserido**, não o maior. E `advance` insere em `folded` (linha 200) verificando
só `day.isBefore(advancing)` — não a ordem contra o conjunto. Sequência que
passa pelas duas guardas e produz o que elas dizem impedir:
`add(D3)` → `advance(D1, t)` (`advancing == null`, passa; `folded == [D3, D1]`)
→ `add(D2)` (`last == D1`, `D2.isAfter(D1)`, passa) — com D2 anterior a D3, que
já está dentro.

**Consequência.** Exatamente o que o comentário descreve: tijolos fora de
sequência e a régua carregada para trás, sem nada no resultado que diga.

**Correção.** Guardar o maior dia visto num campo (`Comparable.max`) e checá-lo
nos três pontos de entrada — `add`, `advance` e `addUpTo`, que hoje não checa
nada.

**Tentei refutar assim.** (a) Verifiquei se em produção a ordem é sempre
crescente: `ChartCanvas.extendBricks` só chama `advance`, e trata o caso para
trás abandonando o renko (linha 1410), então hoje não se cruza. (b) Verifiquei
se `sessions()` (linha 366) corrige: devolve `List.copyOf(folded)` — ordem de
inserção, portanto propaga o problema em vez de corrigi-lo, e o javadoc diz "in
order". Não derrubei.

### B2-10. "Feito para rodar fora da thread da interface" — e o único chamador de produção é a EDT

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/TickRenko.java:49-50`

```java
 * <p><b>Not thread-safe, and meant to be used off the interface thread.</b>
 * Each {@link #add} reads a file.</p>
```

**Problema.** `ChartCanvas.extendBricks` chama `growing.advance(day, now + 1)`
direto na EDT, e o comentário lá (`ChartCanvas.java:1419-1422`) admite o que isso
custa: *"TickRenko.advance calls load(), which reads the session from disk if it
is not resident -- ninety megabytes, on the interface thread"*. A mitigação é
pedir o dia adiante (`growingFrom.request`), o que reduz a chance mas não muda a
regra da casa (trabalho pesado nunca na EDT) nem torna verdadeira a frase do
javadoc.

**Consequência.** Duas coisas, e a segunda é a que sobra depois de corrigir a
primeira: (i) a leitura de 90 MB pode cair na EDT quando o pedido antecipado não
chegou a tempo; (ii) o javadoc diz o contrário do que o programa faz, então quem
adicionar um segundo chamador vai supor que já está fora da EDT — e as quatro
coleções mutáveis (`bricks`, `stamps`, `counts`, `untraded`) mais o cache
`builtBricks` não têm sincronização nenhuma.

**Correção.** Alinhar javadoc e código: ou dizer que a classe é conduzida pela
EDT e depende de o `TickLibrary` já ter o dia residente, ou tirar `advance` da
EDT. A frase atual não pode ficar como está nos dois casos.

**Tentei refutar assim.** (a) Confirmei o chamador: `ChartCanvas.java:1338`
(`if (growing != null && extendBricks())`) e `1446` (`this.series =
growing.live()`), ambos em caminho de repintura/timer. (b) Verifiquei se há um
`SwingWorker` em volta: há um, mas só para a **construção** inicial
(`ChartCanvas.java:1201-1249`); o `advance` por quadro é síncrono. Não derrubei.

### B2-11. `Carry.pending` e `Carry.tally().volume()` são duas respostas para a mesma pergunta, e nada as mantém iguais

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Renko.java:461-465` e `581-582`

```java
            if (Double.isFinite(volume)) {
                pending += volume;
                anyVolume = true;
            }
...
            tally.add(source, i);
            pending = tally.volume();
```

**Problema.** `pending += volume` é linha morta: `pending` não é lido entre a
linha 463 e a linha 582, e a 582 o sobrescreve ao fim de **toda** iteração. O
valor inicial `from.pending()` (linha 444) também é descartado assim que houver
uma barra. Quem responde de verdade é sempre `tally.volume()`.

Pior: os dois números têm semânticas diferentes. `pending += volume` soma o
volume da barra corrente **antes** dos tijolos; `tally` guarda a barra corrente
para **depois** (linha 581), que é a regra medida e comentada nas linhas 467-473.
E `settle` (linha 687) entrega ao tijolo `tally.volume()`, enquanto `formingAt`
(linha 320) entrega `carry.pending()`.

**Consequência.** O componente `pending` do record é redundante e não é validado
contra o `tally` no construtor compacto. Um `Carry` montado à mão — o construtor
é público — com `pending` diferente de `tally.volume()` faz o tijolo em formação
mostrar um volume e o tijolo que ele vira mostrar outro, sem que nada acuse.

**Correção.** Ou tirar `pending` do record e ler `tally().volume()` em
`formingAt`, ou validar `pending == tally.volume()` no construtor compacto (que
já valida `direction` e `sinceLow <= sinceHigh`). E apagar `pending += volume`,
mantendo o `anyVolume`.

**Tentei refutar assim.** (a) Reli o laço inteiro procurando uma leitura de
`pending` entre as linhas 463 e 582: não há. (b) Verifiquei se o
comportamento de hoje está errado: não está — o resultado final é o de
`tally.volume()`, que é o correto. Por isso MÉDIA (dívida que já causa dois
lugares de verdade), não ALTA. (c) Verifiquei se `Continued`/`applyFrom` corrige
na saída: a linha 619 passa `pending` (== `tally.volume()`) e `tally` juntos,
consistentes; só o caminho de fora, `new Carry(...)`, é que pode divergir. Não
derrubei.

### B2-12. `measureBar` lê só as duas primeiras barras da série

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/ReplaySeries.java:141-149`

```java
    private static long measureBar(PriceSeries day) {
        if (day.size() < 2) {
            return DEFAULT_BAR_MILLIS;
        }

        long gap = day.timeAt(1) - day.timeAt(0);

        return gap > 0 ? gap : DEFAULT_BAR_MILLIS;
    }
```

**Problema.** O javadoc justifica: *"the series knows its own spacing, and a
caller passing the wrong number would make the replay run at the wrong speed"*.
Mas a série **não** é regularmente espaçada — um minuto sem negócio simplesmente
não existe (é o que `Untraded.java:25-27` afirma). Se houver um buraco entre a
barra 0 e a barra 1, `barMillis` sai 120.000 (ou mais) para uma série de um
minuto.

**Consequência.** Três efeitos, todos silenciosos: `end()` (linha 178) rotula o
fim errado do scrubber; `spendStamped` (linha 275) trava o relógio em
`start + barMillis`, então cada barra dura o dobro do que deveria e o relógio
**anda para trás** ao começar a barra seguinte (`clock()` devolve
`start + barMillis` sem barra formando na linha 428, e `now = day.timeAt(
completed)` na linha 314, que pode ser menor); e um `barMillis` *menor* que o
real faria a barra fechar deixando preços para trás.

**Correção.** Ler a mediana (ou o mínimo positivo) das primeiras N diferenças, em
vez do primeiro par; ou tomar a escala da própria `Aggregation` que dobrou a
série, que sabe a resposta sem inferir.

**Tentei refutar assim.** (a) Verifiquei se `ConcatSeries.of(parts)`
(`ReplaySession.java:289`) garante barras consecutivas no começo: não garante
nada — as partes são pregões inteiros e o primeiro par é o primeiro par do
primeiro pregão de história. (b) Verifiquei se `clock()` tem uma proteção de
monotonicidade: não tem; o único cuidado nesse sentido é o comentário das linhas
421-427, que trata de outro caso (o relógio voltar ao *início* da barra
anterior). Não derrubei; a probabilidade é modesta, e é por isso que está em
MÉDIA.

---

# BAIXA

### B2-13. O javadoc de `settle` documenta quatro parâmetros que o método não tem, e omite o que tem

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Renko.java:649-675`

```java
     * @param at where this batch starts in {@code bricks}
     * @param barLow the low of the bar that laid it
     * @param barHigh its high
     * @param coverLow the lowest price traded since the previous brick
     * @param coverHigh the highest
...
    private void settle(List<double[]> bricks, List<Long> stamps,
                        List<Boolean> untraded, List<Long> counts, int at,
                        TradeTally tally) {
```

**Problema.** `barLow`, `barHigh`, `coverLow` e `coverHigh` não existem na
assinatura; `tally`, que existe, não é documentado. **Consequência.** É o fóssil
que prova B2-5 e, por si, javadoc grudado num membro que mudou.
**Correção.** Acertar a lista. **Tentei refutar assim.** Procurei uma sobrecarga
de `settle` que tivesse esses parâmetros: `grep -n "void settle"` dá uma só,
linha 673.

### B2-14. Dois pares de números do javadoc de `SyntheticTicks` discordam entre si

`SyntheticTicks.java:32` diz `5.074 minutes`; a legenda da tabela na linha 37 diz
`the same 5.065 minutes`. `SyntheticTicks.java:39` diz `97,2%` para o passo de um
tick; a linha 58 diz `97,1%` da mesma medição. **Consequência.** Ruído numa
seção que existe para ser citada como medida. **Correção.** Um número por
medição. **Tentei refutar assim.** Verifiquei se "5.065" podia ser um
subconjunto declarado ("the same ... minutes" diz que não) e se 97,1/97,2 podiam
ser medidas diferentes (as duas frases dizem "a step of exactly one tick" /
"changes are a single tick").

### B2-15. As duas primeiras guardas de `draw` são inalcançáveis

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/SyntheticTicks.java:348-355`

```java
    private static int draw(int up, int down, int last, Random random) {
        if (up == 0) {
            return -1;
        }

        if (down == 0 || last == 0) {
```

**Problema.** `draw` só é chamado dentro de `if (rise && fall)`
(`SyntheticTicks.java:296`), e `rise`/`fall` já exigem `up > 0` e `down > 0`
(linhas 292-293). Os testes `up == 0` e `down == 0` nunca dão verdadeiro; só
`last == 0` (primeiro passo da perna) importa. **Consequência.** O javadoc do
método (*"A leg that has spent its up-steps therefore turns down on its own"*)
descreve um mecanismo que na verdade está no laço, não aqui. **Correção.**
Tirar as duas condições mortas e mover a explicação para o laço, ou dizer que
são defesas para chamada futura. **Tentei refutar assim.** Procurei outro
chamador de `draw`: só a linha 297.

### B2-16. `withForming(true)` aloca um `Renko` por quadro para nada

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/TickRenko.java:234`

```java
        forming = carry == null ? null : renko.withForming(true).formingAt(carry, price);
```

**Problema.** `formingAt` (`Renko.java:313-321`) lê `wicks` e os campos do
`carry`; não lê `forming`. `withForming(true)` devolve um `Renko` novo (o campo
`forming` do original é `false`, fixado no construtor da linha 105) com
exatamente o mesmo resultado. **Consequência.** Uma alocação por quadro de
replay, e a linha sugere que o `forming` do `Renko` participa do cálculo — que é
justamente a confusão que `formingAt` existe para acabar ("Here so there is ONE
of it"). **Correção.** `renko.formingAt(carry, price)`. **Tentei refutar
assim.** Reli `formingAt` procurando leitura de `forming`: não há; a única
leitura do campo é `hasForming()` e o `if (forming)` de `applyFrom` (linha 585),
que não passa por aqui.

### B2-17. `Carry` copia o tally na entrada e entrega o original na saída

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Renko.java:273-285`

```java
        public Carry {
            ...
            tally = tally == null ? new TradeTally() : tally.copy();
        }
```

**Problema.** O javadoc do construtor compacto diz: *"applyFrom copied on the
way in and the guarantee lived in the caller; here it lives in the type, where a
caller cannot forget it"*. Mas o acessor gerado `tally()` devolve a referência
mutável, e `TradeTally.add/clear/seeing` são package-private — qualquer classe
do pacote `market` pode esvaziar o tally de um `Carry` alheio.
**Consequência.** A garantia vale numa direção só; é uma guarda que protege
menos do que diz. **Correção.** Ou `tally()` devolve `tally.copy()`, ou o
javadoc diz que a proteção é só na entrada. **Tentei refutar assim.** Procurei
quem chama `carry.tally()`: `Renko.applyFrom:455-456`, que copia antes de
escrever — hoje ninguém abusa. Por isso BAIXA.

### B2-18. `laydown` aloca por tijolo, num laço que roda na EDT

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Renko.java:634-643`

```java
        for (int b = 0; b < count; b++) {
            ...
            bricks.add(new double[]{open, Math.max(open, close), Math.min(open, close), close, 0.0});
            stamps.add(time);
            untraded.add(Boolean.FALSE);
            counts.add(Counted.UNKNOWN);
```

**Problema.** Um `double[5]` mais um `Long` encaixotado por tijolo, mais os
`Long`/`Boolean` de `stamps`/`untraded`/`counts` — e o comentário de `steps()`
(linhas 392-398) já registra que esse mesmo laço, sem teto, foi o que travou a
interface. Com o teto ele ainda pode fazer 100.000 dessas por barra.
**Consequência.** Alocação por elemento em laço quente, contra a convenção da
casa; e `apply()` é chamado sincronamente do `ChartCanvas.refold`.
**Correção.** Vetores primitivos crescentes (`double[]`/`long[]`/`boolean[]` com
duplicação) em vez das quatro listas. **Tentei refutar assim.** Verifiquei se há
um caminho não-EDT: o `TickRenko` roda `applyFrom` por trecho, mas o fold de
candles (`Renko.apply`) é chamado do `refold` da tela. Não derrubei; BAIXA
porque `count` é 1 na esmagadora maioria dos lotes.

### B2-19. `anyVolume` ignora o volume que veio no `carry`

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/Renko.java:446` e `730`

```java
        boolean anyVolume = false;
...
            volumes[i] = anyVolume ? one[4] : Double.NaN;
```

**Problema.** `anyVolume` só é ligado por `source.volumeAt(i)` finito **deste**
trecho. Um trecho cujas barras não trazem volume, continuando um `carry` com
`pending > 0`, devolve `NaN` em todos os tijolos — inclusive no primeiro do
lote, que recebeu volume real de ontem. **Consequência.** Perda silenciosa de um
número que existia. **Correção.** `boolean anyVolume = from != null &&
from.pending() > 0;`. **Tentei refutar assim.** Verifiquei se o caso é
alcançável: `TickRenko` dobra pregão a pregão e um pregão inteiro sem volume é
improvável — daí BAIXA. A regra da casa ("volume ausente é NaN, nunca zero") não
é violada; o defeito é o oposto, NaN onde havia número.

### B2-20. O volume da barra em formação nunca chega ao total

`src/main/java/br/com/jorge/reis/endeavourneo/domain/market/ReplaySeries.java:492-494`

```java
        double whole = day.volumeAt(completed);

        return Double.isFinite(whole) ? whole * cursor / (double) path.length : Double.NaN;
```

**Problema.** `cursor` chega no máximo a `path.length - 1` — no ramo carimbado o
laço é `while (cursor + 1 < path.length ...)` (linha 281) e no não carimbado
`step()` fecha a barra assim que `cursor >= path.length` (linha 337). Então a
fração máxima é `(n-1)/n`. **Consequência.** No último quadro antes de fechar, a
barra mostra um volume um pouco menor do que o dela, e depois salta para o
total; com `n` pequeno (o piso de `SyntheticTicks` é 8) a diferença é de 12%.
**Correção.** Dividir por `path.length - 1`, ou usar a fração de tempo no ramo
carimbado. **Tentei refutar assim.** Verifiquei se `cursor` pode chegar a
`path.length`: no ramo carimbado não, pela condição do laço; no outro, quando
chega a barra já foi fechada e `forming(index)` é falso. Não derrubei.

---

# LIMPO

O que foi conferido e está certo, e como.

**A aritmética do passeio de `SyntheticTicks` (o ponto que o briefing mandou
olhar de perto).** Refeita à mão, perna a perna:

- *orçamento e paridade*: `steps(from,to,wanted)` (linha 245) garante
  `steps >= gap` e `(steps − gap) % 2 == 0`, logo `up = (steps + gaps(aim−from))
  / 2` (linha 275) e `down = steps − up` são ambos ≥ 0 e a divisão inteira nunca
  trunca (o numerador é par e não negativo). `up − down` é exatamente o
  deslocamento pedido.
- *`steps` nunca é zero*: `wanted >= 1` por `share` (linha 206); se
  `landing == 1` então `|gaps(to−from)| > 1` e portanto `gap >= 1`; se
  `landing == 0` então `steps >= wanted >= 1`. O `if (steps > 0)` da linha 324 é
  sempre verdadeiro, e o vetor dimensionado por `lengthOf` (linha 210) bate
  exatamente com o que `walk` escreve (`steps + landing` por perna).
- *o caminho não sai da barra*: em cada perna, `floor` e `ceiling` são `from` ou
  `aim`, e a grade do passeio é ancorada em `from`. A folga de meio tick das
  condições das linhas 292-293 só permitiria sair se o limite viesse de um preço
  fora dessa grade — e nas três pernas o limite do lado perigoso é sempre o
  próprio `from` (perna 2 sobe a partir de `low`, perna 3 desce a partir do
  extremo). No caso degenerado `floor == ceiling`, o alargamento é grampeado em
  `lowest`/`highest` (linhas 284-285). Confere com
  `SyntheticTicksTest.thePathStaysInside`.
- *travamento*: o único jeito de `rise` e `fall` falharem juntos é caixa de
  largura zero, que só ocorre quando `high == low`; o `break` da linha 309 trata
  esse caso preenchendo o resto com o preço único.
- *reprodutibilidade*: `stir` é o finalizador do SplitMix64 e o javadoc explica
  por que a mistura era necessária (a primeira tiragem decide a ordem dos
  extremos); `pathFor` recria a mesma `Random` por índice.

**`Renko.steps` — "fecha quando o preço PASSA o nível".** `Math.ceil(moved /
brick − 1e-9) − 1` (linha 388): um movimento de exatamente um tijolo dá 0, de um
tijolo mais um tick dá 1. Confere com
`RenkoGapTest.aPriceOnTheEdgeStaysBelow`. `NaN` cai no `!(moved > 0.0)` e devolve
0; `Infinity` é barrado por `MOST_BRICKS` antes do cast (o cast de `double`
saturado era o defeito que o comentário registra).

**A grade absoluta e o desconto da reversão.** `gridUnder` (linha 353) só é usado
quando `from == null`, e cada tijolo depois anda exatamente `brick`, então a
régua que começa na grade fica nela. O `skip = upNeeded − 1` (linha 538) reproduz
a leitura do produto de referência citada no comentário; e `upSteps >=
upNeeded` / `downSteps >= downNeeded` são mutuamente exclusivos, porque
`steps(x)` e `steps(−x)` nunca são ambos positivos.

**A cauda e a ordem dos extremos.** `lowFirst = direction >= 0` é fixado antes
dos dois passos, então uma reversão dentro da barra não troca a ordem no meio do
caminho; e cada extremo entra em `sinceLow`/`sinceHigh` só quando chega a vez
dele (linhas 517-523), que é o que impede a cauda de usar um preço que, no
caminho suposto, ainda não aconteceu. Refiz os quatro casos (subindo/descendo ×
lote no primeiro/segundo passo) e o `made` declarado **dentro** do laço
(linha 513) está certo.

**A propriedade "dobrar em pedaços dá o mesmo".** `applyFrom` deriva o `Carry`
do estado e não dos tijolos (linhas 615-619), então o tijolo em formação
acrescentado logo acima não contamina o trecho seguinte. Coberto por
`RenkoGapTest.wholeAndHalvesAgree` e `carriedAcrossSessions`, que têm dentes (a
string de marcas quebra se um único tijolo mudar de lado).

**Monotonicidade dos carimbos dos tijolos.** Preocupava-me que
`stamps.set(at, tally.first())` pudesse pôr um tijolo antes do anterior. Não
pode: `tally` é limpo em cada lote e só recebe barras posteriores, então
`first()` de um lote é ≥ o carimbo do último tijolo do lote anterior. Sobre
candles a sobrescrita nem acontece. (O que está errado é o javadoc — B2-4.)

**`spendStamped`: o relógio não passa do fim do minuto nem deixa preço para
trás.** `step = min(owed, max(0, end − now))` (linha 276) grampeia `now` em
`start + barMillis`; o laço da linha 281 consome **todos** os preços com
`when <= now` antes do teste de fechamento, então nenhum preço com carimbo
dentro do minuto é pulado; e o fechamento devolve `true`, de modo que o `while
(owed > 0)` de `advanceMarketTime` sempre progride (ou gasta `owed`, ou fecha uma
barra). O relógio também não retrocede na virada de barra: sem barra formando
`clock()` devolve `start + barMillis`, que é o mesmo valor que `now` tinha ao
fechar. (A exceção é `barMillis` medido errado — B2-12.)

**Os `when` chegam ordenados.** Em `RecordedTicks.bracketed` (linha 149) o
carimbo da frente é `from` e `stamps[0] >= from` por `firstAtOrAfter`; o de trás
é `max(to − 1, stamps[last])`, e o filtro `timeAt(i) < to` garante
`stamps[last] <= to − 1`, então o máximo é sempre `to − 1`. O vetor sai não
decrescente, que é o que o laço de `spendStamped` supõe.

**`RecordedTicks.firstAtOrAfter`.** Busca binária conferida caso a caso:
`(low + high) >>> 1` não estoura, `found` começa em `size()` (nenhum tick ao ou
depois do instante) e o invariante `high = middle − 1` converge. Correta.

**`endOf`.** Usa o carimbo da barra seguinte quando existe, a largura da anterior
para a última, e um minuto quando nem isso — e o javadoc explica os dois erros
que as alternativas cometeriam. Correto para o que promete.

**`RecordedTicks.dayOf` e o fuso.** Uma zona só (`Timeframe.defaultZone`), lida
aqui e pela sessão; o comentário registra o defeito silencioso que duas zonas
causavam. Confere.

**`TradeTally.clear` preserva `summarised`.** Conferido contra o caso que o
javadoc cita: uma barra pode assentar dois lotes (baixa e depois alta) e o
segundo encontraria um tally que esqueceu que estava lendo candles. `clear`
(linha 190) zera só `trades`, `volume` e `first`. Certo.

**`TradeTally.seeing` separado de `add`.** Necessário porque a barra entra no
tally **depois** dos tijolos que ela assentou (`Renko.java:581`), e a pergunta
"isto pode ser contado" tem de ser respondida antes. Confere.

**`Counted.UNKNOWN` e a regra "ausente é NaN, nunca zero".** `assemble`
(linha 730) devolve `Double.NaN` para volume quando a série não trouxe nenhum, e
`Counted.UNKNOWN` é `-1L`, distinto de zero. `Untraded.at`/`Counted.at`
resolvem o `instanceof` num lugar só, e `ArraySeries` implementa as duas
interfaces (conferido na declaração), então a informação de `Renko` chega
inteira ao `TickRenko.fold`.

**O cache `builtBricks` de `TickRenko`.** Invalidado em `fold` (linha 349)
exatamente quando `laid.size() > 0`, que é a mesma condição sob a qual as quatro
listas crescem; nada mais escreve nelas (`grep` por `bricks.add`, `stamps.add`,
`counts.add`, `untraded.set` no arquivo). O cache não pode ficar velho.

**`TickRenko.live()`.** O tijolo em formação nunca é marcado como gap nem
contado (linhas 261-271), e `size()` devolve `settled.size() + 1`, então ele
existe na tela sem entrar no que `bricks()` considera assentado. Coerente com
`RenkoGapTest.formingIsNeverAGap`.

**A dobra do rabo do pregão na virada do dia** (`TickRenko.java:188-189`). É a
correção certa e o comentário explica o que se perdia (o leilão de fechamento) e
por que era invisível. O único furo que achei nessa vizinhança está em B2-8, e é
sobre o `forming`, não sobre o rabo.

**`ReplaySeries.check`, `clamp`, `origin` e `seekFraction`.** `check` fecha o
futuro *entre* barras com mensagem própria; `clamp` respeita o piso `origin`, de
modo que rebobinar não apaga a história; `seekFraction` mede sobre o pregão e não
sobre a tela, com `!Double.isFinite` tratado. Conferidos contra
`HistoryBeforeReplayTest`.

**`TickPath.Timed` e o padrão de `timedPathFor`.** O `default` devolve
`when == null`, que é o que uma caminhada inventada pode honestamente afirmar; o
javadoc (linhas 57-78) explica a regra dos dois réguas e por que os instantes
têm de vir junto dos preços. O contrato está certo — o que ainda não está
guardado por teste é o outro lado dele (B2-1).
