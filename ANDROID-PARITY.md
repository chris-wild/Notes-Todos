# Android parity plan

Status as of 1 October 2026. iOS version 1.0.1 (build 26) is waiting for App Store review. This document plans the work needed to bring the Android app up to the iOS feature set and launch it on Google Play.

## Purpose and scope

The Android app was rewritten in September 2026 and has not moved since the iOS monetisation work began. The iOS app has since gained metered recipe conversion, credit packs, a daily naming allowance, unit conversion and a series of usability fixes. The Android app still calls Anthropic directly with a key the user types in.

This plan covers four things. The first is feature parity with iOS 1.0.1. The second is the server changes Google Play purchases need. The third is the Android backup problem that was deferred in September. The fourth is the work required for a Google Play launch.

The shared Kotlin core already carries most of the business logic. Ingredient parsing, amalgamation, scaling and the use case are shared, and Android already passes the quantity multiplier through. Most of the remaining work is in the Android user interface, Google Play Billing and the metering Worker.

## Current state

Every row below was checked against the source on 1 October 2026. File references are relative to the repository root. `APP` is `app/src/main/java/uk/co/promptbuilt/notestodos/`.

| Feature | iOS 1.0.1 | Android today | Evidence |
|---|---|---|---|
| Metered conversion through the Worker | Yes | Absent. Bring-your-own key only | `APP/NotesTodosApp.kt:35-37` wires `ByoOcrService` |
| Credit packs and paywall | Yes | Absent. No billing dependency | `gradle/libs.versions.toml`, `AndroidManifest.xml:4` |
| Anonymous account identity | iCloud Keychain identifier | Absent | No `OpsAccount` equivalent under `app/` |
| Automatic naming of photographed recipes | Metered, 30 per day, manual-name dialog | Partial. Calls Anthropic directly, only when a key is stored, no allowance, fixed name "Photographed recipe" | `APP/ui/recipes/RecipesViewModel.kt:136-169` |
| Preferred units conversion | Yes | Absent | `core/.../data/SettingsRepository.kt` |
| Todos per-row delete | Yes | Present | `APP/ui/todos/TodosScreen.kt:267-273` |
| Todos Delete All, removing an emptied category | Yes | Absent | No Delete All in `TodosScreen.kt` or `TodosViewModel.kt` |
| Prominent Create ingredient list button | Yes | Partial. A small text button in the viewer header, shown only with a key | `APP/ui/recipes/RecipesScreen.kt:492-496` |
| Conversion dialog with quantities and cost | Yes | Partial. Inline stepper, no dialog, no cost | `RecipesScreen.kt:474`, `:499-508` |
| Open the new todo list after conversion | Yes | Partial. Switches tab but not category | `RecipesScreen.kt:241-245`, `TodosViewModel.kt:32-33` |
| Delete a recipe from the open recipe | Yes, with Cancel | Absent. Delete only from the list row | `RecipesScreen.kt:450` |
| Tappable links in recipe notes | Yes | Partial. Links work on note cards only | `APP/ui/common/LinkifiedText.kt`, `RecipesScreen.kt:510-517` |
| Compact photo PDFs | 2200 pixel JPEG at quality 0.6 | Unknown size. Bitmap up to 4000 pixels drawn into `PdfDocument` | `APP/data/RecipeFiles.kt:76-102` |
| Full backup | iCloud device backup | Auto Backup covers the database. Recipe PDFs only by manual zip or phone-to-phone transfer | `res/xml/data_extraction_rules.xml` |
| Tab order Recipes, Todos, Notes | Yes | Present | `APP/ui/AppScaffold.kt:33-37` |
| Two-pane layout on large screens | Yes (iPad) | Absent | No window size class or adaptive code |
| Light theme following the system | Yes | Absent. Dark-only by design | `APP/ui/theme/Theme.kt` |

Android builds against SDK 36 (minimum 26). The application ID is `uk.co.promptbuilt.notestodos` and the version is 0.2.0 (`app/build.gradle.kts:11-20`).

## Decisions needed before work starts

Each decision carries a recommendation. None of them should be settled by the implementer alone.

