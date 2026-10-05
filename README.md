# UtahMeta Core TV

Private source mirror of the production UtahMeta Core TV Android application.

- Production source: `C:\ProgramData\UtahMeta\Android\CoreTV`
- Application ID: `com.utahmeta.coretv`
- Version: `1.0.1`
- Version code: `56`
- Min SDK: 26
- Target SDK: 36
- Java: 17

## Security

Signing keys, keystores, local machine paths, generated APK/AAB files, Gradle caches, and UtahMeta secret-store data are intentionally excluded from Git.

Release signing remains external to this repository. The app Gradle file accepts signing values through project properties:
`CORETV_STORE_FILE`, `CORETV_STORE_PASSWORD`, `CORETV_KEY_ALIAS`, and `CORETV_KEY_PASSWORD`.

This repository is being populated from the live production Core TV project on PFServer.
