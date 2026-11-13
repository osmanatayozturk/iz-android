# Bundled OSM preset catalogue

The catalogue projects OpenStreetMap iD Tagging Schema v7.3.0, commit
`f4cd7759d2ef509ae0360662988208d16a21374c`, into point/area rules and Turkish/source
names, aliases and search terms. Original upstream files and ISC license are
preserved under `upstream/`; the Android asset includes no NSI, icons or fonts.

From the repository root, with an existing Node.js installation, run:

```sh
node --test tools/osm-catalogue/test/catalogue.test.mjs
node tools/osm-catalogue/build.mjs --check
```

Both checks are offline. The second verifies the seven pinned Git blobs,
reproducibility with reversed input ordering, and byte equality against
`app/src/main/assets/osm-light-catalogue-v1.json`. Expected SHA-256:
`7759ee8364771334e4c24b9817ee6c7b354fc670338c92eecca6696cf74aeb56`.

Without `--check`, the generator writes a fresh `bundle/` beside this file for
review; it does not replace Android assets. Do not commit this generated scratch
directory. `provenance.json` records the original vendored bundle, including its
reference-adapter hashes and original package layout; fresh bundle provenance
records the integrated adapter paths and hashes.

Production Kotlin adapter and tests live in `app/src/main/java` and
`app/src/test/java` under `org/iz/navigation/osmcatalog`. The adapter adds
conservative positive-area evidence checks; point/area tag recipes and frozen
draft snapshots remain separately tested. Generator tests do not establish
Android, map UI, Room, device or live OSM write acceptance.
