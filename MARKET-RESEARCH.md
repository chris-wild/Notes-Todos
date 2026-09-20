# HobPad Market Research

Date: 18 September 2026

## Purpose

This note records the competitor landscape for HobPad, assesses whether the app is differentiated enough to sell, and recommends a price. It was compiled from app store listings, independent review sites and the RevenueCat State of Subscription Apps 2026 report. Prices are as listed on the date above and will drift.

## Summary

The two features identified as unique selling points, ingredient extraction from a PDF and the planned photo-to-recipe capture, are now standard in paid recipe apps and in several free ones. Recipe Keeper, Crouton, Mela, Samsung Food and ReciMe all import recipes from photographs, and most of them also generate shopping lists from the result. Google Play has dozens of "AI recipe scanner" apps launched in the last eighteen months. HobPad enters the most crowded part of the market if it leads with these features.

HobPad can still be sold, but on a different pitch and at a modest one-off price. The genuine points of difference are that it needs no account and no server, that it keeps the original document rather than discarding it after extraction, and that it carries no subscription. The recommended price is £3.99 to £4.99 as a single purchase, with no free tier and no subscription.

## Competitor landscape

| App | Price | Photo or scan import | PDF import | Shopping list from recipe | Platforms |
|---|---|---|---|---|---|
| Paprika 3 | £4.99 one-off (mobile); $29.99 per desktop platform | No (web import only) | No | Yes, merges duplicates | iOS, Android, Mac, Windows |
| Recipe Keeper | Free, $19.99 Pro upgrade | Yes (OCR) | Yes | Yes | iOS, Android, Mac, Windows |
| Crouton | Free; $24.99 lifetime or $14.99 per year | Yes (AI, single photo) | Yes | Yes | Apple only |
| Mela | $6.99 one-off (iOS); $14.99 Mac | Yes (ML scan) | No | Yes, via Reminders | Apple only |
| Samsung Food | Free; Food+ $6.99 per month or $59.99 per year | Yes (AI) | Not stated | Yes | iOS, Android, web |
| ReciMe | Free (5 to 8 AI imports); $39.99 to $59.99 per year | Yes (AI, plus Instagram and TikTok) | Not stated | Yes, does not merge duplicates | iOS, Android |
| Copy Me That | Free (40 recipes); $0.99 per month or $12 per year | Limited | Not stated | Yes | iOS, Android, web |
| Plan to Eat | $5.95 per month or $49 per year | Not stated | Not stated | Yes | iOS, Android, web |
| Google Play "AI recipe scanner" apps | Mostly free with subscriptions | Yes; this is the whole product | Varies | Varies | Android |

Recipe Keeper has more than 30,000 App Store ratings at 4.8 stars. Paprika has 2,800 UK ratings at 4.9 stars and is ranked first in the UK Food and Drink category despite its last update being July 2025. Both are the incumbents a paying customer will compare HobPad against.

## What HobPad actually offers today

HobPad has three tabs. Notes are plain text with pinning. Todos are grouped into categories. Recipes are a name, free-text notes and an optional PDF attachment. The one AI feature sends a recipe PDF or its notes to the Anthropic API, using a key the user supplies, and creates a todo category containing the ingredients. All data stays on the device.

Compared with the £4.99 tier, HobPad lacks the following.

- Import from a web address. This is the most common way people capture recipes and every competitor supports it.
- Ingredient scaling.
- Meal planning.
- Merging of duplicate ingredients on the shopping list.
- Sync between devices. With no server, a recipe added on the phone does not appear on a tablet. Paprika, Recipe Keeper and Mela all sync. Whether the iOS build uses iCloud should be confirmed. If it does not, this will be the first complaint in reviews.
- Sharing a recipe with another person.

## Where HobPad is different

The features that competitors do not offer are not the ones the app was designed around.

**No account, no server, no subscription.** Every AI-import competitor except Mela and Recipe Keeper is subscription-led, and all of them route recipes through their own cloud. Local-only storage with an optional AI call is a real position, and it is the one privacy-minded buyers search for.

**The original document is the recipe.** Competitors convert a scan into structured fields and discard the source. HobPad keeps the PDF. For scanned family recipes and cookbook pages, the original is what people value.

**Bring your own API key.** The user's own Anthropic key means recipe content goes directly to the user's own account, not through a third party. This is a strength for the privacy pitch and a constraint for the audience, discussed below.

