# TNO operado: a medição

**13/09/2026.** Uma configuração, uma rodada, aprovada antes de rodar. Não houve
varredura, então não há correção por comparações múltiplas a fazer — e também
não há vencedor escolhido entre candidatos, que é de onde vem o encolhimento por
seleção.

## A configuração

| | |
|---|---|
| série | `winfull-1m`, base inteira — 824.881 barras de 1m |
| decisão | 5m (165.288 barras) |
| execução | 1m, as barras armazenadas |
| estratégia | `MomentumCross` com os defaults do porte |
| PMO | 1 / 35 / 20 / 10, escala 10 — os valores do `JorgeReis_TNO_PMO.src` |
| alvo | 2R · stop no extremo do candle do sinal · folga 200 pts |
| contratos | 1 |
| custo | 6,5 pontos por giro por contrato |

**Tick a tick não foi usado, e a razão é de tamanho:** 824.881 minutos a ~1.500
ticks sintéticos por minuto dá ~1,2 bilhão de passos. Executar nas barras de 1m
é bem mais fino que 5m-em-5m e cabe na memória. O motor contou **68 barras
ambíguas** — 0,7% das operações — então o desempate stop-contra-alvo quase não
foi exercido.

## O número

| | |
|---|---|
| operações | 9.156 |
| **bruto** | **−25.145 pts** |
| custo | 59.514 pts |
| **líquido** | **−84.659 pts** (R$ −16.932 a R$ 0,20/ponto) |
| bruto por operação | **−2,75** |
| líquido por operação | −9,25 |
| acerto | 35,9% |
| queda máxima | −99.590 pts |
| exposição | 22,2% do tempo |
| aberto no fim | zerado |
| trivial (comprar e segurar, 1 contrato) | +81.940 pts |

**t do bruto por operação = −0,98** (média −2,75 · desvio 268,4 · n = 9.156).

## O que isso diz, e o que não diz

**O bruto não é distinguível de zero.** Com t = −0,98 não dá para afirmar que a
estratégia perde antes do custo; dá para afirmar que ela **não ganha**. O sinal
do PMO, operado assim, é uma moeda.

**O custo é quem decide.** Mesmo com o bruto exatamente zero, 9.156 giros a 6,5
pontos são 59.514 pontos de custo. O alvo desta busca é
[[janela-de-busca-viavel]]: mais de 12,5 pontos por operação para ser
demonstrável em quatro anos. Aqui são −2,75.

**A queda máxima é maior que o prejuízo total** (−99.590 contra −84.659), o que
diz que a curva não é uma sangria constante e sim um caminho violento que por
acaso terminou menos baixo do que já esteve.

**O acerto de 35,9% não é o vilão.** Num alvo de 2R o ponto de equilíbrio bruto
seria ~33,3%, e 35,9% está acima disso — o que bate com o bruto ficar perto de
zero. A conta limpa de R não fecha exatamente porque nem toda perda é 1R: há
saídas por inversão, por fim de pregão, e stops que executam além do nível
dentro da folga.

**O trivial não é um nulo justo aqui** — a estratégia fica no mercado 22% do
tempo e nos dois lados —, mas está na tabela porque é a referência que a janela
mostra, e porque a diferença de 166 mil pontos é a escala do que se está
deixando na mesa.

## O que NÃO foi medido

- **1m.** A estratégia opera o cruzamento da escala em que roda, e 1m é outra
  estratégia. Se for medido, entra na conta de comparações múltiplas junto com
  esta.
- **Qualquer outro jogo de períodos.** Os quatro vieram do indicador do Profit e
  não foram tocados. Mexer neles e escolher o melhor é uma varredura, e aí valem
  as regras de [[selecao-overfitting-medido]].
- **Andar para frente.** Esta é a base inteira de uma vez. Não houve separação
  entre base de busca e base de teste porque não houve busca.
