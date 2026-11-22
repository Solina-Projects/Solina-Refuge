# Solina Refuge — Internal Security Review (Track F)

**Date:** 2026-11-22
**Base commit reviewed:** `ab3f2cbe75425d4bad1ebe7ecc05020e1fe5a199`
(branch `audit/security-hardening`; includes tracks A — encrypted attachments +
backup v2, B — multi-vault, C — bank-card scan OCR)
**Review branch:** `audit/security-review`
**Reviewer role:** independent-style internal review (read the code as a reviewer,
not as its author).

## ⚠️ Status — read this first

**This is an INTERNAL review, NOT an independent external audit.**
It was done by the same team/tooling that built the app, from source code only,
with no device testing and no dynamic analysis. It does **not** replace the
phase-5 external audit: that still requires an independent third-party firm
with device testing, and cannot be done by us.

**The app is still PRE-ALPHA with no independent security audit. Do NOT store
real passwords in it yet.**

A green CI run is not proof the app works on a phone — nothing in this review
was device-tested.

## Scope

Re-read as a reviewer: key derivation (Argon2id params), AES-GCM usage (IV reuse,
AAD), Keystore usage, backup format (RFGB v1/v2) parsing, sync MAC verification,
Autofill data exposure, clipboard handling, PIN lock logic, biometric fallback,
and the new track A–C code (attachment sealing/restore, multi-vault key isolation
+ migration, card-scan OCR data handling). Also swept: importers (Aegis/2FAS/
Bitwarden/Proton/andOTP/Raivo/CSV/otpauth), OAuth PKCE, breach checking
(k-anonymity), passkeys, Room storage, notifications/logging, manifest hardening.

## Result summary

| # | Severity | Finding | Status |
|---|----------|---------|--------|
| F-01 | **High** | Secure wipe leaves decrypted attachment plaintext in cache | **Fixed** |
| F-02 | Medium | WebDAV `read()` unbounded — compromised server can OOM the app | **Fixed** |
| F-03 | Medium | Backup decode has no total size cap | **Fixed** |
| F-04 | Medium | PIN wrap AAD not bound to vault (cross-vault confusion) | **Fixed** |
| F-05 | Medium | Biometric blob not bound to vault | **Fixed** |
| F-06 | Medium | Attachments not vault-scoped; add broken in non-default vaults | **Fixed** (code-level) |
| F-07 | Medium | KeystoreSecretStore values lack AAD binding to key name | **Fixed** |
| F-08 | Medium | Auto-backup snapshots silently omit attachments | **Fixed** |
| F-09 | Low | Sync rollback detected but sync continues | Accepted (documented) |
| F-10 | Low | Crafted header can request up to 256 MiB Argon2 (transient OOM) | Deferred |
| F-11 | Low | ItemPayload fields have no length caps | Documented |
| F-12 | Low | OAuth/HIBP HTTP clients follow redirects by default | **Fixed** |
| F-13 | Low | Clipboard auto-clear is best-effort | Documented (pre-existing) |
| F-14 | Low | Autofill auth PendingIntent is FLAG_MUTABLE | Accepted (framework-required) |
| F-15 | Info | `MultiVaultDesign.WRAP_INFO_PREFIX` unused | Documented |
| F-16 | Info | Password unlock has no attempt cap (only backoff) | Accepted (by design) |
| F-17 | Info | WAL may retain freed blob pages until checkpoint | Documented |
| F-18 | Info | Passkey live flow gated off; no key material stored | No action |
| F-19 | Info | OAuth `state` comparison not constant-time | No action (negligible) |
| F-20 | Info | `StagedJoin` holds WebDAV password as a transient String | No action |

**Critical findings: none.**

All Critical and High findings are fixed. No fix weakens security: no check was
disabled, no KDF parameter was lowered, no plaintext fallback was added. Every
format change migrates old data instead of breaking it, except the biometric
blob (F-05), where a one-time re-enrollment is required — see below.

---

## Findings in detail

### F-01 — High — Secure wipe leaves decrypted attachment plaintext in cache — FIXED

- `AppContainer.kt:76` — `wipeLocalVaultSecurely()` wiped the database (with
  `secure_delete` + `VACUUM`), vault headers, the biometric Keystore key, the PIN
  and sync state — but never deleted `cacheDir/attachments/`.
