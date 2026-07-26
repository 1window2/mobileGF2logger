# Release signing

Release builds never use Android's shared debug key. Without local signing
properties, `assembleRelease` deliberately produces an unsigned CI artifact.

For a distributable build, keep the private keystore outside Git and create an
ignored `keystore.properties` in the repository root:

```properties
storeFile=C:/secure/mobileGF2logger-release.jks
storePassword=replace-with-private-value
keyAlias=mobilegf2logger
keyPassword=replace-with-private-value
```

Run `gradlew.bat :app:assembleRelease`. Before publishing, verify the APK:

```text
apksigner verify --verbose --print-certs GF2logger.apk
```

Back up the keystore and passwords offline. Losing them prevents future APKs
from updating an installed release. Never commit or upload those secrets as CI
artifacts.
