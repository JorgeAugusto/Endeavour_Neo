# B8b — os testes de interface

Auditoria II, 07/09/2026. Área: `src/test/java/br/com/jorge/reis/endeavourneo/ui/`.

## O que foi lido

**47 arquivos, 9.840 linhas — todas.** Conferido com `wc -l`.

> Nota de método, que vale como aviso para as outras áreas: a primeira contagem
> deu **46 arquivos e 9.705 linhas**. `StudyScaleTest.java` (135 linhas) não
> apareceu no `find | xargs wc -l` inicial — provavelmente um lote do `xargs` que
> se perdeu na ordenação. Só apareceu ao recontar por diretório. **Conferir a
> contagem de arquivos por dois caminhos independentes**, não só o total de
> linhas.

| pacote | arquivos | linhas |
|---|---:|---:|
| `ui/chart` (raiz) | 22 | 4.093 |
| `ui/chart/overlay` | 3 | 777 |
| `ui/chart/study` (raiz) | 3 | 576 |
| `ui/chart/study/rsi` | 1 | 315 |
| `ui/chart/study/stochastic` | 1 | 293 |
| `ui/chart/style` | 2 | 441 |
| `ui/replay` | 10 | 1.862 |
| `ui/series` | 1 | 275 |
| `ui/shell` | 4 | 1.208 |
| **total** | **47** | **9.840** |

Além dos testes, li o código de produto necessário para tentar derrubar cada
achado: `ChartCanvas.java` (3.009 linhas, trechos), `Settings.java`,
`ReplayPreferences.java`, `CollapsiblePane.java`, `DatePicker.java`,
`ReplaySession.step`, `Renko` (construtores), `SeriesSummary.spanBetween`,
e o `pom.xml`.

**A pergunta desta área não é "o produto está errado".** É: *este teste passaria
com o produto quebrado?* Para todo achado abaixo está escrito **como quebrar o
produto de forma que o teste continue verde** — sem isso o achado não entrou.

---

## ALTA

### B8b-1. O único teste da escala própria do IFR passa com o indicador desenhando NADA

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/study/rsi/RelativeStrengthTest.java:46-85`

```java
    @DisplayName("on its own scale it reads the last CLOSED coarse bar, never the one forming")
    void onItsOwnScaleItDoesNotReadTheFuture() {
        // The RSI's own-scale CALCULATION had no test at all. [...]
        ...
        // Nothing before the first five-minute bar has closed.
        for (int bar = 0; bar < 5; bar++) {
            assertTrue(Double.isNaN(at(rsi, bar)),
                    "bar " + bar + " drew a value from a five-minute bar still forming");
        }

        assertEquals(at(rsi, 5), at(rsi, 9), EXACT,
                "the value moved inside a coarse bar that had not closed");

        assertEquals(at(rsi, 10), at(rsi, 14), EXACT,
                "the last bar read a coarse bar that had not finished");
    }
```

**Problema.** As três asserções são satisfeitas por `NaN` em todas as barras.
As cinco primeiras **exigem** NaN. As duas seguintes comparam dois valores entre
si, e `Assertions.assertEquals(double, double, double delta)` do JUnit 5.10.3
começa por `Double.valueOf(v1).equals(Double.valueOf(v2))` — que é **verdadeiro
para NaN contra NaN**. Não há nenhuma asserção de que algum valor finito tenha
sido produzido.

**Como quebrar o produto e o teste continuar verde.** Fazer
`RelativeStrength.calculate` sair cedo quando `ownPeriod != null` — um `return`
antes do laço, ou um off-by-one no mapeamento que nunca encontra uma barra
grossa fechada — deixando o vetor de valores todo em NaN. Na tela o IFR
simplesmente **desaparece** assim que o leitor escolhe uma escala própria, em
silêncio, e este teste passa inteiro.

**Consequência.** É o **único** teste do cálculo da escala própria do IFR — o
próprio comentário do teste diz isso: *"The RSI's own-scale CALCULATION had no
test at all. setOwnPeriod appeared once in this file, inside a round trip of the
appearance text"*. A licença que ele compra ("não lê o futuro") é comprada
também pela ausência total de desenho.

**Correção.** Uma linha, copiada do irmão que já a tem. Em
`BollingerBandsTest.ownScaleDoesNotReadTheFuture:233`:

```java
        assertTrue(Double.isFinite(bands.valueAt(series.size() - 1)[1]),
                "no band was drawn at all, so neither loop above asserted anything");
```

O mesmo aqui: `assertFalse(Double.isNaN(at(rsi, 14)))`, e de preferência o valor
exato em `at(rsi, 10)` — a série é 100..114 e a conta cabe à mão.

**Tentei refutar assim.** (a) Procurei outro teste da escala própria do IFR:
`grep -rn "setOwnPeriod" src/test` dá `RelativeStrengthTest` (aqui e no
round-trip de aparência, que só prova que a string sobrevive), `OwnPeriodTest`
(média móvel), `BollingerBandsTest` e `PaneSharingTest` (que só compara nomes).
Nenhum cobre o cálculo do IFR. (b) Verifiquei se `valueAt` fora da série lançaria
em vez de dar NaN — `tooShort:232` mostra que ela devolve NaN, então nada
protege. (c) Verifiquei a semântica do JUnit: `AssertionUtils.doublesAreEqual`
usa `Double.valueOf(...).equals(...)` antes do delta, e `Double.equals` trata
NaN como igual a NaN. Não derrubei.

---

### B8b-2. O teste dos botões do mouse casa uma string dentro de 700 caracteres, não a ordem em que o guarda age

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartViewTest.java:80-113`

```java
        for (int at : new int[]{pressed, clicked}) {
            String head = source.substring(at, Math.min(source.length(), at + 700));

            assertTrue(head.contains("isLeftMouseButton"),
                    "a mouse handler acts on every button: " + head.lines().findFirst());
        }
```

