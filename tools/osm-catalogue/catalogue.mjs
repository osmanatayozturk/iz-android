// ISC upstream data semantics: pinned upstream/SCHEMA.md, especially tags/addTags/removeTags/geometry.
// This dependency-free reference adapter lives in the evidence bundle, not in the Android source tree.
const own = (object, key) => Object.hasOwn(object, key);
const order = (a, b) => a < b ? -1 : a > b ? 1 : 0;
const sortedObject = object => Object.fromEntries(Object.entries(object).sort(([a], [b]) => order(a, b)));
const unique = values => [...new Set(values)].sort(order);
const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);

export function canonicalJson(value) {
  function canonical(value) {
    if (Array.isArray(value)) return value.map(canonical);
    if (object(value)) return Object.fromEntries(Object.keys(value).sort(order).map(key => [key, canonical(value[key])]));
    return value;
  }
  return JSON.stringify(canonical(value)) + '\n';
}

function tagMap(value, purpose) {
  if (!object(value)) throw new Error(`Invalid ${purpose} tag map`);
  for (const [key, val] of Object.entries(value)) {
    if (!key || [...key].length > 255 || /[\u0000-\u0020\u007f]/u.test(key) ||
        typeof val !== 'string' || !val || [...val].length > 255 || /[\u0000-\u001f\u007f]/u.test(val)) {
      throw new Error(`Invalid ${purpose} tag: ${key}`);
    }
    if (key.includes('*') && (purpose !== 'match' || !/^[^*]+:\*$/.test(key) || val !== '*')) {
      throw new Error(`Unsupported wildcard tag key: ${key}`);
    }
    if (val.includes('*') && val !== '*') throw new Error(`Unsupported wildcard tag value: ${key}`);
  }
  return sortedObject(value);
}

function list(value, field) {
  if (value === undefined) return [];
  if (!Array.isArray(value) || value.some(s => typeof s !== 'string' || !s.trim())) throw new Error(`Invalid ${field}`);
  return unique(value);
}

function label(value, id) {
  if (typeof value !== 'string' || !value.trim() || /^\{.*\}$/.test(value)) throw new Error(`Unresolved display name/reference: ${id}`);
  return value;
}

function addOps(tags) {
  return Object.entries(tags).map(([key, value]) => value === '*'
    ? { op: 'ensureAny', key, defaultValue: 'yes' }
    : { op: 'set', key, value });
}

function removeOps(tags) {
  return Object.entries(tags).map(([key, value]) => value === '*'
    ? { op: 'removeAny', key }
    : { op: 'removeIfEquals', key, value });
}

function matchOps(tags) {
  return Object.entries(tags).map(([key, value]) => key.endsWith(':*')
    ? { op: 'prefixAny', keyPrefix: key.slice(0, -1) }
    : value === '*' ? { op: 'any', key } : { op: 'equals', key, value });
}

export function normalizeSearch(value) {
  return value.replace(/İ/g, 'i').replace(/I/g, 'ı').toLowerCase()
    .normalize('NFKD').replace(/\p{M}/gu, '').replace(/ı/g, 'i').replace(/\s+/gu, ' ').trim();
}

