# Third-party notices

Original application code and identity are dgChat / Debjyoti Gorai. No BitChat source, branding, interface assets or copy are included.

| Dependency | Use | License / upstream |
|---|---|---|
| Kotlin / coroutines | Application language and asynchronous state | Apache 2.0; https://github.com/JetBrains/kotlin |
| AndroidX / Compose / Material / Room | Native UI, lifecycle and persistence | Apache 2.0; https://android.googlesource.com/platform/frameworks/support/ |
| Material icons | Standard Android icon set | Apache 2.0; https://github.com/google/material-design-icons |
| Noise-Java (Signal fork) | Standard Noise handshake/envelope processing | MIT; https://github.com/signalapp/noise-java |
| Noise-Java original | Upstream protocol library by Rhys Weatherley | MIT; https://github.com/rweather/noise-java |
| OkHttp / Okio | Optional WebSocket transport | Apache 2.0; https://github.com/square/okhttp |
| ZXing | Public identity QR encoding/decoding | Apache 2.0; https://github.com/zxing/zxing |
| JUnit | Tests | EPL 1.0; https://github.com/junit-team/junit4 |
| websockets | Optional independent Python relay | BSD 3-Clause; https://github.com/python-websockets/websockets |
| Gradle | Build system downloaded on first build | Apache 2.0; https://github.com/gradle/gradle |

No upstream dependency source is vendored in the application. Preserve the dependencies' bundled notices and licenses in release packaging. The bootstrap launcher JAR is compiled from this project's own source, not copied from Gradle. Review generated dependency notices/SBOM when preparing a production release.