- `MainActivity.kt:1448` — `openAttachmentFile()` writes the **decrypted**
  attachment to `cacheDir/attachments/<fileId>-<name>` and hands it to an external
  viewer via FileProvider. The directory is only deleted at the next app start
  (`MainActivity.kt:248`), so a "secure wipe" left plaintext files behind, and a
  deleted vault (`AuthController.deleteVault`) had the same gap.
- Threat: a user who wipes the vault (or deletes a vault) before handing the
  phone away, or a forensic read of app-private cache, recovers plaintext
  attachment contents.
- **Fix:** `AppContainer.clearAttachmentCache()` deletes `cacheDir/attachments/`;
  called from `wipeLocalVaultSecurely()` and from `AuthController.deleteVault()`.
  (Plaintext in cache while an attachment is open in an external viewer is still
  inherent to the FileProvider design and is deleted at next launch; that part is
  unchanged and documented.)

### F-02 — Medium — WebDAV `read()` unbounded — FIXED

- `sync/WebDavRemote.kt:97` — `conn.inputStream.use { it.readBytes() }` loaded an
  entire server response into memory with no cap; `list()` also did an unbounded
  `readText()` on the PROPFIND response. A compromised or malicious WebDAV server
  could OOM-crash the app with a multi-gigabyte response. (`FolderRemote` already
  capped reads at 8 MB; WebDAV did not.)
- **Fix:** `WebDavRemote.readCapped()` (8 MB, same as `FolderRemote.MAX_READ`);
  `read()` returns `null` for oversized bodies (the sync engine counts those as
  rejected records) and `list()` throws. Unit-tested (`WebDavRemoteTest`).

### F-03 — Medium — Backup decode has no total size cap — FIXED

- `core/backup/Backup.kt:116,211` — `inspect()`/`decodeFull()` accepted
  arbitrarily large byte arrays; the only cap lived in the UI file picker
  (64 MB). Any direct caller could feed a huge file to the AEAD/JSON parsers.
- **Fix:** `Backup.MAX_BACKUP_BYTES = 64 MB`, enforced in `inspect()` (which
  `decodeFull()`/`decode()` go through). Unit-tested.

### F-04 — Medium — PIN wrap AAD not bound to vault — FIXED

- `core/security/PinUnlock.kt:56` — the PIN wrap AAD was the constant
  `"refuge/pin/v1"` for every vault. The manager-level `pinVaultId()` check
  normally prevents cross-vault use, but if `SharedPreferences` were tampered
  with (or the blob copied), a PIN blob from vault A would unwrap vault A's key
  while vault B was active — the session would then seal vault B's rows with
  vault A's key (data corruption) and sync with the wrong MAC key.
- **Fix:** AAD is now `"refuge/pin/v1|<vaultId>"` (`PinCrypto.pinAad()`).
  `unwrapVaultKeyDetailed()` tries the vault AAD first, then the legacy AAD;
  legacy blobs are re-wrapped with the vault AAD on first successful use
  (one-time migration, PIN unchanged). Unit-tested
  (`pinWrapIsBoundToVault`, `legacyPinBlobMigratesToVaultAad`).

### F-05 — Medium — Biometric blob not bound to vault — FIXED

- `core/security/BiometricKeystore.kt:52,65` — the biometric blob (`iv ‖ GCM(VK)`)
  had no AAD at all, so a blob copied between per-vault directories would
  decrypt to the wrong vault's key (same confusion class as F-04; requires
  filesystem write access, hence Medium, not High).
- **Fix:** AAD `"refuge/biometric/v1|<vaultId>"` set on the cipher in
  `newEnrollCipher(vaultId)` / `newUnlockCipher(blob, vaultId)`; `AuthController`
  passes the active vault id. A stale blob that can never open now disables the
  biometric shortcut instead of failing forever.
- **Migration cost (one-time, user-visible):** blobs enrolled before this change
  have no AAD and will no longer open. The user signs in with the master
  password (always valid) and re-enables biometrics. This is the only
  breaking change in this review; it was chosen over leaving the binding out,
  because a silent wrong-key session is worse than a re-enrollment prompt.

### F-06 — Medium — Attachments not vault-scoped — FIXED (code-level)

- `core/storage/VaultRepository.kt:250` — `addAttachment()` called
  `store.get(itemId)` with the default vault id, so adding an attachment in any
  non-default vault always failed with "item not found" (functional bug in the
  multi-vault track).
