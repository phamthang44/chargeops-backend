"""Generate an offline HTML test dashboard and fail on silently skipped Docker tests."""
import html
import os
from pathlib import Path
import xml.etree.ElementTree as ET

reports = sorted(Path('target/surefire-reports').glob('TEST-*.xml'))
totals = dict(tests=0, failures=0, errors=0, skipped=0)
rows = []
details = []
blocked = []
for report in reports:
    suite = ET.parse(report).getroot()
    counts = {key: int(suite.get(key, '0')) for key in totals}
    for key in totals:
        totals[key] += counts[key]
    name = suite.get('name', report.stem)
    source = Path('src/test/java') / (name.split('$')[0].replace('.', '/') + '.java')
    docker_skips = [item for item in suite.iter('skipped')
                    if 'docker' in item.get('message', '').lower()]
    if docker_skips or (counts['skipped'] == counts['tests'] and counts['tests']
                        and source.exists() and '@Testcontainers' in source.read_text(encoding='utf-8')):
        blocked.append(name)
    rows.append('<tr><td>' + html.escape(name) + '</td>' + ''.join(
        f'<td>{counts[key]}</td>' for key in totals) + '</tr>')
    for case in suite.iter('testcase'):
        for kind in ('failure', 'error', 'skipped'):
            for issue in case.findall(kind):
                details.append('<details><summary>' + html.escape(
                    kind.upper() + ': ' + name + ' / ' + case.get('name', ''))
                    + '</summary><pre>' + html.escape(issue.get('message', '')
                    + '\n' + (issue.text or '')) + '</pre></details>')
summary = ', '.join(f'{key}: {value}' for key, value in totals.items())
status = 'BLOCKED' if not reports or totals['tests'] <= totals['skipped'] or totals['failures'] or totals['errors'] or blocked else 'PASS'
print(status + ': ' + summary)
Path('target').mkdir(exist_ok=True)
Path('target/quality-report.html').write_text(
    '<!doctype html><html lang="en"><meta charset="utf-8"><title>Backend quality</title>'
    '<style>body{font:16px system-ui;margin:40px;color:#17243a}table{border-collapse:collapse}'
    'td,th{padding:8px;border:1px solid #ccd4df}th{background:#e8eef6}'
    'pre{white-space:pre-wrap;overflow-wrap:anywhere}details{margin:12px 0}</style>'
    '<h1>ChargeOps backend test results: ' + status + '</h1><p>' + html.escape(summary) + '</p>'
    '<p><a href="site/jacoco/index.html">Coverage report</a> · '
    '<a href="bom.json">Software inventory JSON</a> · <a href="bom.xml">Inventory XML</a>. '
    'Skipped tests are not passes. Coverage/inventory links require their generated artifacts.</p>'
    '<table><tr><th>Suite</th><th>Tests</th><th>Failures</th><th>Errors</th><th>Skipped</th></tr>'
    + ''.join(rows) + '</table><h2>Failures and skipped tests</h2>' + ''.join(details)
    + '</html>', encoding='utf-8')
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as output:
        output.write('### Backend tests: ' + status + '\n\n' + summary
                     + '\n\n<table><tr><th>Suite</th><th>Tests</th><th>Failures</th>'
                     + '<th>Errors</th><th>Skipped</th></tr>' + ''.join(rows)
                     + '</table>\n\nDownload the quality artifact for detailed HTML, coverage and SBOM.\n')
if not reports or totals['tests'] <= totals['skipped'] or totals['failures'] or totals['errors'] or blocked:
    raise SystemExit('Quality gate failed: missing/failed tests or skipped Testcontainers suites: ' + ', '.join(blocked))
