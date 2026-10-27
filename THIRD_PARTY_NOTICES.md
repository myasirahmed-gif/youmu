# Third-party components

YouMu 0.4.0 integrates NewPipeExtractor v0.26.5, copyright its respective contributors, licensed GPL-3.0-or-later. This combined build is distributed under GPL-3.0-or-later. Preserve upstream copyright notices and provide corresponding source when redistributing it. The license text is in COPYING and bundled in the APK under assets/licenses. The app source and build scripts are provided alongside the APK; the extractor's matching source JAR is in third-party/.

YouMu is not affiliated with or endorsed by Team NewPipe or YouTube.

- NewPipeExtractor v0.26.5: https://github.com/TeamNewPipe/NewPipeExtractor/tree/v0.26.5
- Extractor sources: third-party/NewPipeExtractor-v0.26.5-sources.jar
- Full upstream build and source: https://github.com/TeamNewPipe/NewPipeExtractor/archive/refs/tags/v0.26.5.zip
- NewPipe nanojson fork (MIT): https://github.com/TeamNewPipe/nanojson/tree/e9d656ddb49a412a5a0a5d5ef20ca7ef09549996
- AndroidX Media3 1.5.1 (Apache-2.0): https://github.com/androidx/media/tree/1.5.1
- OkHttp 4.12.0 (Apache-2.0): https://github.com/square/okhttp/tree/parent-4.12.0
- Rhino 1.8.1 (MPL-2.0): https://github.com/mozilla/rhino/tree/Rhino1_8_1_Release
- Jsoup and other transitive dependencies retain their upstream licenses and notices.

Dependencies are pinned in the Gradle build. The unmodified upstream dependency build metadata is retrieved from Google Maven, Maven Central and JitPack during a build. Google Play services authentication is used for the optional Google account playlist connection and is subject to its own SDK terms.
