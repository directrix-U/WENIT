"""uiautomator dump XML -> 구조 전용 TSV fixture.

text / content-desc / hint 는 읽지도 출력하지도 않는다. 결과물에는 화면 원문이 없다.
사용법: python -I tools/sanitize_uiautomator_dump.py <dump.xml> <out.tsv> "<출처 메모: 날짜, 기기, 앱 버전, 화면>"
출력 컬럼: depth, viewId, className, left, top, right, bottom, scrollable
"""
import re, sys, xml.etree.ElementTree as ET

src, dst, note = sys.argv[1], sys.argv[2], sys.argv[3]
BOUNDS = re.compile(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]')
rows = []


def walk(el, depth):
    for n in el.findall('node'):
        m = BOUNDS.match(n.get('bounds', ''))
        l, t, r, b = m.groups() if m else ('0', '0', '0', '0')
        rows.append('\t'.join([
            str(depth), n.get('resource-id', '') or '-', n.get('class', '') or '-',
            l, t, r, b, '1' if n.get('scrollable') == 'true' else '0',
        ]))
        walk(n, depth + 1)


walk(ET.parse(src).getroot(), 0)
with open(dst, 'w', encoding='utf-8', newline='\n') as f:
    f.write('# ' + note + '\n')
    f.write('# columns: depth viewId className left top right bottom scrollable\n')
    f.write('# text/content-desc removed. structure only.\n')
    f.write('\n'.join(rows) + '\n')
print(dst, len(rows), 'nodes')