- `VaultRepository.kt:270` — `openAttachment()` / `removeAttachment()` resolved
  a `fileId` globally with no check that its owning item belongs to the active
  vault. Decryption with the wrong vault key would fail anyway, so this was a
  defense-in-depth gap rather than a leak — but `removeAttachment()` could delete
  another vault's attachment outright.
- **Fix:** `addAttachment()` passes `vaultId()`; `openAttachment()` and
  `removeAttachment()` verify the owning item exists in the active vault.
  Unit-tested (`addAttachmentWorksInNonDefaultVault`,
  `attachmentOfOtherVaultIsNotOpenable`).
- **Deferred (schema-level):** the `attachments` table still has no `vaultId`
  column, so scoping is enforced in the repository, not the database. Adding the
  column needs Room migration 3→4 plus a backfill; judged out of scope for this
  review — the repository check closes the reachable paths, and the column is
  recommended as follow-up hardening.

### F-07 — Medium — KeystoreSecretStore values lack AAD binding — FIXED

- `core/security/KeystoreSecretStore.kt:48` — values (WebDAV credentials, PIN
  blob, external-account list, client ids) were AES-GCM encrypted with the same
  Keystore key and **no AAD**, so two stored ciphertexts were interchangeable:
  swapping the PIN blob with the WebDAV password entry would still decrypt.
- **Fix:** AAD `"refuge/keystore/v1|<store>|<key>"` on encrypt and decrypt.
  Legacy values (no AAD) are read once and re-encrypted with AAD. No JVM unit
  test is possible (Android Keystore); verified by CI compile + code review.

### F-08 — Medium — Auto-backup snapshots silently omit attachments — FIXED

- `sync/SyncManager.kt:364` — `writeVersionedSnapshot()` encoded backups without
  attachments, so scheduled snapshots were always RFGB v1. Restoring a snapshot
  returned the items without their attachments, with no warning — silent data
  loss for track A users who rely on auto-backup.
- **Fix:** snapshots now include `exportAttachmentsForBackup()` output
  (RFGB v2 when attachments exist); decrypted bytes are wiped after encoding,
  same as the manual export path.

### F-09 — Low — Sync rollback detected but sync continues — ACCEPTED

- `core/sync/SyncEngine.kt:105` — a manifest epoch below the last-seen epoch
  sets `rollbackDetected = true` and the report warns the user, but pull/push
  continue. Reviewed the record path: per-record revision comparison prevents
  adopting older records, and pushing re-publishes current state, so the
  protocol self-heals; aborting on detection could wedge legitimate re-syncs
  (e.g. a fresh device with a stale epoch store). No change; the warning
  already surfaces in the UI message.

### F-10 — Low — Crafted header can request up to 256 MiB Argon2 — DEFERRED