**D1. Application ID. Decided and done 1 October 2026.** The Android application ID is now `uk.co.promptbuilt.hobpad`, matching iOS (version 0.3.0, version code 3). The code namespace stays `uk.co.promptbuilt.notestodos`. The camera's FileProvider authority now derives from the application ID, because a hardcoded authority would have blocked installing the new app beside the old one. The OnePlus was migrated by copying the old app's data into the new one, verified identical (every record count, a hash of all note contents, and all 63 PDF checksums), and the old app was then uninstalled. The Anthropic key could not be carried over, since it was encrypted with a key belonging to the old app, so it must be re-entered.

**D2. Bring-your-own key in release builds.** On iOS the personal-key path exists only in Debug builds. The recommendation is the same for Android. Release builds meter through the Worker, and the key field becomes a debug-only developer tool.

**D3. Credit balances across platforms.** HobPad has no accounts. A customer with both an iPhone and an Android phone would therefore hold two separate balances, one per platform identity. Sharing a balance would require sign-in, which contradicts the product's position. The recommendation is to keep separate balances and state this in the support FAQ.

**D4. Product identifiers and prices.** Google Play product IDs may contain lowercase letters, digits, periods and underscores, so the iOS identifiers (`uk.co.promptbuilt.hobpad.ops50` and so on) are valid on Play. Reusing them keeps a single credit map on the Worker. The recommendation is to reuse the identifiers and the £1.99, £2.99 and £9.99 prices.

**D5. Android backup approach. Decided 1 October 2026.** Android's standard Auto Backup continues to back up the database and settings, and recipe PDFs are backed up separately to the Google Drive application data folder. See Phase 4.

## Phased plan

Sizes are relative and ESTIMATED, not measured. They indicate order of magnitude only.

### Phase 0. Start the slow, calendar-bound work first

Some Google Play steps take calendar time regardless of engineering effort, so they should begin before any code is written.

1. **Answered 1 October 2026.** A Google Play developer account exists. It is the personal account registered for RiderNav (`/Volumes/DATA/Projects/RiderNav/ridernav/GOOGLE_DEPLOYMENT_CHECKLIST.md`).
2. **Applies.** Because it is a personal account created after 13 November 2023, Google requires a closed test with at least 12 testers, opted in for 14 continuous days, before production access can be requested. Only closed testing counts; internal testing does not. Recruiting testers is therefore the critical path for the whole launch. **On hold (1 October 2026):** Chris will recruit a new group of testers for HobPad rather than reuse RiderNav's, so the closed test is not to be set up until he provides them.
3. Set up a Google payments merchant profile, which selling credit packs requires.
4. Create the Play Console app record under the ID chosen in D1.
5. Create a Google Cloud service account with Play Developer API access, which the Worker needs to verify purchases.

Size: small in effort, long in elapsed time.

### Phase 1. Usability parity that needs no server work

These items are independent of billing and can ship to the OnePlus over adb as soon as each is done.

1. **Todos Delete All.** Add the Delete All action with a confirmation, and remove an emptied non-default category in the same operation, mirroring `TodosModel.delete(ids:removeCategory:)` in `ios/HobPad/Todos/TodosModel.swift`.
2. **Recipe viewer actions.** Add Edit and Delete to the open recipe. The delete confirmation must appear over the open recipe and offer Cancel.
3. **Create ingredient list flow.** Replace the header text button with a full-width primary button, move the quantities stepper into a conversion dialog, and open the newly created todo category afterwards. This last step needs the target category passed to the Todos screen, as iOS does with `AppServices.pendingTodoCategory`.
4. **Linked recipe notes.** Render recipe notes in the viewer with the existing `LinkifiedText` composable.
5. **Date-stamped fallback names.** Replace the fixed "Photographed recipe" with "Photographed 1 Oct 2026", adding " (2)", " (3)" and so on for duplicates, as `RecipesModel.photographedFallbackName()` does on iOS.
6. **Photo PDF size.** Photograph a recipe on the OnePlus and measure the stored PDF. iOS produced 29 MB single-page PDFs until its encoder was fixed, and the Worker rejects anything over 15 MB. If Android's PDFs are large, write the photo as a JPEG at a 2200 pixel long edge and embed the JPEG bytes directly in the PDF, then add a one-off compaction pass for PDFs already stored, as `ios/HobPad/Recipes/PdfCompactor.swift` does. This item is a prerequisite for metering.

