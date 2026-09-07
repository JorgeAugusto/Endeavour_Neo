# L3 — persistência, recursos e memória

Lente transversal. A pergunta: **o que foi aberto se fecha, o que foi gravado
dá a volta, e o que é alocado cabe?**

## O que foi rodado

| padrão | ocorrências | lidas (±40 linhas) |
| --- | --- | --- |
| `Files.(walk\|list\|find\|newInputStream\|newOutputStream\|newBufferedReader\|newBufferedWriter\|readAllLines\|readAllBytes\|lines)` | 11 | 11 |
| `new (FileInputStream\|FileOutputStream\|RandomAccessFile\|Scanner\|BufferedReader)` | 0 | — |
| `FileChannel\|MappedByteBuffer\|ByteBuffer.allocate` | 44 | 12 (as que abrem canal) |
| `try (` / `.close()` / `AutoCloseable` | 58 / 45 | as 14 do domínio + as 4 de `TickLibrary` |
| `Preferences` | 20 | 20 — **nenhuma é `java.util.prefs.Preferences`** |
| `new (double\|int\|long\|byte\|float)[` | 209 | ~24 (as que estão em laço, em pintura ou em `valueAt`) |
| `String.join\|.split(` | 23 (19 em `src/main`) | 19 |
| `serialVersionUID\|transient\|Serializable` | 190 | varredura completa: **zero** `implements Serializable`, zero `readObject`/`writeObject`, zero `ObjectOutputStream` |
| `catch (` | 53 | as 12 de `IOException`/`Exception`/`Throwable` |
| getters devolvendo campo-coleção | script sobre todo `src/main` | **zero ocorrências** |

Nenhum arquivo foi lido inteiro.

---

## Achados ALTA

### L3-1 — O layout padrão nomeia um indicador que não existe, e por isso desenha nada

**Onde:** `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartLayouts.java:141-143`

**Trecho:**

```java
    private static ChartLayout defaultLayout() {
        return new ChartLayout(Messages.get("layout.default"),
                List.of(new ChartLayout.Entry("overlay.ema", List.of(17, 55, 200), true)));
    }
```

O catálogo inteiro de tipos é este, e `overlay.ema` não está nele:

```java
// OverlayCatalog.java:66
            new Kind("overlay.movingAverage", List.of(9), 1, 2_000,
                    br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage::new),
```

```java
// MovingAverage.java:254-256
    public String nameKey() {
        return "overlay.movingAverage";
    }
```

**Problema:** `ChartLayout.Entry.build()` (`ChartLayout.java:110-135`) percorre
`OverlayCatalog.kinds()` procurando `kindKey`, não acha `overlay.ema`, e devolve
`null`. `ChartLayout.build()` (`ChartLayout.java:182-194`) descarta os nulos em
silêncio — está escrito assim de propósito, para que um layout de uma versão
posterior não impeça o gráfico de abrir. Aqui a tolerância engole o único
indicador do layout padrão.

**Consequência:** `ChartLayouts.all()` devolve o layout padrão em duas
situações — primeira execução (`count <= 0`, linha 62) e arquivo sem nenhum
layout legível (linha 77). `LayoutBar` o consome em
`canvas.setOverlays(layouts.get(selected).build())` (`LayoutBar.java:388`). O
resultado é uma aba chamada "Padrão" que não põe nada no gráfico. As três
médias que o nome do método promete — 17, 55 e 200 — nunca apareceram para
ninguém que abriu o programa pela primeira vez.

Segundo defeito na mesma linha: `List.of(17, 55, 200)` são **três** números para
um tipo cujo `defaults()` tem um só, e cujo construtor variádico lê apenas dois:

```java
// MovingAverage.java:157-161
    /** @param settings period, kind, shift -- the shape, as a layout stores it */
    public MovingAverage(int... settings) {
        this.period = settings.length > 0 ? Math.max(1, settings[0]) : 9;
        this.shift = settings.length > 1 ? settings[1] : 0;
    }
```

Mesmo corrigindo a chave, o que nasceria seria **uma** média de período 17
deslocada 55 barras, não três médias. O javadoc dessa linha já está reportado
como A5-18; o que não estava reportado é que o layout padrão depende dele.

**Correção:** trocar por
`new ChartLayout.Entry("overlay.movingAverage", List.of(17), true)` repetido
três vezes (uma entrada por média), ou por três `Pane`/`Entry` conforme o
desenho pretendido. E um teste que exija
`ChartLayouts.all().get(0).build().size() == 3` — hoje nenhum teste constrói o
layout padrão.

**Tentei refutar:** procurei uma tabela de sinônimos de chave em todo
`src/main` (`grep -rn "overlay.ema"`). `overlay.ema` aparece em exatamente
quatro lugares: dois javadocs (`ChartLayout.java:100`, `Overlay.java:46`), o
exemplo do cabeçalho de `ChartLayouts.java:35-36`, e esta linha 143. Em
`src/test` aparece só em `SettingsTest.java:87`, onde é texto opaco usado para
provar escape de `\n` — não passa por `build()`. `ChartLayoutTest` usa
`overlay.movingAverage` em todos os casos (linhas 38, 39, 52-55, 71, 83-84), que
é exatamente por que a suíte passa verde com este defeito de pé.

---

## Achados MÉDIA

### L3-2 — Nenhuma chave de gráfico é apagada quando o gráfico fecha

**Onde:** único ponto de limpeza do arquivo de trabalho:

```java
// MainWindow.java:265
        workspace.removeStartingWith("chart.open.");
```

**Trecho — o que é gravado e nunca removido:**

