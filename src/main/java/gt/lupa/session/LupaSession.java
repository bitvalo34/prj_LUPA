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
    private final ViewPlanner viewPlanner = new ViewPlanner();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
    private final Map<Integer, DeliveryReservation> deliveries = new LinkedHashMap<>();

    private volatile Sender sender;
    private LupaSessionState state = LupaSessionState.ESPERA_HELLO;
    private int currentEpoch;
    private int negotiatedWindowBytes;
    private long freeWindowBytes;
    private long bitmapBudgetBytes;
    private long nextDeliveryId = 1;
    private long planGeneration;
    private boolean thumbnailCommittedForOpen;
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
        closed.set(true);
        if (!cleanupScheduled.compareAndSet(false, true)) return;
        try {
            serial.execute(() -> {
                state = LupaSessionState.CERRADA;
                activePlan = null;
                deliveries.clear();
                freeWindowBytes = 0;
            });
        } catch (RejectedExecutionException ignored) {
            // The connection is terminal and the session becomes unreachable with the transport.
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
        if (bitmapBudget < LupaProtocol.BITMAP_RESERVED_BYTES) {
            sendError(sender, null, LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                    "bitmapBudgetBytes must be at least " + LupaProtocol.BITMAP_RESERVED_BYTES);
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
        thumbnailCommittedForOpen = false;
        currentEpoch = epoch;
        state = LupaSessionState.IMAGEN_ABIERTA;
        sendManifest(sender, epoch, candidate.manifest());
    }

    private void handleView(Sender sender, ObjectNode control) throws LupaControlException {
        int epoch = LupaJson.requireInt(control, "epoch", 1, LupaProtocol.MAX_EPOCH);

        if (state != LupaSessionState.IMAGEN_ABIERTA || opened == null) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.BAD_VIEW, "OPEN must complete before VIEW");
            return;
        }
        if (epoch <= currentEpoch) return;

        final ViewRequest request;
        try {
            request = ViewRequest.parse(
                    control,
                    opened.catalogImage().imageId(),
                    opened.catalogImage().imageVersion(),
                    opened.manifest().width(),
                    opened.manifest().height());
        } catch (LupaControlException e) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.BAD_VIEW, e.getMessage());
            return;
        }

        boolean includeOpeningThumbnail = !thumbnailCommittedForOpen;
        final ViewPlanner.Plan selected;
        try {
            selected = viewPlanner.plan(
                    opened.manifest(),
                    request,
                    bitmapBudgetBytes,
                    includeOpeningThumbnail);
        } catch (ViewPlanner.PlanningException | ArithmeticException e) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.LIMIT_EXCEEDED, e.getMessage());
            return;
        }

        invalidatePlan();
        currentEpoch = epoch;
        long generation = ++planGeneration;
        activePlan = new ActivePlan(
                generation,
                request,
                selected,
                new PlannedTileCursor(selected));

        ObjectNode plan = json.mapper().createObjectNode();
        plan.put("type", "PLAN");
        plan.put("epoch", epoch);
        plan.put("appliedLevel", selected.appliedLevel());
        plan.put("contextLevel", selected.contextLevel());
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

        ViewPlanner.TileDescriptor descriptor = plan.cursor.next();
        ViewPlanner.TileRef ref = descriptor.ref();
        boolean openingThumbnail = descriptor.role() == ViewPlanner.TileRole.OPENING_THUMBNAIL;
        plan.busy = true;
        long generation = plan.generation;
        PublishedImageStore.OpenedImage imageAtRead = opened;

        try {
            diskExecutor.execute(() -> {
                try {
                    TileData tile = tileReader.read(imageAtRead, ref.z(), ref.x(), ref.y());
                    submitSerial(() -> onTileRead(
                            generation, ref, openingThumbnail, tile, null), sender);
                } catch (TileReadException e) {
                    submitSerial(() -> onTileRead(
                            generation, ref, openingThumbnail, null, e), sender);
                } catch (RuntimeException e) {
                    submitSerial(() -> onTileRead(
                            generation,
                            ref,
                            openingThumbnail,
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
            ViewPlanner.TileRef ref,
            boolean openingThumbnail,
            TileData tile,
            TileReadException failure) {
        ActivePlan plan = activePlan;
        if (plan == null || plan.generation != generation) return;
        plan.busy = false;

        if (failure != null) {
            failPlan(plan, LupaProtocol.ErrorCode.INTERNAL_READ_ERROR, "published tile could not be read");
            return;
        }

        plan.pendingTile = new PendingTile(ref, tile, openingThumbnail);
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
        if (accepted && pending.openingThumbnail()) {
            thumbnailCommittedForOpen = true;
        }
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
            ViewPlanner.TileRef ref,
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

    private static final class ActivePlan {
        private final long generation;
        private final ViewRequest view;
        private final ViewPlanner.Plan selection;
        private final PlannedTileCursor cursor;
        private boolean busy;
        private int sentTiles;
        private PendingTile pendingTile;

        private ActivePlan(
                long generation,
                ViewRequest view,
                ViewPlanner.Plan selection,
                PlannedTileCursor cursor) {
            this.generation = generation;
            this.view = view;
            this.selection = selection;
            this.cursor = cursor;
        }
    }

    private record PendingTile(
            ViewPlanner.TileRef ref,
            TileData tile,
            boolean openingThumbnail) {}

    private record DeliveryReservation(int deliveryId, int epoch, int reservedBytes) {}
}
