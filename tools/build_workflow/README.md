# Windows build workflow

These small tools replace task-specific launch scripts. They do not authorize a build, device action, publication, cleanup, or a new acceptance scope. Keep one heavy Build owner; independent source review may proceed in parallel. Product/API/version/dependencies are unchanged by adopting this tooling.

## Start with existing evidence

The technical owner selects the relevant source, configuration, toolchain, tasks and artifacts. Reuse is explicit; a role/worktree/commit label change alone is not a reason to rebuild. Changed product inputs require the affected checks. Failed, incomplete, uncertain, mismatched or planning-only results never become PASS.

`reuse.py` has three operations:

1. `snapshot --root <repo> --spec <input-spec.json> --out <new-before.json>` before a necessary run.
2. After completion, capture another snapshot and save the compact wrapper **status** JSON. `seal --before <before.json> --after <after.json> --status <status.json> --artifact <existing-report-or-apk> [--artifact ...] --out <new-receipt.json>` binds them. It requires current completed sequence, exit0, exact effective command/cwd and unchanged inputs bracketing that run.
3. `check --receipt <receipt.json> --current <current.json>` returns `REUSABLE` or `NOT_REUSABLE` (exit2). It rechecks bound output bytes/hashes. It never starts a build or retries a failure.

An input spec contains an identity and explicit file/tree roots. Relative paths resolve against `--root`; external configuration/toolchain paths may be absolute. Trees include their complete file set, so additions/removals invalidate reuse. Include all relevant generated/compiled inputs when reusing device-test results; this **host** receipt alone does not accept device/runtime behavior.

```json
{
  "identity": {
    "scope": "app-final-host",
    "variant": "debug",
    "tasks": [":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug"],
    "argv": ["D:/Iz/work/gradlew.bat", ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug", "--no-daemon", "-Pkotlin.compiler.execution.strategy=in-process", "--console=plain", "--no-scan"]
  },
  "inputs": [
    {"label": "app", "kind": "source", "path": "app/src"},
    {"label": "protocol", "kind": "source", "path": "wear-protocol/src"},
    {"label": "app-build", "kind": "config", "path": "app/build.gradle.kts"},
    {"label": "jdk", "kind": "toolchain", "path": "D:/Iz/tools/jdk17/release"}
  ]
}
```

This abbreviated example is **not** a complete acceptance spec. The owner must also select applicable root/settings/module build files, Gradle wrapper/properties, manifests, schemas, private service/profile/overlay configuration, locks, scripts and actual compiler/SDK/Gradle binaries or their complete verified identities. Add timing/init arguments to `argv` exactly as executed, including wrapper-added flags. Truncated/redacted command evidence is rejected. No automatic input-completeness inference is attempted. The owner must read the bound reports for semantic acceptance; an existing APK or a successful `--dry-run` is not a test result. Receipts are local evidence bindings, not authenticated attestations. Keep one receipt and reference it across roles; keep private configuration/evidence outside Git.

## Standalone Windows launcher

Use an existing absolute Python executable (`$pythonExe` below), not an unverified Windows Store alias. Keep machine-local JSON outside Git. The launcher config has exactly these keys; every path except the read-only installed wrapper must resolve to D:

```json
{
  "repo": "D:/Iz/work",
  "jdk": "D:/Iz/tools/jdk17",
  "sdk": "D:/Iz/android-sdk",
  "temp": "D:/Iz/runs/current/tmp",
  "output": "D:/Iz/runs/current/output",
  "gradle_home": "D:/Iz/gradle-home",
  "signer": "D:/Iz/android-user/debug.keystore",
  "wrapper": "C:/Codex/skills/gradle-run/scripts/gradle_run.py"
}
```

Substitute actual existing installations; examples are not installation instructions. Before the next necessary build, `preflight.py <config.json> --write-probes` checks JDK17 release/executable, SDK target/build tools, conflicting Java/SDK properties, temp/output/cache access and readable existing signer plus existing writable lock. Probes create/delete only their own unique zero-byte files; the signer/lock content is preserved. No permissions, SDK, signer, cache or daemon repair is attempted. Without `--write-probes`, this command only checks the recovery prerequisites (source and installed wrapper); it is not a full host preflight.

`launcher.py` sets process-local Java/SDK/Gradle/temp paths and invokes the **unmodified** compact wrapper via `runpy`. Every lifecycle operation is one standalone shell command, with no assignments, pipes, redirection or inspection chained to it:

```powershell
& $pythonExe .\tools\build_workflow\launcher.py --config D:/Iz/run/local.json -- --root D:/Iz/run/compact create
```

Retain the returned opaque workflow ID. On the frozen phone candidate, one combined invocation is:

```powershell
& $pythonExe .\tools\build_workflow\launcher.py --config D:/Iz/run/local.json -- --root D:/Iz/run/compact run --workflow WORKFLOW_ID --scope broad --question "Does the frozen phone candidate pass its required host checks?" -- D:/Iz/work/gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestKotlin --no-daemon --no-configuration-cache '-Pkotlin.compiler.execution.strategy=in-process' -I D:/Iz/work/tools/build_workflow/timing.init.gradle '-PizTaskTimingFile=D:/Iz/runs/current/output/timings/tasks-UNIQUE.json'
```

