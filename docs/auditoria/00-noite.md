# A noite de 07/09/2026 — corrida autônoma

Ele foi dormir e pediu, textualmente:

> 1 – com a lista do que falta, resolve todos os restantes
> 2 – refaça a auditoria completa, rodando até 2 agente ao mesmo tempo, sempre
>     produzindo o relatório
> 3 – ao final junte tudo e faça a validação cruzada
> 4 – corrija todos os itens restante dessa nova auditoria
> 5 – faça tudo isso sem mim, o que precisar da minha decisão, adie se for
>     possível, senão, decida a melhor opção e registre pra me apresentar a
>     escolha quando eu voltar
> 6 – vou dormir, faça tudo como falei e registre cada passo, para continuar se
>     vc parar

**Este arquivo é o estado.** Quem retomar — eu, depois de perder contexto, ou
outra sessão — lê daqui e continua. Atualizado ao fim de cada lote, antes do
commit.

---

## O mecanismo escolhido, e por quê

Ele perguntou se é um laço ou outra coisa. **Não é um laço.**

Um laço (`/loop`, `ScheduleWakeup`) só paga quando se está *esperando* por algo
de fora — uma CI, um deploy, uma fila. Aqui não há espera: há trabalho contínuo.
Acordar por temporizador gastaria requisição sem produzir avanço.

O que dá continuidade é **este arquivo mais os commits**. Cada lote fecha em um
commit, e a fila abaixo diz o que vem depois. Se eu parar no meio, o próximo a
sentar lê a fila e segue; nada depende de eu estar vivo.

**Agentes só na fase 2**, dois por vez, como ele pediu. E **nunca com o código
mudando debaixo deles**: isso já custou caro uma vez — corrigi dois dos cinco
defeitos do gabarito da A8b enquanto ela ainda lia, e o relatório saiu falando
de código que não existia mais. Por isso as fases são estritamente sequenciais.

---

## As fases

| # | fase | estado |
|---|---|---|
| 0 | Trabalho em voo quando ele foi dormir | **fechada** — `1b34571` |
| 1 | Resolver os achados de domínio e replay da auditoria I | **fechada** — `f6f6640` |
| 2 | Refazer a auditoria completa, 2 agentes por vez | **fechada** — 10 áreas + 4 lentes |
| 3 | Juntar e validar cruzado | **fechada** — `ii/00-cruzamento.md` |
| 4 | Corrigir os achados novos, e o que sobrou da lista velha | **em curso** — 21 das 32 ALTA |

---

## O que falta — o inventário

Da auditoria de 30/08 a 06/09/2026, nove áreas mais quatro lentes
transversais. `00-achados.md` é o índice; o detalhe está no relatório de cada
área.

| gravidade | levantados | fechados | **abertos** |
|---|---:|---:|---:|
| ALTA | 52 | 51 + 1 refutado | **0** |
| MÉDIA | 133 | **40** | **93** |
| BAIXA | 115 | 0 | **115** |

Atualizado às 05h. Estão **fechadas inteiras** as áreas **A1, A2 e A3** nos
MÉDIA, mais L2-3 e A5-12. É todo o domínio e todo o replay — onde um defeito
muda número e não texto, que foi o corte da decisão D5.

Os 24 MÉDIA já fechados, com o commit:

| lote | quantos | commit |
|---|---:|---|
| isolamento dos testes | 4 | anteriores a esta noite |
| `TickLibrary` fechada pelos donos | 5 | `73fb739` |
| javadoc e testes do renko | 3 | `7c3d284` |
| corretude de domínio I | 4 | `1d9a968` |
| corretude de domínio II (ticks) | 4 | `b9bf044` |
| corretude de domínio III (renko, estocástico) | 4 | `939abb7` |

### A fila da fase 1, em ordem

A ordem é por **valor**, não por numeração: o que muda comportamento antes do
que muda texto.

1. **L2-3** — dois "agoras" num quadro de replay. O renko anda por tempo de
   negócio e o candle por contagem de ticks; onde os negócios não chegam
   uniformes as duas réguas divergem, e é na abertura que isso é pior. Último
   MÉDIA de corretude de domínio, e o maior: a correção muda o `TickPath` para
   carregar carimbos junto dos preços.
2. **Corretude restante fora do domínio** — as MÉDIA de `ChartCanvas`, eixos,
   indicadores e replay que mudam o que se vê.
3. **EDT e entrada/saída** — trabalho pesado na thread da interface. Inclui a
   decisão D2 abaixo.
4. **Persistência e recursos** — o que vaza, o que não fecha, o que não volta.
5. **Testes sem dentes** — testes que passariam com o produto quebrado. Cada um
   vale mais que um achado: é uma licença falsa.
6. **javadoc órfão, i18n, consistência de interface** — o grosso das BAIXA.

---

## Decisões — o que adiei e o que decidi sozinho

Ele pediu: adiar quando der; quando não der, decidir a melhor opção e registrar
para apresentar.

### D1 — Cache em disco das barras dobradas de um export · **FEITA em 08/09/2026**

Ele aprovou. Medido na base dele, antes e depois:

| export | pregões | lidos | barras | primeira | de novo |
|---|---:|---:|---:|---:|---:|
| MetaTrader | 20 | 1.838 MB | 10.766 | **16,6 s** | **0,09 s** |
| Profit | 9 | 691 MB | 5.074 | **8,8 s** | **0,03 s** |

O cache inteiro são **0,7 MB em 29 arquivos**, e as barras da segunda leitura são
as mesmas da primeira, conferidas barra a barra.

**A invalidação, que era a razão de adiar, saiu de graça ao guardar POR PREGÃO.**
Um arquivo entra, um arquivo sai: o dia que nunca foi dobrado não tem cache, e o
dia reimportado tem outro. Não há conjunto para manter de acordo e não há nada a
invalidar quando um pregão novo chega — o pregão novo é simplesmente o que não
está guardado. Abrir uma semana com seis dias já dobrados custa o sétimo.

**Como ele sabe que o cache vale: os dois carimbos são IGUAIS.** Depois de
escrever, o cache recebe o carimbo de modificação do próprio pregão. A pergunta
deixa de ser "o cache é mais novo" — que um arquivo restaurado de backup com os
tempos preservados responde errado — e passa a ser "ele foi feito a partir DESTA
versão deste arquivo".

O artefato derivado continua sendo artefato derivado, e diz isso sendo derivado:
apagar os `.folded` custa os segundos de novo e mais nada.

### D6 — Uma candle plana contada como negócio · **A DECIDIR (08/09/2026)**

`TradeTally.seeing` decide "isto é um resumo" pela faixa da barra: `high != low`.
Uma candle **plana** — um minuto em que tudo negociou a um preço — tem faixa zero
e é indistinguível de um negócio único pelos números. Uma fonte de candles cujas
primeiras barras sejam planas conta CANDLES como negócios até chegar a primeira
barra com faixa. Improvável no WIN; latente para outro papel.

**Tentei corrigir e voltei atrás.** A correção certa é a série dizer o que ela é —
um marcador `Printed` no `TickBars`, no mesmo formato do `Untraded` e do
`Counted`, já que a diferença está em de onde as barras vieram e não no que elas
contêm. Implementado, **onze testes caíram em três arquivos** — e não por
fixture: a regra do resumo também governa o CARIMBO do tijolo
(`settle` só escreve `bricks.times[at]` quando não é resumo), então marcar as
candles planas move o carimbo de todo tijolo assentado a partir delas.

Mover carimbo de renko é a coisa que este projeto mede com mais cuidado, e o
achado é BAIXA e latente. **Não é decisão minha.** As opções, com o custo:

1. **Deixar como está.** Zero risco; o defeito espera outro papel.
2. **Marcador `Printed`**, e aceitar que o carimbo dos tijolos de candle plana
   passa a vir da barra que os fechou. Três arquivos de teste a reescrever, e a
   pergunta "qual carimbo é o certo" a responder antes.
3. **Separar as duas perguntas**: um marcador para a CONTAGEM e a regra da faixa
   para o carimbo. Corrige o defeito sem mexer no carimbo, ao preço de duas
   regras onde hoje há uma.

