export const meta = {
  name: 'auditoria-neo-v2',
  description: 'Auditoria do endeavour_neo em tres fases: areas leem integral (Opus), lentes cruzam por grep dirigido (Opus), achados verificados adversarialmente (Fable)',
  phases: [
    { title: 'Areas', detail: '9 agentes, leitura integral da propria fatia' },
    { title: 'Lentes', detail: '4 agentes transversais: grep dirigido + os relatorios das areas' },
    { title: 'Verificar', detail: 'ALTA com 3 lentes, MEDIA com 2 — no Fable', model: 'fable' },
  ],
}

const ROOT = args.root
const AREAS = args.areas

// ------------------------------------------------------------------ schemas

const FINDING = {
  type: 'object',
  required: ['severity', 'title', 'file', 'line', 'excerpt', 'problem', 'consequence', 'fix', 'confidence', 'refutation', 'category'],
  properties: {
    severity: { type: 'string', enum: ['ALTA', 'MEDIA', 'BAIXA'] },
    title: { type: 'string', description: 'uma frase, em portugues, dizendo o defeito' },
    file: { type: 'string', description: 'caminho relativo a raiz do projeto' },
    line: { type: 'integer' },
    excerpt: { type: 'string', description: 'trecho LITERAL copiado do codigo, 1-8 linhas' },
    problem: { type: 'string', description: 'o que esta errado, em portugues' },
    consequence: { type: 'string', description: 'o que o usuario ou a medicao ve por causa disso' },
    fix: { type: 'string', description: 'como corrigir, concreto' },
    confidence: { type: 'number' },
    refutation: { type: 'string', description: 'o que voce tentou para refutar e por que nao conseguiu' },
    category: { type: 'string', description: 'lookahead | edt | indice | nan | estado | vazamento | persistencia | excecao | comentario | duplicacao | invariante | i18n | desempenho | teste | desenho | outro' },
  },
}

const REPORT = {
  type: 'object',
  required: ['area', 'files_read', 'findings', 'clean', 'notes'],
  properties: {
    area: { type: 'string' },
    files_read: { type: 'array', items: { type: 'string' } },
    findings: { type: 'array', items: FINDING },
    clean: { type: 'array', items: { type: 'object', required: ['what', 'how'], properties: { what: { type: 'string' }, how: { type: 'string' } } } },
    notes: { type: 'string', description: 'observacoes sobre a area: qualidade, riscos estruturais, o que merece segundo olhar' },
  },
}

const VERDICT = {
  type: 'object',
  required: ['refuted', 'severity', 'reasoning', 'evidence'],
  properties: {
    refuted: { type: 'boolean' },
    severity: { type: 'string', enum: ['ALTA', 'MEDIA', 'BAIXA', 'NENHUMA'] },
    reasoning: { type: 'string' },
    evidence: { type: 'string', description: 'arquivo:linha e trecho literal' },
  },
}

// ------------------------------------------------------------ o padrao

