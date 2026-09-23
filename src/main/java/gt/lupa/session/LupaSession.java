package gt.lupa.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gt.lupa.concurrent.MonotonicScheduler;
import gt.lupa.concurrent.TileReadAdmission;
import gt.lupa.concurrent.TransientBufferBudget;
import gt.lupa.diagnostics.E22Metrics;
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
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class LupaSession implements WebSocketEndpoint {
    private static final Set<String> RELEASE_STATUSES = Set.of("displayed", "discarded", "failed");
    private static final boolean I21_DIAGNOSTICS = Boolean.getBoolean("lupa.i21.diag");
    private static final AtomicInteger SESSION_IDS = new AtomicInteger();
    private static final int MAX_TILE_ENVELOPE_BYTES =
            4 + 4096 + LupaProtocol.MAX_TILE_BYTES;
    private static final int MAX_SELECTIVE_RETRIES = 2;

    private final PublishedImageStore store;
    private final TileReader tileReader;
    private final Executor diskExecutor;
    private final Executor metadataExecutor;
    private final SessionAdmission sessionAdmission;
    private final TileReadAdmission tileReadAdmission;
    private final TransientBufferBudget transientBuffers;
    private final MonotonicScheduler scheduler;
    private final Duration helloTimeout;
    private final Duration releaseTimeout;
    private final SerialExecutor serial;
    private E22Metrics metrics = new E22Metrics();
    private final LupaJson json = new LupaJson();
    private final ViewPlanner viewPlanner = new ViewPlanner();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
    private final Map<Integer, DeliveryReservation> deliveries = new LinkedHashMap<>();
    private final Set<TransientBufferBudget.Lease> liveTransientBuffers =
            ConcurrentHashMap.newKeySet();
    private final Set<MonotonicScheduler.Handle> liveReleaseTimers =
            ConcurrentHashMap.newKeySet();
    private volatile SessionAdmission.Lease admissionLease;
    private volatile MonotonicScheduler.Handle helloTimer;
    private final int diagnosticSessionId = SESSION_IDS.incrementAndGet();

    private volatile Sender sender;
    private LupaSessionState state = LupaSessionState.ESPERA_HELLO;
    private int currentEpoch;
    private int negotiatedWindowBytes;
    private long freeWindowBytes;
    private long bitmapBudgetBytes;
    private final DeliveryIdSequence deliveryIds;
    private long planGeneration;
    private long tileTurnsGranted;
    private boolean thumbnailCommittedForOpen;
    private PublishedImageStore.OpenedImage opened;
    private ActivePlan activePlan;

    public LupaSession(PublishedImageStore store, Executor workerExecutor) {
        this(store, new PublishedTileReader(store, LupaProtocol.MAX_TILE_BYTES), workerExecutor);
    }

    public LupaSession(PublishedImageStore store, TileReader tileReader, Executor workerExecutor) {
        this(store, tileReader, workerExecutor, workerExecutor, workerExecutor);
    }

    /*
     * Existing deterministic tests use the four-argument constructor to control tile reads only.
     * Metadata remains synchronous there; production uses the shared worker executor asynchronously.
     */
    LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor) {
        this(store, tileReader, stateExecutor, diskExecutor, Runnable::run);
    }

    LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor,
            Executor metadataExecutor) {
        this(
                store,
                tileReader,
                stateExecutor,
                diskExecutor,
                metadataExecutor,
                null,
                null,
                null,
                null,
                null,
                null,
                new DeliveryIdSequence());
    }

    public LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor,
            Executor metadataExecutor,
            SessionAdmission sessionAdmission) {
        this(
                store,
                tileReader,
                stateExecutor,
                diskExecutor,
                metadataExecutor,
                Objects.requireNonNull(sessionAdmission),
                null,
                null,
                null,
                null,
                null,
                new DeliveryIdSequence());
    }

    public LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor,
            Executor metadataExecutor,
            SessionAdmission sessionAdmission,
            TileReadAdmission tileReadAdmission,
            TransientBufferBudget transientBuffers) {
        this(
                store,
                tileReader,
                stateExecutor,
                diskExecutor,
                metadataExecutor,
                Objects.requireNonNull(sessionAdmission),
                Objects.requireNonNull(tileReadAdmission),
                Objects.requireNonNull(transientBuffers),
                null,
                null,
                null,
                new DeliveryIdSequence());
    }

    public LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor,
            Executor metadataExecutor,
            SessionAdmission sessionAdmission,
            TileReadAdmission tileReadAdmission,
            TransientBufferBudget transientBuffers,
            MonotonicScheduler scheduler,
            Duration helloTimeout,
            Duration releaseTimeout) {
        this(
                store,
                tileReader,
                stateExecutor,
                diskExecutor,
                metadataExecutor,
                Objects.requireNonNull(sessionAdmission),
                Objects.requireNonNull(tileReadAdmission),
                Objects.requireNonNull(transientBuffers),
                Objects.requireNonNull(scheduler),
                requirePositive(helloTimeout, "helloTimeout"),
                requirePositive(releaseTimeout, "releaseTimeout"),
                new DeliveryIdSequence());
    }

    public LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor,
            Executor metadataExecutor,
            SessionAdmission sessionAdmission,
            TileReadAdmission tileReadAdmission,
            TransientBufferBudget transientBuffers,
            MonotonicScheduler scheduler,
            Duration helloTimeout,
            Duration releaseTimeout,
            E22Metrics metrics) {
        this(
                store,
                tileReader,
                stateExecutor,
                diskExecutor,
                metadataExecutor,
                sessionAdmission,
                tileReadAdmission,
                transientBuffers,
                scheduler,
                helloTimeout,
                releaseTimeout);
        this.metrics = Objects.requireNonNull(metrics);
    }

    LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor,
            Executor metadataExecutor,
            long firstDeliveryId) {
        this(
                store,
                tileReader,
                stateExecutor,
                diskExecutor,
                metadataExecutor,
                null,
                null,
                null,
                null,
                null,
                null,
                new DeliveryIdSequence(firstDeliveryId));
    }

    private LupaSession(
            PublishedImageStore store,
            TileReader tileReader,
            Executor stateExecutor,
            Executor diskExecutor,
            Executor metadataExecutor,
            SessionAdmission sessionAdmission,
            TileReadAdmission tileReadAdmission,
            TransientBufferBudget transientBuffers,
            MonotonicScheduler scheduler,
            Duration helloTimeout,
            Duration releaseTimeout,
            DeliveryIdSequence deliveryIds) {
        this.store = Objects.requireNonNull(store);
        this.tileReader = Objects.requireNonNull(tileReader);
        this.diskExecutor = Objects.requireNonNull(diskExecutor);
        this.metadataExecutor = Objects.requireNonNull(metadataExecutor);
        this.sessionAdmission = sessionAdmission;
        this.tileReadAdmission = tileReadAdmission;
        this.transientBuffers = transientBuffers;
        this.scheduler = scheduler;
        this.helloTimeout = helloTimeout;
        this.releaseTimeout = releaseTimeout;
        this.serial = new SerialExecutor(Objects.requireNonNull(stateExecutor));
        this.deliveryIds = Objects.requireNonNull(deliveryIds);
    }

    @Override
    public void onOpen(Sender sender) {
        this.sender = Objects.requireNonNull(sender);
        diagnostic("OPEN_SOCKET", "state=" + state);

        if (scheduler != null) {
            helloTimer = scheduler.schedule(
                    helloTimeout,
                    () -> submitSerial(
                            this::onHelloTimeout,
                            this.sender));
        }
    }

    @Override
    public void onText(Sender sender, String message) {
        if (closed.get()) return;
        submitSerial(() -> handleText(sender, message), sender);
    }

    @Override
    public void onClosed(int code, String reason) {
        closed.set(true);
        cancelHelloTimer();
        cancelAllReleaseTimers();
        releaseAdmission();
        releaseAllTransientBuffers();
        if (!cleanupScheduled.compareAndSet(false, true)) return;
        metrics.recordSessionCleanupRun();
        try {
            serial.execute(() -> {
                state = LupaSessionState.CERRADA;
                ActivePlan closingPlan = activePlan;
                activePlan = null;
                discardPlanResources(closingPlan);
                diagnosticCredits("CLOSE", null, "code=" + code + " reason=" + boundedMessage(reason));
                deliveries.clear();
                freeWindowBytes = 0;
            });
        } catch (RejectedExecutionException ignored) {
            // Transport is terminal. Shared budgets were already released above.
        }
    }

    private void releaseAdmission() {
        SessionAdmission.Lease lease = admissionLease;
        admissionLease = null;
        if (lease != null) {
            lease.close();
            metrics.recordSessionRelease();
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
                case "ACK_STATE" -> handleAckState(sender, control);
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

        if (sessionAdmission != null) {
            SessionAdmission.Lease lease = sessionAdmission.tryAcquire();
            if (lease == null) {
                sendError(
                        sender,
                        null,
                        LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                        "server active-session limit reached");
                closed.set(true);
                state = LupaSessionState.CERRADA;
                sender.close(1013, "server active-session limit reached");
                return;
            }
            admissionLease = lease;
        }

        cancelHelloTimer();
        negotiatedWindowBytes = (int) Math.min(requestedWindow, LupaProtocol.MAX_WINDOW_BYTES);
        freeWindowBytes = negotiatedWindowBytes;
        bitmapBudgetBytes = bitmapBudget;
        state = LupaSessionState.LISTA;
        diagnosticCredits("HELLO", null,
                "window=" + negotiatedWindowBytes + " bitmapBudget=" + bitmapBudgetBytes);

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

        /*
         * Storage validation is intentionally off the serialized session executor. The result is
         * committed only if its epoch is still newer than the last accepted intent. Therefore a
         * late OPEN can never replace a later successful OPEN/VIEW, while a later OPEN that fails
         * storage validation does not consume the epoch or destroy the current image/plan.
         */
        try {
            metadataExecutor.execute(() -> {
                PublishedImageStore.OpenedImage candidate = null;
                LupaProtocol.ErrorCode failureCode = null;
                String failureMessage = null;
                try {
                    boolean listed = store.readCatalog().images().stream()
                            .anyMatch(image -> image.imageId().equals(imageId));
                    if (!listed) {
                        failureCode = LupaProtocol.ErrorCode.IMAGE_NOT_FOUND;
                        failureMessage = "image is not published";
                    } else {
                        candidate = store.openCurrent(imageId);
                    }
                } catch (CatalogException e) {
                    failureCode = LupaProtocol.ErrorCode.IMAGE_NOT_READY;
                    failureMessage = "published image is not ready";
                }

                PublishedImageStore.OpenedImage resolved = candidate;
                LupaProtocol.ErrorCode resolvedFailureCode = failureCode;
                String resolvedFailureMessage = failureMessage;
                submitSerial(
                        () -> onOpenResolved(
                                sender,
                                epoch,
                                resolved,
                                resolvedFailureCode,
                                resolvedFailureMessage),
                        sender);
            });
        } catch (RejectedExecutionException e) {
            sendError(sender, epoch, LupaProtocol.ErrorCode.IMAGE_NOT_READY, "server storage queue is full");
        }
    }

    private void onOpenResolved(
            Sender sender,
            int epoch,
            PublishedImageStore.OpenedImage candidate,
            LupaProtocol.ErrorCode failureCode,
            String failureMessage) {
        if (closed.get() || state == LupaSessionState.CERRADA) return;
        if (epoch <= currentEpoch) return;

        if (failureCode != null || candidate == null) {
            sendError(
                    sender,
                    epoch,
                    failureCode == null ? LupaProtocol.ErrorCode.IMAGE_NOT_READY : failureCode,
                    failureMessage == null ? "published image is not ready" : failureMessage);
            return;
        }

        invalidatePlan();
        opened = candidate;
        thumbnailCommittedForOpen = false;
        currentEpoch = epoch;
        state = LupaSessionState.IMAGEN_ABIERTA;
        diagnosticCredits("OPEN", null,
                "epoch=" + epoch
                        + " image=" + candidate.catalogImage().imageId()
                        + "/" + candidate.catalogImage().imageVersion());
        sendManifest(sender, epoch, candidate.manifest());
    }

    private void handleView(Sender sender, ObjectNode control) throws LupaControlException {
        int epoch = LupaJson.requireInt(control, "epoch", 1, LupaProtocol.MAX_EPOCH);

        if (state != LupaSessionState.IMAGEN_ABIERTA || opened == null) {
            policyClose(sender, "OPEN must complete before VIEW");
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

        ObjectNode planControl = json.mapper().createObjectNode();
        planControl.put("type", "PLAN");
        planControl.put("epoch", epoch);
        planControl.put("appliedLevel", selected.appliedLevel());
        planControl.put("contextLevel", selected.contextLevel());
        diagnosticCredits("VIEW_PLAN", null,
                "epoch=" + epoch
                        + " mode=" + request.mode()
                        + " offset=" + request.detailOffset()
                        + " applied=" + selected.appliedLevel()
                        + " context=" + selected.contextLevel()
                        + " rect=" + request.rect().x() + "," + request.rect().y()
                        + "," + request.rect().width() + "x" + request.rect().height());
        sendPlanControl(activePlan, planControl);
    }

    private void sendPlanControl(ActivePlan plan, ObjectNode control) {
        final String encoded;
        try {
            encoded = json.mapper().writeValueAsString(control);
        } catch (JsonProcessingException e) {
            failPlan(plan, LupaProtocol.ErrorCode.INTERNAL_READ_ERROR, "PLAN could not be encoded");
            return;
        }

        WebSocketEndpoint.TrackedSend write = sender.sendTextTracked(
                encoded,
                () -> submitSerial(() -> onPlanCommitted(plan.generation), sender),
                () -> {});
        plan.planWrite = write;
        if (!write.accepted()) {
            activePlan = null;
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "WebSocket write queue is full");
        }
    }

    private void onPlanCommitted(long generation) {
        ActivePlan plan = activePlan;
        if (plan == null || plan.generation != generation) {
            metrics.recordLateCallbackDiscarded();
            return;
        }
        plan.planCommitted = true;
        pump();
    }

    private void sendDoneControl(ActivePlan plan, ObjectNode control) {
        final String encoded;
        try {
            encoded = json.mapper().writeValueAsString(control);
        } catch (JsonProcessingException e) {
            failPlan(plan, LupaProtocol.ErrorCode.INTERNAL_READ_ERROR, "DONE could not be encoded");
            return;
        }

        WebSocketEndpoint.TrackedSend write = sender.sendTextTracked(
                encoded,
                () -> submitSerial(() -> onDoneCommitted(plan.generation), sender),
                () -> {});
        plan.doneWrite = write;
        if (!write.accepted()) {
            activePlan = null;
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "WebSocket write queue is full");
        }
    }

    private void onDoneCommitted(long generation) {
        ActivePlan plan = activePlan;
        if (plan == null || plan.generation != generation) return;
        diagnosticCredits("DONE", null,
                "epoch=" + plan.view.epoch() + " sentTiles=" + plan.sentTiles);
        activePlan = null;
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

        DeliveryReservation reservation =
                deliveries.get(deliveryId);
        if (reservation == null) {
            diagnosticCredits(
                    "RELEASE_IGNORED",
                    deliveryId,
                    "status=" + status);
            return;
        }

        int epoch = reservation.epoch;
        acknowledgeDelivery(
                sender,
                deliveryId,
                "RELEASE_" + status.toUpperCase());
        diagnosticCredits(
                "RELEASE",
                deliveryId,
                "status=" + status
                        + " epoch=" + epoch);
        pump();
    }

    /**
     * SACK-inspired selective tile acknowledgement.
     *
     * received contains inclusive deliveryId ranges that are terminal at the
     * client and therefore release their byte reservations. missing contains
     * individual deliveryIds that must be selectively retransmitted. Unknown
     * or stale ids are ignored; malformed/overlapping sets are protocol errors.
     */
    private void handleAckState(Sender sender, ObjectNode control)
            throws LupaControlException {
        LupaJson.requireOnly(
                control,
                "type",
                "epoch",
                "received",
                "missing");

        if (state == LupaSessionState.ESPERA_HELLO) {
            policyClose(sender, "HELLO must complete before ACK_STATE");
            return;
        }

        int epoch =
                LupaJson.requireInt(
                        control,
                        "epoch",
                        1,
                        LupaProtocol.MAX_EPOCH);

        JsonNode receivedNode = control.get("received");
        JsonNode missingNode = control.get("missing");

        if (receivedNode == null || !receivedNode.isArray()) {
            throw new LupaControlException("received must be an array");
        }
        if (missingNode == null || !missingNode.isArray()) {
            throw new LupaControlException("missing must be an array");
        }
        if (receivedNode.size() > 32 || missingNode.size() > 32) {
            throw new LupaControlException(
                    "ACK_STATE exceeds selective acknowledgement limits");
        }

        java.util.List<AckRange> ranges =
                new java.util.ArrayList<>();

        int previousEnd = 0;
        for (JsonNode rangeNode : receivedNode) {
            if (!rangeNode.isArray() || rangeNode.size() != 2) {
                throw new LupaControlException(
                        "received ranges must be [start,end]");
            }

            int start =
                    requireDeliveryIdNode(
                            rangeNode.get(0),
                            "received range start");
            int end =
                    requireDeliveryIdNode(
                            rangeNode.get(1),
                            "received range end");

            if (end < start) {
                throw new LupaControlException(
                        "received range end precedes start");
            }
            if (!ranges.isEmpty() && start <= previousEnd) {
                throw new LupaControlException(
                        "received ranges must be sorted and non-overlapping");
            }

            ranges.add(new AckRange(start, end));
            previousEnd = end;
        }

        java.util.Set<Integer> missing =
                new java.util.LinkedHashSet<>();

        for (JsonNode node : missingNode) {
            int deliveryId =
                    requireDeliveryIdNode(
                            node,
                            "missing deliveryId");

            if (!missing.add(deliveryId)) {
                throw new LupaControlException(
                        "missing deliveryId is duplicated");
            }

            for (AckRange range : ranges) {
                if (range.contains(deliveryId)) {
                    throw new LupaControlException(
                            "deliveryId cannot be both received and missing");
                }
            }
        }

        java.util.List<Integer> acknowledged =
                new java.util.ArrayList<>();

        for (Map.Entry<Integer, DeliveryReservation> entry :
                deliveries.entrySet()) {
            DeliveryReservation reservation = entry.getValue();
            if (reservation.epoch != epoch) continue;

            for (AckRange range : ranges) {
                if (range.contains(entry.getKey())) {
                    acknowledged.add(entry.getKey());
                    break;
                }
            }
        }

        for (Integer deliveryId : acknowledged) {
            acknowledgeDelivery(
                    sender,
                    deliveryId,
                    "ACK_STATE");
        }

        for (Integer deliveryId : missing) {
            requestSelectiveRetransmit(
                    sender,
                    epoch,
                    deliveryId);
        }

        diagnosticCredits(
                "ACK_STATE",
                null,
                "epoch=" + epoch
                        + " receivedRanges=" + ranges.size()
                        + " missing=" + missing.size());

        pump();
    }

    private static int requireDeliveryIdNode(
            JsonNode node,
            String field) throws LupaControlException {
        if (node == null
                || !node.isIntegralNumber()
                || !node.canConvertToInt()) {
            throw new LupaControlException(
                    field + " must be an integer");
        }

        int value = node.intValue();
        if (value < 1 || value > LupaProtocol.MAX_EPOCH) {
            throw new LupaControlException(
                    field + " is outside its allowed range");
        }

        return value;
    }

    private void acknowledgeDelivery(
            Sender sender,
            int deliveryId,
            String source) {
        DeliveryReservation reservation =
                deliveries.remove(deliveryId);

        if (reservation == null) {
            diagnosticCredits(
                    source + "_IGNORED",
                    deliveryId,
                    "unknown delivery");
            return;
        }

        cancelReleaseTimer(reservation);
        reservation.state = DeliveryState.RELEASED;
        freeWindowBytes += reservation.reservedBytes;
        enforceCreditInvariant(sender);

        diagnosticCredits(
                source,
                deliveryId,
                "epoch=" + reservation.epoch
                        + " bytes=" + reservation.reservedBytes);
    }

    private void requestSelectiveRetransmit(
            Sender sender,
            int epoch,
            int deliveryId) {
        DeliveryReservation reservation =
                deliveries.get(deliveryId);

        if (reservation == null
                || reservation.epoch != epoch
                || reservation.state
                        != DeliveryState.WRITTEN_AWAITING_RELEASE) {
            diagnosticCredits(
                    "ACK_MISSING_IGNORED",
                    deliveryId,
                    "epoch=" + epoch);
            return;
        }

        if (reservation.retryInProgress) {
            diagnosticCredits(
                    "ACK_MISSING_DUPLICATE",
                    deliveryId,
                    "retry already in progress");
            return;
        }

        if (reservation.selectiveRetries >= MAX_SELECTIVE_RETRIES) {
            acknowledgeDelivery(
                    sender,
                    deliveryId,
                    "ACK_RETRY_EXHAUSTED");
            sendError(
                    sender,
                    epoch,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "selective recovery retry limit reached for deliveryId "
                            + deliveryId);
            return;
        }

        if (opened == null
                || currentEpoch != epoch
                || activeImageDoesNotMatch(reservation)) {
            acknowledgeDelivery(
                    sender,
                    deliveryId,
                    "ACK_STALE_MISSING");
            return;
        }

        reservation.retryInProgress = true;
        reservation.selectiveRetries++;
        cancelReleaseTimer(reservation);

        PublishedImageStore.OpenedImage imageAtRead = opened;
        ViewPlanner.TileRef ref = reservation.ref;

        try {
            diskExecutor.execute(
                    () -> {
                        TileData tile = null;
                        TileReadException failure = null;
                        try {
                            tile =
                                    tileReader.read(
                                            imageAtRead,
                                            ref.z(),
                                            ref.x(),
                                            ref.y());
                        } catch (TileReadException e) {
                            failure = e;
                        } catch (RuntimeException e) {
                            failure =
                                    new TileReadException(
                                            "unexpected selective retransmit failure",
                                            e);
                        }

                        TileData result = tile;
                        TileReadException error = failure;

                        submitTileResult(
                                () -> onSelectiveTileRead(
                                        deliveryId,
                                        result,
                                        error),
                                result);
                    });
        } catch (RejectedExecutionException e) {
            reservation.retryInProgress = false;
            armReleaseTimer(reservation);
            sendError(
                    sender,
                    epoch,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "server tile read queue is full during selective recovery");
        }
    }

    private boolean activeImageDoesNotMatch(
            DeliveryReservation reservation) {
        return opened == null
                || !opened.catalogImage().imageId()
                        .equals(reservation.imageId)
                || !opened.catalogImage().imageVersion()
                        .equals(reservation.imageVersion);
    }

    private void onSelectiveTileRead(
            int deliveryId,
            TileData tile,
            TileReadException failure) {
        DeliveryReservation reservation =
                deliveries.get(deliveryId);

        if (reservation == null
                || !reservation.retryInProgress
                || reservation.state
                        != DeliveryState.WRITTEN_AWAITING_RELEASE) {
            if (tile != null) tile.close();
            return;
        }

        if (failure != null || tile == null) {
            if (tile != null) tile.close();
            reservation.retryInProgress = false;
            armReleaseTimer(reservation);
            sendError(
                    sender,
                    reservation.epoch,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "selective recovery could not reload deliveryId "
                            + deliveryId);
            return;
        }

        final byte[] header;
        final byte[] envelope;
        final int envelopeBytes;

        try {
            header =
                    buildTileHeaderForReservation(
                            reservation,
                            tile);
            envelopeBytes =
                    Math.addExact(
                            4 + header.length,
                            tile.jpegLength());

            if (envelopeBytes > MAX_TILE_ENVELOPE_BYTES) {
                throw new IllegalArgumentException(
                        "selective retransmit envelope exceeds protocol limit");
            }

            envelope =
                    buildTileEnvelope(
                            header,
                            tile);
        } catch (JsonProcessingException
                 | IllegalArgumentException
                 | ArithmeticException e) {
            tile.close();
            reservation.retryInProgress = false;
            armReleaseTimer(reservation);
            sendError(
                    sender,
                    reservation.epoch,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "selective recovery could not encode deliveryId "
                            + deliveryId);
            return;
        }

        tile.close();

        TransientBufferBudget.Lease transientLease = null;
        if (transientBuffers != null) {
            transientLease =
                    transientBuffers.tryReserve(
                            envelopeBytes);
            if (transientLease == null) {
                reservation.retryInProgress = false;
                armReleaseTimer(reservation);
                sendError(
                        sender,
                        reservation.epoch,
                        LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                        "selective recovery transient buffer budget is full");
                return;
            }
            liveTransientBuffers.add(transientLease);
        }

        final TransientBufferBudget.Lease sendLease =
                transientLease;

        final WebSocketEndpoint.BinarySend write;
        try {
            write =
                    sender.sendBinaryTracked(
                            envelope,
                            () -> {},
                            () -> {
                                releaseTransientBuffer(sendLease);
                                submitSerial(
                                        () -> onSelectiveTileWritten(
                                                deliveryId),
                                        sender);
                            });
        } catch (RuntimeException e) {
            releaseTransientBuffer(sendLease);
            reservation.retryInProgress = false;
            armReleaseTimer(reservation);
            sendError(
                    sender,
                    reservation.epoch,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "selective recovery WebSocket write failed");
            return;
        }

        if (!write.accepted()) {
            releaseTransientBuffer(sendLease);
            reservation.retryInProgress = false;
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(
                    1011,
                    "WebSocket write queue is full during selective recovery");
        }
    }

    private void onSelectiveTileWritten(
            int deliveryId) {
        DeliveryReservation reservation =
                deliveries.get(deliveryId);

        if (reservation == null) return;

        reservation.retryInProgress = false;
        reservation.state =
                DeliveryState.WRITTEN_AWAITING_RELEASE;
        armReleaseTimer(reservation);

        diagnosticCredits(
                "RETRANSMIT",
                deliveryId,
                "retry=" + reservation.selectiveRetries
                        + " epoch=" + reservation.epoch);
    }

    private void pump() {
        if (closed.get()) return;
        ActivePlan plan = activePlan;
        if (plan == null || plan.busy || !plan.planCommitted) return;

        if (plan.pendingTile != null) {
            trySendPending(plan);
            return;
        }

        if (!plan.cursor.hasNext()) {
            if (!plan.doneQueued) {
                plan.doneQueued = true;
                ObjectNode done = json.mapper().createObjectNode();
                done.put("type", "DONE");
                done.put("epoch", plan.view.epoch());
                done.put("sentTiles", plan.sentTiles);
                sendDoneControl(plan, done);
            }
            return;
        }

        if (deliveries.size() >= LupaProtocol.MAX_IN_FLIGHT) return;
        if (plan.readWait != null) return;

        requestTileRead(plan);
    }

    private void requestTileRead(ActivePlan plan) {
        if (tileReadAdmission == null) {
            startTileRead(plan, null);
            return;
        }

        TileReadAdmission.AcquireResult admission =
                tileReadAdmission.acquireOrQueue(
                        diagnosticSessionId,
                        lease -> onReadPermitGrantedAsync(plan.generation, lease));

        if (!admission.accepted()) {
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "server tile read admission queue is full");
            return;
        }

        if (admission.grantedImmediately()) {
            startTileRead(plan, admission.lease());
            return;
        }

        plan.readWait = admission.waitHandle();
    }

    private void onReadPermitGrantedAsync(
            long generation,
            TileReadAdmission.Lease lease) {
        if (closed.get()) {
            lease.close();
            return;
        }
        try {
            serial.execute(() -> onReadPermitGranted(generation, lease));
        } catch (RejectedExecutionException e) {
            lease.close();
            closed.set(true);
            state = LupaSessionState.CERRADA;
            Sender current = sender;
            if (current != null) {
                current.close(1011, "server session queue is full");
            }
        }
    }

    private void onReadPermitGranted(
            long generation,
            TileReadAdmission.Lease lease) {
        ActivePlan plan = activePlan;
        if (closed.get() || plan == null || plan.generation != generation) {
            metrics.recordLateCallbackDiscarded();
            lease.close();
            return;
        }

        plan.readWait = null;
        startTileRead(plan, lease);
    }

    private void startTileRead(
            ActivePlan plan,
            TileReadAdmission.Lease readLease) {
        if (closed.get() || activePlan != plan || plan.busy) {
            if (readLease != null) readLease.close();
            return;
        }

        if (!plan.cursor.hasNext()) {
            if (readLease != null) readLease.close();
            pump();
            return;
        }

        ViewPlanner.TileDescriptor descriptor = plan.cursor.next();
        ViewPlanner.TileRef ref = descriptor.ref();
        boolean openingThumbnail =
                descriptor.role() == ViewPlanner.TileRole.OPENING_THUMBNAIL;
        plan.busy = true;
        tileTurnsGranted++;
        diagnostic(
                "TURN",
                "generation=" + plan.generation
                        + " epoch=" + plan.view.epoch()
                        + " turn=" + tileTurnsGranted);
        long generation = plan.generation;
        PublishedImageStore.OpenedImage imageAtRead = opened;

        try {
            diskExecutor.execute(() -> {
                TileData tile = null;
                TileReadException failure = null;
                int actualCompressedBytes = 0;
                try {
                    tile = tileReader.read(
                            imageAtRead,
                            ref.z(),
                            ref.x(),
                            ref.y());
                    actualCompressedBytes =
                            tile.jpegLength();
                } catch (TileReadException e) {
                    failure = e;
                } catch (RuntimeException e) {
                    failure = new TileReadException(
                            "unexpected tile read failure",
                            e);
                } finally {
                    if (readLease != null) {
                        readLease.complete(
                                actualCompressedBytes);
                    }
                }

                TileData resultTile = tile;
                TileReadException resultFailure = failure;
                submitTileResult(
                        () -> onTileRead(
                                generation,
                                ref,
                                openingThumbnail,
                                resultTile,
                                resultFailure),
                        resultTile);
            });
        } catch (RejectedExecutionException e) {
            if (readLease != null) readLease.close();
            plan.busy = false;
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "server tile read queue is full");
        }
    }

    private void submitTileResult(Runnable task, TileData tile) {
        try {
            serial.execute(task);
        } catch (RejectedExecutionException e) {
            if (tile != null) tile.close();
            closed.set(true);
            state = LupaSessionState.CERRADA;
            Sender current = sender;
            if (current != null) {
                current.close(1011, "server session queue is full");
            }
        }
    }

    private void onTileRead(
            long generation,
            ViewPlanner.TileRef ref,
            boolean openingThumbnail,
            TileData tile,
            TileReadException failure) {
        ActivePlan plan = activePlan;
        if (plan == null || plan.generation != generation) {
            metrics.recordLateCallbackDiscarded();
            if (tile != null) tile.close();
            return;
        }

        plan.busy = false;

        if (failure != null) {
            if (tile != null) tile.close();
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "published tile could not be read");
            return;
        }

        if (tile == null) {
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "published tile read returned no data");
            return;
        }

        plan.pendingTile = new PendingTile(ref, tile, openingThumbnail);
        trySendPending(plan);
    }

    private void trySendPending(ActivePlan plan) {
        if (closed.get()
                || activePlan != plan
                || plan.busy
                || plan.pendingTile == null) {
            return;
        }
        if (deliveries.size() >= LupaProtocol.MAX_IN_FLIGHT) return;
        if (deliveryIds.exhausted()) {
            requireNewSession(
                    plan.view.epoch(),
                    LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                    "deliveryId space is exhausted; reconnect with a new LUPA session");
            return;
        }

        PendingTile pending = plan.pendingTile;
        int deliveryId = deliveryIds.peek();

        final byte[] encodedHeader;
        try {
            encodedHeader = buildTileHeader(
                    deliveryId,
                    plan.view.epoch(),
                    pending.ref,
                    pending.tile);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            discardPendingTile(plan);
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "TILE header could not be encoded");
            return;
        }

        int reservationBytes;
        try {
            reservationBytes = Math.addExact(
                    4 + encodedHeader.length,
                    pending.tile.jpegLength());
        } catch (ArithmeticException e) {
            discardPendingTile(plan);
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                    "TILE envelope size overflow");
            return;
        }

        if (reservationBytes > negotiatedWindowBytes) {
            discardPendingTile(plan);
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                    "single TILE exceeds negotiated window");
            return;
        }
        if (reservationBytes > freeWindowBytes) return;

        TransientBufferBudget.Lease transientLease = null;
        if (transientBuffers != null) {
            transientLease = transientBuffers.tryReserve(reservationBytes);
            if (transientLease == null) {
                discardPendingTile(plan);
                failPlan(
                        plan,
                        LupaProtocol.ErrorCode.LIMIT_EXCEEDED,
                        "server transient compressed-buffer budget is full");
                return;
            }
            liveTransientBuffers.add(transientLease);
        }

        final byte[] envelope;
        try {
            envelope = buildTileEnvelope(encodedHeader, pending.tile);
        } catch (RuntimeException e) {
            releaseTransientBuffer(transientLease);
            discardPendingTile(plan);
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "TILE envelope could not be built");
            return;
        }

        pending.tile.close();

        int allocatedDeliveryId = deliveryIds.take();
        if (allocatedDeliveryId != deliveryId) {
            releaseTransientBuffer(transientLease);
            throw new IllegalStateException(
                    "deliveryId sequence changed inside serialized session state");
        }

        DeliveryReservation reservation = new DeliveryReservation(
                deliveryId,
                plan.view.epoch(),
                plan.generation,
                reservationBytes,
                pending.openingThumbnail(),
                pending.ref,
                opened.catalogImage().imageId(),
                opened.catalogImage().imageVersion(),
                transientLease);
        deliveries.put(deliveryId, reservation);
        freeWindowBytes -= reservationBytes;
        plan.pendingTile = null;
        plan.busy = true;
        enforceCreditInvariant(sender);
        diagnosticCredits(
                "RESERVE",
                deliveryId,
                "epoch=" + plan.view.epoch() + " bytes=" + reservationBytes);

        if (closed.get()) return;

        final TransientBufferBudget.Lease sendLease = transientLease;
        final WebSocketEndpoint.BinarySend write;
        try {
            write = sender.sendBinaryTracked(
                    envelope,
                    () -> submitSerial(() -> onTileCommitted(deliveryId), sender),
                    () -> {
                        releaseTransientBuffer(sendLease);
                        submitSerial(
                                () -> onTileWritten(plan.generation, deliveryId),
                                sender);
                    });
        } catch (RuntimeException e) {
            DeliveryReservation removed = deliveries.remove(deliveryId);
            if (removed != null) {
                removed.state = DeliveryState.CANCELLED;
                freeWindowBytes += removed.reservedBytes;
            }
            releaseTransientBuffer(sendLease);
            plan.busy = false;
            enforceCreditInvariant(sender);
            failPlan(
                    plan,
                    LupaProtocol.ErrorCode.INTERNAL_READ_ERROR,
                    "WebSocket TILE write failed");
            return;
        }

        reservation.write = write;
        if (!write.accepted()) {
            DeliveryReservation removed = deliveries.remove(deliveryId);
            if (removed != null) {
                removed.state = DeliveryState.CANCELLED;
                freeWindowBytes += removed.reservedBytes;
            }
            releaseTransientBuffer(sendLease);
            plan.busy = false;
            enforceCreditInvariant(sender);
            closed.set(true);
            state = LupaSessionState.CERRADA;
            sender.close(1011, "WebSocket write queue is full");
        }
    }

    private void onTileCommitted(int deliveryId) {
        DeliveryReservation reservation = deliveries.get(deliveryId);
        if (reservation == null) return; // RELEASE may have arrived first in a controlled test.
        if (reservation.state != DeliveryState.RESERVED_QUEUED) return;
        reservation.state = DeliveryState.COMMITTED;
        if (reservation.openingThumbnail) thumbnailCommittedForOpen = true;
    }

    private void onTileWritten(long generation, int deliveryId) {
        DeliveryReservation reservation = deliveries.get(deliveryId);
        if (reservation != null
                && (reservation.state == DeliveryState.COMMITTED
                    || reservation.state == DeliveryState.RESERVED_QUEUED)) {
            reservation.state = DeliveryState.WRITTEN_AWAITING_RELEASE;
            armReleaseTimer(reservation);
        }

        ActivePlan plan = activePlan;
        if (plan == null || plan.generation != generation) return;
        plan.busy = false;
        plan.sentTiles++;
        pump();
    }

    private byte[] buildTileHeader(
            int deliveryId,
            int epoch,
            ViewPlanner.TileRef ref,
            TileData tile) throws JsonProcessingException {
        if (tile.jpegLength() > LupaProtocol.MAX_TILE_BYTES) {
            throw new IllegalArgumentException(
                    "tile payload exceeds LUPA v1 limit");
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
        header.put("payloadBytes", tile.jpegLength());

        byte[] encodedHeader = json.mapper().writeValueAsBytes(header);
        if (encodedHeader.length > 4096) {
            throw new IllegalArgumentException(
                    "TILE header exceeds LUPA v1 limit");
        }
        return encodedHeader;
    }

    private byte[] buildTileHeaderForReservation(
            DeliveryReservation reservation,
            TileData tile) throws JsonProcessingException {
        if (tile.jpegLength() > LupaProtocol.MAX_TILE_BYTES) {
            throw new IllegalArgumentException(
                    "tile payload exceeds LUPA v1 limit");
        }

        ObjectNode header =
                json.mapper().createObjectNode();
        header.put("type", "TILE");
        header.put(
                "deliveryId",
                reservation.deliveryId);
        header.put("epoch", reservation.epoch);
        header.put("imageId", reservation.imageId);
        header.put(
                "imageVersion",
                reservation.imageVersion);
        header.put("z", reservation.ref.z());
        header.put("x", reservation.ref.x());
        header.put("y", reservation.ref.y());
        header.put("w", tile.width());
        header.put("h", tile.height());
        header.put("codec", "jpeg");
        header.put(
                "payloadBytes",
                tile.jpegLength());
        header.put(
                "retry",
                reservation.selectiveRetries);

        byte[] encodedHeader =
                json.mapper().writeValueAsBytes(header);
        if (encodedHeader.length > 4096) {
            throw new IllegalArgumentException(
                    "TILE header exceeds LUPA v1 limit");
        }
        return encodedHeader;
    }

    private byte[] buildTileEnvelope(
            byte[] encodedHeader,
            TileData tile) {
        int total = Math.addExact(
                4 + encodedHeader.length,
                tile.jpegLength());
        if (total > MAX_TILE_ENVELOPE_BYTES) {
            throw new IllegalArgumentException(
                    "TILE envelope exceeds LUPA v1 limit");
        }

        ByteBuffer message = ByteBuffer.allocate(total);
        message.putInt(encodedHeader.length);
        message.put(encodedHeader);
        tile.copyJpegTo(message);
        return message.array();
    }

    private void failPlan(ActivePlan plan, LupaProtocol.ErrorCode code, String message) {
        if (activePlan != plan) return;
        activePlan = null;
        planGeneration++;
        discardPlanResources(plan);
        cancelUncommittedControl(plan.planWrite);
        cancelUncommittedControl(plan.doneWrite);
        cancelUncommittedDeliveries(plan.generation);
        sendError(sender, plan.view.epoch(), code, message);
    }

    private void requireNewSession(
            Integer epoch,
            LupaProtocol.ErrorCode code,
            String message) {
        sendError(sender, epoch, code, message);
        if (closed.compareAndSet(false, true)) {
            state = LupaSessionState.CERRADA;
            sender.close(1008, boundedMessage(message));
        }
    }

    private void invalidatePlan() {
        ActivePlan invalidated = activePlan;
        activePlan = null;
        planGeneration++;
        if (invalidated != null) {
            discardPlanResources(invalidated);
            cancelUncommittedControl(invalidated.planWrite);
            cancelUncommittedControl(invalidated.doneWrite);
            cancelUncommittedDeliveries(invalidated.generation);
        }
    }

    private static void cancelUncommittedControl(WebSocketEndpoint.TrackedSend write) {
        if (write != null) write.cancelIfNotCommitted();
    }

    private void cancelUncommittedDeliveries(long generation) {
        var iterator = deliveries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, DeliveryReservation> entry = iterator.next();
            DeliveryReservation reservation = entry.getValue();
            if (reservation.planGeneration != generation) continue;
            WebSocketEndpoint.BinarySend write = reservation.write;
            if (write == null || !write.cancelIfNotCommitted()) continue;

            cancelReleaseTimer(reservation);
            reservation.state = DeliveryState.CANCELLED;
            freeWindowBytes += reservation.reservedBytes;
            releaseTransientBuffer(reservation.transientBuffer);
            iterator.remove();
            diagnosticCredits("CANCEL_PRECOMMIT", entry.getKey(),
                    "epoch=" + reservation.epoch
                            + " bytes=" + reservation.reservedBytes);
        }
        enforceCreditInvariant(sender);
    }

    private void onHelloTimeout() {
        if (closed.get()
                || state != LupaSessionState.ESPERA_HELLO) {
            return;
        }
        metrics.recordHelloTimeout();
        closeForTimeout("HELLO timeout");
    }

    private void armReleaseTimer(
            DeliveryReservation reservation) {
        if (scheduler == null || releaseTimeout == null) return;

        cancelReleaseTimer(reservation);
        reservation.releaseDeadlineStartedNanos =
                scheduler.nowNanos();
        MonotonicScheduler.Handle timer =
                scheduler.schedule(
                        releaseTimeout,
                        () -> submitSerial(
                                () -> onReleaseTimeout(
                                        reservation.deliveryId),
                                sender));
        reservation.releaseTimer = timer;
        liveReleaseTimers.add(timer);
    }

    private void onReleaseTimeout(int deliveryId) {
        DeliveryReservation reservation =
                deliveries.get(deliveryId);

        if (closed.get()
                || reservation == null
                || reservation.state
                        != DeliveryState.WRITTEN_AWAITING_RELEASE) {
            return;
        }

        metrics.recordReleaseTimeout();
        diagnosticCredits(
                "RELEASE_TIMEOUT",
                deliveryId,
                "epoch=" + reservation.epoch
                        + " startedNanos="
                        + reservation.releaseDeadlineStartedNanos);

        closeForTimeout(
                "delivery " + deliveryId
                        + " RELEASE timeout");
    }

    private void closeForTimeout(String reason) {
        if (!closed.compareAndSet(false, true)) return;

        state = LupaSessionState.CERRADA;
        cancelHelloTimer();

        ActivePlan closingPlan = activePlan;
        activePlan = null;
        discardPlanResources(closingPlan);

        cancelAllReleaseTimers();
        releaseAllTransientBuffers();
        deliveries.clear();
        freeWindowBytes = 0;
        releaseAdmission();

        Sender current = sender;
        if (current != null) {
            current.close(
                    1008,
                    boundedMessage(reason));
        }
    }

    private void cancelHelloTimer() {
        MonotonicScheduler.Handle timer = helloTimer;
        helloTimer = null;
        if (timer != null) timer.cancel();
    }

    private void cancelReleaseTimer(
            DeliveryReservation reservation) {
        MonotonicScheduler.Handle timer =
                reservation.releaseTimer;
        reservation.releaseTimer = null;
        if (timer != null) {
            liveReleaseTimers.remove(timer);
            timer.cancel();
        }
    }

    private void cancelAllReleaseTimers() {
        for (MonotonicScheduler.Handle timer :
                liveReleaseTimers.toArray(
                        MonotonicScheduler.Handle[]::new)) {
            liveReleaseTimers.remove(timer);
            timer.cancel();
        }
    }

    private static Duration requirePositive(
            Duration value,
            String name) {
        Objects.requireNonNull(value);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(
                    name + " must be positive");
        }
        return value;
    }

    private void discardPendingTile(ActivePlan plan) {
        if (plan == null || plan.pendingTile == null) return;
        TileData tile = plan.pendingTile.tile;
        plan.pendingTile = null;
        tile.close();
    }

    private void discardPlanResources(ActivePlan plan) {
        if (plan == null) return;
        TileReadAdmission.WaitHandle wait = plan.readWait;
        plan.readWait = null;
        if (wait != null) wait.cancel();
        discardPendingTile(plan);
    }

    private void releaseTransientBuffer(TransientBufferBudget.Lease lease) {
        if (lease == null) return;
        liveTransientBuffers.remove(lease);
        lease.close();
    }

    private void releaseAllTransientBuffers() {
        for (TransientBufferBudget.Lease lease :
                liveTransientBuffers.toArray(TransientBufferBudget.Lease[]::new)) {
            releaseTransientBuffer(lease);
        }
    }

    private void enforceCreditInvariant(Sender sender) {
        long reserved = 0;
        for (DeliveryReservation reservation : deliveries.values()) {
            reserved += reservation.reservedBytes;
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

    private void diagnostic(String event, String detail) {
        if (!I21_DIAGNOSTICS) return;
        System.out.printf(
                "I21_DIAG session=%d event=%s %s%n",
                diagnosticSessionId,
                event,
                detail == null ? "" : detail);
    }

    private void diagnosticCredits(String event, Integer deliveryId, String detail) {
        if (!I21_DIAGNOSTICS) return;
        long reserved = 0;
        for (DeliveryReservation reservation : deliveries.values()) {
            reserved += reservation.reservedBytes;
        }
        System.out.printf(
                "I21_DIAG session=%d event=%s%s epoch=%d free=%d reserved=%d pending=%d window=%d invariant=%s %s%n",
                diagnosticSessionId,
                event,
                deliveryId == null ? "" : " delivery=" + deliveryId,
                currentEpoch,
                freeWindowBytes,
                reserved,
                deliveries.size(),
                negotiatedWindowBytes,
                negotiatedWindowBytes == 0 || freeWindowBytes + reserved == negotiatedWindowBytes ? "OK" : "FAIL",
                detail == null ? "" : detail);
    }

    private static String boundedMessage(String message) {
        if (message == null || message.isBlank()) return "LUPA request rejected";
        return message.length() <= 240 ? message : message.substring(0, 240);
    }

    SessionSnapshot snapshotForTest() {
        long reserved = 0;
        for (DeliveryReservation reservation : deliveries.values()) {
            reserved += reservation.reservedBytes;
        }
        return new SessionSnapshot(
                state,
                currentEpoch,
                negotiatedWindowBytes,
                freeWindowBytes,
                reserved,
                deliveries.size(),
                deliveryIds.nextValue(),
                tileTurnsGranted,
                opened == null ? null : opened.catalogImage().imageId(),
                activePlan == null ? null : activePlan.view.epoch());
    }

    record SessionSnapshot(
            LupaSessionState state,
            int currentEpoch,
            int negotiatedWindowBytes,
            long freeWindowBytes,
            long reservedBytes,
            int pendingDeliveries,
            long nextDeliveryId,
            long tileTurnsGranted,
            String openedImageId,
            Integer activePlanEpoch) {}

    private record AckRange(
            int start,
            int end) {
        private boolean contains(int deliveryId) {
            return deliveryId >= start
                    && deliveryId <= end;
        }
    }

    private static final class ActivePlan {
        private final long generation;
        private final ViewRequest view;
        private final ViewPlanner.Plan selection;
        private final PlannedTileCursor cursor;
        private boolean planCommitted;
        private WebSocketEndpoint.TrackedSend planWrite;
        private boolean doneQueued;
        private WebSocketEndpoint.TrackedSend doneWrite;
        private boolean busy;
        private int sentTiles;
        private PendingTile pendingTile;
        private TileReadAdmission.WaitHandle readWait;

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

    private enum DeliveryState {
        RESERVED_QUEUED,
        COMMITTED,
        WRITTEN_AWAITING_RELEASE,
        RELEASED,
        CANCELLED
    }

    private static final class DeliveryReservation {
        private final int deliveryId;
        private final int epoch;
        private final long planGeneration;
        private final int reservedBytes;
        private final boolean openingThumbnail;
        private final ViewPlanner.TileRef ref;
        private final String imageId;
        private final String imageVersion;
        private final TransientBufferBudget.Lease transientBuffer;
        private DeliveryState state = DeliveryState.RESERVED_QUEUED;
        private int selectiveRetries;
        private boolean retryInProgress;
        private WebSocketEndpoint.BinarySend write;
        private MonotonicScheduler.Handle releaseTimer;
        private long releaseDeadlineStartedNanos;

        private DeliveryReservation(
                int deliveryId,
                int epoch,
                long planGeneration,
                int reservedBytes,
                boolean openingThumbnail,
                ViewPlanner.TileRef ref,
                String imageId,
                String imageVersion,
                TransientBufferBudget.Lease transientBuffer) {
            this.deliveryId = deliveryId;
            this.epoch = epoch;
            this.planGeneration = planGeneration;
            this.reservedBytes = reservedBytes;
            this.openingThumbnail = openingThumbnail;
            this.ref = Objects.requireNonNull(ref);
            this.imageId = Objects.requireNonNull(imageId);
            this.imageVersion = Objects.requireNonNull(imageVersion);
            this.transientBuffer = transientBuffer;
        }
    }
}
