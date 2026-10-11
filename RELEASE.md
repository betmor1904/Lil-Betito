# Putting Lil Betito on Google Play

The app is ready for Play: it's a normal full-screen game (no "display over other apps"), targets Android 16 (API 36),
uses the ID `com.betmor.lilbetito`, has an icon, and saves progress. What's left is stuff only you can do.

## 1. Make your upload key (once, on your phone in Termux)

```
pkg install openjdk-17
keytool -genkeypair -v -keystore lilbetito-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

It asks for a password and your name. **Write the password down.** Back up `lilbetito-upload.jks`
(email it to yourself or put it in Google Drive). If you lose it, Google can reset it, but it's a hassle.

Turn it into text for GitHub:

```
base64 -w0 lilbetito-upload.jks > key.txt
```

## 2. Give the key to GitHub (secrets stay private, even on a public repo)

With the GitHub CLI in Termux (from inside the Lil-Betito folder):

```
gh secret set KEYSTORE_B64 < key.txt
gh secret set KEYSTORE_PASSWORD      # type the password
gh secret set KEY_ALIAS              # type: upload
gh secret set KEY_PASSWORD           # same password again
rm key.txt
```

Or on github.com: repo → Settings → Secrets and variables → Actions → New repository secret (same 4 names).

The next build makes a second download, **lil-betito-play-store-aab**. That `.aab` file is what you upload to Play.

```
gh run download $(gh run list -L 1 --json databaseId -q '.[0].databaseId') -n lil-betito-play-store-aab -D ~/storage/downloads/betito-aab
```

## 3. Google Play Console

1. Sign up at play.google.com/console ($25 once, personal account, ID check).
2. **Create app**: name *Lil Betito*, Game, Free.
3. **App content** (left menu):
   - Privacy policy: `https://github.com/betmor1904/Lil-Betito/blob/main/PRIVACY.md` (add your contact email to that file first)
   - Ads: **No** (for now; change it when ads are added)
   - App access: all functionality available without login
   - Content rating: fill the questionnaire honestly. The slot reels count as **simulated gambling** (no real money), so say yes there; expect a Teen / 12+ type rating.
   - Target audience: **13 and over** (keeps you out of the stricter kids' rules)
   - Data safety: **No data collected or shared**
4. **Store listing**: text is in `store/listing.md`; icon (512×512) and feature graphic (1024×500) are in `store/`.
   Phone screenshots: take 4–8 while playing (landscape is fine).
5. **Testing → Closed testing**: create a track, upload the `.aab`, add your testers' Gmail addresses,
   pick countries, send for review, then share the opt-in link.
   **New personal accounts need 12+ testers opted in for 14 days in a row** before you can go public.
   Recruit ~15–20 so a dropout doesn't reset the clock.
6. After 14 days: **Apply for production access**, then publish.

## Every update after that

Bump `versionCode` (and `versionName`) in `app/build.gradle.kts`, push, download the new `.aab`, upload it to a new release.