const STANDARD = `
PROJETO: endeavour_neo, raiz ${ROOT}. Java 17, Swing, Maven, JUnit 5. E o MVP de uma ferramenta de PESQUISA de mercado (WIN, mini indice B3): graficos, indicadores, replay de pregao, series de 1 minuto e de ticks, renko. Codigo e comentarios em ingles; interface via ResourceBundle (messages.properties e messages_pt_BR.properties).

CONVENCOES DO PROJETO — desvio delas E achado:
- Comentario explica POR QUE, nunca O QUE. Comentario que MENTE sobre o codigo (diz uma coisa, o codigo faz outra) e achado MEDIA no minimo.
- Teste precisa ter dentes: um teste que passaria com a logica quebrada e achado ALTA. Teste que compara um valor com ele mesmo, que nao le dado nenhum, ou que le o workspace real do desenvolvedor, e achado.
- domain/ NAO importa ui/ nem javax.swing (ha LayerBoundaryTest para isso).
- Zero dependencia de runtime fora do JDK, exceto FlatLaf por reflexao com queda limpa.
- Record com invariante validada no construtor compacto.
- Classe utilitaria recusa instanciacao (construtor privado que lanca AssertionError).
- EDT: componente Swing so na EDT; nada synchronized no caminho de pintura; getter lido por repaint e volatile e nao sincronizado; metodo publico chamado de dentro de SwingWorker/Timer/thread se encaminha sozinho (invokeLater); look-and-feel antes de qualquer componente.
- Texto de interface so no ResourceBundle. String de interface literal em Java e achado.
- Busque parametro por chave, nunca por posicao.

REGRAS DE DOMINIO (violacao e ALTA):
- Indicador de escala maior sobre serie menor le o ULTIMO candle FECHADO da escala maior, nunca o que contem a barra atual. A regra vive em OwnScale. Qualquer leitura do futuro, em qualquer lugar, e ALTA.
- A base padrao e CRUA (nao ajustada). Nada pode supor precos ajustados.
- Renko (medido contra o Profit): caixa e (n-1) x tick; fecha so quando o preco PASSA o nivel (ceil(moved/brick - eps) - 1); reversao desenha steps-(reversal-1) caixas comecando (reversal-1) caixas do anchor; contagem de negocios e por janela de TEMPO; a primeira caixa do lote leva o acumulador inteiro; o acumulador atravessa a noite; a regua nao reinicia por pregao.
- Agregacao por fuso: W1 vai de quinta a quarta; D1 carrega a data do dia anterior.
- Series de ate ~1.050.000 barras de 1 minuto sao o caso NORMAL: alocacao por barra dentro de laco de pintura, ou algoritmo O(n^2), e achado de desempenho.

O QUE PROCURAR (nao se limite): lookahead/vazamento de futuro; erro de indice e de limite; NaN, divisao por zero, janela vazia; estado mutavel compartilhado entre threads; vazamento de listener, follower, Timer, arquivo, handle; violacao de EDT; ordem de inicializacao (campo lido antes de atribuido, this escapando do construtor); persistencia que nao da a volta (salvar -> ler -> mesmo objeto); formato fragil; excecao engolida; comentario desatualizado ou mentiroso; duplicacao que ja divergiu; invariante nao validada; chave de mensagem faltando; teste sem dentes; e problemas de DESENHO — acoplamento errado, responsabilidade no lugar errado, abstracao que vaza.

SEVERIDADE: ALTA = resultado errado, perda de dado, travamento, leitura do futuro, teste que da licenca falsa. MEDIA = defeito real com contorno, ou divida que vai custar em breve. BAIXA = qualidade, clareza, inconsistencia pequena.

EVIDENCIA OBRIGATORIA: todo achado com arquivo:linha e trecho LITERAL em 'excerpt'. Nada de "parece que". Antes de reportar, tente REFUTAR: releia, procure o tratamento em outro lugar (chamador, guarda, teste), e escreva em 'refutation' o que tentou e por que nao derrubou. Achado que voce conseguiu refutar NAO entra.

LIMPO: liste em 'clean' o que conferiu e esta correto, e COMO conferiu. Secao obrigatoria.

IDIOMA: title, problem, consequence, fix, refutation, clean e notes em PORTUGUES com acentos. file, excerpt e category como no codigo.

Este relatorio sera usado por outro modelo para CORRIGIR os problemas: cada achado precisa ser acionavel sem reler a conversa.`

// --------------------------------------------------------- fase 1: areas

phase('Areas')

function areaPrompt(name, files) {
  return `${STANDARD}

SUA AREA: ${name}

LEIA INTEGRALMENTE, do inicio ao fim, cada um destes arquivos (relativos a ${ROOT}):
${files.map(f => '  ' + f).join('\n')}

Leitura integral significa TODAS as linhas — use Read com offset/limit quantas vezes precisar; um arquivo de 2.650 linhas exige varias leituras. Nao amostre. Voce pode abrir OUTROS arquivos para conferir um chamador, uma guarda ou um teste — faca isso sempre que um achado dependa do que acontece fora da sua area, mas nao audite a area dos outros.

Preencha 'files_read' com o que leu por inteiro. Se nao conseguiu ler algum integralmente, diga em 'notes'.`
}

const areaNames = Object.keys(AREAS)

log(`Fase 1: ${areaNames.length} agentes de area (Opus, esforco medio)`)

const reports = (await parallel(areaNames.map(name => () =>
  agent(areaPrompt(name, AREAS[name]), {
    label: name.slice(0, 38), phase: 'Areas', schema: REPORT, effort: 'medium',
  })
))).filter(Boolean)

log(`${reports.length}/${areaNames.length} areas responderam`)

// ------------------------------------------------------- fase 2: lentes

phase('Lentes')

const known = []
for (const r of reports) {
  for (const f of r.findings || []) {
    known.push(`${f.file}:${f.line} [${f.category}/${f.severity}] ${f.title}`)
  }
}

const KNOWN_BLOCK = known.length
  ? `JA ACHADO PELAS AREAS (nao repita; so reporte o que estas nao viram):\n${known.map(k => '  ' + k).join('\n')}`
  : 'As areas nao reportaram nada ainda — reporte tudo o que encontrar.'

