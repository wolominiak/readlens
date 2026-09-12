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

public class CaptureService extends Service {

    static final String ACTION_START = "com.readlens.app.action.START";
    static final String ACTION_STOP = "com.readlens.app.action.STOP";
    static final String EXTRA_RESULT_CODE = "result_code";
    static final String EXTRA_RESULT_DATA = "result_data";

    private static final String TAG = "ReadLens";
    private static final String CHANNEL_ID = "readlens_capture";
    private static final int NOTIFICATION_ID = 4711;

    /** Siatka probek jasnosci uzywana do wykrywania zmiany strony. */
    private static final int GRID_W = 24;
    private static final int GRID_H = 40;
    /** Srednia roznica jasnosci (0-255), powyzej ktorej uznajemy ze obraz sie zmienil. */
    private static final int DIFF_THRESHOLD = 5;
    /** Ile cykli obraz musi byc nieruchomy, zanim uznamy strone za gotowa. */
    private static final int SETTLE_TICKS = 2;
    /** Minimalna liczba liter, zeby uznac ekran za tekst ksiazki. */
    private static final int MIN_LETTERS = 60;

    /** Ile czekac, az kompozytor przerysuje ekran bez nakladki. */
    private static final long HIDE_SETTLE_MS = 120;
    private static final long FRAME_WAIT_MS = 90;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean translating = new AtomicBoolean(false);

    private volatile MediaProjection projection;
    private volatile VirtualDisplay virtualDisplay;
    private volatile ImageReader imageReader;
    private volatile TextRecognizer recognizer;
    private volatile OverlayController overlay;

    private HandlerThread workerThread;
    private volatile Handler worker;
    private volatile ExecutorService network;

    private volatile int screenWidth;
    private volatile int screenHeight;
    private volatile int capWidth;
    private volatile int capHeight;
    private volatile float captureScale = 1f;
    private volatile int capDensity;

    private volatile int intervalMs = Prefs.DEFAULT_INTERVAL;
    private volatile boolean running = false;

    // Stan detekcji - dotykany wylacznie z watku roboczego.
    private int[] lastFingerprint;
    private int[] translatedFingerprint;
    private Rect lastBand;
    private int stableTicks;
    private volatile String lastTranslatedKey = "";

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
                    rebuildVirtualDisplay();
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
                    oc.setStatus("odswiezam...");
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

    private void createVirtualDisplay() {
        MediaProjection p = projection;
        if (p == null) {
            return;
        }
        ImageReader reader = ImageReader.newInstance(capWidth, capHeight,
                PixelFormat.RGBA_8888, 3);
        imageReader = reader;
        virtualDisplay = p.createVirtualDisplay(
                "readlens",
                capWidth, capHeight, capDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                reader.getSurface(),
                null, null);
    }

    private void rebuildVirtualDisplay() {
        releaseCaptureSurfaces();
        createVirtualDisplay();
    }

