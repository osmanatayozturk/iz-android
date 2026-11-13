import copy
import importlib.util
import os
from pathlib import Path
import tempfile
import unittest

HERE = Path(__file__).parent
FIXTURE_ROOT = Path(os.environ.get('IZ_BUILD_TEST_ROOT', str(Path(__file__).resolve().parents[2] / '.build-workflow-test-fixtures'))).resolve()
def temporary_fixture():
    if FIXTURE_ROOT.drive.upper() != 'D:':
        raise ValueError('Fixture root must be on D:')
    FIXTURE_ROOT.mkdir(parents=True, exist_ok=True)
    return tempfile.TemporaryDirectory(dir=FIXTURE_ROOT)
def module(name):
    spec = importlib.util.spec_from_file_location(name, HERE / (name + '.py'))
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj

class ReuseTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((HERE / 'reuse.py').exists(), 'Explicit input/result reuse tool is missing')
        self.tool = module('reuse')
        self.tmp = temporary_fixture()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        for name in ['src/A.kt', 'config.json', 'jdk/release', 'out/app.apk']:
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(name)
        self.spec = {'identity': {'scope': 'app-final-host', 'variant': 'debug', 'tasks': [':app:testDebugUnitTest'], 'argv': ['gradlew.bat', ':app:testDebugUnitTest', '--console=plain', '--no-scan']}, 'inputs': [{'label': 'app', 'kind': 'source', 'path': 'src'}, {'label': 'config', 'kind': 'config', 'path': 'config.json'}, {'label': 'jdk', 'kind': 'toolchain', 'path': 'jdk'}]}
        self.before = self.tool.snapshot(self.root, self.spec, now=10)
        self.after = self.tool.snapshot(self.root, self.spec, now=20)
        self.status = {'workflow': 'fixture-only', 'state': 'completed', 'run': {'sequence': 1, 'started_at': 11, 'cwd': str(self.root)}, 'result_sequence': 1, 'result': {'exit_status': 0, 'elapsed_seconds': 1, 'command': ' '.join(self.spec['identity']['argv']), 'failed_tasks': []}}
    def seal(self):
        return self.tool.seal(self.before, self.after, self.status, [self.root / 'out/app.apk'])
    def test_failed_or_stale_previous_result_cannot_be_sealed(self):
        for mutation in [lambda s: s.update(state='running'), lambda s: s.update(result_sequence=0), lambda s: s['result'].update(exit_status=1), lambda s: s['result'].update(command='another task')]:
            status = copy.deepcopy(self.status)
            mutation(status)
            with self.assertRaises(ValueError):
                self.tool.seal(self.before, self.after, status, [self.root / 'out/app.apk'])
    def test_same_inputs_and_existing_artifacts_reuse(self):
        self.assertEqual('REUSABLE', self.tool.check(self.seal(), self.after)['status'])
    def test_planning_or_excluded_task_cannot_reuse_old_outputs_as_pass(self):
        for flag in ['--dry-run', '-m', '--help', '--version', '--status', '--stop', '-x', '--exclude-task=:app:testDebugUnitTest']:
            spec = copy.deepcopy(self.spec)
            spec['identity']['argv'].append(flag)
            before = self.tool.snapshot(self.root, spec, now=10)
            after = self.tool.snapshot(self.root, spec, now=20)
            status = copy.deepcopy(self.status)
            status['result']['command'] = ' '.join(spec['identity']['argv'])
            with self.subTest(flag=flag), self.assertRaises(ValueError):
                self.tool.seal(before, after, status, [self.root / 'out/app.apk'])
    def test_added_source_and_toolchain_change_invalidate(self):
        receipt = self.seal()
        for name in ['src/B.kt', 'jdk/release']:
            (self.root / name).write_text('changed')
            self.assertEqual('NOT_REUSABLE', self.tool.check(receipt, self.tool.snapshot(self.root, self.spec, now=30))['status'])
    def test_missing_or_modified_artifact_is_not_pass(self):
        receipt = self.seal()
        (self.root / 'out/app.apk').write_text('tampered')
        self.assertEqual('NOT_REUSABLE', self.tool.check(receipt, self.after)['status'])
        (self.root / 'out/app.apk').unlink()
        self.assertEqual('NOT_REUSABLE', self.tool.check(receipt, self.after)['status'])
    def test_changed_input_during_run_and_wrong_time_rejected(self):
        self.after['fingerprint'] = 'different'
        with self.assertRaises(ValueError):
            self.seal()
        self.after = self.tool.snapshot(self.root, self.spec, now=11)
        with self.assertRaises(ValueError):
            self.seal()
    def test_task_identity_and_incomplete_spec_rejected(self):
        receipt = self.seal()
        spec = copy.deepcopy(self.spec)
        spec['identity']['tasks'] = [':app:lintDebug']
        spec['identity']['argv'][1] = ':app:lintDebug'
        self.assertEqual('NOT_REUSABLE', self.tool.check(receipt, self.tool.snapshot(self.root, spec, now=30))['status'])
        spec['inputs'] = spec['inputs'][:1]
        with self.assertRaises(ValueError):
            self.tool.snapshot(self.root, spec)

class QaTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue((HERE / 'qa_profile.py').exists(), 'Parameterized offline QA profile tool is missing')
        self.tool = module('qa_profile')
        self.tmp = temporary_fixture()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'repo').mkdir()
        (self.root / 'signer').write_bytes(b'existing-fixture-only')
        self.config = {'repo': str(self.root / 'repo'), 'profile': str(self.root / 'profile'), 'output': str(self.root / 'profile/build'), 'signer': str(self.root / 'signer'), 'package': 'org.iz.navigation.qafixture', 'label': 'Iz QA fixture'}
    def test_generates_offline_app_profile_without_touching_source_or_key(self):
        result = self.tool.generate(self.config)
        self.assertEqual('PREPARED_NOT_ASSEMBLED_OR_ADMITTED', result['status'])
        init = Path(result['init']).read_text()
        self.assertIn("if (p.path == ':app')", init)
        self.assertIn('finalizeDsl', init)
        self.assertNotIn("p.path == ':wear'", init)
        for name in ['debug', 'androidTest']:
            manifest = (self.root / 'profile' / name / 'AndroidManifest.xml').read_text()
            self.assertIn('android.permission.INTERNET', manifest)
            self.assertIn('tools:node="remove"', manifest)
        for field in ['OSM_CLIENT_ID', 'GROUP_SUPABASE_URL', 'GROUP_SUPABASE_KEY']:
            self.assertIn(field, init)
        self.assertEqual([], list((self.root / 'repo').iterdir()))
        self.assertEqual(b'existing-fixture-only', (self.root / 'signer').read_bytes())
        self.assertIn('allJvmArgs', Path(result['unitTempInit']).read_text())
        self.assertIn('iz.offline.testRoot', Path(result['unitTempInit']).read_text())
    def test_rejects_production_package_c_paths_and_source_output(self):
        for change in [{'package': 'org.iz.navigation'}, {'package': 'org.iz.navigation.qafixture.test'}, {'output': 'C:/new-data'}, {'profile': str(self.root / 'repo/unsafe')}]:
            with self.subTest(change=change), self.assertRaises(ValueError):
                self.tool.generate({**self.config, **change})
    def test_no_overwrite_of_an_existing_profile(self):
        self.tool.generate(self.config)
        with self.assertRaises(FileExistsError):
            self.tool.generate(self.config)

if __name__ == '__main__':
    unittest.main()