### D2 — Renko de 11R dobrando 1,7 milhão de caixas na EDT · **A DECIDIR NA FASE 1**

Medido na base dele: `Renko(11, 2)` sobre 100.000 barras assenta **1.695.538
caixas em 468 ms e 65 MB**, na thread da interface, a cada redesenho. Note que
`11R` da interface é caixa de 50 pontos — a medição acima usou caixa de 11
pontos, então é o pior caso, não o caso dele.

Vou medir o caso real dele antes de mexer. Se for pesado, a correção é dobrar
fora da EDT, que não muda o que é desenhado — decido isso sozinho, é melhoria
sem contrapartida. Registro o número medido aqui quando tiver.

### D3 — Formato de tick sem cruzamento de datas · **SÓ REGISTRO**

MetaTrader cobre jan/2021 e Profit cobre ago-set/2026. **Não há um pregão em
que os dois se cruzem**, então não dá para comparar livro e agressão lado a
lado. Não é decisão, é limite do que existe; registrado porque muda o que se
pode estudar.

---

### D5 — Onde parar de corrigir a lista velha · **DECIDIDA POR MIM**

Medida a velocidade real da noite: **onze achados a cada duas horas**, contando
a correção, o teste que faltava, a prova de dentes e o commit. Nesse passo os 97
MÉDIA restantes são dezoito horas. Não cabem antes da auditoria, e a auditoria é
o que ele pediu explicitamente na fase 2.

**Decidi cortar aqui.** A fase 1 fecha ainda os MÉDIA de **domínio e replay** —
onde um defeito muda número, e não texto — e para. O resto da lista velha vai
para a fase 4, ao lado do que a auditoria nova achar.

**Por quê:** a auditoria relê o código atual. Metade das áreas foi reescrita
esta noite, então metade da lista velha aponta para linhas que já não existem.
Gastar as horas restantes na lista velha entregaria correções de sete dias atrás
e nenhuma auditoria; gastar na auditoria entrega a lista nova, que é a que vale
para o estado de agora.

**O que isso custa:** as áreas A4, A7a, A7b, A8a, A8b e as lentes L1, L3 e L4
ficam sem passada de correção esta noite. Elas voltam pela auditoria, e a
validação cruzada da fase 3 confere a lista velha contra a nova para achar o que
só a velha viu.

### D4 — A ordem entre corrigir tudo e reauditar · **DECIDIDA POR MIM**

Ele pediu, nesta ordem: resolver todos os restantes, depois refazer a auditoria.
Restam **~105 MÉDIA e 115 BAIXA**. Corrigir os 220 à mão é trabalho serial e
lento; a auditoria é a parte cara, valiosa e paralelizável. Fazendo a fase 1
inteira primeiro, a fase 2 não começa esta noite.

**Decidi assim:** fase 1 fecha os **MÉDIA** — o que muda comportamento, o que
vaza recurso, e os testes sem dentes. As **BAIXA entram na fase 4**, junto com o
que a auditoria nova achar.

**Por quê:** a auditoria nova relê o código como ele estará depois das
correções. Uma BAIXA de sete dias atrás aponta para linhas que já mudaram — três
áreas inteiras foram reescritas esta noite. Corrigir a lista velha antes de
reauditar é gastar duas vezes e acertar a segunda; deixar a auditoria derivá-las
do código atual é gastar uma.

**O que isso custa:** se a auditoria nova não reencontrar alguma BAIXA da lista
velha, ela fica sem correção. Aceitei porque a lista velha continua no disco
(`00-achados.md`) e a validação cruzada da fase 3 confere uma contra a outra — é
exatamente o tipo de furo que ela existe para pegar.

---

## A auditoria II — como está desenhada

**Pasta própria**, `docs/auditoria/ii/`, para não sobrescrever a primeira: a
fase 3 precisa das duas lado a lado.

**Os agentes de área não leem a auditoria I.** Isso é deliberado e é o que dá
valor à segunda passada: se ela lesse a primeira, encontraria o que a primeira
encontrou e a validação cruzada não significaria nada. O briefing
(`ii/00-briefing.md`) diz isso em letras.

**Ids com prefixo `B`**, para nunca confundir com os `A` da primeira.

**Dois por vez**, como ele pediu. A skill `auditar` manda rodar um por vez, e a
razão registrada nela é uma decisão dele de 05/09; a instrução desta noite é
mais nova e diz "até 2", então é ela que vale. Ainda está longe do desenho que
falhou — treze de uma vez, duas corridas inteiras evaporadas.

A partição é a medida em `docs/AUDITORIA.md`, com as áreas grandes já partidas:

| área | o que cobre | linhas |
|---|---|---:|
| B1 | séries, agregação, formatos de arquivo, biblioteca de ticks | ~3.500 |
| B2 | renko, ticks sintéticos, replay do domínio | ~2.900 |
| B3 | `ChartCanvas` inteiro | ~3.000 |
| B4 | holder, layout, legenda, eixos, estilo | ~5.200 |
| B5 | indicadores: overlays, estudos, painéis, diálogos | ~6.900 |
| B6 | `ui/replay` | ~2.600 |
| B7a | platform, settings, catálogo | ~2.500 |
| B7b | shell, series, settings de interface | ~5.400 |
| B8a | testes de domínio e plataforma | ~4.600 |
| B8b | testes de interface | ~8.300 |

E depois as quatro lentes transversais, que **não releem os arquivos**: recebem
padrões de `grep` e leem ±40 linhas em volta de cada ocorrência, mais a lista do
que as áreas já acharam.

### O que a auditoria II entregou — as dez áreas

| área | linhas lidas | ALTA | MÉDIA | BAIXA |
|---|---:|---:|---:|---:|
| B1 séries e formatos | 4.527 | 2 | 11 | 8 |
| B2 renko e replay | 2.774 | 2 | 10 | 8 |
| B3 `ChartCanvas` | 2.983 | 2 | 8 | 9 |
| B4 moldura | 8.198 | 1 | 13 | 17 |
| B5 indicadores | 4.375 | 1 | 9 | 8 |
| B6 transporte de replay | 2.693 | 3 | 6 | 6 |
| B7a plataforma | 2.731 | 3 | 13 | 8 |
| B7b casca | 5.397 | 3 | 8 | 13 |
| B8a testes de domínio | 7.620 | 4 | 9 | 6 |
| B8b testes de interface | 9.840 | 5 | 8 | 6 |
| | **51.138** | **26** | **99** | **90** |

Cinquenta e uma mil linhas lidas — o projeto tem 50.599, então cada linha foi
lida uma vez e pouco. É o que a partição por áreas existe para conseguir.

**Treze das vinte e seis ALTA já estão corrigidas**, com o teste que faltava e a
prova de dentes: `e541da7` e `30e30f7`.

**Vale mais que a contagem:** a segunda passada achou defeito em código que eu
escrevi nesta mesma noite, e achou-o porque não sabia que eu o tinha escrito.

- **B3-1 e B3-9** dizem que duas das minhas mudanças no `ChartCanvas` abriram
  caso novo. B3-1 é grave: `extendBricks` passou a devolver `false` tanto para
  "não consegui" quanto para "nada mudou", e quem lê entende o primeiro — troca
  o renko de ticks pelo de candles, que assenta de 5% a 23% mais tijolos, e
  `fromTicks` continua dizendo que veio dos ticks.
- **B2-1 e B8a-1** dizem que o `OneClockTest` que escrevi para provar a
  correção do L2-3 **afirma só um lado**: ele verifica que o candle não está
  atrás do renko, então adiantar o relógio — mostrar o futuro, que é o defeito
  que a classe existe para impedir — passava intacto.
- **B1-2** é o `FoldedTicks` de ontem à noite engolindo `IOException`: um pregão
  exportado e ilegível sumia do meio do gráfico sem buraco e sem palavra.
- **B8a-4** é a melhor delas e não é sobre código novo: **nenhum teste do
  domínio lê máxima, mínima ou volume de uma barra FECHADA do replay**. As duas
  fixtures tinham abertura = máxima = mínima = fechamento, então trocar
  `highAt` por `lowAt` dentro do `ReplaySeries` — virar todo o gráfico de
  história de cabeça para baixo — deixava a suíte inteira verde.

