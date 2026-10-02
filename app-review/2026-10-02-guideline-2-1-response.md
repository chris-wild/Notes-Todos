# App Review response: Guideline 2.1, Information Needed

Submission ID a917af80-909f-4f29-98be-a1e1315714da, HobPad 1.0.1 (build 26, replaced by build 27 for this reply). Apple asked for this information in the Resolution Center reply and in the Notes field of App Review Information. The text below the line is written to be pasted into both.

---

Thank you for reviewing HobPad. The requested information follows, numbered as in your message.

**1. Screen recording**

The attached recording was captured on an iPhone 11 Pro running iOS 26.7, using build 27 installed from TestFlight. It begins at the Home Screen, launches HobPad and shows the typical flow: adding a recipe from a photograph of a printed recipe, opening it, creating an ingredient shopping list from it, using the Todos and Notes tabs, the Settings screen, and the purchase screen for a credit pack. The recording stops at the purchase sheet, before the Apple ID password is entered. HobPad has no account registration, no sign-in and no account deletion, because it has no accounts. It has no user-generated content that is shared with other people, so it needs no reporting or blocking mechanism. Everything a person writes or photographs is stored only on their own device. Section 4 describes the requests that send a recipe page to our service.

**2. Purpose and target audience**

HobPad is for home cooks. It keeps recipes, notes and todo lists in one place. A recipe can be photographed with the camera or added from a PDF or an image, and it is stored on the device as a PDF. The app's distinctive feature is recipe conversion. With one tap, HobPad reads a recipe page and turns its ingredients into a shopping list in the Todos tab, scaled for the number of people being cooked for and converted into the user's preferred units of measurement. This removes the work of copying ingredients out by hand and converting cups and ounces into grams and millilitres.

**3. Setting up and accessing the main features**

No login, credentials or setup are needed. The app opens on the Recipes tab.

1. Photograph a recipe. Tap the camera button at the top of the Recipes tab and photograph any printed recipe, for example a page from a cookbook or a printed web page. HobPad saves the photograph and names the recipe automatically. A recipe can also be added with the + button, attaching a PDF or an image from the device. If the name is left blank, HobPad names that recipe automatically as well.
2. Create an ingredient list. Tap a recipe to open it, then tap Create ingredient list. Choose the quantity multiplier if wanted, then tap Create ingredient list. HobPad opens the Todos tab on the new list.
3. Todos and Notes. The Todos tab holds lists in categories, and the Notes tab holds free-text notes. Neither needs any setup.
4. Settings. The gear button on the Recipes tab shows the remaining conversion credits, the Buy credits button and the units preference.

Every new install receives five free conversion credits, so the conversion feature can be tested without a purchase.

**4. External services**

1. **HobPad conversion service.** Our own server, hosted on Cloudflare Workers (Cloudflare, Inc.). It keeps the conversion credit balance for each install, verifies In-App Purchase transactions, and passes recipe pages to the AI service below. It is reached only when a recipe is converted or named, or when credits are checked or bought.
2. **Anthropic Claude API (model Claude Haiku 4.5).** Called only by our server, never directly by the app. It reads the recipe page to extract the ingredients and to suggest a name for a new recipe. For naming, only the first page of the recipe is sent. The recipe page is sent for that request only.
3. **Apple In-App Purchase (StoreKit 2)** for the credit packs, with App Store Server Notifications so that refunded packs are removed from the balance.
4. **iCloud Keychain**, which stores an anonymous random identifier for the install, so that credits remain available after the app is reinstalled or moved to a new device.

HobPad uses no analytics, advertising, tracking or third-party SDKs, and no authentication service.

**5. Regional differences**

HobPad works the same way in all regions. Two behaviours follow the device's region settings. The units preference, set to Automatic by default, converts ingredients to US customary units in regions that use them and to metric units elsewhere, and it can be changed in Settings. Prices for the credit packs are shown in the local currency by the App Store. The app's interface is in English.

**6. Regulated industries and protected material**

HobPad does not operate in a regulated industry. It provides no third-party content of its own. The recipes in the app are those the user photographs or imports for their own use.

**7. In-App Purchase**

HobPad sells three consumable packs of recipe conversion credits:

- 50 conversions (£1.99 in the UK)
- 100 conversions (£2.99 in the UK)
- 500 conversions (£9.99 in the UK)

One credit converts one recipe page, either a photograph or one page of a PDF, into an ingredient list. Converting a recipe that has already been converted is free. Credits never expire. Automatic naming is free, up to 30 recipes a day, with no daily limit while purchased credits remain.

The purchase screen can be reached in three ways:

1. Recipes tab, then the gear button (Settings), then Buy credits in the Recipe conversions section.
2. Opening a recipe and tapping Create ingredient list when the remaining credits are fewer than the recipe needs. The purchase screen opens automatically.
3. The "Daily naming limit reached" message that appears after the 30th automatic name in a day, which offers Buy credits.

---

## Notes for Chris (not for Apple)

**Recording.** Recorded on the spare iPhone 11 Pro from the Mac over the cable. That phone held a copy of Chris's own notes, which must be removed before recording. Its camera does not work, so the recipe is added with + then Attach image, from a photograph already in Photos, with the name left blank so that HobPad names it. Naming a blank-named recipe arrives in build 27, so build 27 must be uploaded and chosen for version 1.0.1 before recording. Section 3 still tells the reviewer how to use the camera. The steps and shot list are in `scripts/review-recording/README.md`. About two to three minutes is enough.

Shot list, for reference:

1. Start on the Home Screen and launch HobPad.
2. Recipes tab. Tap +, leave the name blank, tap Attach image, choose the recipe photograph, and tap Save. Wait for the automatic name.
3. Open the recipe. Show the page, then tap Create ingredient list, set the multiplier to 2 and create the list. Show the new list opening in the Todos tab and tick one item.
4. Go back to the recipe and tap Create ingredient list again, to show that a re-run is free.
5. Notes tab. Create a short note and open it.
6. Recipes tab, gear button. Show the credits and the units setting.
7. Tap Buy credits and tap the 50 pack, then stop at the purchase sheet. The Apple ID password is not entered on camera, so the recording ends there.

**Privacy.** The recording goes to Apple. Before recording, the iPhone 11 Pro must hold none of your notes and no personal photos. A take on 2 October showed your notes, so it was not used.

**Where it goes.** Apple wants the answers in two places: a reply in the App Review section of App Store Connect, with the recording attached, and the Notes field of App Review Information. The App Store Connect API cannot send the Resolution Center reply, so that needs the web page. The Notes field can be set through the API.
