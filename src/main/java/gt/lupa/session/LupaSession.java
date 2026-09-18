package gt.lupa.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gt.lupa.protocol.LupaControlException;
import gt.lupa.protocol.LupaJson;
import gt.lupa.protocol.LupaProtocol;
import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogImage;
import gt.lupa.storage.CatalogValidator;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import gt.lupa.storage.PublishedImageStore;
import gt.lupa.websocket.WebSocketEndpoint;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LupaSession implements WebSocketEndpoint {
    private final PublishedImageStore store;
    private final SerialExecutor serial;
    private final LupaJson json = new LupaJson();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Sender sender;
    private LupaSessionState state = LupaSessionState.ESPERA_HELLO;
    private int currentEpoch;
    private int negotiatedWindowBytes;
    private long bitmapBudgetBytes;
    private PublishedImageStore.OpenedImage opened;
    private ViewPlan activePlan;

    public LupaSession(PublishedImageStore store, Executor workerExecutor) {
        this.store = Objects.requireNonNull(store);
        this.serial = new SerialExecutor(Objects.requireNonNull(workerExecutor));
    }

    @Override
    public void onOpen(Sender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    @Override
    public void onText(Sender sender, String message) {
        if (closed.get()) return;
        try {
            serial.execute(() -> handleText(sender, message));
        } catch (RejectedExecutionException e) {
            closed.set(true);
            sender.close(1011, "server work queue is full");
        }
    }

    @Override
    public void onClosed(int code, String reason) {
        closed.set(true);
    }

    private void handleText(Sender sender, String message) {
        if (closed.get() || state == LupaSessionState.CERRADA) return;
        final ObjectNode control;
        try {
            control = json.parseControl(message);
        } catch (LupaControlException e) {
            policyClose(sender, e.getMessage());
            return;
        }

        final String type;
        try {
            type = LupaJson.requireText(control, "type");
        } catch (LupaControlException e) {
            policyClose(sender, e.getMessage());
            return;
        }

        try {
            switch (type) {
                case "HELLO" -> handleHello(sender, control);
                case "OPEN" -> handleOpen(sender, control);
                case "VIEW" -> handleView(sender, control);
                default -> policyClose(sender, "unknown or currently invalid LUPA control type: " + type);
            }
        } catch (LupaControlException e) {
            policyClose(sender, e.getMessage());
        }
    }

    private void handleHello(Sender sender, ObjectNode control) throws LupaControlException {
        LupaJson.requireOnly(control, "type", "version", "windowBytes", "bitmapBudgetBytes");
        if (state != LupaSessionState.ESPERA_HELLO) {
            policyClose(sender, "HELLO is only valid as the first LUPA control");
            return;
        }

        int version = LupaJson.requireInt(control, "version", 1, LupaProtocol.MAX_EPOCH);
        long requestedWindow = LupaJson.requireLong(control, "windowBytes", 1, Integer.MAX_VALUE);
        long bitmapBudget = LupaJson.requireLong(control, "bitmapBudgetBytes", 1, Long.MAX_VALUE);

        if (version != LupaProtocol.VERSION) {
            sendError(sender, null, LupaProtocol.ErrorCode.VERSION_UNSUPPORTED,
                    "LUPA v1 is the only supported application version");
            return;
        }
        if (requestedWindow < LupaProtocol.MIN_WINDOW_BYTES) {
            sendError(sender, null, LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                    "windowBytes must be at least " + LupaProtocol.MIN_WINDOW_BYTES);
            return;
        }

        negotiatedWindowBytes = (int) Math.min(requestedWindow, LupaProtocol.MAX_WINDOW_BYTES);
        bitmapBudgetBytes = bitmapBudget;
        state = LupaSessionState.LISTA;

        ObjectNode welcome = json.mapper().createObjectNode();
        welcome.put("type", "WELCOME");
        welcome.put("version", LupaProtocol.VERSION);
        welcome.put("windowBytes", negotiatedWindowBytes);
        welcome.put("maxTileBytes", LupaProtocol.MAX_TILE_BYTES);
        welcome.put("maxInFlight", LupaProtocol.MAX_IN_FLIGHT);
        sendJson(sender, welcome);
    }

    private void handleOpen(Sender sender, ObjectNode control) throws LupaControlException {
        LupaJson.requireOnly(control, "type", "epoch", "imageId");
        if (state == LupaSessionState.ESPERA_HELLO) {
            policyClose(sender, "HELLO must complete before OPEN");
            return;
        }

        int epoch = LupaJson.requireInt(control, "epoch", 1, LupaProtocol.MAX_EPOCH);
        String imageId = LupaJson.requireText(control, "imageId");
        if (epoch <= currentEpoch) return;

        try {
            CatalogValidator.validateId(imageId);
        } catch (CatalogException e) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.IMAGE_NOT_FOUND, "imageId is not published");
            return;
        }

        final CatalogImage listed;
        try {
            listed = store.readCatalog().images().stream()
                    .filter(image -> image.imageId().equals(imageId))
                    .findFirst()
                    .orElse(null);
        } catch (CatalogException e) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.IMAGE_NOT_READY, "published catalog is not readable");
            return;
        }
        if (listed == null) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.IMAGE_NOT_FOUND, "image is not published");
            return;
        }

        final PublishedImageStore.OpenedImage candidate;
        try {
            candidate = store.openCurrent(imageId);
        } catch (CatalogException e) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.IMAGE_NOT_READY, "published image is not ready");
            return;
        }

        opened = candidate;
        currentEpoch = epoch;
        activePlan = null;
        state = LupaSessionState.IMAGEN_ABIERTA;
        sendManifest(sender, epoch, candidate.manifest());
    }

    private void handleView(Sender sender, ObjectNode control) throws LupaControlException {
        LupaJson.requireOnly(
                control,
                "type", "epoch", "imageId", "imageVersion", "rect",
                "viewportPx", "detailOffset", "mode", "focus");

        if (state != LupaSessionState.IMAGEN_ABIERTA || opened == null) {
            policyClose(sender, "OPEN must complete before VIEW");
            return;
        }

        int epoch = LupaJson.requireInt(control, "epoch", 1, LupaProtocol.MAX_EPOCH);
        if (epoch <= currentEpoch) return;

        String imageId = LupaJson.requireText(control, "imageId");
        String imageVersion = LupaJson.requireText(control, "imageVersion");
        if (!imageId.equals(opened.catalogImage().imageId())
                || !imageVersion.equals(opened.catalogImage().imageVersion())) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.BAD_VIEW, "VIEW does not match the opened image version");
            return;
        }

        int detailOffset = LupaJson.requireInt(control, "detailOffset", -2, 0);
        String mode = LupaJson.requireText(control, "mode");
        if (detailOffset != 0 || !"uniform".equals(mode)) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.BAD_VIEW,
                    "E20 currently supports mode=uniform with detailOffset=0; focus refinement belongs to E21");
            return;
        }
        LupaJson.requireNull(control, "focus");

        ObjectNode rectNode = LupaJson.requireObject(control, "rect");
        LupaJson.requireOnly(rectNode, "x", "y", "width", "height");
        int x = LupaJson.requireInt(rectNode, "x", 0, Integer.MAX_VALUE);
        int y = LupaJson.requireInt(rectNode, "y", 0, Integer.MAX_VALUE);
        int width = LupaJson.requireInt(rectNode, "width", 1, Integer.MAX_VALUE);
        int height = LupaJson.requireInt(rectNode, "height", 1, Integer.MAX_VALUE);

        ImageManifest manifest = opened.manifest();
        if ((long) x + width > manifest.width() || (long) y + height > manifest.height()) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.BAD_VIEW, "rect must be fully contained in the opened image");
            return;
        }

        ObjectNode viewportNode = LupaJson.requireObject(control, "viewportPx");
        LupaJson.requireOnly(viewportNode, "width", "height");
        int viewportWidth = LupaJson.requireInt(viewportNode, "width", 1, Integer.MAX_VALUE);
        int viewportHeight = LupaJson.requireInt(viewportNode, "height", 1, Integer.MAX_VALUE);
        long viewportPixels = (long) viewportWidth * viewportHeight;
        if (viewportPixels > LupaProtocol.MAX_VIEWPORT_PIXELS) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                    "viewportPx exceeds " + LupaProtocol.MAX_VIEWPORT_PIXELS + " pixels");
            return;
        }

        int level = automaticLevel(manifest, width, height, viewportWidth, viewportHeight);
        activePlan = new ViewPlan(epoch, x, y, width, height, viewportWidth, viewportHeight, level);
        currentEpoch = epoch;

        ObjectNode plan = json.mapper().createObjectNode();
        plan.put("type", "PLAN");
        plan.put("epoch", epoch);
        plan.put("appliedLevel", level);
        plan.put("contextLevel", level);
        sendJson(sender, plan);
    }

    private int automaticLevel(
            ImageManifest manifest,
            int rectWidth,
            int rectHeight,
            int viewportWidth,
            int viewportHeight) {
        for (ImageLevel level : manifest.levels()) {
            long availableX = (long) rectWidth * level.width();
            long requiredX = (long) viewportWidth * manifest.width();
            long availableY = (long) rectHeight * level.height();
            long requiredY = (long) viewportHeight * manifest.height();
            if (availableX >= requiredX && availableY >= requiredY) return level.z();
        }
        return manifest.levels().getLast().z();
    }

    private void sendManifest(Sender sender, int epoch, ImageManifest manifest) {
        ObjectNode response = json.mapper().createObjectNode();
        response.put("type", "MANIFEST");
        response.put("epoch", epoch);
        response.put("imageId", manifest.imageId());
        response.put("imageVersion", manifest.imageVersion());
        response.put("width", manifest.width());
        response.put("height", manifest.height());
        ArrayNode levels = response.putArray("levels");
        for (ImageLevel level : manifest.levels()) {
            ObjectNode item = levels.addObject();
            item.put("z", level.z());
            item.put("width", level.width());
            item.put("height", level.height());
        }
        response.put("tileSize", manifest.tileSize());
        sendJson(sender, response);
    }

    private void sendError(Sender sender, Integer epoch, LupaProtocol.ErrorCode code, String message) {
        ObjectNode error = json.mapper().createObjectNode();
        error.put("type", "ERROR");
        if (epoch != null) error.put("epoch", epoch);
        error.put("code", code.name());
        error.put("message", boundedMessage(message));
        sendJson(sender, error);
    }

    private void sendJson(Sender sender, ObjectNode message) {
        if (closed.get()) return;
        final String encoded;
        try {
            encoded = json.mapper().writeValueAsString(message);
        } catch (JsonProcessingException e) {
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "cannot encode LUPA control");
            return;
        }
        if (!sender.sendText(encoded)) {
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "WebSocket write queue is full");
        }
    }

    private void policyClose(Sender sender, String reason) {
        closed.set(true);
        state = LupaSessionState.CERRADA;
        sender.close(1008, boundedMessage(reason));
    }

    private static String boundedMessage(String message) {
        if (message == null || message.isBlank()) return "LUPA request rejected";
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    record ViewPlan(
            int epoch,
            int x,
            int y,
            int width,
            int height,
            int viewportWidth,
            int viewportHeight,
            int level) {}
}
