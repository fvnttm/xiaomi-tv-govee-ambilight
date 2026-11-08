# Ambilight bez kamery: Xiaomi TV + Govee H605C

Aplikacja na Google TV, która steruje taśmą LED **Govee Envisual TV Backlight T2 (H605C)** na podstawie
tego, co faktycznie jest na ekranie, bez kamery Govee i bez komputera.

Zamiast zrzutów ekranu czyta piksele ze **sprzętowego toru wyświetlania MediaTek** (HAL `IRender`). Ten tor
widzi gotowy obraz idący na panel: aplikacje, wideo 4K i **wejścia HDMI** (np. konsolę). Nie obciąża przy tym
GPU, więc nie spowalnia odtwarzania. Taśma dostaje kolory przez Bluetooth.

> **English summary:** a Google TV app that drives a Govee H605C TV backlight from the actual picture, with no
> camera and no PC. It reads output pixels through the MediaTek render HAL (sees apps, 4K video and HDMI
> inputs, no GPU cost), running as the shell user via the TV's own ADB, and sends colours to the strip over BLE.
> Starts with the TV, turns the strip off in standby. Tested on a Xiaomi TV S Pro Mini LED 65 (2026, MT9689,
> Google TV / Android 14). Unofficial; uses undocumented vendor interfaces.

## Sprawdzony sprzęt

| | |
|---|---|
| Telewizor | Xiaomi TV S Pro Mini LED 65 (2026, „ladybird”), MediaTek MT9689, Google TV / Android 14 |
| Taśma | Govee H605C (Envisual TV Backlight T2), montaż na 4 bokach |

Inne telewizory z MediaTek i usługą `vendor.mediatek.hardware.render.IRender` mogą działać, ale tego nie sprawdzałem.

## Jak to działa

1. **Autostart.** `BootReceiver` uruchamia `AmbilightService` po starcie TV i po aktualizacji aplikacji.
   W czuwaniu usługa gasi taśmę, po wybudzeniu wznawia pracę.