export function compileCatalogue(presets, english, turkish, pin) {
  if (!object(presets) || !object(english) || !object(turkish)) throw new Error('Catalogue maps required');
  const records = [];
  let excludedTemplates = 0, excludedGeometry = 0;
  for (const id of Object.keys(presets).sort(order)) {
    const preset = presets[id];
    if (id.startsWith('@templates/')) { excludedTemplates++; continue; }
    if (!Array.isArray(preset.geometry)) throw new Error(`Geometry required: ${id}`);
    const geometry = preset.geometry.filter(g => g === 'point' || g === 'area');
    if (!geometry.length) { excludedGeometry++; continue; }
    const sourceName = label(english[id]?.name, id);
    const local = turkish[id] ?? {};
    const trName = local.name ? label(local.name, id) : null;
    const aliases = { en: list(english[id]?.aliases, 'English aliases'), tr: list(local.aliases, 'Turkish aliases') };
    const terms = { en: list(english[id]?.terms, 'English terms'), tr: list(local.terms, 'Turkish terms') };
    const tags = tagMap(preset.tags, 'match');
    const addTags = tagMap(own(preset, 'addTags') ? preset.addTags : preset.tags, 'add');
    const removeTags = tagMap(own(preset, 'removeTags') ? preset.removeTags : addTags, 'remove');
    const matchScore = preset.matchScore ?? 1;
    if (!Number.isFinite(matchScore) || matchScore < 0) throw new Error(`Invalid match score: ${id}`);
    const locationSet = preset.locationSet ? JSON.parse(canonicalJson(preset.locationSet)) : null;
    const requiresLocationValidation = locationSet !== null &&
      ((locationSet.include ?? []).some(region => region !== 'Planet') || (locationSet.exclude ?? []).length > 0);
    const selectable = preset.searchable !== false && Object.keys(addTags).length > 0 &&
      !(locationSet?.exclude ?? []).includes('Planet');
    const variants = Object.fromEntries(geometry.map(kind => {
      const additions = addOps(addTags);
      if (kind === 'area' && preset.geometry.includes('line') && !additions.some(op => op.key === 'area')) {
        additions.push({ op: 'set', key: 'area', value: 'yes' });
      }
      return [kind, { add: additions, remove: removeOps(removeTags) }];
    }));
    const searchTerms = unique([sourceName, ...(trName ? [trName] : []), ...aliases.en, ...aliases.tr,
      ...terms.en, ...terms.tr, ...Object.entries(tags).map(([key, value]) => `${key}=${value}`),
      ...Object.entries(addTags).map(([key, value]) => `${key}=${value}`)]);
    records.push({ id, sourceName, trName, name: trName ?? sourceName, aliases, terms, searchTerms,
      normalizedSearch: unique(searchTerms.map(normalizeSearch)).join('\n'),
      sourceGeometry: [...preset.geometry], geometry, searchable: preset.searchable !== false, selectable,
      locationSet, requiresLocationValidation, matchScore, tags, addTags, removeTags,
      declared: { addTags: own(preset, 'addTags'), removeTags: own(preset, 'removeTags') },
      match: matchOps(tags), variants });
  }
  return { format: 'iz.osm-light-catalogue', schemaVersion: 1, upstream: { ...pin },
    policy: { geometry: ['point', 'area'], wildcardWrite: 'ensureAny/preserve-nonempty-else-yes',
      regionalSelection: 'requires-local-validation', fieldDefaults: 'not-applied',
      exhaustiveFreeformOsmTags: false },
    stats: { sourcePresets: Object.keys(presets).length, includedPresets: records.length,
      excludedTemplates, excludedGeometry, turkishNames: records.filter(p => p.trName !== null).length,
      sourceNameFallback: records.filter(p => p.trName === null).length,
      point: records.filter(p => p.geometry.includes('point')).length,
      area: records.filter(p => p.geometry.includes('area')).length,
      selectable: records.filter(p => p.selectable).length,
      regional: records.filter(p => p.requiresLocationValidation).length }, presets: records };
}

export function matchesPreset(preset, tags, geometry) {
  if (!preset.geometry.includes(geometry)) return false;
  return preset.match.every(rule => rule.op === 'prefixAny'
    ? Object.entries(tags).some(([key, value]) => key.startsWith(rule.keyPrefix) && value !== '' && value !== '*')
    : rule.op === 'any' ? own(tags, rule.key) && tags[rule.key] !== '' && tags[rule.key] !== '*'
    : tags[rule.key] === rule.value);
}

/** Reference preview transform only. Android still owns authoritative version/geometry/account validation. */
export function applyTypeRecipe(existing, oldPreset, nextPreset, geometry, { locationValidated = false } = {}) {
  const recipe = nextPreset.variants[geometry];
  if (!recipe) throw new Error('Unsupported geometry');
  if (!nextPreset.selectable) throw new Error('Preset is not selectable');
  if (nextPreset.requiresLocationValidation && !locationValidated) throw new Error('Location validation required');
  if (oldPreset && !matchesPreset(oldPreset, existing, geometry)) throw new Error('Old preset no longer matches');
  const result = new Map(Object.entries(existing));
  if (oldPreset) for (const op of oldPreset.variants[geometry].remove) {
    if (op.op === 'removeAny' || result.get(op.key) === op.value) result.delete(op.key);
  }
  for (const op of recipe.add) {
    if (op.op === 'set') result.set(op.key, op.value);
    else if (!result.get(op.key) || result.get(op.key) === '*') result.set(op.key, op.defaultValue);
  }
  if ([...result].some(([key, value]) => key.includes('*') || value === '*')) throw new Error('Literal wildcard cannot be uploaded');
  return sortedObject(Object.fromEntries(result));
}

export function searchCatalogue(catalogue, query, geometry, locationAllowed = () => false) {
  const words = normalizeSearch(query).split(' ').filter(Boolean);
  return catalogue.presets.filter(preset => preset.selectable && preset.geometry.includes(geometry) &&
    (!preset.requiresLocationValidation || locationAllowed(preset.locationSet)) &&
    words.every(word => preset.normalizedSearch.includes(word)));
}
