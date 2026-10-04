# SkyLog 1090

Dedicated ADS-B Android app derived from rkarikari/9GRadio (GPL-3.0). Upstream ownership and LICENSE remain intact. Package: `com.radiosport.skylog1090`. Internal Kotlin/JNI namespace remains `com.radiosport.ninegradio` to preserve the native USB bridge. Android package identity controls installation and private storage, independent of the Kotlin namespace.

## Reception

Launch goes straight to LIVE. The foreground service detects supported USB devices, asks for USB permission and automatically configures exactly 1090 MHz, 2,000,000 IQ samples/s, direct sampling off, no software frequency offset, gain index 26 by default, tuner/hardware AGC off by default and PPM 0. These values are configurable only where relevant to ADS-B. The decoder assumes 2 samples per microsecond; unlike the old generic receiver, the dedicated app never restores an unrelated modulation or sample rate.

`RtlSdrDevice`, `RtlSdrUsbHelper`, `UsbDeviceManager`, `RtlSdrDeviceSource`, `NativeDsp`, the native USB/DSP JNI file and the complete `ProtocolDecoders.kt` are retained unchanged. The dedicated pipeline uses the upstream uint8 normalization, DC removal (alpha 0.9999) and magnitude calculation, then calls the unchanged ADS-B decoder. A 240-sample carry preserves frames crossing USB chunk boundaries; a CRC-valid cross-boundary Mode S fixture is covered by the Android IQ test. A partial wake lock is held only while streaming, to keep USB reception alive with the screen off. It does not instantiate FFT, audio, scanning or digital voice pipelines. The two upstream streaming constants remain in a small DspEngine compatibility class so the driver stays unchanged.

Reception and logging survive navigation and rotation. Notification Stop stops reception. Reopening the app retries device discovery. USB detach stops the source and closes the session. The state line identifies actual receiver detection and streaming, not an assumed connection. Frame count is the number of CRC-valid aircraft frames emitted by the existing decoder, not all USB samples, CRC failures or RF bursts. Hardware reception/sensitivity still requires physical V4L validation; emulator tests cannot prove antenna performance.

## Data / report

Private Room file: `skylog1090.db`, under the new package. It never opens or imports the original application's database. The logger keeps reception aggregation, 5-minute visit gaps, 5-second checkpoints, at most 512 historical track points per visit, partial-frame merge, ICAO identity cache, statistics and XLSX/PDF/CSV. The existing v1→v2→v3 migrations and legacy schema tables remain for safe schema compatibility/testing; they expose no radio/bookmark UI. Retention defaults to unlimited (0). A positive day count removes completed old sessions with cascading receptions/routes, preserving identities and active sessions. The first decoded frame of a new UTC date starts a new listening session.

LIVE shows aircraft still seen within 120 seconds, identity, altitude, speed, course, receiver distance and age. Radar shows local geographic positions and bounded live trails without online map tiles; without a configured receiver it centres on a known aircraft. Positions and distances are unknown until actually available. Counts for today use UTC and persisted checkpoints, so counters can trail reception by a few seconds.

HISTORIA offers date/ICAO/callsign/registration/operator/type filters and aircraft reception details. RAPORT offers session/day selection and the same filters, a native dark dashboard with statistic cards, first-detection-by-hour chart, selectable aircraft altitude/range charts, track preview, top receptions, operator/type breakdowns and paginated aircraft cards. Badges include a future optional local Drawable slot for operator logos; no logos or online image dependencies are bundled.

The mobile dashboard uses `ReceptionReport` and `ReceptionStats` shared with XLSX. Its displayed snapshot is used by Excel, PDF and Share; press **ODŚWIEŻ RAPORT** to capture a newer snapshot during live reception. This preserves identical totals while reading/exporting a changing live session. Times and filters use UTC, distance NM, altitude ft, speed kt. Date filters select overlapping aggregated receptions rather than splitting counters inside a visit. The time chart shows first detections of unique ICAO24 per UTC hour, not an inferred per-minute raw frame count. Unknown track values are omitted. Paths are offline diagrams, not street-map tiles.

Excel remains fully editable OOXML with PODSUMOWANIE, SAMOLOTY, TRASY, SESJE and RAW DATA. RAW DATA is reception aggregates, not raw Mode-S bytes or IQ. PDF and CSV remain additional exports. Share writes XLSX into private cache and grants a temporary FileProvider read permission.

## Cloud build and validation

GitHub Actions builds **SkyLog-1090-debug.apk**, calculates SHA-256, checks the compiled package/name and runs all JVM and emulator tests. Download **SkyLog-1090-debug**, unpack ZIP and install APK alongside original 9GRadio or the previous ADS-B Logger fork. This is a new package with a fresh history; histories in older packages remain untouched.

Two stages: stage 1 first built/tested the new launcher/service/UI while retaining upstream source; stage 2 removed confirmed unused UI, audio/scanner/TCP modules, digital voice libraries, resources and Android dependencies, then reruns the complete workflow. Tests: 17 JVM tests, 7 Android tests (including isolation, IQ pipeline and native LIVE/report smoke tests) and 8 SQL tests. Original Room migrations, export tests and schema output remain covered. No local Android build is required.

A phone and the original app can both retain installations, but the same physical USB tuner should be used by one receiving app at a time. Microsoft Excel on Windows and actual V4L reception are not available in CI.
