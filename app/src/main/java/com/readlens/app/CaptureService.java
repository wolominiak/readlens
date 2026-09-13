package com.readlens.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.Toast;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Przechwytywanie ekranu i sterowanie tlumaczeniem.
 *
 * Klatki odbiera osobny watek przez ImageReader.OnImageAvailableListener i
 * zamyka je natychmiast. Watek roboczy nigdy sam nie siega do ImageReadera -
 * czyta gotowy odcisk jasnosci albo zamawia pelna klatke. Dzieki temu kolejka
 * buforow nie zapycha sie przy animacji przewracania strony, a brak nowych
 * klatek na nieruchomym ekranie oznacza "obraz sie ustabilizowal", a nie
 * "nie mam danych".
 */
public class CaptureService extends Service {

    static final String ACTION_START = "com.readlens.app.action.START";
    static final String ACTION_STOP = "com.readlens.app.action.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "ReadLens";
    private static final String CHANNEL_ID = "readlens_capture";
    private static final int NOTIFICATION_ID = 4711;

    private static final int GRID_W = 24;
    private static final int GRID_H = 40;
    private static final int DIFF_THRESHOLD = 5;
    private static final int SETTLE_TICKS = 2;
    private static final int MIN_LETTERS = 60;
    /** Ile znakow konca poprzedniej strony podac jako kontekst. */
    private static final int TAIL_CHARS = 600;

    /** Kolor sondy - nie wystepuje na zadnej stronie ksiazki. */
    private static final int PROBE_COLOR = 0xFFFF00FF;

    private static final long HIDE_SETTLE_MS = 90;
    private static final long FRAME_TIMEOUT_MS = 1200;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean translating = new AtomicBoolean(false);
    private final AtomicBoolean wantFrame = new AtomicBoolean(false);

    private volatile MediaProjection projection;
    private volatile VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;
    private volatile TextRecognizer recognizer;
    private volatile OverlayController overlay;

    private HandlerThread workerThread;
    private volatile Handler worker;
    private HandlerThread frameThread;
    private volatile Handler frameHandler;
    private volatile ExecutorService network;

    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int capWidth;
    private volatile int capHeight;
    private volatile float captureScale = 1f;
    private volatile int capDensity;

    private volatile int intervalMs = Prefs.DEFAULT_INTERVAL;
    private volatile boolean running = false;

    // --- wymiana danych miedzy watkiem klatek a robotniczym ---
    /** Obszar probkowania, ustawiany przez workera. */
    private volatile Rect sampleBand = new Rect();
    /** Odcisk najswiezszej klatki, liczony na watku klatek. */
    private volatile int[] latestFingerprint;
    /** Zamowiona pelna klatka. */
    private volatile Bitmap orderedFrame;
    private volatile CountDownLatch frameLatch;

    /**
     * Czy przed zrzutem trzeba chowac nakladke.
     *
     * Przy udostepnianiu calego ekranu - tak, bo panel zaslania strone.
     * Przy udostepnianiu pojedynczej aplikacji (Android 14+) nakladki w ogole
     * nie ma w przechwytywanym obrazie, wiec chowanie jest zbedne i mrugniecie
     * znika. Tryb wykrywany jest sonda przy starcie.
     */
    private volatile boolean hideDuringCapture = true;
    private volatile boolean modeDetected = false;

    /** Ostatnia zachowana klatka w trybie jednej aplikacji. */
    private final Object frameLock = new Object();
    /** Klatka gotowa dla workera (surowa, z ewentualnym marginesem wiersza). */
    private Bitmap retainedFrame;
    /** Bufor odzyskany po workerze, zeby nie alokowac przy kazdej klatce. */
    private Bitmap spareBuffer;
    private volatile int retainedPadded;

    // --- stan detekcji, tylko watek roboczy ---
    private int[] lastFingerprint;
    private int[] translatedFingerprint;
    private Rect lastBand;
    private int stableTicks;
    private int failStreak;
    private int backoffTicks;
    private volatile String lastTranslatedKey = "";
    /** Ogon poprzedniej strony - kontekst dla zdan urwanych na granicy stron. */
    private volatile String previousTail = "";

    // ------------------------------------------------------------- cykl zycia

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            shutdown();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            if (running) {
                return START_NOT_STICKY;
            }
            startForegroundNotification();

            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
            if (resultData == null) {
                fail("Brak zgody na przechwytywanie ekranu.");
                return START_NOT_STICKY;
            }

