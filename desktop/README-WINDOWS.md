# SkyLog 1090 Windows 0.1.1 — instrukcja MVP

## Zmiany 0.1.1 — odbiór i diagnostyka

Detektor uwzględnia energię impulsu rozłożoną na sąsiednie próbki przy 2 MS/s. Usunięto bezwzględny próg amplitudy oraz porównywanie każdej sąsiedniej próbki ze słabszym z czterech impulsów. Nadal wymagane jest poprawne CRC; nie dodano naprawiania bitów ani sztucznych samolotów. Dodano 12 testów przesunięcia fazowego próbek oraz test rozróżnienia statusu IQ/ADS-B. Łącznie: 49 testów.

Panel Odbiornik pokazuje liczbę próbek, poziom dBFS, przesterowanie, kandydatów preambuły/ADS-B i odrzucone CRC; kandydaci mogą być szumem. Diagnostykę można zapisać lokalnie jako JSON. Status „IQ AKTYWNE” oznacza tylko dopływ próbek; „LIVE ADS-B” wymaga zaakceptowanej ramki. Panel jest przewijany również na niższych ekranach.

Przeprowadzono krótkie próby z podłączonym Blog V4L: odbiór próbek i zmiana gain działają, nie potwierdzono jednak poprawnych ramek ADS-B w tych próbach. Przy 38,6 i 49,6 dB zebrano po 10 s IQ; własny dekoder nie uzyskał poprawnej ramki. Osobna próba programem rtl_adsb producenta trwała 15 s: 18 wynikowych wierszy, zero potwierdzonych ramek ADS-B po sprawdzeniu CRC. Test toru RF w okolicy 100 MHz wykazał większy poziom sygnału niż przy 1090 MHz, ale nie potwierdza czułości anteny na 1090 MHz. Poprawka detektora jest potwierdzona testami syntetycznymi; nie stanowi jeszcze dowodu odbioru samolotów.

Natywna aplikacja Windows 11 x64 dla RTL-SDR Blog V4L. Dashboard, radar geograficzny offline z granicami państw, tabela samolotów, wybór i szczegóły, filtrowanie, historia SQLite, zapisane trasy, statystyki i eksport CSV/XLSX/PDF. Bez konta, serwera i obowiązkowego dostępu do internetu. Tryb DEMO jest symulacją z osobną bazą w pamięci i nie potwierdza odbioru RF.

## Instalacja

1. Uruchom `SkyLog1090-0.1.1-Setup-x64.exe`. Instalacja dla bieżącego użytkownika, bez administratora. Domyślnie `%LOCALAPPDATA%\Programs\SkyLog1090`. Możesz wybrać skrót na pulpicie. Nie jest wymagany Python ani .NET.
2. Alternatywnie rozpakuj CAŁY `SkyLog1090-0.1.1-win-x64.zip` do stałego folderu i uruchom `SkyLog1090.exe`. Nie przenoś samego EXE bez katalogu `_internal`.
3. Instalator tej wersji nie jest podpisany certyfikatem wydawcy. Sumy SHA-256 są dołączone do wydania.
4. Przy pierwszym uruchomieniu możesz wybrać **Podgląd DEMO**. Zielony opis LIVE pojawia się dopiero po otwarciu USB i otrzymaniu danych IQ. Same otwarte USB ani DEMO nie dowodzą odbioru samolotów.

## Sterownik Twojego RTL-SDR Blog V4L

W paczce jest oficjalna biblioteka **RTL-SDR Blog V1.4.0 x64**, która dodaje obsługę V4L/R828S, oraz `pthreadVC2.dll` i `msvcr100.dll`. Nie należy podmieniać jej starszą biblioteką V4. Program ładuje kopię z własnego `_internal/drivers`, nie z przypadkowego folderu systemowego. DLL i mapa są pobierane ze sprawdzeniem SHA-256 przy budowaniu.

Osobnym krokiem jest przypisanie systemowego **WinUSB** do urządzenia USB:

