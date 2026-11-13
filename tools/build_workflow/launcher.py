"""Run an unmodified compact wrapper via runpy; no direct Gradle subprocess.

CLI: launcher.py --config CONFIG -- [exact wrapper argv, including optional --root].
API: final_argv(config, workflow, question) plans ONE four-task phone command;
invoke(config, wrapper_argv) executes it. Each invocation is a standalone command.
Timing is explicitly planned, not silently inserted into caller argv.
"""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import runpy
import sys
import tempfile
import uuid

try:
    from . import preflight
except ImportError:
    import preflight

IN_PROCESS = '-Pkotlin.compiler.execution.strategy=in-process'
TIMING_PROPERTY = '-PizTaskTimingFile='
TIMING_INIT = Path(__file__).with_name('timing.init.gradle').resolve()


class _Parser(argparse.ArgumentParser):
    def error(self, message): raise preflight.PreflightError('WRAPPER_ARGV_INVALID')


def final_argv(config, workflow, question, *, module='app', root=None, timing_id=None):
    """Plan one combined module invocation. No execution or filesystem writes."""
    values = preflight.normalize(config)
    if not re.fullmatch(r'[A-Za-z0-9_-]+', module): raise preflight.PreflightError('MODULE_NAME_INVALID')
    timing_id = timing_id or uuid.uuid4().hex
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', timing_id): raise preflight.PreflightError('TIMING_ID_INVALID')
    tasks = [f':{module}:testDebugUnitTest', f':{module}:lintDebug', f':{module}:assembleDebug', f':{module}:compileDebugAndroidTestKotlin']
    argv = ['--root', str(root)] if root is not None else []
    return argv + ['run', '--workflow', workflow, '--scope', 'broad', '--question', question, '--', str(values['repo'] / 'gradlew.bat'), *tasks, '--no-daemon', '--no-configuration-cache', IN_PROCESS, '-I', str(TIMING_INIT), TIMING_PROPERTY + str(values['output'] / 'timings' / ('tasks-' + timing_id + '.json'))]


def validate_argv(values, argv):
    if not isinstance(argv, (list, tuple)) or not all(isinstance(value, str) and '\x00' not in value for value in argv):
        raise preflight.PreflightError('WRAPPER_ARGV_INVALID')
    remaining = list(argv)
    if remaining[:1] == ['--root']:
        if len(remaining) < 3: raise preflight.PreflightError('WRAPPER_ROOT_MISSING')
        preflight.d_path(remaining[1]); remaining = remaining[2:]
    if not remaining or remaining[0] not in ['create', 'run', 'status', 'finish']:
        raise preflight.PreflightError('WRAPPER_OPERATION_INVALID')
    operation, rest = remaining[0], remaining[1:]
    parser = _Parser(add_help=False)
    if operation == 'create':
        parser.parse_args(rest); return operation
    parser.add_argument('--workflow', required=True)
    if operation != 'run':
        args = parser.parse_args(rest)
        if not args.workflow.strip(): raise preflight.PreflightError('WORKFLOW_REQUIRED')
        return operation
    parser.add_argument('--scope', required=True, choices=['targeted', 'broad'])
    parser.add_argument('--question', required=True)
    if '--' not in rest: raise preflight.PreflightError('GRADLE_ARGV_MISSING')
    split = rest.index('--'); args = parser.parse_args(rest[:split]); command = rest[split + 1:]
    if not args.workflow.strip() or not args.question.strip() or not command:
        raise preflight.PreflightError('WORKFLOW_QUESTION_COMMAND_REQUIRED')
    entry = Path(command[0]); entry = entry if entry.is_absolute() else values['repo'] / entry
    if entry.resolve() != values['repo'] / 'gradlew.bat': raise preflight.PreflightError('PROJECT_ENTRYPOINT_MISMATCH')
    if command.count(IN_PROCESS) != 1 or any(value.startswith('-Pkotlin.compiler.execution.strategy=') and value != IN_PROCESS for value in command):
        raise preflight.PreflightError('INPROCESS_SINGLE_TOKEN_REQUIRED')
    if '--no-daemon' not in command or '--daemon' in command or '--stop' in command:
        raise preflight.PreflightError('NO_DAEMON_REQUIRED')
    if '--no-configuration-cache' not in command or '--configuration-cache' in command:
        raise preflight.PreflightError('TIMING_REQUIRES_NO_CONFIGURATION_CACHE')
    init_paths = []
    for index, value in enumerate(command):
        if value in ('-I', '--init-script'):
            if index + 1 == len(command): raise preflight.PreflightError('INIT_PATH_MISSING')
            init_paths.append(preflight.d_path(command[index + 1]))
        elif value.startswith('--init-script='):
            init_paths.append(preflight.d_path(value.split('=', 1)[1]))
    if init_paths.count(TIMING_INIT) != 1 or not TIMING_INIT.is_file():
        raise preflight.PreflightError('EXPLICIT_TIMING_INIT_REQUIRED')
    timing = [value[len(TIMING_PROPERTY):] for value in command if value.startswith(TIMING_PROPERTY)]
    if len(timing) != 1: raise preflight.PreflightError('ONE_TIMING_OUTPUT_REQUIRED')
    path = preflight.d_path(timing[0])
    if not path.is_relative_to(values['output']) or path == values['output'] or path.exists():
        raise preflight.PreflightError('NEW_OWNED_TIMING_OUTPUT_REQUIRED', path)
    return operation


@contextmanager
def _context(values, argv):
    old_env, old_argv, old_path = dict(os.environ), list(sys.argv), list(sys.path)
    old_cwd, old_temp = Path.cwd(), tempfile.tempdir
    try:
        for key in ['TMP', 'TEMP', 'TMPDIR']: os.environ[key] = str(values['temp'])
        os.environ.update(JAVA_HOME=str(values['jdk']), ANDROID_HOME=str(values['sdk']), ANDROID_SDK_ROOT=str(values['sdk']), ANDROID_USER_HOME=str(values['signer'].parent), GRADLE_USER_HOME=str(values['gradle_home']), IZ_BUILD_WORKFLOW_OUTPUT=str(values['output']))
        tempfile.tempdir = None
        sys.argv = [str(values['wrapper']), *argv]
        sys.path.insert(0, str(values['wrapper'].parent))
        os.chdir(values['repo'])
        yield
    finally:
        os.chdir(old_cwd); sys.argv = old_argv; sys.path[:] = old_path; tempfile.tempdir = old_temp
        os.environ.clear(); os.environ.update(old_env)


def invoke(config, argv, *, executor=None):
    values = preflight.normalize(config)
    operation = validate_argv(values, argv)
    values = preflight.check(config, writable=operation in ('create', 'run'))
    with _context(values, argv):
        try: (executor or runpy.run_path)(str(values['wrapper']), run_name='__main__')
        except SystemExit as exc:
            if exc.code is None: return 0
            return exc.code if isinstance(exc.code, int) else 1
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    if '--' not in argv: parser.error('Pass exact wrapper arguments after --')
    split = argv.index('--'); options = parser.parse_args(argv[:split])
    try: return invoke(preflight.load_config(options.config), argv[split + 1:])
    except preflight.PreflightError as exc:
        print(json.dumps(exc.bounded())); return 2
    except Exception as exc:
        print(json.dumps({'stage': 'launcher', 'code': 'WRAPPER_EXCEPTION', 'type': type(exc).__name__})); return 2


if __name__ == '__main__': raise SystemExit(main())