- `core/crypto/Primitives.kt:28` — `KdfParams.MAX_MEMORY_KIB = 256 MiB` is
  accepted from vault/backup headers. A crafted header (requires write access
  to app-private files, or tricking the user into opening a crafted backup)
  forces a 256 MiB Argon2 allocation on unlock — transient OOM/crash, no key
  compromise. No legitimate header exceeds 64 MiB (the calibrator's maximum).
  Deferred: a decode-time clamp (e.g. reject > 128 MiB in `VaultHeader.decode`
  / `Backup.inspect`) is recommended as follow-up; not applied here to avoid
  changing accepted-header semantics in a review branch.

### F-11 — Low — ItemPayload fields have no length caps — DOCUMENTED

- `core/storage/ItemPayload.kt` — title/password/note/body etc. have no length
  limits (only `CustomField` is capped). A hostile import file could store a
  very large field, bloating the encrypted blob. Bounded in practice by the
  64 MB import-file cap and the 10 MB attachment cap. Recommend per-field caps
  at the import boundary as follow-up.

### F-12 — Low — OAuth/HIBP HTTP clients follow redirects by default — FIXED

- `auth/ExternalAccountManager.kt` (token exchange, userinfo, GitHub emails)
  and `core/security/BreachChecker.kt` used `HttpURLConnection` with default
  redirect following, so a redirect could send an auth code / access token /
  HIBP hash prefix to another host.
- **Fix:** `instanceFollowRedirects = false` on all four connections
  (matching `WebDavRemote`, which already disabled it).

### F-13 — Low — Clipboard auto-clear is best-effort — DOCUMENTED (pre-existing)

- `ui/components/Components.kt` — `copySensitive()` marks the clip sensitive
  (Android 13+) and clears it after 30 s, but if the app is killed the clear
  never runs, and keyboards with clipboard history keep their own copy. This
  was already documented in the code; no change.

### F-14 — Low — Autofill auth PendingIntent is FLAG_MUTABLE — ACCEPTED

- `autofill/AutofillResponses.kt:135` — the locked-vault auth intent uses
  `FLAG_MUTABLE` on API 31+. This is required by the framework for Autofill
  authentication intents; the intent is explicit to the app's own
  `AutofillAuthActivity` with fixed extras. No change.

### F-15 — Info — `MultiVaultDesign.WRAP_INFO_PREFIX` unused — DOCUMENTED

- `core/crypto/MultiVaultDesign.kt:31` — the per-vault wrap-info prefix was
  never wired into `VaultCrypto` (the comment says "to be added"). Cross-vault
  key separation currently relies on each vault's independent random 128-bit
  salt (different salt ⇒ different Argon2id KEK) and independent recovery
  entropy, which is sufficient. Either wire the prefix in or remove the dead
  constant; no security action needed.

### F-16 — Info — Password unlock has no attempt cap — ACCEPTED (by design)

- Only the PIN wipes itself after 5 failures; the master password relies on
  exponential backoff (`UnlockBackoff`, monotonic clock) plus Argon2id cost.
  An offline copy of the vault bypasses backoff by design — the KDF is the
  defense. No change.

### F-17 — Info — WAL may retain freed blob pages until checkpoint — DOCUMENTED

- `purge()` relies on `PRAGMA secure_delete = ON`; a full `VACUUM` +
  `wal_checkpoint(TRUNCATE)` runs only on `wipeAll()`. Freed pages can linger
  in the WAL until the next checkpoint. Acceptable for the threat model; a
  checkpoint on purge is possible follow-up.

### F-18 — Info — Passkey live flow gated off — NO ACTION

- `core/passkey/PasskeyManager.kt` — `LIVE_ENABLED = false`; no private key
  material is created or stored. `PasskeyRecord` holds only public metadata
  inside the encrypted item blob. No action.

### F-19 — Info — OAuth `state` comparison not constant-time — NO ACTION

- `core/auth/OAuthPkce.kt` — `state != expectedState` uses a normal string
  comparison. The state is a 192-bit random token checked once over an online
  flow; timing leakage is negligible. No action.

### F-20 — Info — `StagedJoin` holds WebDAV password transiently — NO ACTION

- `sync/SyncManager.kt` — the staged-join object carries the WebDAV password
  as a `String` in memory until commit. Short-lived, never persisted or logged.
  Acceptable; a `CharArray` version would be marginally better.

---

## Areas checked and found sound (no finding)

- **Argon2id:** v1.3, id variant, default 64 MiB / 3 iter / 4 lanes; minimum
  19 MiB / 2 / 1 (OWASP minimum); header KDF params range-checked on decode;
  password normalized NFKC; HKDF-SHA256 domain separation per use
  (`refuge/wrap/pw|rec|pin/v1`, `refuge/backup/v1`, `refuge/autobackup/v1`,
  `refuge/sync/mac|manifest/v1`).
- **AES-256-GCM:** fresh random 96-bit nonce per seal; 128-bit tag; AAD binds
  vault header params, item id + rev + schema, attachment file id (both DEK and
  data layers), backup header, PIN (now vault-bound), biometric blob (now
  vault-bound). No IV reuse found; `sealWithNonce` is test-only.
- **Vault header:** exact-size decode, magic + version check, wrapped VK by
  password and by recovery (BIP-39 24 words, checksum verified).
- **RFGB backup:** header in AAD; v1/v2 parsing strict (version, KDF ranges,
  attachment index/size bounds, 10 MB per-attachment cap); plaintext wiped
  after decode; manual export wipes attachment plaintext after encoding.
- **Sync:** records HMAC-SHA256 with a vault-derived key, verified with
  constant-time compare **before** parsing; filename hash cross-checked;
  tombstones carry empty blob/key; manifest HMAC + monotonic epoch; HTTPS-only
  WebDAV; filename allow-list blocks path traversal; SAF folder remote caps
  reads at 8 MB.
- **Attachments (track A):** per-file random DEK, DEK wrapped with vault key,
  both layers AAD-bound to file id; restore re-seals with the current vault key
  and skips duplicate file ids; no plaintext in Room (only name/mime/size).
- **Multi-vault (track B):** separate header + salt + recovery phrase per vault;
  separate directories (`vault-<id>`, id allow-listed); registry written
  atomically; legacy migration moves files without re-encryption; cross-vault
  records rejected by MAC; delete/wipe paths vault-scoped.
- **Card scan (track C):** on-device ML Kit only — no image stored, uploaded or
  sent; OCR text processed in memory; card number accepted only with a valid
  Luhn check; expiry strictly `MM/YY` with month 01–12.
- **Autofill:** domain matching is strict (public-suffix aware, no
  `evil-github.com` / `github.com.evil.com` confusion); only trusted browsers'
  `webDomain` is accepted; HTTP origins refused (except localhost); multi-step
  memory is one-shot and origin-checked; nothing is offered when locked except
  an unlock action; save requires the vault open and the autosave opt-in.
- **Clipboard / screenshots:** sensitive flag + 30 s auto-clear; `FLAG_SECURE`
  on by default (opt-in screenshots); `allowBackup=false`, no cleartext,
  network-security-config cleartext-denied; FileProvider scoped to
  `cache/attachments/` only; minimal manifest permissions; only MainActivity
  and the Autofill service exported.
- **Importers:** Aegis scrypt params capped (N ≤ 2²⁰, 128·N·r ≤ 512 MiB),
  Bitwarden Argon2 capped, andOTP PBKDF2 iterations capped, JSON depth capped
  at 64, ZIP entries/size capped, CSV parser bounded; foreign KDF failures do
  not leak which password candidate failed beyond the GCM tag.
- **Logging/notifications:** `DiagLog`/`CrashReporter` record class names and
  stack lines only — no messages, no user input, no file names; task reminders
  show generic text only; no `Log.*`/`println` in main sources (guard-tested).
- **Session hygiene:** vault key kept only in memory, wiped on lock;
  auto-lock uses the monotonic clock; `keyCopy()` callers wipe (audited all
  call sites); backup/auto-backup passwords wiped after use.

## Files changed (this branch)

| File | Change |
|---|---|
| `core/security/PinUnlock.kt` | Vault-bound PIN AAD + legacy migration |
| `core/security/KeystoreSecretStore.kt` | AAD bound to store + key + legacy migration |
| `core/security/BiometricKeystore.kt` | Vault-bound AAD for enroll/unlock ciphers |
| `AuthController.kt` | Pass active vault id to biometric; disable stale blob; clear attachment cache on vault delete |
| `AppContainer.kt` | `clearAttachmentCache()`; called on secure wipe |
| `sync/WebDavRemote.kt` | Capped reads (`readCapped`, 8 MB) for GET + PROPFIND |
| `core/backup/Backup.kt` | `MAX_BACKUP_BYTES` (64 MB) enforced in `inspect()` |
| `core/storage/VaultRepository.kt` | Attachment ops vault-scoped (add/open/remove) |
| `sync/SyncManager.kt` | Auto-backup snapshots include attachments (v2) |
| `auth/ExternalAccountManager.kt` | `instanceFollowRedirects = false` (3 sites) |
| `core/security/BreachChecker.kt` | `instanceFollowRedirects = false` |
| `core/security/PinUnlockTest.kt` | Vault binding + legacy migration tests |
| `core/storage/AttachmentsTest.kt` | Non-default vault + cross-vault rejection tests |
| `core/storage/IdentityAndBackupTest.kt` | Oversized backup rejection test |
| `sync/WebDavRemoteTest.kt` | (new) `readCapped` tests |
| `docs/SECURITY_REVIEW_2026-11-22.md` | (new) this document |

## What this review did NOT do

- No device testing (no POCO F3 run, no launch-path verification).
- No dynamic analysis, no fuzzing beyond the existing suites, no dependency
  vulnerability scan beyond the existing CI guards.
- No review of the GitHub Actions workflows (out of scope for this branch; the
  token cannot modify them) — CI configuration itself was not audited.
- KeystoreSecretStore / BiometricKeystore changes are compile-verified only
  (no JVM unit test is possible without Robolectric); they need a device check:
  existing PINs migrate silently, existing biometric enrollments need a
  one-time re-enrollment (F-05).

## Recommendation

Ship these fixes, then still commission the independent third-party audit
before any stable release. The remaining known gaps for that audit: decode-time
KDF clamp (F-10), attachments-table `vaultId` column (F-06 follow-up),
per-field import caps (F-11), and a device pass over the biometric/PIN
migration paths.
