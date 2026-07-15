package imagejai.engine.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.measure.ResultsTable;
import ij.process.ByteProcessor;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.CommandEngine;
import imagejai.engine.ExecutionResult;
import imagejai.engine.ExplorationEngine;
import imagejai.engine.PipelineBuilder;
import imagejai.engine.PostureController;
import imagejai.engine.StateInspector;
import imagejai.engine.TCPCommandServer;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end outbound-string proof for stage 08.
 *
 * <p>Run with {@code mvn test -Pintegration}. The default {@code mvn test}
 * run excludes {@code *IntegrationTest} classes so this socket-level sweep does
 * not slow down the unit-test cycle.
 */
public class PseudonymisationIntegrationTest {
    private static final String SENSITIVE_ID = "MOAB2_subject_017_visit3";
    private static final String OME_PHI = "Jane Donor subject_017 visit3";
    private static final String RESULT_LABEL = "Stage08LabelCellAlpha";

    @Test
    public void everyTcpCommandResponseCarriesGovernanceAndNoOriginalStrings()
            throws Exception {
        Path root = Files.createTempDirectory("imagejai-stage08-integration");
        Path sensitiveFolder = root.resolve("MOAB2").resolve("subject_017_visit3");
        Files.createDirectories(sensitiveFolder);
        Path rawImagePath = sensitiveFolder.resolve(SENSITIVE_ID + ".lif");
        Files.createFile(rawImagePath);
        Path csv = sensitiveFolder.resolve("AI_Exports").resolve(AuditLog.FILE_NAME);
        String originalUserDir = System.getProperty("user.dir");

        String resultLabelToken = registerSensitive(rawImagePath, sensitiveFolder);
        ImagePlus image = syntheticImage(rawImagePath);
        Settings settings = new Settings();
        settings.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
        PostureController.getInstance().configure(settings);
        WindowManager.setTempCurrentImage(image);
        System.setProperty("user.dir", sensitiveFolder.toString());
        IJ.log("registered path " + rawImagePath.toString());
        seedResultsTable();
        assertTrue("integration fixture did not seed a Label value",
                new StateInspector().getResultsTableCSV().contains(RESULT_LABEL));

        CommandEngine engine = new StubCommandEngine();
        TCPCommandServer server = new TCPCommandServer(0, engine,
                new StateInspector(), new PipelineBuilder(engine),
                new StubExplorationEngine(engine));
        setTempLedger(server, root.resolve("ledger.json"));

        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger boundPort = new AtomicInteger(-1);
        server.start(new TCPCommandServer.ServerListener() {
            @Override
            public void onServerStarted(int port) {
                boundPort.set(port);
                started.countDown();
            }

            @Override
            public void onServerStopped() {
            }

            @Override
            public void onClientConnected(String clientInfo) {
            }

            @Override
            public void onCommandReceived(String command) {
            }

            @Override
            public void onError(String error) {
                started.countDown();
            }
        });

        try {
            assertTrue("server did not start",
                    started.await(10, TimeUnit.SECONDS));
            assertTrue("server did not publish an ephemeral port",
                    boundPort.get() > 0);

            List<String> rawResponses = new ArrayList<String>();
            for (String command : TCPCommandServer.knownCommands()) {
                String raw;
                try {
                    raw = send(boundPort.get(), requestFor(command,
                            rawImagePath, sensitiveFolder));
                } catch (Exception e) {
                    throw new AssertionError(
                            "TCP command timed out or failed: " + command, e);
                }
                rawResponses.add(raw);
                JsonObject response = new JsonParser().parse(raw).getAsJsonObject();
                assertTrue(command + " missing _governance in " + raw,
                        response.has("_governance"));
                assertEquals("Pseudonymised",
                        response.getAsJsonObject("_governance")
                                .get("posture").getAsString());
                assertNoOriginals(command, raw, rawImagePath, sensitiveFolder);
            }

            AuditLog.getInstance().flushForTest();
            assertTrue("audit CSV was not created at " + csv, Files.exists(csv));
            List<AuditRow> rows = auditRows(csv);
            assertEquals(TCPCommandServer.knownCommands().size(), rows.size());
            for (AuditRow row : rows) {
                assertEquals(PrivacyPosture.PSEUDONYMISED, row.posture());
                assertTrue(row.command() + " did not mark pseudonymisation in force",
                        row.redactionApplied());
                assertNoOriginals(row.command(), row.toCsvLine(), rawImagePath,
                        sensitiveFolder);
                assertNoOriginals(row.command(), row.redactedPayloadJson(), rawImagePath,
                        sensitiveFolder);
            }

            assertEquals("socket response count", TCPCommandServer.knownCommands().size(),
                    rawResponses.size());
            assertTrue("socket proof never exercised Label redaction",
                    rawResponses.toString().contains(resultLabelToken));
        } finally {
            server.stop();
            WindowManager.setTempCurrentImage(null);
            ResultsTable.getResultsTable().reset();
            if (originalUserDir != null) {
                System.setProperty("user.dir", originalUserDir);
            }
            Settings reset = new Settings();
            reset.setPrivacyPosture(PrivacyPosture.PSEUDONYMISED);
            PostureController.getInstance().configure(reset);
        }
    }

