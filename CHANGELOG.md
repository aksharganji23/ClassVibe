# Changelog

All notable changes to ClassVibe. Versions follow `MAJOR.MINOR.PATCH`.
The release workflow copies the section for each tag into the GitHub Release notes.

## [1.1.0] – Launchpad 🚀

### Added
- **Settings page** with expandable sections and **multiple reminder times** per class (start, 5, 10, 15, 20, 30, 45, 60 min).
- **Light (default) & dark themes** – white + #0055A4 + orange / black + #0055A4 + #5AA2F0; first-launch theme picker; "Follow phone" option.
- **Exam planner** (semester, mid, lab, slip test, quiz, viva, assignment, custom) with smart countdown schedules, subject/room/time dropdowns from the timetable, "Save & add another", exam-week mode and CSV import.
- **Weekend prep** summaries every Saturday/Sunday (configurable days, times, look-ahead).
- **Swipe between tabs**; Timetable **auto-scrolls to today**; new **grid view**.
- **Attendance tracker** (Today cards + notification buttons) with 75% warnings, "can miss / need to attend" and streaks; **Stats** tab.
- **Home-screen widget** (Now / Next / nearest exam).
- **Holiday mode**, "class cancelled today", **free-period finder**.
- **Class-ending nudge** incl. "next class is in another block" alert; **morning summary**.
- **620 messages** with 6 styles (Classic, Savage, Motivational, Tenglish, Hinglish, Plain), per-category no-repeat, your own messages, 👍/👎 ratings, festival lines.
- **High-accuracy screenshot import**: crop/rotate, deskew, contrast enhancement, table grid detection, merged-cell (2-hour lab) support, two-pass OCR, fuzzy auto-correction incl. initials (AIDL → Artificial Intelligence and Deep Learning), learned corrections, 🟢🟡🔴 review with image comparison and grid preview, multi-screenshot merge, camera capture, CSV templates.
- **Backup & restore** (JSON), CSV export, share, **QR code**.
- **Odd/even weeks** and semester start/end dates.
- **In-app updates** from GitHub Releases (on open + every 8 h + manual), SHA-256 and signature verification, beta channel, skip version.
- GitHub Actions: build on every push, signed release APK on every tag.

### Changed
- Database v2 (keeps your v1.0 timetable via migration).
- Release builds are minified and signed with your own release key.

### Security / privacy
- No accounts, ads or analytics; only optional network use is the GitHub update check.
- No high-risk permissions; cleartext traffic disabled; immutable PendingIntents.

## [1.0.1] – Spark ⚡
### Fixed
- Test notification now uses the current or next class.

## [1.0.0] – Spark ⚡
- First release: CSV/OCR import, Today & Timetable screens, funny 10-minute reminders, reboot persistence.