const LENSES = [
  ['L1 EDT e concorrencia',
   'Onde um componente Swing e criado, alterado ou pintado fora da thread de interface? Onde ha synchronized, lock ou espera no caminho de pintura? Onde um SwingWorker.done(), Timer, callback de servico ou watcher toca a interface sem garantir a EDT? Onde um campo lido pelo repaint e escrito por outra thread sem volatile? Onde o look-and-feel poderia ser aplicado depois de um componente ja existir? Onde um listener/follower/Timer e adicionado e nunca removido?',
   'invokeLater|invokeAndWait|isEventDispatchThread|SwingWorker|new Timer\\(|synchronized|volatile|setLookAndFeel|addChangeListener|addActionListener|onChange\\(|watch\\(|follow\\('],
  ['L2 Tempo, lookahead e escala',
   'Onde um calculo le uma barra que ainda nao fechou, ou um valor do futuro? Onde a regra de OwnScale (ultimo candle FECHADO) e contornada, reimplementada ou aplicada errado? Onde um indice de barra pode apontar para o futuro no replay? Onde ha suposicao de fuso, virada de pregao, W1/D1, horario de abertura? Onde a unidade esta errada (pontos vs reais, ajustado vs cru)? Onde o renko pode divergir das regras medidas?',
   'OwnScale|indexOfClosed|lastVisibleBar|hoveredBar|barUnderCursor|size\\(\\) - 1|ZoneId|LocalDate|LocalTime|sessionGap|startsSession|brick|reversal|anchor'],
  ['L3 Persistencia, recursos e memoria',
   'Onde um formato salvo nao da a volta (salvar -> ler -> mesmo objeto)? Onde uma linha malformada, chave faltando ou versao antiga do workspace derruba a leitura inteira em vez de pular a linha? Onde um arquivo, stream, Timer ou thread pode nao ser fechado (inclusive em excecao)? Onde a memoria de ticks (90-340 MB por sessao) pode ficar retida? Onde ha alocacao por barra dentro de laco sobre 1M de barras? Onde uma preferencia e lida com padrao errado ou escrita em chave diferente da lida?',
   'PREFS|Settings\\.|\\.put\\(|\\.get\\(|getBoolean|putBoolean|Files\\.|FileInputStream|FileChannel|try \\(|\\.close\\(\\)|new byte\\[|new double\\[|new int\\['],
  ['L4 i18n, interface e consistencia',
   'Quais chaves usadas em Messages.get(...) NAO existem em messages.properties ou em messages_pt_BR.properties? Quais chaves existem no bundle e nao sao usadas por ninguem? Onde ha texto de interface literal em Java? Onde um enum e mostrado pelo name() em vez de traduzido? Onde dois lugares fazem a mesma coisa de jeitos que ja divergiram (dois renderizadores, duas formatacoes, duas regras de cor)?',
   'Messages\\.get\\(|setText\\(\"|setToolTipText\\(\"|new JLabel\\(\"|new JButton\\(\"|setTitle\\(\"|name\\(\\)'],
]

function lensPrompt(name, question, patterns) {
  return `${STANDARD}

SUA LENTE TRANSVERSAL: ${name}

Voce NAO audita uma area — audita UMA PERGUNTA em todo o codigo de producao (src/main sob ${ROOT}):

${question}

COMO TRABALHAR, e isto e importante para o custo: **NAO leia arquivos inteiros.** Use Grep com estes padroes para localizar as ocorrencias, e depois Read APENAS o trecho em volta de cada ocorrencia (offset/limit, ~40 linhas de contexto). Nove agentes ja leram o projeto inteiro; o seu valor esta em cruzar arquivos, nao em reler.

PADROES PARA COMECAR (ajuste e acrescente conforme achar):
${patterns}

Para a L4 especificamente: leia os DOIS arquivos de bundle por inteiro (sao pequenos) e compare as chaves entre eles e contra os Messages.get do codigo.

${KNOWN_BLOCK}

Registre em 'files_read' os arquivos em que leu contexto.`
}

log(`Fase 2: ${LENSES.length} lentes transversais por grep dirigido, cientes de ${known.length} achados das areas`)

const lensReports = (await parallel(LENSES.map(([name, question, patterns]) => () =>
  agent(lensPrompt(name, question, patterns), {
    label: name, phase: 'Lentes', schema: REPORT, effort: 'medium',
  })
))).filter(Boolean)

const allReports = reports.concat(lensReports)

// ----------------------------------------------------------- fundir

const all = []
for (const r of allReports) {
  for (const f of r.findings || []) { all.push({ ...f, area: r.area }) }
}

const RANK = { ALTA: 3, MEDIA: 2, BAIXA: 1 }
const merged = []

