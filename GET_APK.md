# Build your dgChat APK on GitHub

1. Extract the ZIP.
2. Create a GitHub repository named `dgChat`.
3. Upload the files **inside** the extracted `dgChat` folder to the repository root. Include the `.github` folder. Do not upload just the ZIP.
4. Go to **Actions → Build dgChat APK → Run workflow**.
5. Select **debug**, then run it.
6. Wait for the full workflow to pass.
7. Open that run → **Artifacts → dgChat-debug-<number>**.
8. Download/unzip the artifact and install `app-debug.apk` on Android 12 or newer.
9. Install it on another device, enable Bluetooth on both, grant Nearby devices permission and tap **Join nearby mesh**.

For signed release APKs, configure the four GitHub Secrets listed in `README.md` and choose **release**. Keep keys/passwords outside Git.

This archive contains source and workflows. It does **not** contain an already built APK. The Android build and physical-device validation have not been executed in the delivery environment; see `docs/verification.md`.
