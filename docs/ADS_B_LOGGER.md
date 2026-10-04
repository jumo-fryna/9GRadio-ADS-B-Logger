# Przygotowanie i obsługa 9GRadio ADS-B Logger

Kod bazowy: https://github.com/rkarikari/9GRadio, commit `6ddd1df798f39640ee87f16b4abc1074c962dcfb`.
Pełna lista zmian znajduje się w `CHANGED_FILES.md`.

## Budowa w GitHub Actions

1. Zastosuj dostarczony patch w swoim klonie/forku tego repozytorium lub wgraj przygotowane źródła. Zachowaj pliki `.github/`.
2. Zapisz i wypchnij zmiany do GitHub. Workflow **ADS-B Logger APK and tests** uruchamia się po pushu/PR; można go również uruchomić przez **Actions → Run workflow**.
3. Job `build` instaluje SDK 35, NDK 27.0.12077973, CMake 3.22.1, JDK 21 oraz Gradle 8.13. Generuje kompletny Gradle Wrapper. Tylko w checkout CI odkłada upstreamową konfigurację daemon JVM JetBrains i używa skonfigurowanego Temurin 21.
4. Job `build` wykonuje kontrole statyczne, testy SQL i 17 testów JVM oraz buduje aplikację i APK testowy. Job `android-tests` uruchamia 4 testy instrumentacyjne na emulatorze Android 35 x86_64: migracje 1 → 3 / 2 → 3, PDF i lokalny cache.
5. Pobierz artefakt **9GRadio-ADS-B-Logger-debug**. Po rozpakowaniu zawiera `9GRadio-ADS-B-Logger-debug.apk` oraz jego sumę SHA-256 w pliku `.apk.sha256`. Raporty testów, wygenerowany schemat Room i wynik emulatora są osobnymi artefaktami.

Projekt i workflow są opublikowane w https://github.com/jumo-fryna/9GRadio-ADS-B-Logger. GitHub Actions zbudował APK oraz uruchomił 17 testów JVM i 4 testy Android zakończone powodzeniem. Lokalne Android SDK/NDK oraz kompletny wrapper nie są potrzebne do uruchomienia tego workflow. Klucz debug jest cache'owany w obrębie repozytorium, aby kolejne buildy mogły aktualizować wcześniejszą instalację debug, gdy cache jest dostępny. Instalacja na istniejącym wydaniu upstream wymaga zgodnego klucza podpisu; debug APK nie zastępuje automatycznie aplikacji podpisanej innym kluczem.

## Odbiór i historia

Otwórz Settings → **ADS-B Radar / Logger**. Istniejący odbiornik i dekoder pracują przy 1090 MHz; ramki trafiają do agregatora na Dispatchers.Default. Room zapisuje partie na Dispatchers.IO, poza UI i dekoderem. Przy zamknięciu radaru następuje zakończenie sesji i flush; poprzedni tryb odbioru i jego ustawienia są przywracane.

Sesja nasłuchu zaczyna się po połączeniu z odbiornikiem, kończy po utracie połączenia lub zamknięciu radaru. Zmiana pozycji odbiornika rozpoczyna nową sesję. Sesja pozostaje aktywna podczas przeglądania historii nad otwartym radarem. Ramki jednego ICAO24 są scalane w odbiór; przerwa dłuższa niż 5 minut rozpoczyna nowy odbiór w tej samej sesji nasłuchu.

**Receiver** pozwala podać współrzędne odbiornika. Bez nich maksymalny dystans pozostaje nieznany. Współrzędne 0,0 są poprawne wyłącznie po świadomym ustawieniu. Pozycja, wysokość i prędkość pozostają nieznane, dopóki dekoder nie dostarczy tych pól.

**ADS-B LOG / HISTORIA** jest dostępne z radaru i Settings. Filtry dat stosują UTC, początek włącznie i koniec następnego dnia wyłącznie. Wybierają odbiory przecinające zakres; nie przycinają liczników ramek wewnątrz już zagregowanego odbioru. Statystyki na ekranie dotyczą wybranej sesji lub wszystkich sesji. Statystyki raportu dotyczą wyeksportowanych odbiorów. Szczegóły otwierają wybraną wizytę i pozostałą historię ICAO24; kolejne odbiory można doładowywać przyciskiem.

