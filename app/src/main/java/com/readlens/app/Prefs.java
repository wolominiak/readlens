package com.readlens.app;

import android.content.Context;
import android.content.SharedPreferences;

final class Prefs {

    static final String NAME = "readlens";

    static final String KEY_API_KEY = "api_key";
    static final String KEY_MODEL = "model";
    static final String KEY_INTERVAL = "interval_ms";
    static final String KEY_PROMPT = "prompt";
    static final String KEY_PROMPT_VERSION = "prompt_version";
    static final String KEY_FONT_SP = "font_sp";
    static final String KEY_HEIGHT_IDX = "height_idx";
    static final String KEY_PANEL_Y = "panel_y";

    static final String DEFAULT_MODEL = "gemini-3.8-flash";
    static final int DEFAULT_INTERVAL = 500;
    static final float DEFAULT_FONT_SP = 16f;

    /**
     * Podbijane, gdy domyslna instrukcja sie zmienia. Zapisana u uzytkownika
     * starsza wersja jest wtedy podmieniana - inaczej poprawki promptu nigdy
     * by do niego nie dotarly.
     */
    static final int PROMPT_VERSION = 2;

    static final String DEFAULT_PROMPT =
            "Jestes tlumaczem literackim EN->PL. Dostajesz surowy tekst OCR z jednej strony "
          + "angielskiej ksiazki wyswietlonej w czytniku na telefonie. Tekst moze zawierac bledy "
          + "rozpoznawania, urwane slowa, numer strony, zywa pagine oraz elementy interfejsu "
          + "aplikacji.\n\n"
          + "Zwroc naturalne polskie tlumaczenie tresci, z zachowaniem podzialu na akapity. "
          + "Popraw w duchu oryginalu oczywiste bledy OCR. Pomin numery stron, naglowki "
          + "powtarzane u gory i dolu strony oraz elementy interfejsu czytnika.\n\n"
          + "Nie dodawaj zadnych komentarzy, wstepow ani wyjasnien. Zwroc wylacznie tlumaczenie.";

    static SharedPreferences get(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** Podmienia zapisana instrukcje, jesli pochodzi ze starszej wersji aplikacji. */
    static void migrate(Context c) {
        SharedPreferences p = get(c);
        if (p.getInt(KEY_PROMPT_VERSION, 1) != PROMPT_VERSION) {
            p.edit()
                    .putString(KEY_PROMPT, DEFAULT_PROMPT)
                    .putInt(KEY_PROMPT_VERSION, PROMPT_VERSION)
                    .apply();
        }
    }

    private Prefs() {
    }
}
