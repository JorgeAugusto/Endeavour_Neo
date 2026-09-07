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
| 2 | Refazer a auditoria completa, 2 agentes por vez | **em curso** |
| 3 | Juntar e validar cruzado | não começou |
| 4 | Corrigir os achados novos, e o que sobrou da lista velha | não começou |

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

### D1 — Cache em disco das barras dobradas de um export · **ADIADA**

Abrir o gráfico de um export custa **8,1 s (MetaTrader, 1.838 MB)** ou **4,4 s
(Profit, 691 MB)**, e produz só 10.766 e 5.074 barras. Guardar essas barras num
`.bin` ao lado dos ticks tornaria a segunda abertura instantânea.

Adiada porque introduz **artefato derivado** e a pergunta de invalidação (um
pregão novo importado tem de invalidar o cache), e isso é escolha dele: se esse
gráfico for de uso diário o cache se paga; se for de consulta rara, é
complexidade sem dono.

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

### O que a auditoria II já entregou

| área | linhas lidas | ALTA | MÉDIA | BAIXA |
|---|---:|---:|---:|---:|
| B1 séries e formatos | 4.527 | 2 | 11 | 8 |
| B2 renko e replay | 2.774 | 2 | 10 | 8 |
| B3 `ChartCanvas` | 2.983 | 2 | 8 | 9 |
| B4 moldura | 8.198 | 1 | 13 | 17 |
| B5 indicadores | 4.375 | 1 | 9 | 8 |
| B8a testes de domínio | 7.620 | 4 | 9 | 6 |
| | | **12** | **60** | **56** |

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