for (const f of all) {
  const twin = merged.find(m => m.file === f.file && Math.abs((m.line || 0) - (f.line || 0)) <= 8)
  if (twin) {
    twin.also = twin.also || []
    twin.also.push({ area: f.area, title: f.title, severity: f.severity })
    if (RANK[f.severity] > RANK[twin.severity]) { twin.severity = f.severity }
    continue
  }
  merged.push({ ...f })
}

const counts = { ALTA: 0, MEDIA: 0, BAIXA: 0 }
for (const m of merged) { counts[m.severity]++ }
log(`${all.length} achados brutos -> ${merged.length} apos fundir (ALTA ${counts.ALTA}, MEDIA ${counts.MEDIA}, BAIXA ${counts.BAIXA})`)

// ------------------------------------------------------ fase 3: verificar

phase('Verificar')

const VERIFY_LENSES = [
  ['reproduzir', 'Abra o arquivo na linha indicada e leia ao menos 60 linhas em volta. O trecho citado existe como citado? O mecanismo descrito e o que o codigo realmente faz? Trace o caminho concreto — quem chama, com que valores — e diga se o defeito se reproduz. Se o codigo NAO faz o que o achado diz, refute.'],
  ['ja tratado', 'Procure em TODO o projeto (chamadores, guardas, outros metodos da mesma classe, testes) se este caso ja e tratado de modo que o defeito nao chega a acontecer. Um teste que cobre exatamente este caso e passa e refutacao. Se o tratamento existe, refute e aponte onde.'],
  ['consequencia', 'Suponha o achado verdadeiro. Um usuario do aplicativo, ou uma medicao feita com ele, VERIA a consequencia descrita? Em que situacao concreta? A severidade e a certa pela regra (ALTA = resultado errado, perda de dado, travamento, futuro; MEDIA = defeito com contorno; BAIXA = qualidade)? Se a consequencia nao se materializa, refute; se a severidade esta errada, corrija em "severity".'],
]

function verifyPrompt(f, lens, instruction) {
  return `Voce e um verificador ADVERSARIAL de uma auditoria de codigo. Projeto: ${ROOT} (Java 17, Swing). Sua lente e "${lens}". Sua predisposicao e REFUTAR: um achado so sobrevive se resistir a voce. Na duvida, refute e diga por que a duvida existe.

ACHADO
severidade proposta: ${f.severity}
titulo: ${f.title}
arquivo: ${f.file}:${f.line}
trecho citado:
${f.excerpt}
problema: ${f.problem}
consequencia: ${f.consequence}
correcao proposta: ${f.fix}
o auditor ja tentou refutar assim: ${f.refutation}

O QUE FAZER NESTA LENTE: ${instruction}

Verdades do dominio: indicador de escala maior le o ultimo candle FECHADO (OwnScale); base crua; renko fecha ao PASSAR o nivel; teste sem dentes e ALTA; series de 1M de barras sao o caso normal. Responda em portugues. 'evidence' com arquivo:linha e trecho literal.`
}

const toVerify = merged.filter(m => m.severity === 'ALTA' || m.severity === 'MEDIA')
log(`Fase 3: ${toVerify.length} achados no Fable (ALTA com 3 lentes, MEDIA com 2); ${counts.BAIXA} BAIXA carregam so a evidencia do auditor`)

const verified = (await parallel(toVerify.map(f => () => {
  const lenses = f.severity === 'ALTA' ? VERIFY_LENSES : VERIFY_LENSES.slice(0, 2)
  return parallel(lenses.map(([lens, instruction]) => () =>
    agent(verifyPrompt(f, lens, instruction), {
      label: `v:${lens}:${(f.file || '').split('/').pop()}:${f.line}`,
      phase: 'Verificar', schema: VERDICT, model: 'fable', effort: 'high',
    }).then(v => v && { lens, ...v })
  )).then(votes => ({ ...f, votes: votes.filter(Boolean) }))
}))).filter(Boolean)

let unanimous = 0, killed = 0, contested = 0
for (const v of verified) {
  const refutes = v.votes.filter(x => x.refuted).length
  if (refutes === 0) unanimous++
  else if (refutes === v.votes.length) killed++
  else contested++
}
log(`Verificacao: ${unanimous} confirmados por unanimidade, ${contested} contestados, ${killed} refutados por todas as lentes`)

return {
  areas: allReports.map(r => ({ area: r.area, files_read: (r.files_read || []).length, findings: (r.findings || []).length, clean: r.clean, notes: r.notes })),
  verified,
  baixa: merged.filter(m => m.severity === 'BAIXA'),
  raw_count: all.length,
  merged_count: merged.length,
}