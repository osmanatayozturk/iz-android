"""Explicit local host-result reuse. Never executes a build or grants device authority."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import time

def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()

def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()

def snapshot(root, spec, now=None):
    root = Path(root).resolve(strict=True)
    identity = spec['identity']
    if not all(identity.get(k) for k in ['scope', 'variant', 'tasks', 'argv']):
        raise ValueError('Explicit scope/variant/tasks/exact effective argv required')
    if not all(isinstance(identity[k], list) and all(isinstance(x, str) and x for x in identity[k]) for k in ['tasks', 'argv']):
        raise ValueError('Tasks and argv must be nonempty string arrays')
    if len(set(identity['tasks'])) != len(identity['tasks']) or any(t not in identity['argv'] for t in identity['tasks']):
        raise ValueError('Every unique selected task must occur in effective argv')
    if not {'source', 'config', 'toolchain'} <= {x['kind'] for x in spec['inputs']}:
        raise ValueError('Explicit source, configuration and toolchain inputs required')
    files, labels = [], set()
    for entry in spec['inputs']:
        label = entry['label']
        if not isinstance(label, str) or not label or '/' in label or '\\' in label or label.casefold() in labels:
            raise ValueError('Input labels must be unique simple names')
        labels.add(label.casefold())
        path = Path(entry['path'])
        path = (path if path.is_absolute() else root / path).resolve(strict=True)
        entries = sorted(path.rglob('*')) if path.is_dir() else [path]
        selected = []
        for item in entries:
            if item.is_symlink() or (item.is_dir() and hasattr(item, 'is_junction') and item.is_junction()):
                raise ValueError('Explicit input trees cannot contain links/junctions')
            if item.is_file():
                resolved = item.resolve(strict=True)
                if path.is_dir() and not resolved.is_relative_to(path):
                    raise ValueError('Input file escaped selected root')
                relative = item.relative_to(path).as_posix() if path.is_dir() else item.name
                selected.append({'key': label + '/' + relative, 'kind': entry['kind'], 'bytes': item.stat().st_size, 'sha256': digest(item)})
        if not selected:
            raise ValueError('Input selection is empty: ' + label)
        files.extend(selected)
    files.sort(key=lambda x: x['key'])
    body = {'identity': identity, 'inputs': files}
    captured = time.time() if now is None else now
    if type(captured) not in (int, float) or not math.isfinite(captured):
        raise ValueError('Invalid snapshot clock')
    return {'schema': 1, 'root': str(root), 'capturedAt': captured, **body, 'fingerprint': hashlib.sha256(encoded(body)).hexdigest(), 'coverage': 'Caller explicitly owns completeness of relevant input selection; no automatic scope inference.'}

def validate_snapshot(value):
    body = {key: value[key] for key in ['identity', 'inputs']}
    if value.get('schema') != 1 or not body['inputs'] or hashlib.sha256(encoded(body)).hexdigest() != value.get('fingerprint'):
        raise ValueError('Invalid or modified input snapshot')

def terminal(before, after, status):
    validate_snapshot(before)
    validate_snapshot(after)
    if before['fingerprint'] != after['fingerprint'] or before['root'] != after['root']:
        raise ValueError('Inputs changed during execution')
    planning = {'--dry-run', '--help', '--version', '--status', '--stop', '--exclude-task', '-m', '-h', '-v', '-?', '-x'}
    for argument in before['identity']['argv'][1:]:
        option = argument.split('=', 1)[0]
        short_planning = argument.startswith('-') and not argument.startswith(('--', '-P', '-D')) and any(c in argument[1:] for c in 'mhv?x')
        if option in planning or short_planning:
            raise ValueError('Planning/information/task-exclusion invocation cannot become a reusable test PASS')
    run, result = status.get('run') or {}, status.get('result') or {}
    if status.get('state') != 'completed' or type(run.get('sequence')) is not int or run['sequence'] <= 0 or status.get('result_sequence') != run['sequence']:
        raise ValueError('Current completed wrapper result required; stale/running/uncertain results rejected')
    if type(result.get('exit_status')) is not int or result['exit_status'] != 0 or result.get('failed_tasks') or result.get('interrupted_signal') or result.get('launch_error'):
        raise ValueError('Failed, interrupted or launch-error result cannot be PASS')
    started, elapsed = run.get('started_at'), result.get('elapsed_seconds')
    if any(type(x) not in (int, float) or not math.isfinite(x) for x in [started, elapsed]) or elapsed < 0:
        raise ValueError('Bounded wrapper start/duration required')
    if not before['capturedAt'] <= started <= started + elapsed <= after['capturedAt']:
        raise ValueError('Snapshots do not bracket this wrapper run')
    if Path(run.get('cwd', '')).resolve() != Path(before['root']).resolve():
        raise ValueError('Wrapper source root does not match snapshot')
    if result.get('command') != ' '.join(before['identity']['argv']):
        raise ValueError('Exact effective task/argument identity unavailable or different; do not infer from truncated command')
    if not status.get('workflow'):
        raise ValueError('Workflow identity missing')

def seal(before, after, status, artifacts):
    terminal(before, after, status)
    if not artifacts:
        raise ValueError('Existing output artifacts/reports must be bound')
    outputs = []
    for path in artifacts:
        path = Path(path).resolve(strict=True)
        if not path.is_file():
            raise ValueError('Artifact is not a file')
        outputs.append({'path': str(path), 'bytes': path.stat().st_size, 'sha256': digest(path)})
    if len({x['path'].casefold() for x in outputs}) != len(outputs):
        raise ValueError('Duplicate artifact path')
    return {'schema': 1, 'kind': 'LOCAL_HOST_RESULT_REUSE', 'status': 'PASS', 'before': before, 'snapshot': after, 'wrapperStatus': status, 'artifacts': outputs, 'limit': 'Local evidence binding only; not signature authentication, device/runtime/release acceptance or automatic rerun authority.'}

def check(receipt, current):
    reasons = []
    try:
        if receipt.get('schema') != 1 or receipt.get('kind') != 'LOCAL_HOST_RESULT_REUSE' or receipt.get('status') != 'PASS':
            raise ValueError('Successful local host receipt required')
        terminal(receipt['before'], receipt['snapshot'], receipt['wrapperStatus'])
        validate_snapshot(current)
        if current['fingerprint'] != receipt['snapshot']['fingerprint']:
            reasons.append('Relevant source/configuration/toolchain/tasks/arguments changed')
        if not receipt['artifacts']:
            raise ValueError('No bound outputs')
        for item in receipt['artifacts']:
            path = Path(item['path'])
            if not path.is_file() or path.stat().st_size != item['bytes'] or digest(path) != item['sha256']:
                reasons.append('Missing or modified output: ' + str(path))
    except (ValueError, KeyError, TypeError, OSError) as error:
        reasons.append(str(error))
    return {'status': 'NOT_REUSABLE' if reasons else 'REUSABLE', 'reasons': reasons, 'action': 'Inspect changed inputs or failed evidence; no build is started and no blind retry is authorized.'}

def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='operation', required=True)
    snap = commands.add_parser('snapshot')
    snap.add_argument('--root', required=True)
    snap.add_argument('--spec', required=True)
    snap.add_argument('--out', required=True)
    record = commands.add_parser('seal')
    for key in ['before', 'after', 'status', 'out']:
        record.add_argument('--' + key, required=True)
    record.add_argument('--artifact', action='append', required=True)
    reuse = commands.add_parser('check')
    reuse.add_argument('--receipt', required=True)
    reuse.add_argument('--current', required=True)
    args = parser.parse_args(argv)
    try:
        if args.operation == 'snapshot':
            result = snapshot(args.root, read(args.spec))
        elif args.operation == 'seal':
            result = seal(read(args.before), read(args.after), read(args.status), args.artifact)
        else:
            result = check(read(args.receipt), read(args.current))
        if args.operation != 'check':
            target = Path(args.out).resolve()
            if target.drive.upper() != 'D:':
                raise ValueError('New evidence must be on D:')
            with target.open('x', encoding='utf-8') as stream:
                json.dump(result, stream, indent=2)
                stream.write('\n')
            print(json.dumps({'path': str(target), 'sha256': digest(target), 'status': result.get('status', 'SNAPSHOT')}))
        else:
            print(json.dumps(result))
        return 2 if result.get('status') == 'NOT_REUSABLE' else 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(json.dumps({'status': 'REJECTED', 'reason': str(error)}))
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
