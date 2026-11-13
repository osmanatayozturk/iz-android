import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { compileCatalogue, canonicalJson, applyTypeRecipe, matchesPreset, searchCatalogue } from '../catalogue.mjs';

const pin = { repository: 'openstreetmap/id-tagging-schema', version: '7.3.0', commit: 'f4cd7759d2ef509ae0360662988208d16a21374c', license: 'ISC' };
const input = {
  cafe: { geometry: ['area', 'point'], tags: { amenity: 'cafe' } },
  pharmacy: { geometry: ['point', 'area'], tags: { amenity: 'pharmacy' } },
  bridge: { geometry: ['line', 'area'], tags: { man_made: 'bridge' }, addTags: { man_made: 'bridge', layer: '1' } },
  generic: { geometry: ['area'], tags: { building: '*' } },
  address: { geometry: ['point', 'area'], tags: { 'addr:*': '*' }, addTags: {}, removeTags: {} },
  hidden: { geometry: ['point'], tags: { amenity: 'old' }, searchable: false },
  road: { geometry: ['line'], tags: { highway: 'residential' } },
  '@templates/poi': { geometry: ['point'], tags: { '@template': 'poi' } },
  regional: { geometry: ['point'], tags: { amenity: 'special' }, locationSet: { include: ['us'] } },
};
const english = Object.fromEntries(Object.keys(input).map(id => [id, { name: id === 'cafe' ? 'Cafe' : id, aliases: id === 'cafe' ? ['Coffee House'] : [], terms: id === 'cafe' ? ['espresso'] : [] }]));
const turkish = { cafe: { name: 'Kafe', aliases: ['Kahve Evi'], terms: ['İçecek'] } };
const make = (p = input, en = english, tr = turkish) => compileCatalogue(p, en, tr, pin);
const entry = (id, catalogue = make()) => catalogue.presets.find(p => p.id === id);

test('point/area projection preserves stable IDs and geometry preference; lines/templates are excluded', () => {
  assert.deepEqual(make().presets.map(p => p.id), ['address', 'bridge', 'cafe', 'generic', 'hidden', 'pharmacy', 'regional']);
  assert.deepEqual(entry('cafe').geometry, ['area', 'point']);
  assert.deepEqual(entry('bridge').geometry, ['area']);
  assert.deepEqual(entry('bridge').sourceGeometry, ['line', 'area']);
});

test('Turkish names/aliases and original language search terms survive fallback independently', () => {
  const cafe = entry('cafe');
  assert.equal(cafe.name, 'Kafe');
  assert.equal(cafe.sourceName, 'Cafe');
  assert.deepEqual(cafe.aliases, { en: ['Coffee House'], tr: ['Kahve Evi'] });
  assert.equal(entry('pharmacy').name, 'pharmacy');
  assert.equal(entry('pharmacy').trName, null);
  for (const query of ['KAHVE EVİ', 'İÇECEK', 'Coffee House', 'espresso', 'amenity=cafe']) {
    assert.deepEqual(searchCatalogue(make(), query, 'point').map(p => p.id), ['cafe']);
  }
});

test('addTags/removeTags defaults are independent and explicit empty maps never fall through', () => {
  const cafe = entry('cafe');
  assert.deepEqual(cafe.addTags, { amenity: 'cafe' });
  assert.deepEqual(cafe.removeTags, { amenity: 'cafe' });
  assert.deepEqual(entry('bridge').removeTags, { layer: '1', man_made: 'bridge' });
  assert.deepEqual(entry('address').addTags, {});
  assert.deepEqual(entry('address').removeTags, {});
  assert.equal(entry('address').selectable, false);
});

test('area/line presets get an explicit area=yes recipe; point creation cannot use an area-only preset', () => {
  assert.deepEqual(applyTypeRecipe({}, null, entry('bridge'), 'area'), { area: 'yes', layer: '1', man_made: 'bridge' });
  assert.throws(() => applyTypeRecipe({}, null, entry('bridge'), 'point'), /geometry/i);
  assert.deepEqual(applyTypeRecipe({}, null, entry('cafe'), 'point'), { amenity: 'cafe' });
});

test('a wildcard compiles to match-any and ensure-yes, never a literal upload value', () => {
  const generic = entry('generic');
  assert.equal(matchesPreset(generic, { building: 'apartments' }, 'area'), true);
  assert.equal(matchesPreset(generic, {}, 'area'), false);
  assert.deepEqual(applyTypeRecipe({}, null, generic, 'area'), { building: 'yes' });
  assert.deepEqual(applyTypeRecipe({ building: 'apartments' }, null, generic, 'area'), { building: 'apartments' });
  assert.deepEqual(applyTypeRecipe({ building: '*' }, null, generic, 'area'), { building: 'yes' });
});

test('prefix wildcard matching is bounded to its namespace and cannot be selected for untagged creation', () => {
  const address = entry('address');
  assert.equal(matchesPreset(address, { 'addr:street': 'Test Street' }, 'point'), true);
  assert.equal(matchesPreset(address, { name: 'Test Street' }, 'point'), false);
  assert.throws(() => applyTypeRecipe({}, null, address, 'point'), /selectable/i);
});

