# App Review reply and Notes field, as sent on 2 October 2026

The Resolution Center reply is limited to 4,000 characters and the Notes field to 4,000, so both use this shorter version of the answers in 2026-10-02-guideline-2-1-response.md. The reply attaches hobpad-review.mp4.

## Reply

Thank you for reviewing HobPad. The requested information follows, numbered as in your message. The same answers are in the Notes field of App Review Information.

1. Screen recording
The attached recording was captured on an iPhone 11 Pro running iOS 26.7, using build 27 installed from TestFlight. It begins at the Home Screen, launches HobPad and shows adding a recipe from a photograph of a printed recipe, which HobPad names automatically, creating an ingredient shopping list from it, the Todos and Notes tabs, Settings and the purchase screen for a credit pack. It stops at the purchase sheet, before the Apple ID password is entered. HobPad has no accounts, so there is no registration, sign-in or account deletion. Nothing is shared between users, so no reporting or blocking is needed.

2. Purpose and audience
HobPad is for home cooks. It keeps recipes, notes and todo lists in one place. Its main feature turns a recipe page into a shopping list in the Todos tab, scaled for the number of people and converted into the user's preferred units.

3. Accessing the main features
No login or setup is needed. Tap the camera button on the Recipes tab to photograph a printed recipe, which is named automatically. A recipe can also be added with the + button from a PDF or an image, and leaving the name blank names it automatically. Open a recipe and tap Create ingredient list to make the shopping list. Every new install receives five free conversion credits, so the feature can be tested without a purchase.

4. External services
Our own conversion service, hosted on Cloudflare Workers, keeps each install's credit balance, verifies In-App Purchase transactions and passes recipe pages to the Anthropic Claude API (Claude Haiku 4.5), which extracts ingredients and suggests recipe names. The app never calls Anthropic directly. For naming, only the first page of a recipe is sent. Apple In-App Purchase (StoreKit 2) sells the credit packs, with App Store Server Notifications for refunds. iCloud Keychain stores an anonymous identifier so that credits survive a reinstall. There is no analytics, advertising, tracking or third-party SDK.

5. Regional differences
None. The units preference follows the device region by default and can be changed in Settings. Prices are shown in the local currency by the App Store.

6. Regulated industries
None. HobPad provides no third-party content of its own.

7. In-App Purchase
Three consumable packs of recipe conversion credits: 50 (GBP 1.99), 100 (GBP 2.99) and 500 (GBP 9.99). One credit converts one recipe page. Converting a recipe again is free, credits never expire, and automatic naming is free up to 30 recipes a day. The purchase screen is reached from Recipes, then the gear button, then Buy credits; automatically when a conversion needs more credits than remain; and from the daily naming limit message.
## Notes field

Answers to the information request of 2 October 2026 (Guideline 2.1). The full answers and the screen recording are in our App Review reply.

1. Screen recording
Attached to our App Review reply. It was captured on an iPhone 11 Pro running iOS 26.7 with build 27 from TestFlight, and it shows adding a recipe, creating an ingredient list, the Todos and Notes tabs, Settings and the purchase screen. HobPad has no accounts, so there is no registration, sign-in or account deletion. Nothing is shared between users, so no reporting or blocking is needed.

2. Purpose and audience
HobPad is for home cooks. It keeps recipes, notes and todo lists in one place. Its main feature turns a recipe page into a shopping list in the Todos tab, scaled for the number of people and converted into the user's preferred units.

3. Accessing the main features
No login or setup is needed. Tap the camera button on the Recipes tab to photograph a printed recipe, which is named automatically. A recipe can also be added with the + button from a PDF or an image, and leaving the name blank names it automatically. Open a recipe and tap Create ingredient list to make the shopping list. Every new install receives five free conversion credits, so the feature can be tested without a purchase.

4. External services
Our own conversion service on Cloudflare Workers keeps each install's credit balance, verifies In-App Purchase transactions and passes recipe pages to the Anthropic Claude API (Claude Haiku 4.5), which extracts ingredients and suggests recipe names. The app never calls Anthropic directly. For naming, only the first page of a recipe is sent. Apple In-App Purchase (StoreKit 2) sells the credit packs, with App Store Server Notifications for refunds. iCloud Keychain stores an anonymous identifier so that credits survive a reinstall. There is no analytics, advertising, tracking or third-party SDK.

5. Regional differences
None. The units preference follows the device region by default and can be changed in Settings. Prices are shown in the local currency by the App Store.

6. Regulated industries
None. HobPad provides no third-party content of its own.

7. In-App Purchase
Three consumable packs of recipe conversion credits: 50 (GBP 1.99), 100 (GBP 2.99) and 500 (GBP 9.99). One credit converts one recipe page. Converting a recipe again is free, credits never expire, and automatic naming is free up to 30 recipes a day. The purchase screen is reached from Recipes, then the gear button, then Buy credits; automatically when a conversion needs more credits than remain; and from the daily naming limit message.