**Notes and todos alongside recipes.** This is unusual, and it should be treated as a liability in marketing. Apple Notes and Google Keep are free and better at notes. Nobody buys a recipe app for its notes tab, and nobody buys a notes app for its recipes. The tabs can stay in the app, but they should not lead the store listing.

## The API key question

Most buyers do not have an Anthropic account and will not create one. In practice this limits the AI features to technical users, and the store listing must not promise AI import to everyone else.

If the key is hosted instead, every extraction costs money. That forces either consumable credits or a subscription, and it changes the pricing model entirely.

Apple has rejected apps for sending user content to a third-party AI service without itemised disclosure, citing guidelines 5.1.1 and 5.1.2. Bring-your-own-key largely sidesteps this because the data goes to the user's own account, but the privacy policy still needs to name Anthropic and state exactly what is sent.

## Pricing

The market has three tiers.

1. Free with an upsell. Samsung Food, ReciMe and most Android AI scanners.
2. One-off purchase between £5 and £20. Paprika, Mela and Recipe Keeper.
3. Subscription between £30 and £50 per year. Crouton, Plan to Eat and ReciMe.

RevenueCat's 2026 report gives a median annual price of $39.99 for productivity apps and a median monthly price of $7.99. A hard paywall converts a median of 10.7 percent of installs to paid by day 35, against 2.1 percent for freemium. Download-to-trial for mid-priced apps is 5.4 percent. Those figures come from apps with marketing budgets and should be treated as a ceiling rather than an expectation.

### Recommendation

**Price at £3.99 to £4.99 as a single purchase, with no free tier and no subscription.** A subscription cannot be justified with no server costs and user-supplied AI keys, and buyers in this category resent them. Paprika at £4.99 is the anchor. HobPad offers less than Paprika today, so it should not be priced above it.

**Do not offer a free tier.** With no server, a free tier costs nothing to run, but it brings the one-star reviews of people expecting Samsung Food. A paid app filters for the buyer who wants what HobPad is.

**If the API key is ever hosted, sell AI import packs as consumable in-app purchases**, for example 50 imports for £2.99, rather than a subscription. That matches the cost structure and keeps the no-subscription pitch intact.

Without marketing, expect single-digit sales per week. Recipe Keeper and Paprika took years to build their review counts, and neither store surfaces new paid apps organically.

## What would make HobPad sellable

1. Commit to the privacy position in the store listing. "Your recipes stay on your phone. No account, no subscription, no cloud."
2. Close the three gaps that stop a Paprika owner switching. Import from a web address, ingredient scaling, and duplicate merging on the shopping list.
3. For the photo feature, do both structured extraction and retention of the original as a PDF. That combination is what nobody else offers. Photo-to-PDF alone is a scanner feature that iOS Notes and Google Drive already provide for free.
4. Remove notes and todos from the marketing, even if they stay in the app.
5. Confirm the cross-device story before launch. If there is no sync, say so plainly in the listing rather than letting reviews say it.

## Hosted AI: cost per conversion and pack pricing

This section costs the option of hosting the Anthropic key and selling conversions as consumable in-app purchases, rather than requiring users to supply their own key.

### What a conversion costs

The app pins `claude-haiku-4-5`, which is the cheapest current Anthropic model at $1 per million input tokens and $5 per million output tokens. Anthropic bills a PDF at roughly 1,500 to 3,000 tokens per page, covering the page text and a rendered image of the page. The app's system prompt and instruction add about 100 tokens. The ingredient JSON returned is typically 150 to 300 tokens and is capped at 2,048.

| Call | Input tokens | Output tokens | Cost (USD) | Cost (GBP at about $1.33 per £) |
|---|---|---|---|---|
| Text-only extraction from recipe notes | ~500 | ~200 | $0.0015 | 0.1p |
| One-page PDF, typical | ~3,000 | ~300 | $0.0045 | 0.35p |
| Three-page PDF, verbose output | ~7,500 | ~2,048 | $0.018 | 1.4p |
| Planned photo-to-recipe, one image, full recipe with method | ~1,700 | ~800 | $0.0057 | 0.45p |
| Photo-to-recipe on Sonnet 5 for higher accuracy | ~1,700 | ~800 | $0.0115 | 0.9p |

A conversion therefore costs between a tenth of a penny and a penny and a half. A blended budget of 0.5p per conversion covers failed calls and retries, which are billed whether or not they succeed.

### A control that must exist before hosting a key