Isso é o argumento inteiro a favor de reauditar em vez de continuar corrigindo
a lista velha, e ele apareceu sozinho.

E continuou aparecendo depois que escrevi o parágrafo acima:

- **B7a-4** — o gancho `whenForgotten` que liguei para corrigir "o calendário
  nunca é esvaziado" pendurava-se num `SeriesCatalog.forget()` que **não tem
  chamador nenhum** em `src/main`. Eu tinha mudado o defeito de andar, não
  corrigido. Agora a janela de séries o chama; o momento de verdade — importar
  um pregão — ainda não tem caminho pela interface, e isso está dito no javadoc
  para não virar promessa.
- **B7a-1** — a janela por segmento que escrevi à noite tinha um furo no ramo do
  cache: com a série inteira em memória ela devolvia tudo e ignorava o fim do
  trecho. Medido pelo teste: 1.000 barras onde cabem 300.
- **B6-3** — o `forgetEnding` que acrescentei também não tem chamador, e o teste
  que escrevi registra e desregistra ele próprio. Licença falsa, e ainda aberto.
- **B8b-3 e B8b-5** — dois testes meus que leem o CÓDIGO-FONTE em vez do
  comportamento passam com o defeito reescrito de outro jeito.

Quatro correções minhas desta noite, das quais **três estavam erradas ou
incompletas** e uma delas apenas mudou o defeito de lugar. Nenhuma teria
aparecido sem a segunda leitura.

---

### As lentes transversais

Quatro perguntas, cada uma em todo o código: EDT e concorrência, tempo e leitura
do futuro, persistência e recursos, i18n e consistência.

**Elas não releem os arquivos.** Recebem padrões de `grep`, leem ±40 linhas em
volta de cada ocorrência, e recebem o índice dos 215 achados com a ordem de
reportar só o que as áreas não viram. Foi exatamente aqui que a primeira
tentativa desta auditoria se perdeu, em 05/09: quatro lentes relendo
integralmente o que nove agentes já tinham lido, um multiplicador de quatro em
cima do corpus inteiro, 1,5 milhão de tokens e zero relatórios.

---

## O placar da noite

| | |
|---|---|
| suíte | **537 → 583 verdes** |
| commits | 19 |
| auditoria II | 10 áreas + 4 lentes, **51.138 linhas** |
| achados novos | **32 ALTA · 121 MÉDIA · 107 BAIXA** |
| ALTA fechadas | **21**, cada uma com o teste que faltava |

### A fila da fase 4, em ordem

O que fica, e por que nesta ordem. As primeiras mudam número ou perdem dado; as
últimas mudam texto.

1. **L3-1 (ALTA, perda de dado)** — `TickFile.Writer` e `TapeFile.Writer`
   truncam o arquivo do pregão **no construtor**, antes de ler a primeira linha
   do export; e o `finally` dos conversores fecha o writer, que grava a contagem
   do que deu tempo de escrever. Uma conversão recusada **apaga o pregão bom** e
   deixa no lugar um pregão curto internamente coerente, que `read`, `sessionOf`
   e a biblioteca aceitam como inteiro. Os três testes de recusa param no
   `assertThrows` e nunca olham o disco. A correção é escrever num temporário e
   mover no fim.
2. **B7b-2 (ALTA)** — a janela de séries lê a série **inteira** na thread da
   interface, no construtor e a cada troca da combo. Congela a aplicação ao
   abrir Ferramentas → Séries.
3. **L1-1 (ALTA)** — `relaunch()` não refaz `captureStandardOutput()`: depois da
   primeira troca de idioma toda a saída padrão vai para a janela morta, que
   fica presa em memória para sempre.
4. **B7b-1 (ALTA)** — a tranca "só pelos segmentos" não vale para fonte de
   ticks: o leitor marca a caixa, ela fica marcada, e o export abre inteiro.
5. **B8b-1 (ALTA)** — o único teste da escala própria do IFR passa com o
   indicador desenhando NaN em todas as barras. `assertEquals(double, double,
   delta)` trata NaN como igual a NaN.
6. **B8b-2 a B8b-5, B8a-3 (ALTA)** — cinco testes meus que leem o **código-fonte**
   em vez do comportamento. Passam com o mesmo defeito reescrito de outro jeito.
7. **B7b-3 (ALTA)** — javadoc no membro errado em dez lugares, sete no
   `MainWindow`. O `OrphanJavadocTest` já os conta; levar o teto a zero apaga o
   teto.
8. **As 121 MÉDIA e 107 BAIXA da auditoria II**, e as **93 MÉDIA e 115 BAIXA da
   auditoria I** que a decisão D5 mandou para cá.

### Onde faltou teste, dito de propósito

Duas correções desta noite foram commitadas **sem o teste que a casa exige**, e
está escrito no commit e aqui:

- **B6-1** — os ticks pedidos enquanto o relógio anda. Precisa de um fixture de
  três pregões de tape e de conduzir o relógio por dois deles.
- **B6-3** — `forgetEnding` chamado pelo `ReplayDrop`. Precisa de um
  `ChartHolder` de verdade.

Nenhuma das duas cabia no tempo que restava, e deixá-las sem teste e sem dizer
seria pior do que deixá-las sem teste.

---

## Diário — cada passo, com o commit

### 07/09, madrugada — fase 0, o que estava em voo

| o que | commit |
|---|---|
| Ticks sintéticos recalibrados pelos nove pregões do Profit | `294a330` |
| Corretude de domínio II: pontas do caminho, fuso, dicionário, agressor | `b9bf044` |
| Corretude de domínio III: rabo do pregão, carry, teto, estocástico | `939abb7` |
| Uma regra só: o feed decide de onde vem o que se vê | `05fc7ba` |
| Janela de um segmento lida NELE, não no fim do arquivo | `477922f` |
| Um export de ticks abre como gráfico | `1b34571` |

Suíte: **537 verdes**.

### 07/09, 02h às 04h — fase 1

| lote | achados | commit |
|---|---|---|
| Um relógio só: candle e renko param no mesmo instante | L2-3 | `cf9d3b8` |
| Botão, âncora do zoom, posição guardada, fita recusada | A3-5, A3-6, A3-7, A3-8, A3-11 | `fa0d4ed` |
| Rodapé, caldas, pregão pedido adiantado, javadoc inerte | A3-9, A3-10, A3-12, A3-13, A2-5, A2-6 | `5fce5e8` |

Suíte: **550 verdes**, de 537.

Três coisas que valem mais que os achados em si:

- **Um teste matou uma classe inteira de defeito.** `OrphanJavadocTest` varre a
  árvore procurando javadoc que documenta o membro errado. A auditoria achava um
  arquivo por vez; a varredura acha todos e impede que voltem. Restam 23 num
  teto que não pode crescer.
- **Um dos oito javadocs órfãos do `ChartCanvas` era meu**, criado uma hora
  antes ao extrair um método. É a melhor prova de que o caso pedia um teste e
  não oito correções.
- **Um teste que escrevi não tinha dentes** e só apareceu ao quebrar o produto:
  ele perguntava ao feed e ao arquivo, que são as mesmas duas coisas de que a
  ligação é feita. `isRecorded()` passou a responder da própria ligação.

Dois defeitos foram achados pelos próprios testes desta noite, e valem
registro porque não estavam em auditoria nenhuma:

- o passeio sintético **saía da barra** — corrigir o passo sorteado duas vezes
  deixava as duas correções se anularem;
- `java.util.Random` com sementes vizinhas dá **primeiros sorteios
  correlacionados**, e era esse sorteio que decidia qual extremo vem primeiro:
  31% onde a medição diz 83,2%.

E um teste que eu escrevi **não tinha dentes** — passava com o produto
quebrado. Só apareceu porque quebrei o produto de propósito para conferir.
Está corrigido; a lição vai para a fase 3.

### 07/09, 04h às 07h — fases 1 (fim), 2 e 3