1. Podłącz odbiornik bezpośrednio do USB laptopa. Zamknij SDR#, SDR++, rtl_tcp i inne aplikacje używające tego samego urządzenia.
2. Pobierz **Zadig** z https://zadig.akeo.ie/ — nie z katalogu z przypadkowymi sterownikami.
3. W Zadig: Options → List All Devices. Zidentyfikuj RTL-SDR Blog V4L / Bulk-In Interface 0 po nazwie i identyfikatorze USB. Przy niejednoznacznej nazwie porównaj listę przed i po odłączeniu odbiornika. Nie zmieniaj sterownika Wi-Fi, dysku, klawiatury ani interfejsu IR.
4. Wybierz WinUSB i Install/Replace Driver dla tego interfejsu. Ten krok może wymagać administratora. Nie instaluj sterownika automatycznie dla wszystkich urządzeń.
5. W SkyLog: **Odbiornik → Szukaj odbiorników**. Sprawdź nazwę produktu i numer seryjny. Kliknij **Uruchom RTL-SDR**. Jeśli sterownik DVB-T nie został zastąpiony lub USB zajmuje inny program, zobaczysz błąd, a nie udawany odbiór.

Instrukcja producenta: https://www.rtl-sdr.com/v4l/ . Wydanie biblioteki: https://github.com/rtlsdrblog/rtl-sdr-blog/releases/tag/V1.4.0 . Przypisanie WinUSB wykonuje użytkownik; instalator SkyLog nie modyfikuje sterowników innych urządzeń i nie zmienia EEPROM odbiornika.

## Parametry profilu

Z karty producenta V4L: tuner R828S, ADC RTL2832U 8 bit, zakres 500 kHz–1,766 GHz, stabilne pasmo 2,56 MHz, wejście SMA 50 Ω, TCXO 1 ppm. Źródło: https://www.rtl-sdr.com/wp-content/uploads/2026/08/RTLSDR_V4L_Datasheet4.pdf . Są to parametry modelu, nie pomiar egzemplarza użytkownika.

Aplikacja ustawia 1090 MHz, **2 MS/s**, manual gain (domyślnie 38,6 dB; rzeczywisty krok wybierany z tabeli tunera), PPM domyślnie 0, direct sampling OFF, RTL/IF AGC OFF, Bias-T OFF. Zmiany gain/PPM stosują się przy następnym uruchomieniu odbioru. Nie włączamy zasilania anteny pasywnej. MVP nie oferuje włączania Bias-T do zewnętrznego LNA.

## Dipol i lokalizacja

W **Antena i pozycja** jest schemat pionowego dipola. Dla 1090 MHz długość fali wynosi około 27,5 cm, a pojedyncze ramię λ/4 około 6,9 cm. Ustaw ramiona pionowo, jedno w górę i drugie w dół, z punktem zasilania pośrodku. To przybliżenie dla wolnej przestrzeni; wymiary gotowej anteny i uchwytu wpływają na strojenie. Nie przycinaj fabrycznych elementów bez pomiaru. Ustaw antenę wysoko, z możliwie odsłoniętym horyzontem, z dala od laptopa, zasilacza i metalu. Jeśli ramiona nie skracają się do tej długości, porównaj odbiór przy najmniejszej dostępnej długości; wynik wymaga próby terenowej.

Pozycję odbiornika wpisz ręcznie i zaznacz **Używaj podanych współrzędnych**, a następnie **Zapisz lokalizację**. Możesz też na żądanie pobrać lokalizację z Windows. Windows musi mieć włączoną usługę lokalizacji i dostęp dla aplikacji klasycznych. Laptop może podać przybliżoną pozycję Wi-Fi, nie GPS. Wyświetlana jest dokładność, jeśli system ją udostępni. Przy odmowie/braku pozycji pozostaje ręczne wpisanie. Współrzędne są zapisywane lokalnie w ustawieniach użytkownika. Aplikacja nie wysyła ich do własnego serwera; samo systemowe ustalenie pozycji może korzystać z usług Windows. Bez pozycji odbiornika dystans pozostaje nieznany, a mapa może wyśrodkować się na pierwszym odebranym samolocie.

## Obsługa radaru, historii i raportów