```java
// ChartCanvas.java:1628-1633, escrito por ChartHolder.java:841 com prefixo "chart." + key + "."
        into.putInt(prefix + "visibleBars", visibleBars);
        into.putInt(prefix + "rightMargin", rightMargin);
        into.put(prefix + "stretch", String.valueOf(stretch));
        into.put(prefix + "priceOffset", String.valueOf(priceOffset));
        into.put(prefix + "period", periodCode);
        into.put(prefix + "style", style instanceof LineStyle ? "line" : "candle");
```

```java
// ChartHolder.java:485, 524, 851-854, 876-880
        PREFS.putBoolean(key + ".floating", false);
        PREFS.putInt(key + ".x", floating.getX());
        PREFS.putInt(key + ".y", floating.getY());
        PREFS.putInt(key + ".width", floating.getWidth());
        PREFS.putInt(key + ".height", floating.getHeight());
        PREFS.putBoolean(key + ".maximised", maximised);
```

```java
// OverlayLegend.java:163
        PREFS.putBoolean(key + ".collapsed", value);
// ChartLayouts.java:118
        PREFS.put("selected." + chartKey, layoutName);
```

**Problema:** são onze chaves por gráfico, e a única remoção que existe no
programa inteiro é a de `chart.open.` (que é a *lista* do que estava aberto, não
o estado de cada um). Um gráfico fechado, renomeado ou de uma série que saiu do
disco deixa as onze para sempre.

**Consequência:** duas, e a segunda é a cara. (a) O arquivo cresce sem limite:
onze chaves por gráfico já visto, e um gráfico é aberto por série × escala ×
segmento. (b) `Settings.put` reescreve o arquivo **inteiro** a cada chave (já
reportado em A7a-8 e A4-11) — logo o custo de *toda* gravação de preferência,
inclusive marcar uma caixinha nas opções, cresce monotonamente com o número de
gráficos que a pessoa já abriu na vida, e nunca desce.

**Correção:** ao fechar um gráfico, `workspace.removeStartingWith("chart." + key + ".")`
e `removeStartingWith(key + ".")`, mais `remove("selected." + key)`. Ou, mais
barato de acertar: no fechamento do programa, varrer as chaves e apagar as que
não pertencem a nenhum gráfico em `chart.open.`.

**Tentei refutar:** procurei `removeStartingWith|PREFS.remove|workspace.remove`
em todo `src/main`. As únicas remoções de configuração são
`Segmentation.java:136` e `:223`, `ChartLayouts.java:104-106` (a cauda de
layouts encolhidos) e `MainWindow.java:265`. Nenhuma toca o estado por gráfico.

### L3-3 — A chave de um gráfico colapsa toda pontuação, e o `#` de segmento com ela

**Onde:** `ui/chart/ChartHolder.java:185`

**Trecho:**

```java
        this.key = name.replaceAll("[^A-Za-z0-9]+", "_");
```

**Problema:** todo trecho de caracteres não alfanuméricos vira **um** `_`. O
`-` da escala, o `.`, o espaço e o `#` — que é `Segmentation.MARK`, o separador
que distingue uma série de um dos seus trechos (`Segmentation.java:159-163`) —
são todos o mesmo caractere depois disso. `winfut-1m#treino` e uma série de
nome `winfut-1m-treino` produzem a chave `winfut_1m_treino`. Duas médias com
nomes que só diferem em pontuação (`busca 2020-2022` e `busca 2020 2022`) idem.

**Consequência:** os dois gráficos compartilham `chart.<chave>.period`,
`.visibleBars`, `.stretch`, a geometria da janela flutuante e
`selected.<chave>` — o último a fechar sobrescreve o outro. É a mesma família
de A7b-6 (duas janelas de série sobre a mesma série), aqui pelo caminho da
chave e não pelo da janela.

**Correção:** codificar em vez de colapsar (percent-encoding dos caracteres
fora de `[A-Za-z0-9]`, ou um hash curto anexado ao nome legível), de modo que
nomes distintos produzam chaves distintas.

**Tentei refutar:** procurei um desambiguador. `MainWindow.seriesOf`
(`MainWindow.java:278-282`) tira o sufixo `" (2)"` do título, o que resolve o
caso de dois gráficos da *mesma* série (`winfut-1m` → `winfut_1m`,
`winfut-1m (2)` → `winfut_1m_2_`), mas não faz nada por nomes diferentes que
colapsam no mesmo. Nenhum teste em `src/test` constrói duas chaves e compara.

### L3-4 — Uma entrada de layout sem parâmetro numérico desaparece na leitura, e os dois analisadores discordam

**Onde:** `ui/chart/ChartLayouts.java:317-333`

**Trecho:**

```java
            List<Integer> parameters = new ArrayList<>();

            for (String piece : fields[1].split(",")) {
                try {
                    parameters.add(Integer.valueOf(piece.trim()));
                } catch (NumberFormatException e) {
                    parameters.clear();

                    break;
                }
            }

            if (!parameters.isEmpty()) {
                entries.add(new ChartLayout.Entry(fields[0], List.copyOf(parameters),
```

**Problema:** `format` (linha 268-276) escreve `kind||true` para um indicador
sem parâmetros. Na volta, `"".split(",")` devolve `[""]`,
`Integer.valueOf("")` lança, `parameters` fica vazia e o `if` da linha 329
**descarta a linha inteira**. Um indicador sem número — VWAP, volume, uma linha
de abertura — não pode ser salvo neste formato: ele some ao reabrir, em
silêncio.

Pior: o outro analisador do MESMO campo não faz isso.

```java
// ChartLayouts.java:230 — parsePanes
            gathering.add(new ChartLayout.Entry(fields[1].trim(), numbers(fields[2]), true,
```

`numbers()` (linha 240-252) devolve `List.of()` quando um número não lê, e a
entrada é acrescentada assim mesmo. Ou seja: o mesmo layout, gravado como
*overlay* e como *pane*, sobrevive num caminho e morre no outro.

