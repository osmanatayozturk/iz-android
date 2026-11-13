"""Read existing .class files with javap; never compile or execute tests.

API: check_signatures(javap, class_roots, selectors, timeout_seconds=30).
selectors is a JSON path or mapping with exactly these keys:
  tests: ["package.Class#method", ...] (nonempty)
  lifecycle: [{"selector": "package.Class#method",
               "annotation": "org.junit.Before" | "org.junit.After"}, ...]
  excludedTests: [{"selector": "package.Class#method", "reason": "..."}, ...]
excludedTests is optional; every entry must exist in an allocated class.
All discovered JUnit methods must be public, nonstatic, concrete ()V, even
excluded tests (an invalid method can prevent the whole class from running).
Only declared methods are checked; inherited/runner-specific discovery and
runtime behavior require a separate runner check. No recursive classpath scan.
Official contracts: docs.oracle.com/en/java/javase/17/docs/specs/man/javap.html
and junit.org/junit4/javadoc/4.13.2/org/junit/{Test,Before,After}.html.
"""
import argparse
import hashlib
import json
import math
import pathlib
import re
import subprocess

_SELECTOR = re.compile(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*#[A-Za-z_$][\w$]*', re.ASCII)
_ROLES = {'org.junit.Test', 'org.junit.Before', 'org.junit.After'}
_MAX_OUTPUT = 8 * 1024 * 1024


class _Invalid(ValueError):
    def __init__(self, code, detail):
        self.code = code
        super().__init__(detail)


def _require(condition, code, detail):
    if not condition:
        raise _Invalid(code, detail)


def _unique_object(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, 'INVALID_SELECTORS', 'Duplicate JSON key: ' + key)
        result[key] = value
    return result


def _scope(value):
    if isinstance(value, (str, pathlib.Path)):
        path = pathlib.Path(value)
        _require(path.stat().st_size <= 1024 * 1024, 'INVALID_SELECTORS', 'Selectors JSON exceeds 1MiB')
        value = json.loads(path.read_text(encoding='utf-8-sig'), object_pairs_hook=_unique_object)
    _require(isinstance(value, dict) and {'tests', 'lifecycle'} <= value.keys()
             and value.keys() <= {'tests', 'lifecycle', 'excludedTests'}, 'INVALID_SELECTORS', 'Expected tests/lifecycle/excludedTests schema')
    tests, lifecycle, excluded = value['tests'], value['lifecycle'], value.get('excludedTests', [])
    _require(all(isinstance(x, list) for x in (tests, lifecycle, excluded)) and bool(tests), 'INVALID_SELECTORS', 'Nonempty tests and separate lifecycle/excluded lists required')
    _require(len(tests) + len(lifecycle) + len(excluded) <= 4096, 'INVALID_SELECTORS', 'Too many selectors')
    expected, reasons = {}, {}

    def add(selector, role):
        _require(isinstance(selector, str) and _SELECTOR.fullmatch(selector) is not None, 'INVALID_SELECTORS', 'Invalid Class#method selector')
        _require(selector not in expected, 'DUPLICATE_SELECTOR', selector)
        expected[selector] = role

    for selector in tests:
        add(selector, 'org.junit.Test')
    for entry in lifecycle:
        _require(isinstance(entry, dict) and entry.keys() == {'selector', 'annotation'}
                 and entry['annotation'] in ('org.junit.Before', 'org.junit.After'), 'INVALID_SELECTORS', 'Lifecycle needs explicit Before/After annotation')
        add(entry['selector'], entry['annotation'])
    allocated = {x.split('#')[0] for x in expected}
    for entry in excluded:
        _require(isinstance(entry, dict) and entry.keys() == {'selector', 'reason'}
                 and isinstance(entry['reason'], str) and bool(entry['reason'].strip()), 'INVALID_SELECTORS', 'Excluded test needs a nonblank reason')
        add(entry['selector'], 'org.junit.Test')
        _require(entry['selector'].split('#')[0] in allocated, 'INVALID_SELECTORS', 'Exclusion cannot allocate another class')
        reasons[entry['selector']] = entry['reason']
    _require(len(allocated) <= 256, 'INVALID_SELECTORS', 'Too many allocated classes')
    return expected, reasons, sorted(allocated)


def _members(output, class_name):
    identities = re.findall(r'^\s*this_class:\s*#\d+\s+//\s+(\S+)\s*$', output, re.M)
    _require(identities == [class_name.replace('.', '/')], 'CLASS_IDENTITY_MISMATCH', class_name)
    lines = output.splitlines()
    starts = [i for i, line in enumerate(lines) if line == '{']
    ends = [i for i, line in enumerate(lines) if line == '}']
    _require(len(starts) == len(ends) == 1 and starts[0] < ends[0], 'MALFORMED_JAVAP', class_name + ': missing class body')
    class_flags = [set(re.findall(r'ACC_\w+', line)) for line in lines[:starts[0]] if line.startswith('  flags: ')]
    _require(len(class_flags) == 1 and 'ACC_PUBLIC' in class_flags[0]
             and not class_flags[0] & {'ACC_ABSTRACT', 'ACC_INTERFACE'}, 'INVALID_TEST_CLASS', class_name + ': public concrete class required')
    class_visible = False
    for line in lines[ends[0] + 1:]:
        if line and not line.startswith(' '):
            class_visible = line == 'RuntimeVisibleAnnotations:'
        elif class_visible and re.fullmatch(r'    org\.junit\.Ignore(?:\(.*)?', line):
            raise _Invalid('IGNORED_CLASS', class_name)
    result, current, visible = [], None, False
    for line in lines[starts[0] + 1:ends[0]]:
        if re.match(r'^  \S', line):
            current, visible = None, False
            if '(' in line and line.rstrip().endswith(';'):
                match = re.search(r'([^\s(]+)\([^)]*\)(?: throws .+)?;$', line)
                _require(match is not None, 'MALFORMED_JAVAP', class_name + ': unrecognized method declaration')
                current = {'name': match.group(1), 'descriptors': [], 'flags': [], 'annotations': []}
                result.append(current)
            continue
        if current is None:
            continue
        if re.match(r'^    \S', line):
            visible = line == '    RuntimeVisibleAnnotations:'
            if line.startswith('    descriptor: '):
                current['descriptors'].append(line.split(': ', 1)[1].strip())
            if line.startswith('    flags: '):
                current['flags'].append(set(re.findall(r'ACC_\w+', line)))
        elif visible:
            match = re.fullmatch(r'        ([\w.$]+)(?:\(.*)?', line)
            if match:
                current['annotations'].append(match.group(1))
    for member in result:
        _require(len(member['descriptors']) == len(member['flags']) == 1, 'MALFORMED_JAVAP', class_name + '#' + member['name'] + ': descriptor/flags missing or repeated')
    return result


def check_signatures(javap, class_roots, selectors, *, timeout_seconds=30):
    """Return a compact PASS/FAIL receipt. Pass an executable, never shell text.

    Roots are existing directory outputs, not JARs. Exactly one matching class
    must exist across roots; duplicate copies are rejected even if byte-equal.
    A result proves only the inspected compiled signatures, not actual runner
    discovery. Callers must bind the supplied class/source/APK identities.
    """
    receipt = {'status': 'FAIL_COMPILED_SIGNATURES', 'runtimeDiscovery': 'NOT_RUN',
               'runtimeTests': 'NOT_RUN', 'classes': [], 'failures': []}
    try:
        expected, exclusions, classes = _scope(selectors)
        _require(isinstance(javap, (str, pathlib.Path)) and bool(str(javap).strip()), 'INVALID_INPUT', 'javap executable path required')
        _require(isinstance(timeout_seconds, (int, float)) and not isinstance(timeout_seconds, bool)
                 and math.isfinite(timeout_seconds) and 0 < timeout_seconds <= 120, 'INVALID_INPUT', 'timeout must be >0 and <=120 seconds')
        _require(isinstance(class_roots, (list, tuple)) and bool(class_roots), 'INVALID_INPUT', 'Existing compiled class root directories required')
        roots = [pathlib.Path(x).resolve(strict=True) for x in class_roots]
        _require(all(x.is_dir() for x in roots) and len(set(roots)) == len(roots), 'INVALID_INPUT', 'Class roots must be distinct directories')
        receipt['counts'] = {'tests': len(expected) - len(exclusions) - sum(x != 'org.junit.Test' for x in expected.values()),
                             'lifecycle': sum(x != 'org.junit.Test' for x in expected.values()), 'excludedTests': len(exclusions), 'classes': len(classes)}
        receipt['tests'] = [s for s, role in expected.items() if role == 'org.junit.Test' and s not in exclusions]
        receipt['lifecycle'] = [{'selector': s, 'annotation': role} for s, role in expected.items() if role != 'org.junit.Test']
        receipt['excludedTests'] = [{'selector': s, 'reason': reason} for s, reason in exclusions.items()]
        for class_name in classes:
            relative = pathlib.Path(*class_name.split('.')).with_suffix('.class')
            found = [(root, root / relative) for root in roots if (root / relative).is_file()]
            _require(bool(found), 'MISSING_CLASS', class_name)
            _require(len(found) == 1, 'DUPLICATE_CLASS', class_name)
            root, path = found[0]
            path = path.resolve(strict=True)
            _require(path.is_relative_to(root), 'CLASS_OUTSIDE_ROOT', class_name)
            before = hashlib.sha256(path.read_bytes()).hexdigest()
            argv = [str(javap), '-v', '-p', str(path)]
            process = subprocess.run(argv, shell=False, capture_output=True, text=True,
                                     encoding='utf-8', errors='strict', timeout=timeout_seconds)
            _require(process.returncode == 0, 'JAVAP_FAILED', class_name + ': exit ' + str(process.returncode))
            _require(not process.stderr.strip(), 'JAVAP_STDERR', class_name + ': nonempty stderr')
            _require(len(process.stdout.encode('utf-8')) <= _MAX_OUTPUT, 'JAVAP_OUTPUT_LIMIT', class_name)
            _require(hashlib.sha256(path.read_bytes()).hexdigest() == before, 'CLASS_CHANGED', class_name)
            members = _members(process.stdout, class_name)
            by_name = {}
            for member in members:
                by_name.setdefault(member['name'], []).append(member)
            actual = {}
            for member in members:
                roles = [x for x in member['annotations'] if x in _ROLES]
                if not roles:
                    continue
                selector = class_name + '#' + member['name']
                _require(len(roles) == 1, 'ANNOTATION_CONFLICT', selector)
                flags = member['flags'][0]
                _require(member['descriptors'] == ['()V'] and 'ACC_PUBLIC' in flags
                         and not flags & {'ACC_STATIC', 'ACC_ABSTRACT', 'ACC_SYNTHETIC', 'ACC_BRIDGE'}, 'INVALID_SIGNATURE', selector + ': public concrete instance ()V required')
                _require(len(by_name[member['name']]) == 1, 'DUPLICATE_METHOD', selector)
                _require(selector in expected, 'UNALLOCATED_TEST' if roles[0] == 'org.junit.Test' else 'UNALLOCATED_LIFECYCLE', selector)
                _require(expected[selector] == roles[0], 'WRONG_ANNOTATION', selector)
                _require('org.junit.Ignore' not in member['annotations'] or selector in exclusions, 'IGNORED_TEST', selector)
                actual[selector] = roles[0]
            for selector, role in expected.items():
                if selector.split('#')[0] != class_name:
                    continue
                name = selector.split('#')[1]
                _require(name in by_name, 'MISSING_METHOD', selector)
                _require(len(by_name[name]) == 1, 'DUPLICATE_METHOD', selector)
                _require(actual.get(selector) == role, 'MISSING_ANNOTATION', selector + ': expected ' + role)
            receipt['classes'].append({'class': class_name, 'path': str(path), 'sha256': before,
                                       'declaredJUnitMethods': sorted(actual), 'javapArgv': argv})
        receipt['status'] = 'PASS_COMPILED_SIGNATURES'
    except _Invalid as exc:
        receipt['failures'].append({'code': exc.code, 'detail': str(exc)})
    except subprocess.TimeoutExpired:
        receipt['failures'].append({'code': 'JAVAP_TIMEOUT', 'detail': 'javap exceeded per-class timeout'})
    except (OSError, UnicodeError, ValueError, TypeError) as exc:
        receipt['failures'].append({'code': 'INPUT_OR_TOOL_ERROR', 'detail': type(exc).__name__})
    return receipt


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--javap', required=True)
    parser.add_argument('--class-root', action='append', required=True)
    parser.add_argument('--selectors', required=True)
    parser.add_argument('--timeout-seconds', type=float, default=30)
    args = parser.parse_args(argv)
    result = check_signatures(args.javap, args.class_root, args.selectors, timeout_seconds=args.timeout_seconds)
    print(json.dumps(result, indent=2))
    return 0 if result['status'] == 'PASS_COMPILED_SIGNATURES' else 1


if __name__ == '__main__':
    raise SystemExit(main())
