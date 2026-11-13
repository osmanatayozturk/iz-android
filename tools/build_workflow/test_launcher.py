"""Fixture-only launcher tests. No real wrapper lifecycle, JVM, SDK or Gradle execution."""
import contextlib
import importlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


class LauncherTests(unittest.TestCase):
    def setUp(self):
        here = Path(__file__).resolve().parent
        self.assertTrue((here / 'launcher.py').exists(), 'Launcher behavior is not implemented')
        self.assertTrue((here / 'preflight.py').exists(), 'Preflight behavior is not implemented')
        self.launcher = importlib.import_module('tools.build_workflow.launcher')
        self.preflight = importlib.import_module('tools.build_workflow.preflight')
        base = Path(os.environ.get('IZ_BUILD_TEST_ROOT', str(here.parents[1] / '.build-workflow-test-fixtures'))).resolve()
        self.assertEqual(base.drive.upper(), 'D:', 'Fixtures must remain on D:')
        base.mkdir(parents=True, exist_ok=True)
        self.tmp = tempfile.TemporaryDirectory(prefix='launcher-', dir=base)
        self.root = Path(self.tmp.name).resolve()
        self.assertEqual(self.root.parent, base)
        self.addCleanup(self.tmp.cleanup)
        self.config = {key: str(self.root / key) for key in ['repo', 'jdk', 'sdk', 'temp', 'output', 'gradle_home']}
        for value in self.config.values(): Path(value).mkdir()
        self.config.update(signer=str(self.root / 'signing/debug.keystore'), wrapper=str(self.root / 'wrapper/gradle_run.py'))
        files = {
            'repo/gradlew.bat': 'never execute', 'repo/app/build.gradle.kts': 'android { compileSdk = 36 }',
            'jdk/release': 'JAVA_VERSION="17.0.20.1"\n', 'jdk/bin/java.exe': 'fake JVM',
            'sdk/platforms/android-36/android.jar': 'fake platform', 'sdk/build-tools/36.0.0/aapt2.exe': 'fake tool',
            'signing/debug.keystore': 'fixture key bytes must survive', 'signing/debug.keystore.lock': '',
            'wrapper/gradle_run.py': 'raise AssertionError("Real wrapper execution is forbidden in tests")',
        }
        for relative, text in files.items():
            p = self.root / relative; p.parent.mkdir(parents=True, exist_ok=True); p.write_text(text)
        for child in self.preflight.CACHE_CHILDREN:
            (Path(self.config['gradle_home']) / child).mkdir()

    def run_args(self):
        return self.launcher.final_argv(self.config, 'opaque-id', 'Does the full phone module pass?', timing_id='fixture')

    def test_final_module_runs_once_and_preserves_all_wrapper_arguments(self):
        argv = self.run_args(); observed = []
        def executor(path, *, run_name):
            observed.append((path, run_name, list(sys.argv), Path.cwd(), os.environ['JAVA_HOME']))
            print('{"state":"completed","exit_status":0}')
            raise SystemExit(0)
        original_argv, original_cwd, original_env = list(sys.argv), Path.cwd(), dict(os.environ)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(self.launcher.invoke(self.config, argv, executor=executor), 0)
        self.assertEqual(len(observed), 1)
        self.assertEqual(observed[0][2], [str(Path(self.config['wrapper']).resolve()), *argv])
        self.assertEqual(observed[0][1], '__main__')
        self.assertEqual(observed[0][3], Path(self.config['repo']).resolve())
        self.assertEqual(observed[0][4], str(Path(self.config['jdk']).resolve()))
        nested = argv[argv.index('--') + 1:]
        self.assertEqual(nested[1:5], [':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebug', ':app:compileDebugAndroidTestKotlin'])
        self.assertIn('-Pkotlin.compiler.execution.strategy=in-process', nested)
        self.assertEqual(output.getvalue(), '{"state":"completed","exit_status":0}\n')
        self.assertEqual(sys.argv, original_argv); self.assertEqual(Path.cwd(), original_cwd)
        self.assertEqual(dict(os.environ), original_env)

    def test_create_status_finish_preserve_explicit_root_and_opaque_workflow(self):
        root = str(self.root / 'explicit wrapper root')
        for suffix in [['create'], ['status', '--workflow', 'unchanged-opaque'], ['finish', '--workflow', 'unchanged-opaque']]:
            argv = ['--root', root, *suffix]; seen = []
            self.assertEqual(self.launcher.invoke(self.config, argv, executor=lambda *a, **k: seen.append(list(sys.argv))), 0)
            self.assertEqual(seen, [[str(Path(self.config['wrapper']).resolve()), *argv]])

    def test_status_does_not_need_healthy_jdk_or_write_probes(self):
        (Path(self.config['jdk']) / 'release').unlink()
        with patch.object(self.preflight, 'probe_write', side_effect=AssertionError('status must be read-only')):
            self.assertEqual(self.launcher.invoke(self.config, ['status', '--workflow', 'old'], executor=lambda *a, **k: {}), 0)

    def test_tempfile_cache_and_environment_restore_even_when_wrapper_raises(self):
        old = tempfile.tempdir; tempfile.tempdir = 'C:/cached-temp-not-used'
        def executor(*a, **k):
            self.assertEqual(Path(tempfile.gettempdir()), Path(self.config['temp']).resolve())
            raise RuntimeError('fixture failure')
        try:
            with self.assertRaises(RuntimeError): self.launcher.invoke(self.config, self.run_args(), executor=executor)
            self.assertEqual(tempfile.tempdir, 'C:/cached-temp-not-used')
        finally: tempfile.tempdir = old

    def test_wrapper_exit_status_is_not_relabelled_as_pass(self):
        def executor(*a, **k): raise SystemExit(7)
        self.assertEqual(self.launcher.invoke(self.config, self.run_args(), executor=executor), 7)

    def test_split_inprocess_property_is_rejected_before_wrapper(self):
        argv = self.run_args(); index = argv.index('-Pkotlin.compiler.execution.strategy=in-process')
        argv[index:index + 1] = ['-Pkotlin', '.compiler.execution.strategy=in-process']
        with self.assertRaises(self.preflight.PreflightError):
            self.launcher.invoke(self.config, argv, executor=lambda *a, **k: self.fail('Invalid argv reached wrapper'))

    def test_missing_no_daemon_is_rejected_before_wrapper(self):
        argv = self.run_args(); argv.remove('--no-daemon')
        with self.assertRaises(self.preflight.PreflightError): self.launcher.invoke(self.config, argv, executor=lambda *a, **k: self.fail('Unexpected wrapper call'))

    def test_timing_requires_configuration_cache_disabled(self):
        argv = self.run_args(); argv.remove('--no-configuration-cache')
        with self.assertRaises(self.preflight.PreflightError): self.launcher.invoke(self.config, argv, executor=lambda *a, **k: self.fail('Unsupported timing mode reached wrapper'))

    def test_missing_signer_lock_is_not_created_or_worked_around(self):
        lock = Path(self.config['signer'] + '.lock'); lock.unlink()
        with self.assertRaises(self.preflight.PreflightError) as caught:
            self.launcher.invoke(self.config, self.run_args(), executor=lambda *a, **k: self.fail('Missing lock reached wrapper'))
        self.assertEqual(caught.exception.code, 'SIGNER_LOCK_MISSING'); self.assertFalse(lock.exists())

    def test_denied_existing_lock_stops_without_wrapper_or_permission_retry(self):
        with patch.object(self.preflight, 'probe_existing_lock', side_effect=PermissionError('fixture denied')):
            with self.assertRaises(self.preflight.PreflightError) as caught:
                self.launcher.invoke(self.config, self.run_args(), executor=lambda *a, **k: self.fail('Denied lock reached wrapper'))
        self.assertEqual(caught.exception.code, 'SIGNER_LOCK_ACCESS_DENIED')

    def test_supported_jdk_is_checked_before_creating_workflow(self):
        (Path(self.config['jdk']) / 'release').write_text('JAVA_VERSION="25.0.2"\n')
        with self.assertRaises(self.preflight.PreflightError) as caught:
            self.launcher.invoke(self.config, ['create'], executor=lambda *a, **k: self.fail('Unsupported JDK reached wrapper'))
        self.assertEqual(caught.exception.code, 'JDK17_REQUIRED')

    def test_missing_exact_compile_sdk_platform_stops_before_wrapper(self):
        (Path(self.config['sdk']) / 'platforms/android-36/android.jar').unlink()
        with self.assertRaises(self.preflight.PreflightError) as caught:
            self.launcher.invoke(self.config, self.run_args(), executor=lambda *a, **k: self.fail('Missing SDK reached wrapper'))
        self.assertEqual(caught.exception.code, 'SDK_PLATFORM_MISSING')

    def test_c_output_path_is_rejected_without_touching_it(self):
        config = {**self.config, 'output': 'C:/forbidden-build-output'}
        with self.assertRaises(self.preflight.PreflightError): self.preflight.check(config)

    def test_probes_preserve_existing_key_lock_and_cache_data(self):
        key = Path(self.config['signer']); original = key.read_bytes()
        cache = Path(self.config['gradle_home']) / 'caches/keep.bin'; cache.write_bytes(b'keep')
        self.preflight.check(self.config)
        self.assertEqual(key.read_bytes(), original); self.assertEqual(Path(str(key) + '.lock').read_bytes(), b'')
        self.assertEqual(cache.read_bytes(), b'keep')
        self.assertFalse(list(self.root.rglob('.iz-build-probe-*')))

    def test_timing_path_must_be_new_and_within_configured_output(self):
        argv = self.run_args(); prefix = '-PizTaskTimingFile='
        index = next(i for i, value in enumerate(argv) if value.startswith(prefix))
        file = Path(argv[index][len(prefix):]); file.parent.mkdir(parents=True, exist_ok=True); file.write_text('keep')
        with self.assertRaises(self.preflight.PreflightError): self.launcher.invoke(self.config, argv, executor=lambda *a, **k: self.fail('Timing overwrite reached wrapper'))
        self.assertEqual(file.read_text(), 'keep')
        argv[index] = prefix + str(self.root / 'outside.json')
        with self.assertRaises(self.preflight.PreflightError): self.launcher.invoke(self.config, argv, executor=lambda *a, **k: self.fail('Escaping timing path reached wrapper'))

    def test_nested_or_unknown_config_is_rejected(self):
        for config in [{**self.config, 'temp': {'path': self.config['temp']}}, {**self.config, 'secret': 'never accepted'}]:
            with self.assertRaises(self.preflight.PreflightError): self.preflight.check(config)


if __name__ == '__main__': unittest.main()
