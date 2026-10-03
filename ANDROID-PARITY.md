# Android parity plan

Status as of 3 October 2026, 10:00. iOS version 1.0.1 is waiting for App Store review with build 27. It was resubmitted on 2 October with a screen recording and written answers to Apple's information request (Guideline 2.1), and builds 28 to 30 are in TestFlight only. Android version 0.4.7 (version code 11) is live on the Google Play internal testing track for Chris only. As of iOS build 30 and Android 0.4.7 the two apps have the same features. This document plans the work needed to bring the Android app up to the iOS feature set and launch it on Google Play. The next section lists what is left. The sections after it are the original plan, kept as the record of how the work was scoped, with status notes added where items have since been completed.

## What is left

Every item below was checked on 2 October 2026 against the source, App Store Connect, Play Console, the connected OnePlus or the live Worker. Items are grouped by who can act on them.

### Built and proven

1. Metered conversion through the Worker, with the personal key confined to debug builds.
2. Credit packs through Google Play Billing. The three packs exist in Play Console (`uk.co.promptbuilt.hobpad.ops50`, `ops100` and `ops500`, at £1.99, £2.99 and £9.99 in the UK including VAT), and a licence-test purchase was credited by the production Worker (see "First Google Play purchase" below).
3. The daily naming allowance, the no-recipe naming fix and the Units setting.
4. Recipe file backup to the Google Drive application data folder.
5. The conversion flow, the fix for the crash when opening a photographed recipe, and pinch-to-zoom whose limits match iOS PDFView (0.25 to 5 times the page size).
6. The seven behaviour gaps from the parity audit of 1 October (see "Parity audit" below), fixed in 0.4.4 and checked on the OnePlus through the side-by-side HobPad dev build.
7. Photographs stored as JPEG pages turned upright from their EXIF orientation, and a compaction pass at launch that shrinks any stored PDF over 8 MB, as iOS does.
8. New on both platforms on 2 October, in iOS builds 28 and 29 and Android 0.4.5 and 0.4.6:
   1. A recipe added with + and a blank name is named automatically from its first attachment. Only the first page is sent, which was measured on the staging Worker at 143,900 bytes from iOS and 195,936 bytes from Android for a 19-page, 2.3 MB PDF. A typed name is kept and makes no naming call.
   2. Delete All Notes at the foot of the Notes sort menu, behind a confirmation.
   3. The Todos tab no longer has a search field.
   4. Settings says that naming new recipes automatically is free.
9. Play Console foundations: the HobPad payments profile, enrolment in the 15% service fee, the purchase-verification service account, licence testing, and Android developer verification.
10. The Android paywall keeps showing Google Play's response code under the message when the credit packs cannot load. Chris decided on 2 October to keep it, because it helps support.
11. The hobpad.app support page answers how credits survive a new Android phone and explains that iPhone and Android keep separate balances (decision D3, accepted), and the home page says HobPad is coming soon to Google Play. Both were deployed on 2 October.
12. The privacy policy at hobpad.app now covers Google Play purchases and naming of recipes added without a name. It was deployed on 2 October.

### Free credits once per device, and deletion on request (3 October)

Built on both platforms and live in production. New installs from iOS build 30 and Android 0.4.7 start at zero, and the Worker grants the 5 free credits only to a device whose platform bit says it has not had them: Apple DeviceCheck bit0 on iOS, Play Integrity device recall bitFirst on Android. RiderNav must never use those two bits. Older builds keep the old grant while the Worker's `LEGACY_FREE_OPS` is "1". Deletion requests are handled with `scripts/ops-erase.sh`, by Google Play order number or account identifier. Verified: 68 Worker tests; staging and production checked against Apple and Google; a real DeviceCheck token from the iPhone 11 Pro accepted by Apple's development server; new installs on the simulator and emulator granted through the staging test check.

Still open on this work:

