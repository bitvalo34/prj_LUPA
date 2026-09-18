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
import gt.lupa.storage.PublishedTileReader;
import gt.lupa.storage.TileData;
import gt.lupa.storage.TileReadException;
import gt.lupa.storage.TileReader;
import gt.lupa.websocket.WebSocketEndpoint;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LupaSession implements WebSocketEndpoint {
    private static final Set<String> RELEASE_STATUSES = Set.of("displayed", "discarded", "failed");

    private final PublishedImageStore store;
    private final TileReader tileReader;
    private final Executor diskExecutor;
    private final SerialExecutor serial;
    private final LupaJson json = new LupaJson();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<Integer, DeliveryReservation> deliveries = new LinkedHashMap<>();

    private volatile Sender sender;
    private LupaSessionState state = LupaSessionState.ESPERA_HELLO;
    private int currentEpoch;
    private int negotiatedWindowBytes;
    private long freeWindowBytes;
    private long bitmapBudgetBytes;
    private long nextDeliveryId = 1;
    private long planGeneration;
    private PublishedImageStore.OpenedImage opened;
    private ActivePlan activePlan;

    public LupaSession(PublishedImageStore store, Executor workerExecutor) {
        this(store, new PublishedTileReader(store, LupaProtocol.MAX_TILE_BYTES), workerExecutor);
    }

    public LupaSession(PublishedImageStore store, TileReader tileReader, Executor workerExecutor) {
        this(store, tileReader, workerExecutor, workerExecutor);
    }

    LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor) {
        this.store = Objects.requireNonNull(store);
        this.tileReader = Objects.requireNonNull(tileReader);
        this.diskExecutor = Objects.requireNonNull(diskExecutor);
        this.serial = new SerialExecutor(Objects.requireNonNull(stateExecutor));
    }

    @Override
    public void onOpen(Sender sender) {
        this.sender = Objects.requireNonNull(sender);
    }

    @Override
    public void onText(Sender sender, String message) {
        if (closed.get()) return;
        submitSerial(() -> handleText(sender, message), sender);
    }

    @Override
    public void onClosed(int code, String reason) {
        if (!closed.compareAndSet(false, true)) return;
        try {
            serial.execute(() -> {
                state = LupaSessionState.CERRADA;
                activePlan = null;
                deliveries.clear();
                freeWindowBytes = 0;
            });
        } catch (RejectedExecutionException ignored) {
            // The connection is already terminal; queued state becomes unreachable with the session.
        }
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
                case "RELEASE" -> handleRelease(sender, control);
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
        freeWindowBytes = negotiatedWindowBytes;
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

        invalidatePlan();
        opened = candidate;
        currentEpoch = epoch;
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
        invalidatePlan();
        currentEpoch = epoch;
        long generation = ++planGeneration;
        ViewPlan view = new ViewPlan(epoch, x, y, width, height, viewportWidth, viewportHeight, level);
        activePlan = new ActivePlan(
                generation,
                view,
                new TilePlanCursor(manifest, level, x, y, width, height));

        ObjectNode plan = json.mapper().createObjectNode();
        plan.put("type", "PLAN");
        plan.put("epoch", epoch);
        plan.put("appliedLevel", level);
        plan.put("contextLevel", level);
        if (sendJson(sender, plan)) pump();
    }

    private void handleRelease(Sender sender, ObjectNode control) throws LupaControlException {
        LupaJson.requireOnly(control, "type", "deliveryId", "status");
        if (state == LupaSessionState.ESPERA_HELLO) {
            policyClose(sender, "HELLO must complete before RELEASE");
            return;
        }

        int deliveryId = LupaJson.requireInt(control, "deliveryId", 1, LupaProtocol.MAX_EPOCH);
        String status = LupaJson.requireText(control, "status");
        if (!RELEASE_STATUSES.contains(status)) {
            policyClose(sender, "RELEASE status must be displayed, discarded or failed");
            return;
        }

        DeliveryReservation reservation = deliveries.remove(deliveryId);
        if (reservation == null) return;

        freeWindowBytes += reservation.reservedBytes();
        enforceCreditInvariant(sender);
        pump();
    }

    private void pump() {
        if (closed.get()) return;
        ActivePlan plan = activePlan;
        if (plan == null || plan.busy) return;

        if (plan.pendingTile != null) {
            trySendPending(plan);
            return;
        }

        if (!plan.cursor.hasNext()) {
            ObjectNode done = json.mapper().createObjectNode();
            done.put("type", "DONE");
            done.put("epoch", plan.view.epoch());
            done.put("sentTiles", plan.sentTiles);
            activePlan = null;
            sendJson(sender, done);
            return;
        }

        if (deliveries.size() >= LupaProtocol.MAX_IN_FLIGHT) return;

        TilePlanCursor.TileRef ref = plan.cursor.next();
        plan.busy = true;
        long generation = plan.generation;
        PublishedImageStore.OpenedImage imageAtRead = opened;

        try {
            diskExecutor.execute(() -> {
                try {
                    TileData tile = tileReader.read(imageAtRead, ref.z(), ref.x(), ref.y());
                    submitSerial(() -> onTileRead(generation, ref, tile, null), sender);
                } catch (TileReadException e) {
                    submitSerial(() -> onTileRead(generation, ref, null, e), sender);
                } catch (RuntimeException e) {
                    submitSerial(() -> onTileRead(
                            generation,
                            ref,
                            null,
                            new TileReadException("unexpected tile read failure", e)), sender);
                }
            });
        } catch (RejectedExecutionException e) {
            plan.busy = false;
            failPlan(plan, LupaProtocol.ErrorCode.INTERNAL_READ_ERROR, "server tile read queue is full");
        }
    }

    private void onTileRead(
            long generation,
            TilePlanCursor.TileRef ref,
            TileData tile,
            TileReadException failure) {
        ActivePlan plan = activePlan;
        if (plan == null || plan.generation != generation) return;
        plan.busy = false;

        if (failure != null) {
            failPlan(plan, LupaProtocol.ErrorCode.INTERNAL_READ_ERROR, "published tile could not be read");
            return;
        }

        plan.pendingTile = new PendingTile(ref, tile);
        trySendPending(plan);
    }

    private void trySendPending(ActivePlan plan) {
        if (closed.get() || activePlan != plan || plan.busy || plan.pendingTile == null) return;
        if (deliveries.size() >= LupaProtocol.MAX_IN_FLIGHT) return;
        if (nextDeliveryId > LupaProtocol.MAX_EPOCH) {
            failPlan(plan, LupaProtocol.ErrorCode.LIMIT_EXCEEDED, "deliveryId space is exhausted");
            return;
        }

        PendingTile pending = plan.pendingTile;
        int deliveryId = (int) nextDeliveryId;
        final byte[] envelope;
        try {
            envelope = buildTileEnvelope(deliveryId, plan.view.epoch(), pending.ref, pending.tile);
        } catch (JsonProcessingException e) {
            failPlan(plan, LupaProtocol.ErrorCode.INTERNAL_READ_ERROR, "TILE header could not be encoded");
            return;
        }

        int reservationBytes = envelope.length;
        if (reservationBytes > negotiatedWindowBytes) {
            failPlan(plan, LupaProtocol.ErrorCode.LIMIT_EXCEEDED, "single TILE exceeds negotiated window");
            return;
        }
        if (reservationBytes > freeWindowBytes) return;

        DeliveryReservation reservation = new DeliveryReservation(deliveryId, plan.view.epoch(), reservationBytes);
        deliveries.put(deliveryId, reservation);
        freeWindowBytes -= reservationBytes;
        nextDeliveryId++;
        plan.pendingTile = null;
        plan.busy = true;
        enforceCreditInvariant(sender);
        if (closed.get()) return;

        boolean accepted = sender.sendBinary(envelope, () ->
                submitSerial(() -> onTileWritten(plan.generation), sender));
        if (!accepted) {
            DeliveryReservation removed = deliveries.remove(deliveryId);
            if (removed != null) freeWindowBytes += removed.reservedBytes();
            plan.busy = false;
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "WebSocket write queue is full");
        }
    }

    private void onTileWritten(long generation) {
        ActivePlan plan = activePlan;
        if (plan == null || plan.generation != generation) return;
        plan.busy = false;
        plan.sentTiles++;
        pump();
    }

    private byte[] buildTileEnvelope(
            int deliveryId,
            int epoch,
            TilePlanCursor.TileRef ref,
            TileData tile) throws JsonProcessingException {
        byte[] jpeg = tile.jpeg();
        if (jpeg.length > LupaProtocol.MAX_TILE_BYTES) {
            throw new IllegalArgumentException("tile payload exceeds LUPA v1 limit");
        }

        ObjectNode header = json.mapper().createObjectNode();
        header.put("type", "TILE");
        header.put("deliveryId", deliveryId);
        header.put("epoch", epoch);
        header.put("imageId", opened.catalogImage().imageId());
        header.put("imageVersion", opened.catalogImage().imageVersion());
        header.put("z", ref.z());
        header.put("x", ref.x());
        header.put("y", ref.y());
        header.put("w", tile.width());
        header.put("h", tile.height());
        header.put("codec", "jpeg");
        header.put("payloadBytes", jpeg.length);

        byte[] encodedHeader = json.mapper().writeValueAsBytes(header);
        if (encodedHeader.length > 4096) {
            throw new IllegalArgumentException("TILE header exceeds LUPA v1 limit");
        }
        ByteBuffer message = ByteBuffer.allocate(4 + encodedHeader.length + jpeg.length);
        message.putInt(encodedHeader.length);
        message.put(encodedHeader);
        message.put(jpeg);
        return message.array();
    }

    private void failPlan(ActivePlan plan, LupaProtocol.ErrorCode code, String message) {
        if (activePlan != plan) return;
        activePlan = null;
        planGeneration++;
        sendError(sender, plan.view.epoch(), code, message);
    }

    private void invalidatePlan() {
        activePlan = null;
        planGeneration++;
    }

    private void enforceCreditInvariant(Sender sender) {
        long reserved = 0;
        for (DeliveryReservation reservation : deliveries.values()) {
            reserved += reservation.reservedBytes();
        }
        if (freeWindowBytes < 0 || freeWindowBytes + reserved != negotiatedWindowBytes) {
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "credit invariant violated");
        }
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

    private boolean sendJson(Sender sender, ObjectNode message) {
        if (closed.get()) return false;
        final String encoded;
        try {
            encoded = json.mapper().writeValueAsString(message);
        } catch (JsonProcessingException e) {
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "cannot encode LUPA control");
            return false;
        }
        if (!sender.sendText(encoded)) {
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "WebSocket write queue is full");
            return false;
        }
        return true;
    }

    private void policyClose(Sender sender, String reason) {
        closed.set(true);
        state = LupaSessionState.CERRADA;
        sender.close(1008, boundedMessage(reason));
    }

    private void submitSerial(Runnable task, Sender sender) {
        if (closed.get()) return;
        try {
            serial.execute(task);
        } catch (RejectedExecutionException e) {
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "server session queue is full");
        }
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

    private static final class ActivePlan {
        private final long generation;
        private final ViewPlan view;
        private final TilePlanCursor cursor;
        private boolean busy;
        private int sentTiles;
        private PendingTile pendingTile;

        private ActivePlan(long generation, ViewPlan view, TilePlanCursor cursor) {
            this.generation = generation;
            this.view = view;
            this.cursor = cursor;
        }
    }

    private record PendingTile(TilePlanCursor.TileRef ref, TileData tile) {}

    private record DeliveryReservation(int deliveryId, int epoch, int reservedBytes) {}
}
