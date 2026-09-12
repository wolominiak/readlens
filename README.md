# ReadLens

Nakładka na Androida: czyta tekst z ekranu przez OCR, tłumaczy całą stronę
przez Gemini i pokazuje polski tekst w pływającym panelu nad dowolną
aplikacją (Storytel, Kindle, Legimi, przeglądarka — cokolwiek, co nie blokuje
zrzutów ekranu).

## Jak to działa

```
MediaProjection  →  ImageReader  →  Bitmap
      ↓
ML Kit Text Recognition (on-device, offline, model wbudowany w APK)
      ↓
hash z samych liter  →  zmienił się?  →  stabilny przez 2 odczyty?
      ↓
Gemini generateContent  →  panel TYPE_APPLICATION_OVERLAY
```

Detale, które mają znaczenie:

- **Klucz porównawczy to same litery.** Zegar, procent baterii i numer strony
  nie wyglądają jak nowa strona, więc nie wywołują tłumaczenia.
- **Debounce na 2 stabilne odczyty** — klatki z trwającego przewracania
  kartki nie idą do modelu.
- **Panel filtruje sam siebie z OCR.** Bloki tekstu przecinające prostokąt
  nakładki są odrzucane, inaczej polskie tłumaczenie wracałoby na wejście.
- **Połowa rozdzielczości** przy ekranach powyżej 1200 px krótszego boku —
  ML Kit i tak to przeczyta, a zużycie pamięci spada czterokrotnie.
- **Sieć na osobnym wątku** niż pętla przechwytywania, więc wolne łącze nie
  zatyka kolejki ImageReadera.
- **`thinkingBudget`** ustawiany automatycznie: 0 dla `flash`, 128 dla `pro`
  (pro nie przyjmuje zera i zwróciłby HTTP 400).

## Budowanie

Wymaga Android SDK (platforma 35, build-tools 35) i JDK 17+.

```bash
echo "sdk.dir=$ANDROID_HOME" > local.properties   # albo ścieżka wprost
./gradlew assembleDebug
```

APK ląduje w `app/build/outputs/apk/debug/app-debug.apk`.

Instalacja:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Klucz API

Klucza **nie ma w kodzie ani w APK** — to byłoby proszenie się o wyciek.
Aplikacja trzyma go w prywatnych SharedPreferences. Żeby nie przepisywać go
palcem na telefonie, wstrzyknij go z komputera:

```bash
adb shell am start -n com.readlens.app/.MainActivity --es gkey "TWOJ_KLUCZ"
```

Można tak samo podmienić model:

```bash
adb shell am start -n com.readlens.app/.MainActivity --es model "gemini-2.5-flash"
```

Albo po prostu wkleić klucz w pole w aplikacji — zapisuje się na stałe.

## Pierwsze uruchomienie

1. Otwórz ReadLens, wklej klucz (albo wstrzyknij przez adb).
2. **1. Zezwól na nakładkę** — Android wyrzuci do ustawień „Wyświetlanie nad
   innymi aplikacjami".
3. **2. Start** — zgoda na przechwytywanie ekranu, aplikacja sama schodzi w tło.
4. Otwórz czytnik. Po ~2 sekundach od zatrzymania na stronie pojawi się
   tłumaczenie.

## Panel

| | |
|---|---|
| przeciągnij pasek | przesuwa panel w pionie |
| `A-` / `A+` | rozmiar pisma |
| `⇅` | wysokość panelu: 35% / 55% / 75% ekranu |
| `⟳` | wymusza ponowne tłumaczenie bieżącej strony |
| `–` | zwija do kulki (kulkę też można przeciągać, tap rozwija) |
| `✕` | kończy pracę |

Zatrzymać można też z powiadomienia albo przyciskiem w aplikacji.

## Ograniczenia

- **`FLAG_SECURE` = koniec.** Jeśli czytnik blokuje zrzuty ekranu, MediaProjection
  dostaje czarny prostokąt i nie ma na to obejścia bez roota. Test: zrób
  zwykły zrzut ekranu w czytniku.
- OCR czyta to, co widać. Przypisy drobnym drukiem i tekst na tle grafiki
  wypadają gorzej.
- Każda strona to jedno wywołanie API. Przy `gemini-2.5-flash` i stronie
  ~300 słów koszt jest znikomy, ale przy maratonie czytania warto zerknąć
  na zużycie.
- Obrót ekranu przebudowuje przechwytywanie — jedna strona może się zgubić.

## Struktura

```
app/src/main/java/com/readlens/app/
  MainActivity.java       ustawienia, zgody, start/stop
  CaptureService.java     foreground service: capture → OCR → dispatch
  OverlayController.java  pływające okno, przeciąganie, filtr własnych granic
  GeminiClient.java       generateContent po HttpURLConnection, bez zależności
  Prefs.java              klucze SharedPreferences i domyślny prompt
```
