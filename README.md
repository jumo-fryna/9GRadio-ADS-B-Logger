# SkyLog 1090

A dedicated offline ADS-B 1090 MHz logger for Android and RTL-SDR / RTL-SDR Blog V4L, derived from [rkarikari/9GRadio](https://github.com/rkarikari/9GRadio) under GPL-3.0.

Starts directly in LIVE, detects USB and automatically tunes to 1090 MHz / 2 MS/s. Includes offline radar/tracks, Room history, aircraft identity cache, session statistics, native mobile reports, editable five-sheet XLSX, PDF and CSV.

- Separate Android package: `com.radiosport.skylog1090`.
- Separate private database: `skylog1090.db`.
- Install alongside original 9GRadio; no classic SDR/audio/scanner UI.
- Cloud builds: **Actions → SkyLog 1090 APK and tests → successful run → SkyLog-1090-debug**.

[Reception, reporting, test coverage and limitations](docs/SKYLOG_1090.md) · [Changelog](CHANGELOG_ADSB_LOGGER.md)

The RTL driver, native USB bridge and ADS-B decoder remain upstream code. Source namespace is preserved for JNI compatibility. No online API is required for logging. A local licensed identity dataset can be imported; unknown aircraft metadata remains unknown. Physical hardware reception and desktop Excel must be verified on those devices.
