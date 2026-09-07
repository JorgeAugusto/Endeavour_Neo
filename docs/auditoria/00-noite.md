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
| 1 | Resolver todos os achados restantes da auditoria I | **em curso** |
| 2 | Refazer a auditoria completa, 2 agentes por vez | não começou |
| 3 | Juntar e validar cruzado | não começou |
| 4 | Corrigir os achados novos | não começou |

---

## O que falta — o inventário

Da auditoria de 30/08 a 06/09/2026, nove áreas mais quatro lentes
transversais. `00-achados.md` é o índice; o detalhe está no relatório de cada
área.

| gravidade | levantados | fechados | **abertos** |
|---|---:|---:|---:|
| ALTA | 52 | 51 + 1 refutado | **0** |
| MÉDIA | 133 | 24 | **109** |
| BAIXA | 115 | 0 | **115** |

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
