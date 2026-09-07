# B8a — os testes de domínio e de plataforma

Auditoria II, 07/09/2026. A pergunta desta área não é "o produto tem defeito?",
é **"este teste passaria com o produto quebrado?"**. Todo achado abaixo traz a
quebra concreta que o deixaria verde.

## O que foi lido

29 arquivos, **7.620 linhas**, todas.

```
src/test/java/br/com/jorge/reis/endeavourneo/domain/market/   20 arquivos  5.892 linhas
src/test/java/br/com/jorge/reis/endeavourneo/platform/         8 arquivos  1.579 linhas
src/test/java/br/com/jorge/reis/endeavourneo/OrphanJavadocTest.java          149 linhas
```

| arquivo | linhas | | arquivo | linhas |
|---|---:|---|---|---:|
| RenkoTest | 624 | | TimeframeTest | 457 |
| TickRenkoTest | 560 | | SyntheticTicksTest | 407 |
| TickLibraryTest | 535 | | TapeFileTest | 375 |
| SeriesCatalogTest | 374 | | JobServiceTest | 324 |
| ReplaySeriesTest | 295 | | SessionsTest | 291 |
| RenkoGapTest | 250 | | TickFileTest | 246 |
| RenkoCountTest | 232 | | RenkoWickBoundsTest | 232 |
| RenkoContinuedTest | 230 | | SegmentationTest | 226 |
| MarketFileTest | 210 | | SegmentedSeriesTest | 184 |
| SeriesMergeTest | 180 | | HistoryBeforeReplayTest | 170 |
| OneClockTest | 185 | | SettingsTest | 149 |
| OrphanJavadocTest | 149 | | FoldedTicksTest | 135 |
| TickLibraryClosingTest | 129 | | LanguageTest | 125 |
| ThemeSwitchTest | 116 | | BundleKeysTest | 115 |
| SettingsEncodingTest | 115 | | | |

Foram lidos também, como referência para julgar se cada asserção tem dentes:
`ReplaySeries.java`, `FoldedTicks.java`, `TickRenko.java`, `TickLibrary.exported()`,
`JobService.close()`, `Appearance.install`, `Settings`, `Language`, `pom.xml`,
`architecture/LayerBoundaryTest.java` (fora da área, lido só para conferir o que
ele cobre e o que não cobre).

Contagens auxiliares rodadas: javadoc órfão em `src/main/java` = **23** (bate
exatamente com o teto do `OrphanJavadocTest`, ver B8a-17); chaves usadas em
código × chaves nos dois bundles = nenhuma faltando hoje (ver B8a-11).

---

# ALTA

### B8a-1. `OneClockTest` inteiro passa com o candle lendo o FUTURO

`src/test/java/.../domain/market/OneClockTest.java:174`

```java
assertTrue(replay.highAt(0) >= high - 5.0,
        "frame " + frame + ": the renko has seen " + high
                + " and the candle is still at " + replay.highAt(0));
assertTrue(replay.lowAt(0) <= low + 5.0, ...);
```

com o `@DisplayName` em `:134`:

```java
@DisplayName("o candle e o renko contam os mesmos negocios, quadro a quadro")
```

**Problema.** O nome afirma **igualdade** ("os mesmos negócios") e as asserções
são duas desigualdades **do mesmo lado**: o candle não pode estar ATRÁS do que o
renko já viu. Nada impede o candle de estar **à frente**. E "à frente" é o
defeito que a classe `ReplaySeries` existe para impedir — o javadoc dela, em
`ReplaySeries.java:33`, diz `<b>What has not arrived cannot be read</b>`.

O outro teste do arquivo tem o mesmo buraco: `:125` afirma
`assertEquals(118_500, replay.highAt(0))` **depois** de 750 quadros. Um candle
que já mostrasse a máxima do minuto inteiro no quadro zero satisfaz isso também.

**Como quebro o produto para ele passar.** Em `ReplaySeries.advanceMarketTime`,
substituir o corpo por `completed = clamp(completed + 1); path = null;` — ou
seja, revelar a barra inteira no primeiro quadro em vez de formá-la. Então:
`replay.size()` continua 1 (o dia da fixture tem uma barra só),
`replay.highAt(0)` devolve `day.highAt(0)` = 118.500 ≥ `high − 5` sempre, e
`replay.lowAt(0)` = 118.000 ≤ `low + 5` sempre, em todos os 1.400 quadros. **Os
dois testes ficam verdes com a leitura do futuro ligada** — que é exatamente o
que o arquivo diz estar guardando.

**Consequência.** O único teste que compara candle e renko quadro a quadro, e
que foi escrito para uma regra medida do briefing ("um relógio só"), compra
confiança na metade errada da propriedade. A metade não checada é a que produz
número errado na tela durante o replay.

**Correção.** Acrescentar o teto no mesmo laço, com a folga que a regra permite
(a barra pode estar UM negócio atrás, nunca à frente):

```java
assertTrue(replay.highAt(0) <= high + 1e-9,
        "frame " + frame + ": the candle shows " + replay.highAt(0)
                + " and the ticks only reached " + high);
assertTrue(replay.lowAt(0) >= low - 1e-9, ...);
```

**Tentei refutar assim.** (a) Procurei o teto em `ReplaySeriesTest`:
`theFutureIsOutOfBounds` (`:165`) cobre ler uma barra **futura pelo índice**, não
a barra corrente mostrando preços que ainda não imprimiram — são propriedades
diferentes. (b) Procurei em `TickRenkoTest.nothingFromTheFuture` (`:125`): ele
guarda o lado do renko (`addUpTo`), não o do candle. (c) Conferi que a folga de
`5.0` não é o teto disfarçado: ela é aplicada ao mesmo lado da desigualdade
(`>= high - 5.0`), afrouxando o piso, não criando teto. Não caiu.

---

### B8a-2. `JobServiceTest`: apagar o relatório de última chance deixa o teste verde

`src/test/java/.../platform/JobServiceTest.java:248`

```java
assertEquals(0, swallowed.size(),
        "the report was written to whatever System.err happened to be at the "
                + "time, which during shutdown is a queue nothing will drain: "
                + swallowed.toString(java.nio.charset.StandardCharsets.UTF_8));
```

`@DisplayName` em `:199`: `"a failure nobody handled is reported where output really goes"`.

**Problema.** A frase tem duas metades — *é relatado* e *onde a saída realmente
vai*. Só a segunda é verificada, e verificada pela negativa: nada foi escrito no
`System.err` redirecionado. Que o relatório **tenha acontecido** não é afirmado
em lugar nenhum. `swallowed.size() == 0` é o resultado tanto de "escreveu no
fluxo capturado" quanto de "não escreveu em fluxo nenhum".

