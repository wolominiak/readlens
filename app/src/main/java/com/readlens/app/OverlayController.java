package com.readlens.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Plywajace okno z tlumaczeniem. Zyje w watku glownym.
 * getOccupiedRect() jest wolane z watku roboczego - stad synchronizacja.
 */
class OverlayController {

    interface Listener {
        void onRefreshRequested();

        void onStopRequested();
    }

    private static final int[] HEIGHT_PERCENTS = {35, 55, 75};

    private final Context ctx;
    private final WindowManager wm;
    private final Listener listener;
    private final SharedPreferences prefs;

    private LinearLayout panel;
    private TextView statusView;
    private TextView bodyView;
    private ScrollView scrollView;
    private TextView bubble;

    private WindowManager.LayoutParams panelLp;
    private WindowManager.LayoutParams bubbleLp;

    private boolean collapsed = false;
    private boolean attached = false;
    private float fontSp;
    private int heightIdx;
    private int screenW;
    private int screenH;

    private final Rect occupied = new Rect();

    OverlayController(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        this.prefs = Prefs.get(ctx);
        this.fontSp = prefs.getFloat(Prefs.KEY_FONT_SP, Prefs.DEFAULT_FONT_SP);
        this.heightIdx = prefs.getInt(Prefs.KEY_HEIGHT_IDX, 1);
        if (heightIdx < 0 || heightIdx >= HEIGHT_PERCENTS.length) {
            heightIdx = 1;
        }
    }

    // ---------------------------------------------------------------- budowa