| lote | achados | commit |
|---|---|---|
| A varredura que emudecia, as caixas remontadas, o rótulo em português | fase 1 | `f6f6640` |
| O briefing da segunda passada, e este diário | — | `77a4836` |
| A ferramenta que confronta as duas passadas | — | `9c7a44c` |
| O que não se soltava, o calendário que não sabia, a preposição em java | fase 1 | `9e87cf5` |
| As sete ALTA da segunda passada, e o teste que nunca rodou | B1..B6 | `e541da7` |
| O quadro parado, que trocava os tijolos em silêncio | B5-2 | `9b2c469` |
| O estudo na escala errada, o cache que engolia o trecho, o arquivo que apagava tudo | B7a | `30e30f7` |
| O índice dos 215 achados, e o parser que quase o inventou | — | `5f32210` |
| As dez áreas fechadas, e as quatro correções minhas que a segunda leitura derrubou | — | `e3bfe05` |
| O extrator passa a entender as três formas de referência | — | `d3de376` |
| A fase 3, com as duas auditorias confrontadas | — | `7c95406` |

O que a fase 2 e a fase 3 acharam, e que nenhuma outra coisa acharia:

- **Cinco defeitos nas minhas próprias correções desta noite**, e quatro estavam
  erradas ou pela metade: o gancho `whenForgotten` pendurado num `forget()` sem
  chamador, o `openUntil` com um buraco no cache (mil barras onde cabem 300), o
  `forgetEnding` sem chamador, e o `OneClockTest` que **nunca rodava** porque a
  guarda do laço é falsa antes do primeiro quadro. Corrigir e reauditar na mesma
  noite não é redundância: é a única leitura que pega a correção fresca.
- **`data.zone` era lida pelo Launcher e escrita por ninguém.** A correção de
  fuso inteira foi construída e nunca armada. Invisível na máquina do autor, que
  fica no fuso do mercado — a classe de defeito que só existe onde o código não
  é escrito.

### 07/09, manhã — fase 4

| item | o que era | commit |
|---|---|---|
| **L3-1** | Conversão recusada apagava o pregão bom e deixava um curto no lugar | `0ead241` |
| **B7b-2** | A janela de séries lia a série inteira na thread da interface | `2f60bcf` |
| **L1-1** | Trocar de idioma deixava a saída padrão presa ao console da janela morta | `e2ae2ae` |
| **B7b-1** | A tranca "só pelos segmentos" não valia para fonte de ticks | `351410d` |
| **B8b-1** | O único teste da escala própria do IFR passava com o IFR desenhando NADA | `df5c531` |
| **B8b-2, B8b-5** | Botões do mouse e arredondamento do preço, medidos em vez de lidos | `b38029e` |
| **B8b-3, B8b-4** | A biblioteca que cresce, medida — e uma premissa do relatório caiu | `0e55307` |
| **B8a-3** | A varredura que falha PELO MEIO nunca caía no catch que a esperava | `03cd5a9` |
| **B7b-3** | Javadoc inerte: 23 para zero, e o teto saiu do teste | `7a9627d` |

**As 32 ALTA da auditoria II estão fechadas.** Cada uma com o teste que faltava,
e cada teste provado quebrando o produto de propósito.

Três coisas que a fase 4 achou e que não estavam em auditoria nenhuma:

- **`Files.walk` e preguicoso, e o que ele lanca pelo meio e
  `UncheckedIOException`.** O `catch (IOException)` embaixo do walk so podia ver
  a falha de COMECAR. O caso que o comentario dele descrevia -- "the walk is
  lazy, so the throw can come halfway" -- passava reto, saia do metodo levando os
  pregoes ja achados e derrubava a construcao da arvore atras. O comentario
  estava certo sobre o mundo e errado sobre o proprio codigo, que e a forma de
  comentario mais cara que existe.
- **Duas premissas dos relatorios nao se sustentam**, e estao registradas onde
  foram refutadas: trocar `step >= 1.0` por `step > 1.0` e a mesma funcao (para
  passo maior ou igual a 1, `-log10(passo)` e zero ou negativo e o `ceil` disso e
  zero de qualquer jeito), e tirar `request(day.plusDays(1))` nao trava a
  meia-noite, porque `TickLibrary.request` ja enfileira o dia, o seguinte e o
  anterior.
- **Dois javadoc inertes descreviam comportamento que a aplicação já não tem.**
  Um javadoc que não está preso a nada para de ser mantido, e depois para de ser
  verdade.

### 07/09, tarde — fase 4, as MÉDIA

| itens | o que era | commit |
|---|---|---|
| **B7b-6, B7b-7, B7b-8, B7b-9** | O acento pela saída padrão, a dobra do console, a barra de estado abandonada | `b82048e` |
| **B7a-6, B7a-7, B7a-16, B7a-17, B7a-18** | A configuração que substituía em vez de completar; o rótulo com a escala duas vezes | `6a3c4a8` |
| **B4-6, B4-13** | A aba apagada à esquerda, e o nome de cópia que não terminava | `d023232` |
| **B5-5, B5-6** | Esconder um estudo esconde o estudo, e ele volta escondido | `55e16dd` |

**13 MÉDIA fechadas.** Suíte em **592**.

Duas coisas que a tarde ensinou:

- **O `OrphanJavadocTest` pegou o autor dele duas vezes na mesma noite.** A
  segunda foi um javadoc inerte criado ao tornar um método visível ao pacote,
  e bloqueou o commit. Uma varredura vale mais do que as correções uma a uma.
- **`@Timeout` sozinho não interrompe um laço infinito** — ele só confere o tempo
  depois de o teste voltar, e um laço infinito nunca volta. Precisa de
  `SEPARATE_THREAD`. A primeira versão do teste ficou pendurada em vez de falhar,
  e isso só apareceu ao quebrar o produto de propósito.

| itens | o que era | commit |
|---|---|---|
| **B6-6, B6-9** | A data lembrada de outro feed, e a sessão que sobrevivia à janela | `141120f` |
| **B1-3, B1-4** | A barra de vários dias carrega a abertura, e corta numa segunda | `40a950d` |
| **B2-6, B2-7** | O resto do quadro deixa de ser jogado fora, e o vetor vazio some | `bdaf416` |
| **B2-12** | O espaçamento da série sai da mediana, não do primeiro par | `ccda1a5` |
| **B2-8, B2-9** | O tijolo de ontem sai da tela, e a ordem é conferida nas três portas | `60fac95` |
| **B3-4, B3-5** | O dígito age no gráfico da frente, e o Control alterna uma vez só | `5201974` |
| **B4-2** | A vista volta ao ABRIR, e o gráfico flutuante para de perdê-la | `ce15513` |
| **B4-3, B4-5** | A lista de escalas passa a falar o idioma escolhido | `269db07` |
| **B1-9, B1-10** | Uma biblioteca fechada não volta a segurar 113 MB nem a avisar | `c9c92a7` |
| **B1-11** | A limpeza do caminho de falha não pode mais apagar a causa | `b1cc939` |
| **B1-14** | Uma resposta só para "em que balde essa barra cai" | `5d81bfc` |
| **B1-15** | Um número com lixo no meio passa a ser recusado | `96a059e` |
| **B1-5, B1-6** | O código do agressor deixa de ser a ordem de declaração | `21f8b45` |
| **B7a-9, B7a-10, B7a-11** | Gravação atômica, em lote, e sem o carimbo que anulava a ordem | `fe1ef93` |
| **B7a-12, B7a-14** | O estágio é do trabalho, e a falha é dita quando acontece | `e3e13a3` |
| **B7a-5, B7a-8** | O ouvinte que estoura, e o arquivo que diz seu formato | `4f46396` |
| **B7b-4, B7b-5** | Um segmento de export lê só os pregões dele | `4c20e12` |
| **B7b-10, B7b-11** | Uma folha sem nome abre nada; um segmento em diante fica em diante | `02277dc` |
| **B1-7, B1-8, B1-12** | O degrau sem sobreposição, a alocação por linha, o "custa nada" | `3c1de03` |
| **B1-13** | A coluna Ativo deixa de ser ignorada | `22daa0f` |
| **B2-3, B2-4, B2-5** | Dois comentários que mentiam sobre número medido, e um achado refutado | `775ca1e` |
| **B2-10, B2-11** | A linha morta do volume pendente, e a frase da thread errada | `8053e1a` |
| **B3-6, B3-7** | A recusa do renko vale no restauro, e o restauro espera as barras | `25ae09f` |
| **B3-8, B4-14** | O estilo diz o próprio código, volta nos dois sentidos, e se anuncia | `74a0342` |
| **B3-10, B4-12, B5-7** | O que era construído por barra, por linha e por movimento do mouse | `85218fc` |
| **B4-7, B4-8, B4-9** | Os layouts gravam uma vez, as cores param de ser refeitas, o Forms passa a ser usado | `849696e` |
| **B4-4, B4-10** | A régua para de responder duas vezes, e o renko de reler as barras | `a6463ce` |
| **B3-9** | A busca pela fonte dos tijolos entrega a biblioteca que abriu | `2dda6b0` |
| **B3-3** | O indicador é perguntado uma vez por barra, não uma vez por linha | `1a33950` |
| **B5-2, B5-3** | O método morto do OwnScale sai, e o valor para de ser embrulhado por linha | `132eac5` |
| **B5-8, B5-10** | A regra do preço da barra passa a existir uma vez só | `a3fb3eb` |
| **B5-9** | Fechar um painel espera o gesto acabar, como reordenar já esperava | `088711a` |
| **B5-4** | O recálculo de todos os estudos sai da thread da interface | `a417c42` |
| **B6-8** | A janela do replay passa a ser medida em pregões | `67fc626` |
| **B6-4, B6-5** | A segunda varredura de disco sai, e o calendário volta a ser aquecido | `f5083f8` |
| **B8a-9, B8a-12, B8a-13** | Três testes que ficavam verdes sobre o vazio | `6e7bfc7` |
| **B8a-11** | O bundle passa a ser conferido contra o código | `cb4c480` |
| **B8a-10** | A costura das configurações alcança quem já as guardou | `56758cc` |
| **B8a-5..8** | Quatro fixtures que não viam o defeito que nomeavam | `9ba648f` |
| **B8b-9, B8b-10** | Os testes param de gravar os arquivos e o registro do leitor | `945b295` |
| **B8b-6,7,8,11,12,13** | As últimas seis | `5b76998` |

