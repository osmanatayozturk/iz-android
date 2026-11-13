"""Prepare an explicit app-only offline QA profile; never builds or installs it."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

def d_path(value):
    path = Path(value).resolve()
    if path.drive.upper() != 'D:' or len(path.parts) < 3:
        raise ValueError('An explicit allocated D: path is required')
    if any(c in str(path) for c in '\r\n\x00'):
        raise ValueError('Invalid path framing')
    return path

def literal(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "\\'") + "'"

def generate(config):
    repo, profile, output, signer = [d_path(config[k]) for k in ['repo', 'profile', 'output', 'signer']]
    package = config['package']
    if not re.fullmatch(r'org\.iz\.navigation\.qa[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)*', package) or package.endswith('.test'):
        raise ValueError('Use a distinct org.iz.navigation.qa... application ID, never production or test ID')
    if not repo.is_dir() or not signer.is_file():
        raise ValueError('Existing source directory and existing readable signer required')
    with signer.open('rb') as key:
        key.read(1)  # Only read access is tested here; never copy/create/change key material.
    if profile.is_relative_to(repo) or repo.is_relative_to(profile) or signer.is_relative_to(profile):
        raise ValueError('Allocated profile must be separate from source and signer')
    if output == profile or not output.is_relative_to(profile):
        raise ValueError('QA build output must be a child of the allocated profile')
    if profile.exists():
        raise FileExistsError('Existing profile/evidence is preserved; choose a fresh allocated directory')
    label = config.get('label', 'Iz isolated QA')
    if not isinstance(label, str) or not label or len(label) > 120 or any(c in label for c in '\r\n\x00'):
        raise ValueError('Invalid QA label')
    init = """// Explicit opt-in QA profile. AGP8.10 finalizeDsl; no product/source mutation.
def expectedRoot = new File(@REPO@).canonicalFile
def profileRoot = new File(@PROFILE@).canonicalFile
def outputRoot = new File(@OUTPUT@).canonicalFile
def existingSigner = new File(@SIGNER@).canonicalFile
def qaPackage = @PACKAGE@
gradle.beforeProject { p ->
    if (p.rootDir.canonicalFile != expectedRoot) throw new GradleException('Unexpected QA source root')
    if (!existingSigner.isFile() || !existingSigner.canRead()) throw new GradleException('Existing D signer required; no new key')
    def component = p.path == ':' ? 'root-build' : p.path.substring(1).replace(':', '-') + '-build'
    p.layout.buildDirectory.set(new File(outputRoot, component))
    if (p.path == ':app') p.plugins.withId('com.android.application') {
        p.extensions.getByName('androidComponents').finalizeDsl { android ->
            android.testBuildType = 'debug'
            android.defaultConfig.applicationId = qaPackage
            android.defaultConfig.testApplicationId = qaPackage + '.test'
            android.defaultConfig.manifestPlaceholders['appAuthRedirectScheme'] = qaPackage
            ['OSM_CLIENT_ID', 'GROUP_SUPABASE_URL', 'GROUP_SUPABASE_KEY'].each { field ->
                android.defaultConfig.buildConfigField('String', field, '""')
            }
            android.defaultConfig.testInstrumentationRunnerArguments['osmLightEditorZipQaPackage'] = qaPackage
            android.signingConfigs.getByName('debug').storeFile = existingSigner
            android.sourceSets.getByName('debug').manifest.srcFile(new File(profileRoot, 'debug/AndroidManifest.xml'))
            android.sourceSets.getByName('androidTest').manifest.srcFile(new File(profileRoot, 'androidTest/AndroidManifest.xml'))
        }
    }
}
"""
    for name, value in [('REPO', repo), ('PROFILE', profile), ('OUTPUT', output), ('SIGNER', signer), ('PACKAGE', package)]:
        init = init.replace('@' + name + '@', literal(value))
    unit = """// Same three existing app Test temp overrides; Gradle8.11 immutable tmp handling.
