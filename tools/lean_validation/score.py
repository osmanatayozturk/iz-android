"""Score time-aligned handlebar estimates against independent chassis curves."""
import argparse
import json
import math
import sys
from pathlib import Path


def require(condition, message):
    if not condition:
        raise ValueError(message)


def validate_intervals(values, label):
    require(isinstance(values, list), f'{label} must be a list')
    end = -1
    for row in values:
        start, stop = row['startMs'], row['endMs']
        require(type(start) is int and type(stop) is int and 0 <= start < stop,
                f'{label}: invalid interval')
        require(start >= end, f'{label}: overlapping or unordered intervals')
        end = stop


def peak(row):
    value = row['signedPeakDegrees']
    require(type(value) in (int, float) and math.isfinite(value) and 0 < abs(value) <= 90,
            'Expected a nonzero finite signed peak between -90 and 90 degrees (inclusive)')
    return value


def score(data):
    source = data['source']
    require(source in ('synthetic', 'field'), 'Unknown source')
    require(isinstance(data['rides'], list) and data['rides'], 'Missing validation rides')
    tuning = data['tuningRideIds']
    require(isinstance(tuning, list) and all(isinstance(x, str) and x.strip() for x in tuning),
            'Invalid calibration/tuning ride IDs')
    seen = set(tuning)
    groups = ('flowing', 'low_speed')
    directions = {g: {'left': 0, 'right': 0} for g in groups}
    matched_directions = {g: {'left': 0, 'right': 0} for g in groups}
    errors = {g: [] for g in groups}
    wrong = {g: 0 for g in groups}
    parked_windows = 0
    false_positives = 0
    parked_reports = 0
    for ride in data['rides']:
        ident = ride['id']
        require(isinstance(ident, str) and ident.strip() and ident not in seen,
                'Missing, repeated or tuning ride ID')
        seen.add(ident)
        references, estimates = ride['reference'], ride['reported']
        validate_intervals(references, 'reference')
        validate_intervals(estimates, 'reported')
        for ref in references:
            group = ref['kind']
            if group == 'parked_steering':
                parked_windows += 1
            else:
                require(group in groups, 'Unknown maneuver class')
                directions[group]['right' if peak(ref) > 0 else 'left'] += 1
        used = set()
        first = 0
        for shown in estimates:
            value = peak(shown)
            start, end = shown['startMs'], shown['endMs']
            while first < len(references) and references[first]['endMs'] <= start:
                first += 1
            candidates = []
            for i in range(first, len(references)):
                ref = references[i]
                if ref['startMs'] >= end:
                    break
                if ref['kind'] == 'parked_steering':
                    parked_reports += 1
                    continue
                overlap = min(end, ref['endMs']) - max(start, ref['startMs'])
                # Require at least half of both intervals. A partial transient cannot
                # claim a whole labeled bend, nor a long estimate multiple bends.
                if 2 * overlap >= max(end - start, ref['endMs'] - ref['startMs']):
                    candidates.append(i)
            if len(candidates) != 1 or candidates[0] in used:
                false_positives += 1
                continue
            index = candidates[0]
            used.add(index)
            ref = references[index]
            group, expected = ref['kind'], peak(ref)
            matched_directions[group]['right' if expected > 0 else 'left'] += 1
            errors[group].append(abs(value - expected))
            wrong[group] += (expected > 0) != (value > 0)

    stats = {}
    for group in groups:
        total, matched = sum(directions[group].values()), len(errors[group])
        within = sum(e <= 3 for e in errors[group])
        ordered = sorted(errors[group])
        stats[group] = {
            'reference': total, 'matched': matched, 'referenceByDirection': directions[group],
            'matchedByDirection': matched_directions[group], 'within3Degrees': within,
            'coveragePercent': 100 * matched / total if total else None,
            'within3DegreesPercent': 100 * within / matched if matched else None,
            'p95ErrorDegrees': ordered[math.ceil(.95 * matched) - 1] if matched else None,
            'wrongDirection': wrong[group],
            'meetsTargets': total >= 40 and min(matched_directions[group].values()) > 0
                and 2 * matched >= total and 100 * within >= 95 * matched and wrong[group] == 0,
        }
    sufficient = all(s['meetsTargets'] for s in stats.values()) and parked_windows > 0 and parked_reports == 0 and false_positives == 0
    return {'source': source, 'classes': stats, 'parkedSteeringWindows': parked_windows,
            'reportsDuringParkedSteering': parked_reports, 'unmatchedReports': false_positives,
            'numericCriteriaMet': sufficient,
            'fieldStatus': ('FIELD_REVIEW_REQUIRED' if sufficient else 'BELOW_TARGET') if source == 'field' else 'SYNTHETIC_ONLY'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    args = parser.parse_args()
    try:
        result = score(json.loads(args.input.read_text(encoding='utf-8-sig')))
    except (OSError, ValueError, KeyError, TypeError) as error:
        sys.exit(f'Invalid field-comparison input: {error}')
    print(json.dumps(result, indent=2, allow_nan=False))
    sys.exit(0 if result['numericCriteriaMet'] else 1)