**Como quebro o produto para ele passar.** Em `JobService.close()`
(`JobService.java:447-452`), apagar o laço:

```java
for (Handle<?> handle : unclaimed) {
    handle.reportIfUnclaimed();
}
```

Ou esvaziar `reportIfUnclaimed()` (`JobService.java:164-170`). Nos dois casos
`swallowed.size()` continua 0 e o teste passa — enquanto a falha que ninguém
tratou volta a morrer em silêncio, que é a única coisa que o javadoc de
`unclaimed` (`JobService.java:296`) diz que a classe existe para impedir.

**Consequência.** A rede de segurança contra "o job morreu e ninguém soube" não
tem teste nenhum. O comentário do próprio teste (`:236-238`) diz que a primeira
versão dele "passava com o conserto dentro ou fora" — o buraco foi metade
tapado.

**Correção.** Capturar `System.err` **antes** de a classe carregar e afirmar o
positivo também:

```java
java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
System.setErr(new java.io.PrintStream(captured, true, StandardCharsets.UTF_8));
Class.forName(JobService.class.getName());          // agora FAILURES = captured
// ... redireciona para `swallowed`, roda o job órfão, fecha ...
assertEquals(0, swallowed.size(), ...);
assertTrue(captured.toString(UTF_8).contains("nobody is listening"),
        "the last-chance report was not written at all");
```

**Tentei refutar assim.** Procurei outro teste que cobrisse `reportIfUnclaimed`:
`grep -rn "reportIfUnclaimed|unclaimed|FAILURES" src/` devolve **só** o próprio
`JobService.java` e um comentário deste teste. Nenhuma outra asserção toca esse
caminho. Não caiu.

---

### B8a-3. `aFailedScanKeepsWhatItFound` procura um literal, não um comportamento

`src/test/java/.../domain/market/TickLibraryTest.java:528`

```java
assertFalse(rescue.contains("return List.of();"),
        "a failed scan still answers with an empty list");
assertTrue(rescue.contains("System.err"),
        "a failed scan still says nothing about why");
assertTrue(rescue.contains("days.size()"),
        "a failed scan does not say how much it did find");
```

**Problema.** O teste lê o **texto-fonte** de `TickLibrary.java` e casa três
substrings. Três formas de reintroduzir exatamente o defeito que ele nomeia
passam:

- `return java.util.Collections.emptyList();`
- `return new ArrayList<>();`
- `return List.of( );` (um espaço)

Em todas, `rescue.contains("return List.of();")` é falso, `System.err` continua
lá (a mensagem pode ficar) e `days.size()` continua lá — e as sessões já
encontradas voltam a ser descartadas. Pior: o recorte é

```java
int ends = source.indexOf("    public ", caught);
String rescue = source.substring(caught, ends < 0 ? source.length() : ends);
```

e `exported()` é hoje o **último** método do arquivo, então `ends` é −1 e
`rescue` é todo o resto do arquivo. No dia em que alguém acrescentar um método
depois, o recorte muda de tamanho sem aviso; e enquanto for o último, `System.err`
e `days.size()` poderiam vir de qualquer linha posterior ao `catch`, não do
bloco de resgate.

**Como quebro o produto para ele passar.** Trocar o corpo do `catch`
(`TickLibrary.java`, dentro de `exported()`) por:

```java
System.err.println(folder + ": listing failed (" + e + "); "
        + days.size() + " found");
return java.util.Collections.emptyList();
```

Compila, roda, devolve lista vazia numa varredura que falhou pela metade — e o
teste fica verde.

