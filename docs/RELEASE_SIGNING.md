# Release signing policy

## Permanent production lineage

- Android application ID: `dev.gf2log`
- First production-key release: `v2.0.2`
- Alias: `mobilegf2logger-release`
- Keystore type: `PKCS12`
- Certificate SHA-256:
  `3B:6A:F9:A1:70:76:75:49:4E:FD:04:2A:17:D7:23:B2:3E:CB:96:C9:FF:53:DB:92:55:77:9C:F5:A5:FC:BF:01`

The certificate and its fingerprint are public. The keystore, private key, and
passwords are private and must never be committed, uploaded, or distributed.
Losing the key prevents future versions from updating v2.0.2 and later.

## Local configuration

Keep the production keystore outside the repository and create an ignored
`keystore.properties` in the repository root:

```properties
storeFile=D:/SecureKeys/mobileGF2logger/mobileGF2logger-release.jks
storePassword=replace-with-private-value
keyAlias=mobilegf2logger-release
keyPassword=replace-with-private-value
```

The Gradle configuration verifies the certificate fingerprint before packaging
a distributable release. A missing, malformed, or different signer fails the
build. Android's shared debug key remains valid only for debug variants.

## Release procedure

1. Confirm the worktree is clean and `keystore.properties` points to the
   permanent external keystore.
2. Run `gradlew.bat clean :app:assembleRelease`.
3. Verify the generated APK with the Android SDK's `apksigner`:

```powershell
$apksigner = Get-ChildItem `
    "$env:ANDROID_HOME\build-tools\*\apksigner.bat" |
    Sort-Object { [version]$_.Directory.Name } -Descending |
    Select-Object -First 1

& $apksigner.FullName verify `
    --verbose `
    --print-certs `
    ".\app\build\outputs\apk\release\app-release.apk"
```

4. Confirm the APK verifies, has exactly one signer, and its reported
   certificate SHA-256 exactly matches the fingerprint in this document.
5. Install and smoke-test the signed APK on a physical device.
6. Publish only that verified signed APK.

Back up the keystore in at least two encrypted, physically separate locations.
Keep its passwords in a password manager separate from those copies.

## CI policy

GitHub Actions has no private signing material. It explicitly passes
`-PallowUnsignedRelease=true` under `CI=true` to verify tests, lint, native
compilation, minification, resource shrinking, and release packaging. The
workflow confirms that this APK is unsigned, renames it as verification-only,
and bundles a `DO_NOT_DISTRIBUTE.txt` notice.

The artifact named `unsigned-verification-only-do-not-distribute` must never be
attached to a GitHub Release or installed as a production update.
