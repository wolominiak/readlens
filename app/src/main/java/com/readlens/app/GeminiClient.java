package com.readlens.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimalny klient generateContent. Bez zewnetrznych bibliotek - HttpURLConnection + org.json.
 */
final class GeminiClient {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    static class ApiException extends Exception {
        ApiException(String message) {
            super(message);
        }
    }

    /**
     * Wywolanie blokujace. Musi byc uruchomione poza watkiem glownym.
     */
    static String translate(String apiKey, String model, String instruction, String pageText)
            throws ApiException {

        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new ApiException("Brak klucza API. Ustaw go w aplikacji ReadLens.");
        }
        String m = (model == null || model.trim().isEmpty()) ? Prefs.DEFAULT_MODEL : model.trim();

        String body = buildRequest(m, instruction, pageText);

        HttpURLConnection conn = null;
        try {
            URL url = new URL(BASE + m + ":generateContent");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(45000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
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
            String response = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());

            if (code >= 400) {
                throw new ApiException("HTTP " + code + ": " + shortError(response));
            }
            return extractText(response);

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

    private static String buildRequest(String model, String instruction, String pageText) {
        try {
            JSONObject part = new JSONObject();
            part.put("text", instruction + "\n\n--- TEKST STRONY ---\n" + pageText);

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
            // Modele 2.5 maja domyslnie wlaczone "myslenie", ktore zjada budzet tokenow
            // i potrafi zwrocic pusta odpowiedz. Przy tlumaczeniu jest niepotrzebne.
            if (model.contains("2.5")) {
                JSONObject thinking = new JSONObject();
                // gemini-2.5-pro nie przyjmuje zera, minimum to 128
                thinking.put("thinkingBudget", model.contains("pro") ? 128 : 0);
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

    private static String extractText(String response) throws ApiException {
        try {
            JSONObject root = new JSONObject(response);

            if (root.has("error")) {
                JSONObject err = root.getJSONObject("error");
                throw new ApiException(err.optString("message", "nieznany blad API"));
            }

            JSONArray candidates = root.optJSONArray("candidates");
            if (candidates == null || candidates.length() == 0) {
                JSONObject fb = root.optJSONObject("promptFeedback");
                if (fb != null && fb.has("blockReason")) {
                    throw new ApiException("Odpowiedz zablokowana: " + fb.optString("blockReason"));
                }
                throw new ApiException("Pusta odpowiedz modelu.");
            }

            JSONObject cand = candidates.getJSONObject(0);
            StringBuilder sb = new StringBuilder();
            JSONObject content = cand.optJSONObject("content");
            if (content != null) {
                JSONArray parts = content.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        String t = parts.getJSONObject(i).optString("text", "");
                        if (!t.isEmpty()) {
                            sb.append(t);
                        }
                    }
                }
            }

            String out = sb.toString().trim();
            if (out.isEmpty()) {
                String reason = cand.optString("finishReason", "");
                throw new ApiException("Model nie zwrocil tekstu"
                        + (reason.isEmpty() ? "." : " (finishReason=" + reason + ")."));
            }
            if ("MAX_TOKENS".equals(cand.optString("finishReason", ""))) {
                out = out + "\n\n[...] (ucieto - limit tokenow)";
            }
            return out;

        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException("Nie udalo sie odczytac odpowiedzi: " + e.getMessage());
        }
    }

    private static String shortError(String raw) {
        if (raw == null) {
            return "brak tresci";
        }
        try {
            JSONObject root = new JSONObject(raw);
            JSONObject err = root.optJSONObject("error");
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