    private void resetDetection() {
        lastFingerprint = null;
        translatedFingerprint = null;
        lastTranslatedKey = "";
        stableTicks = 0;
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

    /**
     * Tani cykl: probkuje jasnosc prosto z bufora klatki, bez tworzenia bitmapy
     * i bez OCR. Pelny odczyt strony odpala sie dopiero, gdy obraz sie zmienil
     * i zdazyl sie uspokoic.
     */
    private void step() {
        Rect band = freeBand();
        if (lastBand == null || !lastBand.equals(band)) {
            lastBand = band;
            lastFingerprint = null;
            translatedFingerprint = null;
            stableTicks = 0;
        }

        int[] fingerprint = sampleFingerprint(band);
        if (fingerprint == null) {
            return;
        }

        if (lastFingerprint == null || differs(fingerprint, lastFingerprint)) {
            lastFingerprint = fingerprint;
            stableTicks = 0;
            return;
        }

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

        // Odcisk zapisujemy dopiero, gdy strona faktycznie zostala odczytana.
        // Inaczej nieudany zrzut kasowalby szanse na ponowna probe.
        if (readAndTranslatePage()) {
            translatedFingerprint = fingerprint;
        }
    }

    /** Obszar ekranu nie zaslaniany przez nakladke, we wspolrzednych klatki. */
    private Rect freeBand() {
        int w = capWidth;
        int h = capHeight;
        OverlayController o = overlay;
        Rect occ = (o != null) ? o.getOccupiedRect() : new Rect();
        if (occ.isEmpty()) {
            return new Rect(0, 0, w, h);
        }
        float s = captureScale;
        int top = clamp((int) (occ.top * s), 0, h);
        int bottom = clamp((int) (occ.bottom * s), 0, h);

        int above = top;
        int below = h - bottom;
        if (above >= below) {
            return new Rect(0, 0, w, Math.max(8, top));
        }
        return new Rect(0, Math.min(bottom, h - 8), w, h);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** Siatka probek jasnosci z bufora klatki. Bez alokacji bitmapy. */
    private int[] sampleFingerprint(Rect band) {
        ImageReader reader = imageReader;
        if (reader == null) {
            return null;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return null;
            }
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rowStride = plane.getRowStride();
            int pixelStride = plane.getPixelStride();
            int imgW = image.getWidth();
            int imgH = image.getHeight();

            int left = clamp(band.left, 0, imgW - 1);
            int right = clamp(band.right, left + 1, imgW);
            int top = clamp(band.top, 0, imgH - 1);
            int bottom = clamp(band.bottom, top + 1, imgH);

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
        } catch (Throwable t) {
            Log.w(TAG, "Probkowanie nieudane: " + t.getMessage());
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
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

    // ----------------------------------------------- pelny odczyt strony

    /**
     * Chowa nakladke, czeka na PIERWSZA nowa klatke bez niej, rozpoznaje cala
     * strone i wysyla ja do tlumaczenia.
     *
     * VirtualDisplay produkuje klatke tylko wtedy, gdy obraz sie zmieni. Po
     * ustaniu ruchu na ekranie kolejnych klatek nie ma - jedyna, jaka na pewno
     * przyjdzie, to ta wywolana zniknieciem nakladki. Dlatego stare klatki
     * czyscimy PRZED ukryciem panelu, a potem czekamy, az pojawi sie nowa.
     *
     * @return true, jesli strone udalo sie odczytac i nie ma po co ponawiac
     */
    private boolean readAndTranslatePage() {
        drainFrames();

        boolean hidden = setOverlayHidden(true);
        Bitmap bitmap;
        try {
            bitmap = awaitFreshFrame(hidden ? 1200 : 400);
        } finally {
            setOverlayHidden(false);
        }

        if (bitmap == null) {
            return false;
        }

        String pageText;
        try {
            pageText = recognize(bitmap, hidden);
        } finally {
            bitmap.recycle();
        }

        if (pageText == null) {
            return false;
        }
        String key = normalizeKey(pageText);
        if (key.length() < MIN_LETTERS) {
            // za malo tekstu, zeby to byla strona ksiazki - nie ponawiaj
            return true;
        }
        if (key.equals(lastTranslatedKey)) {
            return true;
        }
        lastTranslatedKey = key;
        dispatchTranslation(pageText);
        return true;
    }

    /** Czeka na klatke wyprodukowana po zniknieciu nakladki. */
    private Bitmap awaitFreshFrame(long timeoutMs) {
        SystemClock.sleep(HIDE_SETTLE_MS);
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            Bitmap frame = grabFrame();
            if (frame != null) {
                // jeszcze jedna proba - gdyby kompozytor dosylal poprawke
                SystemClock.sleep(FRAME_WAIT_MS);
                Bitmap better = grabFrame();
                if (better != null) {
                    frame.recycle();
                    return better;
                }
                return frame;
            }
            SystemClock.sleep(40);
        }
        return null;
    }

    /** Zwraca true, jesli udalo sie przelaczyc widocznosc nakladki. */
    private boolean setOverlayHidden(final boolean invisible) {
        final OverlayController o = overlay;
        if (o == null) {
            return false;
        }
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    o.setCaptureMode(invisible);
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            return done.await(700, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Wyrzuca klatki sprzed ukrycia nakladki. */
    private void drainFrames() {
        ImageReader reader = imageReader;
        if (reader == null) {
            return;
        }
        for (int i = 0; i < 5; i++) {
            Image img = null;
            try {
                img = reader.acquireLatestImage();
            } catch (Throwable ignored) {
                return;
            }
            if (img == null) {
                return;
            }
            img.close();
        }
    }

    private Bitmap grabFrame() {
        ImageReader reader = imageReader;
        if (reader == null) {
            return null;
        }
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) {
                return null;
            }
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
        } catch (Throwable t) {
            Log.w(TAG, "Nie udalo sie pobrac klatki: " + t.getMessage());
            return null;
        } finally {
            if (image != null) {
                image.close();
            }
        }
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

    /**
     * Sklada bloki w tekst strony. Prostokat nakladki wycinamy tylko wtedy,
     * gdy nie udalo sie jej ukryc - inaczej zabralibysmy kawal ksiazki.
     */
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

    private void dispatchTranslation(final String pageText) {
        ExecutorService net = network;
        if (net == null || !translating.compareAndSet(false, true)) {
            return;
        }

        final SharedPreferences p = Prefs.get(this);
        final String apiKey = p.getString(Prefs.KEY_API_KEY, "");
        final String model = p.getString(Prefs.KEY_MODEL, Prefs.DEFAULT_MODEL);
        final String prompt = p.getString(Prefs.KEY_PROMPT, Prefs.DEFAULT_PROMPT);

        main.post(new Runnable() {
            @Override
            public void run() {
                OverlayController o = overlay;
                if (o != null) {
                    o.beginStream();
                    o.setStatus("tlumacze...");
                }
            }
        });

        try {
            net.execute(new Runnable() {
                @Override
                public void run() {
                    final long t0 = SystemClock.uptimeMillis();
                    try {
                        GeminiClient.translateStream(apiKey, model, prompt, pageText,
                                new GeminiClient.StreamListener() {
                                    @Override
                                    public void onChunk(final String chunk) {
                                        main.post(new Runnable() {
                                            @Override
                                            public void run() {
                                                OverlayController o = overlay;
                                                if (o != null) {
                                                    o.appendStream(chunk);
                                                }
                                            }
                                        });
                                    }
                                });
                        long ms = SystemClock.uptimeMillis() - t0;
                        postStatus("gotowe • " + (ms / 100) / 10.0 + " s");
                    } catch (Throwable e) {
                        Log.w(TAG, "Tlumaczenie nieudane", e);
                        postResult("Nie udalo sie przetlumaczyc.\n\n" + e.getMessage(), "blad");
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
                reader.close();
            } catch (Throwable ignored) {
                // juz zamkniete
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

        final HandlerThread thread = workerThread;
        final Handler w = worker;
        workerThread = null;
        worker = null;

        if (w != null && thread != null) {
            w.removeCallbacksAndMessages(null);
            w.post(new Runnable() {
                @Override
                public void run() {
                    releaseCaptureSurfaces();
                    closeRecognizer();
                    stopProjection();
                    thread.quitSafely();
                }
            });
        } else {
            releaseCaptureSurfaces();
            closeRecognizer();
            stopProjection();
        }

        stopForeground(true);
        stopSelf();
    }
}