    private static JsonObject requestFor(String command, Path rawImagePath,
                                         Path sensitiveFolder) {
        JsonObject request = new JsonObject();
        request.addProperty("command", command);
        request.addProperty("session_id", "stage08-session");
        request.addProperty("model_endpoint", "openai.codex");
        request.addProperty("identifiable_input", SENSITIVE_ID);
        request.addProperty("path_hint", rawImagePath.toString());
        if ("hello".equals(command)) {
            request.addProperty("agent", "stage08");
            JsonObject caps = new JsonObject();
            caps.addProperty("session_id", "stage08-session");
            caps.addProperty("model_endpoint", "openai.codex");
            request.add("capabilities", caps);
        } else if ("capture_image".equals(command)) {
            request.addProperty("source", "dialog_screenshot");
            request.addProperty("maxSize", 64);
        } else if ("request_visual".equals(command)) {
            request.addProperty("reason", "inspect " + rawImagePath.toString());
        } else if ("browse_pending_brief".equals(command)) {
            enqueueStage08Brief(rawImagePath);
        } else if ("get_pending_brief".equals(command)) {
            if (!SelectionBroker.getInstance().hasPending("stage08-session")) {
                enqueueStage08Brief(rawImagePath);
            }
        } else if ("3d_viewer".equals(command)) {
            request.addProperty("action", "status");
        } else if ("interact_dialog".equals(command)) {
            request.addProperty("action", "list_components");
        } else if ("job_status".equals(command) || "job_cancel".equals(command)) {
            request.addProperty("job_id", SENSITIVE_ID);
        } else if ("reactive_enable".equals(command)
                || "reactive_disable".equals(command)) {
            request.addProperty("name", SENSITIVE_ID);
        } else if ("ledger_lookup".equals(command)) {
            request.addProperty("error_fragment", SENSITIVE_ID);
            request.addProperty("macro_prefix", "open " + rawImagePath.toString());
        } else if ("ledger_confirm".equals(command)) {
            request.addProperty("fingerprint", SENSITIVE_ID);
            request.addProperty("fix", "Use local token for " + rawImagePath);
            request.addProperty("worked", true);
        } else if ("rewind".equals(command)) {
            request.addProperty("to_call_id", SENSITIVE_ID);
        } else if ("branch".equals(command)) {
            request.addProperty("from_call_id", SENSITIVE_ID);
            request.addProperty("name", SENSITIVE_ID);
        } else if ("branch_switch".equals(command)
                || "branch_delete".equals(command)) {
            request.addProperty("branch_id", SENSITIVE_ID);
        } else if ("intent".equals(command)) {
            request.addProperty("text", "analyse " + sensitiveFolder.toString());
        } else if ("intent_teach".equals(command)) {
            request.addProperty("phrase", SENSITIVE_ID);
            request.addProperty("intent_id", "image.open");
        } else if ("intent_forget".equals(command)) {
            request.addProperty("phrase", SENSITIVE_ID);
        } else if ("gui_action".equals(command)) {
            request.addProperty("action", "noop");
        }
        return request;
    }

    private static void enqueueStage08Brief(Path rawImagePath) {
        java.util.Map<String, Object> metadata = new java.util.LinkedHashMap<String, Object>();
        metadata.put("channels", 1);
        metadata.put("dimensions", "64x64");
        SelectionBroker.getInstance().enqueue(new Brief("stage08-session",
                java.util.Collections.singletonList(
                        PathTokenMap.getInstance().tokenForSeries(rawImagePath, 1)),
                "stage08 brief", metadata));
    }