**Problema.** A asserção é sobre **texto-fonte**, e sobre a mera presença do
identificador em uma janela de 700 caracteres a partir da abertura do método —
comentários incluídos. Ela não diz nada sobre *onde* o guarda está nem sobre o
que ele protege.

**Como quebrar o produto e o teste continuar verde.** Em
`ChartCanvas.java:2808-2823`, mover o bloco do "ir para o fim" para **antes** do
guarda:

```java
        public void mousePressed(MouseEvent e) {
            Rectangle jump = jumpBounds();

            if (jump != null && jump.contains(e.getPoint())) {
                goToEnd();
                return;
            }

            if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
```

O botão direito sobre a insígnia de "ir para o fim" volta a jogar o leitor para
o fim da série — o defeito que o teste existe para pegar, na sua forma mais
visível — e `isLeftMouseButton` continua dentro dos 700 caracteres. Verde.
Também passa verde se o guarda for reduzido a um `if` sem `return`.

**Consequência.** O teste compra a garantia "os tratadores conferem QUAL botão
foi" e entrega "a palavra `isLeftMouseButton` aparece no arquivo". No sentido
oposto ele produz alarme falso: reescrever o guarda como
`if (e.getButton() != MouseEvent.BUTTON1) return;` — equivalente — faz o teste
falhar com o produto certo.

**Correção.** Chamar os tratadores com um `MouseEvent` de botão 3 construído à
mão e olhar o **efeito**: `canvas.getMode() == MEASURE`, disparar um
`mousePressed` com `BUTTON3` e exigir que `measurement()` não tenha sido apagada
e que `firstVisibleBar()` não tenha mudado. Não precisa de janela: o
`MouseEvent` é um construtor público e o `Mouse` é um campo do canvas.

**Tentei refutar assim.** (a) Li `ChartCanvas.java:2807-2884` inteiro: hoje o
guarda é de fato a primeira instrução dos dois tratadores, então o teste está
certo **sobre o estado atual**. O que ele não faz é impedir a volta do defeito,
que é para o que serve um teste. (b) Procurei um teste de comportamento dos
botões: `grep -rn "BUTTON3\|isPopupTrigger\|MouseEvent" src/test/java/.../ui/`
não devolve nenhum teste que dispare um evento de mouse. Não derrubei.

---

### B8b-3. O teste do vazamento da biblioteca de ticks olha só a PRIMEIRA atribuição, e 600 caracteres antes dela

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartViewTest.java:340-355`

```java
        String source = Files.readString(CANVAS, StandardCharsets.UTF_8);
        int at = source.indexOf("growingFrom = library;");
        ...
        String before = source.substring(Math.max(0, at - 600), at);

        assertTrue(before.contains("stopGrowing();"),
                "the library is assigned without closing what was there");
```

**Problema.** `indexOf` acha **uma** ocorrência, e a asserção é "a string
`stopGrowing();` aparece em algum lugar nos 600 caracteres anteriores" — sem
dizer que ela está no mesmo caminho de execução.

**Como quebrar o produto e o teste continuar verde.** Duas formas, ambas
realistas:

1. Condicionar o fechamento. Em `ChartCanvas.java:1240-1245` o código hoje é
   `stopGrowing();` seguido de `growingFrom = library;`. Envolver o primeiro em
   `if (growingFrom != library) { stopGrowing(); }` parece uma otimização
   inofensiva, mantém a string nos 600 caracteres, e devolve o vazamento na
   corrida que o teste descreve (dois `SwingWorker` chegando em sequência com
   bibliotecas diferentes — que é exatamente o caso em que a condição é
   verdadeira... e em qualquer variação que a torne falsa, o vazamento volta em
   silêncio).
2. Acrescentar uma **segunda** atribuição `growingFrom = library;` em outro
   método — por exemplo num caminho novo de replay — sem `stopGrowing()` antes.
   `indexOf` continua achando a primeira, e o teste nunca olha a segunda.
   Verifiquei: hoje há exatamente uma ocorrência (`ChartCanvas.java:1245`), o
   que torna a segunda forma indetectável.

**Consequência.** O comentário do próprio teste quantifica o que se perde: *"a
reading thread and up to three sessions of ticks -- 340 MB -- held for the life
of the application, once per race"*. Um teste que passa nessa condição é pior
que nenhum, porque a próxima pessoa não vai reabrir o assunto.

**Correção.** Medir o objeto, não o texto. `FirstSessionTest.residentTicks()`
já existe e é exatamente o instrumento: montar duas bibliotecas em sequência
num canvas e exigir que a contagem de sessões residentes não some as duas.

**Tentei refutar assim.** (a) `grep -n "growingFrom = library;"` →
uma ocorrência, `ChartCanvas.java:1245`; `stopGrowing();` aparece em 1036, 1060,
1134, 1242, 1419, 1454. A de 1242 está de fato imediatamente antes. Certo hoje.
(b) Procurei um teste que contasse bibliotecas abertas no canvas:
`TickLibraryClosingTest` está em `domain`, fora desta área, e mede a
`TickLibrary` isolada, não a troca dentro do `ChartCanvas`. Não derrubei.

---

### B8b-4. O teste do replay que "pede o pregão antes" casa três strings numa fatia de 2.600 caracteres

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartViewTest.java:317-338`

```java
        String body = source.substring(at, at + 2_600);

        assertTrue(body.contains("growingFrom.request(day)"),
                "the session is not asked for before advance goes looking");
        assertTrue(body.contains("growingFrom.request(day.plusDays(1))"),
                "the next session is never asked for, so midnight blocks");
        assertTrue(body.contains("if (!growing.advance(day, now + 1)"),
                "the answer from advance is thrown away and the view rebuilt anyway");
```

