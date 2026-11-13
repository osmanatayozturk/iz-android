import { readFileSync, writeFileSync, mkdirSync, copyFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { compileCatalogue, canonicalJson } from './catalogue.mjs';

const read = path => readFileSync(new URL(path, import.meta.url));
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const pin = JSON.parse(read('upstream/commit.json'));
const blobs = JSON.parse(read('upstream/git-blobs.json'));
const inputs = [];
for (const [path, expected] of Object.entries(blobs)) {
  const bytes = read(`upstream/${path}`);
  const gitBlob = createHash('sha1').update(Buffer.from(`blob ${bytes.length}\0`)).update(bytes).digest('hex');
  if (gitBlob !== expected) throw new Error(`Upstream Git blob mismatch: ${path}`);
  inputs.push({ path, url: `https://github.com/${pin.repository}/blob/${pin.sha}/${path}`,
    rawUrl: `https://raw.githubusercontent.com/${pin.repository}/${pin.sha}/${path}`, gitBlob, sha256: sha256(bytes), bytes: bytes.length });
}
const pkg = JSON.parse(read('upstream/package.json'));
if (pin.sha !== 'f4cd7759d2ef509ae0360662988208d16a21374c' || pkg.version !== '7.3.0' || pkg.license !== 'ISC') throw new Error('Unexpected upstream pin');
const presets = JSON.parse(read('upstream/dist/presets.min.json'));
const en = JSON.parse(read('upstream/dist/translations/en.min.json')).en.presets.presets;
const tr = JSON.parse(read('upstream/dist/translations/tr.min.json')).tr.presets.presets;
const catalogue = compileCatalogue(presets, en, tr, { repository: pin.repository, version: pkg.version, commit: pin.sha, license: pkg.license });
const generated = Buffer.from(canonicalJson(catalogue));
if (generated.length > 8 * 1024 * 1024) throw new Error('Catalogue exceeds bounded loader budget');
// Insertion order is not part of the schema. Rebuild from independently reversed upstream maps.
const reverse = object => Object.fromEntries(Object.entries(object).reverse());
const second = Buffer.from(canonicalJson(compileCatalogue(reverse(presets), reverse(en), reverse(tr), catalogue.upstream)));
if (!generated.equals(second)) throw new Error('Non-deterministic catalogue');
if (process.argv.includes('--check')) {
  if (!generated.equals(read('../../app/src/main/assets/osm-light-catalogue-v1.json'))) throw new Error('Bundled asset differs from pinned generator output');
  console.log(JSON.stringify({ verified: true, bytes: generated.length, sha256: sha256(generated), statistics: catalogue.stats }));
  process.exit(0);
}
mkdirSync(new URL('bundle/', import.meta.url), { recursive: true });
writeFileSync(new URL('bundle/osm-light-catalogue-v1.json', import.meta.url), generated);
copyFileSync(new URL('upstream/LICENSE.md', import.meta.url), new URL('bundle/LICENSE.id-tagging-schema.md', import.meta.url));
const notice = `iD Tagging Schema ${pkg.version}\nOpenStreetMap / iD Contributors\n${pin.url}\nISC license: see LICENSE.id-tagging-schema.md\nModified projection for Iz: point/area, Turkish plus source names/aliases, explicit tag recipes.\nNo icon/font/image assets are included. No exhaustive OSM-tag coverage claim.\n`;
writeFileSync(new URL('bundle/NOTICE.txt', import.meta.url), notice);
const provenance = { schemaVersion: 1, catalogue: { path: 'osm-light-catalogue-v1.json', bytes: generated.length,
  sha256: sha256(generated), format: catalogue.format, schemaVersion: catalogue.schemaVersion },
  upstream: { ...catalogue.upstream, tag: pin.tag, url: pin.url },
  inputs, statistics: catalogue.stats, reproducibility: 'Byte-identical canonical UTF-8 LF result from reversed input map order',
  generator: { path: 'catalogue.mjs', sha256: sha256(read('catalogue.mjs')) },
  tests: { path: 'test/catalogue.test.mjs', sha256: sha256(read('test/catalogue.test.mjs')) },
  kotlin: { namespace: 'org.iz.navigation.osmcatalog',
    source: { path: 'app/src/main/java/org/iz/navigation/osmcatalog/OsmPresetCatalog.kt', sha256: sha256(read('../../app/src/main/java/org/iz/navigation/osmcatalog/OsmPresetCatalog.kt')) },
    tests: { path: 'app/src/test/java/org/iz/navigation/osmcatalog/OsmPresetCatalogTest.kt', sha256: sha256(read('../../app/src/test/java/org/iz/navigation/osmcatalog/OsmPresetCatalogTest.kt')) },
    sourceIntegration: 'Integrated adapter; Android build and runtime acceptance reported separately' },
  limits: ['Point/area only; no geometry edits, vertex/line/relation support',
    'Templates omitted; searchable=false and empty addTags entries recognition-only',
    'Regional locationSet retained; selection requires an explicit local location validator',
    'Fields/defaults/deprecations/icons/categories/brand NSI are not expanded',
    'Generated data and reference adapter do not perform OSM writes, auth, version review, draft storage or favorites persistence'] };
writeFileSync(new URL('bundle/provenance.json', import.meta.url), canonicalJson(provenance));
console.log(JSON.stringify({ ...provenance.catalogue, ...catalogue.stats, upstream: catalogue.upstream }));
