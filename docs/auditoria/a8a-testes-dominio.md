# A8a — os testes de domínio

**Data:** 06/09/2026
**Raiz auditada:** `src/test/java/br/com/jorge/reis/endeavourneo/domain/market/`
**Código de produção lido para a mutação:** `src/main/java/br/com/jorge/reis/endeavourneo/domain/market/`

## Inventário

`find` confirmou **15 arquivos `.java` de teste sob `domain/`**, todos em `domain/market/`.
Não há nenhum outro pacote de teste sob `domain/`. As contagens de linha reais
divergem da tabela do encargo — anotadas abaixo porque mudam a leitura de
"quanto teste existe":

| arquivo | linhas (medidas) | linhas (na tabela) |
|---|---:|---:|
| `RenkoTest.java` | **482** | 408 |
| `TickRenkoTest.java` | **451** | 360 |
| `TickLibraryTest.java` | **370** | 301 |
| `TapeFileTest.java` | **300** | 249 |
| `TimeframeTest.java` | **269** | 218 |
| `RenkoGapTest.java` | **250** | 204 |
| `TickFileTest.java` | **246** | 203 |
| `RenkoCountTest.java` | **232** | 191 |
| `ReplaySeriesTest.java` | **197** | 160 |
| `RenkoWickBoundsTest.java` | **191** | 159 |
| `SegmentedSeriesTest.java` | **184** | 154 |
| `SeriesMergeTest.java` | **180** | 150 |
| `RenkoContinuedTest.java` | **172** | 141 |
| `MarketFileTest.java` | **154** | 126 |
| `HistoryBeforeReplayTest.java` | **153** | 126 |
| **total** | **3.831** | 3.150 |

**Método:** para cada método de teste, escolhi a mutação mais plausível do
código que ele cobre e simulei à mão a execução do teste sobre o fixture
literal. Só reportei o que sobrevive. Toda mutação abaixo foi conferida contra
os outros catorze arquivos antes de virar achado.

---

## Os três defeitos conhecidos: quem deveria ter pegado?

### 1. `Renko.java:418` — `int made = 0;` fora do laço `for (int step = 0; step < 2; step++)`

**Quem deveria ter pegado:** `RenkoWickBoundsTest.noTailBeyondTheReversal`
(`RenkoWickBoundsTest.java:96`), que roda o renko sobre **20.000 barras** de um
passeio semeado — o único fixture da suíte grande o bastante para conter a
situação que dispara o defeito.

**Por que não pegou:** as três asserções do arquivo são **todas de teto**.

- linha 119 — `assertEquals(0, broken, ...)` onde `broken` conta caldas
  **maiores** que `reversal * brick`
- linha 138 — `assertEquals(0, broken, ...)` onde `broken` conta caldas que
  passam **além** do fechamento
- linha 178 — `assertTrue(bricks.lowAt(up) >= 80.0 - 2 * 10, ...)`, que é um
  **piso no preço** e portanto de novo um **teto no comprimento da calda**

O defeito encurta caldas. Nas três asserções, encurtar só afasta do limite. O
arquivo inteiro é cego a caldas curtas por construção — inclusive a caldas de
comprimento zero.

Os outros cinco arquivos de renko não salvam:

- `RenkoGapTest` e `RenkoCountTest` constroem **todos** os seus renkos com
  `new Renko(100, 2, false)` — **calda desligada**. Nenhum dos dois toca o
  código do defeito.
- `RenkoContinuedTest.theTailsAgree` (`:141`) compara calda-inteiro contra
  calda-em-pedaços: os dois lados carregam o mesmo defeito, então concordam.
- `RenkoTest.tailShowsTheFightBeforeTheBreak` (`:229`) tem piso de verdade
  (`firstTail <= 93.0`), mas o fixture nunca chega no estado que dispara o
  defeito: na barra `{100, 112, 93, 112}` o passo 0 (a mínima) **não assenta
  tijolo**, então `made` continua zero e o reset do fim do passo 1 é legítimo.
  O defeito precisa do passo 0 assentando **e** do passo 1 não assentando.
- `TickRenkoTest.thePiecesAgreeWithTheWhole` (`:82`) também é
  auto-consistência: os dois lados são o mesmo código.

Detalhe do achado A8a-1 abaixo.

### 2. `Timeframe.java:309` — `(minuteOfDay / minutes)` com `minuteOfDay <= 1439`

**Quem deveria ter pegado:** `TimeframeTest`, 269 linhas.

**Por que não pegou:** **nenhum dos onze métodos do arquivo dobra em escala
acima de trinta minutos.** As únicas escalas exercitadas são
`ONE_MINUTE`, `FIVE_MINUTES`, `THIRTY_MINUTES`, `DAILY` e `WEEKLY`. `ONE_HOUR`
nunca aparece; `MONTHLY` nunca aparece; `ofMinutes(...)` **nunca é chamado neste
arquivo** — a única chamada em toda a suíte está em
`ui/chart/PeriodCatalogTest.java:148-151`, e só verifica os **rejeitos**
(`0`, `-5`, `MOST_MINUTES + 1`) e o acerto de `5`.

Ou seja: `MOST_MINUTES = 43_200` deixa construir 30 dias em minutos, e a suíte
inteira nunca dobrou nada acima de 30 minutos. `DAILY` e `WEEKLY` passam por
`startOf` pelo caminho `minutes <= 0`, que devolve `millis` sem dividir — então
nem eles tocam a divisão. Não é uma asserção fraca: é um buraco de cobertura
completo. Ver A8a-2.

### 3. `ReplaySeries.java:174` — congelamento quando `pathFor` devolve null

```java
if (path == null && !startForming()) {
    owed = 0;

    return;
}
```

**Quem deveria ter pegado:** `ReplaySeriesTest`, 197 linhas.

**Por que não pegou:** o arquivo tem **um único** teste que constrói um
`ReplaySeries` com gerador de ticks — `theClockMovesInsideTheBar`
(`ReplaySeriesTest.java:173`) — e o gerador é:

```java
ReplaySeries live = new ReplaySeries(day(), 0,
        (day, index) -> new double[]{100, 101, 102, 103, 104, 105, 106, 107});
```

Um lambda que **nunca** devolve null e **nunca** devolve vetor vazio. Os outros
oito métodos passam `null` como `TickPath` e usam `advance(int)`/`seek(int)`,
que não passam por `advanceMarketTime`.

Pior: `TickLibraryTest.theFallbackCanBeRefused` (`:295`) **prova** que
`RecordedTicks.pathFor` devolve null quando não há ticks gravados e o fallback
sintético está desligado — exatamente a entrada que congela o replay. Os dois
lados do defeito estão testados separadamente e a junção não está. Ver A8a-3.

---

## Achados ALTA

### A8a-1 — as três asserções de calda são de teto; calda nenhuma passa em todas

**Onde:** `RenkoWickBoundsTest.java:119`, `:138`, `:178`

**Trecho:**

```java
        assertEquals(0, broken, broken + " bricks wear a tail the rules forbid; first: " + first);
        assertTrue(bricks.size() > 1_000, "the walk laid too few bricks to prove anything");
```

```java
        assertTrue(bricks.lowAt(up) >= 80.0 - 2 * 10,
                "the up brick wears a tail to " + bricks.lowAt(up)
                        + ", a price that in the assumed path had not been reached yet");
```

