# O método de auditoria do endeavour_neo

Como rodar uma auditoria profunda deste projeto com vários agentes, quanto ela
custa, e o que muda conforme o modelo. Escrito em 05/09/2026, depois de uma
tentativa que estourou o limite de tokens sem entregar nada — o desenho abaixo
existe por causa dela.

Roteiro executável: [auditoria/auditoria-neo.workflow.js](auditoria/auditoria-neo.workflow.js)

---

## O tamanho do problema, medido

| | caracteres | ~tokens |
|---|---:|---:|
| produção (105 arquivos) | 1.125.086 | **281.000** |
| testes (64 arquivos) | 500.557 | **125.000** |
| **corpus inteiro, uma leitura** | 1.625.643 | **406.000** |

Esse é o piso: **nenhuma auditoria que leia tudo custa menos que ~406k tokens**,
qualquer que seja o modelo. O que se controla é o multiplicador em cima disso.

---

## As duas tentativas que falharam, e o que custaram

| tentativa | desenho | modelo | tokens | agentes concluídos | parou por |
|---|---|---|---:|---:|---|
| v1 | 13 agentes de uma vez, esforço alto; 4 lentes reliam todo o `src/main` | Fable | 1.506.753 | **0** | limite de sessão |
| v2 | 3 fases, lentes por `grep` dirigido, esforço médio | Opus | 830.073 | **0** | limite mensal de gasto |
| | | | **2.336.826** | **0** | |

**Dois milhões e trezentos mil tokens, nenhum relatório.**

A v2 corrigiu o custo por agente — tirou o 4× das lentes, baixou o esforço,
separou em fases. Não adiantou, e a razão é a lição de verdade:

> **O defeito não era o volume. Era a estrutura tudo-ou-nada.**

Treze agentes disparados juntos, com o resultado só existindo depois que todos
terminam. Um morre, todos morrem, e nada do que já foi lido vira relatório. O
trabalho das duas corridas evaporou inteiro.

Rodando **uma área por vez**, com cada agente escrevendo o seu arquivo antes de
terminar, o mesmo gasto teria produzido oito ou nove relatórios.

---

## A regra que sai disso

**Trabalho caro tem de ser incremental e persistir no disco a cada passo.**
Nunca dependa de um agregador final que só roda se tudo sobreviver.

Vale para qualquer varredura grande neste projeto — auditoria, medição em lote,
varredura de parâmetros: se o passo N não deixa um arquivo no disco, o passo N+1
não deveria começar.

---

## O desenho atual: três fases

### Fase 1 — áreas (leitura integral, particionada)

Nove agentes, um por área, cada um lendo **todas as linhas** dos seus arquivos.
A partição foi medida para equilibrar; o `ChartCanvas` fica sozinho por causa do
tamanho.

| área | arquivos | linhas |
|---|---:|---:|
| A1 `domain/market` — séries, arquivos e sessões | 16 | 3.185 |
| A2 `domain/market` — renko, ticks e replay | 12 | 2.996 |
| A3 `ui/chart` — **ChartCanvas** (integral) | 1 | 2.650 |
| A4 `ui/chart` — holder, layout, legenda, eixos, estilo | 21 | 5.167 |
| A5 indicadores — overlays, estudos, painéis e diálogos | 18 | 6.929 |
| A6 `ui/replay` | 9 | 2.519 |
| A7 platform, shell, settings, series e Launcher | 28 | 7.500 |
| A8a testes — domain, platform, arquitetura | 23 | ~4.600 |
| A8b testes — interface | 41 | ~8.300 |

Custo: **~1× o corpus**. É o piso, e não dá para fugir dele.

### Fase 2 — lentes transversais (grep dirigido)

Quatro agentes que não auditam uma área, e sim **uma pergunta em todo o código**:

| lente | pergunta |
|---|---|
| L1 | EDT e concorrência — Swing fora da thread, `synchronized` no caminho de pintura, listener que nunca sai |
| L2 | tempo, lookahead e escala — leitura do futuro, `OwnScale` contornado, fuso, regras do renko |
| L3 | persistência, recursos e memória — formato que não dá a volta, handle não fechado, alocação por barra |
| L4 | i18n e consistência — chave faltando no bundle, texto literal em Java, duas implementações divergidas |

**A mudança que barateou tudo:** elas recebem **padrões de `grep`** e leem
apenas ±40 linhas em volta de cada ocorrência. Não releem arquivo inteiro —
nove agentes já fizeram isso na fase 1.

Recebem também a lista do que a fase 1 já achou (`arquivo:linha
[categoria/severidade] título`), com instrução de reportar **só o que aquelas
não viram**. Isso elimina o trabalho duplicado antes de pagar por ele, em vez de
fundir depois.

Custo: **~0,1× o corpus** em vez de 4×.

### Fase 3 — verificação adversarial

Cada achado ALTA passa por **três lentes**, cada MÉDIA por **duas**, e a
predisposição do verificador é **refutar**:

| lente | o que faz |
|---|---|
| reproduzir | abre o arquivo, lê 60 linhas em volta, traça o caminho concreto; se o código não faz o que o achado diz, refuta |
| já tratado | procura no projeto inteiro se uma guarda, um chamador ou um teste já cobre o caso |
| consequência | supõe o achado verdadeiro e pergunta se o usuário ou a medição VÊ o efeito; corrige a severidade se estiver errada |

O insumo de um verificador é **um achado de ~20 linhas**, não o corpus. Por isso
esta fase é barata mesmo com o modelo mais pesado.

---

## Qual modelo em qual fase

A variável que decide é **a razão entre contexto e inferência**.