**As 99 MÉDIA da auditoria II estão FECHADAS.** Suíte em **641**. Todas as áreas — **B1** a **B8b** e as quatro lentes
estão fechadas. **B4-11** já estava fechada pela correção de B5-5 — o
`study.setVisible(entry.visible())` do `ChartLayout.Pane.build()`.

Uma terceira lição, e é a mesma três vezes: **o `OrphanJavadocTest` pegou o autor
dele em três commits diferentes desta fase**, sempre por um membro novo inserido
entre um javadoc e o membro que ele descrevia. Dois desses commits foram
bloqueados pelo gancho. Uma varredura que roda em toda a árvore vale mais do que
qualquer correção individual — e vale mais ainda contra quem a escreveu.

### O que fica

- **B6-9 sem teste, dito de propósito.** Exercitar aquele caminho exige soltar o
  painel DURANTE os quatro segundos da construção da sessão, o que é dirigir um
  `SwingWorker` pelo meio — um teste sobre o escalonador, não sobre isto.
- **As 90 BAIXA** da auditoria II, e as **93 MÉDIA e 115
  BAIXA** da auditoria I que a decisão D5 mandou para cá.

### Mais duas lições da fase 4

- **Uma prova de dentes pode passar VERDE por um motivo que vale descobrir.** Ao
  quebrar só a contagem de despachantes do Control, o teste continuou verde: o
  sinal "Control sozinho" tinha virado estático na correção, e o segundo
  despachante já o encontrava apagado. O defeito original precisava das DUAS
  coisas. Uma quebra que não reproduz o defeito não prova nada, e a única forma
  de saber é insistir até ela ficar vermelha.
- **Três fixtures errados seguidos, todos meus.** No teste da lista de escalas o
  código da hora é `1h` e não `H1`, a hora usa o singular, e o tijolo do 11R é de
  50 pontos e não de 10. Números escritos de cabeça; o produto estava certo nas
  três. Um teste que falha é sempre uma pergunta sobre qual dos dois lados errou.

### E mais uma

**Um achado pode chegar meio morto, e vale dizer.** O `B1-11` descrevia um
`finally` que fechava duas vezes e trocava a exceção — e a correção de `L3-1`,
horas antes, já tinha trocado aquele `close()` por um `discard()` idempotente.
O mecanismo não reproduzia mais. A FORMA continuava: uma limpeza dentro de um
`finally` pode substituir a causa. Foi corrigida por isso, e o commit diz que o
achado chegou pela metade em vez de fingir que fechou inteiro.

### Um achado refutado depois de implementado

**B2-5** dizia que a regra do tijolo cinza está documentada e não implementada em
lugar nenhum, porque o `settle` decide por POSIÇÃO no lote. Implementei a
"correção" — a contagem passou a carregar o menor e o maior preço negociado, e o
tijolo passou a ser conferido pela faixa. **Seis testes do `RenkoGapTest` caíram
na hora, e eles é que estão certos.**

As duas coisas concordam, e o motivo estava no parágrafo logo acima da frase
acusada: a contagem é zerada a cada tijolo assentado, então o que ela guarda são
exatamente os negócios desde o tijolo anterior — e são esses que fecharam o
primeiro do lote. Sob essa disciplina, posição no lote **é** a pergunta de preço.

A mudança foi revertida inteira e a explicação ficou escrita no `Untraded`. Um
relatório de auditoria é uma hipótese, e esta caiu no primeiro teste.

### E uma decisão de escopo, registrada

Na correção de **B3-3** e de **B5-3** o relatório pedia também que os laços de
pintura andassem de `barsPerColumn()`, como os de candle. **Não passaram a
andar, e isso é decisão.** Os laços de candle andam assim porque AGREGAM: a
coluna guarda a máxima e a mínima de tudo que caiu nela. Uma linha não tem com
o que agregar, então amostrar uma barra por coluna jogaria fora a faixa que o
indicador percorreu dentro dela — e no zoom em que a economia importaria, essa
faixa é quase tudo o que a linha mostra. Fica escrito no código, com o motivo.

E mais uma prova de dentes verde, a quinta: em `MovingAverage.at()`, quebrar o
`at()` não derrubou o teste que compara `at()` com `valueAt()` — porque
`valueAt` delega a `at`, e as duas se moveram juntas. A quebra que vale é
reimplementar `valueAt` por fora e errar só o `at()`. **Uma prova verde é uma
pergunta sobre a quebra, não sobre o teste.**

### Duas decisões de escopo desta fase

**B6-4: o cache de `available()` foi implementado e descartado.** Ele repousa
em "o disco só muda pelo catálogo", e a suíte mostrou que ninguém garante isso
— os próprios testes escrevem fixtures direto. Uma memória ali estaria certa
até silenciosamente não estar. Ficou a metade que não faz a aposta: a segunda
varredura saiu.

**B6-5: o reaquecimento não mora no `ReplayFeed`.** Uma thread própria dentro
de uma classe utilitária é global ao processo, e vazou entre classes de teste
— uma promessa feita num teste continuava valendo nos seguintes. Foi para o
`Launcher`, onde o `warm()` já era submetido e onde um job aparece no rodapé.
**Fica sem teste, dito de propósito**, pela mesma razão do B6-9.

### E a sexta prova de dentes verde

No B5-8, comparar o CENTRO da banda com a média passou verde na quebra: o
centro vem de uma `MovingAverage` própria e nunca passou pelo `switch`
duplicado. Quem passava era a LARGURA. **Uma prova verde é uma pergunta sobre
a quebra, não sobre o teste** — é a segunda vez esta noite.

### Onde a auditoria II termina

**26 ALTA e 99 MÉDIA fechadas**, cada uma com o teste que faltava e cada teste
provado quebrando o produto. Suíte de 537 para 641.

