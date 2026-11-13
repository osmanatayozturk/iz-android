"""Focused host tests: fake javap only, no Java/compiler/Gradle invocation."""
import contextlib
import io
import json
import os
import pathlib
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from tools.build_workflow import signatures


def method(name='selected', descriptor='()V', annotation='org.junit.Test', flags='ACC_PUBLIC, ACC_FINAL'):
    return f'''  public final void {name}();
    descriptor: {descriptor}
    flags: (0x0011) {flags}
    Code:
      stack=0, locals=1, args_size=1
         0: return
    RuntimeVisibleAnnotations:
      0: #15()
        {annotation}
'''


def listing(*members, class_name='sample.Cases'):
    return f'''Classfile /fixture/sample/Cases.class
  Last modified Nov 14, 2026; size 100 bytes
public final class {class_name}
  minor version: 0
  major version: 61
  flags: (0x0031) ACC_PUBLIC, ACC_FINAL, ACC_SUPER
  this_class: #2                          // {class_name.replace('.', '/')}
  super_class: #4                         // java/lang/Object
Constant pool:
  #15 = Utf8               Lorg/junit/Test;
{{
  public {class_name}();
    descriptor: ()V
    flags: (0x0001) ACC_PUBLIC
{''.join(members)}}}
SourceFile: "Cases.kt"
RuntimeVisibleAnnotations:
  0: #20()
    kotlin.Metadata
'''


