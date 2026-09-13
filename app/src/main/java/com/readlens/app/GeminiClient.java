package com.readlens.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Klient generateContent. Bez zewnetrznych bibliotek - HttpURLConnection + org.json.
 * Tlumaczenie leci strumieniowo (SSE), zeby tekst pojawial sie w panelu od razu,
 * a nie dopiero po wygenerowaniu calej strony.
 */
final class GeminiClient {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    interface StreamListener {
        /** Kolejny kawalek tekstu. Wolane z watku sieciowego. */
        void onChunk(String text);
    }

    static class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }

    /**
     * Wywolanie blokujace. Musi byc uruchomione poza watkiem glownym.
     * Zwraca pelny tekst, a po drodze karmi listenera kawalkami.
     */
    static String translateStream(String apiKey, String model, String instruction,
                                  String previousTail, String pageText,
                                  StreamListener listener)
            throws ApiException {

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException("Brak klucza API. Wpisz go w aplikacji ReadLens.");
        }
        String m = (model == null || model.trim().isEmpty()) ? Prefs.DEFAULT_MODEL : model.trim();
        String body = buildRequest(m, instruction, previousTail, pageText);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE + m + ":streamGenerateContent?alt=sse");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(45000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("x-goog-api-key", apiKey.trim());

            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            try {
                os.write(payload);
                os.flush();
            } finally {
                closeQuietly(os);
            }

            int code = conn.getResponseCode();
            if (code >= 400) {
                String err = readAll(conn.getErrorStream());
                throw new ApiException("HTTP " + code + ": " + shortError(err));
            }

            return consumeSse(conn.getInputStream(), listener);

        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException("Blad sieci: " + e.getMessage());
        } catch (Exception e) {
            throw new ApiException("Blad: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String consumeSse(InputStream in, StreamListener listener)
            throws IOException, ApiException {

        StringBuilder all = new StringBuilder();
        String blockReason = null;
        String finishReason = "";

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 8192);
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String json = line.substring(5).trim();
                if (json.isEmpty() || "[DONE]".equals(json)) {
                    continue;
                }
                try {
                    JSONObject root = new JSONObject(json);

                    if (root.has("error")) {
                        throw new ApiException(root.getJSONObject("error")
                                .optString("message", "nieznany blad API"));
                    }
                    JSONObject fb = root.optJSONObject("promptFeedback");
                    if (fb != null && fb.has("blockReason")) {
                        blockReason = fb.optString("blockReason");
                    }

                    JSONArray candidates = root.optJSONArray("candidates");
                    if (candidates == null || candidates.length() == 0) {
                        continue;
                    }
                    JSONObject cand = candidates.getJSONObject(0);
                    String fr = cand.optString("finishReason", "");
                    if (!fr.isEmpty()) {
                        finishReason = fr;
                    }

                    JSONObject content = cand.optJSONObject("content");
                    if (content == null) {
                        continue;
                    }
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts == null) {
                        continue;
                    }
                    StringBuilder chunk = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.getJSONObject(i);
                        // pomin bloki "myslenia", gdyby model je zwrocil
                        if (part.optBoolean("thought", false)) {
                            continue;
                        }
                        chunk.append(part.optString("text", ""));
                    }
                    if (chunk.length() > 0) {
                        all.append(chunk);
                        if (listener != null) {
                            listener.onChunk(chunk.toString());
                        }
                    }
                } catch (ApiException e) {
                    throw e;
                } catch (Exception ignored) {
                    // uszkodzona linia SSE - lecimy dalej
                }
            }
        } finally {
            closeQuietly(reader);
        }

        String out = all.toString().trim();
        if (out.isEmpty()) {
            if (blockReason != null) {
                throw new ApiException("Odpowiedz zablokowana: " + blockReason);
            }
            throw new ApiException("Model nie zwrocil tekstu"
                    + (finishReason.isEmpty() ? "." : " (" + finishReason + ")."));
        }
        return out;
    }

    /**
     * Zasada ciaglosci siedzi w kodzie, a nie w edytowalnej instrukcji - dzieki
     * temu dziala niezaleznie od tego, co uzytkownik wpisze w swoim promcie.
     */
    private static final String CONTINUITY_RULE =
            "--- ZASADA CIAGLOSCI ---\n"
          + "Jesli pierwsze zdanie strony jest dalszym ciagiem zdania urwanego na "
          + "poprzedniej stronie, zloz je z kontekstem i oddaj po polsku jako jedno, "
          + "kompletne, sensowne zdanie. Poprzedz je wielokropkiem. Poza tym jednym "
          + "zdaniem nie tlumacz kontekstu.\n"
          + "Jesli strona urywa sie w polowie zdania, przetlumacz fragment i zakoncz "
          + "wielokropkiem. Nie zgaduj dalszego ciagu.";

    private static String buildRequest(String model, String instruction,
                                       String previousTail, String pageText) {
        try {
            StringBuilder text = new StringBuilder();
            text.append(instruction).append("\n\n");
            if (previousTail != null && !previousTail.trim().isEmpty()) {
                text.append("--- KONIEC POPRZEDNIEJ STRONY (tylko kontekst, nie tlumacz) ---\n")
                        .append(previousTail.trim()).append("\n\n");
            }
            text.append("--- TEKST STRONY DO PRZETLUMACZENIA ---\n")
                    .append(pageText).append("\n\n");
            text.append(CONTINUITY_RULE);

            JSONObject part = new JSONObject();
            part.put("text", text.toString());

            JSONArray parts = new JSONArray();
            parts.put(part);

            JSONObject content = new JSONObject();
            content.put("role", "user");
            content.put("parts", parts);

            JSONArray contents = new JSONArray();
            contents.put(content);

            JSONObject cfg = new JSONObject();
            cfg.put("temperature", 0.3);
            cfg.put("maxOutputTokens", 8192);

            // "Myslenie" opoznia pierwszy token i zjada budzet wyjscia.
            // Przy tlumaczeniu jest zbedne, ale kazda generacja modeli
            // wylacza je inaczej.
            JSONObject thinking = new JSONObject();
            if (model.contains("2.5")) {
                // stary parametr: budzet w tokenach, pro nie przyjmuje zera
                thinking.put("thinkingBudget", model.contains("pro") ? 128 : 0);
                cfg.put("thinkingConfig", thinking);
            } else if (model.startsWith("gemini-3")) {
                // od trojki: poziom zamiast budzetu, domyslnie "high"
                thinking.put("thinkingLevel", "low");
                cfg.put("thinkingConfig", thinking);
            }

            JSONObject root = new JSONObject();
            root.put("contents", contents);
            root.put("generationConfig", cfg);
            return root.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String shortError(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "brak tresci";
        }
        try {
            JSONObject err = new JSONObject(raw).optJSONObject("error");
            if (err != null) {
                return err.optString("message", raw);
            }
        } catch (Exception ignored) {
            // nie JSON - pokaz surowo
        }
        return raw.length() > 300 ? raw.substring(0, 300) + "..." : raw;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
        } finally {
            closeQuietly(in);
        }
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
                // celowo puste
            }
        }
    }

    private GeminiClient() {
    }
}