1. **Google device recall access.** The beta interest form was submitted on 3 October; Google replies only if HobPad is chosen. Until then, new Android installs on 0.4.7 get no free credits (their grant stays pending and is retried). If Google declines, Android needs another way to recognise a device before public launch.
2. **The first real device recall answer.** Google documents empty values only for "unavailable", so the Worker waits rather than grants on an empty answer. Once access is granted, check what a never-written device returns on the OnePlus and adjust if it is empty.
3. **App Store order lookups.** The In-App Purchase key "HobPad order lookup" (YY79KZUR2C) was created on 3 October and is set on both Workers. Apple's sandbox server accepts it, but the production server answered 401 at first. That may be the new key propagating, or production not answering for an app that is not yet on the App Store. Retry a lookup against production before relying on it for a real request; the Worker asks both environments, so sandbox orders are found either way.
4. **Website.** Done. The privacy policy ("Free credits and device checks", "Deleting your data") and the support answer "How do I delete my data?" were deployed on 3 October.
5. **Turn `LEGACY_FREE_OPS` to "0"** once no build from before device checks is in use, which for iOS means after a version newer than 1.0.1 (build 27) is released.
6. **App Review notes for the next version.** A reviewer device that has had HobPad's free credits before will not get them again; say so, and note that conversion can be tested with a sandbox purchase at no cost.

### Still to verify

1. **0.4.7 on the OnePlus.** Update HobPad from the Play Store, then check blank-name naming with a photo, Delete All Notes and the Todos tab. The existing account keeps its credits.
2. **Delete All Notes, run end to end.** The menu item and confirmation were checked on the iOS simulator and the Android emulator, cancelling each time. No real wipe has been run, because both test devices hold copies of Chris's notes.
3. **Untested paths.** Three paths have not been exercised on Android. The first is the paywall opening when a conversion needs more credits than the balance holds. The second is a refund. Refunding test order GPA.3358-4385-8365-92846 in Play Console should cause the Worker's six-hourly voided-purchases check to remove 50 credits. The third is a full cloud restore of the database onto a new phone.
4. **Android 8.0 and 8.1.** The fallback for Automatic units on these versions has not been run on a device, because no Android 8 emulator is installed.

### Decisions for Chris

1. **The next iOS version.** Build 29 cannot reach the App Store until a new version is created after 1.0.1. That version should also carry three changes. The first is the corrected App Store description, which still says photography is free without limits and that recipe content is sent only for conversion. The second is the two replacement Todos screenshots in `app-store/screenshots/`. The third is the version number, which only Chris changes.

### Play Console store setup

The draft in `play-listing/play-store-listing.md` covers the store listing, the ten app content declarations and the data safety answers, and the graphics and four phone screenshots are ready in `play-listing/`. None of it has been entered in Play Console yet. The privacy policy prerequisite is now met.

### Closed test and production access

On hold until Chris recruits HobPad testers. Google requires at least 12 testers opted in for 14 continuous days on the closed testing track before production access can be requested. This sets the earliest possible launch date.

### Production configuration at launch

1. Turn off `ALLOW_GOOGLE_TEST_PURCHASES` on the production Worker before the public Android release. It is on now, which is how the licence-test purchase was credited.
2. After iOS 1.0.1 is released, tighten `ALLOWED_ENVIRONMENTS` on the production Worker from "Production,Sandbox" to "Production".

### Shared with iOS

1. The App Review outcome for iOS 1.0.1 with build 27. Build 27 already carries commit dd7122c, the shared title parser. Release is manual and Chris decides when.

### Housekeeping

1. On the OnePlus, remove HobPad dev (`uk.co.promptbuilt.hobpad.dev`) once 0.4.6 has been checked, and turn Recipe file backup back on in HobPad's Settings.
2. On the iPhone 11 Pro, install build 30 from TestFlight, which replaces the development build installed on 3 October for the DeviceCheck probe, and delete the HobPadUITests runner app.
3. The Android emulator is still set to 1080 by 1920 at density 420 for the Play screenshots. Reset it with `adb shell wm size reset` and `adb shell wm density reset` when the screenshots are final.
4. Keep offline copies of `~/keystores/hobpad-upload.jks` and `~/keystores/hobpad-play-service-account.json`.
5. Delete the two Google Cloud OAuth clients for the retired `uk.co.promptbuilt.notestodos` ID.
6. Optionally, give the Cloudflare API token read access to Workers observability, so the Worker's request log can be checked without the phone.
7. Optionally, upload native debug symbols. Play warns on every upload that none are attached, but the AndroidX native libraries ship already stripped, so this is not currently possible.

### After launch

Phase 6 (large screens) below.

## Purpose and scope