class SignatureGateTests(unittest.TestCase):
    def setUp(self):
        base = pathlib.Path(os.environ.get('IZ_BUILD_TEST_ROOT', str(pathlib.Path(__file__).resolve().parents[2] / '.build-workflow-test-fixtures'))).resolve()
        self.assertEqual(base.drive.upper(), 'D:', 'Fixture root must be on D:')
        base.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=base)
        self.addCleanup(self.temp.cleanup)
        self.root = pathlib.Path(self.temp.name)
        self.class_file = self.root / 'sample/Cases.class'
        self.class_file.parent.mkdir()
        self.class_file.write_bytes(b'\xca\xfe\xba\xbeFAKE-NOT-JAVA')
        self.spec = {'tests': ['sample.Cases#selected'], 'lifecycle': [], 'excludedTests': []}

    def gate(self, output=None, **kwargs):
        result = subprocess.CompletedProcess([], 0, listing(method()) if output is None else output, '')
        with patch.object(signatures.subprocess, 'run', return_value=result):
            return signatures.check_signatures('javap-fixture', [self.root], self.spec, **kwargs)

    def assertFailure(self, result, code):
        self.assertEqual('FAIL_COMPILED_SIGNATURES', result['status'])
        self.assertIn(code, [x['code'] for x in result['failures']])

    def test_void_test_and_explicit_lifecycle_pass_without_claiming_runtime(self):
        self.spec['lifecycle'] = [
            {'selector': 'sample.Cases#before', 'annotation': 'org.junit.Before'},
            {'selector': 'sample.Cases#after', 'annotation': 'org.junit.After'},
        ]
        result = self.gate(listing(method(), method('before', annotation='org.junit.Before'), method('after', annotation='org.junit.After')))
        self.assertEqual('PASS_COMPILED_SIGNATURES', result['status'])
        self.assertEqual({'tests': 1, 'lifecycle': 2, 'excludedTests': 0, 'classes': 1}, result['counts'])
        self.assertEqual('NOT_RUN', result['runtimeDiscovery'])
        self.assertEqual([], result['failures'])

    def test_kotlin_expression_body_nonvoid_and_parameterized_tests_are_rejected(self):
        for descriptor in ('()Ljava/lang/IllegalArgumentException;', '(I)V'):
            with self.subTest(descriptor=descriptor):
                self.assertFailure(self.gate(listing(method(descriptor=descriptor))), 'INVALID_SIGNATURE')

    def test_nonpublic_static_abstract_and_generated_test_methods_are_rejected(self):
        for flags in ('ACC_PRIVATE', 'ACC_PUBLIC, ACC_STATIC', 'ACC_PUBLIC, ACC_ABSTRACT', 'ACC_PUBLIC, ACC_SYNTHETIC', 'ACC_PUBLIC, ACC_BRIDGE'):
            with self.subTest(flags=flags):
                self.assertFailure(self.gate(listing(method(flags=flags))), 'INVALID_SIGNATURE')

    def test_missing_method_or_runtime_test_annotation_fails(self):
        self.assertFailure(self.gate(listing()), 'MISSING_METHOD')
        self.assertFailure(self.gate(listing(method(annotation='org.jetbrains.annotations.NotNull'))), 'MISSING_ANNOTATION')
        invisible = listing(method()).replace('    RuntimeVisibleAnnotations:', '    RuntimeInvisibleAnnotations:')
        self.assertFailure(self.gate(invisible), 'MISSING_ANNOTATION')

    def test_constant_pool_and_other_method_annotations_cannot_satisfy_selected_method(self):
        self.assertFailure(self.gate(listing(method(annotation='org.jetbrains.annotations.NotNull'), method('helper', annotation='org.junit.Before'))), 'UNALLOCATED_LIFECYCLE')
        output = listing(method(annotation='org.jetbrains.annotations.NotNull')).replace('    kotlin.Metadata', '    org.junit.Test')
        self.assertFailure(self.gate(output), 'MISSING_ANNOTATION')

    def test_duplicate_or_overloaded_method_is_rejected(self):
        self.assertFailure(self.gate(listing(method(), method())), 'DUPLICATE_METHOD')
        self.assertFailure(self.gate(listing(method(), method(descriptor='(I)V', annotation='other.Annotation'))), 'DUPLICATE_METHOD')

    def test_extra_tests_require_exact_exclusion_and_nonblank_reason(self):
        output = listing(method(), method('extra'))
        self.assertFailure(self.gate(output), 'UNALLOCATED_TEST')
        self.spec['excludedTests'] = [{'selector': 'sample.Cases#extra', 'reason': 'Separate device allocation'}]
        result = self.gate(output)
        self.assertEqual('PASS_COMPILED_SIGNATURES', result['status'])
        self.assertEqual(1, result['counts']['excludedTests'])
        self.spec['excludedTests'][0]['reason'] = ' '
        self.assertFailure(self.gate(output), 'INVALID_SELECTORS')

    def test_exclusion_must_exist_and_cannot_hide_invalid_junit_signature(self):
        self.spec['excludedTests'] = [{'selector': 'sample.Cases#extra', 'reason': 'Separate allocation'}]
        self.assertFailure(self.gate(), 'MISSING_METHOD')
        self.assertFailure(self.gate(listing(method(), method('extra', descriptor='()I'))), 'INVALID_SIGNATURE')

    def test_lifecycle_requires_correct_explicit_annotation_and_void_signature(self):
        self.spec['lifecycle'] = [{'selector': 'sample.Cases#before', 'annotation': 'org.junit.Before'}]
        self.assertFailure(self.gate(listing(method(), method('before', annotation='org.junit.After'))), 'WRONG_ANNOTATION')
        self.assertFailure(self.gate(listing(method(), method('before', descriptor='()I', annotation='org.junit.Before'))), 'INVALID_SIGNATURE')

    def test_duplicate_selector_roles_and_malformed_scope_fail_before_tool_execution(self):
        for spec in (
            {'tests': ['sample.Cases#selected'] * 2, 'lifecycle': []},
            {'tests': ['sample.Cases#selected'], 'lifecycle': [{'selector': 'sample.Cases#selected', 'annotation': 'org.junit.Before'}]},
        ):
            self.spec = spec
            self.assertFailure(self.gate(), 'DUPLICATE_SELECTOR')
        for spec in (
            {'tests': [], 'lifecycle': []},
            {'tests': ['../Cases#selected'], 'lifecycle': []},
            {'tests': ['sample.Cases#selected'], 'lifecycle': ['sample.Cases#before']},
            {'tests': ['sample.Cases#selected'], 'lifecycle': [], 'unknown': []},
        ):
            self.spec = spec
            self.assertFailure(self.gate(), 'INVALID_SELECTORS')

    def test_missing_and_duplicate_compiled_class_fail_without_javap(self):
        with patch.object(signatures.subprocess, 'run', side_effect=AssertionError('must not invoke javap')):
            self.class_file.unlink()
            result = signatures.check_signatures('fake', [self.root], self.spec)
            self.assertFailure(result, 'MISSING_CLASS')
            self.class_file.write_bytes(b'fixture')
            other = self.root / 'second'
            duplicate = other / 'sample/Cases.class'
            duplicate.parent.mkdir(parents=True)
            duplicate.write_bytes(b'fixture')
            result = signatures.check_signatures('fake', [self.root, other], self.spec)
            self.assertFailure(result, 'DUPLICATE_CLASS')

    def test_class_identity_and_malformed_output_are_rejected(self):
        self.assertFailure(self.gate(listing(method(), class_name='sample.Other')), 'CLASS_IDENTITY_MISMATCH')
        self.assertFailure(self.gate(listing(method()).replace('    descriptor: ()V', '    missing: ()V')), 'MALFORMED_JAVAP')

    def test_nonpublic_abstract_or_ignored_allocated_class_is_not_discoverable(self):
        for flags in ('ACC_FINAL, ACC_SUPER', 'ACC_PUBLIC, ACC_ABSTRACT, ACC_SUPER'):
            output = listing(method()).replace('ACC_PUBLIC, ACC_FINAL, ACC_SUPER', flags)
            with self.subTest(flags=flags):
                self.assertFailure(self.gate(output), 'INVALID_TEST_CLASS')
        ignored = listing(method()).replace('    kotlin.Metadata', '    org.junit.Ignore')
        self.assertFailure(self.gate(ignored), 'IGNORED_CLASS')

    def test_tool_uses_argv_and_binds_class_hash_without_executing_runtime(self):
        def fake_javap(argv, **kwargs):
            self.assertEqual(['javap fixture.exe', '-v', '-p', str(self.class_file.resolve())], argv)
            self.assertIs(False, kwargs['shell'])
            self.assertEqual(7, kwargs['timeout'])
            return subprocess.CompletedProcess(argv, 0, listing(method()), '')
        with patch.object(signatures.subprocess, 'run', side_effect=fake_javap):
            result = signatures.check_signatures('javap fixture.exe', [self.root], self.spec, timeout_seconds=7)
        self.assertEqual('PASS_COMPILED_SIGNATURES', result['status'])
        self.assertEqual(64, len(result['classes'][0]['sha256']))
        self.assertEqual('NOT_RUN', result['runtimeTests'])

    def test_failed_noisy_timed_out_tool_and_changed_class_are_rejected(self):
        cases = (
            (subprocess.CompletedProcess([], 1, '', 'failure'), 'JAVAP_FAILED'),
            (subprocess.CompletedProcess([], 0, listing(method()), 'warning'), 'JAVAP_STDERR'),
        )
        for process, code in cases:
            with patch.object(signatures.subprocess, 'run', return_value=process):
                self.assertFailure(signatures.check_signatures('fake', [self.root], self.spec), code)
        with patch.object(signatures.subprocess, 'run', side_effect=subprocess.TimeoutExpired('fake', 1)):
            self.assertFailure(signatures.check_signatures('fake', [self.root], self.spec), 'JAVAP_TIMEOUT')
        def change_class(*args, **kwargs):
            self.class_file.write_bytes(b'replaced-during-javap')
            return subprocess.CompletedProcess([], 0, listing(method()), '')
        with patch.object(signatures.subprocess, 'run', side_effect=change_class):
            self.assertFailure(signatures.check_signatures('fake', [self.root], self.spec), 'CLASS_CHANGED')

    def test_json_input_duplicate_keys_and_cli_nonzero_failure(self):
        spec_path = self.root / 'scope.json'
        spec_path.write_text(json.dumps(self.spec), encoding='utf-8')
        self.spec = spec_path
        self.assertEqual('PASS_COMPILED_SIGNATURES', self.gate()['status'])
        spec_path.write_text('{"tests":[],"tests":["sample.Cases#selected"],"lifecycle":[]}', encoding='utf-8')
        self.assertFailure(self.gate(), 'INVALID_SELECTORS')
        with contextlib.redirect_stdout(io.StringIO()) as captured:
            exit_code = signatures.main(['--javap', 'fake', '--class-root', str(self.root), '--selectors', str(spec_path)])
        self.assertEqual(1, exit_code)
        self.assertEqual('FAIL_COMPILED_SIGNATURES', json.loads(captured.getvalue())['status'])


if __name__ == '__main__':
    unittest.main()