The single quoted in-process argument must remain one argv token. `final_argv(config, workflow, question, module='app')` provides the same explicit command plan as a Python API; it never executes. For other affected modules/bridge work, the technical owner selects the required existing tasks in the **same** explicit combined invocation. Add necessary approved init-script arguments as distinct tokens. The launcher rejects missing workaround/timing flags, another Gradle entrypoint and reused timing filenames; it does not silently rewrite caller argv.

```powershell
& $pythonExe .\tools\build_workflow\launcher.py --config D:/Iz/run/local.json -- --root D:/Iz/run/compact status --workflow WORKFLOW_ID
```

Save the returned bounded status JSON through the host/file tool, separately from that command. A running status may contain the previous result: bind `run.sequence == result_sequence` and terminal state before using it. On a lost session use this same workflow/root; `uncertain` requires owner investigation, not another run or cleanup. Inspect only compact summaries, never raw Gradle logs. After all required results and timing artifacts are saved, finish separately:

```powershell
& $pythonExe .\tools\build_workflow\launcher.py --config D:/Iz/run/local.json -- --root D:/Iz/run/compact finish --workflow WORKFLOW_ID
```

Status/finish deliberately remain available without build-only JDK/SDK/signer probes. Wrapper lifecycle/lock/interrupt cleanup remain owned by `gradle-run`. Structured task timing (max256 rows/64KiB) records outcome and elapsed time during the next necessary invocation; no extra measurement run is started.

## Parameterized offline QA

`qa_profile.py --config <qa.json>` prepares a **new** allocated D: directory, leaving source and signer untouched:

```json
{
  "repo": "D:/Iz/work",
  "profile": "D:/Iz/runs/qa-example",
  "output": "D:/Iz/runs/qa-example/build",
  "signer": "D:/Iz/keys/existing-debug.keystore",
  "package": "org.iz.navigation.qaexample",
  "label": "Iz isolated QA"
}
```

Nothing is active by default. Only an authorized invocation explicitly adding the returned `qa.init.gradle` / `unit-temp.init.gradle` via separate `-I` argv tokens enables them. The app-only profile uses a unique QA app/test ID and redirect scheme, three blank OSM/group service fields, existing signer, isolated D: outputs and INTERNET-removal overlays for both APKs. Existing profiles are not overwritten. The unit profile preserves the three existing app Test temp properties and Gradle8.11 immutable `java.io.tmpdir` handling.

Preparation is not assembly/admission. After the next necessary assembly, bind actual binary manifests, app/test hashes, runner/target, scheme, three blank generated fields and signer. Before any authorized native run, retain the existing exact target/pair/per-run admission and denied-location/no-INTERNET checks. Update only the allocated packages with data-preserving `-r`; no `-g`, clear/reset/uninstall/downgrade, production install or shared-server restart is granted here.

## Early compiled test check

After necessary AndroidTest compilation and before long device acceptance, use:

```powershell
& $pythonExe .\tools\build_workflow\signatures.py --javap D:/Iz/tools/jdk17/bin/javap.exe --class-root D:/Iz/run/compiled/debugAndroidTest --selectors D:/Iz/run/selectors.json
```

Selectors are `{"tests":["package.Class#method"],"lifecycle":[{"selector":"package.Class#cleanup","annotation":"org.junit.After"}],"excludedTests":[]}`. Lifecycle is separate; extra discovered declared tests need an explicit exclusion reason. Wrong signatures, missing annotations, duplicate classes/methods and failed javap reject the receipt. Bind class/source/APK identities. The result proves declared compiled JUnit signatures; inherited/custom-runner discovery and runtime execution remain `NOT_RUN` until their actual check.

## Scope and verification

No Gradle, compiler, APK assembly, device or old application suite is required merely to validate these tools. Run `python -B -m unittest discover -s tools/build_workflow -p 'test_*.py'` with an existing Python executable and an allocated D: temp root; fixtures use fake executors/javap and temporary files. Their result is tooling validation, never application acceptance or a speed measurement.

Keep the proven in-process/no-daemon workaround. Do not enable configuration cache, relocate caches, stop daemons, change permissions broadly, or run a benchmark solely for this work. Measure task durations on the **next necessary authorized** Gradle invocation; report build, tests and other measured phases separately. Cached/skipped task time is not a speedup claim. First real deployment of the generated Gradle init scripts remains to be observed on that necessary invocation.

## Official contracts

- [AGP8.10 finalizeDsl](https://developer.android.com/reference/tools/gradle-api/8.10/com/android/build/api/variant/DslLifecycle) and [manifest removal/merge priorities](https://developer.android.com/build/manage-manifests).
- [JDK17 javap](https://docs.oracle.com/en/java/javase/17/docs/specs/man/javap.html).
- [Gradle8.11.1 TaskExecutionListener](https://raw.githubusercontent.com/gradle/gradle/v8.11.1/subprojects/core-api/src/main/java/org/gradle/api/execution/TaskExecutionListener.java): task listeners are incompatible with configuration cache. These optional init scripts preserve that explicit restriction.