**Consequência:** hoje é latente — os quatro indicadores existentes têm
parâmetro (`MovingAverage:266`, `BollingerBands:321`, `RelativeStrength:200`,
`SlowStochastic:302`). É uma armadilha armada para o próximo indicador.

**Correção:** um só caminho para "os números desta linha", com contrato
explícito: lista vazia é válida; lista ilegível é a linha inteira descartada
(ou os padrões do tipo). Hoje há dois caminhos com duas respostas.

**Tentei refutar:** procurei um teste que gravasse e lesse uma entrada sem
parâmetro. `ChartLayoutTest` cobre lista com um número, com dois, e com `abc`
(linha 54) — nunca vazia.

### L3-5 — `TickLibrary` tem `close()` mas não declara `AutoCloseable`

**Onde:** `domain/market/TickLibrary.java:58` e `:202`

**Trecho:**

```java
public final class TickLibrary {
...
    public void close() {
        loader.shutdownNow();

        forget();
    }
```

**Problema:** o tipo tem o recurso (um `ExecutorService` de thread única, campo
final da linha 93) e tem o método, mas não implementa a interface. Os quatro
locais de construção precisam, cada um, lembrar de um `try/finally` escrito à
mão:

- `ui/replay/ReplayFeed.java:113` — fecha (`finally`, linha 122)
- `ui/replay/ReplayFeed.java:153` — fecha (`finally`, linha 158)
- `ui/series/Segmentable.java:133` — fecha (`finally`, linha 140)
- `ui/chart/ChartCanvas.java:938` (`growingFrom`) — **não fecha** (é o A3-11)

**Consequência:** três acertaram e um esqueceu, o que é exatamente o resultado
esperado quando o contrato não está no tipo. Declarando `implements AutoCloseable`
o compilador e a IDE passam a apontar o quarto.

**Correção:** `public final class TickLibrary implements AutoCloseable` e
`@Override public void close()`. O vazamento em si já está catalogado como
A3-11; o que se reporta aqui é a causa estrutural de ele existir.

**Tentei refutar:** li os quatro locais de construção e confirmei que três
fecham em `finally`. Confirmei também que `Executors.newSingleThreadExecutor`
não cria a thread até a primeira tarefa, então `ReplayFeed.all()` — que
constrói uma biblioteca por mercado × fonte só para listar diretório — não
vaza thread nenhuma; essa suspeita ficou refutada e está em LIMPO.

### L3-6 — `close()` dentro de `finally` cru: a falha real é substituída pela falha ao fechar

**Onde:** `domain/market/MetaTraderTicks.java:137-145` e
`domain/market/ProfitTrades.java:138-146`

**Trecho (idêntico nos dois):**

```java
        } finally {
            // Only reached when something threw: the normal path closed it
            // above. Without this a failed conversion leaves a file open and a
            // header still claiming zero ticks.
            if (writer != null) {
                writer.close();
            }
        }
```

**Problema:** `TickFile.Writer.close()` e `TapeFile.Writer.close()` **escrevem**
— descarregam o buffer, voltam a posição zero e reescrevem o cabeçalho
(`TickFile.java:279-289`, `TapeFile.java:333-341`). Numa conversão que falhou
por disco cheio, esse `close()` também falha, e uma exceção lançada de dentro
de um `finally` **substitui** a que estava em voo (não há supressão como no
try-com-recursos). O relatório da falha vira "não consegui escrever o
cabeçalho" em vez da causa.

Em `ProfitTrades` há um segundo caminho: o `finish()` de meio de laço (linha
111-115) fecha o escritor sem anular a variável; se o
`new TapeFile.Writer(...)` da linha 118 falhar em seguida, o `finally` fecha o
mesmo escritor de novo, e o segundo `flush()` sobre canal fechado lança
`ClosedChannelException` por cima do erro verdadeiro.

**Consequência:** conversões de 4 GB de texto falham com a mensagem errada.
Ambos os arquivos são a porta de entrada dos ticks comprados.

**Correção:** `try (TickFile.Writer w = ...)` não serve aqui (o escritor troca
dentro do laço), então: envolver o `close()` do `finally` em
`try { writer.close(); } catch (IOException e) { /* já estamos falhando */ }`,
ou capturar a causa e chamar `addSuppressed`. E anular `writer` logo depois de
cada `finish()`.

**Tentei refutar:** li as duas rotinas inteiras (`MetaTraderTicks:73-167`,
`ProfitTrades:92-160`) procurando um `catch` que preservasse a causa. Não há.

### L3-7 — A lista de séries aposentadas junta com vírgula sem escapar, e ninguém a escreve

**Onde:** `platform/SeriesCatalog.java:458-476`

**Trecho:**

```java
    public static Set<String> retired() {
        String saved = Settings.settings().get(RETIRED_KEY, RETIRED_BY_DEFAULT);
        Set<String> names = new LinkedHashSet<>();

        for (String each : saved.split(",")) {
...
    public static void setRetired(Set<String> names) {
        Settings.settings().put(RETIRED_KEY, String.join(",", names));
    }
```

**Problema:** os nomes vêm de nomes de arquivo no disco
(`namesIn`, linha 494-523), e vírgula é caractere legal em nome de arquivo no
Windows. Uma série `win-5m,antiga` gravada aqui volta como duas entradas que
não correspondem a nada, e a série de verdade continua sendo oferecida.

Segundo ponto, achado ao procurar quem chama: **`setRetired` não tem chamador
nenhum** em `src/main` nem em `src/test`. `data.retired` só pode ser editada à
mão, e não existe nenhum teste de ida e volta desse formato. As chaves irmãs
`data.roles`, `data.groups` e `data.scales` (lidas por `stated()`, linha
245-256, no formato `nome=valor,nome=valor`) estão na mesma situação: lidas em
quatro lugares, escritas em nenhum.