- Kółko myszy: zoom; przeciąganie: przesunięcie; Wyśrodkuj: reset. Kliknięcie znacznika lub wiersza wybiera samolot i jego trasę. Filtr ICAO/callsign, tylko z pozycją oraz dolny próg wysokości wpływają na tabelę i radar. Karty liczby samolotów i zasięgu opisują cały bieżący LIVE.
- LIVE obejmuje ramki widziane do 120 s temu. Szary znacznik oznacza pozycję starszą niż 30 s; po 120 s pozycja znika z mapy. Wiek w tabeli dotyczy ostatniej dowolnej ramki, nie ostatniej pozycji. Brak pozycji po samym callsign/velocity jest normalny. Globalne CPR potrzebuje aktualnej pary even/odd.
- Historia zapisuje wizyty (przerwa >5 min tworzy nową) i sesje. Daty są w UTC. Filtr dat wybiera wizyty nachodzące na przedział; nie rozdziela ich liczników wewnątrz wizyty. Dwuklik na wierszu pokazuje zapisaną trasę. Restart odbioru i nowa data UTC rozpoczynają sesję. SQLite ma transakcje/WAL; ostatnia sekunda niezatwierdzonych danych może przepaść po awarii.
- Raport powstaje z aktualnych filtrów Historii. Karty, ranking i eksport używają tego samego utrwalonego obrazu danych. **Odśwież z filtrów historii** tworzy nowszy. Wykres pokazuje pierwsze wykrycia unikalnych ICAO według godziny UTC w całym wybranym zbiorze; nie pokazuje liczby surowych ramek na godzinę. Ranking to wizyty, nie suma wszystkich wizyt danego ICAO.
- CSV: jedna linia na wizytę. XLSX: AIRCRAFT, SUMMARY, TRACKS; edytowalne wartości, brak makr. PDF: wielostronicowa tabela wizyt i liczników. Puste pola oznaczają wartości nieznane. Pliki nie zawierają IQ ani kompletu surowych ramek. Zapisane trasy są próbkowane i ograniczane do około 512 punktów na wizytę.
- Odtwarzanie AVR: pliki do 50 MB, HEX lub `*HEX;`, także `@` + 12 znaków znacznika czasu + HEX. Tempo 50 wierszy/s; czas odtworzenia jest czasem odbioru w historii, znaczniki oryginalnego pliku nie są odtwarzane. Źródło ma etykietę REPLAY. DEMO jest oddzielone od rzeczywistego pliku bazy i znika po zamknięciu programu.

## Dane, kopia zapasowa i usunięcie programu

Baza: `%LOCALAPPDATA%\SkyLog1090\skylog1090.sqlite`. Ustawienia: `HKEY_CURRENT_USER\Software\SkyLog1090\Windows`. Otwórz folder danych przyciskiem w panelu Odbiornik. Do kopii zapasowej najpierw zamknij aplikację i skopiuj bazę oraz ewentualne pliki `-wal`/`-shm` z tego folderu. Nie otwieraj tej samej bazy w dwóch instancjach; główny program ma blokadę pojedynczej instancji.

Odinstaluj przez Ustawienia → Aplikacje → SkyLog 1090. Historia i ustawienia pozostają; nie są kasowane przez deinstalator. Android, jego pakiet i baza nie są modyfikowane.

## Test terenowy — do wykonania z Twoim odbiornikiem

1. Zanotuj wersję Windows, model/serial SDR, antenę, jej położenie, gain, PPM, lokalizację i czas UTC. Podłącz antenę 1090 MHz. Uruchom bez DEMO i bez innych programów SDR.
2. Sprawdź nazwę USB i status po rozpoczęciu danych IQ. W diagnostyce powinien rosnąć licznik próbek; odebrane ADS-B powinny zwiększać CRC OK i liczbę samolotów. Brak samolotów przy rosnących próbkach wymaga oceny anteny, gain, otoczenia i ruchu lotniczego.
3. Odbieraj co najmniej 15 min. Sprawdź callsign, wysokość, prędkość, kurs i trasy. Nie wszystkie typy ramek niosą wszystkie pola. Porównanie z innym odbiornikiem wymaga uwzględnienia opóźnień i źródeł danych.
4. Sprawdź wybór na mapie i w tabeli, zoom, przesuwanie, wyszukiwanie i filtr wysokości. Porównaj zasięg po wpisaniu prawdziwej pozycji.
5. Odłącz USB podczas odbioru. Program powinien zgłosić zatrzymanie/błąd i zapisać historię. Podłącz, wyszukaj i uruchom ponownie. Automatyczne ponowne łączenie nie jest częścią MVP.
6. Zamknij i uruchom program ponownie. Sprawdź wizyty, liczniki i trasy w Historii. Utwórz raport, wyeksportuj CSV/XLSX/PDF; sprawdź te same sumy. Otwórz XLSX w rzeczywistym Excelu i PDF w czytniku.
7. Dla porównania ustawień dipola zachowaj ten sam gain i porównaj dwa 10–15-minutowe okresy; nie traktuj chwilowego ruchu lotniczego jako pomiaru charakterystyki anteny. Dłuższy test powinien objąć uśpienie/wznowienie Windows i co najmniej godzinę odbioru.

## Zakres i ograniczenia MVP

