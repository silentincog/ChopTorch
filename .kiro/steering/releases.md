---
inclusion: always
---

# Releases

Repo: `silentincog/ChopTorch` (private). The owner downloads the app from GitHub Releases.

Package name since 4.0: `dev.incog.choptorch` (3.x was `com.kiro.choptorch`). Don't change it again: Android would treat it as a new app. Author: incogcyberpunk; licence MIT (`LICENSE`). Keep `Legal.kt` in sync with `LICENSE` and `PRIVACY.md`.

For every new app version:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build the signed release APK (`assembleRelease`). It must be signed with `keystore/incog.jks` (signer `CN=incog`). Verify with `apksigner verify --print-certs`.
3. Commit the source changes and push to `main`.
4. Create a GitHub release with tag `v<versionName>` on that commit and a short plain-language "What's new" body (`POST repos/silentincog/ChopTorch/releases`).
5. Attach the APK. The Kiro GitHub connection can't upload release assets (`uploads.github.com` is not routed, and `gh release` needs GraphQL), so:
   - push a temporary orphan branch `upload/v<versionName>` that contains only `ChopTorch-<versionName>.apk` (`git add -f`, since `*.apk` is ignored);
   - start `.github/workflows/attach-apk.yml` with `POST repos/silentincog/ChopTorch/actions/workflows/attach-apk.yml/dispatches`, `ref=main`, inputs `tag` and `sha256` (the APK's SHA-256);
   - wait for the run to succeed, then check the release lists the asset with the right size, and that the upload branch is gone.
6. Give the user the release page link.

The Kiro app has Administration: read only, so it can't create repositories; the owner created this one by hand.

Never commit `keystore/`, `keystore.properties` or APKs (see `.gitignore`).
