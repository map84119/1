# UtahMeta Core TV

Private GitHub recovery/build repository for the production UtahMeta Core TV Android application.

## Production baseline

- PFServer source: `C:\ProgramData\UtahMeta\Android\CoreTV`
- Application ID: `com.utahmeta.coretv`
- Version: `1.0.1`
- Version code: `56`
- Min SDK: 26
- Target SDK: 36
- Java: 17
- Android Gradle Plugin: 9.2.0

## Source status

The major application sources were recovered directly from PFServer's trusted Core TV readers, including the production manifest, full MainActivity, PresenceService, SignInActivity, ProfileActivity, CoreAiActivity, and UpdateManager.

PFServer's connector blocks arbitrary source export and clipped portions of the large PlayerActivity reader. To keep this repository build-complete, the small manifest helper classes and PlayerActivity glue were recovered against the production intent/API contract. They are intentionally isolated and can be replaced byte-for-byte if the connector later exposes unrestricted private file export.

## Security

The repository intentionally excludes all machine-local or sensitive build material:

- signing keystores and certificates
- signing passwords / UtahMeta secret-store files
- `local.properties`
- generated APK/AAB/build output
- Gradle caches and IDE state
- temporary and backup files

Release signing remains external to Git. The production Gradle project accepts signing values through `CORETV_STORE_FILE`, `CORETV_STORE_PASSWORD`, `CORETV_KEY_ALIAS`, and `CORETV_KEY_PASSWORD`.

## Build

A GitHub Actions build definition is included at `.github/workflows/build.yml`. It performs a Java 17 / Gradle 9.5 debug build and uploads the resulting debug APK as an Actions artifact.

The production signing key is deliberately not stored in GitHub.