    @SuppressLint("ClickableViewAccessibility")
    void show() {
        if (attached) {
            return;
        }
        readScreenSize();

        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF21B1B1F);
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x33FFFFFF);
        panel.setBackground(bg);
        panel.setPadding(dp(10), dp(6), dp(10), dp(10));

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView grip = makeLabel("PL", 15, 0xFF8AB4F8);
        grip.setPadding(dp(4), dp(6), dp(10), dp(6));

        statusView = makeLabel("gotowe", 12, 0xFF9AA0A6);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusView.setLayoutParams(statusLp);
        statusView.setSingleLine(true);
        statusView.setEllipsize(TextUtils.TruncateAt.END);

        header.addView(grip);
        header.addView(statusView);
        header.addView(makeButton("A-", new Runnable() {
            @Override
            public void run() {
                changeFont(-2f);
            }
        }));
        header.addView(makeButton("A+", new Runnable() {
            @Override
            public void run() {
                changeFont(2f);
            }
        }));
        header.addView(makeButton("⇅", new Runnable() {
            @Override
            public void run() {
                cycleHeight();
            }
        }));
        header.addView(makeButton("⟳", new Runnable() {
            @Override
            public void run() {
                listener.onRefreshRequested();
            }
        }));
        header.addView(makeButton("–", new Runnable() {
            @Override
            public void run() {
                collapse();
            }
        }));
        header.addView(makeButton("✕", new Runnable() {
            @Override
            public void run() {
                listener.onStopRequested();
            }
        }));

        header.setOnTouchListener(new DragListener());

        bodyView = new TextView(ctx);
        bodyView.setTextColor(0xFFECECEC);
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
        bodyView.setLineSpacing(dp(3), 1.05f);
        bodyView.setText("Otworz ksiazke w czytniku. Tlumaczenie pojawi sie tutaj po "
                + "rozpoznaniu tekstu strony.");

        scrollView = new ScrollView(ctx);
        scrollView.setVerticalScrollBarEnabled(true);
        scrollView.addView(bodyView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        panel.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        panel.addView(scrollView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        panelLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                panelHeightPx(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;
        panelLp.x = 0;
        panelLp.y = prefs.getInt(Prefs.KEY_PANEL_Y, screenH - panelHeightPx());
        clampPanelY();

        wm.addView(panel, panelLp);
        attached = true;
        scheduleBoundsUpdate();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void ensureBubble() {
        if (bubble != null) {
            return;
        }
        bubble = new TextView(ctx);
        bubble.setText("PL");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        bubble.setGravity(Gravity.CENTER);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(0xF2246BDF);
        bd.setCornerRadius(dp(24));
        bubble.setBackground(bd);
        bubble.setPadding(dp(14), dp(12), dp(14), dp(12));

        bubbleLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        bubbleLp.gravity = Gravity.TOP | Gravity.START;
        bubbleLp.x = dp(12);
        bubbleLp.y = screenH / 2;

        bubble.setOnTouchListener(new View.OnTouchListener() {
            private float downX, downY;
            private int origX, origY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        origX = bubbleLp.x;
                        origY = bubbleLp.y;
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (e.getRawX() - downX);
                        int dy = (int) (e.getRawY() - downY);
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) {
                            moved = true;
                        }
                        bubbleLp.x = origX + dx;
                        bubbleLp.y = origY + dy;
                        wm.updateViewLayout(bubble, bubbleLp);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (!moved) {
                            expand();
                        } else {
                            scheduleBoundsUpdate();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
    }

    // ------------------------------------------------------------ sterowanie

    void setStatus(final String text) {
        if (statusView != null) {
            statusView.setText(text);
        }
    }

    void setBody(final String text) {
        if (bodyView != null) {
            bodyView.setText(text);
            if (scrollView != null) {
                scrollView.scrollTo(0, 0);
            }
        }
        if (collapsed && bubble != null) {
            bubble.setText("PL •");
        }
    }

    private void collapse() {
        if (collapsed || !attached) {
            return;
        }
        ensureBubble();
        try {
            wm.removeView(panel);
        } catch (Exception ignored) {
            // widok mogl juz zniknac
        }
        wm.addView(bubble, bubbleLp);
        collapsed = true;
        scheduleBoundsUpdate();
    }

    private void expand() {
        if (!collapsed) {
            return;
        }
        try {
            wm.removeView(bubble);
        } catch (Exception ignored) {
            // widok mogl juz zniknac
        }
        bubble.setText("PL");
        wm.addView(panel, panelLp);
        collapsed = false;
        scheduleBoundsUpdate();
    }

    private void changeFont(float delta) {
        fontSp = Math.max(10f, Math.min(30f, fontSp + delta));
        bodyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, fontSp);
        prefs.edit().putFloat(Prefs.KEY_FONT_SP, fontSp).apply();
    }

    private void cycleHeight() {
        heightIdx = (heightIdx + 1) % HEIGHT_PERCENTS.length;
        prefs.edit().putInt(Prefs.KEY_HEIGHT_IDX, heightIdx).apply();
        if (!collapsed && attached) {
            panelLp.height = panelHeightPx();
            clampPanelY();
            wm.updateViewLayout(panel, panelLp);
            scheduleBoundsUpdate();
        }
    }

    void destroy() {
        try {
            if (panel != null && !collapsed) {
                wm.removeView(panel);
            }
        } catch (Exception ignored) {
            // widok mogl juz zniknac
        }
        try {
            if (bubble != null && collapsed) {
                wm.removeView(bubble);
            }
        } catch (Exception ignored) {
            // widok mogl juz zniknac
        }
        attached = false;
        synchronized (occupied) {
            occupied.setEmpty();
        }
    }

    void onScreenSizeChanged() {
        readScreenSize();
        if (attached && !collapsed) {
            panelLp.height = panelHeightPx();
            clampPanelY();
            wm.updateViewLayout(panel, panelLp);
        }
        scheduleBoundsUpdate();
    }

    // ------------------------------------------------------------- geometria

    /**
     * Rozmiar podaje serwis, bo liczy go z metryk calego wyswietlacza.
     * getDisplayMetrics() w kontekscie serwisu potrafi zwrocic obszar
     * bez paskow systemowych, co rozjezdza sie ze wspolrzednymi z
     * getLocationOnScreen() uzywanymi do filtrowania bloków OCR.
     */
    void setScreenSize(int width, int height) {
        if (width > 0 && height > 0) {
            screenW = width;
            screenH = height;
        }
    }

    private void readScreenSize() {
        if (screenW > 0 && screenH > 0) {
            return;
        }
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    private int panelHeightPx() {
        return (int) (screenH * (HEIGHT_PERCENTS[heightIdx] / 100f));
    }

    private void clampPanelY() {
        int max = Math.max(0, screenH - panelLp.height);
        if (panelLp.y > max) {
            panelLp.y = max;
        }
        if (panelLp.y < 0) {
            panelLp.y = 0;
        }
    }

    /** Prostokat zajmowany przez nakladke, we wspolrzednych ekranu. */
    Rect getOccupiedRect() {
        synchronized (occupied) {
            return new Rect(occupied);
        }
    }

    void scheduleBoundsUpdate() {
        final View v = collapsed ? bubble : panel;
        if (v == null) {
            return;
        }
        v.post(new Runnable() {
            @Override
            public void run() {
                updateBounds();
            }
        });
    }

    private void updateBounds() {
        View v = collapsed ? bubble : panel;
        if (v == null || !v.isAttachedToWindow() || v.getWidth() == 0) {
            return;
        }
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int pad = dp(6);
        synchronized (occupied) {
            occupied.set(loc[0] - pad, loc[1] - pad,
                    loc[0] + v.getWidth() + pad, loc[1] + v.getHeight() + pad);
        }
    }

    // ----------------------------------------------------------- pomocnicze

    private class DragListener implements View.OnTouchListener {
        private float downY;
        private int origY;

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    origY = panelLp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    panelLp.y = origY + (int) (e.getRawY() - downY);
                    clampPanelY();
                    wm.updateViewLayout(panel, panelLp);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    prefs.edit().putInt(Prefs.KEY_PANEL_Y, panelLp.y).apply();
                    scheduleBoundsUpdate();
                    return true;
                default:
                    return false;
            }
        }
    }

    private TextView makeLabel(String text, int sizeSp, int color) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        return tv;
    }

    private TextView makeButton(String text, final Runnable action) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(0xFFD7D7DB);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(9), dp(7), dp(9), dp(7));
        tv.setClickable(true);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });
        return tv;
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }
}