**Consequência:** um formato de escrita sem escape e sem teste, ao lado de três
formatos que só existem como padrão embutido. O primeiro nome com vírgula ou
com `=` quebra a leitura silenciosamente.

**Correção:** decidir se essas quatro chaves são de leitura apenas (então
remover `setRetired`, que é código morto público) ou de escrita (então escapar,
e cobrir com teste de ida e volta).

**Tentei refutar:** `grep -rn "setRetired\|data.retired\|data.scales\|data.roles\|data.groups" src/` —
`setRetired` aparece só na própria definição; as três chaves `data.*`
aparecem só como constantes e nos `stated()` que as leem.

### L3-8 — Com dez gráficos ou mais, eles reabrem fora de ordem

**Onde:** `ui/shell/MainWindow.java:301` sobre `platform/Settings.java:373-381`

**Trecho:**

```java
// MainWindow.java:301
        for (String key : workspace.keysStartingWith("chart.open.")) {
```

```java
// Settings.java:373-381
        for (String key : values.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                found.add(key);
            }
        }

        Collections.sort(found);

        return found;
```

**Problema:** a ordenação é lexicográfica sobre a chave, e o índice é escrito em
decimal sem preenchimento (`"chart.open." + at + ".series"`, linha 270). Com dez
ou mais gráficos, `chart.open.10.series` vem antes de `chart.open.2.series`.

**Consequência:** os gráficos reabrem na ordem 0, 1, 10, 11, 2, 3… — as abas do
desktop trocam de lugar a cada reinício. O comentário das linhas 292-297 explica
com cuidado por que a lista é lida inteira antes de abrir qualquer gráfico
(porque abrir reescreve as próprias chaves); a ordem escapou desse cuidado.

**Correção:** ou preencher o índice (`String.format("%03d", at)`), ou ordenar
numericamente ao ler.

