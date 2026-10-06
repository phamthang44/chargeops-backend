# Elasticsearch demo — first milestone

Spring Boot 4.1.0 manages Java client 9.4.2. The Docker image is pinned to the same version.
PostgreSQL is the source of truth; Elasticsearch contains only searchable station text.

## Run locally

```powershell
docker compose up -d --wait postgres redis elasticsearch
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn spring-boot:run
```

Elasticsearch is included in the existing `docker-compose.yml`, `docker-compose.demo.yml`,
`start-dev.bat`, `start-demo.bat`, and `scripts/demo-start.ps1`. No extra Spring/Maven profile
or Elasticsearch-specific Compose file is needed. Use the normal launchers:

* Dev: run `start-dev.bat`, then start the backend in the IDE as usual.
* Full stack demo: run `start-demo.bat`; backend waits for ES health before starting.

On startup, the backend rebuilds `chargeops-stations-demo` from existing nondeleted DB stations.
The startup log reports the indexed count. Start the existing driver frontend as usual:

```powershell
cd D:\Desktop\Workspace\final-year-project\chargeops-frontend\chargeops-driver-mobile
npm run web
```

Use real API mode (`EXPO_PUBLIC_USE_MOCKS` must not be `true`) and the existing discovery search box.
For a quick unauthenticated demo, open http://localhost:8082/elasticsearch-demo.html.
This frontend page has no mock fallback and shows errors from the real backend.
No frontend contract or response shape changes are needed. Search HTTP requests use:

```http
GET /api/v1/stations?query=Vincom&page=1&size=12
```

Nonblank keywords use ES `multi_match` against `name^3`, `address`, `code^2`.
DB discovery rechecks current station/license eligibility and every existing filter.
Keyword results use relevance order; blank keywords retain existing DB sorting.
This simple integration does not honor nearest/cheapest ordering for keyword results.

## Inspect the concepts

```powershell
Invoke-RestMethod http://localhost:9200/chargeops-stations-demo/_mapping
$body = '{"query":{"multi_match":{"query":"Vincom","fields":["name^3","address","code^2"]}}}'
Invoke-RestMethod http://localhost:9200/chargeops-stations-demo/_search -Method Post -ContentType application/json -Body $body
```

* Index: `chargeops-stations-demo`, a collection of search documents.
* Document: station ID, name, address, code; its ES ID equals its DB UUID.
* Mapping: IDs use `keyword`; searchable strings use `text` and the standard analyzer.
* Analyzer: splits/normalizes text into searchable terms. No custom Vietnamese analyzer in this milestone.
* Query: `multi_match` searches several fields; `name^3` boosts station names.
* Relevance: `_score` determines keyword result order.

## Reindex

With an Admin access token, call `POST /api/v1/admin/stations/search-index/reindex`.
It deletes/recreates only this demo index, bulk indexes current DB rows and refreshes it.
Without a token, restarting the demo backend also reindexes automatically.
There is no automatic DB → ES sync yet; reindex after station text changes.

## Deliberate demo limits

Single local node, max 10,000 station documents. ES listens only on localhost with security disabled.
Keyword search reads eligible DB cards in batches, intersects with ES IDs, and then paginates.
This favors minimal changes over performance. Reindex briefly interrupts searches.
An ES outage returns an API error, never a silent SQL fallback. Existing frontend mock fallbacks
can still show sample cards on API failure: check Network/API responses when validating the demo.
Configuration is in `application.yml` and `.env.demo.example`:
`ELASTICSEARCH_ENABLED=true`, `ELASTICSEARCH_REINDEX_ON_STARTUP=true`, and
`ELASTICSEARCH_URL=http://localhost:9200` for a host backend. The Docker backend uses
`http://elasticsearch:9200` automatically. Tests disable search via `application-test.yml`.
Set `ELASTICSEARCH_ENABLED=false` to restore the original database discovery path.

The root `.env` is imported explicitly by Spring Boot as a properties file and is the
default input for Compose/launchers. Use `.env.example` for local setup or
`.env.demo.example` for the public demo. Neither template contains real secrets.
`.env.demo` is not loaded automatically; Compose needs `--env-file .env.demo` to use it.
OS environment variables and command-line properties still override file config.
Container hostnames for DB/Redis/ES are provided by `docker-compose.demo.yml`.

STOP for milestone 1: local ES running, existing data indexed, name/address search working in the UI.
DB → ES sync and advanced search are follow-up work.