7. **Light theme.** Add a light colour scheme that follows the system setting, as iOS does. The app is currently dark-only by design (`APP/ui/theme/Theme.kt`).

Every change is checked on the emulator and the OnePlus, in light and dark themes once item 7 exists, and on both the inner and cover screens of the foldable.

Size: medium in total. Item 6 is the only one with technical uncertainty.

### Phase 2. Commerce foundation

This phase is the critical path. Its server half can proceed in parallel with Phase 1.

**Worker changes** (`backend/ops`):

1. A Google purchase endpoint. The client sends the package name, product ID and purchase token. The Worker calls the Google Play Developer API to fetch the purchase, then checks four things: the purchase state is purchased, the product is in `PRODUCT_CREDITS`, the purchase's obfuscated account ID matches the caller's bearer token, and test purchases are accepted only where configured. Unlike Apple's signed transactions this verification is an online call, authenticated with a service-account token that the Worker signs with WebCrypto and caches.
2. Idempotent crediting through the existing account object, keyed by the Google order ID under a `google:` prefix so it can never collide with an Apple transaction ID. The existing `hasPurchased()` check and the naming exemption then work unchanged.
3. Refund handling. Google reports voided purchases through Real-time Developer Notifications (delivered by Cloud Pub/Sub push) or through the Voided Purchases API. The Phase 2 spike should choose one. A refund debits the pack through the existing `refundPurchase` path.
4. Configuration. The service-account key is stored as a Worker secret on both deployments, and a variable controls whether test purchases are accepted, mirroring `ALLOWED_ENVIRONMENTS`.
5. Tests in the existing vitest suite, with the Google API mocked in the same way the Anthropic upstream is.

**Android changes**:

1. **Account identity.** Generate an anonymous identifier once and store it where it survives reinstalling the app and moving to a new phone. The candidate is Google's Block Store API, which is designed for exactly this. It must not live in `secure_prefs.xml`, which is deliberately excluded from backup. This needs a short spike to confirm Block Store's behaviour on the OnePlus.
2. **Metered wiring.** Wire `SwitchingOcrService` and `MeteredOcrClient` in `NotesTodosApp`, with the personal key honoured only in debug builds (D2).
3. **Google Play Billing.** Load the three products, launch purchases with the account identifier set as the obfuscated account ID, submit each purchase to the Worker, and consume it only after the Worker confirms the credit. Google refunds purchases that are not acknowledged within three days, and consuming counts as acknowledging, so a failed submission must be retried promptly. Unfinished purchases are recovered at launch with `queryPurchasesAsync`.
4. **Credits interface.** A paywall screen, the balance in Settings, and the conversion gate that iOS uses. Re-converting a recipe with cached ingredients is free and asks nothing. A first conversion shows its cost in credits, and a shortfall opens the paywall. The page-count preview can use Android's `PdfRenderer`.

Size: large. The Worker verification and the purchase-to-consume flow carry the most risk.

### Phase 3. Features that depend on metering

1. **Daily naming allowance.** Name photographs through the metered title endpoint instead of calling Anthropic directly. Allow 30 automatic namings per day, with no limit while purchased credits remain, using the `purchased` flag from `/v1/balance`. Past the limit, show the dialog with Save, Buy credits and Cancel, as `ios/HobPad/Recipes/RecipesTab.swift` does.
2. **Preferred units.** Add the Units setting (Automatic, Metric, US) to `SettingsRepository` and pass it to `MeteredOcrClient`. Android only exposes the locale's measurement system directly from API 28 onwards, while the app supports API 26, so Automatic needs a fallback that maps the region code (the United States, Liberia and Myanmar use US customary units).

Size: small to medium. Both features reuse server behaviour that is already live.

### Phase 4. Backup

