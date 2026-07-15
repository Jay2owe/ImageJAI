package imagejai.engine;

import com.google.gson.JsonObject;
import ij.ImagePlus;
import ij.io.FileInfo;
import imagejai.config.FolderPostureStore;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import imagejai.engine.security.PathTokenMap;
import imagejai.engine.security.PseudonymisationFilter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Session source of truth for the current Data Governance Privacy Posture.
 */
public final class PostureController {

    public interface Listener {
        void postureChanged(PrivacyPosture from, PrivacyPosture to, Path folder);

        default void postureEvent(String eventType, PrivacyPosture from,
                                  PrivacyPosture to, Path folder, String reason) {
        }
    }

    public interface Presenter {
        void showFolderPosturePrompt(Path folder, PrivacyPosture defaultPosture,
                                     PostureController controller);

        void showDownshift(Path folder, PrivacyPosture from, PrivacyPosture to,
                           PostureController controller);

        void showWarning(Path folder, String message);
    }

    private static final Presenter NO_OP_PRESENTER = new Presenter() {
        @Override
        public void showFolderPosturePrompt(Path folder, PrivacyPosture defaultPosture,
                                            PostureController controller) {
        }

        @Override
        public void showDownshift(Path folder, PrivacyPosture from, PrivacyPosture to,
                                  PostureController controller) {
        }

        @Override
        public void showWarning(Path folder, String message) {
        }
    };

    private static final PostureController INSTANCE = new PostureController();

    private final CopyOnWriteArrayList<Listener> listeners =
            new CopyOnWriteArrayList<Listener>();
    private final Map<Path, Long> consultedByFolderSecond = new HashMap<Path, Long>();

    private Settings settings = new Settings();
    private FolderPostureStore store = new FolderPostureStore();
    private EventBus eventBus = EventBus.getInstance();
    private Presenter presenter = NO_OP_PRESENTER;
    private Path activeFolder;

    public PostureController() {
    }

    public PostureController(Settings settings, FolderPostureStore store, EventBus eventBus) {
        configure(settings, store, eventBus);
    }

    public static PostureController getInstance() {
        return INSTANCE;
    }

    public synchronized void configure(Settings settings) {
        configure(settings, new FolderPostureStore(), EventBus.getInstance());
    }

    public synchronized void configure(Settings settings, FolderPostureStore store,
                                       EventBus eventBus) {
        this.settings = settings == null ? new Settings() : settings;
        this.store = store == null ? new FolderPostureStore() : store;
        this.eventBus = eventBus == null ? EventBus.getInstance() : eventBus;
        this.activeFolder = null;
        this.settings.setPrivacyPosture(this.settings.getPrivacyPosture());
    }

    public synchronized void setPresenter(Presenter presenter) {
        this.presenter = presenter == null ? NO_OP_PRESENTER : presenter;
    }