Checkpoint co 5 sekund ogranicza zapisy dyskowe. Nagłe zabicie procesu może utracić jeszcze niezapisany fragment; przy kolejnym uruchomieniu niedomknięte sesje są zamykane na ostatnim checkpointcie. Nieudany zapis wyświetla komunikat na radarze i jest ponawiany bez wyłączania dekodera.

## XLSX — główny raport

Przycisk **XLSX** otwiera systemowy wybór miejsca zapisu. Raport wykorzystuje standardowy OOXML i nie wymaga internetu ani dodatkowej biblioteki biurowej na Androidzie.

- **PODSUMOWANIE**: tytuł ADS-B Reception Report, daty UTC, zakres sesji, sześć kart statystyk z formułami oraz dwa edytowalne wykresy powiązane z komórkami SAMOLOTY. Wykresy pokazują do 10 maszyn o największej liczbie ramek.
- **SAMOLOTY**: jeden wiersz na ICAO24 w wybranym eksporcie; metadane, czas pierwszego/ostatniego odbioru, suma ramek, zakres wysokości, maksymalna prędkość/dystans, ostatni znany kurs, vertical rate i pozycja, producent/model i liczba wizyt. Filtr oraz zamrożony pierwszy wiersz.
- **TRASY**: punkty z session ID, reception ID, ICAO24, kolejnością, czasem UTC, pozycją i wysokością.
- **SESJE**: identyfikatory, daty, czas nasłuchu, pozycja odbiornika, liczba wybranych maszyn i ramek oraz stan sesji.
- **RAW DATA**: pełne zapisane agregaty poszczególnych wizyt i metadane identyfikacyjne. Nie zawiera surowych bajtów Mode S ani IQ. Powiązanie z TRASY wykorzystuje reception ID.

Daty i liczby są typowanymi komórkami Excel; importowane teksty są komórkami tekstowymi, także gdy zaczynają się od `=`. Formuły podsumowania i wykresy można edytować w Microsoft Excel na Windows. Testy OOXML w CI przeszły; tego pliku nie otwierano jeszcze w desktopowym Excelu w tej sesji. Przy przekroczeniu limitu wierszy Excela eksport zgłasza błąd i wymaga zawężenia filtrów.

Operatorzy mają tekstowe badge. Nie dołączono logo ani obrazów pobieranych z internetu. Dossier PDF pozostaje opcjonalne: okładka i osobna karta każdego odbioru, lokalny rysunek trasy, jednostki ft/kt/NM. CSV jest dodatkowym eksportem RFC 4180 z ochroną tekstów przed formułami arkusza.

## Lokalna identyfikacja

**Local aircraft identity cache → Import JSON** importuje licencjonowany przez użytkownika zbiór; patrz `aircraft-identities.example.json` (dane fikcyjne). Pola: icao24, registration, manufacturer, model, operator, aircraftType. ICAO24 ma 6 cyfr szesnastkowych. Nie wyprowadza się operatora z callsign. Aktualizacja cache uzupełnia także metadane w widoku historycznych odbiorów.

Opcjonalne **Refresh HTTPS** używa podanego adresu datasetu, z limitami czasu i rozmiaru. Import jest transakcyjny, maksymalnie 8 MiB i 50 000 pozycji. Podstawowe logowanie nie wywołuje żadnego API. Baza nie zawiera domyślnie globalnego zbioru rejestracji; bez importu nieznane metadane pozostają nieznane.

## Weryfikacja

Lokalnie wykonano 8 testów SQL SQLite pobranego z kodu migracji i filtrów, sprawdzono leksykę/delimitery 21 plików Kotlin, 70 XML, identyfikatory zasobów, manifest, style XLSX oraz składnię YAML/shell CI. Nie są to kompilator Kotlin ani walidacja Room. W GitHub Actions potwierdzono budowę debug APK, 17/17 testów JVM oraz 4/4 testy instrumentacyjne na Android 35 x86_64. Odbiór i wydajność na fizycznym RTL-SDR Blog V4L wymagają sprawdzenia na urządzeniu; kod sterownika i dekodera nie został zmieniony.
