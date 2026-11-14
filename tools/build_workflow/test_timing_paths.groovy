// Run with the installed Gradle Groovy jar, not Gradle. Exercises the actual init predicate.
def source = new File(args[0]).canonicalFile
def line = source.readLines('UTF-8').find { it.startsWith('if (!root.') }
assert line != null
def predicate = line.substring(4, line.length() - 3)
def rejected = { File root, File target ->
    new GroovyShell(new Binding([root: root.canonicalFile, target: target.canonicalFile])).evaluate(predicate)
}
def output = new File(args[1]).canonicalFile
assert output.isDirectory() && output.toPath().root.toString().equalsIgnoreCase('D:\\')
def target = new File(output, 'timings/never-created-path-test.json')
assert !target.exists()
if (rejected(output, target)) {
    def shell = new GroovyShell(new Binding([root: output, target: target.canonicalFile]))
    println([canonicalRoot: output.path, guards: predicate.split(' \\|\\| ').collect { shell.evaluate(it) }])
}
assert !rejected(output, target): 'New canonical Windows child path must be admitted'
assert rejected(output, output): 'Root is not a timing file'
assert rejected(output, new File(output.parentFile, 'outside.json')): 'Sibling escape must be rejected'
assert rejected(output, new File(output, '../outside.json')): 'Canonical traversal must be rejected'
assert rejected(new File('C:/'), new File('C:/never-created-path-test.json')): 'C drive must be rejected'
assert rejected(source.parentFile, source): 'Existing target must be rejected'
println 'PASS: canonical D child, root, sibling, traversal, C drive and existing target (6 cases; no Gradle/tasks/files created)'