**Problema.** Nenhuma das três diz **quando** as chamadas acontecem nem o que é
feito com o resultado. A terceira chega a fixar o texto de uma condição
(`if (!growing.advance(day, now + 1)`) — o que passa a valer para qualquer corpo
dentro dela.

**Como quebrar o produto e o teste continuar verde.** Trocar o corpo do `if` por
`return true;` (ou por um `repaint()` incondicional), mantendo a condição
literal. Todas as três strings continuam presentes, e volta a reconstrução do
renko inteiro a 25 quadros por segundo que o teste diz impedir — que é o defeito
descrito em `TickRenkoOnChartTest.aQuietFrameKeepsTheTickBricks:483`, e ali sim
com asserção de comportamento (`assertSame(was, canvas.series())`).

**Consequência.** O nome do teste promete duas coisas medíveis — "pede o pregão
antes de precisar dele" e "não redesenha o que não mudou" — e não mede nenhuma.
A segunda já tem teste de verdade noutro arquivo; a primeira (os 90 MB lidos na
EDT à meia-noite) não tem nenhum.

**Correção.** Para a segunda metade, apagar este teste: `aQuietFrameKeepsTheTickBricks`
já a cobre com dentes. Para a primeira, um `TickLibrary` de teste que registre
quais dias foram pedidos, e a exigência de que `day+1` esteja na lista **antes**
de o relógio atravessar a meia-noite.

**Tentei refutar assim.** (a) Confirmei que `extendBricks` existe em
`ChartCanvas.java:1396` e que os 2.600 caracteres cabem dentro do arquivo de
3.009 linhas — a fatia não estoura hoje, mas `substring(at, at + 2_600)` lança
`StringIndexOutOfBoundsException` se o método for para o fim do arquivo, o que é
um segundo modo de falha por motivo errado. (b) Procurei um teste que contasse
os pedidos ao `TickLibrary` a partir do canvas: não há. Não derrubei.

---

### B8b-5. O rodapé: o teste lê o código-fonte de `cursorReading()`, sendo que o método é público e devolve o número

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartViewTest.java:294-315`

```java
        String body = source.substring(at, source.indexOf("\n    }", at));

        assertTrue(body.contains("formatFor(gridStep("),
                "the footer rounds by a rule of its own");
        assertTrue(!body.contains("DecimalFormat"), ...);
        assertTrue(!body.contains("ofPattern("), ...);