**Tentei refutar:** `MainWindowTest.java:205` ("every chart open at closing time
comes back, not just the first") e a asserção da linha 258 usam a mesma
`keysStartingWith`, e o cenário do teste tem poucos gráficos — nunca dez.

### L3-9 — Os records de layout guardam `List` sem construtor compacto

**Onde:** `ui/chart/ChartLayout.java:41`, `:62`, `:104`

**Trecho:**

```java
public record ChartLayout(String name, List<Entry> entries, List<Pane> panes) {
    public record Pane(List<Entry> entries, int height, boolean minimised) {
    public record Entry(String kindKey, List<Integer> parameters, boolean visible,
                       String appearance) {
```

**Problema:** nenhum dos três tem construtor compacto com `List.copyOf`. Quem
constrói guarda a referência que passou, e pode continuar mexendo nela — que é
justamente o que um record promete que não acontece. É a mesma forma já
reportada em A5-20 para `OverlayCatalog.Kind`, em outra área.

**Consequência:** hoje nenhuma, e por disciplina, não por tipo: os seis locais
de construção copiam à mão (`ChartLayout.java:67` `List.of(...)`, `:141`
`List.of()`, `:168` `List.copyOf`, `ChartLayouts.java:143` `List.of`, `:222` e
`:235` `List.copyOf(gathering)`, `:330` `List.copyOf(parameters)`). Basta um
sétimo que esqueça.

**Correção:** construtor compacto com `List.copyOf` nos três, e `Objects.requireNonNull`
em `kindKey`/`appearance` — `Entry.build()` (linha 118) já desreferencia
`parameters` sem checar.

**Tentei refutar:** rodei um script sobre todo `src/main` procurando
`return <campo-coleção>;` — **zero ocorrências**, ou seja, nenhum getter deste
projeto devolve coleção mutável (ver LIMPO). O risco aqui é só o dos records.

### L3-10 — `MarketFile.write` trunca o arquivo antes de saber se consegue escrevê-lo

**Onde:** `domain/market/MarketFile.java:154-156`

**Trecho:**

```java
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
```

**Problema:** grava no lugar. Uma falha no meio (disco cheio, energia) deixa o
`.endv` com o cabeçalho prometendo N barras e o corpo com menos. `read` recusa
o arquivo pela conferência de tamanho da linha 95-104 — o que é o
comportamento certo, mas quem tinha o arquivo antes fica sem ele.

**Consequência:** perda da base. Uma série real é 824.881 barras, e o javadoc
da linha 141-147 diz que este método existe para salvar uma base construída a
partir de outras duas — isto é, a saída de trabalho que não está em lugar
nenhum.

**Correção:** escrever num `.tmp` ao lado e `Files.move(…, ATOMIC_MOVE)` no
fim. É a mesma correção que A7a-8 pede para `Settings.save`, aqui no arquivo
que guarda o mercado.

**Tentei refutar:** procurei `Files.move`, `ATOMIC_MOVE`, `createTempFile` em
`src/main` — não existem. Nenhuma escrita deste projeto é atômica.

### L3-11 — O nome do layout padrão é gravado traduzido, e a seleção se perde ao trocar de idioma

**Onde:** `ui/chart/ChartLayouts.java:142` com `ui/chart/LayoutBar.java:110` e `:494-502`

**Trecho:**

```java
// ChartLayouts.java:142 — o nome do layout é uma string traduzida
        return new ChartLayout(Messages.get("layout.default"),
// ChartLayouts.java:93 — e é o nome que vai para o arquivo
            PREFS.put("layout." + i + ".name", layout.name());
// ChartLayouts.java:118 — e a seleção também é guardada por NOME
        PREFS.put("selected." + chartKey, layoutName);
```

```java
// LayoutBar.java:494-502
    private int indexOf(String name) {
        for (int i = 0; i < layouts.size(); i++) {
            if (layouts.get(i).name().equals(name)) {
                return i;
            }
        }

        return 0;
    }
```

**Problema:** o layout é identificado por nome, o nome do padrão é
`Messages.get("layout.default")`, e `indexOf` devolve **0** quando não acha —
não distingue "não existe" de "é o primeiro".

**Consequência:** quem trocar o idioma tem `selected.<gráfico>` guardado como
"Padrão" e `all()` devolvendo "Default": a busca falha, o `indexOf` responde 0,
e o gráfico volta calado para a primeira aba, jogando fora a escolha do leitor.
Um layout já capturado fica com nome português dentro de uma interface inglesa,
para sempre. É parente de A4-6 e A4-10 (seleção de aba quebrada), com outra
causa.

**Correção:** guardar um identificador estável ao lado do nome, e fazer
`indexOf` devolver `-1` para "não achei", deixando o chamador decidir.

**Tentei refutar:** confirmei que `LayoutBar.java:110`
(`selected = indexOf(ChartLayouts.selectedFor(chartKey))`) é o único ponto de
restauração, e que `apply()` (linha 384-395) trata `selected` fora de faixa,
mas nunca vê fora de faixa porque `indexOf` já devolveu 0.

---

## Achados BAIXA

### L3-12 — Uma linha de CSV mais longa que o buffer perde a cauda em silêncio

**Onde:** `domain/market/ProfitTrades.java:215` e `:222-225`; mesma forma em
`domain/market/MetaTraderTicks.java:77` e `:93-97`

**Trecho:**

```java
            byte[] row = new byte[512];
...
                    if (at != '\n') {
                        if (at != '\r' && inRow < row.length) {
                            row[inRow++] = at;
                        }

                        continue;
                    }
```

**Problema:** os bytes além de 512 são jogados fora sem uma palavra e a linha
truncada segue para `parse`. Se o corte cair *antes* do sétimo `;`, `parse`
recusa com "expected eight" (linha 258-260) — bom. Se cair *depois*, a linha é
aceita com o último campo mutilado. O arquivo inteiro deste projeto é escrito
sob a regra "um valor em que ninguém pode confiar é pior que uma conversão que
precisa rodar de novo" (javadoc, linha 61-68); esta é a exceção.

**Correção:** lançar quando `inRow == row.length`, em vez de descartar.

### L3-13 — Uma alocação por negócio e por tick, dentro do laço de conversão

**Onde:** `domain/market/ProfitTrades.java:250` e `domain/market/MetaTraderTicks.java:183`

**Trecho:**

```java
    private static void parse(Path csv, Rows rows, byte[] row, int length, long line) {
        int[] ends = new int[8];
```

```java
        // Where each field begins, found once for the row.
        int[] starts = new int[8];
```

**Problema:** "found once for the row" — e o vetor também é criado uma vez por
linha. Com os números que o próprio javadoc mede (4,4 milhões de ticks por
pregão, `TickFile.java:68`), são 4,4 milhões de vetores de 8 inteiros por
sessão, só para achar separadores.

**Correção:** um vetor reaproveitado, passado como parâmetro (o `row` já é
reaproveitado assim, duas linhas acima).

Não confundir com A3-1, A4-3, A5-11 e A5-14, que são alocação por barra na
*pintura*. Esta é na conversão, fora da EDT — por isso BAIXA e não MÉDIA.

### L3-14 — O nome de corretora tem o comprimento gravado sem conferência

**Onde:** `domain/market/TapeFile.java:358-366`

**Trecho:**

```java
                byte[] name = each.getValue().getBytes(StandardCharsets.UTF_8);
                ByteBuffer entry = ByteBuffer.allocate(2 * Short.BYTES + name.length)
                        .order(ByteOrder.BIG_ENDIAN);

                entry.putShort((short) (int) each.getKey());
                entry.putShort((short) name.length);
```

**Problema:** `check()` (linha 317-322) guarda o **código** da corretora contra
`LARGEST`; nada guarda o **nome**. Um nome acima de 65.535 bytes escreve um
comprimento truncado, e `brokers()` na leitura (linha 205-218) passa a ler o
dicionário desalinhado a partir dali.

**Correção:** um `check` simétrico no nome, com a mesma mensagem que os outros.

### L3-15 — O javadoc de `parsePanes` diz que a altura vem da primeira linha; o código usa a última

**Onde:** `ui/chart/ChartLayouts.java:127-134` contra `:221-231`

**Trecho — o que promete:**

```
 * three indicators, and a format with two levels of punctuation is a
 * format nobody can read in the file or fix by hand. The height and the
 * minimised flag repeat on every line of a pane; they are read from the
 * first and the repetition costs nothing.</p>
```

**Trecho — o que faz:**

```java
            if (mine != belongsTo && !gathering.isEmpty()) {
                panes.add(new ChartLayout.Pane(List.copyOf(gathering), height, minimised));
                gathering.clear();
            }

            belongsTo = mine;
            height = number(fields[3], 0);
            minimised = Boolean.parseBoolean(fields[4].trim());
```

`height` e `minimised` são sobrescritos a cada linha do painel; o `Pane` é
montado depois, com o valor da **última**. Inócuo enquanto o escritor repete o
mesmo valor; deixa de ser num arquivo editado à mão, que é a razão declarada
de o formato ser texto.

### L3-16 — `{@link Preferences}` não resolve: a classe não existe neste projeto

**Onde:** `ui/chart/ChartLayouts.java:29`

**Trecho:**

```java
 * <p>Stored as text in {@link Preferences}: one line per indicator, fields
```

Não há `import java.util.prefs.Preferences` neste arquivo nem em nenhum outro
do projeto (varredura completa: as 20 ocorrências de "Preferences" são
`ChartPreferences` e `ReplayPreferences`, classes próprias). O `{@link}` é um
link quebrado que o javadoc reporta como erro. A4-5 já reporta que esta classe
documenta um armazenamento que não usa e corta em 40 indicadores por um limite
inexistente; esta linha é a evidência pontual.

---

## O balanço dos handles

Todo ponto do programa que abre um arquivo, um canal ou um pool.

| Onde abre | O quê | Onde fecha | Veredito |
| --- | --- | --- | --- |
| `MetaTraderTicks.java:87` | `BufferedInputStream` sobre `Files.newInputStream` | try-com-recursos, mesma linha | ✅ |
| `MetaTraderTicks.java:112,129` | `TickFile.Writer` (canal) | `finish()` no caminho normal + `finally` `:141` | ✅ handle; ⚠️ L3-6 (a causa se perde) |
| `ProfitTrades.java:213` | `BufferedInputStream` sobre `Files.newInputStream` | try-com-recursos, mesma linha | ✅ |
| `ProfitTrades.java:118` | `TapeFile.Writer` (canal) | `finish()` + `finally` `:142` | ✅ handle; ⚠️ L3-6 (dupla-fechada possível) |
| `TickLibrary.java:283` | `Files.walk` → `Stream<Path>` | `try (var files = …)` | ✅ |
| `TickLibrary.java:93` | `ExecutorService` (thread daemon) | `close()` `:202`, chamado por 3 dos 4 donos | ⚠️ L3-5 / A3-11 |
| `SeriesCatalog.java:495` | `Files.walk` → `Stream<Path>` | `try (Stream<Path> files = …)` | ✅ |
| `Settings.java:142-143` | `InputStream` + `InputStreamReader` | try-com-recursos, duas linhas | ✅ |
| `Settings.java:265` | `BufferedWriter` | try-com-recursos | ✅ handle; ⚠️ A7a-8 (trunca no lugar) |
| `MarketFile.java:81,92,202` | `FileChannel` (leitura) | try-com-recursos, cada um | ✅ |
| `MarketFile.java:154` | `FileChannel` (escrita) | try-com-recursos | ✅ handle; ⚠️ L3-10 (trunca no lugar) |
| `TickFile.java:102,113,134,149` | `FileChannel` (leitura) | try-com-recursos, cada um | ✅ |
| `TickFile.java:226` (`Writer`) | `FileChannel` (escrita) | `close()` `:279` com `finally { channel.close(); }` | ✅ |
| `TapeFile.java:113` | `FileChannel` (leitura) | try-com-recursos | ✅ |
| `TapeFile.java:264` (`Writer`) | `FileChannel` (escrita) | `close()` `:333` com `finally { channel.close(); }` | ✅ |
| `JobService.java:263` | `ExecutorService` (pool fixo) | `close()` `:398-405`, ligado ao gancho de desligamento em `Launcher.java:83` | ✅ |
| `ReplaySession.java:275` | `javax.swing.Timer` | `stop()` `:689-696`, que também fecha a `TickLibrary` | ✅ (a *chamada* de `stop` é assunto de A6-10) |
| `LayerBoundaryTest.java:115,145,153` | `Files.walk` × 3 | try-com-recursos, cada um | ✅ |

**Contagem:** 24 aberturas de recurso no programa. **23 fecham.** A única que
não fecha é `ChartCanvas.growingFrom` (`ChartCanvas.java:938`), já catalogada
como A3-11 — e L3-5 diz por que ela pôde acontecer.

`Files.walk`/`Files.lines`/`Files.find`: **4 ocorrências, 4 em try-com-recursos.**
Zero esquecimentos — que era a suspeita de maior valor da lente, e ficou
refutada.

---

## Os formatos salvos, um a um

| Formato | Onde | Separador | Ida e volta testada? | Sobrevive a campo a menos? | Sobrevive a separador dentro do valor? |
| --- | --- | --- | --- | --- | --- |
| `.endv` (barras) | `MarketFile.java` | binário, registro fixo | ✅ `MarketFileTest` | n/a (versão no cabeçalho) | n/a |
| `.bin` de ticks | `TickFile.java` | binário, registro fixo | ✅ `TickFileTest` (texto original reimpresso e comparado) | n/a | n/a |
| tape de negócios | `TapeFile.java` | binário + dicionário no fim | ✅ `TapeFileTest` | n/a | ⚠️ L3-14 (nome > 64 KiB) |
| arquivo de configuração | `Settings.java` | `=`, com escape próprio (`:283-320`) | ✅ `SettingsTest` + `SettingsEncodingTest` (inclusive `\n` dentro do valor e a reparação de UTF-8 lido como Latin-1) | ✅ chave ausente → `fallback` | ✅ escapa `\ \n \r \t = : # !` |
| `layout.N.entries` | `ChartLayouts.format/parse` | `\n` entre linhas, `\|` entre campos, `,` entre números | ✅ `ChartLayoutTest` | ✅ 3 campos aceitos, 4º opcional | ⚠️ nenhum escape de `\|`; hoje nenhum valor contém `\|`, mas nada impede |
| `layout.N.panes` | `ChartLayouts.formatPanes/parsePanes` | idem, com o índice do painel à frente | ✅ `ChartLayoutTest` (linhas 71-84) | ✅ exige 5, lê 6º | ⚠️ idem; e ⚠️ L3-4 (discorda do outro analisador), ⚠️ L3-15 |
| *appearance* da média | `MovingAverage:445-451` | `;`, 7 campos | ✅ `MovingAverageTest:163` + `:187` (campo desconhecido) | ✅ campo a campo, cada um com seu guarda | ✅ nenhum campo é texto livre (`ownPeriod` vem do catálogo de períodos) |
| *appearance* das Bandas | `BollingerBands:560-580` | `;` com `chave=valor` | ✅ `BollingerBandsTest:236` + `:265` | ✅ | ✅ |
| *appearance* do IFR | `RelativeStrength:250-270` | `;` | ✅ `RelativeStrengthTest:244` + `:258` | ✅ | ✅ |
| *appearance* do estocástico | `SlowStochastic:375-400` | `;`, 15 campos | ❌ **nenhum** | ? | ? | → **já reportado: A5** |
| `chart.<chave>.*` (a vista) | `ChartCanvas.storeView/restoreView:1627-1662` | chave por campo | ❌ **nenhum teste** | ✅ tudo com `fallback` e limitação | ✅ (`String.valueOf(double)`/`parseDouble` são independentes de locale — conferido) | → e a posição horizontal não é gravada: A3-8 |
| `segments.<série>.<n>.*` | `Segmentation.of/set:104-146` | chave por campo, índice denso | ✅ `SegmentationTest` | ✅ para em `name==null \|\| from==null`; data ilegível pula só a entrada | ✅ (datas em ISO; o nome é valor, não chave) |
| `<série>#<trecho>` (nome composto) | `Segmentation.MARK:154-192` | `#` | ✅ `SegmentChipTest` | n/a | ⚠️ `indexOf` no primeiro `#`: uma série com `#` no nome é lida como trecho |
| `replay.feed` | `ReplayFeed.saved/read:230-246` | `:` | ❌ nenhum | n/a | ✅ **por construção**: `read` compara com os candidatos enumerados, nunca separa a string |
| `replay.from` / `.to` | `ReplayPanel:566-567` | ISO, chave por campo | ❌ nenhum | ✅ | ✅ |
| `data.retired` | `SeriesCatalog:458-476` | `,` | ❌ nenhum | ✅ | ❌ **L3-7** |
| `data.roles` / `.groups` / `.scales` | `SeriesCatalog.stated:245-256` | `,` e `=` | ❌ nenhum | ✅ ignora par sem `=` | ❌ nome com `,` ou `=` quebra; **e nada escreve estas chaves** — L3-7 |
| `chart.open.N.*` | `MainWindow:265-282, 301-315` | chave por campo | ✅ `MainWindowTest:205` | ✅ | ✅ | → mas ⚠️ L3-8 (ordem) |
| `<chave>.floating/.x/.y/.width/.height/.maximised` | `ChartHolder:485-880` | chave por campo | ❌ nenhum | ✅ | ⚠️ L3-3 (a chave colide) |

---

## Já visto pelas áreas

O que a lente encontrou e que já está no índice — citado e abandonado:

- `ChartCanvas.java:938` `TickLibrary` do replay nunca fechada → **A3-11**.
  (O que é novo é a causa: L3-5.)
- `ChartCanvas.java:1627` `storeView` não guarda a posição → **A3-8**.
- `ChartLayouts` documenta armazenamento que não usa e corta em 40 → **A4-5**.
  (O `{@link}` quebrado é a evidência pontual, L3-16.)
- `LayoutBar` seleção de aba quebrada por nome → **A4-6**, **A4-10**.
  (A causa "nome traduzido" é nova: L3-11.)
- `ChartLayouts.java:90-109` `capture()` reescreve o arquivo 3n+1 vezes → **A4-11**.
- `Settings.java:257-284` `save()` trunca no lugar e reescreve a cada chave → **A7a-8**.
  (L3-2 mostra que o custo cresce sem limite; L3-10 mostra o mesmo padrão em `MarketFile`.)
- `Settings.java:278-283` falha de gravação vira chave `_unsaved` → **A7a-10**.
- `SeriesCatalog.java:490-527` varredura engole erro por um caminho → **A7a-11**;
  `:495-523` relista tudo na EDT → **A7a-12** (é lá que
  `.filter(name -> !retired().contains(name))`, linha 504, reconstrói o
  conjunto de aposentadas uma vez **por arquivo**);
  `:601-620` verifica-depois-age no cache → **A7a-13**.
- `Segmentation.java:133-148` `set` apaga tudo e grava um arquivo por vez → **A7a-16**.
- `SeriesWindow.java:404` grava mesmo sem edição e apaga o que não leu → **A7b-13**;
  `:388` grava sob a chave literal "null" → **A7b-14**;
  duas janelas sobre a mesma série → **A7b-6**.
  (`Segmentable.java:126`, que devolve conjunto vazio ao engolir `IOException`,
  é candidata a ser a referência que falta em **A7b-35**.)
- `TickLibrary.java:283-300` `exported()` engole `IOException` → **A1-9**.
- `TapeFile.java:531-534` `aggressorAt` clona vetor por chamada → **A1-8**.
- `MovingAverage.java:157-161` javadoc do construtor variádico em outra ordem → **A5-18**.
  (É esse construtor que L3-1 mostra recebendo três números.)
- `OverlayCatalog.java:51-52` record sem construtor compacto → **A5-20**.
  (Mesma forma em `ChartLayout`: L3-9.)
- `Renko.java:220-222` `Carry` guarda `TradeTally` mutável → **A2-9**.
- Alocação por barra na pintura → **A3-1**, **A4-3**, **A5-11**, **A5-14**.

---

## O que está LIMPO, e como foi conferido

**Fluxos de arquivo.** As 4 chamadas de `Files.walk` do projeto
(`TickLibrary:283`, `SeriesCatalog:495`, `LayerBoundaryTest:115,145,153`) estão
todas em try-com-recursos. Não há `Files.lines` nem `Files.find` em lugar
nenhum. Não há um único `new FileInputStream`, `new FileOutputStream`,
`new RandomAccessFile`, `new Scanner` nem `new FileReader` no projeto — todo
acesso a arquivo passa por `Files.*` ou por `FileChannel.open`. Conferido por
grep dos cinco construtores: zero ocorrências.

**Canais.** As 12 aberturas de `FileChannel` foram lidas uma a uma. As 9 de
leitura estão em try-com-recursos. As 3 de escrita (`MarketFile:154`,
`TickFile.Writer:226`, `TapeFile.Writer:264`) estão em classes `AutoCloseable`
cujo `close()` põe o `channel.close()` num `finally` — de modo que a falha ao
reescrever o cabeçalho não deixa o canal aberto. Conferido lendo
`TickFile:279-289` e `TapeFile:333-341`.

**`java.util.prefs.Preferences` não é usada.** As 20 ocorrências da palavra são
as classes próprias `ChartPreferences` e `ReplayPreferences`, mais dois
javadocs herdados de uma versão anterior. Portanto **nenhum** dos limites da
API — 8.192 caracteres por valor, 80 por chave — se aplica a este projeto:
`platform/Settings` é um `.properties` escrito à mão, sem teto. O corte de
`MAX_ENTRIES = 40` em `ChartLayouts:51` é órfão desse passado (A4-5), e a
suspeita de "chave cortada em silêncio" ficou **refutada por não existir a
API**.

**Serialização Java.** Zero `implements Serializable` escritos à mão, zero
`readObject`/`writeObject`, zero `ObjectOutputStream`/`ObjectInputStream`. Os
190 `serialVersionUID`/`transient` são todos de subclasses de componentes Swing
(que herdam `Serializable` de `java.awt.Component`), e o `transient` está lá só
para calar o *warning*. Nenhum formato de disco depende de nome de classe —
que é exatamente o que o javadoc de `ChartLayouts:29-33` diz ter sido a
intenção, e neste ponto ela foi cumprida.

**Coleção mutável escapando por getter.** Rodei um script sobre todos os
arquivos de `src/main`: extrai os campos declarados como
`List/Map/Set/Collection/NavigableSet/…` e procura `return <campo>;`.
**Zero ocorrências.** Os `return` de coleção que existem devolvem sempre uma
coleção local recém-construída (`Sessions.of`, `TickLibrary.exported`,
`Settings.keysStartingWith`, `SeriesCatalog.retired`, `BarReadout.rows`,
`ChartLayout.build`), ou uma cópia explícita
(`TickLibrary.residentDays:190` faz `new ArrayList<>(resident.keySet())` dentro
do bloco sincronizado, que é o certo). O achado A7b-27 do índice, que está sem
referência, não corresponde a nenhuma ocorrência deste padrão em `src/main`.

**`ReplayFeed` não vaza thread.** Suspeitei que `ReplayFeed.all():110-125`
construísse uma `TickLibrary` por mercado × fonte só para listar diretório, e
que cada construção criasse uma thread. Refutado em dois passos:
(a) `Executors.newSingleThreadExecutor` não cria a thread até a primeira tarefa,
e `exported()` não submete nenhuma; (b) as duas construções de `ReplayFeed`
(`:113` e `:153`) e a de `Segmentable:133` fecham em `finally`.

**`MetaTraderTicks.convert` fecha o escritor mesmo falhando.** Suspeitei de
vazamento porque o `TickFile.Writer` não está no try-com-recursos do
`InputStream`. Refutado: há um `finally` explícito em `:137-145`, com o
comentário certo. (O que sobrou é o problema *da exceção*, não do handle:
L3-6.)

**Números decimais no arquivo de configuração.** `storeView` grava
`String.valueOf(stretch)` e `restoreView` lê com `Double.parseDouble`
(`ChartCanvas:1630-1631` e `:1670`). Suspeitei de dependência de locale — em
pt-BR o separador é vírgula. Refutado: `String.valueOf(double)` e
`Double.parseDouble` são ambos independentes de locale por contrato (só
`NumberFormat`/`String.format` não são). O `readDouble:1666-1679` ainda limita
e trata `NaN`/infinito. Este trecho está certo.

**Escape do arquivo de configuração.** `Settings.escape:283-320` escapa
`\ \n \r \t = : # !` e o espaço em chave ou em início de valor; `load` usa
`Properties.load(Reader)` com UTF-8 explícito, que desfaz tudo isso. O caso que
mais me preocupava — o layout, que é um valor com `\n` dentro — está coberto
por `SettingsTest:87` com o texto exato de duas linhas. A rotina de reparação
`repair/undo:130-240` também é sólida: só desfaz quando **todos** os caracteres
cabem num byte **e** os bytes formam UTF-8 válido, o que é a condição certa e
está afirmada em teste (`SettingsEncodingTest:96-99`).

**Índice do cabeçalho binário.** Os três formatos binários guardam a contagem
como `long` e a recusam fora de `[0, Integer.MAX_VALUE]` antes de alocar
(`TickFile:342-345`, `TapeFile:135-137`, `MarketFile` pela conferência de
tamanho em `:95-104`). Um cabeçalho corrompido dá `IOException`, não
`OutOfMemoryError` — que era a suspeita "o que é alocado cabe" mais séria deste
lado. Os 48 MB de `MarketFile.read:108-113` são o caso normal e estão
declarados; o que os limita é a conferência de tamanho da linha 95, que só
aloca depois de saber que o arquivo tem exatamente os bytes prometidos.

**Crescimento dos vetores de `ProfitTrades.Rows`.** `room():193-205` dobra os
sete vetores juntos e é chamada em `:267`, antes de cada escrita — conferido
que não há caminho que escreva em `rows.day[rows.count]` sem passar por ela.

**Fora do escopo, registrado.** Há 2,7 MB em 88 arquivos de cache do graphify
dentro da árvore de fontes, em
`src/main/java/br/com/jorge/reis/endeavourneo/graphify-out/`. Não está no git
(`git ls-files` não devolve nada) e não é compilado nem empacotado (só `.java`
de `src/main/java` e o conteúdo de `src/main/resources` entram no jar), então
não é defeito — mas é ruído em qualquer varredura que não o exclua, e foi
excluído de todos os greps deste relatório.