| fase | contexto | inferência | modelo | esforço |
|---|---|---|---|---|
| 1 · áreas | enorme (~406k) | rasa por achado | **Opus** | médio |
| 2 · lentes | pequeno (grep dirigido) | média | **Opus** | médio |
| 3 · verificação | mínimo (um achado) | **profunda** | **Fable** | alto |

**Auditoria ampla é leitura e lista.** Perguntas como "esta chave existe no
bundle?" ou "este componente é tocado fora da EDT?" são de checklist: o modelo
mais pesado paga preço cheio em cada token lido e o raciocínio extra em grande
parte re-deriva o que o mais leve também veria.

**Refutar um achado plausível-mas-errado é inferência difícil** sobre contexto
curto. É exatamente onde o modelo pesado converte raciocínio em resposta, e onde
ele custa pouco em absoluto.

### Se for rodar tudo no Fable

Muda só a fase 1, que é onde está o volume. Três ajustes, em ordem de eficácia:

1. **Divida em duas ou três corridas** — A1–A4, depois A5–A7, depois A8a/A8b.
   Cada uma fecha sozinha e escreve o seu relatório; a rajada some.
2. **Deixe os testes para uma corrida à parte.** São ~125k tokens e o tipo de
   defeito é outro (dentes, isolamento, cobertura) — cabe num documento próprio.
3. **Esforço médio na fase 1.** Alto ali multiplica o raciocínio justamente onde
   ele menos rende.

Com isso o Fable roda o método inteiro. Sem isso, ele estoura na fase 1 — foi o
que aconteceu.

---

## Como rodar — uma área por vez

**Este é o jeito recomendado, e a razão está na seção das duas falhas.**

Um agente por área, na ordem que quiser. Cada um recebe o padrão da seção
"Regras que os agentes recebem", lê a sua fatia integralmente, e **escreve o
relatório em `docs/auditoria/<area>.md` antes de terminar** — não devolve só
texto na conversa.

| área | linhas | ~tokens |
|---|---:|---:|
| A1 séries, arquivos e sessões | 3.185 | ~40k |
| A2 renko, ticks e replay | 2.996 | ~38k |
| A3 ChartCanvas | 2.650 | ~33k |
| A4 holder, layout, legenda | 5.167 | ~65k |
| A5 indicadores | 6.929 | ~87k |
| A6 replay | 2.519 | ~32k |
| A7 platform, shell, settings | 7.500 | ~94k |
| A8a testes — domínio | ~4.600 | ~58k |
| A8b testes — interface | ~8.300 | ~104k |
| **soma** | **43.800** | **~550k** |

Se o limite estourar na quinta área, as quatro primeiras continuam no disco e
valem. Depois de ter os nove arquivos, junte-os num documento só e só então rode
a verificação adversarial sobre os achados que sobraram — essa fase é barata,
porque o insumo é um achado de vinte linhas.

As quatro lentes transversais entram **depois** das nove áreas, lendo os
relatórios prontos mais `grep` dirigido, e também escrevem arquivo.

### O caminho por workflow, e quando NÃO usar

O roteiro está em [auditoria/auditoria-neo.workflow.js](auditoria/auditoria-neo.workflow.js)
e recebe `args` com a raiz e o mapa de áreas:

```
Workflow({ scriptPath: "docs/auditoria/auditoria-neo.workflow.js", args: { root, areas } })
```

`resumeFromRunId` traz de volta do cache os agentes cujo par (prompt, opções)
não mudou. **Mas isso só ajuda se algum tiver concluído** — nas duas corridas
acima nenhum concluiu, e o cache veio vazio.

**Use o workflow apenas com folga de orçamento confirmada.** Sem folga, ele
converte um gasto grande em zero entrega.

Regenerar a partição, se o projeto crescer:

```bash
find src/main/java -name "*.java" -exec wc -l {} + | sort -rn | head -30
```

Reequilibre para que nenhuma área passe de ~7.500 linhas, e mantenha qualquer
arquivo acima de ~2.000 linhas sozinho numa área.

---

## O formato do documento final

O mesmo das auditorias do `endeavour` (`docs/auditoria-2026-08-30-iii.md` é o
melhor modelo):

1. **Cabeçalho** — quantos agentes, que áreas, cobertura, e o estado do grafo
2. **Achados ALTA** — um por seção, com `arquivo:linha`, trecho literal,
   problema, consequência, correção, e a marca ✅ VERIFICADO quando as três
   lentes confirmaram
3. **Achados MÉDIA** — agrupados por área
4. **Achados BAIXA** — consolidados em lista
5. **O que foi auditado e está LIMPO** — obrigatório; "não achei nada" sem dizer
   o que olhou não vale
6. **Ordem de correção recomendada**
7. **Honestidade sobre o processo** — o que foi verificado centralmente, o que
   carrega só a evidência do agente, duplicatas fundidas, e o estado da suíte

---

## Regras que os agentes recebem

O prompt padrão carrega, além do que procurar:

- **As convenções do projeto** — comentário explica o porquê; teste sem dentes é
  ALTA; `domain/` não importa `ui/`; record valida invariante; classe utilitária
  recusa instanciação; texto de interface só no bundle; as regras da EDT.
- **As regras de domínio já medidas** — `OwnScale` lê o último candle FECHADO;
  base crua; as seis regras do renko conferidas contra o Profit; W1 de quinta a
  quarta; 1M de barras é o caso normal.

Violação de qualquer uma delas é achado, e as de domínio são ALTA. Ver
[../CLAUDE.md](../CLAUDE.md) e a skill `convencoes`.

**Evidência é obrigatória:** `arquivo:linha` e trecho literal. Antes de reportar,
o agente tem de tentar **refutar o próprio achado** e escrever o que tentou. O
que ele conseguiu refutar não entra.