Odbiór USB na fizycznym V4L został potwierdzony w diagnostyce 0.1.1. Odbiór poprawnych ramek ADS-B z anteny nie został potwierdzony; szczegóły krótkich prób znajdują się na początku instrukcji. Testy syntetyczne i znane wektory nie potwierdzają czułości, zasięgu ani odporności na trudne warunki RF. Pozycja z Windows wymaga sprawdzenia na Twojej konfiguracji uprawnień; jej wywołanie jest zaimplementowane, nie potwierdzono uzyskania rzeczywistej pozycji.

Dekoder obsługuje DF17 i ICAO-addressed DF18 CF0, callsign TC1–4, wysokość barometryczną Q=1 i airborne CPR TC9–18, wektor prędkości względem ziemi TC19 subtype1/2 oraz vertical rate. Q=0/Gillham, powierzchniowe CPR, wysokości GNSS TC20–22, MLAT, TIS-B/ADS-R i DF z parzystością adresową nie są dekodowane. Nie naprawia błędów CRC. TC5–8 rozpoznaje tylko stan na ziemi. Algorytm 2 MS/s ma podstawowy detektor preambuły; skuteczność w realnym RF wymaga testu. Po przepełnieniu kolejki lub braku IQ przez 5 s odbiór zatrzymuje się z błędem.

Mapa jest przybliżonym lokalnym rzutem geograficznym z granicami Natural Earth, bez ulic i map lotniczych. Nie służy do nawigacji. Nie dołączono bazy rejestracji/operatorów/typów ani logo linii; te wartości pozostają nieznane. Nie ma importu bazy Android, pełnej zgodności pięciu arkuszy Android, automatycznej retencji, harmonogramów raportowania, statystyk według operatora/typu, automatycznego reconnect ani zapisu pełnego strumienia IQ. Historia bez limitu może wymagać późniejszej paginacji dla bardzo dużych zbiorów.

## Budowanie ze źródeł

Windows 11 x64, Python 3.14 x64, opcjonalnie Inno Setup 6.7.4 lub nowszy. Z katalogu repozytorium:

```powershell
python -m venv .venv
.venv\Scripts\python.exe -m pip install -r desktop\requirements.txt
desktop\scripts\build.ps1 -Python "$PWD\.venv\Scripts\python.exe" -Iscc 'C:\Program Files (x86)\Inno Setup 6\ISCC.exe'
```

Bez `-Iscc` powstaje paczka portable. Skrypt pobiera przypięte pliki producenta, ich źródła i mapę ze sprawdzeniem SHA-256, zbiera licencje, uruchamia testy, buduje EXE i ZIP. Dodatkowo `python desktop/run.py` uruchamia źródła. `python desktop/run.py --demo --smoke-test --data-dir work/smoke` uruchamia i zamyka okno po 2,5 s. Nie jest to test USB. Wersje bezpośrednich zależności są w requirements.txt; pełny zapis środowiska wydania w requirements-lock.txt. Licencje i źródła: THIRD-PARTY.md.

## Decyzja architektoniczna po przeglądzie repo

Repo bazowe: commit `56fb84243338cd13c2faa2f769a02a4631663207`. Przejrzano ProtocolDecoders.kt, SkyLogService/SkyLogIq, kod natywny JNI, agregator, dokumentację, Gradle oraz testy raportów, agregacji i Android IQ. Android jest zależny od UsbManager, Room, Service i JNI Linux/Android; nie można uruchomić tego sterownika USB jako biblioteki Windows.

Wybrano **Python + PySide6/Qt Widgets + NumPy + ctypes/librtlsdr + SQLite + PyInstaller/Inno Setup**: natywny interfejs desktopowy, szybkie wektorowe skanowanie próbek, oficjalne ABI odbiornika, lokalne dane i paczka bez instalacji Pythona u użytkownika. Warstwa core.py jest niezależna od Qt/Windows i nadaje się do dalszego użycia. Zachowano preambułę 0/2/7/9, demodulację PPM i wielomian CRC z Androida; skorygowano usuwanie bitu Q wysokości, znaki składowych prędkości i globalne CPR z limitem wieku 10 s. Wspólne neutralne wektory są w `shared/adsb-vectors`; jeden jest identyczny z Android SkyLogTest. To port i wspólny kontrakt testowy, jeszcze nie jeden silnik wykonywany przez Kotlin i Python. Nie przenoszono wydanego Androida na nowy moduł, aby nie wprowadzać tam regresji. Jego testów JVM/emulatora nie uruchamiano lokalnie; automatyczny workflow Android jest oceniany osobno.