The Android app was rewritten in September 2026 and has not moved since the iOS monetisation work began. The iOS app has since gained metered recipe conversion, credit packs, a daily naming allowance, unit conversion and a series of usability fixes. The Android app still calls Anthropic directly with a key the user types in.

This plan covers four things. The first is feature parity with iOS 1.0.1. The second is the server changes Google Play purchases need. The third is the Android backup problem that was deferred in September. The fourth is the work required for a Google Play launch.

The shared Kotlin core already carries most of the business logic. Ingredient parsing, amalgamation, scaling and the use case are shared, and Android already passes the quantity multiplier through. Most of the remaining work is in the Android user interface, Google Play Billing and the metering Worker.

## Current state

This table records the starting point on the morning of 1 October 2026. "What is left" above gives the current state. Every row below was checked against the source on 1 October 2026. File references are relative to the repository root. `APP` is `app/src/main/java/uk/co/promptbuilt/notestodos/`.

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

**D2. Bring-your-own key in release builds. Decided and done 1 October 2026.** As on iOS, release builds meter through the Worker and the personal key is a debug-only developer tool.

**D3. Credit balances across platforms.** HobPad has no accounts. A customer with both an iPhone and an Android phone would therefore hold two separate balances, one per platform identity. Sharing a balance would require sign-in, which contradicts the product's position. The recommendation is to keep separate balances and state this in the support FAQ.

**D4. Product identifiers and prices.** Google Play product IDs may contain lowercase letters, digits, periods and underscores, so the iOS identifiers (`uk.co.promptbuilt.hobpad.ops50` and so on) are valid on Play. Reusing them keeps a single credit map on the Worker. The recommendation is to reuse the identifiers and the £1.99, £2.99 and £9.99 prices.

**D5. Android backup approach. Decided 1 October 2026.** Android's standard Auto Backup continues to back up the database and settings, and recipe PDFs are backed up separately to the Google Drive application data folder. See Phase 4.

## Phased plan

Sizes are relative and ESTIMATED, not measured. They indicate order of magnitude only.

### Phase 0. Start the slow, calendar-bound work first

Some Google Play steps take calendar time regardless of engineering effort, so they should begin before any code is written.

1. **Answered 1 October 2026.** A Google Play developer account exists. It is the personal account registered for RiderNav (`/Volumes/DATA/Projects/RiderNav/ridernav/GOOGLE_DEPLOYMENT_CHECKLIST.md`).
2. **Applies.** Because it is a personal account created after 13 November 2023, Google requires a closed test with at least 12 testers, opted in for 14 continuous days, before production access can be requested. Only closed testing counts; internal testing does not. Recruiting testers is therefore the critical path for the whole launch. **On hold (1 October 2026):** Chris will recruit a new group of testers for HobPad rather than reuse RiderNav's, so the closed test is not to be set up until he provides them.
3. **Done 1 October 2026.** Set up a Google payments merchant profile, which selling credit packs requires.
4. **Done 1 October 2026.** Create the Play Console app record under the ID chosen in D1.
5. **Done 1 October 2026.** Create a Google Cloud service account with Play Developer API access, which the Worker needs to verify purchases.

Size: small in effort, long in elapsed time.

### Phase 1. Usability parity that needs no server work

These items are independent of billing and can ship to the OnePlus over adb as soon as each is done.