test('type change preserves unrelated building/address/name/phone tags and does not mutate input', () => {
  const before = { amenity: 'cafe', building: 'apartments', 'addr:street': 'Test', name: 'Local', 'contact:phone': '+9000000000', note: 'unchanged' };
  const copy = structuredClone(before);
  assert.deepEqual(applyTypeRecipe(before, entry('cafe'), entry('pharmacy'), 'area'), { ...before, amenity: 'pharmacy' });
  assert.deepEqual(before, copy);
});

test('old recipe removal deletes only matched values; unrelated customized values remain', () => {
  const before = { man_made: 'bridge', layer: '3', name: 'Existing' };
  assert.deepEqual(applyTypeRecipe(before, entry('bridge'), entry('pharmacy'), 'area'), { amenity: 'pharmacy', layer: '3', name: 'Existing' });
  assert.deepEqual(applyTypeRecipe({ man_made: 'bridge', layer: '1' }, entry('bridge'), entry('pharmacy'), 'area'), { amenity: 'pharmacy' });
});

test('hidden presets remain matchable, never offered; regional restrictions require an explicit local decision', () => {
  assert.equal(matchesPreset(entry('hidden'), { amenity: 'old' }, 'point'), true);
  assert.equal(searchCatalogue(make(), 'hidden', 'point').length, 0);
  assert.equal(entry('regional').requiresLocationValidation, true);
  assert.deepEqual(entry('regional').locationSet, { include: ['us'] });
  assert.equal(searchCatalogue(make(), 'regional', 'point').length, 0);
  assert.equal(searchCatalogue(make(), 'regional', 'point', () => true)[0].id, 'regional');
});

test('canonical output and recipes do not depend on input map insertion order', () => {
  const reversed = Object.fromEntries(Object.entries(input).reverse());
  assert.equal(canonicalJson(make()), canonicalJson(make(reversed)));
  assert.equal(canonicalJson({ b: 2, a: { z: 1, y: 0 } }), '{"a":{"y":0,"z":1},"b":2}\n');
});

test('malformed tag operations and unresolved display-name references fail closed', () => {
  for (const preset of [
    { geometry: ['point'], tags: { amenity: 'cafe' }, addTags: { 'addr:*': '*' } },
    { geometry: ['point'], tags: { amenity: 'cafe' }, removeTags: { '*': '*' } },
    { geometry: ['point'], tags: { amenity: 1 } },
  ]) assert.throws(() => make({ bad: preset }, { bad: { name: 'Bad' } }), /tag|wildcard/i);
  assert.throws(() => make({ bad: { geometry: ['point'], tags: { amenity: 'cafe' } } }, { bad: { name: '{other}' } }), /name|reference/i);
});

test('the pinned catalogue has official Turkish cafe/teahouse names and stable IDs', () => {
  const presets = JSON.parse(readFileSync(new URL('../upstream/dist/presets.min.json', import.meta.url)));
  const en = JSON.parse(readFileSync(new URL('../upstream/dist/translations/en.min.json', import.meta.url))).en.presets.presets;
  const tr = JSON.parse(readFileSync(new URL('../upstream/dist/translations/tr.min.json', import.meta.url))).tr.presets.presets;
  const catalogue = make(presets, en, tr);
  assert.equal(entry('amenity/cafe', catalogue).name, 'Kafe');
  assert.equal(entry('amenity/cafe/teahouse', catalogue).name, 'Kahvehane');
  assert.ok(entry('amenity/cafe/teahouse', catalogue).aliases.tr.includes('Çay Ocağı'));
  assert.equal(entry('building/house', catalogue).geometry.includes('point'), false);
  assert.equal(entry('building/house', catalogue).addTags.building, 'house');
  assert.ok(catalogue.presets.length > 1000);
  assert.equal(catalogue.presets.some(p => p.id.startsWith('@templates/')), false);
});

test('the pinned vineyard removal includes crop=grape and wildcard grape_variety without deleting other crops', () => {
  const presets = JSON.parse(readFileSync(new URL('../upstream/dist/presets.min.json', import.meta.url)));
  const en = JSON.parse(readFileSync(new URL('../upstream/dist/translations/en.min.json', import.meta.url))).en.presets.presets;
  const tr = JSON.parse(readFileSync(new URL('../upstream/dist/translations/tr.min.json', import.meta.url))).tr.presets.presets;
  const catalogue = make(presets, en, tr);
  const old = entry('landuse/vineyard', catalogue), next = entry('amenity/cafe', catalogue);
  assert.deepEqual(applyTypeRecipe({ landuse: 'vineyard', crop: 'grape', grape_variety: 'merlot', name: 'Test' }, old, next, 'area'), { amenity: 'cafe', name: 'Test' });
  assert.equal(applyTypeRecipe({ landuse: 'vineyard', crop: 'apple' }, old, next, 'area').crop, 'apple');
});
