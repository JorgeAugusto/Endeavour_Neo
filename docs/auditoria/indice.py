# -*- coding: utf-8 -*-
"""Um indice compacto de tudo que as nove areas acharam.

POR QUE. As lentes transversais existem para achar o que as areas NAO viram, e a
unica forma barata de garantir isso e entregar a elas o que ja foi visto -- uma
linha por achado, nao as 8.100 linhas dos relatorios completos. Foi aqui que a
tentativa v1 se perdeu: quatro lentes relendo integralmente o que nove agentes ja
tinham lido.

POR QUE TOLERANTE. Os nove agentes escreveram em nove estilos: "### A2-3. titulo",
"### A7a-6 -- titulo", referencia em "**Onde:**" ou numa linha de crase solta, e
BAIXA ora em cabecalho ora em lista. Um extrator estrito perdeu 40% dos achados na
primeira tentativa, e um indice furado faz a lente reportar de novo o que a area ja
viu -- que e exatamente o custo que ele existe para evitar.
"""
import io, os, re, glob

d = r'C:/01-programacao/01-back-end/01-java/2022/05-workspaces/02-jorge-reis/endeavour_neo/docs/auditoria'

SEV = re.compile(r'^#{2,3}\s+Achados\s+(ALTA|M\u00c9DIA|BAIXA)', re.I)
# "### A2-3. titulo" / "### A7a-6 -- titulo" / "### A8b-11: titulo"
HEAD = re.compile(r'^#{3,4}\s+([A-Za-z]{1,3}\d[a-z]?-\d+)\s*[.\u2014:\u2013-]*\s*(.*\S)\s*$')
ONDE = re.compile(r'^\*\*Onde:?\*\*:?\s*(.+?)\s*$')
# uma linha que e SO uma referencia entre crases: `Renko.java:72-74`
BARE = re.compile(r'^`([^`]+\.java:[0-9][^`]*)`\s*$')
# "- **A2-15** `x.java:9` -- titulo"  ou  "- `x.java:9` -- titulo"
BULLET = re.compile(
    r'^[-*]\s+(?:\*\*([A-Za-z]{1,3}\d[a-z]?-\d+)\*\*\s*)?`([^`]+)`\s*[\u2014:\u2013-]+\s*(.*\S)\s*$')

rows = []
seen = set()


def add(ident, title, sev, where):
    key = (ident, title[:60])
    if key in seen:
        return
    seen.add(key)
    rows.append([ident, title, sev, where])


for path in sorted(glob.glob(os.path.join(d, 'a*.md'))):
    sev = '?'
    pending = None

    for line in io.open(path, encoding='utf-8'):
        line = line.rstrip('\n')

        m = SEV.match(line)
        if m:
            sev = m.group(1).upper()
            pending = None
            continue

        m = HEAD.match(line)
        if m:
            add(m.group(1), m.group(2), sev, '')
            pending = rows[-1]
            continue

        if pending is not None and not pending[3]:
            m = ONDE.match(line) or BARE.match(line)
            if m:
                pending[3] = m.group(1).replace('`', '').split(',')[0].strip()
                pending = None
                continue

        m = BULLET.match(line)
        if m and sev != '?':
            add(m.group(1) or '-', m.group(3), sev, m.group(2).strip())

out = [u'# \u00cdndice dos achados das nove \u00e1reas',
       u'',
       u'Uma linha por achado: **id \u00b7 arquivo:linha \u00b7 t\u00edtulo**. O detalhe \u2014 trecho',
       u'literal, consequ\u00eancia, corre\u00e7\u00e3o e a tentativa de refuta\u00e7\u00e3o \u2014 est\u00e1 no',
       u'relat\u00f3rio da \u00e1rea.',
       u'',
       u'Este arquivo existe para as **lentes transversais**: elas recebem esta lista',
       u'e a ordem de reportar **s\u00f3 o que estas \u00e1reas n\u00e3o viram**. Entregar os',
       u'relat\u00f3rios inteiros custaria um quarto multiplicador em cima do corpus, que',
       u'foi exatamente como a tentativa v1 queimou 1,5 milh\u00e3o de tokens sem entregar',
       u'nada.',
       u'']

for sev in ('ALTA', u'M\u00c9DIA', 'BAIXA', '?'):
    picked = [r for r in rows if r[2] == sev]
    if not picked:
        continue
    out.append(u'## %s (%d)' % (sev if sev != '?' else u'Sem severidade reconhecida', len(picked)))
    out.append(u'')
    for ident, title, _, where in picked:
        out.append(u'- **%s** `%s` \u2014 %s' % (ident, where or u'(sem refer\u00eancia)', title))
    out.append(u'')

target = os.path.join(d, '00-achados.md')
io.open(target, 'w', encoding='utf-8', newline='\n').write(u'\n'.join(out))

print('achados: %d' % len(rows))
for sev in ('ALTA', u'M\u00c9DIA', 'BAIXA', '?'):
    n = len([r for r in rows if r[2] == sev])
    if n:
        print('  %-6s %d' % (sev, n))
print('sem referencia: %d' % len([r for r in rows if not r[3]]))