1. **Done in 0.4.3.** **Todos Delete All.** Add the Delete All action with a confirmation, and remove an emptied non-default category in the same operation, mirroring `TodosModel.delete(ids:removeCategory:)` in `ios/HobPad/Todos/TodosModel.swift`.
2. **Done in 0.4.3.** **Recipe viewer actions.** Add Edit and Delete to the open recipe. The delete confirmation must appear over the open recipe and offer Cancel.
3. **Done.** **Create ingredient list flow.** Replace the header text button with a full-width primary button, move the quantities stepper into a conversion dialog, and open the newly created todo category afterwards. This last step needs the target category passed to the Todos screen, as iOS does with `AppServices.pendingTodoCategory`.
4. **Done.** **Linked recipe notes.** Render recipe notes in the viewer with the existing `LinkifiedText` composable.
5. **Done.** **Date-stamped fallback names.** Replace the fixed "Photographed recipe" with "Photographed 1 Oct 2026", adding " (2)", " (3)" and so on for duplicates, as `RecipesModel.photographedFallbackName()` does on iOS.
6. **Done in 0.4.1.** **Photo PDF size.** Photograph a recipe on the OnePlus and measure the stored PDF. iOS produced 29 MB single-page PDFs until its encoder was fixed, and the Worker rejects anything over 15 MB. If Android's PDFs are large, write the photo as a JPEG at a 2200 pixel long edge and embed the JPEG bytes directly in the PDF, then add a one-off compaction pass for PDFs already stored, as `ios/HobPad/Recipes/PdfCompactor.swift` does. This item is a prerequisite for metering.

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
3. **Done 1 October 2026.** Create the three products in Play Console with the agreed prices.
4. Complete the Data safety form, the content rating questionnaire and the target audience declaration.
5. Adapt the approved App Store description and keywords for the Play listing, and produce phone screenshots from the same clean fixture data used for iOS.
6. Update `site/privacy.html`, which currently describes only iCloud Backup, the iCloud Keychain identifier and Apple purchases. It needs equivalent statements for Google backup, the Android identifier and Google Play purchases.
7. Run the closed test from Phase 0, then request production access.

Size: medium in effort, with the elapsed time set by Phase 0.

### Phase 6. Large screens (after launch)

The OnePlus is a foldable, and its inner screen is close to a tablet. A list-and-detail layout for Recipes and Notes, built with the Compose adaptive layout libraries, would match the iPad experience. This is polish, not a launch requirement.

## Status, 1 October 2026 (evening)

Chris asked for full iOS parity, including the web service and the retirement of the on-device key. The following is built, committed and verified on the emulator against the staging Worker.

1. **Metered conversion (D2 done).** Android uses `MeteredOcrClient` through `SwitchingOcrService`, with the personal key honoured only in debug builds and its Settings section absent from release builds (verified in a release build). Debug builds use the staging Worker and release builds production, as on iOS.
2. **Account identity.** `store/OpsAccount.kt` mints an anonymous UUID in a plain SharedPreferences file that Android's backup and phone-to-phone transfer include. Block Store was not needed for this.
3. **Credits and Play Billing.** `store/OpsStore.kt` (Billing Library 8.0.0) loads the three packs, buys with the account as the obfuscated account id, submits to the Worker and consumes only after a 2xx, and resubmits unconsumed purchases at launch and on paywall open. The paywall, the Settings credits section and the conversion gate mirror iOS.
4. **Conversion flow.** The viewer has an always-visible, full-width Create ingredient list button. The conversion dialog holds the quantities stepper and states the credit cost or the free re-run, and success opens the new todo category. Verified: a recipe in cups and pounds converted to grams through the staging Worker, one credit was charged, and the re-run showed as free.
5. **Naming allowance and units (Phase 3 done).** Photographed recipes get date-stamped names and are named through the metered service within 30 per day, with the purchased-credit exemption and the Save, Buy credits and Cancel dialog. The Units setting (Automatic, Metric, US) is passed to extraction. Verified on the emulator.
6. **Worker.** `POST /v1/purchase/google` and a six-hourly voided-purchases refund pass (`src/google.js`, `src/google-orders.js`), 41 of 41 tests passing, deployed to **staging only**. Production is deliberately unchanged while iOS 1.0.1 is in App Review.

**Formerly blocked, all resolved on 1 October 2026.**

1. **Merchant account.** A separate organisation payments profile named HobPad was created, so that HobPad income stays apart from Chris Wild Photographic. Chris added the payout bank account.
2. **Service account for purchase verification.** `play-purchases@hobpad.iam.gserviceaccount.com` exists with its key stored as `GOOGLE_SERVICE_ACCOUNT_JSON` on both Workers. It has Play Console permissions for the HobPad app only, not account-wide.
3. **Products and a real purchase test.** The three packs were created and a licence-tester purchase was verified end to end. Play's bulk pricing takes a price before VAT, so the bases are £1.66, £2.49 and £8.33, which become £1.99, £2.99 and £9.99 in the UK.
4. **Production Worker.** Deployed on Chris's instruction while iOS 1.0.1 was still in review, carrying the Google purchase route, the voided-purchases check, units and the no-recipe naming fix. All of these were verified against the live Worker.

