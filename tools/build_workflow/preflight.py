"""Windows D: build prerequisites. No executors, permission grants, installs or cache repair.

Flat config: repo,jdk,sdk,temp,output,gradle_home,signer,wrapper.
check(..., writable=False) is read-only and supports status/finish recovery.
"""
import argparse
import json
import os
from pathlib import Path
import re
import uuid

KEYS = frozenset('repo jdk sdk temp output gradle_home signer wrapper'.split())
CACHE_CHILDREN = ('.tmp', 'android', 'caches', 'daemon', 'kotlin-profile', 'native', 'notifications', 'workers', 'wrapper')


class PreflightError(ValueError):
    def __init__(self, code, path=None):
        self.code, self.path = code, str(path) if path is not None else None
        super().__init__(code)

    def bounded(self):
        result = {'stage': 'preflight', 'code': self.code}
        if self.path is not None: result['path'] = self.path[:512]
        return result


def d_path(value):
    if not isinstance(value, (str, Path)) or not str(value) or '\x00' in str(value):
        raise PreflightError('INVALID_PATH')
    lexical = Path(value)
    if not lexical.is_absolute() or lexical.drive.upper() != 'D:':
        raise PreflightError('D_PATH_REQUIRED', lexical)
    resolved = lexical.resolve()
    if resolved.drive.upper() != 'D:' or resolved == Path(resolved.anchor):
        raise PreflightError('D_RESOLVED_PATH_REQUIRED', lexical)
    return resolved


def normalize(config):
    if not isinstance(config, dict) or set(config) != KEYS or any(not isinstance(v, (str, Path)) for v in config.values()):
        raise PreflightError('FLAT_CONFIG_KEYS_REQUIRED')
    values = {key: d_path(value) for key, value in config.items() if key != 'wrapper'}
    wrapper = Path(config['wrapper'])
    if not wrapper.is_absolute() or wrapper.drive.upper() not in ('C:', 'D:') or wrapper.suffix != '.py':
        raise PreflightError('WRAPPER_PY_PATH_REQUIRED', wrapper)
    values['wrapper'] = wrapper.resolve()
    return values


def load_config(path):
    try:
        config = json.loads(Path(path).read_text(encoding='utf8'))
    except (OSError, ValueError) as exc:
        raise PreflightError('CONFIG_UNREADABLE', path) from exc
    normalize(config)
    return config


def readable(path, code):
    if not path.is_file(): raise PreflightError(code, path)
    try:
        with path.open('rb') as file: file.read(0)
    except OSError as exc: raise PreflightError(code, path) from exc


def probe_write(directory):
    """Create/delete only a unique zero-byte probe; never change existing content."""
    path = directory / ('.iz-build-probe-' + uuid.uuid4().hex)
    created = False
    try:
        with path.open('xb'): created = True
    finally:
        if created: path.unlink()


def probe_existing_lock(path):
    # Opening r+b verifies access without truncation, key reads, or lock-file creation.
    with path.open('r+b'): pass


def _property(path, key):
    if not path.is_file(): return None
    for line in path.read_text(encoding='utf8').splitlines():
        match = re.match(r'^\s*' + re.escape(key) + r'\s*=\s*(.*?)\s*$', line)
        if match: return match.group(1).replace('\\:', ':').replace('\\\\', '\\')
    return None


def check(config, *, writable=True):
    values = normalize(config)
    if not values['repo'].is_dir(): raise PreflightError('REPO_MISSING', values['repo'])
    readable(values['wrapper'], 'WRAPPER_UNREADABLE')
    if not writable: return values
    readable(values['repo'] / 'gradlew.bat', 'PROJECT_ENTRYPOINT_MISSING')
    release = values['jdk'] / 'release'
    readable(release, 'JDK_RELEASE_MISSING')
    if not re.search(r'^JAVA_VERSION="17(?:[.+-][^"]*)?"$', release.read_text(encoding='utf8'), re.M):
        raise PreflightError('JDK17_REQUIRED', release)
    readable(values['jdk'] / 'bin/java.exe', 'JDK_EXECUTABLE_MISSING')
    for properties in [values['repo'] / 'gradle.properties', values['gradle_home'] / 'gradle.properties']:
        java = _property(properties, 'org.gradle.java.home')
        if java is not None and d_path(java) != values['jdk']:
            raise PreflightError('GRADLE_JAVA_HOME_CONFLICT', properties)
    local_sdk = _property(values['repo'] / 'local.properties', 'sdk.dir')
    if local_sdk is not None and d_path(local_sdk) != values['sdk']:
        raise PreflightError('SDK_PATH_CONFLICT', values['repo'] / 'local.properties')
    build = values['repo'] / 'app/build.gradle.kts'
    readable(build, 'APP_BUILD_CONFIG_MISSING')
    sdk = re.search(r'\bcompileSdk\s*=\s*(\d+)', build.read_text(encoding='utf8'))
    if sdk is None: raise PreflightError('SDK_TARGET_UNRESOLVED', build)
    readable(values['sdk'] / ('platforms/android-' + sdk.group(1) + '/android.jar'), 'SDK_PLATFORM_MISSING')
    if not any(path.is_file() for path in (values['sdk'] / 'build-tools').glob('*/aapt2.exe')):
        raise PreflightError('SDK_BUILD_TOOLS_MISSING', values['sdk'] / 'build-tools')
    readable(values['signer'], 'SIGNER_UNREADABLE')
    lock = values['signer'].with_name(values['signer'].name + '.lock')
    if not lock.is_file(): raise PreflightError('SIGNER_LOCK_MISSING', lock)
    try: probe_existing_lock(lock)
    except OSError as exc: raise PreflightError('SIGNER_LOCK_ACCESS_DENIED', lock) from exc
    for key in ['temp', 'output']:
        try:
            values[key].mkdir(parents=True, exist_ok=True)
            probe_write(values[key])
        except OSError as exc: raise PreflightError('OUTPUT_ACCESS_DENIED', values[key]) from exc
    for child in CACHE_CHILDREN:
        path = values['gradle_home'] / child
        if not path.is_dir(): raise PreflightError('CACHE_DIR_MISSING', path)
        try: probe_write(path)
        except OSError as exc: raise PreflightError('CACHE_ACCESS_DENIED', path) from exc
    return values


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('config', type=Path)
    parser.add_argument('--write-probes', action='store_true')
    args = parser.parse_args(argv)
    try:
        check(load_config(args.config), writable=args.write_probes)
        print(json.dumps({'stage': 'preflight', 'status': 'PASS', 'writeProbes': args.write_probes}))
        return 0
    except PreflightError as exc:
        print(json.dumps(exc.bounded())); return 2


if __name__ == '__main__': raise SystemExit(main())