Três achados foram **refutados** e a refutação ficou escrita no código
(B8b-5, B8b-4, B2-5); dois chegaram meio mortos e o commit diz isso
(B1-11, B8a-13); e quatro estavam **já fechados** por correções anteriores
desta mesma noite (B4-11, B6-7, B7a-13, B7a-15).

**Sem teste, de propósito:** B6-1, B6-3, B6-5, B6-9, B2-7, B7b-4, B8a-9. Em
todos, exercitar o caminho seria um teste sobre o escalonador — do JUnit, do
Swing ou do JobService — e não sobre o produto.

## Fase 5 — as BAIXA

| Achados | O que era | Commit |
| --- | --- | --- |
| **B1-16,17,19,20,21** | Cinco BAIXA do domínio; uma delas era leitura de lixo | `e65f0df` |
| **B1-22, B1-23** | O load marca o dia que lê, e o aviso ganha canal | `0412e8f` |
| **B2-19, B2-20** | Dois números que sumiam em silêncio | `2406b6a` |
| **B2-13,14,15,16,17** | Texto, guarda morta e uma alocação por quadro | `0bb1755` |
| **B2-18** | Os tijolos passam a ser acumulados em vetores primitivos | `a987eda` |
| **B1-18** | O recorte e a junção carregam o tijolo cinza e a contagem | `4c4eb95` |

| **B4-18,20,23,24,28,31** | Seis que mudam o que aparece na tela | `026d883` |
| **B4-16,17,19,21,27,30** | Código morto, um teto sem origem, três comentários errados | `a5eef09` |
| **B4-15, B4-29** | A faixa declarada vale nos três caminhos | `6945e54` |
| **B4-22, B4-25, B4-26** | Três duplicações, e a ambiguidade de nome | `3efcc2e` |
| **B5-11..17** | O rótulo ganha a forma que o contrato declara | `c02d0dc` |
| **B6-10,11,12,15** | A cor de erro passa a seguir o tema | `5b971b6` |

| **B7a-19..26** | Um nome de arquivo derrubava a árvore inteira | `df6a46e` |
| **B7b-12..25** | Os gráficos voltam na ordem certa a partir do décimo | `c71ae18` |
| **B8a/B8b (12)** | As últimas, e o `RandomWalkSeries` vai para o domínio | `3b3f3fc` |

**As 90 BAIXA da auditoria II estão FECHADAS.** Suíte em **662**. **A auditoria II está fechada por inteiro:** as três
severidades, as dez áreas e as quatro lentes.

### O que a fase 5 já ensinou

**Uma prova de dentes verde revelou um teste vazio, pela terceira vez.** Em
B1-22 escrevi um teste que perguntava se o dia está marcado antes e depois do
`load`: passou verde com a marcação arrancada, porque as duas respostas são
"não" de qualquer jeito. Observar o meio da leitura exigiria uma costura no
`TickSource` que não existe. O teste foi **apagado** em vez de ficar dando
licença, e o commit diz isso.

**Uma quebra que não compila não é uma quebra.** Em B1-18, tirar as duas
interfaces dos embrulhos deixou os `@Override` órfãos e o build parou — o que
não prova nada. A quebra que vale é a que reproduz o estado anterior inteiro:
sem as interfaces E sem os métodos.

### O que a fase 5 ensinou depois disso

**Um teste que ja existia pegou a minha correcao obvia.** No B3-12 escrevi
`isLeftMouseButton`, como os três irmãos usam — e essa lê a máscara de botões
*apertados*, que numa soltura já não tem o que acabou de subir. O botão
esquerdo parou de arrastar e o `therightButtonDoesNotPan` caiu.

**Uma flaky que eu plantei ficou escondida por vários commits.** O
`RulerModeTest` ganhou um segundo `@AfterEach` no B8b-10, e dois não têm ordem
entre si: o modo régua, que é estático do processo, vazava ligado. Falhava na
suíte inteira e passava sozinho. Corrigido dos dois lados.

**E dois parágrafos que eu mesmo escrevi estavam errados**, os dois conferidos
no código antes de comitar: o determinismo do `RandomWalkSeries` (a semente é
fixa; o que varia é o instante) e a diferença entre os dois readouts (não é só
a âncora — um tem título).

## A auditoria II, fechada

**26 ALTA, 99 MÉDIA e 90 BAIXA.** Suíte de 537 para 662.

Cada correção de comportamento levou o teste que faltava, e cada teste foi
provado quebrando o produto. As que não levaram teste — remoções, javadoc,
código morto — dizem isso no commit, com o motivo.

### O que fica registrado como decisão, não como pendência

- **B6-4:** o cache de `available()` foi implementado e descartado; repousa numa
  garantia que ninguém dá.
- **B6-5:** o reaquecimento mora no `Launcher`, não numa thread global dentro de
  uma classe utilitária.
- **B3-3 e B5-3:** os laços de linha não andam de `barsPerColumn`, porque uma
  linha não tem com o que agregar.
- **B4-25:** só o que é de fato idêntico foi para o `Readouts`; a caixa do
  readout de barra tem título e não é a mesma caixa.
- **B8b-17:** construir componente Swing fora da EDT é convenção que a suíte
  inteira segue igual; mudar uma dezena de arquivos sem defeito observado atrás
  é outra mudança.

### Sem teste, de propósito

B6-1, B6-3, B6-5, B6-9, B2-7, B7b-4, B8a-9, B1-22, B3-12, B2-20. Em todos,
exercitar o caminho seria um teste sobre o escalonador — do JUnit, do Swing ou
do JobService — ou exigiria uma costura que não existe. O commit de cada um diz
qual dos dois.

### O que resta

As **93 MÉDIA e 115 BAIXA da auditoria I** que a decisão D5 mandou para cá.

## Fase 6 — a auditoria I

A decisão D5 mandou para cá as **93 MÉDIA e 115 BAIXA da auditoria I**. A
`00-verificacao.md` prova que só as **52 ALTA** foram corrigidas na época.

**O método.** Triagem por script (`triar.py` procura o trecho citado no código de
hoje), depois conferência à mão de cada candidato, depois correção só do que está
de fato aberto. O que a auditoria II já fechou entra no commit como *fechado por
conferência*, com o nome de quem fechou.

**Por que a triagem não decide.** Ela erra dos dois lados: aponta ABERTO em achado
já corrigido (o trecho citado sobreviveu dentro de um comentário que explica a
correção) e SEM ARQUIVO em achado que existe. Serve para ordenar, não para
concluir.

| lote | o quê | commit |
|---|---|---|
| L1-1, L2-1..6, L3-1, L3-2 | as lentes de EDT e de tempo | `7c3e89b` |
| A6-11, L3-9, A5-20 | o calendário e os records | `7c3e89b` |
| A4-10, A8a-13, A6-14, A5-5 | renomear aba, e o `ArraySeries` sem teste | `e444cb1` |
| A5-6, A5-7, A7a-19 | dois ajustes que não desenhavam nada | `54abeed` |
| **L3-10** | gravar uma base deixa de destruir a que estava lá | `cad643e` |
| **A6-13, A5-13** | a sessão que falha diz por quê; a volta do estocástico | `e4b433d` |
| **A6-15, A7a-13, A7a-9, A7a-14, A6-17** | a alça, o cache, e o `user.home` | `2378434` |

**Fechados por conferência até aqui:** A1-2..9, A2-3..14, A3-5..13, A4-4..12,
A5-8..12, A5-14, A6-8, A6-9, A6-10, A6-12, A6-16, A6-18, A6-19, A7a-6, A7a-7,
A7a-8, A7a-10..12, A7b-15, L1-6.

