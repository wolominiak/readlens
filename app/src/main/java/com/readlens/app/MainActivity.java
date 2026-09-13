package com.readlens.app;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private static final int REQ_PROJECTION = 1001;
    private static final int REQ_OVERLAY = 1002;
    private static final int REQ_NOTIFICATIONS = 1003;

    private EditText apiKeyInput;
    private EditText modelInput;
    private EditText intervalInput;
    private EditText promptInput;
    private TextView statusLine;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Prefs.migrate(this);
        setContentView(buildUi());
        consumeIntentExtras(getIntent());
        loadPrefs();
        refreshStatus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        consumeIntentExtras(intent);
        loadPrefs();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    /**
     * Pozwala ustawic konfiguracje z komputera, zeby nie przepisywac dlugich
     * ciagow na telefonie:
     *
     *   adb shell am start -n com.readlens.app/.MainActivity --es gkey "..."
     *   adb shell am start -n com.readlens.app/.MainActivity --es model "gemini-2.5-flash"
     *
     * Wartosci laduja w prywatnych SharedPreferences aplikacji, nie w kodzie.
     */
    private void consumeIntentExtras(Intent intent) {
        if (intent == null) {
            return;
        }
        SharedPreferences.Editor edit = Prefs.get(this).edit();
        boolean changed = false;

        String secret = intent.getStringExtra("gkey");
        if (secret != null && !secret.trim().isEmpty()) {
            edit.putString(Prefs.KEY_API_KEY, secret.trim());
            changed = true;
        }
        String model = intent.getStringExtra("model");
        if (model != null && !model.trim().isEmpty()) {
            edit.putString(Prefs.KEY_MODEL, model.trim());
            changed = true;
        }
        if (changed) {
            edit.apply();
            toast("Ustawienia przyjete.");
        }
    }

    // ------------------------------------------------------------------ UI

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(28));

        root.addView(heading("ReadLens " + appVersion()));
        root.addView(note("Czyta tekst z ekranu przez OCR i pokazuje polskie tlumaczenie "
                + "calej strony w plywajacym panelu. Na moment zrzutu panel znika, "
                + "zeby nie zaslaniac ksiazki - to normalne mrugniecie."));

        root.addView(label("Klucz Gemini API"));
        apiKeyInput = input(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(apiKeyInput);
        root.addView(note("aistudio.google.com/apikey"));

        root.addView(label("Model"));
        modelInput = input(InputType.TYPE_CLASS_TEXT);
        root.addView(modelInput);
        root.addView(note("gemini-3.8-flash - najlepsza proza. "
                + "gemini-3.5-flash-lite - szybciej i taniej, slabsze niuanse. "
                + "HTTP 404 = zla nazwa modelu."));

        root.addView(label("Co ile sprawdzac ekran (ms)"));
        intervalInput = input(InputType.TYPE_CLASS_NUMBER);
        root.addView(intervalInput);
        root.addView(note("500 to dobry start. To tylko probkowanie jasnosci ekranu, "
                + "wiec jest tanie. OCR rusza dopiero gdy strona sie zmieni."));

        root.addView(label("Instrukcja dla modelu"));
        promptInput = input(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        promptInput.setMinLines(5);
        promptInput.setGravity(Gravity.TOP | Gravity.START);
        root.addView(promptInput);

        root.addView(button("Przywróć domyślną instrukcję", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                promptInput.setText(Prefs.DEFAULT_PROMPT);
                Prefs.get(MainActivity.this).edit()
                        .putString(Prefs.KEY_PROMPT, Prefs.DEFAULT_PROMPT)
                        .putInt(Prefs.KEY_PROMPT_VERSION, Prefs.PROMPT_VERSION)
                        .apply();
                toast("Przywrócono.");
            }
        }));

        root.addView(spacer(dp(18)));

        root.addView(button("1. Zezwol na nakladke", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlayPermission();
            }
        }));
        root.addView(button("2. Start", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                savePrefs();
                startFlow();
            }
        }));
        root.addView(button("Zatrzymaj", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopCapture();
            }
        }));

        root.addView(spacer(dp(14)));
        statusLine = note("");
        root.addView(statusLine);

        root.addView(spacer(dp(14)));
        root.addView(note("Panel: przeciagnij pasek u gory, zeby go przesunac. "
                + "A-/A+ zmienia rozmiar pisma, ⇅ wysokosc panelu, ⟳ wymusza ponowne "
                + "tlumaczenie biezacej strony, – zwija do kulki, ✕ konczy prace."));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    /** Numer wersji z manifestu - zeby bylo widac, ktory build jest zainstalowany. */
    private String appVersion() {
        try {
            return "v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private TextView heading(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, 0, 0, dp(6));
        return tv;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(16), 0, dp(4));
        return tv;
    }

    private TextView note(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setTextColor(0xFF8A8A8E);
        tv.setPadding(0, dp(4), 0, 0);
        return tv;
    }

    private EditText input(int inputType) {
        EditText et = new EditText(this);
        et.setInputType(inputType);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        return et;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private View spacer(int height) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        return v;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------ ustawienia

    private void loadPrefs() {
        SharedPreferences p = Prefs.get(this);
        apiKeyInput.setText(p.getString(Prefs.KEY_API_KEY, ""));
        modelInput.setText(p.getString(Prefs.KEY_MODEL, Prefs.DEFAULT_MODEL));
        intervalInput.setText(String.valueOf(
                p.getInt(Prefs.KEY_INTERVAL, Prefs.DEFAULT_INTERVAL)));
        promptInput.setText(p.getString(Prefs.KEY_PROMPT, Prefs.DEFAULT_PROMPT));
    }

    private void savePrefs() {
        int interval;
        try {
            interval = Integer.parseInt(intervalInput.getText().toString().trim());
        } catch (NumberFormatException e) {
            interval = Prefs.DEFAULT_INTERVAL;
        }
        interval = Math.max(300, Math.min(10000, interval));

        String model = modelInput.getText().toString().trim();
        if (model.isEmpty()) {
            model = Prefs.DEFAULT_MODEL;
        }
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) {
            prompt = Prefs.DEFAULT_PROMPT;
        }

        Prefs.get(this).edit()
                .putString(Prefs.KEY_API_KEY, apiKeyInput.getText().toString().trim())
                .putString(Prefs.KEY_MODEL, model)
                .putInt(Prefs.KEY_INTERVAL, interval)
                .putString(Prefs.KEY_PROMPT, prompt)
                .putInt(Prefs.KEY_PROMPT_VERSION, Prefs.PROMPT_VERSION)
                .apply();
    }

    private void refreshStatus() {
        if (statusLine == null) {
            return;
        }
        boolean overlayOk = Settings.canDrawOverlays(this);
        boolean keyOk = !Prefs.get(this).getString(Prefs.KEY_API_KEY, "").isEmpty();
        statusLine.setText("Nakladka: " + (overlayOk ? "OK" : "BRAK ZGODY")
                + "   •   Klucz API: " + (keyOk ? "ustawiony" : "brak"));
        statusLine.setTextColor(overlayOk && keyOk ? 0xFF3C9A57 : 0xFFB3261E);
    }

    // --------------------------------------------------------------- akcje

    private void requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            toast("Zgoda na nakladke juz jest.");
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, REQ_OVERLAY);
    }

    private void startFlow() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Najpierw zezwol na nakladke.");
            requestOverlayPermission();
            return;
        }
        if (Prefs.get(this).getString(Prefs.KEY_API_KEY, "").isEmpty()) {
            toast("Wpisz klucz Gemini API.");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
            return;
        }
        requestProjection();
    }

    private void requestProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    private void stopCapture() {
        Intent intent = new Intent(this, CaptureService.class);
        intent.setAction(CaptureService.ACTION_STOP);
        startService(intent);
        toast("Zatrzymano.");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS) {
            // Powiadomienie jest wymagane przez system dla uslugi pierwszoplanowej,
            // ale brak zgody na jego pokazanie nie blokuje startu.
            requestProjection();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY) {
            refreshStatus();
            return;
        }

        if (requestCode == REQ_PROJECTION) {
            if (resultCode != RESULT_OK || data == null) {
                toast("Bez zgody na przechwytywanie ekranu nie ruszy.");
                return;
            }
            Intent intent = new Intent(this, CaptureService.class);
            intent.setAction(CaptureService.ACTION_START);
            intent.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
            intent.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
            startForegroundService(intent);
            toast("Dziala. Otworz czytnik.");
            moveTaskToBack(true);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
