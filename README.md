# ClassVibe ⚡

Offline class timetable for Android with reminders that make you laugh, an exam planner, attendance tracking and a high-accuracy screenshot importer.

**Kotlin · Jetpack Compose (Material 3) · Room · DataStore · WorkManager · AlarmManager · ML Kit (on-device OCR)**

[![Download APK](https://img.shields.io/badge/⬇️_Download-ClassVibe.apk-0055A4?style=for-the-badge)](https://github.com/aksharganji23/ClassVibe/releases/latest/download/ClassVibe.apk)
[![Website](https://img.shields.io/badge/🌐_Website-aksharganji23.github.io%2FClassVibe-FF8C1A?style=for-the-badge)](https://aksharganji23.github.io/ClassVibe/)

👉 **Start here: [GUIDE.md](GUIDE.md)**. It covers pushing to GitHub, building, signing, releasing and auto-updates.

## Highlights
- **Reminders:** several reminder times per class (start / 5 / 10 / 15 / 20 / 30 / 45 / 60 min), 620 funny messages in 6 styles (incl. Tenglish & Hinglish), a class-ending nudge and a morning summary.
- **Exam planner:** smart countdowns for semester, mid, lab, slip test, quiz, viva and assignments, plus **weekend prep** summaries.
- **Attendance:** tracking with 75% warnings, a stats tab, holiday mode and "cancelled today".
- **Accurate screenshot import:** crop, deskew, grid detection, merged cells, two-pass OCR, auto-correction and a 🟢🟡🔴 review.
- **Screens and widgets:** light/dark themes (#0055A4), swipe tabs, a weekly grid and a home-screen widget.
- **Sharing & planning:** funny QR sharing (pick what to share; scanning only adds), calendar export (.ics), bunk planner with attendance % calculator, app shortcuts.
- **Data:** backup/restore, CSV export, phone cloud backup toggle, odd/even weeks and semester dates.
- **Updates and privacy:** verified in-app updates from GitHub Releases. No accounts, ads or trackers, and data never leaves the phone.

## Project layout
```
app/src/main/java/com/classvibe/app/
├─ MainActivity.kt, ClassVibeApp.kt
├─ data/          Room entities & DAOs, DB migration, settings (DataStore), calendar rules, backup, stats
├─ parser/        CSV + exam CSV parsers, OCR pipeline (preprocess, table detection, fuzzy fixes)
├─ messages/      MessageBank (assets/messages.txt – 620 lines)
├─ notifications/ scheduler, workers, receivers, exam & weekend planners, notification content
├─ update/        GitHub release checker, verification, update worker
├─ widget/        Now & Next home-screen widget
└─ ui/            view models, theme, components, screens
.github/workflows/  build.yml (every push) · release.yml (signed APK on tags)
```

## Build quickly
- **Android Studio:** File → Open → ▶ Run (debug build, installs as `com.classvibe.app.debug`).
- **GitHub:** push → Actions → **Build** → download the debug APK artifact.
- **Release:** push a tag `vX.Y.Z` → Actions → **Release** → APK + `.sha256` on the Releases page.

See [CHANGELOG.md](CHANGELOG.md) for version history.