**A mutação que passa:** em `Renko.applyFrom`, trocar a calda corrente por uma
calda que só enxerga a barra atual — isto é, `Renko.java:425` e `:428`

```java
    sinceLow  = Math.min(sinceLow,  source.lowAt(i));   // atual
    sinceHigh = Math.max(sinceHigh, source.highAt(i));
```
para
```java
    sinceLow  = Math.min(anchor, source.lowAt(i));      // mutado
    sinceHigh = Math.max(anchor, source.highAt(i));
```

Isso apaga a memória entre barras: a calda deixa de dizer "quanto o preço andou
contra desde o último tijolo" e passa a dizer só "quanto esta barra andou
contra". Toda calda fica igual ou **mais curta**.

Simulei os cinco testes que tocam calda:

| teste | resultado com a mutação |
|---|---|
| `RenkoWickBoundsTest.noTailBeyondTheReversal:119` | passa (caldas menores ⇒ `broken` continua 0) |
| `RenkoWickBoundsTest.noTailPastTheClose:138` | passa (não mexe no `beyond`) |
| `RenkoWickBoundsTest.theOtherExtremeIsNotYetReached:178` | passa (calda curta só **sobe** `lowAt(up)`) |
| `RenkoTest.tailShowsTheFightBeforeTheBreak:248` | passa — no fixture `anchor == 100` e `sinceLow` já valia 100, então `min(100, 93)` dá 93 dos dois jeitos |
| `RenkoContinuedTest.theTailsAgree:141` | passa — compara inteiro contra pedaços, os dois mutados |

A suíte inteira aceita um renko cuja calda perdeu a memória entre barras. É a
mesma cegueira que deixou `Renko.java:418` viver.

**Por que importa:** a calda é o que distingue "o preço rompeu" de "o preço
rompeu depois de apanhar trinta pontos". A nota do próprio arquivo diz que dois
gráficos do mesmo dia "podem parecer idênticos sem ela e completamente
diferentes com ela". Um estudo de reversão lê a calda; ela sair curta é erro de
resultado, não de desenho.

**Correção:** um quarto método no mesmo arquivo, com **piso**, sobre o mesmo
passeio semeado:

```java
    @Test
    @DisplayName("a calda registra o extremo da barra que NAO assentou tijolo")
    void theSecondExtremeSurvivesIntoTheNextTail() {
        // Tijolo 10, reversao 2. Duas barras que sobem para firmar a tendencia,
        // depois UMA barra cuja minima assenta a reversao (passo 0) e cuja
        // maxima nao assenta nada (passo 1). O extremo do passo 1 tem de virar
        // a calda superior do PROXIMO tijolo de baixa.
        PriceSeries bricks = new Renko(10, 2).apply(ohlc(
                new double[]{100, 100, 100, 100},
                new double[]{100, 121, 100, 121},   // sobe: tendencia +1
                new double[]{120,  128,  95, 100},  // minima assenta; maxima 128 nao
                new double[]{100,  100,  78,  80}));// desce mais: quem carrega a calda

        int last = bricks.size() - 1;

        assertTrue(bricks.closeAt(last) < bricks.openAt(last), "o ultimo tijolo nao e de baixa");
        assertTrue(bricks.highAt(last) >= 128.0 - 1e-9,
                "a calda esqueceu a maxima 128 da barra que assentou pelo outro extremo: "
                        + bricks.highAt(last));
    }
```

O piso `>= 128` é o que falta na suíte inteira. Ele reprova a mutação acima e
reprova `Renko.java:418`.

**Tentei refutar:** varri os seis arquivos de renko (1.646 linhas medidas).
`RenkoGapTest` e `RenkoCountTest` desligam a calda em **todas** as suas
construções. `RenkoContinuedTest` e `TickRenkoTest` comparam o código consigo
mesmo. `RenkoTest` tem um único piso de calda (`:248`) e o fixture dele não
alcança o estado. Nenhum irmão salva.

---

### A8a-2 — nenhum teste dobra acima de 30 minutos; o carimbo de meia-noite passa

**Onde:** `TimeframeTest.java` (arquivo inteiro, 269 linhas)

**Trecho:** as escalas que o arquivo exercita, uma por asserção de dobra:

```java
        PriceSeries five = Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO);      // :117
        PriceSeries daily = Timeframe.DAILY.apply(series(), SAO_PAULO);            // :158
        PriceSeries weekly = Timeframe.WEEKLY.apply(series(), SAO_PAULO);          // :182
        assertEquals(2, Timeframe.THIRTY_MINUTES.apply(series(), SAO_PAULO).size());// :267
```

`ONE_HOUR` não aparece. `MONTHLY` não aparece. `ofMinutes(...)` não aparece.

**A mutação que passa:** qualquer mutação em `Timeframe.java:271-281` (`startOf`)
que só afete escalas acima de um dia. A mais brutal:

```java
    private long startOf(long millis, ZoneId zone) {
        if (minutes <= 0) {
            return millis;
        }
        return 0L;                          // mutado: todo balde comeca em 1970
    }
```
não passa — `carriesTheOpeningTime` pega. Mas esta passa:

```java
        long slot = minutes > 1_440 ? 0 : sinceMidnight / minutes * minutes;
```

que é **exatamente o comportamento que o defeito já tem**, escrito de propósito.
Nada na suíte constrói uma escala acima de 1.440 minutos, então nada nota. E o
recíproco também passa: baixar `MOST_MINUTES` de `43_200` para `1_440` — a
correção — **também** não é notado por nenhum teste, porque
`PeriodCatalogTest.java:150` só verifica `ofMinutes(MOST_MINUTES + 1) == null`,
lendo a mesma constante que o produto (constante compartilhada: casa por
construção com qualquer valor).

**Por que importa:** carimbo de tempo. `MOST_MINUTES = 43_200` é o contrato
público de que o leitor pode digitar até 30 dias; acima de 1.440 todo candle
sai carimbado à meia-noite do seu próprio dia, e o eixo do gráfico, o relógio do
replay e qualquer medição por horário passam a mentir em silêncio.

**Correção:** um teste que dobre acima de um dia e leia o carimbo:

```java
    @Test
    @DisplayName("uma escala maior que um dia carimba a hora de abertura, nao meia-noite")
    void scalesLongerThanADayKeepTheClock() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 9, 0);

        bar(start,                 100, 104,  99, 103, 10);
        bar(start.plusMinutes(30), 103, 108, 102, 105, 20);

        Timeframe twoDays = Timeframe.ofMinutes(2_880);
        PriceSeries folded = twoDays.apply(series(), SAO_PAULO);

        assertEquals(1, folded.size());
        assertEquals(start.atZone(SAO_PAULO).toInstant().toEpochMilli(), folded.timeAt(0),
                "a barra de 2 dias foi carimbada a meia-noite");
    }
```

**Tentei refutar:** varri toda a suíte por `ofMinutes` e `MOST_MINUTES`
(`grep -rn` em `src/test/`): só as cinco ocorrências de
`ui/chart/PeriodCatalogTest.java:148-151` e
`ui/chart/TickRenkoOnChartTest.java:345`, ambas com `5` ou com os rejeitos.
Nenhum irmão salva.

---

### A8a-3 — o replay travado nunca é exercitado: nenhum `TickPath` devolve null

**Onde:** `ReplaySeriesTest.java:178`

**Trecho:**