import org.gradle.api.tasks.testing.Test
import groovy.json.JsonOutput
def expectedRoot = new File(@REPO@).canonicalFile
def scratch = new File(@PROFILE@, 'unit-temp').canonicalFile
def keys = ['java.io.tmpdir', 'iz.unitTempRoot', 'iz.offline.testRoot']
gradle.beforeProject { p ->
    if (p.rootDir.canonicalFile != expectedRoot) throw new GradleException('Unexpected unit temp source root')
    if (p.path == ':app') p.tasks.withType(Test).configureEach { task ->
        def root = new File(scratch, task.name).canonicalFile
        keys.each { key -> task.systemProperty(key, root.path) }
        task.doFirst {
            if (root.parentFile.canonicalFile != scratch) throw new GradleException('Unit temp escaped scratch')
            if (!root.isDirectory() && !root.mkdirs()) throw new GradleException('Cannot create owned unit temp')
            keys.each { key -> task.systemProperty(key, root.path) }
            def prefix = '-Djava.io.tmpdir='
            def tempArgs = task.allJvmArgs.findAll { it.startsWith(prefix) }
            def values = keys.collectEntries { key -> [(key): task.systemProperties[key]?.toString()] }
            values['java.io.tmpdir'] = tempArgs.size() == 1 ? tempArgs[0].substring(prefix.length()) : null
            new File(scratch, task.name + '-effective-properties.json').setText(
                JsonOutput.prettyPrint(JsonOutput.toJson([task: task.path, expectedRoot: root.path, properties: values, tempJvmArgs: tempArgs])), 'UTF-8')
            keys.each { key ->
                if (values[key] == null || new File(values[key]).canonicalFile != root) throw new GradleException('Unit temp override mismatch')
            }
        }
    }
}
""".replace('@REPO@', literal(repo)).replace('@PROFILE@', literal(profile))
    android, tools = 'http://schemas.android.com/apk/res/android', 'http://schemas.android.com/tools'
    ET.register_namespace('android', android)
    ET.register_namespace('tools', tools)
    manifests = {}
    for name in ['debug', 'androidTest']:
        manifest = ET.Element('manifest')
        ET.SubElement(manifest, 'uses-permission', {f'{{{android}}}name': 'android.permission.INTERNET', f'{{{tools}}}node': 'remove'})
        if name == 'debug':
            ET.SubElement(manifest, 'application', {f'{{{android}}}label': label, f'{{{tools}}}replace': 'android:label'})
        manifests[name + '/AndroidManifest.xml'] = ET.tostring(manifest, encoding='unicode') + '\n'
    profile.mkdir(parents=True, exist_ok=False)
    for name, content in {'qa.init.gradle': init, 'unit-temp.init.gradle': unit, **manifests}.items():
        target = profile / name
        target.parent.mkdir(parents=True, exist_ok=True)
        with target.open('x', encoding='utf-8') as stream:
            stream.write(content)
    result = {'status': 'PREPARED_NOT_ASSEMBLED_OR_ADMITTED', 'repo': str(repo), 'profile': str(profile), 'output': str(output), 'package': package, 'testPackage': package + '.test', 'init': str(profile / 'qa.init.gradle'), 'unitTempInit': str(profile / 'unit-temp.init.gradle'), 'signer': str(signer), 'limits': ['Explicit -I activation only; neither Gradle nor device is invoked.', 'Verify actual APK manifests/INTERNET/runner/target/scheme/three blank fields/signatures/hashes after assembly.', 'Existing target/per-run/pair authorization and data-preserving install guards still required.', 'No product install, data clear/reset/uninstall/downgrade, live service or publication authority.']}
    (profile / 'PROFILE.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    args = parser.parse_args(argv)
    try:
        result = generate(json.loads(Path(args.config).read_text(encoding='utf-8-sig')))
        print(json.dumps(result))
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print(json.dumps({'status': 'REJECTED', 'reason': str(error)}))
        return 2

if __name__ == '__main__':
    raise SystemExit(main())
