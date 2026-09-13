# Third-party notices

İz original source code is Copyright (C) 2026 İz contributors and is licensed
under GNU GPL version 3 only (SPDX: GPL-3.0-only). See [LICENSE](LICENSE).
The license does not replace third-party copyrights, licenses, or service terms.

## Files included in this source repository

The Gradle Wrapper (`gradlew`, `gradlew.bat`, and
`gradle/wrapper/gradle-wrapper.jar`) comes from the Gradle project and is
licensed under Apache-2.0. Its existing copyright and license headers are
retained. The upstream composite license notice is preserved in
[LICENSES/Gradle-LICENSE.txt](LICENSES/Gradle-LICENSE.txt), including Gradle's own component notices. Those notices do not imply that all Gradle distribution
components are copied into this repository.

## Dependencies downloaded by the build

The dependency source/binary packages are not vendored in this repository.
Version coordinates are specified in the Gradle build files and npm lockfile.
Upstream licenses and notices continue to apply to each dependency.

| Dependency family | Declared license / terms |
| --- | --- |
| Kotlin, kotlinx.coroutines, AndroidX (including Compose, Car, Wear and Health Connect), Material Components | Apache-2.0 |
| MapLibre Native Android OpenGL | BSD-2-Clause; see upstream for bundled third-party notices |
| OkHttp, Coil, AppAuth-Android, ZXing | Apache-2.0 |
| Google Play Services Location and Wearable client libraries | Android Software Development Kit License; proprietary dependencies |
| JUnit 4 (test dependency) | EPL-1.0 |
| JSON-java (test dependency) | Public domain as declared by its upstream release |

Relevant upstream notices and terms:

- [Gradle license](https://github.com/gradle/gradle/blob/v8.11.1/LICENSE)
- [AndroidX licenses](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/LICENSE.txt)
- [MapLibre Native license](https://github.com/maplibre/maplibre-native/blob/main/LICENSE.md)
- [Google Android SDK terms](https://developer.android.com/studio/terms)
- [PGlite source and licenses](https://github.com/electric-sql/pglite)

The phone preview APK is distributed through GitHub Releases together with
license notices and version-specific dependency source materials. The original
İz code remains GPL-3.0-only, with the narrow GPLv3 section 7 permission in
[ADDITIONAL_PERMISSION.md](ADDITIONAL_PERMISSION.md) for the Google Play Services
Location and Wearable clients and their required Google Play Services Base,
Basement and Tasks dependencies. This permission does not relicense third-party
code or the separate Google Play Services application.

The release's license/source supplement contains the full third-party notices
and source locations for the dependencies resolved by that build. İz's matching
source is available from the same release tag. Build-tool dependencies are not
bundled in the APK. See the [release files](https://github.com/osmanatayozturk/iz-android/releases/tag/v0.9.2).

## Map data and online services

OpenStreetMap data is provided by OpenStreetMap contributors under ODbL 1.0.
The application's visible attribution must be retained. ODbL covers map data;
it does not replace the license of İz's original source.

- [OpenStreetMap copyright and attribution](https://www.openstreetmap.org/copyright)
- [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
- [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/)

OpenStreetMap, routing, weather, TomTom and Supabase services have their own
terms and usage limits. Publishing the source does not grant service credentials
or permission to exceed those limits. Optional providers are configured by the
person building or using the app.