            Prefs.migrate(this);
            SharedPreferences p = Prefs.get(this);
            intervalMs = Math.max(300, p.getInt(Prefs.KEY_INTERVAL, Prefs.DEFAULT_INTERVAL));

            try {
                startCapture(resultCode, resultData);
            } catch (Throwable t) {
                Log.e(TAG, "Nie udalo sie uruchomic przechwytywania", t);
                fail("Nie udalo sie wystartowac: " + t.getMessage());
            }
            return START_NOT_STICKY;
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (!running) {
            return;
        }
        Handler w = worker;
        if (w == null) {
            return;
        }
        w.post(new Runnable() {
            @Override
            public void run() {
                int oldW = capWidth;
                int oldH = capHeight;
                readDisplayMetrics();
                if (oldW != capWidth || oldH != capHeight) {
                    VirtualDisplay vd = virtualDisplay;
                    if (vd != null) {
                        try {
                            vd.resize(capWidth, capHeight, capDensity);
                        } catch (Throwable t) {
                            Log.w(TAG, "Resize nieudany: " + t.getMessage());
                        }
                    }
                    rebuildCaptureSurface();
                    resetDetection();
                }
                final int sw = screenWidth;
                final int sh = screenHeight;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        OverlayController o = overlay;
                        if (o != null) {
                            o.setScreenSize(sw, sh);
                            o.onScreenSizeChanged();
                        }
                    }
                });
            }
        });
    }

    // -------------------------------------------------------------- start

    private void startCapture(int resultCode, Intent resultData) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            fail("System odmowil dostepu do obrazu ekranu.");
            return;
        }

        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onCapturedContentResize(int width, int height) {
                final int w = even(width);
                final int h = even(height);
                Handler worker0 = worker;
                if (worker0 == null || w <= 0 || h <= 0) {
                    return;
                }
                worker0.post(new Runnable() {
                    @Override
                    public void run() {
                        capWidth = w;
                        capHeight = h;
                        captureScale = 1f;
                        VirtualDisplay vd = virtualDisplay;
                        if (vd != null) {
                            try {
                                vd.resize(capWidth, capHeight, capDensity);
                            } catch (Throwable t) {
                                Log.w(TAG, "Resize nieudany: " + t.getMessage());
                            }
                        }
                        rebuildCaptureSurface();
                        resetDetection();
                    }
                });
            }

            @Override
            public void onStop() {
                Log.i(TAG, "MediaProjection zatrzymane przez system");
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        shutdown();
                    }
                });
            }
        }, main);

        readDisplayMetrics();
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);

        OverlayController o = new OverlayController(this, new OverlayController.Listener() {
            @Override
            public void onRefreshRequested() {
                OverlayController oc = overlay;
                if (oc != null) {
                    oc.setStatus("odświeżam…");
                }
                Handler w = worker;
                if (w != null) {
                    w.post(new Runnable() {
                        @Override
                        public void run() {
                            resetDetection();
                        }
                    });
                }
            }

            @Override
            public void onStopRequested() {
                shutdown();
            }
        });
        o.setScreenSize(screenWidth, screenHeight);
        o.show();
        overlay = o;

        frameThread = new HandlerThread("readlens-frames");
        frameThread.start();
        frameHandler = new Handler(frameThread.getLooper());

        workerThread = new HandlerThread("readlens-capture");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        network = Executors.newSingleThreadExecutor();

        running = true;

        createVirtualDisplay();
        worker.postDelayed(tick, intervalMs);
    }

    private void readDisplayMetrics() {
        int w;
        int h;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
            Context displayContext = createDisplayContext(display);
            WindowManager wm = displayContext.getSystemService(WindowManager.class);
            Rect bounds = wm.getMaximumWindowMetrics().getBounds();
            w = bounds.width();
            h = bounds.height();
        } else {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            w = metrics.widthPixels;
            h = metrics.heightPixels;
        }

        screenWidth = w;
        screenHeight = h;

        captureScale = (Math.min(w, h) > 1200) ? 0.5f : 1f;
        capWidth = even((int) (w * captureScale));
        capHeight = even((int) (h * captureScale));
        capDensity = Math.max(80, (int) (getResources().getDisplayMetrics().densityDpi
                * captureScale));
    }

    private static int even(int value) {
        return value - (value % 2);
    }

    /** Odbiera klatki i NATYCHMIAST je zamyka - inaczej kolejka buforow staje. */
    private final ImageReader.OnImageAvailableListener frameListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = null;
                    try {
                        image = reader.acquireLatestImage();
                        if (image == null) {
                            return;
                        }
                        if (wantFrame.compareAndSet(true, false)) {
                            orderedFrame = toBitmap(image);
                            CountDownLatch latch = frameLatch;
                            if (latch != null) {
                                latch.countDown();
                            }
                        } else {
                            latestFingerprint = sampleFingerprint(image, sampleBand);
                            if (!hideDuringCapture) {
                                retainFrame(image);
                            }
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "Klatka odrzucona: " + t.getMessage());
                    } finally {
                        if (image != null) {
                            try {
                                image.close();
                            } catch (Throwable ignored) {
                                // juz zamknieta
                            }
                        }
                    }
                }
            };

    private void createVirtualDisplay() {
        MediaProjection p = projection;
        Handler fh = frameHandler;
        if (p == null || fh == null) {
            return;
        }
        ImageReader reader = ImageReader.newInstance(capWidth, capHeight,
                PixelFormat.RGBA_8888, 3);
        reader.setOnImageAvailableListener(frameListener, fh);
        imageReader = reader;
        virtualDisplay = p.createVirtualDisplay(
                "readlens",
                capWidth, capHeight, capDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.getSurface(),
                null, null);
    }

    /**
     * Resetuje potok klatek BEZ tworzenia nowego VirtualDisplay.
     *
     * Od Androida 14 createVirtualDisplay() wolno wywolac dokladnie raz na
     * jedna zgode uzytkownika - drugie wywolanie rzuca SecurityException i
     * zabija cala sesje przechwytywania. Legalna droga to podmiana powierzchni
     * na istniejacym wyswietlaczu, co przy okazji wymusza swieza klatke.
     */
    private void rebuildCaptureSurface() {
        VirtualDisplay vd = virtualDisplay;
        Handler fh = frameHandler;
        if (vd == null || fh == null) {
            return;
        }
        ImageReader previous = imageReader;
        try {
            ImageReader reader = ImageReader.newInstance(capWidth, capHeight,
                    PixelFormat.RGBA_8888, 3);
            reader.setOnImageAvailableListener(frameListener, fh);
            imageReader = reader;
            latestFingerprint = null;
            vd.setSurface(reader.getSurface());
        } catch (Throwable t) {
            Log.w(TAG, "Podmiana powierzchni nieudana: " + t.getMessage());
            return;
        }
        if (previous != null) {
            try {
                previous.setOnImageAvailableListener(null, null);
            } catch (Throwable ignored) {
                // trudno
            }
            try {
                previous.close();
            } catch (Throwable ignored) {
                // juz zamkniety
            }
        }
    }

    private void resetDetection() {
        lastFingerprint = null;
        translatedFingerprint = null;
        lastTranslatedKey = "";
        previousTail = "";
        stableTicks = 0;
        failStreak = 0;
        backoffTicks = 0;
    }

    // --------------------------------------------------------------- petla

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            try {
                step();
            } catch (Throwable t) {
                Log.e(TAG, "Blad w petli przechwytywania", t);
            }
            Handler w = worker;
            if (running && w != null) {
                w.postDelayed(this, intervalMs);
            }
        }
    };

    private void step() {
        Rect band = freeBand();
        if (lastBand == null || !lastBand.equals(band)) {
            lastBand = band;
            sampleBand = band;
            lastFingerprint = null;
            translatedFingerprint = null;
            stableTicks = 0;
            return;
        }

        int[] fingerprint = latestFingerprint;
        if (fingerprint == null) {
            return;
        }

        if (!modeDetected) {
            detectCaptureMode();
            lastFingerprint = null;
            stableTicks = 0;
            return;
        }

        if (lastFingerprint == null || differs(fingerprint, lastFingerprint)) {
            lastFingerprint = fingerprint;
            stableTicks = 0;
            return;
        }

        // Brak nowych klatek tez tu trafia: odcisk sie nie zmienil, wiec obraz
        // stoi. To jest wlasnie warunek "strona sie ustabilizowala".
        stableTicks++;
        if (stableTicks < SETTLE_TICKS) {
            return;
        }
        if (translating.get()) {
            return;
        }
        if (translatedFingerprint != null && !differs(fingerprint, translatedFingerprint)) {
            return;
        }

        // Po nieudanej probie nie mrugaj co pol sekundy - odstepy rosna.
        if (failStreak > 0) {
            if (++backoffTicks < Math.min(failStreak * 3, 20)) {
                return;
            }
            backoffTicks = 0;
        }

        if (readAndTranslatePage()) {
            translatedFingerprint = fingerprint;
            failStreak = 0;
            backoffTicks = 0;
        } else if (failStreak >= 8) {
            // Osiem prob pod rzad to nie chwilowy potkniecie. Przestan
            // meczyc ekran i powiedz wprost, co zrobic.
            translatedFingerprint = fingerprint;
            postWaiting("Nie udaje się pobrać obrazu ekranu. Naciśnij ⟳ w pasku "
                    + "panelu, żeby spróbować od nowa, albo zatrzymaj ReadLens "
                    + "i uruchom Start ponownie.", "poddaję się");
        }
    }

    private Rect freeBand() {
        int w = capWidth;
        int h = capHeight;
        if (!hideDuringCapture) {
            // nakladki nie ma w obrazie - probkuj cala klatke
            return new Rect(0, 0, w, h);
        }
        OverlayController o = overlay;
        Rect occ = (o != null) ? o.getOccupiedRect() : new Rect();
        if (occ.isEmpty()) {
            return new Rect(0, 0, w, h);
        }
        float s = captureScale;
        int top = clamp((int) (occ.top * s), 0, h);
        int bottom = clamp((int) (occ.bottom * s), 0, h);

        if (top >= h - bottom) {
            return new Rect(0, 0, w, Math.max(8, top));
        }
        return new Rect(0, Math.min(bottom, h - 8), w, h);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static boolean differs(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) {
            return true;
        }
        long sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += Math.abs(a[i] - b[i]);
        }
        return (sum / a.length) > DIFF_THRESHOLD;
    }

    // ------------------------------------------- praca na watku klatek

    private int[] sampleFingerprint(Image image, Rect band) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int imgW = image.getWidth();
        int imgH = image.getHeight();

        int left = clamp(band.isEmpty() ? 0 : band.left, 0, imgW - 1);
        int right = clamp(band.isEmpty() ? imgW : band.right, left + 1, imgW);
        int top = clamp(band.isEmpty() ? 0 : band.top, 0, imgH - 1);
        int bottom = clamp(band.isEmpty() ? imgH : band.bottom, top + 1, imgH);

        int[] out = new int[GRID_W * GRID_H];
        int idx = 0;
        for (int gy = 0; gy < GRID_H; gy++) {
            int y = top + (bottom - top) * gy / GRID_H;
            for (int gx = 0; gx < GRID_W; gx++) {
                int x = left + (right - left) * gx / GRID_W;
                int offset = y * rowStride + x * pixelStride;
                int r = buffer.get(offset) & 0xFF;
                int g = buffer.get(offset + 1) & 0xFF;
                int b = buffer.get(offset + 2) & 0xFF;
                out[idx++] = (r * 77 + g * 151 + b * 28) >> 8;
            }
        }
        return out;
    }

    /**
     * Kopiuje klatke do bufora dla workera. Kazda klatka, bez przepuszczania -
     * ostatnia przed zatrzymaniem obrazu jest dokladnie ta, ktora widzi
     * czytelnik. Bufor wraca po workerze, wiec alokacja zdarza sie raz na
     * odczytana strone, a nie raz na klatke.
     */
    private void retainFrame(Image image) {
        int h = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int padded = image.getWidth() + (rowStride - pixelStride * image.getWidth()) / pixelStride;

        Bitmap target;
        synchronized (frameLock) {
            target = spareBuffer;
            spareBuffer = null;
        }
        if (target == null || target.isRecycled()
                || target.getWidth() != padded || target.getHeight() != h) {
            if (target != null && !target.isRecycled()) {
                target.recycle();
            }
            target = Bitmap.createBitmap(padded, h, Bitmap.Config.ARGB_8888);
        }
        buffer.rewind();
        target.copyPixelsFromBuffer(buffer);

        synchronized (frameLock) {
            Bitmap previous = retainedFrame;
            retainedFrame = target;
            retainedPadded = padded;
            spareBuffer = previous;
        }
    }

    private Bitmap toBitmap(Image image) {
        int w = image.getWidth();
        int h = image.getHeight();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * w;
        int paddedWidth = w + rowPadding / pixelStride;

        Bitmap padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedWidth == w) {
            return padded;
        }
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, w, h);
        if (cropped != padded) {
            padded.recycle();
        }
        return cropped;
    }

    // ----------------------------------------------- pelny odczyt strony

    /**
     * @return true, jesli strone udalo sie odczytac i nie ma po co ponawiac
     */
    private boolean readAndTranslatePage() {
        postWaiting("Nowa strona — czytam…", "czytam");

        Bitmap bitmap;
        if (hideDuringCapture) {
            bitmap = captureWithOverlayHidden();
        } else {
            bitmap = takeRetainedFrame();
        }

        if (bitmap == null) {
            failStreak++;
            if (virtualDisplay == null) {
                postWaiting("Sesja przechwytywania ekranu wygasła. "
                        + "Otwórz ReadLens i naciśnij Start jeszcze raz.", "sesja wygasła");
            } else if (failStreak >= 4) {
                postWaiting("Ekran nie oddaje obrazu (próba " + failStreak + "). "
                        + "Jeśli czytnik zasłania treść podczas udostępniania ekranu, "
                        + "tej drogi nie da się obejść.", "ponawiam " + failStreak);
            } else {
                postWaiting("Nie udało się złapać obrazu strony (próba " + failStreak
                        + "). Próbuję dalej…", "ponawiam " + failStreak);
            }
            return false;
        }

        String pageText;
        try {
            pageText = recognize(bitmap, true);
        } finally {
            bitmap.recycle();
        }

        if (pageText == null) {
            failStreak++;
            postWaiting("Nie udało się odczytać tekstu (próba " + failStreak
                    + "). Próbuję dalej…", "ponawiam " + failStreak);
            return false;
        }

        String key = normalizeKey(pageText);
        if (key.length() < MIN_LETTERS) {
            postWaiting("Nie widzę tekstu książki na ekranie.", "czekam");
            return true;
        }
        if (key.equals(lastTranslatedKey)) {
            postRestore();
            return true;
        }
        lastTranslatedKey = key;
        String tail = previousTail;
        previousTail = tailOf(pageText);
        dispatchTranslation(pageText, tail);
        return true;
    }

    /** Tryb calego ekranu: panel na chwile znika, zeby nie zaslanial strony. */
    private Bitmap captureWithOverlayHidden() {
        // niech klatka wywolana zmiana tekstu w panelu zdazy przeleciec
        SystemClock.sleep(HIDE_SETTLE_MS);

        final CountDownLatch[] armed = new CountDownLatch[1];
        runOnMain(new Runnable() {
            @Override
            public void run() {
                armed[0] = armFrameOrder();
                OverlayController o = overlay;
                if (o != null) {
                    o.setCaptureMode(true);
                }
            }
        }, 700);

        try {
            if (armed[0] == null) {
                armed[0] = armFrameOrder();
            }
            Bitmap frame = awaitOrderedFrame(armed[0], FRAME_TIMEOUT_MS);
            if (frame == null) {
                Log.w(TAG, "Brak klatki, podmieniam powierzchnie");
                CountDownLatch retry = armFrameOrder();
                rebuildCaptureSurface();
                frame = awaitOrderedFrame(retry, FRAME_TIMEOUT_MS);
            }
            return frame;
        } finally {
            setOverlayHidden(false);
        }
    }

    /**
     * Tryb pojedynczej aplikacji: nie trzeba nic chowac ani wymuszac klatki.
     * Watek klatek trzyma ostatnia, a skoro ekran stoi, to jest wlasnie ta,
     * ktora widzi czytelnik.
     */
    private Bitmap takeRetainedFrame() {
        Bitmap frame;
        int padded;
        synchronized (frameLock) {
            frame = retainedFrame;
            padded = retainedPadded;
            retainedFrame = null;
        }
        if (frame == null) {
            return null;
        }
        int w = capWidth;
        if (padded <= w || frame.getWidth() <= w) {
            return frame;
        }
        Bitmap cropped = Bitmap.createBitmap(frame, 0, 0, w, frame.getHeight());
        if (cropped != frame) {
            synchronized (frameLock) {
                if (spareBuffer == null) {
                    spareBuffer = frame;
                } else {
                    frame.recycle();
                }
            }
        }
        return cropped;
    }

    /**
     * Sprawdza raz na sesje, czy nakladka w ogole trafia do przechwytywanego
     * obrazu. Panel dostaje na moment nienaturalny kolor: jesli pojawi sie w
     * klatce, udostepniany jest caly ekran i panel trzeba chowac. Jesli zmiana
     * koloru nie wyprodukowala zadnej klatki, a potok dziala - udostepniana
     * jest pojedyncza aplikacja i chowanie jest niepotrzebne.
     */
    private void detectCaptureMode() {
        modeDetected = true;

        OverlayController o = overlay;
        Rect occ = (o != null) ? o.getOccupiedRect() : new Rect();
        if (o == null || occ.isEmpty()) {
            hideDuringCapture = true;
            return;
        }

        final CountDownLatch[] armed = new CountDownLatch[1];
        runOnMain(new Runnable() {
            @Override
            public void run() {
                armed[0] = armFrameOrder();
                OverlayController oc = overlay;
                if (oc != null) {
                    oc.setProbeColor(PROBE_COLOR);
                }
            }
        }, 700);

        Bitmap probe;
        try {
            if (armed[0] == null) {
                armed[0] = armFrameOrder();
            }
            probe = awaitOrderedFrame(armed[0], 900);
        } finally {
            runOnMain(new Runnable() {
                @Override
                public void run() {
                    OverlayController oc = overlay;
                    if (oc != null) {
                        oc.clearProbe();
                    }
                }
            }, 700);
        }

        if (probe != null) {
            float s = captureScale;
            int x = clamp((int) ((occ.left + occ.right) / 2f * s), 0, probe.getWidth() - 1);
            int y = clamp((int) ((occ.top + occ.bottom) / 2f * s), 0, probe.getHeight() - 1);
            hideDuringCapture = isProbeColor(probe.getPixel(x, y));
            probe.recycle();
        } else {
            // Brak klatki mimo zmiany koloru panelu: albo nakladki nie ma w
            // obrazie (pojedyncza aplikacja), albo potok stoi. Jesli odciski
            // przychodza, potok zyje - czyli to pierwszy przypadek.
            hideDuringCapture = (latestFingerprint == null);
        }

        Log.i(TAG, "Tryb przechwytywania: "
                + (hideDuringCapture ? "caly ekran" : "jedna aplikacja"));
        postStatus(hideDuringCapture ? "tryb: cały ekran" : "tryb: jedna aplikacja");
    }

    private static boolean isProbeColor(int pixel) {
        int r = (pixel >> 16) & 0xFF;
        int g = (pixel >> 8) & 0xFF;
        int b = pixel & 0xFF;
        return r > 180 && g < 90 && b > 180;
    }

    /**
     * Uzbraja zamowienie na pelna klatke.
     *
     * KOLEJNOSC MA ZNACZENIE: trzeba to wywolac PRZED czynnoscia, ktora klatke
     * wyprodukuje (ukryciem nakladki albo podmiana powierzchni). Ekran w trakcie
     * czytania stoi, wiec ta jedna wymuszona klatka jest jedyna, jaka przyjdzie -
     * uzbrojenie po fakcie oznacza czekanie w nieskonczonosc.
     */
    private CountDownLatch armFrameOrder() {
        Bitmap stale = orderedFrame;
        orderedFrame = null;
        if (stale != null) {
            stale.recycle();
        }
        CountDownLatch latch = new CountDownLatch(1);
        frameLatch = latch;
        wantFrame.set(true);
        return latch;
    }

    private Bitmap awaitOrderedFrame(CountDownLatch latch, long timeoutMs) {
        if (latch != null) {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        wantFrame.set(false);
        frameLatch = null;
        Bitmap frame = orderedFrame;
        orderedFrame = null;
        return frame;
    }

    private boolean runOnMain(final Runnable action, long timeoutMs) {
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    action.run();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            return done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean setOverlayHidden(final boolean invisible) {
        final OverlayController o = overlay;
        if (o == null) {
            return false;
        }
        return runOnMain(new Runnable() {
            @Override
            public void run() {
                o.setCaptureMode(invisible);
            }
        }, 700);
    }

    private String recognize(Bitmap bitmap, boolean overlayWasHidden) {
        TextRecognizer r = recognizer;
        if (r == null) {
            return null;
        }
        try {
            InputImage input = InputImage.fromBitmap(bitmap, 0);
            Text result = Tasks.await(r.process(input), 25, TimeUnit.SECONDS);
            return assemble(result, overlayWasHidden);
        } catch (Throwable t) {
            Log.w(TAG, "OCR nieudany: " + t.getMessage());
            return null;
        }
    }

    private String assemble(Text result, boolean overlayWasHidden) {
        Rect blocked = new Rect();
        if (!overlayWasHidden) {
            OverlayController o = overlay;
            Rect occ = (o != null) ? o.getOccupiedRect() : new Rect();
            float s = captureScale;
            if (!occ.isEmpty()) {
                blocked = new Rect(
                        (int) (occ.left * s), (int) (occ.top * s),
                        (int) (occ.right * s), (int) (occ.bottom * s));
            }
        }

        List<Text.TextBlock> blocks = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            Rect box = block.getBoundingBox();
            if (box == null) {
                continue;
            }
            if (!blocked.isEmpty() && Rect.intersects(blocked, box)) {
                continue;
            }
            blocks.add(block);
        }

        Collections.sort(blocks, new Comparator<Text.TextBlock>() {
            @Override
            public int compare(Text.TextBlock a, Text.TextBlock b) {
                Rect ra = a.getBoundingBox();
                Rect rb = b.getBoundingBox();
                if (ra == null || rb == null) {
                    return 0;
                }
                int dy = Integer.compare(ra.top, rb.top);
                return dy != 0 ? dy : Integer.compare(ra.left, rb.left);
            }
        });

        StringBuilder sb = new StringBuilder();
        for (Text.TextBlock block : blocks) {
            String t = block.getText();
            if (t == null || t.trim().isEmpty()) {
                continue;
            }
            sb.append(t.trim()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String normalizeKey(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetter(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------- tlumaczenie

    /** Ostatnie zdania strony, uciete na granicy slowa. */
    private static String tailOf(String pageText) {
        if (pageText == null) {
            return "";
        }
        String flat = pageText.replace('\n', ' ').trim();
        if (flat.length() <= TAIL_CHARS) {
            return flat;
        }
        String tail = flat.substring(flat.length() - TAIL_CHARS);
        int space = tail.indexOf(' ');
        return (space > 0 && space < 40) ? tail.substring(space + 1) : tail;
    }

    private void dispatchTranslation(final String pageText, final String tail) {
        ExecutorService net = network;
        if (net == null || !translating.compareAndSet(false, true)) {
            return;
        }

        final SharedPreferences p = Prefs.get(this);
        final String apiKey = p.getString(Prefs.KEY_API_KEY, "");
        final String model = p.getString(Prefs.KEY_MODEL, Prefs.DEFAULT_MODEL);
        final String prompt = p.getString(Prefs.KEY_PROMPT, Prefs.DEFAULT_PROMPT);

        postStatus("tłumaczę…");

        try {
            net.execute(new Runnable() {
                @Override
                public void run() {
                    final long t0 = SystemClock.uptimeMillis();
                    final AtomicBoolean first = new AtomicBoolean(true);
                    try {
                        GeminiClient.translateStream(apiKey, model, prompt, tail, pageText,
                                new GeminiClient.StreamListener() {
                                    @Override
                                    public void onChunk(final String chunk) {
                                        final boolean isFirst = first.compareAndSet(true, false);
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                OverlayController o = overlay;
                                                if (o == null) {
                                                    return;
                                                }
                                                if (isFirst) {
                                                    o.beginStream();
                                                }
                                                o.appendStream(chunk);
                                            }
                                        });
                                    }
                                });
                        long ms = SystemClock.uptimeMillis() - t0;
                        postStatus("gotowe • " + (ms / 100) / 10.0 + " s");
                    } catch (Throwable e) {
                        Log.w(TAG, "Tlumaczenie nieudane", e);
                        postResult("Nie udało się przetłumaczyć.\n\n" + e.getMessage(), "błąd");
                        lastTranslatedKey = "";
                        translatedFingerprint = null;
                    } finally {
                        translating.set(false);
                    }
                }
            });
        } catch (Throwable t) {
            translating.set(false);
            lastTranslatedKey = "";
            Log.w(TAG, "Nie udalo sie zlecic tlumaczenia", t);
        }
    }

    // ------------------------------------------------------------------ UI

    private void postWaiting(final String message, final String status) {
        main.post(new Runnable() {
            @Override
            public void run() {
                OverlayController o = overlay;
                if (o != null) {
                    o.showWaiting(message);
                    o.setStatus(status);
                }
            }
        });
    }

    private void postRestore() {
        main.post(new Runnable() {
            @Override
            public void run() {
                OverlayController o = overlay;
                if (o != null) {
                    o.restoreLast();
                    o.setStatus("bez zmian");
                }
            }
        });
    }

    private void postStatus(final String status) {
        main.post(new Runnable() {
            @Override
            public void run() {
                OverlayController o = overlay;
                if (o != null) {
                    o.setStatus(status);
                }
            }
        });
    }

    private void postResult(final String body, final String status) {
        main.post(new Runnable() {
            @Override
            public void run() {
                OverlayController o = overlay;
                if (o != null) {
                    o.setBody(body);
                    o.setStatus(status);
                    o.scheduleBoundsUpdate();
                }
            }
        });
    }

    private void fail(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(CaptureService.this, "ReadLens: " + message,
                        Toast.LENGTH_LONG).show();
            }
        });
        shutdown();
    }

    // -------------------------------------------------------- powiadomienie

    private void startForegroundNotification() {
        NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ReadLens", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        Intent stopIntent = new Intent(this, CaptureService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 2, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("ReadLens dziala")
                .setContentText("Czyta ekran i tlumaczy strone na polski")
                .setSmallIcon(R.drawable.ic_stat)
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat),
                        "Zatrzymaj", stopPi).build())
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---------------------------------------------------------------- stop

    private void releaseCaptureSurfaces() {
        VirtualDisplay vd = virtualDisplay;
        virtualDisplay = null;
        if (vd != null) {
            try {
                vd.release();
            } catch (Throwable ignored) {
                // juz zwolnione
            }
        }
        ImageReader reader = imageReader;
        imageReader = null;
        if (reader != null) {
            try {
                reader.setOnImageAvailableListener(null, null);
            } catch (Throwable ignored) {
                // trudno
            }
            try {
                reader.close();
            } catch (Throwable ignored) {
                // juz zamkniete
            }
        }
        Bitmap stale = orderedFrame;
        orderedFrame = null;
        if (stale != null) {
            stale.recycle();
        }
        synchronized (frameLock) {
            if (retainedFrame != null) {
                retainedFrame.recycle();
                retainedFrame = null;
            }
            if (spareBuffer != null) {
                spareBuffer.recycle();
                spareBuffer = null;
            }
        }
    }

    private void closeRecognizer() {
        TextRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try {
                r.close();
            } catch (Throwable ignored) {
                // juz zamkniete
            }
        }
    }

    private void stopProjection() {
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try {
                p.stop();
            } catch (Throwable ignored) {
                // juz zatrzymane
            }
        }
    }

    private void shutdown() {
        if (!running && worker == null && overlay == null && projection == null) {
            stopForeground(true);
            stopSelf();
            return;
        }
        running = false;

        final OverlayController o = overlay;
        overlay = null;
        if (o != null) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    o.destroy();
                }
            });
        }

        ExecutorService net = network;
        network = null;
        if (net != null) {
            net.shutdownNow();
        }

        final HandlerThread capture = workerThread;
        final Handler w = worker;
        final HandlerThread frames = frameThread;
        workerThread = null;
        worker = null;
        frameThread = null;
        frameHandler = null;

        Runnable teardown = new Runnable() {
            @Override
            public void run() {
                releaseCaptureSurfaces();
                closeRecognizer();
                stopProjection();
                if (frames != null) {
                    frames.quitSafely();
                }
                if (capture != null) {
                    capture.quitSafely();
                }
            }
        };

        if (w != null && capture != null) {
            w.removeCallbacksAndMessages(null);
            w.post(teardown);
        } else {
            teardown.run();
        }

        stopForeground(true);
        stopSelf();
    }
}
