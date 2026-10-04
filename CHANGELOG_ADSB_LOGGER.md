# 9GRadio ADS-B Logger — 1.39-adsb-logger

- Room v3: migracja addytywna 2 → 3; zachowana ścieżka 1 → 2 → 3 i dotychczasowe dane radia.
- Historia odbioru według ICAO24: scalanie częściowych ramek, statystyki, osobne odbiory po 5 minutach przerwy, checkpoint co 5 sekund.
- Trasy: próbkowanie co najmniej co 10 sekund przy przemieszczeniu ≥ 0,1 NM, limit 512 punktów, zachowanie początku i ostatniej pozycji.
- Settings → ADS-B Radar / Logger oraz ADS-B LOG / HISTORIA; filtry, karty samolotów, wcześniejsze odbiory i pozycja odbiornika.
- Główny eksport: edytowalny XLSX z pięcioma arkuszami, formułami, dwoma wykresami, filtrami i zamrożonymi nagłówkami. PDF i CSV są dodatkowe.
- Lokalny cache identyfikacji: import JSON i opcjonalne odświeżenie HTTPS; tekstowe badge operatorów działają offline.
- GitHub Actions: debug APK dla arm64-v8a, armeabi-v7a i x86_64, testy JVM oraz testy Android na emulatorze.

Podstawa: rkarikari/9GRadio, commit `6ddd1df798f39640ee87f16b4abc1074c962dcfb`.
Sterownik RTL-SDR, DSP, dekoder ADS-B, RtlSdrService i MainViewModel pozostają zgodne bajtowo z tym commitem.
Weryfikacja lokalna: 8 testów rzeczywistego SQL zakończonych powodzeniem oraz kontrole statyczne. GitHub Actions zbudował debug APK; potwierdzono 17/17 testów JVM oraz 4/4 testy Android (migracje Room, PDF, cache offline). Poprawiono konfigurację instalacji SDK, zamykanie PdfDocument oraz skrypt sprawdzania wyniku emulatora. Działanie i wydajność na fizycznym V4L pozostają do sprawdzenia.

- Dodatkowy wariant `loggerTest`: `com.radiosport.ninegradio.adsblogger`, nazwa 9GRadio ADS-B Logger, osobny APK i prywatna baza; instalacja obok oryginału. CI weryfikuje nazwę/pakiet APK i izolację katalogów na emulatorze. Funkcjonalność loggera bez zmian.
