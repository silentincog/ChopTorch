---
inclusion: always
---

# Releases

Repo: `silentincog/ChopTorch` (private). The owner downloads the app from GitHub Releases.

For every new app version:

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Build the signed release APK (`assembleRelease`). It must be signed with `keystore/incog.jks` (signer `CN=incog`). Verify with `apksigner verify --print-certs`.
3. Commit the source changes and push to `main`.
4. Create a GitHub release with tag `v<versionName>` on that commit, a short plain-language "What's new" body, and the APK attached as `ChopTorch-<versionName>.apk`.
5. Give the user the release page link.

Never commit `keystore/`, `keystore.properties` or APKs (see `.gitignore`).
