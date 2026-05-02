package imagejai.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.IJ;
import imagejai.config.Settings;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Short-lived JSON-over-TCP client for user-triggered rail actions.
 */
public final class TcpHotline {
    private static final String HOST = "localhost";
    private static final int SOCKET_TIMEOUT_MS = 15000;

    private final Settings settings;

    public TcpHotline(Settings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        this.settings = settings;
    }

    public JsonObject executeMacro(String code) throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("command", "execute_macro");
        request.addProperty("code", code);
        request.addProperty("source", "rail:hotline");
        return requireSuccess(send(request));
    }

    public JsonObject getImageInfo() throws IOException {
        JsonObject request = new JsonObject();
        request.addProperty("command", "get_image_info");
        JsonObject response = send(request);
        if (!isSuccess(response)) {
            return null;
        }
        JsonElement result = response.get("result");
        return result != null && result.isJsonObject() ? result.getAsJsonObject() : null;
    }

    public JsonObject send(JsonObject request) throws IOException {
        int port = settings.tcpPort;
        IJ.log("[ImageJAI-Term] TCP hotline " + request.get("command").getAsString()
                + " -> " + HOST + ":" + port);
        try (Socket socket = new Socket(HOST, port);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     socket.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader reader = new BufferedReader(new InputStreamReader(
                     socket.getInputStream(), StandardCharsets.UTF_8))) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            writer.write(request.toString());
            writer.newLine();
            writer.flush();

            String line = reader.readLine();
            if (line == null || line.trim().isEmpty()) {
                throw new IOException("TCP server returned an empty response");
            }

            JsonElement parsed = JsonParser.parseString(line);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new IOException("TCP server returned invalid JSON");
            }
            return parsed.getAsJsonObject();
        } catch (ConnectException e) {
            throw new IOException("TCP server is not reachable on port " + port
                    + ". Enable the TCP server in Settings.", e);
        } catch (SocketTimeoutException e) {
            throw new IOException("TCP server timed out on port " + port + ".", e);
        } catch (IllegalStateException e) {
            throw new IOException("TCP server returned invalid JSON", e);
        }
    }

    public static boolean isSuccess(JsonObject response) {
        JsonElement success = response != null ? response.get("success") : null;
        return success != null && success.isJsonPrimitive() && success.getAsBoolean();
    }

    public static String errorMessage(JsonObject response) {
        if (response == null) {
            return "No response from TCP server";
        }
        JsonElement error = response.get("error");
        if (error == null || error.isJsonNull()) {
            return "TCP command failed";
        }
        if (error.isJsonObject()) {
            JsonObject obj = error.getAsJsonObject();
            JsonElement message = obj.get("message");
            if (message != null && message.isJsonPrimitive()) {
                return message.getAsString();
            }
        }
        if (error.isJsonPrimitive()) {
            return error.getAsString();
        }
        return error.toString();
    }

    private JsonObject requireSuccess(JsonObject response) throws IOException {
        if (isSuccess(response)) {
            return response;
        }
        throw new IOException(errorMessage(response));
    }
}
