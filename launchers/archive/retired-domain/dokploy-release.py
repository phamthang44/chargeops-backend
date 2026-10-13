"""Submit an immutable image to an existing Dokploy Docker-provider application.

An accepted deployment request is not a completed rollout; inspect Dokploy logs
and run authenticated smoke tests before declaring the release healthy.
"""
import json
import os
from pathlib import Path
import re
import urllib.request
from urllib.parse import urlparse

base = os.environ['DOKPLOY_URL'].rstrip('/')
key = os.environ['DOKPLOY_API_KEY']
application = os.environ['DOKPLOY_APPLICATION_ID']
digest = Path('target/image-digest.txt').read_text().strip()
if urlparse(base).scheme != 'https' or not key or not application:
    raise SystemExit('Configure HTTPS Dokploy URL, API key and application ID.')
if not re.fullmatch(r'ghcr\.io/[a-z0-9_./-]+@sha256:[0-9a-f]{64}', digest):
    raise SystemExit('Expected an immutable GHCR digest from the release job.')
for endpoint, payload in (
    ('application.update', {'applicationId': application, 'dockerImage': digest}),
    ('application.deploy', {'applicationId': application}),
):
    request = urllib.request.Request(
        base + '/api/' + endpoint, data=json.dumps(payload).encode(),
        headers={'Content-Type': 'application/json', 'x-api-key': key}, method='POST')
    with urllib.request.urlopen(request, timeout=60) as response:
        result = json.loads(response.read() or b'{}')
        if isinstance(result, dict) and result.get('error'):
            raise SystemExit('Dokploy rejected ' + endpoint)
print('Dokploy accepted deployment request for ' + digest)
if os.environ.get('GITHUB_STEP_SUMMARY'):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as report:
        report.write('### Dokploy rollout requested\n\n' + digest +
                     '\n\nCheck rollout logs and smoke tests; acceptance is not readiness.\n')