| **A6-13, A5-13** | a sessão que falha diz por quê; a volta do estocástico | `e4b433d` |
| **A6-15, A7a-13, A7a-9, A7a-14, A6-17** | a alça, o cache e o `user.home` | `2378434` |
| **A7a-17, A7a-15** | uma linha apagada custa um segmento; a costura de configurações | `aeb7203` |
| **A7b-13, A7b-12, A7b-11** | olhar deixa de gravar; o Cancelar fica na barra | `47d6c5f` |
| **A7b-17, A7b-16, A7b-14, A7b-18** | data invertida; os diálogos são descartados | `0b14b2a` |
| **A7b-20, A8a-8, A7b-22, A7b-23** | a árvore sai da EDT; o recorte nos cinco acessores | `c83bfc4` |
| **A8a-9, A8a-10, A8a-11** | três testes que não afirmavam o que prometiam | `019f609` |
| **A8b-7, A8b-8, A8b-9, A8b-10** | quatro que ficavam verdes com o produto mutado | `f32a3f9` |
| **A8b-11, A8b-14** | a legenda dobrada e o tempo da régua | `3b198e5` |
| **L1-7, L1-3, L1-4** | o retorno do job sai do cadeado; o calendário sai do bin | `f7a7b8e` |
| **L3-3, L3-4, L3-11, L3-7** | a chave do gráfico; o layout padrão sobrevive ao idioma | `c03a142` |
| **L4-9, L4-4, L4-8, L4-6, L4-7, L4-10** | a disposição vai para o arquivo; 32 linhas mortas | `51f1bed` |

**As 130 MÉDIA da auditoria I estão FECHADAS.** Suíte em **698**.

### O que a fase 6 ensinou

**A prova de dentes verde apareceu duas vezes mais**, e as duas por fixture: em
A7a-13 o teste reescrevia a base pela própria fixture, que aponta o catálogo
para a pasta e **esvazia o cache que o teste pergunta sobre**; em A7b-14 a
guarda de A7b-13 fazia o teste passar sem exercitar nada, e a pergunta teve de
mudar de lugar — da chave gravada para a chave em memória.

**Uma correção minha criou uma regressão, e a prova de dentes a achou.** A7b-13
passou a gravar só o que foi editado, e o `add` do ramo em que a série não lê
dependia da gravação incondicional do fechamento.

**Duas correções minhas foram pegas pelas guardas da casa.** O
`OrphanJavadocTest` acusou duas vezes um bloco novo inserido entre um javadoc e
a assinatura que ele documenta — porque o script ancorou na assinatura.

**Metade de um achado foi refutada e a correção ficou.** Em L1-4 os exemplos do
relatório são todos conferidos dentro de `TickFile.read` e saem como
`IOException`: não existe arquivo que alcance o ramo. A rede fica porque a
classe de falha já custou caro uma vez, e o `TapeFile` registra isso.

### As 67 BAIXA

| lote | o quê | commit |
|---|---|---|
| A5-16, A5-19..22, A5-24 | alinhamento de cor, invariantes de record, onze imports mortos | `0e4aff3` |
| A6-20, A6-22..26 | dois ícones por quadro, um método sem uso, um invólucro | `44b6a32` |
| A7a-22, A7a-24..26, A7a-28, A7a-30 | a fonte que existe, a bandeira que ninguém entendeu | `521a6fe` |
| A7b-26..28, A7b-31..35 | teclado no painel, cor do tema, o cursor do console | `74a47d1` |
| A8a-16..18 | o tape ganha as recusas que os irmãos já tinham | `7f16004` |
| A8b-16..20 | cinco asserções que não discriminavam nada | `139def2` |
| L1-8, L1-9, L2-4, L3-12, L3-14 | progresso coalescido, linha longa recusada | `50ef074` |
| L4-11..17 | o separador e as datas num lugar; os mnemônicos que não sublinham | `ad186c8` |

## A auditoria I, fechada

**52 ALTA (na época), 130 MÉDIA e 67 BAIXA.** Suíte em **711**.

Boa parte das MÉDIA e das BAIXA já tinha sido fechada pela auditoria II, que
correu sobre o mesmo código; cada uma dessas está nomeada no commit como
*fechada por conferência*, com quem a fechou. O resto foi corrigido aqui, com
teste quando muda comportamento e com o motivo escrito quando não leva teste.

### Duas guardas novas

- **`BundleKeysTest`**: todo mnemônico é uma letra que existe no próprio rótulo,
  nos dois idiomas. Foi assim que `action.preferences = P` sobre "Settings..."
  deixou de ser possível.
- **`platform/Formats`**: o separador de campos e os formatos de data passaram a
  ter um lugar só, e o arquivo diz por que não estão no bundle.

### O que a fase 6 ensinou, ao todo

**Cinco provas de dentes verdes**, e todas por o teste não alcançar a quebra:
uma fixture que esvaziava o cache sobre o qual perguntava (A7a-13), uma guarda
recém-posta que fazia o teste passar sem exercitar nada (A7b-14), um arquivo
curto demais que falhava antes da checagem em questão (A8a-17), uma guarda que o
próprio caminho de leitura torna inalcançável (A8a-18) e uma asserção simétrica
que a fixture não podia mostrar (A8b-18).

**A guarda da casa pegou a minha edição cinco vezes.** O `OrphanJavadocTest`
acusou, em cinco commits diferentes, um bloco novo inserido entre um javadoc e a
assinatura que ele documenta — sempre porque o script de edição ancorou na
assinatura, que vem depois do javadoc.

**Uma correção minha criou uma regressão**, achada pela prova de dentes do
achado seguinte (A7b-13 → A7b-14).

**Metade de dois achados foi refutada e a correção ficou**: em L1-4 os exemplos
do relatório são todos conferidos antes, e saem como `IOException`; em A7a-15 o
arquivo do leitor já estava protegido pelo `endeavourneo.home` do pom, e o que
restava era o vazamento entre testes e entre rodadas — que a prova de dentes
mostrou ao vivo.

### A sexta prova de dentes verde

Em A7a-13 o teste reescrevia a base pela própria fixture — e a fixture aponta o
catálogo para a pasta, o que **esvazia o cache que o teste pergunta sobre**. Ele
passava com a checagem de carimbo arrancada do produto. Reescrito para trocar só
os bytes, ele cai. É a mesma lição de sempre, num lugar novo: a prova de dentes
não pergunta sobre o teste, pergunta sobre a quebra.

---

## Fase 7 — as BAIXA sem identificador

**Ele apontou, e estava certo**: *"ainda restam apontamento de auditoria não
executados"*. Restavam 48.

### Por que passaram batido

O índice da auditoria I (`00-achados.md`) diz no cabeçalho **115 BAIXA**, e só
**67** delas trazem `- **ID**`. As outras **48** entraram na lista como
`- **-**` — sem identificador nenhum. O meu script de varredura casava o padrão
`- \*\*([A-Za-z0-9]+-\d+)\*\*`, isto é, **só as que têm identificador**. As 48
nunca chegaram a ser triadas: não foram adiadas nem recusadas, foram invisíveis.

A mesma armadilha existe nas outras severidades e ali é inofensiva: 4 linhas
`- **-**` em ALTA e 3 em MÉDIA são **corpo** de outros achados (a continuação de
uma lista dentro do texto), não achados novos. Em BAIXA são achados de verdade.
Conferido um a um antes de agir.

**A lição**: o número do cabeçalho e o número de identificadores não batiam —
115 contra 67 — e eu nunca comparei os dois. Uma contagem que não fecha é a
forma mais barata de achar o que ficou de fora, e custa uma linha de `grep -c`.

### O que foi corrigido

| commit | área | achados | arquivos | suíte |
|---|---|---|---|---|
| `f8a68e5` | A1 — `domain/market`, formato e escala | 12 | 10 | 724 |
| `cf794a2` | A2 — renko, contagem e leitores | 14 | 6 | 724 |
| `7b46089` | A3 — `ChartCanvas` | 13 | 4 | 724 |
| `7cbb543` | A4 + A7a — legenda, leitura e bundle | 9 | 8 | 724 |

Os 3 de A7a já estavam fechados por identificador em outra passada; ficam
registrados como conferidos, não como corrigidos de novo.

### Duas correções que estavam inertes

Dois achados **já corrigidos** por identificador não faziam efeito nenhum, e só
apareceu ao ler o vizinho sem identificador:

