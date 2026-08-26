# Playbook — StatePilot device-tests op jouw machine

Dit playbook beschrijft hoe je de **device-afhankelijke tests** (Fase 11 Data Layer + Fase 13 Tile) draait op jouw eigen machine met Android Studio. Alles wat hier niet kan, kon op de headless server niet draaien — maar op een normale dev-machine is het standaard werk.

> Repo: `https://github.com/jajadvert/statepilot` (private)

---

## 1. Vereisten

| Onderdeel | Versie |
|---|---|
| Android Studio | recente versie (Hedgehog of nieuwer — bevat de Wear OS pairing assistant) |
| JDK | 17 |
| Android SDK | platforms;android-34, build-tools 34.0.0, platform-tools, emulator |
| Hardware | 8–16 GB RAM (2 emulators + Studio), KVM/Hyper-V aan (hardware-virtualisatie) |

Controleer of virtualisatie aan staat:

```bash
# Linux: moet iets als vmx/svm tonen
grep -cE 'vmx|svm' /proc/cpuinfo
```

## 2. Clone + eerste build

```bash
git clone https://github.com/jajadvert/statepilot.git
cd statepilot
./gradlew test                    # 122 unit tests, zou groen moeten zijn
./gradlew assembleDebug           # APK's bouwen
```

Open daarna de map in Android Studio (File > Open > statepilot) en laat Gradle syncen.

## 3. JVM-tests draaien (geen device nodig)

```bash
./gradlew :domain:test :data:test :calendar:test :wear-protocol:test :app-phone:testDebugUnitTest
```

Dit draait ook automatisch in CI (GitHub Actions) bij elke push.

---

## 4. Fase 11 — Data Layer tests (gekoppelde phone + watch)

De Data Layer round-trip (`WearDataLayerInstrumentedTest`) vereist een **gekoppelde** phone-emulator en watch-emulator.

### 4a. Emulators aanmaken (Device Manager)

1. **Phone**: AVD met **Play Store**-image (bijv. `system-images;android-34;google_apis_playstore;x86_64`) — nodig voor de Wear OS-companion-app
2. **Watch**: AVD met Wear-image (bijv. `system-images;android-33;android-wear;x86_64`, device `wearos_small_round`)

### 4b. Pairen via de Android Studio-assistant

1. Start beide emulators (Device Manager > play-knop)
2. In het **device-dropdown** (bovenin, naast Run): **Wear OS emulator pairing assistant**
3. Kies de phone-AVD en de watch-AVD → **Next**
4. Android Studio start de bridge en installeert de Wear OS-app op de phone (Play Store-download, eenmalig)
5. In de Wear OS-app op de phone: **Pair with Emulator** → volg de wizard
6. Klaar als de watch in de app verschijnt

> Fallback (CLI): `adb forward tcp:5601 tcp:5601` op de phone + handmatig "Pair with emulator" — brozer, alleen als de assistant faalt.

### 4c. Data Layer tests draaien

```bash
# phone-app installeren (bevat de transport-kant)
./gradlew :app-phone:installDebug

# watch-app + test-APK installeren
./gradlew :app-wear:installDebug :app-wear:installDebugAndroidTest

# tests op de watch (ANDROID_SERIAL = watch-emulator-poort, vaak 5554)
ANDROID_SERIAL=emulator-5554 ./gradlew :app-wear:connectedDebugAndroidTest
```

De tests skippen netjes (`assumeTrue`) als er geen node gekoppeld is, dus een losse watch is veilig.

**Wat je zou moeten zien:** de 3 tests van `WearDataLayerInstrumentedTest` draaien écht — inclusief de round-trip via de Wear Data Layer. Logcat-check:

```bash
adb logcat -s WearableListenerService  # watch-kant ontvangt state
```

---

## 5. Fase 13 — Tile instrumented tests

De Tile-tests (`StatePilotTileServiceTest`) gebruiken de **Wear Tiles testing API** (`TestTileClient`) — ze drijven de echte `TileService` off-screen aan, zonder watch-UI. Ze draaien op de watch-emulator óf een echte watch.

```bash
# watch-emulator of echte watch aangesloten
./gradlew :app-wear:connectedDebugAndroidTest
```

Deze run draait dan **twee suites**:
- `WearMainActivityTest` — activity placeholder/state/revision (3 tests)
- `StatePilotTileServiceTest` — tile: current visible, due-buttons, action-ids, empty-state (5 tests)
- `WearDataLayerInstrumentedTest` — skipt zonder pairing (3 tests)

## 6. Echte Samsung watch (geen emulator nodig)

Zoals beschreven in `android-wear-emulators.md`:

```bash
# op de watch: Instellingen > Over de watch > IP-adres noteren
# developer options + ADB debugging AAN
adb connect <watch-ip>:5555
adb devices                      # watch zichtbaar als device
./gradlew :app-wear:installDebug :app-wear:installDebugAndroidTest
ANDROID_SERIAL=<watch-serial> ./gradlew :app-wear:connectedDebugAndroidTest
```

Kanttekening: de generieke Wear-emulator is AOSP Wear, geen One UI Watch. Samsung-specifieke zaken (skin, tiles, health-permissies) zie je alleen op de echte watch.

---

## Troubleshooting

| Probleem | Oplossing |
|---|---|
| `connectedDebugAndroidTest` faalt met "API level=1" (ddmlib) | Bekende bug: APK's handmatig installeren (`adb install -r -g ...`) en `am instrument` direct draaien |
| `No tests found` met legacy runner | Zet `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"` in `defaultConfig` (staat al in de repo) |
| Watch vindt de phone niet | Controleer dat de phone een **playstore**-image heeft; de Wear OS-app is nodig voor pairing |
| Pairing-assistant vindt emulators niet | Beide emulators volledig opgestart (boot voltooid) laten zijn; Device Manager > Wipe Data op de watch-AVD |
| Emulator traag/crasht | Geen hardware-virtualisatie? Dan alleen software-emulatie — zet `-gpu off -cores 2` en max 1 emulator tegelijk |

## Architectuur-houvast (waar zit wat)

| Pad | Inhoud |
|---|---|
| `app-phone/.../MessageClientWearTransport.kt` | Phone-kant van de Data Layer (publiceert state, ontvangt commands) |
| `app-wear/.../WearDataLayerService.kt` | Watch-kant (ontvangt state → cache, stuurt commands) |
| `app-wear/.../StatePilotTileService.kt` | Wear Tile (layout-logica in `wear-protocol/.../tile/TileStateMapper.kt`) |
| `app-wear/src/androidTest/` | WearMainActivityTest, StatePilotTileServiceTest, WearDataLayerInstrumentedTest |
| `wear-protocol/.../protocol/WearProtocol.kt` | DTO's + `WearDataLayerPaths` (gedeelde paden) |
