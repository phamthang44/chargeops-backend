# Vietnamese administrative data

ChargeOps vendors the core PostgreSQL dataset from
[thanglequoc/vietnamese-provinces-database](https://github.com/thanglequoc/vietnamese-provinces-database).

- Pinned release: `v4.0.0`
- Core administrative version included by that release: `v3.1.0`
- Expected records: 34 provinces/municipalities and 3,321 wards/communes
- License: MIT, copyright (c) 2021 Thang Le Quoc
- Imported tables: `administrative_regions`, `administrative_units`, `provinces`, `wards`
- GIS boundary data: not imported yet

Flyway installs the dataset automatically:

1. `V3__create_vietnamese_administrative_units.sql` creates the reference tables.
2. `V4__seed_vietnamese_administrative_units_v4_0_0.sql` imports the pinned data.

Do not edit the seed migration after it has been applied. To upgrade the
administrative version, add a new Flyway migration and document the upstream
release, effective date, record counts, and any code remapping required for
stations already stored in ChargeOps.

The upstream GIS add-on is deliberately separate from the core code dataset.
ChargeOps already runs on PostGIS, so boundaries or point-in-polygon validation
can be introduced later without coupling the basic E2 station-registration flow
to a large geometry import.