The code has no page cap. A user who attaches a 40-page cookbook PDF sends around 100,000 tokens, which is about 8p on Haiku and 16p on Sonnet. Either conversions should be capped at three to five pages, or a long PDF should consume several conversions. Without that control, one user can spend a pack's margin on a single call.

### What actually consumes the pack price

The API cost is not the constraint. The store commission and VAT are. For a UK consumable in-app purchase the listed price includes 20 percent VAT, and both Apple and Google take 15 percent of the remainder for developers earning under $1 million a year (30 percent above that). On a £2.99 pack the developer receives about £2.12.

| Pack | Developer receives | API cost at 0.5p | API cost at 1.5p (worst case) | Profit range |
|---|---|---|---|---|
| 25 for £0.99 | £0.70 | £0.13 | £0.38 | £0.32 to £0.57 |
| 100 for £2.99 | £2.12 | £0.50 | £1.50 | £0.62 to £1.62 |
| 300 for £6.99 | £4.95 | £1.50 | £4.50 | £0.45 to £3.45 |

All three packs are profitable. The 100 pack is the right anchor. The 300 pack is comfortable at typical usage but thin if a user feeds it long, image-heavy PDFs every time, which is a second reason for the page cap.

### Two challenges to cost-plus pricing

Pricing at cost plus a small margin implies conversions at about a penny each, which is meaningless to a buyer. Value pricing is the better frame. ReciMe gives away five to eight imports and then charges $40 to $60 a year for unlimited use. Against that, 100 conversions for £2.99 is already inexpensive, and £3.99 for the same pack would not look greedy.

Hosting the key requires a small proxy service, such as a Cloudflare Worker, to hold the key and count credits against store receipts. The key cannot be shipped inside the app. The service would cost a few pounds a month at most, but it is real engineering work, and it slightly weakens the "no server" pitch even though recipes are still not stored anywhere.

### Recommendation

1. Keep bring-your-own-key as an option in Settings for technical users.
2. Bundle ten free conversions with the app purchase. At 0.5p each this costs about 5p per install and lets every buyer try the feature.
3. Sell packs at 25 for £0.99, 100 for £2.99 and 300 for £6.99, with a five-page cap per conversion.
4. Stay on Haiku for ingredient extraction. Test the planned photo-to-recipe feature on both Haiku and Sonnet before choosing a model. If Haiku is poor on handwritten recipes, the extra 0.5p per call for Sonnet is trivial against the pack price.

The exchange rate and the 15 percent store tier are the two figures to verify before setting prices.

## Sources

- Paprika Recipe Manager 3, UK App Store: https://apps.apple.com/gb/app/paprika-recipe-manager-3/id1303222868
- Recipe Keeper, App Store: https://apps.apple.com/us/app/recipe-keeper/id974683711
- Crouton, App Store: https://apps.apple.com/us/app/crouton-recipe-manager/id1461650987
- Mela, App Store: https://apps.apple.com/us/app/mela-recipe-manager/id1548466041
- Samsung Food, add from photo: https://samsungfood.com/add-from-photo-howto/
- ReciMe review, Plan to Eat: https://www.plantoeat.com/blog/2025/01/recime-app-review-pros-and-cons/
- ReciMe review, Recipe One: https://www.recipeone.app/blog/recime-app-review
- 12 best recipe apps compared, Recipe One: https://www.recipeone.app/blog/best-recipe-manager-apps
- Best recipe manager apps, Forkee: https://www.getforkee.com/blog/best-recipe-manager-apps/
- Best recipe management software, Cooklang: https://cooklang.org/blog/48-best-recipe-management-software/
- RevenueCat State of Subscription Apps 2026, summary: https://www.revenuecat.com/blog/growth/subscription-app-trends-benchmarks-2026
- RevenueCat State of Subscription Apps 2026, Productivity: https://www.revenuecat.com/state-of-subscription-apps-2026-productivity/
- Apple Developer Forums, rejection over third-party AI service: https://developer.apple.com/forums/thread/816140
- Google Play AI recipe scanners: RecipEase (https://play.google.com/store/apps/details?id=com.recipease.kitchen), RecipeAI (https://play.google.com/store/apps/details?id=ai.recipeai.app), Cooksy (https://play.google.com/store/apps/details?id=ai.cooksy.app)
- Anthropic model pricing: https://platform.claude.com/docs/en/about-claude/pricing
- Anthropic PDF support and token counting: https://platform.claude.com/docs/en/build-with-claude/pdf-support