2. **Uprawnienia bez komputera.** Usługa łączy się z ADB telewizora pod `127.0.0.1:5555` (klient
   [dadb](https://github.com/mobile-dev-inc/dadb)) własnym kluczem „Ambilight@TV” i uruchamia `PixelDaemon` przez
   `app_process` jako użytkownik `shell`. Tylko taki proces może wołać HAL renderowania.
3. **Odczyt obrazu.** `PixelDaemon` woła `IRender`:
   - `OpenController` (kod 69);
   - `GetPixelInfo` (kod 49) dla punktów przy krawędziach;
   - `CloseController` (kod 5).
4. **Kolory na taśmę.** `Govee` trzyma połączenie BLE z taśmą. Wysyła tylko zmiany, a segmenty o podobnym kolorze
   łączy w jedną komendę (maska segmentów), mieszcząc się w ~38 komendach/s. Taśma przyjmuje ich ok. 45/s.

### Ważna właściwość `GetPixelInfo`

Sprzęt **zatrzaskuje pozycję raz na wyświetlaną klatkę** i zwraca piksel z pozycji zatrzaśniętej przy ostatnim
vsync, czyli zwykle z *poprzedniego* wywołania. Dlatego każdy punkt jest pytany dwa razy, w odstępie jednej klatki:
druga odpowiedź zawsze należy do tego punktu. Daje to ok. 45 punktów/s. Kilka kontrolerów nie pomaga, bo HAL zwraca
ten sam uchwyt i jeden zatrzask.

Pozostałe zabezpieczenia w `PixelDaemon`:
- każdy segment czyta po kolei 3 punkty wzdłuż swojego odcinka krawędzi i uśrednia je, odrzucając pojedynczy
  odstający odczyt;
- gdy kilka punktów skacze naraz (cięcie sceny), segmenty przyjmują nowy kolor od razu;
- czarne pasy filmowe (2,39:1, 4:3) są wykrywane i pomijane.

### Dlaczego nie zwykłe przechwytywanie ekranu

Pomiary na tym TV, statystyki SurfaceFlingera, 8 s na każdy wariant:

| Metoda | Wideo 1080p | Wideo 4K | HDMI |
|---|---|---|---|
| MediaProjection / scrcpy (lustro ekranu) | każda klatka ekranu przez GPU, spóźnione klatki | **ekran ~4 kl./s** | czarny obraz |
| Mały zrzut `ScreenCapture.captureDisplay` 64×36 | bez wpływu, 25/s | ~270 ms na zrzut, ekran ~9 kl./s | czarny obraz |
| `PixelCopy` we własnym odtwarzaczu | bez wpływu | bez wpływu, ~3/s | — |
| **`IRender.GetPixelInfo` (ta aplikacja)** | **bez wpływu** | **bez wpływu** | **działa** |

## Instalacja

1. Na TV włącz **opcje programisty** (Ustawienia → System → Informacje → 7× „Kompilacja”) oraz
   **debugowanie USB i debugowanie sieciowe** (ADB na porcie 5555).
2. Zbuduj i zainstaluj aplikację:
   ```bash
   ./gradlew assembleRelease
   adb connect <ip-telewizora>:5555
   adb install app/build/outputs/apk/release/app-release.apk
   ```
   Wydanie jest podpisane kluczem debug z Twojego komputera.
3. Uruchom **Ambilight** z listy aplikacji. TV zapyta o zezwolenie na debugowanie dla **Ambilight@TV**:
   zaznacz **„Zawsze zezwalaj”** i OK. Uprawnienia Bluetooth aplikacja nada sobie sama przez ADB.
4. Zamknij aplikację **Govee Home** na telefonie (taśma przyjmuje jedno połączenie Bluetooth naraz).

## Kalibracja

Ekran aplikacji pokazuje podgląd kolorów wokół ekranu i stan (odczyt obrazu, taśma, ADB).

- **Test segmentów** (60 s) zapala:
  - segment 1 na czerwono;
  - segment 2 na żółto (pokazuje kierunek);
  - segment 8 na zielono;
  - ostatni, 14., na fioletowo.

  Kolory powinny wypaść przy liczbach 1, 8 i 14 na podglądzie.
- **Obraz testowy** ma krawędzie: lewa czerwona, góra zielona, prawa niebieska, dół żółty.
- **Kierunek taśmy**, **Układ** (3 lub 4 boki) i **Przesunięcie startu** (o jeden segment, ~26°) dopasowują
  mapowanie do montażu.

U mnie wyszło: 4 boki, kierunek zwykły (taśma zaczyna się w lewym dolnym rogu i idzie w górę), przesunięcie 1.

H605C według dokumentacji Govee ma 15 segmentów, ale na tej taśmie bit 15 nie zapala niczego, a segment 14
styka się z segmentem 1. Dlatego aplikacja używa 14 segmentów (`Govee.SEGMENTS`).

## Ograniczenia

- **Prędkość.** Ok. 45 punktów obrazu na sekundę (limit sprzętu), więc każdy segment odświeża się ~3×/s.
  Cięcia scen są widoczne po ~0,15–0,3 s, płynne przejścia trochę później.
- **ADB.** Debugowanie sieciowe musi zostać włączone. Ten TV sam przywraca je po restarcie.
  - Android unieważnia zgodę dla klucza po 7 dniach nieużywania. Jeśli TV nie był włączany przez ponad tydzień,
    zgoda pojawi się ponownie.
  - Opcjonalnie wyłączysz wygasanie tak (słabsze zabezpieczenie):
    `adb shell settings put global adb_allowed_connection_time 0`.
- **Bezpieczeństwo.** Port 5555 jest dostępny w sieci lokalnej. Nowe urządzenie zawsze wymaga zgody na ekranie TV.
- **Niesprawdzone:** aplikacje z DRM (Netflix, Disney+), treści HDR i Dolby Vision.
- **Nieudokumentowane API.** Interfejsy MediaTek i protokół BLE Govee nie są publiczne. Aktualizacja oprogramowania
  TV może je zmienić; wtedy aplikacja oddaje taśmę kamerze Govee.

## Diagnostyka

```bash
adb logcat -s Ambilight          # stan: odczyt obrazu, taśma, ADB, problemy
```

`PixelProbe` czyta wskazane punkty z podanym odstępem, przydatne przy innym modelu TV. Najpierw wyłącz Ambilight
w aplikacji, żeby demon nie korzystał z tego samego zatrzasku:

```bash
APK=$(adb shell pm path local.ambilight | cut -d: -f2)
adb shell "CLASSPATH=$APK app_process /system/bin local.ambilight.PixelProbe 50 3 100,540 3700,1080"
```

## Protokół BLE Govee H605C (użyty tutaj)

Usługa `00010203-0405-0607-0809-0a0b0c0d1910`, zapis do `…2b11`, powiadomienia z `…2b10`.
Pakiety mają 20 bajtów, a ostatni bajt to XOR pierwszych 19.

| Pakiet | Znaczenie |
|---|---|
| `33 05 15 01 RR GG BB 00 00 00 00 00 mLo mHi` | kolor dla maski segmentów |
| `33 05 15 02 LL mLo mHi` | jasność (%) dla maski segmentów |
| `33 04 LL` | jasność całej taśmy (%) |
| `33 01 01` / `33 01 00` | włącz / wyłącz |
| `aa 05` | odczyt trybu; odpowiedź `aa 05 00 …` to tryb kamery |
| `33 05 00 …` (te same bajty co odpowiedź) | powrót do trybu kamery |

## Licencja

MIT, zobacz [LICENSE](LICENSE). Projekt nieoficjalny, niezwiązany z Xiaomi, MediaTek ani Govee. Używasz na własną
odpowiedzialność.
