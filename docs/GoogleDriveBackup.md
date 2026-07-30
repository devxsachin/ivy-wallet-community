# Google Drive Auto-Backup

Automatically backs up the user's data to their Google Drive.
The feature is opt-in via **Settings → Import & Export → "Auto-backup to Google Drive"**.

## How it works

- **`GoogleDriveBackup`** (`shared/data/core`, `com.ivy.data.backup.drive`) orchestrates the feature:
  it exposes `enabled` / `lastBackupTime` flows (persisted in DataStore) and
  `enable()` / `finishEnable()` / `disable()` / `backupNow()` operations.
- **`GoogleDriveAuthorizer`** requests the narrow `drive.file` OAuth scope via the
  Google Identity Services `AuthorizationClient`. The app can only see files it created.
  The first `enable()` returns a `PendingIntent` that the Settings screen launches so the
  user can pick an account and grant consent. After that, tokens are refreshed silently.
- **`GoogleDriveClient`** talks to the Drive REST v3 API through the app's Ktor `HttpClient`.
  Each backup overwrites a single `Ivy-Wallet-Backup.json` file in the user's "My Drive"
  (visible to the user, unlike `appDataFolder`).
- **`GoogleDriveBackupWorker`** + **`GoogleDriveAutoBackupScheduler`**: a periodic
  WorkManager job (every 6 hours, network-connected constraint, exponential backoff)
  that generates the full backup JSON via `BackupDataUseCase.generateJsonBackup()` and uploads it.
- Enabling the toggle also triggers an immediate first backup.

The backup content is identical to the manual "Backup data" export (unzipped JSON),
so a Drive backup file can be restored via the regular import flow.

## Required Google Cloud setup (one-time, per signing key)

The feature needs OAuth to be configured in the Google Cloud project that backs
`app/google-services.json`:

1. Open [Google Cloud Console](https://console.cloud.google.com/) → select the project.
2. **Enable the Google Drive API**: APIs & Services → Library → "Google Drive API" → Enable.
3. **Create Android OAuth clients**: APIs & Services → Credentials → Create Credentials →
   OAuth client ID → Android. One client per applicationId + signing key combination:
   - `com.ivy.wallet` with the release keystore's SHA-1 (`sign.jks`)
   - `com.ivy.wallet.debug` with **your local debug keystore's** SHA-1. Debug builds are
     signed with `~/.android/debug.keystore` (see `signingConfigs` in `app/build.gradle.kts`),
     not the repo's committed `debug.jks` — Google allows a given (package name + SHA-1)
     pair to be an Android OAuth client in only one Cloud project globally, and `debug.jks`'s
     fingerprint is already claimed by the upstream Ivy Apps project. Get your SHA-1 with:

     ```
     keytool -list -v -keystore ~/.android/debug.keystore -storepass android | grep SHA1
     ```

   To check which certificate an APK is actually signed with:
   `$ANDROID_HOME/build-tools/34.0.0/apksigner verify --print-certs <apk>`
4. **Configure the OAuth consent screen** (if not already) and add the
   `https://www.googleapis.com/auth/drive.file` scope. While the consent screen is in
   "Testing" publishing status, only listed test users can authorize.

Without this setup, toggling the feature on fails with an authorization error toast.
No new Android permissions are required (`INTERNET` is already declared).
