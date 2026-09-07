# Auditoria II — o briefing que todo agente recebe

Segunda passada completa, 07/09/2026. A primeira está em `docs/auditoria/*.md` e
**não deve ser lida** por quem audita uma área: o valor da segunda passada é ser
independente, e a fase 3 confere uma contra a outra. Ler a primeira anularia a
única coisa que a segunda tem de especial.

## 1. O projeto, em três linhas

`endeavour_neo` é um terminal gráfico de mercado em **Java 25 + Swing**, para o
mini-índice brasileiro (WIN). Lê séries de candles de arquivos binários próprios
e ticks exportados do MetaTrader e do Profit; desenha candles, linha e renko;
tem replay de sessões, indicadores e um espaço de trabalho persistente.

Não é biblioteca: é uma ferramenta que uma pessoa usa para estudar o mercado. O
autor está em **fase de construção da ferramenta** — ressalva sobre operar
dinheiro é ruído, defeito que muda número na tela não é.

## 2. As convenções da casa

Estão em `~/.claude/skills/convencoes/SKILL.md`. **Leia antes de auditar.**
Desvio delas É achado. As que mais pegam:

- javadoc e comentário dizem **por que**, não o que; e devem ser verdadeiros —
  comentário que mente é achado, e de severidade igual ao defeito que ele esconde
- nada de alocação por elemento em laço quente
- interface (`ui`) nunca importa de fora para dentro do `domain`; **texto de
  tela nunca sai do `ResourceBundle`**
- trabalho pesado nunca na thread da interface (EDT)
- todo recurso que abre thread ou arquivo é fechado por quem o abriu

## 3. As regras de domínio já medidas

Violá-las é **ALTA**. Estão nos javadoc das classes, e as principais:

- **renko**: a caixa é `(n−1) × tick`; a grade é absoluta ancorada no zero; não
  reinicia por pregão; **fecha quando o preço PASSA o nível, não quando o
  alcança**; a primeira caixa de um lote leva todo o volume acumulado
- **escala maior**: um indicador em escala maior lê a última barra **FECHADA** —
  ler a que contém a barra atual é ler o futuro
- **replay**: o que não chegou não pode ser lido; e o candle e o renko têm de
  concordar sobre o que já aconteceu (um relógio só)
- **ticks sintéticos**: passo de um tick, 17,6% de continuação, contagem
  `30,1 × amplitude^1,241` — medido em nove pregões de tape
- **volume ausente é NaN, nunca zero**: "não disse nada" e "disse zero" são
  coisas diferentes, e o formato guarda a diferença

## 4. O que procurar

Aberta, e **não se limite a isto**:

resultado errado · leitura do futuro · perda de dado · travamento · recurso que
vaza · trabalho pesado na EDT · comentário ou javadoc que mente · javadoc
grudado no membro errado · teste que passaria com o produto quebrado · texto de
tela fora do bundle · duas respostas para a mesma pergunta em lugares
diferentes · guarda que protege menos do que diz · número mágico sem origem

## 5. Evidência obrigatória, e refutação antes de reportar

- todo achado com **`arquivo:linha` e trecho LITERAL copiado do código**
- **antes de reportar, tente derrubar o próprio achado**: releia, procure o
  tratamento em outro lugar (chamador, guarda, teste), e escreva o que tentou e
  por que não derrubou. O que você conseguir refutar **não entra**
- a seção **LIMPO** é obrigatória: o que você conferiu e está certo, e **como**
  conferiu. "Não achei nada" sem dizer o que olhou não vale

## 6. Severidade

| | |
|---|---|
| **ALTA** | resultado errado, perda de dado, travamento, leitura do futuro, teste que dá licença falsa |
| **MÉDIA** | defeito real com contorno, ou dívida que vai custar em breve |
| **BAIXA** | qualidade, clareza, inconsistência pequena |

## 7. A regra inegociável

**Escreva o seu arquivo em `docs/auditoria/ii/<área>.md` ANTES de terminar.**
Não devolva só texto na conversa. Se a sessão morrer depois de você escrever, o
seu trabalho vale; se morrer antes, ele evapora — foi assim que duas corridas
inteiras viraram zero em 05/09/2026.

Formato do arquivo: cabeçalho com o que foi lido (arquivos e linhas), depois
ALTA, MÉDIA, BAIXA, cada achado com `### <id>. <título>` / `arquivo:linha` /
trecho / **Problema** / **Consequência** / **Correção** / **Tentei refutar
assim**. E a seção **LIMPO** no fim.

Os ids são `<área>-<n>` — por exemplo `B1-1`, `B1-2`. O prefixo **B** separa
esta passada da primeira, que usou **A**.