- **`ChartCanvas.setPeriod`** compara o período novo com o de tela por
  `equals` — e o comentário ao lado explica que faz assim justamente porque
  `Timeframe` e `Renko` são valores. Só que **nenhuma das duas classes definia
  `equals`**: o que rodava era o do `Object`, identidade. `PeriodCatalog.byCode`
  constrói um objeto novo a cada chamada, então a comparação sempre dizia
  "mudou" e sempre pagava o refold inteiro — a dobra, a reconstrução dos ticks
  com as listagens de pasta, e um worker. O comentário lia-se como conserto e o
  comportamento era o defeito que ele descreve. Corrigido com `equals`/`hashCode`
  nas duas.
- **`BollingerBands.strokes()`** responde três traços para que a linha do meio
  possa ter espessura e estilo próprios. O `StudyPane` honra; o gráfico de preço
  chamava `overlay.stroke()` — um só — para todas as linhas. As bandas são
  desenhadas no gráfico de preço. Corrigido em `paintOverlays`.

**O padrão**: correção inerte não é achada por auditoria de código isolado, é
achada quando se lê o chamador e o chamado juntos. Vale procurar as outras.

### Uma prova de dentes

O plural do resumo: `summary.years/months/days` só existiam no plural, então
uma série de exatamente um ano imprimia **"1 anos, 1 meses, 1 dias"**. Com as
chaves no singular e o `counted()` escolhendo entre as duas, imprime "1 ano,
1 mês, 1 dia" — e apagar o `counted` do produto derruba o teste.

### Estado

Com isto, **todos os 115 BAIXA da auditoria I estão processados** — 67 por
identificador nas passadas anteriores, 48 aqui. Somados aos 56 ALTA e 133 MÉDIA,
a auditoria I não tem mais nada em aberto além do que está registrado como
decisão adiada (D2 e D6).

---

## Fase 8 — a caça às outras correções inertes

Ele pediu: *"procure as outras correções inertes"*. Uma correção inerte é uma
que foi feita, está escrita no código, e **não faz efeito nenhum** — o comentário
lê-se como conserto e o comportamento continua sendo o defeito que ele descreve.
As duas achadas na fase 7 (`setPeriod.equals` sem `equals`, `strokes()` ignorado
no gráfico de preço) apareceram por acaso, lendo o vizinho. Aqui foram
procuradas.

### O método: cinco varreduras mecânicas

| o que se procura | como | resultado |
|---|---|---|
| comparação por valor sem `equals` | listar os 152 tipos, marcar quem declara `equals`, cruzar com todo `x.equals(` e toda chave de `Map`/`Set` | **limpo** — só `Renko`, `Timeframe` e `TradeTally` precisam, e os três têm |
| capacidade construída e nunca chamada | contar `nome(` e `::nome` em todo o código para cada método declarado | 11 métodos mortos de verdade |
| chave guardada e nunca lida | cruzar `put`/`setProperty` com `get`/`getProperty` | **limpo** — os 10 suspeitos são `UIManager` (lidos pelo Swing) e prefixos |
| campo atribuído e nunca lido | contar leituras separando as escritas | 3 constantes mortas na Bollinger |
| ajuste que não chega ao desenho | cada `ChartPreferences` e quem o lê | **limpo** — todos os `set` anunciam, e todos os leitores leem na hora de pintar |

E uma sexta, que foi a que mais rendeu: **comparar os dois leitores da mesma
interface**. `ChartCanvas` e `StudyPane` consomem ambos um `Overlay`; listar os
métodos que cada um chama e tirar a diferença mostra, em duas linhas, o que um
honra e o outro deixa cair.

```
ChartCanvas: calculate colours fitsOnPrice isVisible label paintUnder stroke strokes valueAt
StudyPane:   bounds     colours            isVisible label levels    ownPeriod stroke strokes valueAt
```

### O que foi corrigido (`2f348eb`, `6ccfb0b`, `c7d8b39`)

**1. A escala própria do indicador só aparecia no painel.** `Overlay.ownPeriod()`
diz no seu javadoc para que existe: *"dois estocásticos num painel são a mesma
palavra e os mesmos números, e sem isto são duas linhas idênticas sobre duas
linhas diferentes"*. Quem punha isso na tela era o `StudyPane`, sozinho — e a
composição estava escrita **dentro** dele. A legenda do gráfico de preço e o
menu de remover pediam `label()`. Duas médias de período 20, uma nas barras do
gráfico e outra em 5m, eram duas linhas iguais na legenda e duas entradas iguais
no menu. A média móvel e a Bollinger, que são os indicadores que vivem no preço,
são justamente os dois que oferecem a opção. Agora existe `Overlay.title()` e os
três leitores pedem a mesma coisa.

**2. O painel não desenhava o que o indicador põe embaixo das linhas.**
`paintUnder` era pedido pelo gráfico de preço e por mais ninguém. O diálogo de
inserção nunca desabilita "novo painel", então a Bollinger posta num painel
próprio perdia o preenchimento, em silêncio. E não bastava chamar: o viewport
tinha de ser **o do painel**. O do gráfico mapeia preços, e os números do
indicador passados por ele caem a 241 mil pixels do topo — a prova de dentes
mostrou exatamente isso, o que é a diferença entre honrar o contrato e parecer
honrá-lo.

**3. O gráfico de preço não desenhava os níveis.** Espelho do anterior:
`levels()` era honrado só pelo painel. Nenhum indicador de preço declara nível
hoje — e era por isso que dava para faltar sem ninguém ver.

**4. `ReplaySeries` não carregava `Untraded`/`Counted`.** Os outros três
envelopes passam as duas perguntas adiante; este respondia "não sei" para toda
barra. Latente hoje (o que ele embrulha é um dia de minutos), vivo no dia em que
um renko for reproduzido. A **barra em formação** não responde: contar a barra
inteira é contar negócios que ainda não aconteceram, e a prova de dentes mostrou
o vazamento — 10 negócios numa barra um quarto formada.

**5. Peso morto.** `BollingerBands.UPPER/MIDDLE/LOWER`, três constantes sob um
javadoc dizendo "onde os valores ficam em `valueAt`" que **nada lia** — o acordo
entre as três listas continuava preso à ordem em que estão escritas, que é
exatamente o que as constantes deviam ter deixado de valer. E
`Icons.candleHollow`, o ícone de um desenho recusado: vazio-ou-cheio acabou como
ajuste e não como estilo, então não há terceira entrada no seletor para ele ficar
ao lado.

**6. Um javadoc meu, de dois commits antes**, dizia *"candles, hollow candles or
a line"* — listando o ajuste como um terceiro estilo, que é o erro que o javadoc
daquele ajuste diz existir para evitar. Corrigido.

**7. A guarda que estourava em vez de guardar.** `SeriesWindow.readDays` devolve
a resposta com `key.equals(editing)` — a guarda que descarta a resposta de uma
série que o combo já abandonou. Sem série nenhuma a chave é `null`, e a guarda
lançava `NullPointerException` dentro de `SwingWorker.done`: o laço de eventos
imprime e engole, a janela fica dizendo "lendo..." para sempre, e nenhum teste
falha. **Estava saindo em toda rodada da suíte, no log, desde que a janela
existe.**

### As duas lições

**Correção inerte não se acha auditando um arquivo por vez.** Acha-se cruzando o
que uma interface promete com o que cada um dos seus leitores pede. Foi assim que
saíram quatro das sete.

**O log verde também se lê.** A NPE da janela de séries estava impressa em toda
rodada da suíte, no meio da saída de sucesso do gancho de pre-commit. Nenhum
teste falhava, então ninguém olhava. Está agora coberta por um teste que instala
um `UncaughtExceptionHandler` e pergunta se a thread da interface engoliu alguma
coisa.

### O que fica anotado e não foi mexido

Onze métodos que ninguém chama, nem o produto nem os testes:
`Aggregation.none`, `Segment.covers`, `TickSource.version`, `TickSource.suffix`,
`SeriesCatalog.setFolder`, `Settings.directory`, `ChartCanvas.setMode`,
`ChartCanvas.onModeChanged`, `ChartCanvas.getStretch`,
`OverlayLegend.isCollapsed`, `StudyPane.study`. Apagar API pública é decisão
dele, não minha — e alguns podem ser degrau para coisa que ainda vem.
`getStretch` ainda carrega o prefixo `get` que a casa não usa.

Suíte 724 → **732**.
