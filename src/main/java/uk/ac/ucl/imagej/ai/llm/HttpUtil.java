package uk.ac.ucl.imagej.ai.llm;

import uk.ac.ucl.imagej.ai.config.Constants;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Shared HTTP utility for LLM backends.
 * Uses java.net.HttpURLConnection exclusively (no external dependencies).
 */
public final class HttpUtil {

    private HttpUtil() {}

    /**
     * Perform an HTTP POST with a JSON body.
     *
     * @param url      target URL
     * @param jsonBody JSON request body
     * @param headers  additional headers (may be null)
     * @return response body as string
     * @throws IOException on connection or HTTP errors
     */
    public static String post(String url, String jsonBody, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = openConnection(url, headers);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);

        byte[] bodyBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

        OutputStream os = conn.getOutputStream();
        try {
            os.write(bodyBytes);
            os.flush();
        } finally {
            os.close();
        }

        return readResponse(conn);
    }

    /**
     * Perform an HTTP GET.
     *
     * @param url     target URL
     * @param headers additional headers (may be null)
     * @return response body as string
     * @throws IOException on connection or HTTP errors
     */
    public static String get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = openConnection(url, headers);
        conn.setRequestMethod("GET");
        return readResponse(conn);
    }

    /**
     * Encode image bytes to Base64 string.
     */
    public static String encodeBase64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    // --- Internal helpers ---

    private static HttpURLConnection openConnection(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(Constants.HTTP_CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(Constants.HTTP_READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "application/json");

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                conn.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }
        return conn;
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream is;
        if (status >= 200 && status < 300) {
            is = conn.getInputStream();
        } else {
            is = conn.getErrorStream();
            if (is == null) {
                throw new IOException("HTTP " + status + " (no response body)");
            }
        }

        String body = readStream(is);

        if (status < 200 || status >= 300) {
            throw new IOException("HTTP " + status + ": " + body);
        }
        return body;
    }

    private static String readStream(InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } finally {
            reader.close();
        }
    }
}