Android's standard mechanism is Auto Backup, which copies app data to the user's Google Drive roughly nightly with no user action. Google allows each app 25 MB, and an app over that limit is not backed up at all. A real recipe collection exceeds it: the September migration archive of Chris's data was 60 MB, almost entirely recipe PDFs, while the database was about 200 KB. The backup rules therefore exclude recipe PDFs from cloud backup so that everything else stays within the limit.

Device-to-device transfer follows separate rules with no documented limit, and HobPad's rules already include the PDFs there. A customer moving to a new phone with the old one to hand keeps everything. The gap is a cloud restore after a phone is lost, broken or reset, which brings back the database without the PDF files.

The decided solution keeps Auto Backup for the database and adds the Google Drive application data folder for the PDFs. This is a hidden folder in the customer's own Drive, private to the app and invisible in the Drive interface. Access needs the `drive.appdata` permission, which Google classifies as non-sensitive, so no Google app verification is required. The customer grants it once with a standard Google consent prompt. HobPad still has no accounts of its own.

The design has five parts.

1. **Consent.** A Settings switch turns on recipe file backup and requests the `drive.appdata` permission through Google's authorization client. Background work afterwards obtains access silently.
2. **Upload.** Background work uploads every local recipe PDF that the hidden folder does not yet hold. It runs on a schedule and after recipes are added.
3. **Restore.** At launch, any attachment the database references whose file is missing locally is downloaded from the hidden folder. This closes the cloud-restore gap.
4. **Deletion.** Remote files are deleted only when the app records a local deletion. Absence is never treated as deletion, because a freshly installed phone with an empty database must never erase the customer's backup.
5. **Status.** Settings shows whether backup is on, when it last completed, and whether the permission needs granting again.

External setup is required before an end-to-end test. A Google Cloud project with the Drive API enabled needs an Android OAuth client registered for the app's package name and signing certificate fingerprint, one for the debug certificate and one per release certificate.

The account identifier must also survive a restore, which Phase 2's identity work covers.

**Implementation status, 1 October 2026. Built and verified end to end.** The code is in `app/src/main/java/uk/co/promptbuilt/notestodos/backup/` (`DriveBackup`, `DriveAppDataClient`, `DrivePdfSyncPlan`, `DriveSyncWorker`) with the Settings section in `ui/recipes/DriveBackupSection.kt`. The sync rules have ten unit tests, including one proving that a fresh install never deletes the backup. The Google Cloud project `hobpad` has the Drive API enabled, a consent screen in testing mode with Chris as the only test user, and an Android OAuth client for the debug signing certificate.

Verified on Chris's OnePlus against his real data. The first pass backed up all 63 recipe PDFs. Two PDFs deleted behind the app's back were restored at the next launch with identical checksums. A newly saved recipe's PDF was uploaded within about 25 seconds, and deleting that recipe in the app removed its Drive copy within about 20 seconds. Testing found and fixed one bug: a PDF is stored when it is picked but only becomes worth keeping when the recipe is saved, so the backup now also runs whenever the set of attachments changes.

**Before launch.** Status as of 1 October 2026.

1. **Done.** The consent screen's branding carries the hobpad.app home page, the privacy policy link and the authorised domain `hobpad.app`, with no logo so that Google verification is not required. The consent screen is published in production.
2. **Done.** A HobPad upload key exists at `~/keystores/hobpad-upload.jks` (SHA-1 `3A:27:85:15:A6:68:C7:24:A8:6A:59:FC:12:14:5B:0C:B2:7D:5A:8B`), its credentials are in `~/.gradle/gradle.properties` under `HOBPAD_UPLOAD_*`, and release builds sign with it. HobPad exists in Play Console under `uk.co.promptbuilt.hobpad`, with a first bundle (version code 3) saved as an unreleased internal testing draft, which caused Google to generate the Play app signing key (SHA-1 `2E:BA:76:CC:AB:56:09:86:0A:F6:B3:B4:3D:27:E0:E0:89:A2:BC:D0`). Google Cloud holds Android OAuth clients for `uk.co.promptbuilt.hobpad` with the debug key, the upload key and the Play app signing key. The two clients for the retired `uk.co.promptbuilt.notestodos` ID are now unused and can be deleted.
3. **Done.** `site/privacy.html` describes recipe file backup on Android, including the Google API Services User Data Policy statement, and `site/support.html` answers the backup question for Android as well as iOS.