    public void addListener(Listener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Listener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public PrivacyPosture current() {
        Settings s;
        synchronized (this) {
            s = settings;
        }
        return s.getPrivacyPosture();
    }

    public void onImageOpened(ImagePlus image) {
        Path folder = folderOf(image);
        if (folder != null) {
            onFolderOpened(folder);
        }
    }

    public void onFolderOpened(Path folder) {
        Path normalised = normaliseFolder(folder);
        if (normalised == null) {
            return;
        }
        synchronized (this) {
            activeFolder = normalised;
        }
        if (isDebounced(normalised)) {
            return;
        }

        Optional<FolderPostureStore.Record> record;
        try {
            record = store().read(normalised);
        } catch (IOException e) {
            String message = "Could not read Privacy Posture for "
                    + displayName(normalised) + "; using Pseudonymised for this session.";
            applyDefaultForMissingOrUnreadable(normalised, message);
            return;
        }

        if (record.isPresent()) {
            applyStoredPosture(normalised, record.get().posture());
        } else {
            applyDefaultForMissingOrUnreadable(normalised,
                    "Choose a Privacy Posture for this folder.");
            presenter().showFolderPosturePrompt(normalised,
                    PrivacyPosture.defaultPosture(), this);
        }
    }

    public void requestPosture(PrivacyPosture posture, Path folder, String reason) {
        PrivacyPosture target = posture == null
                ? PrivacyPosture.defaultPosture()
                : posture;
        Path normalised = normaliseFolder(folder);
        if (normalised == null) {
            synchronized (this) {
                normalised = activeFolder;
            }
        }
        PrivacyPosture from = current();
        boolean weakens = from != null && from.isStricterThan(target);

        setCurrent(target, normalised, "data_governance.posture.requested", reason);

        if (normalised != null) {
            try {
                // Always replace the sidecar atomically. Keeping an existing
                // record left stale policy behind after a settings change.
                store().write(normalised, target, "user", reason == null ? "" : reason);
                publish("data_governance.posture.folder_saved",
                        from, target, normalised, reason);
                notifyEvent("data_governance.posture.folder_saved",
                        from, target, normalised, reason);
            } catch (IOException e) {
                warn(normalised, "Could not write " + FolderPostureStore.FILE_NAME
                        + "; Privacy Posture is in-memory only for this session.");
            }
        }

        if (weakens) {
            publish("data_governance.posture.override_logged",
                    from, target, normalised, reason);
            notifyEvent("data_governance.posture.override_logged",
                    from, target, normalised, reason);
            auditPostureEvent("posture.override", from, target, normalised, reason);
        }
    }

    /** Remove a folder decision when the user revokes it. */
    public void revokeFolderPosture(Path folder, String reason) {
        Path normalised = normaliseFolder(folder);
        if (normalised == null) {
            synchronized (this) {
                normalised = activeFolder;
            }
        }
        if (normalised == null) {
            return;
        }
        try {
            Files.deleteIfExists(store().postureFile(normalised));
            setCurrent(PrivacyPosture.defaultPosture(), normalised,
                    "data_governance.posture.folder_revoked", reason);
        } catch (IOException e) {
            warn(normalised, "Could not revoke " + FolderPostureStore.FILE_NAME + ".");
        }
    }

    private void applyStoredPosture(Path folder, PrivacyPosture folderPosture) {
        PrivacyPosture target = folderPosture == null
                ? PrivacyPosture.defaultPosture()
                : folderPosture;
        PrivacyPosture from = current();
        if (target.isStricterThan(from)) {
            setCurrent(target, folder, "data_governance.posture.downshifted",
                    "Loaded stricter folder Privacy Posture");
            presenter().showDownshift(folder, from, target, this);
            return;
        }
        if (from != null && from.isStricterThan(target)) {
            publish("data_governance.posture.upshift_refused",
                    from, target, folder, "Never auto-upshift between folders");
            notifyEvent("data_governance.posture.upshift_refused",
                    from, target, folder, "Never auto-upshift between folders");
            return;
        }
        publish("data_governance.posture.loaded", from, target, folder, "");
        notifyEvent("data_governance.posture.loaded", from, target, folder, "");
    }

    private void applyDefaultForMissingOrUnreadable(Path folder, String message) {
        PrivacyPosture target = PrivacyPosture.defaultPosture();
        PrivacyPosture from = current();
        if (target.isStricterThan(from)) {
            setCurrent(target, folder, "data_governance.posture.defaulted", message);
        } else {
            publish("data_governance.posture.selection_needed",
                    from, target, folder, message);
            notifyEvent("data_governance.posture.selection_needed",
                    from, target, folder, message);
        }
        if (message != null && message.startsWith("Could not read")) {
            warn(folder, message);
        }
    }

    private void setCurrent(PrivacyPosture target, Path folder,
                            String eventType, String reason) {
        PrivacyPosture from;
        boolean changed;
        synchronized (this) {
            from = settings.getPrivacyPosture();
            PrivacyPosture to = target == null
                    ? PrivacyPosture.defaultPosture()
                    : target;
            changed = from != to;
            settings.setPrivacyPosture(to);
            if (folder != null) {
                activeFolder = folder;
            }
            target = to;
        }
        publish(eventType, from, target, folder, reason);
        notifyEvent(eventType, from, target, folder, reason);
        if ("data_governance.posture.downshifted".equals(eventType)) {
            auditPostureEvent("posture.downshift", from, target, folder, reason);
        }
        if (changed) {
            notifyChanged(from, target, folder);
        }
    }

    private void warn(Path folder, String message) {
        publish("data_governance.posture.warning",
                current(), current(), folder, message);
        notifyEvent("data_governance.posture.warning",
                current(), current(), folder, message);
        presenter().showWarning(folder, message);
    }

    private void notifyChanged(PrivacyPosture from, PrivacyPosture to, Path folder) {
        for (Listener listener : listeners) {
            try {
                listener.postureChanged(from, to, folder);
            } catch (Throwable ignore) {
            }
        }
    }

    private void notifyEvent(String eventType, PrivacyPosture from, PrivacyPosture to,
                             Path folder, String reason) {
        for (Listener listener : listeners) {
            try {
                listener.postureEvent(eventType, from, to, folder, reason);
            } catch (Throwable ignore) {
            }
        }
    }

    private void publish(String eventType, PrivacyPosture from, PrivacyPosture to,
                         Path folder, String reason) {
        EventBus bus;
        synchronized (this) {
            bus = eventBus;
        }
        if (bus == null) {
            return;
        }
        JsonObject data = new JsonObject();
        if (from != null) {
            data.addProperty("from", from.name());
        }
        if (to != null) {
            data.addProperty("to", to.name());
        }
        if (folder != null) {
            data.addProperty("folder", folder.toString());
        }
        data.addProperty("reason", reason == null ? "" : reason);
        bus.publish(eventType, data);
    }

    private void auditPostureEvent(String command, PrivacyPosture from,
                                   PrivacyPosture to, Path folder, String reason) {
        try {
            PrivacyPosture posture = to == null ? current() : to;
            AuditLog.getInstance().append(new AuditRow(
                    Instant.now(),
                    "",
                    command,
                    posture,
                    "",
                    "",
                    0,
                    0,
                    "",
                    false,
                    Collections.<String>emptyList(),
                    postureNotes(from, to, folder, reason)));
        } catch (Throwable ignore) {
        }
    }

    private String postureNotes(PrivacyPosture from, PrivacyPosture to,
                                Path folder, String reason) {
        StringBuilder notes = new StringBuilder();
        if (from != null) {
            notes.append("from=").append(from.label());
        }
        if (to != null) {
            if (notes.length() > 0) notes.append(' ');
            notes.append("to=").append(to.label());
        }
        if (folder != null) {
            if (notes.length() > 0) notes.append(' ');
            notes.append("folder_token=")
                    .append(PathTokenMap.getInstance().tokenForPathString(folder.toString()));
        }
        String scrubbedReason = PseudonymisationFilter.getInstance()
                .freeTextScrubString(reason == null ? "" : reason)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (!scrubbedReason.isEmpty()) {
            if (notes.length() > 0) notes.append(' ');
            notes.append("reason='").append(scrubbedReason).append('\'');
        }
        return notes.toString();
    }

    private boolean isDebounced(Path folder) {
        long second = System.currentTimeMillis() / 1000L;
        synchronized (this) {
            Long previous = consultedByFolderSecond.get(folder);
            if (previous != null && previous.longValue() == second) {
                return true;
            }
            consultedByFolderSecond.put(folder, second);
            return false;
        }
    }

    private FolderPostureStore store() {
        synchronized (this) {
            return store;
        }
    }

    private Presenter presenter() {
        synchronized (this) {
            return presenter;
        }
    }

    private static Path folderOf(ImagePlus image) {
        if (image == null) {
            return null;
        }
        try {
            FileInfo fileInfo = image.getOriginalFileInfo();
            if (fileInfo == null || fileInfo.directory == null
                    || fileInfo.directory.trim().isEmpty()) {
                return null;
            }
            return Paths.get(fileInfo.directory);
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static Path normaliseFolder(Path folder) {
        if (folder == null) {
            return null;
        }
        return folder.toAbsolutePath().normalize();
    }

    private static String displayName(Path folder) {
        if (folder == null) {
            return "";
        }
        Path name = folder.getFileName();
        return name == null ? folder.toString() : name.toString();
    }
}
