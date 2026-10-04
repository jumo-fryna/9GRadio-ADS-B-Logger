# SkyLog 1090 — 1.0-skylog1090

- Dedicated app (`com.radiosport.skylog1090`), private `skylog1090.db`, parallel installation with original 9GRadio and the earlier fork.
- LIVE launcher, automatic USB permission/device discovery, fixed 1090 MHz and decoder-required 2 MS/s, background service reception/logging, explicit connection state and counters.
- Offline geographic radar and bounded tracks; ADS-B-only RF/PPM/receiver/retention/identity/report settings.
- Native mobile reception dashboard: session/day/filter selection, shared report statistics, graphs, rankings, operator/type badges, aircraft cards/details/routes, Excel/PDF export and Share XLSX.
- Preserved Room aggregation/checkpoints/migrations, 512 historical points per reception, session statistics, local identity cache, editable XLSX (five sheets), PDF and CSV.
- Stage 1 verified launcher/service/pipeline/mobile screen with existing code retained. Stage 2 removes 228 confirmed unused source/resource/native files and unnecessary dependencies. Unchanged RTL driver, USB JNI and complete ProtocolDecoders are retained.
- GitHub Actions: debug APK for arm64-v8a, armeabi-v7a, x86_64; 17 JVM, 7 Android and 8 SQL tests, APK package/name checks and SHA-256.

Base: rkarikari/9GRadio `6ddd1df798f39640ee87f16b4abc1074c962dcfb`, GPL-3.0. Hardware V4L performance and desktop Excel are not validated by emulator tests. Older installations/histories are not automatically imported. See docs/SKYLOG_1090.md for units, snapshot/date filtering and chart semantics.
