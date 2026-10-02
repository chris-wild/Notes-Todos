# Google Play listing and declarations (draft for Chris)

Status: draft, 2 October 2026. Nothing below has been entered into Play Console. Every factual answer was checked against the code, the merged release manifest of version 0.4.4, the production Worker and the privacy policy. Items that need Chris's decision are marked **Decision**.

## Store listing

**App name** (30 characters at most): HobPad

**Short description** (80 characters at most, 74 used):

Photograph recipes and turn them into shopping lists. Notes and todos too.

**Full description:**

HobPad keeps recipes, notes and todo lists in one place, and turns recipes into shopping lists.

Photograph a recipe from a book or magazine, or import a PDF. HobPad stores it, names it, and files it with the rest of your collection. When you are ready to cook, one tap reads every page, merges repeated ingredients, and writes the shopping list into your todos. Cooking for more people? Scale the quantities before you convert.

RECIPES
Photograph paper recipes or import PDFs, with several pages per recipe. Recipes are named automatically from their first page unless you type a name. Search your whole collection, and pinch to zoom into any page.

SHOPPING LISTS
One tap turns a recipe into an ingredient list. Repeated ingredients are merged, so 3 garlic cloves for the sauce and 6 for the dish become 9 on the list. Quantities can be converted to metric or US units, and multiplied when cooking for a crowd. Lists arrive as todos, ready to tick off at the shop.

NOTES AND TODOS
Simple notes with tappable links, and todo lists in categories you define.

PRICING
Notes, todos and recipe storage are free without limits. Automatic naming is free, up to 30 recipes a day, with no daily limit while you hold purchased credits. Converting a recipe page into a shopping list uses one credit, and converting the same recipe again is free. New installs include five free credits, and further credits are sold in packs. Credits never expire.

PRIVACY
Your data lives on your device and in your own Google backup. Recipe content leaves your device only when a recipe is named automatically or converted, and our service does not keep it. Recipe file backup to your own Google Drive is optional. No accounts, no adverts, no tracking.

**Category:** Food & Drink (matches the App Store).

**Tags:** chosen in Play Console from Google's list; suggested Recipes, Shopping list, Cooking.

**Contact details:** email chris.wild@gmail.com, website https://hobpad.app. **Decision:** whether to publish a phone number (optional on Play).

**Privacy policy URL:** https://hobpad.app/privacy.html, after the Google Play section drafted in `site/privacy.html` is deployed.

**Graphics:** ready in `play-listing/graphics/` and `play-listing/screenshots/`, for review.

- `icon-512.png`, the app icon at 512 by 512.
- `feature-graphic.png`, 1024 by 500, in the hobpad.app paper, ink and herb-green style with the site's fonts. Its source is `feature-graphic.html`.
- Four phone screenshots at 1080 by 1920, matching the four iOS App Store screenshots: the recipe list, the open Penne alla Carbonara recipe, the ingredient list dialog, and the resulting shopping list. They were taken on the emulator from a copy of Chris's data, in UK English and the light theme, with the "Holondaise" and "Spagette" typos corrected and personal todo categories removed. Play limits screenshots to a 2:1 shape, so the emulator was set to 1080 by 1920 for them.

## App content declarations

1. **Privacy policy.** https://hobpad.app/privacy.html.
2. **Ads.** No, the app contains no ads.
3. **Sign-in details (app access).** All functionality is available without special access. There is no sign-in.
4. **Content ratings.** IARC questionnaire, category Productivity or Reference. Every content question is answered No: no violence, sexual content, profanity, drugs, gambling or user-generated content shared with others. Users cannot interact with each other or share their location. The app sells digital goods (credit packs). Expected result: suitable for all ages (PEGI 3, Everyone).
5. **Target audience and content.** **Decision.** Recommended: ages 13 and over (13 to 15, 16 to 17, 18 and over). Including under-13 groups would bring the app under Google's Families policy, which adds requirements for no benefit to a cooking app. The App Store rating is 4+, which is a content rating, not a target audience, so the two do not conflict.
6. **Data safety.** See the next section.
7. **Advertising ID.** No. The release manifest does not declare the advertising ID permission, and no advertising or analytics library is included.
8. **Government apps.** No.
9. **Financial features.** No. Selling in-app credit packs through Google Play Billing is not a financial feature.
10. **Health apps.** No.

## Data safety answers

Google counts data as collected when it leaves the device for the developer's service, even if it is processed only briefly. Transfers to a service provider acting for the developer, such as Anthropic processing recipe pages, are not counted as sharing.

**Does the app collect or share any of the required user data types?** Yes, it collects some. It shares none.

**Is all collected data encrypted in transit?** Yes. Every request to the HobPad service uses HTTPS.

**Do you provide a way for users to request that their data be deleted?** **Decision.** The only data the service keeps is the anonymous credit ledger, and recipe content is never stored. A user cannot easily identify their own ledger entry, because the identifier is not shown in the app. Two honest answers are possible. Answering No is defensible because no account exists and nothing personal is held. Answering Yes would need a deletion route, such as a Settings action that asks the service to erase the ledger for this install, which does not exist yet. Recommended: No, unless Google's review asks for a route.

| Data type (Google's category) | Collected | Ephemeral | Required or optional | Purpose | What it is in HobPad |
|---|---|---|---|---|---|
| Photos and videos: Photos | Yes | Yes | Optional | App functionality | A photographed or attached recipe image, sent for automatic naming and for conversion |
| Files and docs | Yes | Yes | Optional | App functionality | Recipe PDFs, whose first page is sent for automatic naming when no name is typed, and which are sent whole for conversion |
| Financial info: Purchase history | Yes | No | Optional | App functionality | Google Play order numbers of credit packs, kept in the credit ledger so balances and refunds are correct |
| Device or other IDs | Yes | No | Required | App functionality | The anonymous random identifier that the credit balance is kept against |

Not collected: name, email, contacts, location, messages, audio, health, web browsing, app interactions, diagnostics and crash logs. Recipe file backup to Google Drive is not collection, because the files go directly from the device to the user's own Drive and never reach the developer.

## A correction for the App Store description

The approved App Store description says that photography is free without limits and that recipe content is sent only when a conversion is requested. Both were true before automatic naming gained its 30-a-day limit and before naming sent the photographed page to the service. The Play description above states the current behaviour, and the App Store description should be brought into line before 1.0.1 is resubmitted. **Decision:** whether to update the App Store description now, while the version is open for editing because of the information request.