**Fixed: titles for pages with no recipe.** Naming a photograph that contains no recipe used to return a junk title from the model (for example "Unable to determine - document contains abstract geometric shapes"). With Chris's approval (1 October 2026), the naming prompt now returns a null title for such pages, and the Worker answers 422 `no_recipe`, so every client, including the shipped iOS build, shows the name dialog instead. This is committed (dd7122c) and live on the staging Worker. It reaches production with the next production Worker deploy, which waits for iOS 1.0.1 to clear App Review. A page with some text but no recipe can still be named from that text, which is acceptable.

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
4. **Identity persistence.** Block Store was not used. The account identifier lives in backed-up preferences, so it returns with Android's backup or phone-to-phone transfer. A reinstall before Android's first nightly backup loses it, as the next risk describes.
5. **Restore timing.** Android's Auto Backup runs roughly once a day. A phone that loses or uninstalls HobPad before the first backup has nothing to restore. This happened on 1 October, when the OnePlus moved from the debug build to the Play build within hours of the application ID change. The data was recovered from a migration archive. A new customer who reinstalls on their first day would lose their notes, todos and recipe records.
6. **Photo PDF size.** Resolved. Photographs are converted at 2200 pixels on the long edge, as on iOS, giving PDFs of about 0.9 MB, well inside the Worker's 15 MB limit.

## Verified facts and assumptions

**Verified on 1 October 2026** (source inspection, cited above):

1. The Android app uses only the bring-your-own-key path and has no billing, account identity or units setting.
2. The shared ingredient logic, including the multiplier, is already used by Android.
3. Android lacks Delete All, viewer delete, linked recipe notes and the conversion dialog, and uses the fixed fallback name "Photographed recipe".
4. The privacy policy described only Apple platform behaviour until 1 October 2026, when the Android backup section was added.
5. The Google Play developer account is a personal account, so the 12-tester, 14-day closed test applies (RiderNav's `GOOGLE_DEPLOYMENT_CHECKLIST.md`).

**Assumptions requiring confirmation:**

1. Block Store's suitability for the account identifier. No longer relevant, because backed-up preferences are used instead.
2. The size of Android photo PDFs. Measured on the emulator at about 0.9 MB for a 4000 by 3008 photo after the 0.4.1 change.
3. The choice between Real-time Developer Notifications and the Voided Purchases API for refunds. The Voided Purchases API was chosen. It runs every six hours and has not yet been exercised by a real refund.
4. Whether files in the Drive application data folder count against the customer's Drive storage (Google's page does not say; expected but unconfirmed).

## Open questions for Chris

1. D1, D2 and D5 are settled and D4 was carried out as recommended. D3 (separate balances per platform) still needs a decision.
2. Should hobpad.app say "Coming soon to Google Play" once Phase 2 begins, or stay iOS-only until launch?

## First Google Play purchase, 1 October 2026

The Play billing chain is proven end to end on Chris's OnePlus. Chris bought the 50-credit pack as a licence tester from the internal-testing build (0.4.2). Google recorded order GPA.3358-4385-8365-92846 as a test purchase in the PROCESSED state. The Play Developer API, queried with the HobPad service account, reports the purchase as consumed and acknowledged, which the app does only after the production Worker has answered 2xx. The production Worker reports the purchasing account at a balance of 54 credits with the purchased flag set, which is the 5 free credits plus the 50 purchased, less 1 credit spent.

Production state after this test: the Worker accepts licence-test purchases (`ALLOW_GOOGLE_TEST_PURCHASES = "1"`). This must be switched off before the Android app is released publicly.

## Parity audit, 1 October 2026

A line-by-line comparison of every iOS and Android screen found six behaviour gaps. The pop-out showed one page only. The pop-out was not discoverable. There was no zoom inside the recipe viewer. Long recipe notes were cut off in the viewer. Opening a note went straight into editing, so its links could not be tapped. There was no light theme. A seventh gap was found while testing: long recipe notes pushed the Update Recipe button off the edit dialog. All seven were fixed in version 0.4.4 (commit 964c78f).

Three differences are deliberate and stay. Android uses visible icons and inline add boxes in place of iOS swipe actions and + buttons. Export and Import backup are in released Android builds, while iOS keeps them to development builds. Android has the Google Drive recipe-file backup, because Android's own backup cannot hold recipe files.