Size: medium.

### Phase 5. Google Play launch

1. **Done.** The application ID chosen in D1 is adopted and the OnePlus data migrated.
2. **Done.** Play App Signing is active and release bundles are built with `bundleRelease`, signed with the upload key.
3. Create the three products in Play Console with the agreed prices.
4. Complete the Data safety form, the content rating questionnaire and the target audience declaration.
5. Adapt the approved App Store description and keywords for the Play listing, and produce phone screenshots from the same clean fixture data used for iOS.
6. Update `site/privacy.html`, which currently describes only iCloud Backup, the iCloud Keychain identifier and Apple purchases. It needs equivalent statements for Google backup, the Android identifier and Google Play purchases.
7. Run the closed test from Phase 0, then request production access.

Size: medium in effort, with the elapsed time set by Phase 0.

### Phase 6. Large screens (after launch)

The OnePlus is a foldable, and its inner screen is close to a tablet. A list-and-detail layout for Recipes and Notes, built with the Compose adaptive layout libraries, would match the iPad experience. This is polish, not a launch requirement.

## Sequencing

Phase 0 starts immediately because its waits are fixed. Phase 1 and the Worker half of Phase 2 can run side by side. The Android half of Phase 2 depends on the Phase 0 accounts and on Phase 1 item 6. Phase 3 depends on Phase 2. Phase 4 can happen at any point but must finish before launch. Phase 5 closes out once the closed test has run its course.

## Testing approach

1. **Worker.** Extend the vitest suite for Google verification, idempotency, account matching, test-purchase gating and refunds, then smoke-test the staging Worker with real purchase tokens from licence testers.
2. **Billing.** Use Play Console licence testers on the internal testing track, so purchases complete without charge, and watch the production ledger for the first real pack in the same way the iOS chain was proven.
3. **Interface.** Test on the `ridernav_api36` emulator and on the OnePlus, in light and dark themes, on both of the foldable's screens.
4. **Regression.** Confirm the shared core changes keep the iOS build and its behaviour unchanged.

## Risks

1. **Closed testing requirement.** If it applies, launch cannot happen sooner than 14 days after 12 testers have opted in.
2. **Online purchase verification.** Google verification depends on a network call and a service-account credential, unlike Apple's offline signature check. Failures must leave the purchase unconsumed so the client retries, and the Worker must never credit without confirmation.
3. **Three-day acknowledgement window.** A purchase that is never consumed is refunded automatically. Prompt retry logic is essential.
4. **Identity persistence.** If Block Store does not behave as expected on the target devices, customers could lose their balance on reinstall. The spike must prove this before any pack is sold.
5. **Photo PDF size.** Not yet measured on Android. Large PDFs would be rejected by the Worker's 15 MB limit.

## Verified facts and assumptions

**Verified on 1 October 2026** (source inspection, cited above):

1. The Android app uses only the bring-your-own-key path and has no billing, account identity or units setting.
2. The shared ingredient logic, including the multiplier, is already used by Android.
3. Android lacks Delete All, viewer delete, linked recipe notes and the conversion dialog, and uses the fixed fallback name "Photographed recipe".
4. The privacy policy described only Apple platform behaviour until 1 October 2026, when the Android backup section was added.
5. The Google Play developer account is a personal account, so the 12-tester, 14-day closed test applies (RiderNav's `GOOGLE_DEPLOYMENT_CHECKLIST.md`).

**Assumptions requiring confirmation:**

1. Block Store's suitability for the account identifier (needs a device spike).
2. The size of Android photo PDFs (not measured).
3. The choice between Real-time Developer Notifications and the Voided Purchases API for refunds (needs the Phase 2 spike).
4. Whether files in the Drive application data folder count against the customer's Drive storage (Google's page does not say; expected but unconfirmed).

## Open questions for Chris

1. Do you agree with the recommendations in D1 to D4?
2. Should hobpad.app say "Coming soon to Google Play" once Phase 2 begins, or stay iOS-only until launch?