```java
        ReplaySeries live = new ReplaySeries(day(), 0,
                (day, index) -> new double[]{100, 101, 102, 103, 104, 105, 106, 107});
```

**A mutação que passa:** já está no produto (`ReplaySeries.java:174-178`), e a
mutação equivalente também passa — trocar o `return` por um laço infinito, ou
trocar `owed = 0; return;` por `return;` sozinho (o que muda o comportamento de
um replay parado, e nada reprova).

Mais direto: **remover `startForming` inteiro** e devolver sempre `false`

```java
    private boolean startForming() {
        return false;                 // mutado
    }
```

reprova `theClockMovesInsideTheBar` (o relógio para de andar). Mas a mutação
**seletiva** — devolver `false` apenas quando `ticks.pathFor` devolveu null —
é o defeito, e passa: nenhum fixture da suíte alcança esse ramo.

**Por que importa:** perda de função silenciosa. `TickLibraryTest:295` prova que
`RecordedTicks.pathFor(bars, 0)` devolve null com o fallback recusado — isso é
uma configuração oferecida ao leitor ("prefiro não ver caminho a ver um
inventado"). Nessa configuração o replay **para para sempre** na primeira barra
sem ticks gravados, e nada na tela diz por quê.

**Correção:**

```java
    @Test
    @DisplayName("uma barra sem caminho de ticks nao congela o replay")
    void aBarWithNoPathStillAdvances() {
        // O que TickLibraryTest.theFallbackCanBeRefused prova que acontece:
        // pathFor devolve null. O replay tem de revelar a barra inteira e
        // seguir, nao parar para sempre.
        ReplaySeries replay = new ReplaySeries(day(), 0, (day, index) -> null);

        int before = replay.size();

        replay.advanceMarketTime(5 * 60_000L);

        assertTrue(replay.size() > before,
                "o replay congelou na barra sem ticks: " + before + " e ainda " + replay.size());
    }
```

**Tentei refutar:** `grep -rn "advanceMarketTime"` na suíte inteira retorna duas
linhas, ambas em `ReplaySeriesTest:184` e `:188`, ambas com o lambda acima.
`ui/replay/` é área de outro agente, mas o `grep` não achou nenhum `TickPath`
que devolva null em teste nenhum. Nenhum irmão salva.

---

### A8a-4 — `assertTrue(half.size() < whole.size())`: um negócio de lookahead passa

**Onde:** `TickRenkoTest.java:142`

**Trecho:**

```java
            assertTrue(half.size() > 0, "nothing was drawn at all");
            assertTrue(half.size() < whole.size(),
                    "the half-played session drew " + half.size()
                            + " bricks, the same as the whole day");
```

**A mutação que passa:** em `TickBars.countUntil` (`TickBars.java:167`), trocar
o estritamente-antes por até-inclusive:

```java
            if (timeAt(middle) < when) {     // atual: "estritamente antes", exclusivo
            if (timeAt(middle) <= when) {    // mutado: inclui o negocio DO instante
```

Simulei no fixture literal do teste. A sessão é
`session(folder, day, 100, 110, 120, 130, 140, 150, 160)` com um negócio por
segundo a partir das 09:00, e o corte é `at(day, 3)` — o carimbo do quarto
negócio.

- atual: `countUntil` = 3 barras → preços `100, 110, 120` → tijolo 10 →
  **1 tijolo** (`100→110`; `110` não passa de `110`, `120` passa)
- mutado: `countUntil` = 4 barras → `100, 110, 120, 130` → **2 tijolos**
- `whole` (7 barras até 160) → **5 tijolos**

`assertTrue(half.size() > 0)`: 1 > 0 ✓ e 2 > 0 ✓.
`assertTrue(half.size() < whole.size())`: 1 < 5 ✓ e 2 < 5 ✓.
O laço seguinte compara prefixos, e os dois prefixos estão corretos.
**Passa nos dois casos.**

**Por que importa:** é o único lugar da suíte que testa "o replay só vê o que já
chegou" no caminho dos ticks, e é justamente a fronteira `<` contra `<=` que ele
não consegue distinguir. Um negócio de lookahead por quadro é pequeno de olhar e
grande de consequência: o javadoc do método diz "up to that instant,
**exclusive**", e o `ReplaySeriesTest` inteiro existe para a propriedade "o que
não chegou não pode ser lido".

**Correção:** trocar a desigualdade por uma contagem exata e por uma asserção
sobre a fronteira:

```java
            // O corte cai EM CIMA do carimbo do quarto negocio. Exclusivo:
            // tres barras entram, a quarta nao. Uma desigualdade nao distingue
            // isso de quatro barras.
            assertEquals(1, half.size(),
                    "o corte incluiu o negocio que acontece exatamente no instante pedido");

            TickRenko justPast = new TickRenko(new Renko(10, 2), library);
            justPast.addUpTo(day, at(day, 3) + 1);

            assertEquals(2, justPast.size(),
                    "um milissegundo depois, o quarto negocio tem de entrar");
```

**Tentei refutar:** os outros quatro testes de `TickRenkoTest` que passam por
`countUntil` — `advancingInPiecesIsTheSameRenko:288`,
`aFrameWithNothingNewLaysNothing:328`, `theLiveEdgeMovesEveryFrame:363`,
`theFormingBrickIsNeverCounted:419` — todos comparam o resultado **final** de
duas montagens ou usam `Long.MAX_VALUE` como fronteira. A mutação só desloca
*quando* um negócio entra, nunca *se* entra, então o resultado final é idêntico
nos quatro. `TickBars.until`, `countUntil` e `range` não têm nenhum teste
direto: `grep -rn "\.range(\|countUntil\|\.until("` em `src/test/` não retorna
nada. Nenhum irmão salva.

---

### A8a-5 — `carriesTheOpeningTime` usa fixture já alinhado; o defeito dos ticks 59s atrasados passa

**Onde:** `TimeframeTest.java:128-140`

**Trecho:**

```java
    void carriesTheOpeningTime() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 9, 0);

        for (int i = 0; i < 5; i++) {
            bar(start.plusMinutes(i), 100, 100, 100, 100, 1);
        }

        assertEquals(start.atZone(SAO_PAULO).toInstant().toEpochMilli(),
                Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO).timeAt(0),
                "a 09:00 bar covering 09:00–09:04 must be stamped 09:00");
    }
```

**A mutação que passa:** reverter `startOf` ao comportamento anterior descrito
no próprio javadoc de `Timeframe.java:262-266` — devolver o carimbo da barra de
origem:

```java
    private long startOf(long millis, ZoneId zone) {
        return millis;                // mutado: o que era antes, e era defeito
    }
```

A primeira barra do fixture cai **exatamente** em 09:00, que é a fronteira do
balde. `startOf(09:00)` e `millis` dão o mesmo número. Passa.

Conferi os outros dez métodos do arquivo com a mesma mutação:

| teste | por que passa |
|---|---|
| `foldsFiveMinutes:117` | não lê `timeAt` |
| `dailyKeepsItsOwnDate:158` | `minutes <= 0`, `startOf` já devolvia `millis` |
| `weekStartsOnMonday:182` | idem |
| `sameTimeDifferentDays:196` | só lê `size()` |
| `theZoneMatters:205` | chama `bucketOf`, não `startOf` |
| `keepsTheIncompleteTail:226` | fixture começa em 09:00; só lê `size()` e `closeAt` |
| `gapsDoNotMerge:267` | barra às 17:55 dobrada em 30m; só lê `size()` |
| `oneMinuteChangesNothing:250` | `apply` devolve a série sem dobrar |

O arquivo inteiro é cego a um carimbo desalinhado porque **todo fixture já nasce
alinhado ao balde**.

**Por que importa:** o javadoc de `startOf` documenta o defeito medido — "uma
sessão dobrada a partir de negócios saiu com cada candle carimbado até 59
segundos atrasado, e nada batia com a base de minutos". E `Timeframe.fold` é
chamado exatamente nessa situação: `ui/replay/ReplaySession.java:408` faz
`Timeframe.ONE_MINUTE.fold(...)` sobre ticks. Esse caminho — dobrar em um minuto
uma série cujos carimbos **não** são múltiplos do minuto — não tem nenhum teste
(`grep -rn "\.fold("` em `src/test/` não retorna nada).

**Correção:** desalinhar o fixture, que é o que a série de ticks faz de verdade:

```java
    @Test
    @DisplayName("o carimbo e o inicio do balde, mesmo com a origem desalinhada")
    void theStampIsTheBucketNotTheFirstBar() {
        // O que um tick faz: chegar as 09:00:37. A barra de cinco minutos tem
        // de sair carimbada 09:00:00, nao 09:00:37 -- senao nada bate com a
        // base de minutos. Medido, e o defeito que startOf existe para evitar.
        LocalDateTime start = LocalDateTime.of(2026, 9, 2, 9, 0, 37);

        bar(start,                 100, 104,  99, 103, 10);
        bar(start.plusSeconds(23), 103, 108, 102, 105, 20);

        assertEquals(LocalDateTime.of(2026, 9, 2, 9, 0)
                        .atZone(SAO_PAULO).toInstant().toEpochMilli(),
                Timeframe.FIVE_MINUTES.apply(series(), SAO_PAULO).timeAt(0),
                "o candle ficou com o carimbo do primeiro negocio, nao o do balde");
    }
```

**Tentei refutar:** varri `TimeframeTest` inteiro e `grep -rn "\.fold("` na
suíte. Nenhum fixture de nenhum arquivo alimenta `Timeframe` com carimbos que
não sejam múltiplos exatos da escala pedida. Nenhum irmão salva.

---

### A8a-6 — `MONTHLY` nunca é dobrado; "todo setembro num só balde" passa

**Onde:** ausência em `TimeframeTest.java`; produto em `Timeframe.java:293-297`

**Trecho (produto, o que ninguém testa):**

```java
        if (minutes == MONTH) {
            // Year and month together: a month number on its own would put every
            // September of every year in one bar.
            return local.getYear() * 12L + local.getMonthValue();
        }
```

**A mutação que passa:** apagar o ano, que é exatamente o defeito que o
comentário nomeia:

```java
            return local.getMonthValue();
```

Nada na suíte reprova. `MONTHLY` só aparece uma vez em todo `src/test/`, em
`ui/chart/PeriodCatalogTest.java:129`:

```java
        assertTrue(all.stream().anyMatch(c -> c.aggregation() == Timeframe.MONTHLY), ...)
```

— que verifica que a escala está **oferecida no catálogo**, não que ela dobra
certo. Ou seja: a escala é oferecida ao leitor e nunca foi exercitada.

**Por que importa:** um gráfico mensal soldando setembro de 2021 com setembro de
2026 numa barra só é um erro de resultado, na cara, e nada avisa. E o
`@DisplayName` de `PeriodCatalogTest` dá licença falsa: a lista de testes diz
que o mensal está coberto.

**Correção:** no `TimeframeTest`, ao lado de `weekStartsOnMonday`:

```java
    @Test
    @DisplayName("dois setembros de anos diferentes nao sao a mesma barra")
    void theMonthCarriesItsYear() {
        // Um numero de mes sozinho poe todo setembro de todo ano numa barra.
        bar(LocalDateTime.of(2021, 9, 15, 10, 0), 100, 100, 100, 100, 1);
        bar(LocalDateTime.of(2026, 9, 15, 10, 0), 200, 200, 200, 200, 1);
        bar(LocalDateTime.of(2026, 10, 1, 10, 0), 300, 300, 300, 300, 1);

        PriceSeries monthly = Timeframe.MONTHLY.apply(series(), SAO_PAULO);

        assertEquals(3, monthly.size(), "dois setembros cairam no mesmo balde");
        assertEquals(100.0, monthly.closeAt(0));
        assertEquals(200.0, monthly.closeAt(1));
    }
```

**Tentei refutar:** `grep -rn "MONTHLY"` em `src/test/` retorna uma linha só, a
citada. Nenhum irmão salva.

---

### A8a-7 — `joinsInOrder` promete ordem e não lê um carimbo sequer

**Onde:** `HistoryBeforeReplayTest.java:74-84`

**Trecho:**

```java
    @DisplayName("joined sessions read as one series, in order")
    void joinsInOrder() {
        PriceSeries all = ConcatSeries.of(List.of(
                bars(3, 100, 0L), bars(4, 200, 1_000_000L), bars(2, 300, 2_000_000L)));

        assertEquals(9, all.size());
        assertEquals(100.0, all.closeAt(0));
        assertEquals(200.0, all.closeAt(3), "the second session must start at index 3");
        assertEquals(300.0, all.closeAt(7));
        assertEquals(301.0, all.closeAt(8));
        assertThrows(IndexOutOfBoundsException.class, () -> all.closeAt(9));
    }
```

**A mutação que passa:** em `ConcatSeries.java:104`, esquecer o deslocamento no
carimbo — e **só** no carimbo:

```java
    public long timeAt(int index) {
        int part = partOf(index);

        return parts[part].timeAt(index);        // mutado: sem "- starts[part]"
    }
```

Nenhum teste da suíte lê `timeAt` de um `ConcatSeries` num índice fora da
primeira parte. `grep -rn "ConcatSeries"` em `src/test/` retorna seis linhas,
todas em `HistoryBeforeReplayTest`, e nenhuma delas chama `timeAt`. Os únicos
consumidores que chamam são `ReplaySeries.measureBar` (índices 0 e 1 — parte 0,
onde `starts[0] == 0` e a mutação é invisível) e `ReplaySeries.clock`, que
nenhum teste deste arquivo exercita fora da história. Por simetria, a mesma
mutação em `openAt`, `highAt`, `lowAt` e `volumeAt` também passa: só `closeAt`
tem asserção.

**Por que importa:** carimbo de tempo, e o `@DisplayName` diz **"in order"** —
promete a ordem cronológica e entrega quatro preços. Quem lê a lista de testes
sai convencido de que a junção de sessões está coberta no eixo do tempo. Um
`ConcatSeries` com carimbos errados manda o eixo do gráfico, a agregação por
fuso e o relógio do replay todos para o lugar errado, e o preço continua certo —
o modo de falha mais difícil de perceber que existe aqui.

**Correção:** duas linhas no mesmo teste:

```java
        // Os carimbos, e nao so os precos: uma parte que devolvesse o indice
        // sem deslocar acerta todo preco e erra todo horario.
        assertEquals(1_000_000L, all.timeAt(3), "a segunda sessao nao comeca no seu proprio inicio");
        assertEquals(2_000_000L + 60_000L, all.timeAt(8));
        assertEquals(200.0, all.openAt(3));
        assertEquals(300.0, all.highAt(7));
```

**Tentei refutar:** `ConcatSeries` só aparece em `HistoryBeforeReplayTest` na
suíte inteira. Os outros quatro métodos do arquivo (`emptyPartsAreDropped:90`,
`oneSessionIsItself:102`, `historyIsAlwaysThere:112`,
`progressIgnoresHistory:128`, `theFutureStaysShut:147`) leem `size()`,
`closeAt` e `progress()`. Nenhum irmão salva.

---

## Achados MÉDIA

### A8a-8 — `SegmentedSeriesTest` só lê `closeAt` e `timeAt`

**Onde:** `SegmentedSeriesTest.java` (arquivo inteiro, 184 linhas)

**Trecho:** o fixture, que é a raiz do problema —

```java
            @Override
            public double openAt(int index) {
                return index;
            }
            // high, low e close idem
```

**A mutação que passa:** em `SegmentedSeries`, esquecer o `first` em qualquer
acessor que não seja `closeAt`:

```java
    public double highAt(int index) {
        return base.highAt(index);       // mutado, sem "first +"
    }
```

Nenhuma das nove asserções do arquivo lê `highAt`, `lowAt`, `openAt` ou
`volumeAt` de uma fatia. A mesma mutação em `closeAt` **é** pega (`:98`,
`:111`), o que mostra que o autor sabia como testar e cobriu um acessor de
cinco.

**Por que importa:** uma fatia é o recorte de estudo. Máximas e mínimas
deslocadas pelo tamanho da história é dado errado entrando em medição, sem
nenhum sinal.

**Correção:** em `translatesTheIndex` (`:92`), somar

```java
        assertEquals(6.0, cut.openAt(0));
        assertEquals(11.0, cut.highAt(5));
        assertEquals(11.0, cut.lowAt(5));
```

**Tentei refutar:** `grep -rln "SegmentedSeries"` em `src/test/` retorna só o
próprio arquivo. Nenhum irmão salva.

---

### A8a-9 — `minutes` escrito por `MarketFile.write` nunca é lido de volta

**Onde:** `MarketFileTest.java:99` e `SeriesMergeTest.java:162`

**Trecho:**

```java
        assertEquals(1, MarketFile.minutesOf(file));          // MarketFileTest:99
```
```java
        MarketFile.write(file, joined, 1);                    // SeriesMergeTest:162
        PriceSeries read = MarketFile.read(file);
```

**A mutação que passa:** em `MarketFile.java:161`, ignorar o parâmetro:

```java
            head.putInt(1);                  // mutado: escreve sempre 1m
```

`MarketFileTest:99` lê `minutesOf` de um arquivo escrito **à mão** pelo teste,
não pelo produto. `SeriesMergeTest` escreve pelo produto mas passa `1` e nunca
lê `minutesOf`. As duas metades existem e não se encontram.

**Por que importa:** o cabeçalho `minutes` é o que diz a que escala a base
salva pertence, e os dois programas leem esse arquivo. Uma base de 5 minutos
salva declarando 1 minuto seria re-agregada errado no próximo carregamento.

**Correção:** no round-trip de `SeriesMergeTest.itSurvivesTheRoundTrip:151`,
escrever numa escala que não seja o valor de fallback e conferir:

```java
        MarketFile.write(file, joined, 5);

        assertEquals(5, MarketFile.minutesOf(file),
                "a escala declarada no cabecalho nao e a que foi pedida");
```

**Tentei refutar:** `grep -rn "minutesOf"` em `src/test/` retorna só
`MarketFileTest:99`. `MarketFile.write` aparece só em `SeriesMergeTest:162`.
Nenhum irmão salva.

---

### A8a-10 — `anEmptySessionIsHarmless`: o laço pode não rodar nenhuma vez

**Onde:** `TickRenkoTest.java:196-199`

**Trecho:**

```java
            for (int i = 1; i < withGap.size(); i++) {
                assertEquals(withGap.closeAt(i - 1), withGap.openAt(i), 1e-9,
                        "brick " + i + " does not start where the one before it closed");
            }
```

**A mutação que passa:** qualquer mutação que reduza o renko a um tijolo só. O
teste **não tem nenhuma asserção de contagem**: se `withGap.size()` for 0 ou 1,
o laço não executa e o método passa sem asserção nenhuma.

Concretamente: reiniciar a régua a cada sessão faz o fixture
(`{100,110,120}`, `{121}`, `{130,140}`, tijolo 10) produzir **1** tijolo em vez
de 3 — e o laço roda zero vezes. Passa.

Além disso, a asserção escolhida é fraca para o que o nome promete: com
reversão 2 um tijolo de virada **não** abre onde o anterior fechou (abre uma
caixa adiante — é a regra 5, e `RenkoTest.reversalCostsMore:121` a fixa). O
fixture é monotônico de subida, então nunca há virada e a condição é satisfeita
por construção.

**Por que importa:** o `@DisplayName` promete que uma sessão quase vazia não
reinicia a régua, e nenhuma asserção fala de régua nem de contagem.

**Correção:** contagem exata e o preço da fronteira:

```java
            assertEquals(3, withGap.size(),
                    "a sessao de um negocio so reiniciou a regua: " + withGap.size() + " tijolos");
            assertEquals(110.0, withGap.openAt(1), 1e-9,
                    "o tijolo depois da sessao vazia nao continua de onde o anterior parou");
```

**Tentei refutar:** o irmão `thePiecesAgreeWithTheWhole` (`:82`) **pega** a
mutação genérica "reiniciar a régua toda sessão", porque compara duas sessões
de sete negócios contra uma passada única. Mas ele não alcança a mutação
específica que este teste diz cobrir — "pular sessões com menos de N negócios",
ou "não propagar o carry quando a sessão não assentou nada" — porque nenhuma das
suas sessões é escassa. `RenkoContinuedTest.anEmptyPieceIsHarmless:157` cobre a
peça **vazia** (`PriceSeries.empty()`), que sai pelo retorno antecipado de
`applyFrom`, não pelo laço. A sessão de **um** negócio, que é o caso real de
25/01/2021 citado no comentário, fica descoberta. Reporto com a ressalva.

---

### A8a-11 — `Timeframe.fold` em um minuto (o caminho dos ticks) não tem teste

**Onde:** ausência; produto em `Timeframe.java:181` e consumidor em
`ui/replay/ReplaySession.java:408`

**Trecho (o teste que dá a impressão de cobrir, e não cobre):**

```java
    @DisplayName("one minute hands the series straight back")
    void oneMinuteChangesNothing() {                          // TimeframeTest:249
        assertSame(source, Timeframe.ONE_MINUTE.apply(source, SAO_PAULO));
    }
```

**A mutação que passa:** `fold` é o método público que existe **precisamente**
para o caso que `apply` atalha, e o javadoc dele diz por quê ("uma série de
TICKS não é uma série de barras de minuto"). Qualquer mutação dentro de `fold`
que só se manifeste em `minutes == 1` — por exemplo devolver `source` também ali
— não é notada: `grep -rn "\.fold("` em `src/test/` não retorna nada.

**Por que importa:** é o caminho por onde a sessão de ticks vira candles no
replay. Também é onde o achado A8a-5 morde.

**Correção:** um teste que chame `fold` diretamente, com carimbos de segundo:

```java
    @Test
    @DisplayName("um minuto dobrado de verdade agrupa negocios do mesmo minuto")
    void foldAtOneMinuteReallyFolds() {
        bar(LocalDateTime.of(2026, 9, 2, 9, 0, 3),  100, 104,  99, 103, 10);
        bar(LocalDateTime.of(2026, 9, 2, 9, 0, 47), 103, 108, 102, 105, 20);
        bar(LocalDateTime.of(2026, 9, 2, 9, 1, 2),  105, 106,  95, 101, 30);

        PriceSeries minutes = Timeframe.ONE_MINUTE.fold(series(), SAO_PAULO);

        assertEquals(2, minutes.size(), "tres negocios em dois minutos viraram tres barras");
        assertEquals(108.0, minutes.highAt(0));
        assertEquals(LocalDateTime.of(2026, 9, 2, 9, 0).atZone(SAO_PAULO)
                .toInstant().toEpochMilli(), minutes.timeAt(0));
    }
```

**Tentei refutar:** nenhum teste da suíte chama `fold`. Nenhum irmão salva.

---

### A8a-12 — `advanceMarketTime` sem gerador de ticks: o ramo de fallback não tem teste

**Onde:** ausência; produto em `ReplaySeries.java:164-169`

**Trecho (produto):**

```java
        if (ticks == null) {
            // No tick generator: fall back to whole bars, which is what the
            // chart got before this existed.
            advance((int) Math.max(0, millis / barMillis));

            return;
        }
```

**A mutação que passa:** `advance(0)`, ou `millis / barMillis / 2`, ou remover
o `Math.max(0, ...)`. Os oito testes de `ReplaySeriesTest` que constroem sem
gerador usam `advance(int)` e `seek(int)` diretamente; o único que chama
`advanceMarketTime` passa um gerador.

**Por que importa:** é o modo do replay sem ticks gravados — o caminho normal
para quem só tem a base de minutos. Um replay que anda na velocidade errada, ou
que não anda, não seria pego.

**Correção:**

```java
    @Test
    @DisplayName("sem gerador de ticks, o tempo de mercado anda em barras inteiras")
    void withoutTicksTimeMovesInWholeBars() {
        ReplaySeries replay = ReplaySeries.of(day());   // barras de um minuto

        replay.advanceMarketTime(3 * 60_000L);

        assertEquals(3, replay.size(), "tres minutos de mercado nao revelaram tres barras");
    }
```

**Tentei refutar:** `grep -rn "advanceMarketTime"` na suíte retorna apenas
`ReplaySeriesTest:184` e `:188`. Nenhum irmão salva.

---

### A8a-13 — `ArraySeries` e `SyntheticTicks` não têm teste nenhum

**Onde:** ausência total.

`grep -rl "ArraySeries"` e `grep -rl "SyntheticTicks"` em `src/test/` não
retornam nada.

- **`ArraySeries.java`** (127 linhas) é o tipo concreto que `Renko.assemble`,
  `Timeframe.fold` e `MarketFile.read` todos devolvem. É exercitado de raspão
  por eles, mas suas próprias bordas — série vazia, índice negativo, o
  construtor sem `gaps`/`trades` contra o com — não têm asserção.
- **`SyntheticTicks.java`** (170 linhas) é o caminho **inventado** que o replay
  desenha quando não há ticks gravados. Tem `FLOOR = 6`, `CEILING = 60`,
  `WOBBLE = 0.9`, uma repartição de passos por perna (`counts`), uma ponte
  (`bridge`) e um arredondamento à grade de tick (`snap`) — cinco métodos
  privados de aritmética, zero testes. Uma mutação em qualquer um deles passa.

**Por que importa:** o caminho sintético é o que aparece na tela na maior parte
do histórico (só há ticks gravados para poucos pregões — ver a nota
`times-and-trades-do-profit`). É o único desenho que o leitor vê, e é o único
subsistema desta área sem uma linha de teste.

**Correção mínima para `SyntheticTicks`:** o caminho tem de (a) começar no
`open` e terminar no `close` da barra, (b) alcançar `high` e `low` e não
passar deles, (c) ter todo preço múltiplo do tick, (d) ter comprimento entre
`FLOOR` e `CEILING`, e (e) ser reproduzível pela semente. Cinco asserções sobre
uma barra fixa cobrem quatro dos cinco métodos.

**Tentei refutar:** procurei por `SyntheticTicks` em `src/test/` inteiro,
inclusive `ui/`. Nada.

---

## Achados BAIXA

### A8a-14 — metade de `everyBoundaryIsOnTheGrid` é aritmética sobre constantes

**Onde:** `RenkoTest.java:453-465`

**Trecho:**

```java
        for (double[] each : read) {
            int name = (int) each[0];
            double brick = (name - 1) * tick;
            double open = each[1];
            double close = each[2];

            assertEquals(brick, Math.abs(close - open), 1e-9,
                    name + "R: o tijolo lido nao mede (n-1) x tick");
            assertEquals(0.0, open % brick, 1e-9, ...);
            assertEquals(0.0, close % brick, 1e-9, ...);
        }
```

**A mutação que passa:** **qualquer** mutação em `Renko.java`. Este laço não
chama uma linha de código de produção: ele verifica que `187_875 % 25 == 0` e
que `|187_850 − 187_875| == 25`. São propriedades dos números literais escritos
duas linhas acima. É um `assertTrue(true)` disfarçado.

O segundo laço do mesmo método (`:471-481`) **tem** dentes: ele chama
`Renko.of(brick).apply(...)` e confere que toda abertura cai na grade. Uma
mutação em `gridUnder` é pega ali.

**Por que importa:** só pelo nome. `@DisplayName("toda fronteira cai na grade,
nos quatro tamanhos lidos do Profit")` sugere que os quatro tamanhos foram
conferidos contra o produto de referência; os quatro tamanhos são conferidos
contra a calculadora. O laço útil roda os quatro tamanhos, então a promessa
acaba entregue — por outro laço.

**Correção:** deixar o primeiro laço com um comentário dizendo que ele fixa a
**leitura** feita do Profit (é uma anotação de medição, não um teste), ou
fundir os dois num só que use os números lidos como entrada do renko.

**Tentei refutar:** o segundo laço do próprio método cobre a regra 2 (grade
absoluta). Não há perda de cobertura — é qualidade de nome.

### A8a-15 — o laço de vinte quadros de `theFormingBrickIsNeverCounted` é inerte

**Onde:** `TickRenkoTest.java:432-436`

**Trecho:**

```java
            for (int i = 1; i <= 20; i++) {
                live.advance(DAY, Long.MIN_VALUE + 1);
            }

            live.advance(DAY, Long.MAX_VALUE);
```

`Long.MIN_VALUE + 1` está antes de qualquer negócio da sessão, então as vinte
chamadas dobram zero barras. O teste efetivamente compara `advance(DAY, MAX)`
contra `add(DAY)` — o que ainda tem dentes nas três asserções finais, mas não é
"tocar a sessão em pedaços", que é o que o laço aparenta.

**Correção:** usar as mesmas fatias de `advancingInPiecesIsTheSameRenko:288`
(`first + (last - first + 1) * i / 20 + 1`), que realmente avançam.

**Tentei refutar:** o irmão `advancingInPiecesIsTheSameRenko` faz o avanço em
pedaços de verdade. A cobertura existe; o defeito é de leitura.

### A8a-16 — `theBrokersSurvive` afere o dicionário por uma faixa larga e uma constante compartilhada

**Onde:** `TapeFileTest.java:194-198`

**Trecho:**

```java
        long records = 24L + 5L * TapeFile.RECORD_BYTES;
        long names = Files.size(file) - records;

        assertTrue(names > 0 && names < 200,
                "the dictionary is " + names + " bytes, which is not four names held once");
```

Duas fragilidades: `RECORD_BYTES` é lida do **produto**, então o teste e o
produto casam por construção se ela estiver errada; e `24L` (o cabeçalho) é
literal e não conferido em lugar nenhum. A faixa `0 < names < 200` é um proxy
largo — os quatro nomes somam ~80 bytes, e mesmo repeti-los duas vezes ainda
caberia.

As três asserções de `brokerName` acima (`:189-191`) **têm** dentes de verdade
e pegam a mutação que importa (o encoding). A parte do dicionário é a fraca.

**Correção:** afirmar o número de nomes distintos pela API em vez do tamanho do
arquivo — por exemplo, que `brokerName(85)` devolva a mesma `String` (mesmo
conteúdo) para todas as linhas que citam 85, e que um código nunca visto devolva
null/vazio em vez de um nome de outro.

**Tentei refutar:** não há irmão. Mas o dano possível é pequeno — o dicionário
inchado custa disco, não número errado. Fica BAIXA.

### A8a-17 — `TapeFile` não tem o teste negativo que `TickFile` tem

**Onde:** ausência em `TapeFileTest.java`

`TickFileTest` tem `anotherFileIsRefused:214` e `theTwoFormatsDoNotOverlap:224`;
`MarketFileTest` tem `anotherFileIsRefused:102`, `anotherVersionIsRefused:118`
e `aTruncatedFileIsRefused:127`. `TapeFileTest` só afirma o positivo
(`assertTrue(TapeFile.isTape(...))`, `:284`). Um arquivo estranho, um arquivo
truncado e uma versão desconhecida de `.tape` não têm teste.

**A mutação que passa:** remover a verificação de magic em `TapeFile.read`.

**Correção:** espelhar os três métodos de `MarketFileTest`.

**Tentei refutar:** `grep` por `isTape` em `src/test/` retorna só a afirmação
positiva. Nenhum irmão salva. Fica BAIXA e não MÉDIA porque `TapeFile` só é
lido de um caminho que o próprio programa escreveu.

### A8a-18 — a guarda `count < 0 || count > Integer.MAX_VALUE` de `MarketFile` não tem teste

**Onde:** `MarketFile.java:236`, ausência em `MarketFileTest.java`

**A mutação que passa:** apagar a guarda. O erro passa a ser
`NegativeArraySizeException` em vez de `IOException` com mensagem — o
`aTruncatedFileIsRefused` não alcança, porque um `count` negativo faz
`expected` negativo e a comparação de tamanho já dispara primeiro. Consequência
pequena (a mensagem, não o número). BAIXA.

---

## Cobertura: o que NÃO tem teste nenhum

| comportamento | onde |
|---|---|
| `SyntheticTicks` inteiro — `countFor`, `pathFor`, `counts`, `bridge`, `snap`, e as constantes `FLOOR`/`CEILING`/`WOBBLE` | `SyntheticTicks.java:56-169` |
| `ArraySeries` como tipo próprio — bordas, os dois construtores, índice fora | `ArraySeries.java` (127 linhas) |
| `Timeframe.fold` — o método público que existe para o caminho dos ticks | `Timeframe.java:181`, consumido em `ui/replay/ReplaySession.java:408` |
| `Timeframe` acima de 30 minutos: `ONE_HOUR`, `MONTHLY`, e todo `ofMinutes(n)` com `n > 30` | `Timeframe.java:271-309` |
| `Timeframe` com origem desalinhada ao balde (o caso real dos ticks) | `Timeframe.java:271-281` |
| `TickBars.until`, `TickBars.countUntil` e `TickBars.range` — nenhum teste direto | `TickBars.java:149`, `:160`, `:187` |
| `Renko.formingAt` — nenhuma chamada em teste; a calda do tijolo em formação nunca é lida | `Renko.java:249` |
| `ReplaySeries.advanceMarketTime` com `ticks == null` | `ReplaySeries.java:164-169` |
| `ReplaySeries` alimentado por um `TickPath` que devolve null ou vetor vazio | `ReplaySeries.java:174`, `:199` |
| `ConcatSeries.timeAt/openAt/highAt/lowAt/volumeAt` fora da primeira parte | `ConcatSeries.java:101-140` |
| `SegmentedSeries.openAt/highAt/lowAt/volumeAt` | `SegmentedSeries.java` |
| `MarketFile.write` com `minutes != 1`, e o `minutes` de volta | `MarketFile.java:161` |
| `TapeFile.read` recusando arquivo estranho, truncado ou de versão desconhecida | `TapeFile.java` |
| Renko com `reversal >= 3` — só 1 e 2 aparecem em toda a suíte | regra 5 das sete |
| Renko sobre série de **uma** barra, e sobre série de preço constante com `forming` ligado | borda ausente |

**Sobre as sete regras do renko** — quais estão realmente exercitadas:

| regra | onde é exercitada | veredito |
|---|---|---|
| 1. tijolo = `(n−1) × tick` | `RenkoTest.everyBoundaryIsOnTheGrid:453` | só como aritmética sobre constantes (A8a-14); o renko não é consultado |
| 2. grade absoluta ancorada no zero | `RenkoTest.anchoredOnTheFirstOpen:405` e `everyBoundaryIsOnTheGrid:471` | **com dentes** |
| 3. não reinicia por pregão | `RenkoContinuedTest.thePiecesAgreeWithTheWhole:102`, `TickRenkoTest.thePiecesAgreeWithTheWhole:82` | **com dentes** |
| 4. fecha só ao PASSAR do nível | `RenkoTest.reversalCostsMore:109`, `RenkoGapTest.aPriceOnTheEdgeStaysBelow:218` | **com dentes** |
| 5. reversão desenha `steps − (reversal−1)`, começando `(reversal−1)` caixas adiante | `RenkoTest.reversalCostsMore:120-122`, `reversalOfOne:128` | com dentes para `reversal` 1 e 2; **nunca para 3 ou mais** |
| 6. contagem de negócios por janela de TEMPO | `RenkoCountTest` inteiro | **com dentes** |
| 7. o primeiro tijolo de um lote leva o acumulador inteiro | `RenkoCountTest.aJumpLeavesEmptyBricksBehind:158`, `RenkoGapTest.openingGapUp:129` | **com dentes** |

A calda não é uma das sete regras, e é justamente onde a suíte é cega (A8a-1).

---

## O que está LIMPO

Os testes abaixo têm dentes de verdade. Para cada um, a mutação concreta que
eles pegam — verificada simulando a execução sobre o fixture literal.

### `RenkoTest.touchingTheLevelIsEnough:171`
Barra `{100, 115, 100, 102}`. Pega a mutação "ler só o fechamento": com o
fechamento em 102 nenhum tijolo é assentado e `assertEquals(1, bricks.size())`
reprova. É o caso discriminante do desenho por extremos.

### `RenkoTest.bricksAreNeverUnlaid:186` e `theCountNeverFalls:331`
Seis quadros de uma barra em formação, com asserção monotônica `now >= most` e
**guarda de piso** (`assertTrue(most > 0, "no brick was ever laid, so the check
proved nothing")`). O piso é o que impede o teste de virar vazio — é o padrão
correto, e é exatamente o que falta em `TickRenkoTest.anEmptySessionIsHarmless:180`
(A8a-10). Pega a mutação "ancorar no primeiro fechamento em vez de no primeiro
abrir" (`Renko.java:357`), que é o defeito histórico documentado ali.

### `RenkoTest.noExcursionMeansNoTail:220` e `noTailPastTheClose:254`
Os dois lados do valor exato da calda, com `assertEquals` e não desigualdade.
Pegam a mutação "desenhar o excesso além do nível como calda"
(`Renko.java:456-462`) e a mutação "inventar calda onde o preço não foi".

### `RenkoGapTest.marks(...)` — os oito métodos
A função `marks` reduz o resultado a uma string tipo `"#.........#"` e
`assertEquals` compara a string inteira. Isso é uma asserção **exata sobre todos
os tijolos de uma vez**: qualquer deslocamento de um tijolo, qualquer troca de
lado, qualquer mudança de contagem reprova. Pega a mutação "dar os negócios
acumulados ao último tijolo do lote em vez do primeiro"
(`Renko.settle`, regra 7). O melhor padrão de asserção desta área inteira.

### `RenkoCountTest.theClosingPrintStartsTheNext:138` e `stampedAtTheFirstTrade:151`
`assertEquals(2, Counted.at(bricks, 0))` **e** `assertEquals(2, Counted.at(bricks, 1))`
na mesma execução — os dois lados da fronteira, não um. Pega a mutação "o
negócio que fecha o tijolo pertence a ele" (`Renko.java:388` vs `:488`), que é o
defeito medido contra o Profit em 03/09/2026.

### `RenkoCountTest.candlesAnswerUnknown:193`
Distingue `UNKNOWN` de `0`. Pega a mutação "candle conta zero negócios", que
transformaria "não sei" em "não houve" — e é a distinção que `Counted` existe
para manter.

### `SeriesMergeTest.oneSourceAtATime:76` e `theStepIsVisible:113`
`countBefore` com `<` trocado por `<=` produz seis contra sete barras e
`assertEquals(6, joined.size())` reprova. E `stepAt` é conferido em **dois**
cenários com valores exatos — `0.0` no dia calmo e `1_498.0` na rolagem — o que
descarta o "sempre devolve zero" e o "sempre devolve a diferença crua".
Discriminante nos dois sentidos.

### `TickFileTest.nothingIsLost:104` e `zeroIsNotAbsent:118`
O round-trip é ancorado num **literal**: a lista `ROWS` é texto do export real,
escrita à mão no topo do arquivo, e a asserção é
`assertEquals(ROWS, asExported(session))`. Qualquer coluna, ausência ou flag
perdida no caminho aparece como diferença de texto. `zeroIsNotAbsent` pega a
mutação que confunde "o campo veio zero" com "o campo veio vazio", que é a razão
de existir do formato.

### `TapeFileTest.theTapeIsTurnedAround:132`
Não só verifica a inversão — verifica os **empates**. Duas linhas do fixture
compartilham 09:00:01, e o teste afirma qual das duas vem primeiro depois de
invertida (`assertEquals(179_390, tape.lastAt(1))`). Pega a mutação "ordenar por
tempo em vez de inverter", que a asserção de monotonicidade sozinha aceitaria.

### `TapeFileTest.theBrokersSurvive:189-191`
Escolhe o travessão (0x96) e o apóstrofo tipográfico (0x92) — a única faixa onde
windows-1252 e ISO-8859-1 discordam. Pega a mutação `ISO_8859_1`, que qualquer
acento comum deixaria passar. Escolha de fixture com intenção.

### `TapeFileTest.eachSessionGetsItsOwnFile:265` e `TickFileTest.oneFilePerSession:157`
Os dois escrevem o caminho esperado **literalmente**
(`ticks.resolve("metatrader").resolve("2021").resolve("01").resolve(...)`), com
o comentário certo: "asserting it with the same expression that builds it would
agree with any layout at all". É o antídoto explícito para constante
compartilhada, e o de dezembro (`"12"` contra `"01"`) descarta prefixo em vez de
pasta.

### `TickLibraryTest.neverMoreThanThree:71` — salvo por um irmão
A asserção é de teto (`assertTrue(library.residentCount() <= 3)`), e a mutação
`RESIDENT = 1` passaria por ela. **O irmão salva:**
`theNeighboursAreTheOnesKept:96` afirma o conjunto exato
(`assertEquals(List.of(5, 6, 7), residentDays())`), e `theFurthestIsDropped:126`
afirma o conjunto exato depois de uma eviction. Os dois reprovam `RESIDENT = 1`.
Não é achado. Registro porque o teto está lá e é o padrão que custou quatro
defeitos ALTA nas outras áreas — o autor inclusive documenta ter evitado a
tautologia (`"THREE, written out, not TickLibrary.RESIDENT"`), o que mostra que
a armadilha era conhecida.

### `TickLibraryTest.theFurthestIsDropped:118`
O único lugar onde as duas políticas de descarte (por distância e por
menos-recentemente-usado) dão respostas diferentes, e o teste escolhe
exatamente esse ponto. Pega a mutação "trocar a política por LRU", que
`theNeighboursAreTheOnesKept` sozinho aceitaria.

### `TickLibraryTest.aMisplacedSessionIsNotListed:333`
Move o arquivo para o mês errado e afirma que a listagem e a busca continuam
concordando. Pega a mutação "listar por nome de arquivo em vez de por pasta",
que é a divergência entre duas perguntas que ninguém veria na tela.

### `TickRenkoTest.theLiveEdgeMovesEveryFrame:364`
O comentário registra por que a asserção fácil foi recusada: "comparing the two
totals was fragile: a fixture that happens to close a brick almost every frame
fails it while the edge is working perfectly, which is a test about the fixture
and not the code". A asserção escolhida (`movedWithoutClosing > 0`) pega a
mutação "não desenhar o tijolo em formação", que é o defeito relatado da tela.

### `SegmentedSeriesTest.theLastDayIsWhole:107`
Fatia de um dia só, com asserção de contagem **e** dos dois extremos
(`closeAt(0) == 9`, `closeAt(2) == 11`). Pega o off-by-one clássico "até o dia 4
às 00:00", que dropa o dia inteiro. É a borda certa, escolhida de propósito.

### `MarketFileTest.theFormatIsRead:78` e `aTruncatedFileIsRefused:128`
Os bytes são escritos à mão pelo teste, não pelo leitor — então uma mudança de
layout aparece. A segunda barra é afirmada explicitamente ("so an error in the
record size would not pass"), o que descarta a mutação em `RECORD_BYTES`. E a
mensagem de erro do truncado é conferida por conteúdo ("3"), não só o tipo da
exceção.

### `RenkoContinuedTest.anEmptyPieceIsHarmless:157`
`assertEquals(first.carry(), nothing.carry())` funciona por identidade — o ramo
de série vazia devolve o mesmo objeto `from`. Pega a mutação "reconstruir um
carry novo na série vazia", que perderia a `TradeTally` acumulada. Aceito como
limpo, com a ressalva de que a asserção depende de `applyFrom` retornar o
próprio objeto: se o retorno virar uma cópia, a asserção passa a comparar
records com `TradeTally` sem `equals` e vira falso-negativo. Vale um comentário
no teste.
