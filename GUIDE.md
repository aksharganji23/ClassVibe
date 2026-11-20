# 📘 ClassVibe Guide Book

Step-by-step guide to getting ClassVibe **v1.1.0 "Launchpad"** onto GitHub, building it, releasing it and keeping everyone updated automatically.

> **Read this first: an honest note.** This version was written outside Android Studio. The pure logic (CSV/exam parsing, exam and weekend schedules, the screenshot-reader pipeline, attendance maths, version comparison, all 620 messages) was compiled and tested. The Android/Compose screens could not be compiled where they were written.
> The **first real compile happens on GitHub Actions** (Part B), or in Android Studio.
> If you see errors, copy the red `e: file:///…kt:LINE:COL …` lines and send them to Claude. They are usually one-line fixes.

---

## Contents
- [Part A: Put the code on GitHub](#part-a--put-the-code-on-github)
- [Part B: Check that it builds](#part-b--check-that-it-builds-github-actions)
- [Part C: Open and run in Android Studio](#part-c--open-and-run-in-android-studio)
- [Part D: Create your release signing key and GitHub secrets](#part-d--create-your-release-signing-key--github-secrets)
- [Part E: Publish a release](#part-e--publish-a-release-tag--apk)
- [Part F: Install on your phone (first time)](#part-f--install-on-your-phone-first-time)
- [Part G: How auto-update works for users](#part-g--how-auto-update-works-for-users)
- [Part H: Your daily development loop](#part-h--your-daily-development-loop)
- [Part I: Version naming rules](#part-i--version-naming-rules)
- [Part J: Play Protect and developer verification](#part-j--play-protect--developer-verification)
- [Part K: Troubleshooting](#part-k--troubleshooting)
- [Part L: Feature tour](#part-l--feature-tour-where-is-everything)
- [Part M: Download website (GitHub Pages)](#part-m--download-website-github-pages)
- [Part N: Sharing with QR codes](#part-n--sharing-with-qr-codes)

---

## Part A: Put the code on GitHub

You already created a repository named **ClassVibe**.

1. Extract `ClassVibe.zip`. You get a `ClassVibe/` folder that contains `settings.gradle.kts`, `app/`, `.github/` and so on.
2. Open a terminal **inside** that folder and run the following, replacing `YOUR_USERNAME`:
   ```bash
   git init
   git add .
   git commit -m "ClassVibe v1.1.0 – Launchpad"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/ClassVibe.git
   git push -u origin main
   ```
   If the repository already has a README or other commits, run `git pull origin main --allow-unrelated-histories` first, then push.
3. **Check the hidden folders.** `.github/workflows/` must be on GitHub, because that's what builds the APK. If you use GitHub Desktop or drag-and-drop uploads, make sure hidden folders (`.github`, `.gitignore`) are included.
4. **Never commit** `keystore.properties`, `*.jks` or `*.keystore`. `.gitignore` already blocks them.

> **Optional:** in `gradle.properties`, change `classvibe.githubRepo=YOUR_GITHUB_USERNAME/ClassVibe` to your real `username/ClassVibe`. GitHub Actions fills this in automatically for releases, so this only matters for local release builds.

---

## Part B: Check that it builds (GitHub Actions)

After pushing, open your repository on GitHub, go to the **Actions** tab and open the **Build** workflow run.

- ✅ **Green tick:** the app compiles. Download the debug APK from **Artifacts → ClassVibe-debug-apk** at the bottom of the run.
- ❌ **Red cross:** click **Build debug APK**, scroll to the first lines starting with `e:` and send them to Claude.

The workflow runs on every push, so after each fix you just push again and watch the tick.

---

## Part C: Open and run in Android Studio

1. **File → Open** and select the `ClassVibe` folder. Wait for **Gradle Sync**.
   - If Studio offers an **AGP upgrade**, ignore it (choose "Don't ask for this project"). AGP 9 changes how Kotlin is set up and would break the build.
   - If it complains about the Gradle JDK, go to **Settings → Build Tools → Gradle → Gradle JDK** and pick **jbr-17/21**.
2. **Before the first run, uninstall ClassVibe v1.0** from your phone. The new version is a different build of the app, so Android won't install it over the old one. Your old timetable will be lost, so keep your CSV handy to re-import.
3. Plug in your phone (or use **Wireless debugging**) and press ▶ **Run**.
   - Builds from Android Studio are **debug builds**. They install as **"com.classvibe.app.debug"**, separately from the real app, so testing never breaks your installed release.
   - The in-app updater is **switched off in debug builds** on purpose.

---

## Part D: Create your release signing key and GitHub secrets

Every update must be signed with **the same key forever**, or phones refuse the update. Do this **once** and back the key up (Google Drive, a USB drive and an email to yourself).

### D1. Create the keystore
**Option 1: Android Studio.** Go to **Build → Generate Signed App Bundle / APK → APK → Next → Create new…**
- Key store path: `classvibe-release.jks` (save it **outside** the project folder)
- Password: a strong one, and note it down
- Alias: `classvibe`
- Validity: 50 years. Fill in your name, then click **OK**. You can cancel the rest of the wizard.

**Option 2: terminal** (the JDK `keytool` is bundled with Android Studio):
```bash
keytool -genkeypair -v -keystore classvibe-release.jks -alias classvibe -keyalg RSA -keysize 2048 -validity 18250
```

### D2. Convert the keystore to Base64
- **Windows (PowerShell):**
  ```powershell
  [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\classvibe-release.jks")) | Set-Clipboard
  ```
- **macOS:** `base64 -i classvibe-release.jks | pbcopy`
- **Linux:** `base64 -w0 classvibe-release.jks`

### D3. Add four repository secrets
On GitHub, go to **Settings → Secrets and variables → Actions → New repository secret**:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | the Base64 text from D2 |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `classvibe` |
| `KEY_PASSWORD` | the key password (often the same as the keystore password) |

### D4. (Optional) Signed builds on your laptop
Create `keystore.properties` in the project root (it's git-ignored):
```properties
storeFile=../classvibe-release.jks
storePassword=YOUR_PASSWORD
keyAlias=classvibe
keyPassword=YOUR_PASSWORD
```
Then go to **Build → Build APK(s)** with the **release** variant selected. Without this file, local release builds use the debug key. Never share APKs signed that way, because their updates won't match.

---

## Part E: Publish a release (tag → APK)

1. Update **`CHANGELOG.md`**: add a `## [1.1.0] – Name` section (it's already there for 1.1.0).
2. Update **`app/src/main/java/com/classvibe/app/ui/ReleaseNotes.kt`**. This is the in-app "What's new" text.
3. Commit and push, then create the tag:
   ```bash
   git tag v1.1.0
   git push origin v1.1.0
   ```
   Or on GitHub: **Releases → Draft a new release → Choose a tag → type `v1.1.0` → Create new tag**, then publish. The workflow attaches the APK for you.
4. Watch **Actions → Release**. When it's green, **Releases** shows:
   - `ClassVibe-v1.1.0.apk`
   - `ClassVibe-v1.1.0.apk.sha256` (the checksum the app uses to verify updates)

**Betas:** tag `v1.2.0-beta.1`. It's published as a *pre-release*, and only phones with **Settings → Updates & data → Beta updates** turned on will see it.

---

## Part F: Install on your phone (first time)

1. On your phone, open `github.com/YOUR_USERNAME/ClassVibe/releases` and download `ClassVibe-v1.1.0.apk`.
2. Tap it, then allow **Install unknown apps** for your browser or Files app.
3. If **Play Protect** shows "App scan recommended" or "unrecognised developer", choose **Scan app**. The scan should come back clean (Part J explains why).
4. Open ClassVibe:
   - choose **Light / Dark / Follow phone**;
   - allow **notifications**;
   - allow **Alarms & reminders** if asked;
   - import your timetable.
5. On **Xiaomi, Oppo, Vivo, Realme, OnePlus or Samsung** phones, go to **Settings → Apps → ClassVibe → Battery → Unrestricted** and enable **Autostart** if it exists.

Share the same Releases link with classmates. From now on they never need to download manually again.

---

## Part G: How auto-update works for users

| When | What happens |
|---|---|
| App opens | Checks for updates (at most once per 10 min) |
| Every ~8 hours (3× a day) | Background check, only on a network, and only if "Check for updates" is on |
| Settings → "Check for updates now" | Manual check |

**Where the app looks (in this order):**
1. `version.json` on your website, written automatically after every release. No hourly limit, so it works on college Wi‑Fi.
2. The `github.com/…/releases/latest` link (stable versions).
3. The GitHub API (limited to 60 checks per hour per network; a 403 shows "Too many update checks from this network – try mobile data").

**When a newer release exists:**
1. A **"🚀 Update available"** card appears on Today, plus a notification.
2. The user taps **Update**. The very first time, Android asks to allow **Install unknown apps** for ClassVibe (switch it on, come back, the update starts by itself).
3. The card shows download progress. ClassVibe **verifies the SHA-256 checksum** and that the APK is **signed with your key**; if either fails, the file is deleted and the user is warned.
4. Android's **"Update this app?"** box appears → tap **Update**. On Android 12+ later updates usually install with **no tap at all**.
5. Android restarts the app; a **"✅ ClassVibe updated – tap to open"** notification appears. All data is kept.
6. A copy of the APK is kept in **Downloads/ClassVibe/** (e.g. `ClassVibe-v1.3.0.apk`) in case you need to install it by hand.

**Also:**
- **Later** hides the card for now; **Skip version** ignores that version.
- Android never lets an app reopen itself after an update from the background, hence the "tap to open" notification.

---

## Part H: Your daily development loop

1. **Change code → ▶ Run.** This installs the debug build next to the real app.
2. **Commit and push.** The Build workflow confirms it compiles.
3. **Ready to ship?** Bump the version in the CHANGELOG and ReleaseNotes, then push a new tag (`v1.1.1`, `v1.2.0`…). Everyone's app offers the update within about 8 hours.

**Database changes:** if you add or change Room entity fields, bump the `version` in `AppDatabase.kt` **and** add a `Migration`, like `MIGRATION_1_2`. Otherwise the app will crash for existing users.

---

## Part I: Version naming rules

| Part | Change it when… | Example |
|---|---|---|
| PATCH `1.1.x` | only bug fixes | `v1.1.1` |
| MINOR `1.x.0` | new features | `v1.2.0` |
| MAJOR `x.0.0` | big milestone (e.g. Google Play launch) | `v2.0.0` |
| Beta | testing before release | `v1.2.0-beta.1` |

**How it's wired up:**
- **Tag format:** `vMAJOR.MINOR.PATCH`. The release title equals the tag; put the codename in the CHANGELOG heading.
- **versionCode** is calculated automatically from the tag: `MAJOR×1,000,000 + MINOR×10,000 + PATCH×100 + (beta number, or 99 for a final release)`. For example, `1.1.0` → `1010099` and `1.2.0-beta.1` → `1020001`. This changed slightly from the earlier plan so that betas sort below the final release.
- **Where to see it:** Settings → About shows the version, and **What's new** pops up once after each update.

**Codename roadmap** (all v1.x features shipped together in v1.1.0):

| Version | Codename | Status |
|---|---|---|
| v1.0.0 | Spark ⚡ | ✅ |
| v1.1.0 | Launchpad 🚀 | ✅ this release (everything on the to-do list) |
| v1.2.0 | Sharp Eye 🔍 | tune OCR on your real timetable screenshots |
| v2.0.0 | Big Stage 🎓 | Google Play launch |

---

## Part J: Play Protect and developer verification

**What ClassVibe already does to pass scans:**
- **Minimal permissions:** notifications, alarms, boot, wake lock, vibrate, and network for updates only.
- **Few permissions:** no SMS, contacts, location, camera, storage, accessibility or overlay permission. The only special one is **Install unknown apps**, used only to install ClassVibe's own verified updates. Play Protect may mention it on first install; that's expected for apps that update themselves.
- **No ads, analytics or tracking SDKs;** no cleartext HTTP; immutable PendingIntents; non-exported internal receivers.
- **Release builds** are minified, not debuggable, signed with your key and verified on update.

**Before each release, optionally:**
1. Upload the release APK to **virustotal.com** and check it shows 0 detections.
2. Run a **MobSF** static scan (MobSF is open source; you can run it with Docker).
3. Install it on a phone with Play Protect on, then go to **Play Store → Profile → Play Protect → Scan**.

**If Play Protect still warns:** choose **Scan app**. You can also submit the APK through Google's Play Protect appeal form ("Submit an app to Google Play Protect for review").

**Developer verification (timeline):**
- **Where it stands:** Google's developer verification for sideloaded apps started on **30 September 2026** in Brazil, Indonesia, Singapore and Thailand.
- **India:** global rollout is planned for **2027**; as of November 2026 no date for India had been announced.
- **What to do before then:** register for a free **limited distribution account** (for students and hobbyists) in the **Android Developer Console**, and register the package `com.classvibe.app` with your release key's certificate. Then installs keep working when verification reaches India.

---

## Part K: Troubleshooting

| Problem | Fix |
|---|---|
| Build workflow red | Copy the `e:` lines from Actions and send them to Claude |
| "App not installed" / "package conflicts" | Uninstall the old version (v1.0, or one signed with a different key), then install again |
| Release workflow: "Missing KEYSTORE_BASE64" | Add the secrets (Part D3) |
| Update says "Signature mismatch" | That APK was signed with a different key; always release through GitHub Actions |
| Update says "no .sha256 file" | The release was uploaded by hand; use the tag workflow instead |
| Reminders late or missing | Allow exact alarms (Settings → Permissions), set battery to Unrestricted, enable Autostart |
| Wrong class times after import | Check 🟡 rows, tap 📷 to compare with the image, fix them, then save. Corrections are remembered |
| Screenshot import misses things | Crop to just the table, take straight-on photos in good light, use several screenshots for long tables, or use CSV for a 100% exact result |
| Odd/even week classes on the wrong week | Set **Settings → Semester → Semester starts** to the first teaching day |
| Widget not updating | It refreshes every 30 min and on every reminder; remove and re-add it once after installing |

---

## Part L: Feature tour (where is everything)

**Swipe left and right** between the four tabs, or tap them in the **floating dock** at the bottom (it hides while you scroll down). **⚙️** (top right) opens Settings, **🔔** sends a test notification of the type you pick. Change the dock's see-through level in ⚙️ → 🎨 Appearance.

1. **Today:**
   - live status of each class (upcoming, in progress, completed or cancelled), with a progress bar;
   - free periods between classes;
   - the nearest exam countdown, a weekend prep card and an update card when relevant;
   - **⋮** on a class: mark it **cancelled today**, or **add an exam** for that subject;
   - **✅ Attended / ❌ Missed** buttons once a class starts.
2. **Timetable:**
   - list view (jumps to **today** automatically) or **grid view**;
   - tap a class to edit it, including week parity (every, odd or even weeks);
   - **＋ Import**: screenshots, camera photo (with tips + auto-crop), CSV, paste, sample or manual entry.
3. **Exams:**
   - **Add exam**: searchable subject picker with colour dots (other subjects show "(not in timetable)");
   - **Exam week**: add many exams at once;
   - **Import exams from CSV**.
4. **Stats:**
   - **🧮 Bunk planner**: attendance % calculator, "can I skip N classes?", day planner, safe days;
   - **✏️ on a subject**: enter attendance you already had before ClassVibe, or fix a wrong ✅/❌;
   - attendance % per subject, streak, weekly hours and busiest day.

**Settings (⚙️):**
- reminder times, message style, ratings and your own messages;
- exam types – tap **Edit** to change each type's countdown (days before, daily final days, weekly);
- weekend prep, semester dates and holidays, theme and permissions;
- **🔳 QR sharing**, **📆 Export to calendar (.ics)**, backup/restore, CSV, **phone cloud backup** toggle;
- updates and About.

**App shortcuts:** long-press the ClassVibe icon → 🔔 Next class, 📅 Today, 📝 Add exam, 🧮 Bunk planner.

**Elsewhere:**
- **Home-screen widget:** long-press the home screen → **Widgets → ClassVibe – Now & Next**.
- **🔔 bell** in the top bar: sends a test reminder.

---

## Part M: Download website (GitHub Pages)

The `site/` folder is a small download page. The workflow `.github/workflows/pages.yml` publishes it.

**One-time setup:**
1. On GitHub open your repo → **Settings → Pages**.
2. Under **Build and deployment → Source**, choose **GitHub Actions**.
3. Go to **Actions → Deploy website → Run workflow** (or just push a change in `site/`).
4. After ~1 minute the site is live at **https://aksharganji23.github.io/ClassVibe/**.
5. Repo home page → ⚙️ next to **About** → tick **Use your GitHub Pages website** → Save.

**Links that never change:**
- Website: `https://aksharganji23.github.io/ClassVibe/`
- Latest version: share the **website** link above. Its Download button always gives the newest file, named with its version (e.g. `ClassVibe-v1.3.0.apk`).

After every release, the Release workflow restarts **Deploy website**, which writes `version.json` (latest version, APK link, checksum). The page and the app both read it, so you never edit the site for a release. If the website run ever fails, run **Actions → Deploy website → Run workflow** by hand.

## Part N: Sharing with QR codes

**Share:** ⚙️ → **🔳 QR sharing → Share**. Tick what to send (classes for some or all days, exams by type, holidays, semester dates, subjects; attendance is off by default), pick a funny theme and caption, then **Share** or **Save**. Each item shows how much it contains (e.g. "23 classes", "none"). Everything normally fits in **one QR**; only very big selections are split into parts.
- To let a friend scan **with their camera**, tap **📱 Show to scan** – a plain, bright, full-screen code. Share / Save keep the funny design.
- Friends need ClassVibe **1.4 or newer** to scan codes made in 1.4+.

**Scan:** **🔳 QR sharing → Scan** → camera or a saved image. ClassVibe shows what will be added:
- items you already have are **never changed**;
- exact duplicates are **skipped**;
- clashes (overlapping times) are listed, tick **add anyway** for any you want;
- multi-part codes: scan the parts in any order.

Scanning uses Google's code scanner, so ClassVibe still needs **no camera permission**.