```

**Problema.** `ChartCanvas.cursorReading()` (`ChartCanvas.java:637`) é `public`,
não recebe argumento e **devolve a string que vai para o rodapé** —
`MainWindow.java:878` a consome. O teste ignora o valor e verifica que o corpo
do método menciona `formatFor(gridStep(`.

**Como quebrar o produto e o teste continuar verde.** Mudar `formatFor`
(`ChartCanvas.java:2626`) ou `gridStep` (`ChartCanvas.java:2640`) — que é onde a
regra de arredondamento realmente mora. Por exemplo, trocar
`step >= 1.0 ? 0 : ...` por `step > 1.0 ? 0 : ...`: um passo de grade de
exatamente 1,0 passa a imprimir uma casa decimal no eixo **e** no rodapé, em
todo gráfico do WIN. O corpo de `cursorReading` não muda uma vírgula, e o teste
que se chama "o rodapé arredonda o preço pela regra do eixo" passa.

**Consequência.** É o pior caso do enunciado: **caminho de interface que decide
número na tela e que nenhum teste toca**. Nem `cursorReading()`, nem
`gridStep()`, nem `formatFor()` têm um único teste de valor em toda a suíte
(`grep -rn "gridStep\|formatFor\|cursorReading" src/test` devolve **só** os
comentários e a `indexOf` deste arquivo). Os rótulos do eixo de preço, a etiqueta
do cursor e o rodapé — três números que o leitor lê o dia inteiro — estão sem
cobertura, atrás de um teste que parece cobri-los.

**Correção.** `formatFor` e `gridStep` são privados e estáticos/de instância;
torná-los visíveis ao pacote custa nada e permite
`assertEquals("177.600", formatFor(gridStep(...)).format(177_600.0))`. E
`cursorReading()` já é público: com um `PriceSeries` de teste e um `hoveredBar`
posicionado, dá para exigir a string inteira.

**Tentei refutar assim.** (a) Verifiquei se `cursorReading` está mesmo em
indentação de 4 espaços, para o `indexOf("\n    }")` recortar só o método:
está (`ChartCanvas.java:637`), então o recorte é o pretendido — o problema não é
o recorte, é a natureza da asserção. (b) Procurei um teste de `StatusBar` que
verificasse o número: `StatusStripTest` usa a constante literal
`"05/09 03:47   100,42"` escrita à mão (`StatusStripTest.java:47`), isto é,
testa o **layout** da barra e não de onde o texto veio. Não derrubei.

---

## MÉDIA

### B8b-6. `zeroSearchesRatherThanBuilds`: laço que não assere nada se a lista voltar vazia

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/PeriodCatalogTest.java:81-91`

```java
    @DisplayName("typing a zero searches, and never builds a zero-minute scale")
    void zeroSearchesRatherThanBuilds() {
        for (PeriodCatalog.Choice choice : PeriodCatalog.forText("0")) {
            assertNotNull(choice.aggregation());
            assertFalse(choice.code().startsWith("0"), ...);
        }
    }
```

**Problema.** Condição de entrada falsa antes da primeira iteração: se
`forText("0")` devolver uma lista vazia, o laço roda zero vezes e o teste passa.
A metade do nome que diz "typing a zero **searches**" nunca é verificada.

**Como quebrar o produto e o teste continuar verde.** Um guarda em
`PeriodCatalog.forText` que devolva `List.of()` para texto que comece por `0`
(uma tentativa razoável de resolver "nunca construir escala de zero minutos"
pelo caminho errado). Quem digita "10" ou "30" vê a lista ficar **vazia** na
primeira tecla, e o teste que existe justamente para isso fica verde.

**Consequência.** Média e não alta porque o que se perde é uma lista de
sugestões, não um número na tela. Mas é o padrão de licença falsa em estado
puro, e nenhum outro teste do arquivo pergunta sobre `"0"` —
`nothingIsADeadEnd:115` testa `""`, `"1"`, `"2"`, `"5"`, `"7"`, `"60"`, `"101"`,
`"200"`, `"ren"`.

**Correção.** Uma linha antes do laço:
`assertFalse(PeriodCatalog.forText("0").isEmpty(), "digitar zero não ofereceu nada");`

**Tentei refutar assim.** Procurei quem mais cobre `"0"`: ninguém, conforme a
lista acima. `theOpeningList` cobre `""`. Não derrubei.

---

### B8b-7. Três testes do renko de ticks aseveram uma AUSÊNCIA depois de um `Thread.sleep` fixo

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/TickRenkoOnChartTest.java:229-242`, `:244-270`, `:272-298`

```java
        canvas.setPeriod(new Renko(55, 2), "55R", "55R");

        Thread.sleep(300);
        SwingUtilities.invokeAndWait(() -> { });

        assertFalse(canvas.isFromTicks(),
                "the chart built a renko from the one day that has ticks and drew it beside "
                        + "a day that has none");
```

**Problema.** A assimetria é o defeito. `settle()` (linha 176) espera até que
`isFromTicks()` fique **verdadeiro**, com prazo de 5 s — correto, porque a espera
tem um alvo. Estes três esperam um tempo fixo e depois exigem que algo **não**
tenha acontecido. Se o `SwingWorker` demorar mais que 150 ou 300 ms — máquina
carregada, primeira execução da JVM, disco frio —, a asserção passa por ter
chegado cedo demais, não por o produto estar certo.

**Como quebrar o produto e o teste continuar verde.** Apagar a verificação de
cobertura em `RenkoSource.allows` **e** fazer o `SwingWorker` levar mais de
300 ms (por exemplo, porque o export real tem 90 MB em vez dos 4.800 ticks do
fixture). Em produção é o caso normal: o comentário de `partialCoverageIsRefused`
diz que sem esse teste "the coverage check could be deleted and the suite would
stay green" — e é justamente o que volta a acontecer sob carga.

**Consequência.** O caso que este teste guarda é o mais grave da área: um renko
denso à esquerda e ralo à direita "que pareceria que o mercado fez isso". Um
guarda que às vezes está presente e às vezes não é pior que um guarda ausente,
porque a intermitência é atribuída à máquina.

**Correção.** Dar à espera um alvo positivo. O canvas já expõe estado suficiente:
esperar até que `canvas.series()` seja diferente do renko de candles **ou** que
o prazo estoure, e então asserir. Ou expor um contador de builds concluídos e
esperar por ele.

**Tentei refutar assim.** (a) `withoutTicksItStaysOnCandles:229` de fato não
tem como falhar por corrida — a pasta está vazia e não há o que construir; para
esse eu concordo que o sleep é decorativo. Os outros dois (`partialCoverageIsRefused`,
`aBuildForAnotherSeriesIsNotShown`) têm sim um build em voo, e a corrida é real.
(b) Verifiquei se `invokeAndWait(() -> {})` drena a fila inteira: drena o que já
está enfileirado, não o que ainda está no `doInBackground`. Não derrubei.

---

### B8b-8. `changingTheListenerDoesNotStackAnother` compara dois contadores sem exigir que nenhum deles seja maior que zero

`src/test/java/br/com/jorge/reis/endeavourneo/ui/replay/ReplayHousekeepingTest.java:124-150`

```java
        assertEquals(once.get(), thrice.get(),
                "setting the listener three times reported the change "
                        + thrice.get() + " times against " + once.get());
```

**Problema.** `0 == 0` satisfaz a asserção. Não há piso.

**Como quebrar o produto e o teste continuar verde.** Fazer
`DatePicker.onChange` (`DatePicker.java:149-158`) guardar o `Runnable` e **nunca**
registrar o `DocumentListener` — por exemplo iniciando o campo `listening` em
`true`, ou trocando o documento do campo depois do registro. Digitar uma data no
transporte deixa de fazer efeito por completo, e este teste fica verde.

**Consequência.** A licença falsa é real, mas há mitigação: essa quebra
específica é apanhada por `ReplayRangeTest.correctingTheEndDoesNotThrow:74`, que
depende do listener para corrigir a data de fim (e `ReplayPreferences.windowDays()`
é limitado a 250 dias pelo `MAX_WINDOW`, bem abaixo dos 3 anos do fixture — então
aquela asserção tem dentes de verdade). Por isso MÉDIA e não ALTA: o teste dá
licença falsa, a suíte inteira não. A dependência é frágil — é entre classes
diferentes, e não está escrita em lugar nenhum.

**Correção.** Uma linha, no padrão que este repositório já usa em
`ReplayEndsTest.watchersGoQuiet:88` (`assertTrue(before > 0, "the fixture never
ticked, so the check proved nothing")`):
`assertTrue(once.get() > 0, "o fixture nunca disparou");`

**Tentei refutar assim.** Descrito acima: encontrei a mitigação e por isso
rebaixei a severidade em vez de descartar o achado.

---

### B8b-9. `CollapsiblePaneTest` grava a configuração REAL do leitor — e é o único que nem sob Maven fica isolado

`src/test/java/br/com/jorge/reis/endeavourneo/ui/shell/CollapsiblePaneTest.java:35,86-95`

```java
    private static final String KEY = "test-pane-" + System.nanoTime();
    ...
    void itRemembers() {
        String key = KEY + "-c";

        new CollapsiblePane("Console", new JPanel(), key).setFolded(true);

        assertTrue(new CollapsiblePane("Console", new JPanel(), key).isFolded(), ...);
    }
```

**Problema.** `CollapsiblePane` não usa `Settings`; usa
`java.util.prefs.Preferences.userNodeForPackage(CollapsiblePane.class)`
(`CollapsiblePane.java:56-57`), que no Windows é o **registro**. E a chave
carrega `System.nanoTime()`, portanto **cada execução da suíte cria uma chave
nova e permanente**, jamais apagada. Três testes deste arquivo gravam
(`KEY`, `KEY + "-b"`, `KEY + "-c"`).

O agravante: o `endeavourneo.home` que o `pom.xml:86` define para isolar as
configurações **não alcança `java.util.prefs`**. Sob Maven os outros testes
escrevem em `target/test-home`; este escreve no registro do leitor de qualquer
jeito.

**Consequência.** Poluição monotônica do registro do usuário, uma entrada por
execução da suíte, para sempre. Ninguém vai notar, e é exatamente por isso que
está aqui.

**Correção.** Dar ao `CollapsiblePane` a mesma costura que o `Settings` tem —
`Settings.settings()` em vez de `Preferences` seria a resposta coerente com o
javadoc de `Settings` ("Why files and not java.util.prefs" — o argumento já está
escrito lá). Enquanto isso, um `@AfterAll` com `PREFS.remove(key)`.

**Tentei refutar assim.** (a) Li `CollapsiblePane.java:56-57` e `:88` para
confirmar que a leitura é `PREFS.getBoolean(key + ".folded", false)` e que o
`setFolded` grava. (b) Verifiquei que o `pom.xml` não passa
`-Djava.util.prefs.*` nem redireciona o `PreferencesFactory`. Não derrubei.

---

### B8b-10. Testes de interface gravam `settings`/`workspace` reais quando a suíte roda do jeito documentado (sem Maven)

`ChartViewTest.java:249-252`, `ReplaySessionTest.java:77-105`, `RulerModeTest.java:45-50`,
`CandleBodyEdgeTest.java:58-63`, `MainWindowTest.java:394-401`

```java
        // ChartViewTest.theChartReopensWhereItWasLeft
        br.com.jorge.reis.endeavourneo.platform.Settings into =
                br.com.jorge.reis.endeavourneo.platform.Settings.workspace();

        left.storeView(into, "chartViewTest.");
```

**Problema.** `Settings.HOME` (`Settings.java:81-83`) usa
`System.getProperty("endeavourneo.home", System.getProperty("user.home"))`, e
`endeavourneo.home` é definido em **um único lugar do repositório**: o surefire
do `pom.xml:86`. A convenção da casa (`~/.claude/skills/convencoes/SKILL.md`,
seção 10) diz que **não há `mvn` no PATH** e que a suíte é executada por
`JupiterRunner` com `javac` do JBR — caminho no qual a propriedade **não é
definida** e `Settings.workspace()` é o `workspace.properties` do leitor.

Dois graus:
- `RulerModeTest`, `ReplaySessionTest` e `CandleBodyEdgeTest` **restauram** o
  valor anterior num `@AfterEach`/`finally`. O dano é uma janela de tempo, e é
  perdido se a JVM morrer no meio. Aceitável, e os comentários assumem a escolha
  (`RulerModeTest.java:46-48`).
- `ChartViewTest.theChartReopensWhereItWasLeft` **nunca limpa**: escreve chaves
  com o prefixo `chartViewTest.` e as deixa. Mesmo sob Maven é sujeira; fora
  dele é sujeira no arquivo do leitor, para sempre.
- `MainWindowTest.aClosedChartStaysClosed:394-401` lê
  `Settings.workspace().keysStartingWith("chart.open.")` e exige
  `assertEquals(1, saved.size())`. Fora do Maven, isso é lido do
  `workspace.properties` real, onde o leitor tem os gráficos que ele deixou
  abertos — o teste falha por motivo alheio, e antes de falhar já reescreveu a
  lista.

**Como quebrar o produto e o teste continuar verde / falhar sem motivo.** O modo
de falha aqui não é licença falsa e sim **acoplamento ao estado da máquina**:
`aClosedChartStaysClosed` diz "1 gráfico lembrado" e mede uma propriedade global
que qualquer outro teste (ou o uso real do programa) pode mudar.

**Correção.** Definir `endeavourneo.home` também no caminho `JupiterRunner`, e
dar a `ChartViewTest` um `Settings` de teste — o construtor
`Settings(Path, String)` é visível ao pacote **exatamente para isso** e o javadoc
diz por quê (`Settings.java:100-104`). Um `@TempDir` resolve.

**Tentei refutar assim.** (a) `grep -rn "endeavourneo.home"` em todo o
repositório: só `pom.xml:86`, `Settings.java:82` e relatórios em
`target/surefire-reports`. Nenhum script, nenhum `.bat`, nenhum
`junit-platform.properties`. (b) Verifiquei que não existe
`src/test/resources`. Não derrubei.

---

### B8b-11. `assumeFalse(isHeadless())` desliga 11 testes de uma vez, e em silêncio

`src/test/java/br/com/jorge/reis/endeavourneo/ui/shell/MainWindowTest.java:406-408`,
`RulerModeTest.java:92`, `SegmentPickingTest.java:198-199`

```java
    private static void onEdt(Consumer<MainWindow> test) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");
```

**Problema.** `onEdt` é o veículo de **9 dos 11 testes** de `MainWindowTest` —
incluindo os quatro que guardam defeitos relatados pelo uso: "every chart open at
closing time comes back", "uma janela fechada nao volta no proximo arranque",
"a series that will not read draws NOTHING" e "a chart opened for a series that
is gone carries the name it really opened". Some junto o teste do vazamento de
listener em `RulerModeTest.chartSubscribesAndUnsubscribes` e o da janela única em
`SegmentPickingTest.onlyOneSeriesWindowIsEverOpen`.

Sob `-Djava.awt.headless=true` — ou em qualquer execução sem display: um
servidor de build, um `ssh`, um agente — a suíte fica **verde com 11 testes que
não rodaram**, e a saída padrão do JupiterRunner não distingue "passou" de
"pulado" a menos que alguém leia a contagem.

**Consequência.** É o item "teste que se desliga sozinho justo no modo em que o
projeto é construído" do enunciado. Hoje o projeto é construído com display
(Windows, JBR), então a desativação não está ativa — por isso MÉDIA e não ALTA.
Mas o caminho headless é objetivo declarado do projeto (o
`LayerBoundaryTest.java:39` justifica a fronteira de camadas dizendo que ela é
"what lets the same code run headless"), e a memória do usuário registra
"treino noturno antes do pregão / falta o caminho headless". No dia em que esse
caminho existir, estes 11 desaparecem sem um aviso.

**Correção.** Fazer a suíte gritar em vez de calar: um teste que **falha** se
`isHeadless()` for verdadeiro e a propriedade `endeavour.allowHeadless` não
estiver posta. Assim quem escolhe rodar sem tela sabe o que está abrindo mão.

**Tentei refutar assim.** (a) `grep -rn "headless"` no repositório: nada define
`java.awt.headless`, nem o `pom.xml`. Portanto a assunção hoje é verdadeira e os
testes rodam. O achado é sobre a fragilidade, e é por isso que não é ALTA.
(b) Contei os testes atingidos abrindo cada arquivo. Não derrubei.

---

### B8b-12. `ReplayEndsTest` é o único teste de replay sem fixture e sem isolamento do catálogo

`src/test/java/br/com/jorge/reis/endeavourneo/ui/replay/ReplayEndsTest.java:34-36`

```java
    private static ReplaySession session() {
        return new ReplaySession("WINFUT", LocalDate.of(2026, 9, 2), 0);
    }
```

**Problema.** Nenhum `ReplayBase.at(...)`, nenhum `SeriesCatalog.useFolderForTest`,
nenhum `@AfterEach`. Todos os outros nove arquivos de `ui/replay` escrevem uma
base num `@TempDir` primeiro — e o javadoc de `ReplayBase` diz por quê: *"the
replay stopped inventing its candles [...] a test that supplies no source is
replaying nothing"*.

Consequências concretas: (a) o `SeriesCatalog` aponta para a pasta de dados
**real** do leitor no momento em que esta classe roda, e a sessão vai procurar
`"WINFUT"` lá; (b) o resultado depende de qual classe rodou antes — se alguma
deixou `useFolderForTest` apontando, a classe herda; se `release()` rodou, cai
no real. O comentário de `ReplayBase.java:104-113` documenta exatamente esse
vazamento entre classes e diz que ele já apareceu uma vez.

**Como quebrar o produto e o teste continuar verde.** Aqui o risco não é licença
falsa — verifiquei que `ReplaySession.step` chama `announce()` incondicionalmente
(`ReplaySession.java:678-686`), então `assertTrue(before > 0)` tem dentes mesmo
com sessão vazia, e as três asserções sobre `whenEnded`/`stop` valem. O risco é
o inverso: a classe lê o disco do leitor e o seu resultado varia com a máquina.

**Correção.** As três linhas que os vizinhos já têm: `@TempDir`, `ReplayBase.at`,
e um `@AfterEach` com `ReplayBase.release()`.

**Tentei refutar assim.** (a) Li `ReplaySession.step` e `announce` para confirmar
que a asserção não fica vazia com sessão vazia — não fica, e por isso este
achado é sobre hermetismo e não sobre dentes. (b) Conferi que nenhuma das outras
nove classes de `ui/replay` faz isso. Não derrubei.

---

### B8b-13. Texto de interface em português escrito à mão dentro dos testes

`SeriesSummaryTest.java:113-114,122-131`, `SegmentChipTest.java:121,128,139`

```java
        assertEquals("3", valueOf(rows, "summary.sessions"));
        assertEquals("1.500", valueOf(rows, "summary.bars"));
        ...
        assertEquals("6 anos", SeriesSummary.spanBetween(
                LocalDate.of(2020, 9, 1), LocalDate.of(2026, 9, 1)));
        ...
        assertEquals(List.of("A série toda", "[Estudos]", "Testes"), itemsOf(header.menuFor()), ...);
```

**Problema.** Duas dependências invisíveis foram fixadas: o **idioma carregado**
e o **`Locale` da JVM**. `"1.500"` é o separador de milhar de `pt_BR`; sob
`en_US` seria `"1,500"`. `"A série toda"` e `"6 anos"` são o conteúdo do
`messages_pt_BR.properties`.

Os outros testes desta área já fazem certo — `NavigatorTreeTest:218` usa
`Messages.orElse("navigator.role.source", "source")` e
`ReplayRangeTest:193` usa `Messages.get("replay.range", ...)`. Estes três não.

**Consequência.** O projeto vai para open source (convenção 9) e a base do bundle
é o **inglês**. No dia em que a suíte rodar com o idioma base, estes três falham
por motivo nenhum — e o modo de falha ("esperava `A série toda`") aponta para o
lugar errado.

**Correção.** `Messages.get("segment.whole")` etc., como os vizinhos.
Para os números, comparar contra `NumberFormat.getInstance().format(1500)`.

**Tentei refutar assim.** Verifiquei se `spanBetween` monta o texto no código
Java (o que faria disto um achado de produto): `SeriesSummary.java:78` chama
`Messages.get("summary.span")` para o rótulo e `spanBetween` para o valor — o
valor sai da linha 91 e a auditoria do produto que olhar `ui/chart` deve dizer se
"anos"/"meses"/"dias" vêm do bundle. Do lado do teste, o achado vale de qualquer
forma. Não derrubei.

---

## BAIXA

### B8b-14. Um teste que testa o próprio fixture

`src/test/java/br/com/jorge/reis/endeavourneo/ui/shell/NavigatorTreeTest.java:435-458`

```java
    @DisplayName("o guarda recusa qualquer caminho fora da pasta do teste")
    void theGuardRefusesToLeaveTheTemporaryFolder(@TempDir Path folder) {
        ...
        assertThrows(IllegalStateException.class, () -> under(folder, outside));
```

`under` é o método auxiliar declarado na linha 90 **deste mesmo arquivo de
teste**. Nenhuma mudança no produto pode fazer este teste falhar. O comentário
justifica bem a rede (ela já salvou 90 MB de ticks reais três vezes), e a rede
merece existir — mas ela é infraestrutura de teste, não comportamento do
programa, e contá-la entre os testes infla o número. Sem correção necessária;
fica registrado para que a contagem seja lida corretamente.

### B8b-15. `copyNamesDoNotCollide`: a segunda asserção é satisfeita por qualquer nome não usado

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartLayoutTest.java:141-143`

```java
        assertFalse(first.equals(second), "the second copy reused the first one's name");
        assertTrue(second.contains(first) || !taken.contains(second),
                "the second copy has to be distinguishable from the first");
```

A disjunção torna o segundo `assertTrue` verdadeiro para qualquer string que não
esteja em `taken` — inclusive a vazia. O nome do teste promete que "copiar uma
cópia empilha o prefixo", e a única parte que ainda pede isso é o lado esquerdo
do `||`, que o lado direito perdoa. Exigir só `second.contains(first)` diria o
que o `@DisplayName` diz.

### B8b-16. Javadoc grudado no membro errado

`src/test/java/br/com/jorge/reis/endeavourneo/ui/shell/NavigatorTreeTest.java:100-108`

```java
    /** Every leaf under the tree, with its label and what it would open. */
    /**
     * The tree reads segments, so a test of the tree has to say which ones.
     ...
    @org.junit.jupiter.api.BeforeEach
    void isolateSegments(@TempDir Path store) {
```

A primeira linha descreve `leaves(TreeModel)`, que está na linha 175. Ficou
órfã acima de `isolateSegments` — dois javadoc seguidos, o de cima falso para o
membro que ele encabeça. Item explícito do briefing ("javadoc grudado no membro
errado"). Mover para a linha 175.

### B8b-17. Componente Swing construído fora da EDT em vários testes

`PlayableDaysTest.java:109`, `ReplayHousekeepingTest.java:134,140`,
`SegmentChipTest.java:68,78`, `PaneOrderTest.java:56-63`, `PlacementTest.java:49`,
`OverlayLegendTest.java:88`, `HistoryPagingTest.java:83`, `TimeAxisTest.java:87`

`new DatePicker(...)`, `new ChartCanvas()`, `new ChartHeader(...)`, `new StudyStack(...)`
são criados na thread do teste. A convenção 8 é explícita: *"Componente Swing so
na EDT. Criar fora funciona quase sempre e falha de forma que nao se reproduz."*
Os testes que fazem certo estão no mesmo diretório e dizem por quê —
`LoadingTrackTest.java:48-57` tem um javadoc inteiro sobre isso, e
`ReplayHandleTest`, `ReplayRangeTest`, `MainWindowTest` e `PaneOrderTest.settle`
usam `invokeAndWait`. É inconsistência, não defeito medido; entra como BAIXA
porque o modo de falha é justamente o que não se reproduz.

### B8b-18. `listedRenkoAnimates`: laço vacuável, mas coberto pelo vizinho

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/PeriodCatalogTest.java:135-143`

```java
        PeriodCatalog.forText("6").stream()
                .filter(choice -> choice.aggregation() instanceof Renko)
                .forEach(choice -> assertTrue(((Renko) choice.aggregation()).hasForming(), ...));
```

Se o filtro não casar nada, o `forEach` não assere nada. **Refutei em parte**:
`bothWhereBothFit:96-101` exige `six.size() == 2` e que `six.get(1)` seja um
`Renko`, então a única quebra que esvaziaria este laço já falha ali. Fica como
BAIXA por dependência entre testes — a mesma fragilidade de B8b-8, com
consequência menor.

### B8b-19. Hedge morto sobre uma constante

`src/test/java/br/com/jorge/reis/endeavourneo/ui/chart/SegmentChipTest.java:104-105`

```java
        ChartHeader header = headerOn(Segmentation.MARK.isEmpty()
                ? SERIES : SERIES + Segmentation.MARK + "Estudos");
```

`Segmentation.MARK` é constante. O ramo `isEmpty()` testaria silenciosamente
**outra coisa** (a série inteira em vez de um segmento) sem que o resultado
mudasse de nome. Escrever direto.

---

## LIMPO — o que conferi e está certo, e como

**Não é "não achei nada".** Abaixo, o que examinei especificamente procurando
licença falsa e por que concluí que tem dentes.

- **`ViewportTest` (276 linhas)** — a aritmética preço↔pixel. Todas as asserções
  são valores exatos com tolerância, ou propriedades com direção
  (`y(110) < y(90)`, `pastTheEnd.x(7) < atTheEnd.x(7)`). `countsSlotsNotBars:104`
  documenta que a asserção **já foi a oposta** e que isso era o defeito.
  Quebras que testei mentalmente — trocar o sinal do `slide`, tirar o piso do
  span nulo, usar a série inteira para a escala — falham todas.

- **`OwnPeriodTest` (213 linhas)** — o irmão de B8b-1, e **certo**.
  `neverTheBarStillForming:104` fixa quatro valores exatos (5,0 / 5,0 / 10,0 /
  10,0), então o caso "tudo NaN" falha imediatamente. `interpolationStaysBehind:141`
  tem um comentário que descreve exatamente a armadilha de B8b-1 e acrescenta o
  `assertNotEquals` que resolve.

- **`BollingerBandsTest.ownScaleDoesNotReadTheFuture:182`** — o modelo do que
  falta em B8b-1: dois laços com `if (isFinite)` **e** uma asserção final
  (`:233`) de que algo finito foi desenhado, com o comentário "so neither loop
  above asserted anything". Correto.

- **`MovingAverageTest.theShiftNeverGoesBackwards:142`** — verifica que um shift
  negativo é recusado **e** confere os quatro valores com período 1 (onde a média
  é o próprio fechamento), de modo que qualquer vazamento da direita vira número
  errado. Dentes.

- **`RelativeStrengthTest`, o resto** — `theFirstValueByHand:140` e
  `theSecondValueTellsThemApart:154` conferem contra contas feitas à mão e
  escritas no comentário; `whatLeavesTheWindow:237` distingue as duas suavizações
  por um valor exato (`100 - 700/27`). Só a escala própria (B8b-1) é frágil.

- **`DecimationTest` (211 linhas)** e **`CandleBodyEdgeTest` (230 linhas)** —
  leem **pixels de uma `BufferedImage`**, que é a forma mais forte de testar
  desenho sem tela. `spikesSurviveTheZoomOut:126` inclusive assere primeiro que
  o fixture está no regime certo (`barsPerColumn() > 1`) antes de medir a tinta.

- **`StatusStripTest` (205 linhas)** — `theyDropFromTheRight:111` monta a ordem
  de queda varrendo 197 larguras e compara a **lista inteira**: um campo que
  nunca caia produziria uma lista mais curta e falharia. `onScreen:77` documenta
  que a versão anterior lia só o `x` e por que a largura entrou.

- **`ChartCanvasTest`, `ChartGeometryTest`, `LayoutOrderTest`, `MeasurementTest`,
  `SessionsTest`** — aritmética pura, valores exatos, casos de borda (NaN em vez
  de zero, índice fora da série, arrasto de zero pixel). Nada vacuável.

- **`TickRenkoOnChartTest.aQuietFrameKeepsTheTickBricks:483`** — o oposto de um
  teste sem dentes: `assertSame(was, canvas.series())` só passa se o objeto for
  literalmente o mesmo. É o teste que B8b-4 deveria ter sido.

- **`TickRenkoOnChartTest.theBricksComeFromTheTicks:198`** — cheguei a suspeitar
  de que `assertNotEquals(fromCandles, canvas.series().size())` fosse fraco
  demais (passaria com um renko de ticks de tamanho de caixa errado).
  **Refutado**: `aLateBuildIsNotShown:415` fixa a altura do tijolo em 55,0 com
  tolerância de 1e-9, e `new Renko(55, 2)` é `(brick, reversal)` — conferi o
  construtor em `Renko.java:131`. O tamanho está pinado. Achado descartado.

- **`ReplayRangeTest.thereIsACap:240`** — o comentário mostra a asserção antiga
  ("A CEILING WITH NOTHING UNDER IT") e por que ela era vazia; a nova pergunta
  ao intervalo em vez de aos dados. Exemplo de conserto do mesmo defeito que
  procurei.

- **`ReplaySessionTest.seekingToTheEnd:187`** — idem: o comentário preserva o
  `assertEquals(x, x)` que existia ali. Hoje compara ponta com ponta e exige
  repetibilidade.

- **`FirstSessionTest.stoppingReleasesTheSessions:145`** — troca um
  `isPreparing() || !isPlaying()` (verdadeiro em quase todo estado) por
  `assertEquals(0, replay.residentTicks())`. Mede os 340 MB.

- **`StudyScaleTest` (135 linhas)** — o teste mais bem construído da área, e o
  terceiro que já traz a defesa que falta em B8b-1. Ele muda a escala **antes**
  de medir (`canvas.setPeriod(Timeframe.ofMinutes(5), ...)`), assere que o
  fixture está no regime certo (`series().size() < source().size()`), e fecha com
  um contador contra a vacuidade do laço: `assertTrue(compared > 50, "only " +
  compared + " bars were comparable: the fixture is too short")`. É o padrão que
  o resto da área deveria seguir.

- **`OverlayCatalogTest.everyKindIsUsable:33`** — o laço sobre o catálogo é
  precedido de `assertFalse(OverlayCatalog.kinds().isEmpty())`. Guardado contra a
  vacuidade que B8b-6 tem.

- **`PaneSharingTest`, `PaneOrderTest`, `OverlayOrderTest`, `OverlayNoticeTest`,
  `PlacementTest`** — contagens exatas e listas inteiras
  (`assertEquals(List.of(21, 8, 14), order())`), nunca `assertNotNull` sozinho.

- **Idempotência do catálogo e do `Segmentation`** — conferi que
  `SeriesCatalog.useFolderForTest(null)` e `Segmentation.stopUsingTestStore()`
  são chamados em `@AfterEach` em `NavigatorTreeTest`, `TickRenkoOnChartTest`,
  `SegmentChipTest`, `PlayableDaysTest` e `ReplayHousekeepingTest`. A exceção é
  `ReplayEndsTest` (B8b-12).

- **A rede `under(folder, file)`** — verifiquei que `NavigatorTreeTest.under:90`
  e a cópia em `TickRenkoOnChartTest.session:108` são chamadas antes de **toda**
  escrita de fixture. É a proteção certa e está aplicada de forma consistente.

- **`ReplayPreferences.windowDays()`** — suspeitei que
  `ReplayRangeTest.correctingTheEndDoesNotThrow:102` fosse trivialmente
  verdadeiro se a preferência real do leitor fosse grande. **Refutado**:
  `MAX_WINDOW = 250` (`ReplayPreferences.java:58`) e o fixture usa 3 anos
  (1.095 dias). A asserção tem dentes com qualquer valor gravado. Achado
  descartado.

---

## Contagem

| severidade | achados |
|---|---:|
| ALTA | 5 |
| MÉDIA | 8 |
| BAIXA | 6 |
| **total** | **19** |