**Consequência.** O achado que este teste registra ("o walk é preguiçoso, o throw
chega no meio, e o que já foi achado ia junto") volta a ser possível sem que
nada acuse. E o efeito é perda de dado visível: a árvore mostra menos pregões
jogáveis do que existem.

**Correção.** Testar o comportamento, não o texto. `Files.walk` é difícil de
fazer falhar de forma portátil, mas o método já é testável por injeção de uma
pasta com um arquivo ilegível — ou, mais barato, extrair o corpo para um método
que receba a `Stream<Path>` e testá-lo com uma stream que lança na terceira
leitura:

```java
Stream<Path> explodes = Stream.concat(Stream.of(good1, good2),
        Stream.generate(() -> { throw new UncheckedIOException(new IOException("boom")); }));
assertEquals(List.of(day1, day2), library.listFrom(explodes));
```

**Tentei refutar assim.** (a) Verifiquei que o `return List.of();` de
`!Files.isDirectory(folder)` está ANTES do `catch`, então não é ele que a
asserção está pegando — o teste realmente aponta para o bloco certo. (b)
Verifiquei se algum outro teste cobre `exported()` numa varredura que falha:
`theExportIsListed` e `aMisplacedSessionIsNotListed` cobrem o caminho feliz e o
arquivo no mês errado, nenhum cobre o `IOException`. Não caiu.

---

### B8a-4. Nenhum teste lê máxima, mínima ou volume de uma barra JÁ FECHADA do replay

`src/test/java/.../domain/market/ReplaySeriesTest.java:39-72` e
`src/test/java/.../domain/market/HistoryBeforeReplayTest.java:36-69`

```java
/** Ten bars, closing at 0..9, one per minute. */
private static PriceSeries day() {
    return new PriceSeries() {
        ...
        @Override public double openAt(int index)  { return index; }
        @Override public double highAt(int index)  { return index; }
        @Override public double lowAt(int index)   { return index; }
        @Override public double closeAt(int index) { return index; }
    };
}
```

**Problema.** As duas fixtures que exercitam `ReplaySeries` têm
`open == high == low == close` em **todas** as barras, e nenhuma delas tem
volume. Nenhuma asserção da área lê `highAt`, `lowAt` ou `volumeAt` de uma
barra **fechada** de um `ReplaySeries` com preços que se distinguam:

- `ReplaySeriesTest` só lê `closeAt` e `size` — `:159` e `:171`;
- `HistoryBeforeReplayTest` só lê `closeAt` — `:134`;
- `OneClockTest:109/125/174` lê `highAt(0)`/`lowAt(0)`, mas o dia da fixture tem
  **uma barra só** e ela está sempre em formação (`path != null`), então só o
  ramo `forming` é exercitado, nunca a delegação.

`ReplaySeries` delega assim (`ReplaySeries.java:469-495`):

```java
public double highAt(int index) {
    return forming(index) ? high : day.highAt(check(index));
}
...
double whole = day.volumeAt(completed);
return Double.isFinite(whole) ? whole * cursor / (double) path.length : Double.NaN;
```

**Como quebro o produto para ele passar.** Trocar `day.highAt` por `day.lowAt` e
`day.lowAt` por `day.highAt` nas duas linhas acima. Toda a suíte de domínio fica
verde: nas fixtures degeneradas os dois valores são o mesmo número, e nenhuma
asserção da área lê o par com uma barra fechada. O gráfico do replay passa a
desenhar toda barra de história de cabeça para baixo. Do mesmo jeito, apagar o
`* cursor / path.length` do `volumeAt` (barra meio formada mostrando o volume
inteiro do minuto) não é notado por asserção nenhuma.

**Consequência.** É a definição de licença falsa: dez testes que se chamam
"Replay series" e a delegação de três dos cinco números fica sem cobertura, com
fixture que por construção não consegue discriminar.

**Correção.** Dar corpo às fixtures — é uma linha em cada:

```java
@Override public double openAt(int i)   { return index; }
@Override public double highAt(int i)   { return index + 0.5; }
@Override public double lowAt(int i)    { return index - 0.5; }
@Override public double closeAt(int i)  { return index; }
@Override public double volumeAt(int i) { return 100 + index; }
```

e acrescentar um teste que leia os cinco de uma barra fechada e os cinco de uma
barra em formação.

**Tentei refutar assim.** (a) `grep -rn "ReplaySeries" src/test/java/.../ui`
não devolve **nada** — os testes de interface não constroem `ReplaySeries`, então
não há cobertura vinda de fora da área. (b) Reli `TickRenkoTest` e
`FoldedTicksTest` procurando leitura indireta de `highAt/lowAt` do replay: o
tick renko lê `TickBars`, não `ReplaySeries`. (c) Conferi que
`OneClockTest:109` (`day.highAt(0) == 118_500`) é sobre `FoldedTicks`, não
sobre a delegação. Não caiu.

---

# MÉDIA

### B8a-5. A fixture de `aDayThatWasNotExportedIsEmpty` não consegue ver o fallback que o comentário acusa

`src/test/java/.../domain/market/FoldedTicksTest.java:94-100`

```java
@DisplayName("um dia que nao foi exportado vem vazio, nunca de outro lugar")
void aDayThatWasNotExportedIsEmpty(@TempDir Path folder) {
    // A chart of the ticks shows ticks, and where there are none it shows
    // nothing. Falling back to the candle file would put two different
    // measurements of the same hours in one window.
    assertEquals(0, FoldedTicks.day(folder, "win", TickSource.METATRADER,
            LocalDate.of(2021, 1, 4), ZONE).size());
}
```

**Problema.** A pasta é um `@TempDir` **vazio**. Não há arquivo de ticks e não há
arquivo de candles. O comentário diz que o teste guarda contra cair no arquivo de
candles — mas nessa pasta não existe arquivo de candles para cair.

**Como quebro o produto para ele passar.** Acrescentar ao fim de
`FoldedTicks.day` (`FoldedTicks.java:87-100`):

```java
} catch (IOException e) {
    return PriceSeries.empty();
}
// fallback novo, exatamente o que o comentário proíbe:
Path candles = folder.resolve(instrument + "-1m.bin");
return MarketFile.isSeries(candles) ? MarketFile.read(candles) : PriceSeries.empty();
```

Na pasta vazia isso devolve `empty()` e o teste passa, com o fallback ligado.

**Consequência.** O teste que registra a regra "um gráfico de ticks mostra ticks"
não consegue detectar a violação dela.

**Correção.** Escrever um `MarketFile` na mesma pasta antes de perguntar:

```java
MarketFile.write(folder.resolve("win-1m.bin"), someBars, 1);
assertEquals(0, FoldedTicks.day(folder, "win", TickSource.METATRADER, day, ZONE).size(),
        "the tick chart fell back to the candle file");
```

**Tentei refutar assim.** Li `FoldedTicks.day` inteiro: hoje ele de fato só
consulta `source.fileFor` e `source.read`, não há fallback. O achado não é sobre
o produto de hoje — é sobre o teste não conseguir defender a regra amanhã, que é
o que o comentário promete. Não caiu.

---

### B8a-6. `onlyTheSessionsAsked` promete ordem no nome e afirma só o tamanho

`src/test/java/.../domain/market/FoldedTicksTest.java:124-134`

```java
@DisplayName("so os pregoes pedidos, na ordem pedida")
void onlyTheSessionsAsked(@TempDir Path folder) throws IOException {
    ...
    assertEquals(3, FoldedTicks.over(folder, "win", TickSource.METATRADER,
            List.of(tuesday), ZONE).size());
}
```

**Problema.** "Na ordem pedida" com uma lista de **um** elemento. Ordem não é
exercitada em lugar nenhum do arquivo (`theSessionsBecomeOneSeries` testa
`all()`, cuja ordem vem da listagem do disco, não de uma lista do chamador). E
mesmo "só os pregões pedidos" é afirmado por tamanho: nada confere que os três
candles vieram da terça.

**Como quebro o produto para ele passar.** Em `FoldedTicks.over`
(`FoldedTicks.java:109-122`) acrescentar `days = new ArrayList<>(days); days.sort(null);`
antes do laço. O teste passa; um chamador que peça `[quarta, terça]` recebe
`[terça, quarta]`, com a série montada fora da ordem pedida.

**Consequência.** `over()` é o caminho que `SeriesWindow`/`Navigator` usam para
abrir um recorte de pregões escolhidos. A ordem decide onde cada barra cai no
eixo.

**Correção.**

```java
PriceSeries only = FoldedTicks.over(folder, "win", TickSource.METATRADER,
        List.of(tuesday), ZONE);
assertEquals(3, only.size());
assertEquals(119_000, only.openAt(0), 1e-9, "these are Monday's bars");

PriceSeries backwards = FoldedTicks.over(folder, "win", TickSource.METATRADER,
        List.of(tuesday, monday), ZONE);
assertEquals(119_000, backwards.openAt(0), 1e-9, "the list order was not honoured");
```

**Tentei refutar assim.** Procurei o teste de ordem em `FoldedTicksTest` e em
`TickRenkoTest` (`TickRenko.over` recebe uma lista parecida): `TickRenkoTest`
guarda a ordem por exceção (`theOrderIsEnforced:177`), mas isso é `TickRenko`,
não `FoldedTicks` — e `FoldedTicks.over` não recusa nada. Não caiu.

---

### B8a-7. `theFormingBrickIsNeverCounted`: o laço de vinte quadros não dobra nada

`src/test/java/.../domain/market/TickRenkoTest.java:451-455`

```java
for (int i = 1; i <= 20; i++) {
    live.advance(DAY, Long.MIN_VALUE + 1);
}

live.advance(DAY, Long.MAX_VALUE);
```

**Problema.** `Long.MIN_VALUE + 1` é um instante 292 milhões de anos antes do
pregão. Em `TickRenko.advance` (`TickRenko.java:216-222`),
`advancingBars.countUntil(when)` devolve 0, `upTo <= advanced` e o método volta
sem dobrar nada — vinte vezes. O laço inteiro é morto. A única coisa que o teste
mede é o estado **depois do dia inteiro** ter entrado de uma vez, que é
precisamente o instante em que a distinção entre tijolo assentado e tijolo em
formação é menos interessante.

**Como quebro o produto para ele passar.** Fazer `fold()` contar o tijolo em
formação como assentado **apenas enquanto o dia não acabou** — por exemplo, em
`advance`, `if (upTo < advancingBars.size()) { bricks.add(forming); }`. No fim
do dia `upTo == size()` e nada é acrescentado: `whole.size() == live.size()`
continua verdadeiro e o teste passa, enquanto durante todo o replay o renko
tocado tem um tijolo a mais que o mesmo renko aberto depois — que é exatamente o
que o comentário do teste (`:441-443`) diz ser impossível.

**Consequência.** O teste tem nome e comentário sobre o replay em curso e mede
só o fim dele.

**Correção.** Avançar por instantes reais, como os outros testes do arquivo já
fazem (`advancingInPiecesIsTheSameRenko:314`), e comparar a cada quadro:

```java
TickSeries ticks = library.load(DAY);
long first = ticks.timeAt(0);
long last = ticks.timeAt(ticks.size() - 1);

for (int i = 1; i <= 20; i++) {
    live.advance(DAY, first + (last - first + 1) * i / 20 + 1);

    TickRenko upToHere = new TickRenko(new Renko(10, 2), library);
    upToHere.addUpTo(DAY, first + (last - first + 1) * i / 20 + 1);

    assertEquals(upToHere.size(), live.size(),
            "frame " + i + ": the forming brick was counted among the settled ones");
}
```

**Tentei refutar assim.** Reli `TickRenko.advance` linha a linha para confirmar
que `Long.MIN_VALUE + 1` não lança nem reinicia estado: a primeira chamada entra
no ramo `!day.equals(advancing)` (advancing é null), carrega a sessão e volta em
`upTo <= advanced`; as 19 seguintes voltam no mesmo ponto. Confirmado morto. Não
caiu.

---

### B8a-8. `theCountNeverFalls`: vazio passa, e a fixture não distingue abertura de fechamento

`src/test/java/.../domain/market/RenkoTest.java:437-458`

```java
// The ruler is anchored on the first bar's OPEN, which never moves; anchoring
// it on the first CLOSE was the defect ...
double[] first = {100, 100, 100, 100};
int most = 0;

for (double[] step : new double[][]{...}) {
    int now = Renko.of(10).withForming(true).apply(ohlc(first, step)).size();

    assertTrue(now >= most, "the chart lost a bar it had already drawn: " + most + " then " + now);

    most = Math.max(most, now);
}
```

**Problema.** Duas coisas.

1. **Vazio passa.** Se `Renko.apply` devolvesse série vazia, `now` seria 0 em
   todos os passos, `0 >= 0` sempre, e o teste ficaria verde. O irmão
   `bricksAreNeverUnlaid` (`:321`) fecha exatamente esse buraco —
   `assertTrue(most > 0, "no brick was ever laid, so the check proved nothing")`
   — e aqui a linha não existe.
2. **A fixture não pode ver o defeito que o comentário nomeia.** A primeira
   barra é `{100, 100, 100, 100}`: abertura **igual** ao fechamento. Uma régua
   ancorada na abertura e uma ancorada no fechamento partem do mesmo número. E a
   barra que anda é a de índice 1, não a 0. O comentário afirma que o teste
   prende a âncora; a fixture é incapaz disso.

**Como quebro o produto para ele passar.** Trocar em `Renko` a âncora de
`openAt(0)` para `closeAt(0)`: nesta fixture os dois valem 100 e o teste passa
sem nem oscilar.

**Consequência.** Um comentário que mente sobre o que a asserção prende, no
arquivo mais sensível do domínio.

**Correção.** Somar `assertTrue(most > 0, ...)` no fim e dar à primeira barra uma
abertura diferente do fechamento (`{100, 130, 100, 125}`, que é o que
`anchoredOnTheFirstOpen:523` já usa) — ou corrigir o comentário para dizer que a
âncora está prendida em `anchoredOnTheFirstOpen` e este teste é só a
monotonicidade.

**Tentei refutar assim.** Verifiquei que a âncora ESTÁ coberta em outro lugar:
`anchoredOnTheFirstOpen` (`RenkoTest.java:523`) usa `ohlc({100, 130, 100, 125})`
e exige `openAt(0) == 100` — com âncora no fechamento daria 120. Por isso o
achado é MÉDIA e não ALTA: o defeito real é pego, o que está errado é o
comentário e a falta da guarda de vacuidade. Não caiu inteiro.

---

### B8a-9. `ThemeSwitchTest` deixa o look-and-feel instalado para todos os testes seguintes

`src/test/java/.../platform/ThemeSwitchTest.java:52-115`

```java
void darkSurvivesNight() {
    Appearance.install(Theme.DARK);
    ...
    Appearance.install(Theme.DARK);
    assertEquals(darkFirst, background(), ...);
}
```

**Problema.** Não há `@AfterEach`. `Appearance.install` (`Appearance.java:66`)
chama `forgetPalettes()` e `UIManager.setLookAndFeel` — registro **global e
permanente**, como o javadoc da própria classe diz em `:41-44`. Os quatro testes
deste arquivo trocam o look-and-feel do JVM e nenhum devolve o que encontrou. O
surefire roda tudo num fork só, então o que sobrar (NIGHT, DARK ou LIGHT,
conforme a ordem) fica de pé para `ChartViewTest`, `MainWindowTest`,
`CollapsiblePaneTest` e tudo o mais que cria componente.

Também: cada teste chama `install` fora da EDT, e a convenção 8 diz "componente
Swing só na EDT" e "look-and-feel antes de qualquer componente".

**Consequência.** É estado global deixado para o próximo — e das piores
variedades, porque muda cores e métricas de fonte, que é o que os testes de
geometria de gráfico medem. Um teste de outra área que passe ou falhe conforme
qual `@Test` deste arquivo rodou por último é indistinguível de um defeito real.

**Correção.**

```java
private static javax.swing.LookAndFeel original;

@BeforeAll static void remember() { original = UIManager.getLookAndFeel(); }

@AfterAll static void restore() throws Exception {
    UIManager.setLookAndFeel(original);
}
```

**Tentei refutar assim.** (a) Procurei `@AfterEach`/`@AfterAll` no arquivo: não
há nenhum. (b) Procurei configuração de execução em paralelo
(`junit-platform.properties`): não existe, então não é corrida — é ordem, o que
é reproduzível mas ainda assim uma dependência entre testes. (c) Verifiquei se
algum outro teste reinstala o LAF no começo: `ChartViewTest` e companhia estão
fora da minha área e eu não as li, então não posso afirmar que ninguém se
protege; mas a obrigação de limpar é de quem sujou. Não caiu.

---

### B8a-10. `LanguageTest` escreve nas configurações reais do leitor fora do Maven

`src/test/java/.../platform/LanguageTest.java:50` e `:72`

```java
private final Language chosen = Language.remembered();
...
Language.ENGLISH.remember();
Language.install();
```

`Language.remember()` faz `Settings.settings().put(KEY, code)`, e
`Settings.SETTINGS` aponta para
`System.getProperty("endeavourneo.home", System.getProperty("user.home"))/.endeavourneo/settings.properties`
(`Settings.java:81-86`).

**Problema.** `endeavourneo.home` é definido em **um só lugar**: o
`maven-surefire-plugin` do `pom.xml:86`. A convenção 10 da casa diz que **não há
`mvn` no PATH** e que a suíte se roda com `javac` do JBR mais um `JupiterRunner`
que recebe nomes de classe. Nesse caminho — o documentado, o usado — a
propriedade não é passada e este teste **grava o arquivo de configurações real do
usuário**. O próprio javadoc do campo (`:44-49`) diz que isso "não é uma coisa
que um teste tenha o direito de fazer", e mesmo assim o teste faz e conta com o
`@AfterEach` para desfazer: se ele falhar entre o `remember()` e o `restore()`,
ou se a JVM morrer, o idioma da aplicação do leitor fica trocado.

`SegmentationTest` (`:51-62`) mostra que a casa já sabe fazer certo:
`@TempDir` + `Segmentation.useForTest(...)` + `stopUsingTestStore()`, com o
comentário "limpar depois só bastaria enquanto todo teste passa".

**Consequência.** Efeito colateral fora da pasta temporária, dependente de uma
propriedade que o modo de build documentado não define.

**Correção.** Dar a `Settings.settings()` a mesma costura que
`Segmentation.useForTest` tem, e usá-la aqui — ou, mais barato, testar
`Language.install()` sem passar por `remember()`, injetando o código escolhido.

**Tentei refutar assim.** (a) Procurei `endeavourneo.home` em todo o repositório:
só `Settings.java` e `pom.xml`. Não há `.mvn/jvm.config`, nem script, nem
`MAVEN_OPTS` versionado. (b) Verifiquei se `HOME` é lido tarde o bastante para
uma costura em runtime: é `static final`, resolvido no carregamento da classe —
não dá para consertar por `System.setProperty` dentro de um `@BeforeAll` que rode
depois de a classe carregar. Não caiu.

---

### B8a-11. `BundleKeysTest` nunca confere que as chaves usadas no código existem

`src/test/java/.../platform/BundleKeysTest.java:88-114`

Os dois testes do arquivo comparam **os bundles entre si**: nenhuma chave
repetida, e os dois com o mesmo conjunto. Nada compara o bundle com o **código**.

**Problema.** A convenção 9 diz que chave faltando aparece como `!chave!` na
tela, "nunca vazio — rótulo em branco parece defeito de layout e se caça por
horas". Ou seja: o caso está tratado no produto, e a consequência é texto
errado na tela. Mas o teste que existe para os bundles não vê esse caso, porque
uma chave que falta nos **dois** bundles satisfaz `bothBundlesSayTheSameKeys`
perfeitamente.

**Como quebro o produto para ele passar.** Renomear
`chart.renkoNeedsTicks` para `chart.renkoNeedsTick` nos dois `.properties` (ou
errar a digitação no código). Os dois testes continuam verdes, e o diálogo passa
a dizer `!chart.renkoNeedsTicks!`.

**Consequência.** É a única barreira automática que existe entre o bundle e a
tela, e ela olha para o lado errado.

**Correção.** Um terceiro teste, no mesmo estilo de varredura de fonte já usado
por `OrphanJavadocTest` e `LayerBoundaryTest`:

```java
@Test
@DisplayName("toda chave pedida pelo codigo existe no bundle")
void everyKeyTheCodeAsksForExists() throws IOException {
    Set<String> defined = new TreeSet<>(keysOf(ENGLISH));
    Pattern literal = Pattern.compile("Messages\\.(get|text|label|mnemonic)\\(\"([^\"]+)\"");
    List<String> missing = new ArrayList<>();
    // ... varre src/main/java, ignora as chamadas com concatenacao (a chave e
    //     montada em runtime: "settings.language." + code) ...
    assertEquals(List.of(), missing, "a screen will show !key! instead of words");
}
```

**Tentei refutar assim.** Rodei a comparação à mão: 216 chaves literais no
código, 335 definidas, e **nenhuma faltando hoje** — o único candidato,
`navigator.ticks`, está definido nos dois bundles (`messages.properties:48`,
`messages_pt_BR.properties:48`); o outro, `settings.language.`, é prefixo de
chave montada em runtime. Então não há defeito aberto: o achado é o buraco de
cobertura, não um `!chave!` na tela hoje. Por isso MÉDIA e não ALTA.

---

### B8a-12. `everyOwnerCloses` desliga a si mesmo em silêncio quando o CWD não é a raiz

`src/test/java/.../domain/market/TickLibraryClosingTest.java:83-84`

```java
org.junit.jupiter.api.Assumptions.assumeTrue(Files.isDirectory(sources),
        "not running from the project root");
```

**Problema.** `assumeTrue` **pula** o teste, e um teste pulado é verde no
relatório. Este é o único guardião da regra "todo dono fecha o que abre" para
`TickLibrary` — que abre thread de leitura e segura até três sessões de ticks,
centenas de megabytes. Rodado de um diretório que não seja a raiz do projeto, a
regra some sem uma palavra.

É o mesmo padrão que a convenção 3 registra como já tendo custado: *"os testes
de GUI do `endeavour_neo` passaram com a base apagada do disco"*.

**Como quebro o produto para ele passar.** Não é preciso quebrar o produto:
basta rodar a suíte com o CWD em `$TEMP/claude`, que é onde a convenção 10 diz
que ficam as listas de fontes e o classpath do `JupiterRunner`. O teste vira
verde-por-omissão, e daí `new TickLibrary(...)` sem `close()` pode entrar no
código sem barreira.

**Consequência.** O guardião de um recurso que vaza thread e centenas de MB pode
estar desligado sem que o relatório diga.

**Correção.** Trocar a suposição por uma falha, como os irmãos da mesma área já
fazem (`OrphanJavadocTest:63` e `BundleKeysTest:52-56` usam o mesmo caminho
relativo e simplesmente estouram `NoSuchFileException` se o CWD estiver errado —
alto e claro):

```java
assertTrue(Files.isDirectory(sources),
        "the suite must run from the project root; this rule cannot be checked otherwise");
```

**Tentei refutar assim.** (a) Conferi se o `pom.xml` fixa o `workingDirectory` do
surefire: não fixa (o default do surefire é `${basedir}`, então **sob Maven** o
teste roda). (b) Conferi se algum outro teste cobre o fechamento: `closingIsVisible`
(`:60`) testa que `close()` funciona, não que alguém chama. O achado vale para o
modo de build que a casa documenta como o usado. Não caiu.

---

### B8a-13. Sete laços de asserção que passam sobre o conjunto vazio

Nenhum destes afirma que a série produzida tem alguma coisa dentro. Se o produto
passar a devolver série vazia, o laço não executa e o teste fica verde:

| arquivo:linha | laço | guarda? |
|---|---|---|
| `RenkoTest.java:383` | `for (int i = 0; i < bare.size(); i++)` — `tailsCanBeTurnedOff` | não |
| `RenkoTest.java:451` | `theCountNeverFalls` (ver B8a-8) | não |
| `RenkoWickBoundsTest.java:132` | `noTailPastTheClose` | não |
| `TickRenkoTest.java:215` | `anEmptySessionIsHarmless` | não |
| `TickRenkoTest.java:112` | `thePiecesAgreeWithTheWhole` (`0 == 0` passa) | não |
| `TickRenkoTest.java:324` | `advancingInPiecesIsTheSameRenko` | não |
| `SeriesMergeTest.java:104` | `theClockKeepsMovingForward` | não |

O contraste está dentro do próprio repositório, escrito pela mesma mão:
`RenkoContinuedTest.java:113` — `assertTrue(whole.size() > 100, "the walk laid too
few bricks to prove anything")`; `RenkoWickBoundsTest.java:120` —
`assertTrue(bricks.size() > 1_000, ...)`; `RenkoTest.java:321` —
`assertTrue(most > 0, "no brick was ever laid, so the check proved nothing")`;
`RenkoGapTest.java:123` — `assertTrue(bricks.size() > 0, ...)`. O padrão é
conhecido e foi aplicado em quatro lugares e esquecido em sete.

**Como quebro o produto para eles passarem.** Um `if (true) return
PriceSeries.empty();` no início de `Renko.laydown` derruba muitos outros testes,
então isoladamente cada um destes é redundante. O caso que **não** é redundante
é `TickRenkoTest.thePiecesAgreeWithTheWhole` e `advancingInPiecesIsTheSameRenko`:
os dois comparam **dois caminhos** do mesmo produto. Fazer `TickRenko.fold`
recusar tudo (`if (bars.size() >= 0) return false;`) deixa `whole.size()` e
`inPieces.size()` ambos em 0 — os dois testes passam comparando nada com nada, e
`aFrameWithNothingNewLaysNothing:349` seria o único a cair.

**Correção.** Uma linha por teste, no formato que o repositório já usa:

```java
assertTrue(wholeInOne.size() > 2, "the fixture laid too few bricks to compare anything");
```

**Tentei refutar assim.** Para cada um dos sete, procurei um irmão no mesmo
arquivo que caísse com a mesma quebra. Achei para cinco deles (`tailsCanBeTurnedOff`,
`theCountNeverFalls`, `noTailPastTheClose`, `anEmptySessionIsHarmless`,
`theClockKeepsMovingForward`) — por isso o achado é MÉDIA e não ALTA. Para os
dois de `TickRenkoTest` que comparam caminho contra caminho não achei, e é isso
que sustenta o achado. Não caiu.

---

# BAIXA

### B8a-14. Metade de `everyBoundaryIsOnTheGrid` compara constante com constante

`src/test/java/.../domain/market/RenkoTest.java:565-577`

```java
for (double[] each : read) {
    int name = (int) each[0];
    double brick = (name - 1) * tick;
    double open = each[1];
    double close = each[2];

    assertEquals(brick, Math.abs(close - open), 1e-9, ...);
    assertEquals(0.0, open % brick, 1e-9, ...);
    assertEquals(0.0, close % brick, 1e-9, ...);
}
```

`read`, `tick`, `brick`, `open` e `close` são todos literais do próprio teste.
Este laço **não pode falhar** — nenhuma linha dele toca o produto. Ele documenta
a medição feita no Profit, o que tem valor, mas aparece no relatório como três
asserções verdes por tijolo.

A regra `(n − 1) × tick` **é** código de produção — `PeriodCatalog.java:75`,
`return (name - 1) * TICK;` — e é ela que deveria estar do lado esquerdo:

```java
assertEquals(brick, PeriodCatalog.brickFor(name), 1e-9,
        name + "R: o programa nao calcula (n-1) x tick");
```

O segundo laço do mesmo teste (`:583-592`) toca o produto de verdade e tem
dentes; o problema é só o primeiro. **Tentei refutar assim:** conferi que
`PeriodCatalog` fica em `ui.chart` e é coberto por `PeriodCatalogTest`, fora
desta área — então a regra não está descoberta, só está afirmada duas vezes, uma
delas contra o vácuo.

### B8a-15. Dois testes de domínio importam de `ui`

`src/test/java/.../domain/market/RenkoContinuedTest.java:25` e
`src/test/java/.../domain/market/RenkoWickBoundsTest.java:23`

```java
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;
```

A convenção 4 diz que a dependência anda num sentido só e que "no
`endeavour_neo` isso vira **teste**, não convenção". O teste existe —
`architecture/LayerBoundaryTest.java` — mas ele varre
`Path.of("src", "main", "java")` (`:64`), então o lado de teste do domínio pode
importar da interface à vontade. Efeito prático: a suíte de domínio não compila
sem o pacote `ui`, que é o oposto do que a regra existe para garantir.
`RandomWalkSeries` é um gerador de série sem nada de gráfico nele — o lugar dele
é `domain/market`, ou um `testFixtures` do domínio. **Tentei refutar assim:**
li `LayerBoundaryTest.scan` para confirmar que `SOURCES` é só `src/main/java` e
que não há um segundo teste varrendo `src/test/java`. Não caiu.

### B8a-16. `assertTrue(x == false)` onde cabe `assertFalse`

`src/test/java/.../domain/market/RenkoTest.java:465` e `:469`

```java
assertTrue(Renko.of(10).withWicks(false).hasWicks() == false);
...
assertTrue(Renko.of(10).withWicks(false).withForming(true).hasWicks() == false);
```

Nenhum dos dois tem mensagem, e a falha vira `expected: <true> but was: <false>`,
que não diz nada sobre rabo de tijolo. `assertFalse(..., "os rabos continuaram
ligados depois de desligados")`.

### B8a-17. `OrphanJavadocTest`: nome com erro de digitação e teto que só sobe

`src/test/java/.../OrphanJavadocTest.java:143` e `:66`

```java
void thebacklogDoesNotGrow() throws IOException {
...
private static final int BACKLOG = 23;
```

`thebacklogDoesNotGrow` — falta a maiúscula. E `assertTrue(all.size() <= BACKLOG)`
nunca aperta: se o passivo cair para 5, o teto continua 23 e dezoito javadoc
órfãos novos podem entrar sem que nada acuse. **Rodei a contagem** com o mesmo
algoritmo do teste: hoje o passivo é **exatamente 23**, então o teto está
justo neste instante — o achado é sobre a mecânica, não sobre folga atual. Uma
falha que dissesse "o passivo caiu para N; abaixe o teto" resolveria, e é o que
a própria javadoc da classe (`:56`) pede: *"Drive it to zero and delete the
ceiling"*.

### B8a-18. O leitor de `.properties` do `BundleKeysTest` não é um leitor de `.properties`

`src/test/java/.../platform/BundleKeysTest.java:59-72`

```java
if (trimmed.isEmpty() || trimmed.startsWith("#") || !trimmed.contains("=")) {
    continue;
}
```

O formato também aceita `!` como comentário, `:` e espaço como separador, e
continuação de linha com `\` no fim. Qualquer uma dessas formas faz o teste
**pular a linha em silêncio** — e uma chave pulada não pode ser vista como
duplicada nem como faltante. **Conferi que hoje não custa nada:** nos dois
bundles, zero linhas com `!` inicial, zero com `\` final, zero com separador
`:` — todas as 383 linhas de cada arquivo são `#`, vazia, ou `chave = valor`.
É dívida latente, não defeito aberto.

### B8a-19. `noSeriesWearsTheMarketsName` deriva o esperado da própria resposta do produto

`src/test/java/.../platform/SeriesCatalogTest.java:196-197`

```java
String market = SeriesCatalog.displayOf("winfut-1m")
        .substring(0, SeriesCatalog.displayOf("winfut-1m").indexOf('-'));
```

`market` sai do produto e volta como expectativa em quatro asserções seguintes.
O que sobra com dentes é só o **sufixo** (`-FUT`, `-N`, `-FULL`, `-WIN`); se
`displayOf` passasse a devolver `"Bitcoin-FUT"` para `winfut-1m`, tudo continua
verde. Dá para fechar sem prender o idioma do bundle afirmando o prefixo uma vez
contra a fonte dele — `Messages.market("win")` — em vez de contra a saída do
método sob teste. **Tentei refutar assim:** o motivo de o teste ser assim está
escrito e é bom (o nome do mercado vem do bundle e muda de idioma). Por isso
BAIXA: a intenção é defensável, a execução é que compra menos do que parece.

---

# LIMPO

O que foi conferido e está certo, e **como** foi conferido.

**`RenkoTest` — as sete regras do renko.** Confrontei cada asserção com a regra
correspondente do briefing. As três que mais importam têm fixture que
discrimina de verdade:

- *fecha quando PASSA, não quando alcança*: `touchingTheLevelIsNotEnough:192`
  usa o par `110` (nada) e `110.01` (um tijolo) — o par mais apertado possível.
  Apagar o epsilon de `Renko.steps` derruba a primeira metade. É o teste com
  mais dentes do arquivo.
- *grade absoluta ancorada no zero*: `everyBoundaryIsOnTheGrid:583` abre em
  189.480, que **não** é múltiplo de 25, 50, 100 nem 200 — âncora no primeiro
  preço falharia nos quatro tamanhos.
- *o volume vai INTEIRO para o primeiro tijolo do lote*:
  `volumeGoesToTheFirstBrickOfABatch:261` exige 1.000 no primeiro e 0 nos
  outros dois, e ainda prende o "três, não quatro" de rebote.
- *volume ausente é NaN*: `absentVolumeStaysAbsent:529`, e o dobro em
  `TimeframeTest.anUnmeasuredBarIsNotZero:162`, que separa "zero" de "não sei"
  por bucket e não pela barra zero.

**`RenkoWickBoundsTest` — as duas fixtures narradas.** `theSecondExtremeIsNotThrownAway:143`
e `theOtherExtremeIsNotYetReached:184` são os dois casos em que li o comentário
e refiz a aritmética à mão para conferir se o número escolhido discrimina. Em
`:219`, `assertTrue(bricks.lowAt(up) >= 80.0 - 2 * 10)` — o limite é 60 e o
valor errado seria 50, então a asserção separa. O comentário até registra que o
primeiro rascunho usava 60, "que fica exatamente NO limite e portanto não
provava nada". Correto e verificado.

**`TimeframeTest`.** As duas armadilhas de fuso (`dailyKeepsItsOwnDate:273`,
`weekStartsOnMonday:296`) têm fixture que só passa com a regra certa: 18:00 em
São Paulo é 21:00 UTC, e 1970-01-01 foi quinta. `theStampIsTheSlotAndNotTheFirstBar:182`
existe justamente porque o irmão anterior começa às 09:00 e não distingue —
está dito no comentário e conferido por mim: com barras a partir de 09:02 as
duas respostas divergem. `aScaleAboveADayDoesNotBecomeDaily:348` afirma o número
exato (20 de 30), não uma desigualdade.

**`SessionsTest`.** As quatro formas de um cache errar têm um teste cada, e a
quinta (ser mais lento) está dita como medida e não asserida — o que é a decisão
certa. `askingTwiceWalksOnce:142` conta leituras em vez de cronometrar.
`theZoneIsPartOfTheQuestion:262` troca Tóquio por Kiritimati com o motivo
escrito: Tóquio é +12 de São Paulo e a DATA sairia igual. `Sessions.forget()` em
`@BeforeEach` isola o estado global.

**`SyntheticTicksTest`.** Cada limite tem número medido por trás e o comentário
diz de onde veio. Recalculei os dois que dava para checar: `30,1 × 10^1,241 ≈ 524`
cai dentro de `(420, 640)` e `30,1 × 20^1,241 ≈ 1.237` dentro de `(980, 1450)`.
Os limites são largos de propósito e o teste diz por quê ("não estão aqui para
prender as constantes; estão aqui para que um modelo de FORMA errada não passe").
`thePathReproducesTheFourNumbers:137` prende o contrato exato; `theExtremesAreNotTouchedEarly:288`
é o único que pega a regra "um tick a menos". Nenhum buraco encontrado.

**`TickFileTest` e `TapeFileTest`.** Round-trip texto→binário→texto, com as
linhas difíceis do export real na fixture: a linha de abertura que diz zero para
tudo, dois ticks no mesmo milissegundo, e o `NEWEST_FIRST` escrito ao contrário
de propósito ("uma fixture em ordem cronológica deixaria passar um conversor que
nunca inverte"). `theBrokersSurvive:180` escolhe `–` e `’`, em 0x96 e 0x92, que
é a faixa onde windows-1252 e ISO-8859-1 discordam — sem isso latin-1 passaria.
`oneFilePerSession:163` escreve o caminho **literalmente** em vez de chamar
`fileFor`, com o motivo dito. Nada a acusar.

**`MarketFileTest`.** Escreve os bytes à mão em vez de pelo escritor, que é a
única forma de o teste notar uma mudança de layout. `theFormatIsRead:134` lê a
**segunda** barra também, o que pega erro no tamanho do registro.
`aSliceIsExactlyTheBarsAskedFor:87` confere início, fim e carimbo.

**`SettingsEncodingTest`.** Os dois lados: `alreadyDamagedFilesAreRepaired:86`
com quatro rodadas de estrago e `healthyTextIsNotTouched:104` com `ÃGUA`, que é
o caso perigoso do reparo. É o par certo.

**`SegmentationTest`.** O único arquivo da área que faz o isolamento de estado
como deve: `@TempDir` + `useForTest` em `@BeforeEach` + `stopUsingTestStore` em
`@AfterEach`, com o comentário dizendo por que limpar depois não bastaria. Serviu
de referência para B8a-10.

**`RenkoContinuedTest`.** `twoCarriesWithTheSameNumbersAreEqual:177` é
exatamente o achado que esta auditoria procura, encontrado e consertado por quem
escreveu: um teste que "afirmava identidade e chamava aquilo de igualdade".
`aCarryDoesNotShareItsTally:199` e `anImpossibleCarryIsRefused:212` fecham o
tipo. `thePiecesAgreeWithTheWhole:105` corta em quatro pontos diferentes e tem a
guarda de vacuidade.

**`JobServiceTest`, salvo B8a-2.** `cancellationIsCooperative:148` documenta e
corrige um caso perfeito de licença falsa (uma disjunção cujo segundo operando
era asserido sozinho na linha seguinte, e cujo `await` sempre estourava dez
segundos). `cancellingIsItsOwnOutcome:259` drena a EDT com `invokeAndWait` antes
de negar. `anErrorIsAFailureAndNotAResult:114` cobre `OutOfMemoryError`, que não
é `Exception`.

**`TickLibraryTest`, salvo B8a-3.** `neverMoreThanThree:71` escreve **3** por
extenso e explica que comparar com `TickLibrary.RESIDENT` seria tautologia — e
que a primeira versão fazia isso e passava com o teto em cem.
`theFurthestIsDropped:130` é o único que separa "por distância" de "menos
recentemente usado". `theEndsAreTheBarsOwn:378` usa uma barra deliberadamente em
desacordo com os ticks (117.900/118.100 contra negócios em 118.000..118.004) e
confere que os cinco negócios reais sobrevivem no meio — sete preços no caminho.
`theTicksAndTheBarsShareOneZone:420` usa Kiritimati porque quatorze horas fazem
os dois lados caírem em DIAS diferentes.

**`SeriesCatalogTest`, salvo B8a-19.** `aWindowCanBeAnchoredAtASegmentsEnd:306`
tem a asserção que fecha a armadilha:
`assertTrue(fromTheStretch.timeAt(99) < fromTheFile.timeAt(0), "the fixture does
not reproduce the gap it is about")` — uma guarda sobre a própria fixture.
`base()` grava pelo `fileOf` com o motivo dito ("uma fixture que montasse o
caminho sozinha continuaria passando no dia em que a regra mudasse"). `@AfterEach`
desfaz as duas peças de estado global.

**`ReplaySeriesTest`, salvo a fixture degenerada (B8a-4).** As propriedades do
relógio estão bem prendidas: `theClockNeverGoesBackwards:81` sobre 400 quadros,
`theClockMovesInsideTheBar:271` com os dois lados (moveu, e não moveu uma barra
inteira), `aMinuteWithoutTicksDoesNotFreeze:121` com `finished()` além do
`size()`. O congelamento e o relógio andando para trás não passam mais.

**O que confirmei que NÃO é achado:**

- o teto de 23 do `OrphanJavadocTest` — rodei a contagem, é 23 exato, não há
  folga escondida;
- chaves de bundle faltando — comparei as 216 usadas com as 335 definidas, zero
  faltando; `navigator.ticks` está nos dois arquivos, na linha 48;
- execução em paralelo agravando os achados de estado global — não há
  `junit-platform.properties`, então é ordem, não corrida;
- `TimeframeTest.theChosenZoneReachesTheOneArgumentFold:208` e
  `TickLibraryTest.theTicksAndTheBarsShareOneZone:420` mexem no fuso global
  `Timeframe.useZone`, mas **restauram em `finally`** — cheguei a abrir os dois
  achando que era vazamento e os dois estão corretos.