    private static String send(int port, JsonObject request) throws Exception {
        try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
            socket.setSoTimeout((int) Duration.ofSeconds(30).toMillis());
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
            writer.println(request.toString());
            String line = reader.readLine();
            assertTrue("empty socket response for " + request, line != null);
            return line;
        }
    }

    private static String registerSensitive(Path rawImagePath, Path sensitiveFolder) {
        PathTokenMap map = PathTokenMap.getInstance();
        map.tokenForPathString(rawImagePath.toString());
        map.tokenForPathString(sensitiveFolder.toString());
        map.tokenForSensitiveText(SENSITIVE_ID, "label");
        map.tokenForSensitiveText(OME_PHI, "ome");
        return map.tokenForSensitiveText(RESULT_LABEL, "label");
    }

    private static void seedResultsTable() {
        ResultsTable table = ResultsTable.getResultsTable();
        table.reset();
        table.incrementCounter();
        table.addValue("Label", RESULT_LABEL);
        table.addValue("Area", 42.0);
    }

    private static ImagePlus syntheticImage(Path rawImagePath) {
        byte[] pixels = new byte[64 * 64];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (byte) (i % 255);
        }
        ImagePlus image = new ImagePlus(SENSITIVE_ID + ".lif",
                new ByteProcessor(64, 64, pixels, null));
        FileInfo info = new FileInfo();
        info.directory = rawImagePath.getParent().toString() + File.separator;
        info.fileName = rawImagePath.getFileName().toString();
        image.setFileInfo(info);
        image.setProperty("Info",
                "<OME><Experimenter>" + OME_PHI + "</Experimenter>"
                        + "<Description>MOAB2 subject_017 visit3</Description>"
                        + "<Pixels SizeX=\"64\" SizeY=\"64\"/></OME>");
        return image;
    }

    private static void setTempLedger(TCPCommandServer server, Path ledgerPath)
            throws Exception {
        Class<?> ledgerStoreClass = Class.forName("imagejai.engine.LedgerStore");
        Constructor<?> ctor = ledgerStoreClass.getDeclaredConstructor(Path.class);
        ctor.setAccessible(true);
        Object store = ctor.newInstance(ledgerPath);
        Method setter = TCPCommandServer.class.getDeclaredMethod("setLedgerStore",
                ledgerStoreClass);
        setter.setAccessible(true);
        setter.invoke(server, store);
    }

    private static List<AuditRow> auditRows(Path csv) throws Exception {
        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        List<AuditRow> rows = new ArrayList<AuditRow>();
        for (String line : lines) {
            if (line == null || line.trim().isEmpty()
                    || AuditLog.HEADER.equals(line.trim())) {
                continue;
            }
            rows.add(AuditRow.fromCsvLine(line));
        }
        return rows;
    }

    private static void assertNoOriginals(String label, String text,
                                          Path rawImagePath,
                                          Path sensitiveFolder) {
        String out = text == null ? "" : text;
        assertFalse(label + " leaked MOAB2 in " + out, out.contains("MOAB2"));
        assertFalse(label + " leaked subject_017 in " + out,
                out.contains("subject_017"));
        assertFalse(label + " leaked visit3 in " + out, out.contains("visit3"));
        assertFalse(label + " leaked OME PHI in " + out, out.contains(OME_PHI));
        assertFalse(label + " leaked Label value in " + out,
                out.contains(RESULT_LABEL));
        assertFalse(label + " leaked absolute image path in " + out,
                out.contains(rawImagePath.toString()));
        assertFalse(label + " leaked absolute parent path in " + out,
                out.contains(sensitiveFolder.toString()));
    }

    private static final class StubCommandEngine extends CommandEngine {
        @Override
        public ExecutionResult executeMacro(String macroCode) {
            return ExecutionResult.success("stub", "", Collections.<String>emptyList(), 0);
        }

        @Override
        public ExecutionResult executeMacroWithTimeout(String macroCode,
                                                       long timeoutMs) {
            return executeMacro(macroCode);
        }

        @Override
        public ExecutionResult executeMacroOnCurrentThread(String code,
                java.util.function.DoubleConsumer progressCallback) {
            return executeMacro(code);
        }
    }

    private static final class StubExplorationEngine extends ExplorationEngine {
        StubExplorationEngine(CommandEngine commandEngine) {
            super(commandEngine);
        }

        @Override
        public ExplorationReport exploreThresholds(String[] methods) {
            ExplorationReport report = new ExplorationReport();
            report.results = Collections.emptyList();
            report.reasoning = "stage08 integration stub for " + SENSITIVE_ID;
            return report;
        }
    }
}
