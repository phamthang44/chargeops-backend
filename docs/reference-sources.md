# ChargeOps Reference Sources

This file records external sources that may be evaluated for future modules. A
listed source is not automatically an approved dependency or imported dataset.

## Vietnamese administrative units

- Source: [thanglequoc/vietnamese-provinces-database](https://github.com/thanglequoc/vietnamese-provinces-database)
- Recorded: 2026-08-13
- Intended use: Candidate local/offline reference data for Vietnamese province
  and commune-level address selectors and server-side code validation.
- Current decision: Deferred. E2 will first implement the basic station
  registration and approval flow without integrating this dataset or its GIS
  data.
- Formats advertised by the source: PostgreSQL, JSON, other SQL databases, and
  optional GIS datasets.
- Source metadata observed when recorded: 34 provinces and 3,321 commune-level
  units; data derived from the Vietnamese statistics authority's administrative
  unit API; MIT-licensed; independently maintained and not an official
  government repository.

Before integration, pin an explicit release or commit and re-check its schema,
effective administrative version, upstream attribution, license, and expected
province/commune counts. Do not consume the moving `master` branch directly in
builds or migrations.
