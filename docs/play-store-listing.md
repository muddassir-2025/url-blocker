# ClearView — Google Play Store Listing Texts

Everything below is copy-paste ready. Placeholders to replace: none (only the
accessibility/device-admin sections are declaration answers — paste as-is).

---

## 1. Short description (max 80 characters)

```
Privacy-first parental controls: block adult content, keywords & incognito.
```
(75 chars ✓)

Alternative (if you prefer mentioning the content hub):
```
Parental controls + Quran, Media & Live. Private, no accounts, no data.
```
(72 chars ✓)

---

## 2. Full description (max 4,000 characters)

```
ClearView — Privacy-first parental controls, plus Quran, Media & Live.

Protect your family's browsing while enjoying a clean Islamic content hub — all in one private, ad-free app with no accounts and no data collection.

🛡️ Blocks what should be blocked
• Adult and explicit content across Chrome, the Google app and YouTube — searches, pages, video titles and thumbnails
• Your own blocked keywords and blocked websites
• Incognito mode, so private browsing can't hide inappropriate content
• Optional Strict Modes for even tighter filtering

🔍 How it works — on your device, not on a server
• Real-time text screening via the Android Accessibility Service, used only for content blocking and nothing else
• On-device AI checks video thumbnails for adult imagery — images are never uploaded
• Everything is processed locally. No accounts, no ads, no analytics, and no personal data ever leaves your phone

📖 Quran, Media and Live — built in
• Quran: a daily verse in Arabic and English, Previous/Next navigation, bookmarks, copy & share, automatic refresh, and a home-screen widget that stays perfectly in sync with the app
• Media: follow your favorite YouTube channels, watch Shorts and long videos inside the app, track watch progress, and get notified when channels upload new videos
• Live: watch Makkah and Madinah live broadcasts in the app

🔒 Made for families
• Protect the Block tab with a password of your choice
• Full transparency: ClearView collects no personal data. Your settings, keywords and blocked-content records never leave your device. Uninstalling the app deletes everything.

Permissions used:
• Accessibility service — required for content blocking (on-device only)
• Notifications — optional, for channel-update alerts
• Internet — for content you request (Quran text, YouTube videos)
• Device admin — optional, for uninstall protection
```

(~1,950 chars ✓)

---

## 3. Accessibility declaration (Play Console → App content → Accessibility)

Question: "Does your app use the accessibility services API?"

Answer: **Yes**

Declaration (paste into the provided field):

```
ClearView uses the Android AccessibilityService solely for its core content-blocking feature. When the user enables protection, the service reads the text currently shown on screen inside the Chrome browser and the Google app in real time to detect and block: (1) adult and explicit content via built-in and user-defined keywords, (2) user-blocked websites, (3) inappropriate search results and video content on YouTube, and (4) incognito browsing. All analysis happens locally on the device: screen content is processed in memory, is never stored, logged or transmitted, and is used for no other purpose. The service remains disabled until the user explicitly enables protection and can be turned off at any time from the app or system settings. Thumbnail screening uses a local on-device machine-learning model; images are never uploaded. ClearView does not use the accessibility API to access data outside this blocking function, does not modify other apps or their interfaces, and complies with the Google Play Accessibility API policy.
```

Then tick the checkbox confirming the app complies with the policy.

---

## 4. Device admin declaration (bonus — if Play Console asks)

```
ClearView includes an optional Device Administrator component used only to add uninstall protection when the user activates it. It performs no other device-administration functions and can be removed at any time by the user in device settings.
```

---

## 5. Category & contact suggestions

- App category: **Parenting** (fits parental-control purpose) or **Tools**
- Content rating: complete the questionnaire honestly; expect a mature rating for the blocking nature
- Data safety: answer **"No data collected"** (matches the privacy policy)
- Privacy policy URL: paste your hosted page from docs/privacy-policy.html
