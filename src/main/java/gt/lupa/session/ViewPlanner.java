package gt.lupa.session;

import gt.lupa.protocol.LupaProtocol;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic E21 planner. It operates only on validated VIEW data and immutable manifest metadata.
 * No disk or network work happens here.
 */
public final class ViewPlanner {
    public enum TileRole {
        OPENING_THUMBNAIL,
        CONTEXT_MINIMUM,
        FOCUS,
        VISIBLE_CONTEXT,
        VISIBLE_UNIFORM
    }

    public record TileRef(int z, int x, int y) {}

    public record TileDescriptor(TileRef ref, TileRole role) {}

    public record Plan(
            int epoch,
            int automaticLevel,
            int appliedLevel,
            int contextLevel,
            long bitmapBytes,
            List<TileDescriptor> tiles) {
        public Plan {
            tiles = List.copyOf(tiles);
        }
    }

    public Plan plan(
            ImageManifest manifest,
            ViewRequest request,
            long bitmapBudgetBytes,
            boolean includeOpeningThumbnail) throws PlanningException {
        Objects.requireNonNull(manifest);
        Objects.requireNonNull(request);

        if (bitmapBudgetBytes < LupaProtocol.BITMAP_RESERVED_BYTES) {
            throw new PlanningException(
                    "bitmapBudgetBytes must reserve at least "
                            + LupaProtocol.BITMAP_RESERVED_BYTES + " bytes");
        }

        int automatic = automaticLevel(manifest, request);
        int requestedApplied = Math.max(0, automatic + request.detailOffset());

        for (int candidate = requestedApplied; candidate >= 0; candidate--) {
            Plan candidatePlan = buildPlan(manifest, request, automatic, candidate, includeOpeningThumbnail);
            if (candidatePlan.tiles().size() > LupaProtocol.MAX_SELECTION_DESCRIPTORS) {
                continue;
            }
            long usable = bitmapBudgetBytes - LupaProtocol.BITMAP_RESERVED_BYTES;
            if (candidatePlan.bitmapBytes() <= usable) {
                return candidatePlan;
            }
        }

        throw new PlanningException("VIEW cannot fit the negotiated bitmap budget");
    }

    static int automaticLevel(ImageManifest manifest, ViewRequest request) {
        ViewRequest.Rect rect = request.rect();
        ViewRequest.Viewport viewport = request.viewportPx();

        for (ImageLevel level : manifest.levels()) {
            long availableX = Math.multiplyExact((long) rect.width(), (long) level.width());
            long requiredX = Math.multiplyExact((long) viewport.width(), (long) manifest.width());
            long availableY = Math.multiplyExact((long) rect.height(), (long) level.height());
            long requiredY = Math.multiplyExact((long) viewport.height(), (long) manifest.height());
            if (availableX >= requiredX && availableY >= requiredY) {
                return level.z();
            }
        }
        return manifest.levels().getLast().z();
    }

    private Plan buildPlan(
            ImageManifest manifest,
            ViewRequest request,
            int automaticLevel,
            int appliedLevel,
            boolean includeOpeningThumbnail) {
        LinkedHashMap<TileRef, TileDescriptor> ordered = new LinkedHashMap<>();

        if (includeOpeningThumbnail) {
            add(ordered, new TileRef(0, 0, 0), TileRole.OPENING_THUMBNAIL);
        }

        int contextLevel = appliedLevel;
        int effectiveAppliedLevel = appliedLevel;

        if (request.mode() == ViewRequest.Mode.UNIFORM) {
            for (TileRef ref : visibleTiles(manifest, appliedLevel, request.rect())) {
                add(ordered, ref, TileRole.VISIBLE_UNIFORM);
            }
        } else {
            contextLevel = Math.max(0, appliedLevel - 1);
            List<TileRef> context = visibleTiles(manifest, contextLevel, request.rect());
            List<TileRef> focus = focusTiles(manifest, appliedLevel, request);

            if (focus.isEmpty()) {
                effectiveAppliedLevel = contextLevel;
                for (TileRef ref : context) {
                    add(ordered, ref, TileRole.VISIBLE_CONTEXT);
                }
            } else {
                TileRef minimum = firstContextTileForFocus(manifest, request, contextLevel, context);
                if (minimum != null) {
                    add(ordered, minimum, TileRole.CONTEXT_MINIMUM);
                }
                for (TileRef ref : focus) {
                    add(ordered, ref, TileRole.FOCUS);
                }
                for (TileRef ref : context) {
                    add(ordered, ref, TileRole.VISIBLE_CONTEXT);
                }
            }
        }

        List<TileDescriptor> descriptors = List.copyOf(ordered.values());
        long bitmapBytes = 0;
        for (TileDescriptor descriptor : descriptors) {
            if (descriptor.ref().z() == 0) {
                // z=0 is the retained thumbnail/base context covered by the 16 MiB reserve.
                continue;
            }
            bitmapBytes = Math.addExact(bitmapBytes, bitmapBytes(manifest, descriptor.ref()));
        }

        return new Plan(
                request.epoch(),
                automaticLevel,
                effectiveAppliedLevel,
                contextLevel,
                bitmapBytes,
                descriptors);
    }

    private static void add(
            Map<TileRef, TileDescriptor> ordered,
            TileRef ref,
            TileRole role) {
        ordered.putIfAbsent(ref, new TileDescriptor(ref, role));
    }

    static List<TileRef> visibleTiles(
            ImageManifest manifest,
            int levelZ,
            ViewRequest.Rect rect) {
        ImageLevel level = manifest.levels().get(levelZ);
        long levelX0 = ((long) rect.x() * level.width()) / manifest.width();
        long levelY0 = ((long) rect.y() * level.height()) / manifest.height();
        long levelX1 = ceilDiv(rect.x1() * level.width(), manifest.width());
        long levelY1 = ceilDiv(rect.y1() * level.height(), manifest.height());

        levelX0 = clamp(levelX0, 0, level.width() - 1L);
        levelY0 = clamp(levelY0, 0, level.height() - 1L);
        levelX1 = clamp(levelX1, levelX0 + 1, level.width());
        levelY1 = clamp(levelY1, levelY0 + 1, level.height());

        int tileSize = manifest.tileSize();
        int xStart = (int) (levelX0 / tileSize);
        int xEnd = (int) ((levelX1 - 1L) / tileSize);
        int yStart = (int) (levelY0 / tileSize);
        int yEnd = (int) ((levelY1 - 1L) / tileSize);

        List<TileRef> refs = new ArrayList<>();
        for (int y = yStart; y <= yEnd; y++) {
            for (int x = xStart; x <= xEnd; x++) {
                refs.add(new TileRef(levelZ, x, y));
            }
        }
        return refs;
    }

    static List<TileRef> focusTiles(
            ImageManifest manifest,
            int levelZ,
            ViewRequest request) {
        if (request.focus() == null) return List.of();

        List<TileRef> refs = new ArrayList<>();
        for (TileRef ref : visibleTiles(manifest, levelZ, request.rect())) {
            if (intersectsFocus(manifest, ref, request)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    private static TileRef firstContextTileForFocus(
            ImageManifest manifest,
            ViewRequest request,
            int contextLevel,
            List<TileRef> context) {
        for (TileRef ref : context) {
            if (intersectsFocus(manifest, ref, request)) {
                return ref;
            }
        }
        if (contextLevel == 0 && !context.isEmpty()) return context.getFirst();
        return null;
    }

    static boolean intersectsFocus(
            ImageManifest manifest,
            TileRef ref,
            ViewRequest request) {
        ViewRequest.Focus focus = request.focus();
        if (focus == null) return false;

        ImageLevel level = manifest.levels().get(ref.z());
        int tileSize = manifest.tileSize();
        int levelX0 = ref.x() * tileSize;
        int levelY0 = ref.y() * tileSize;
        int tileWidth = Math.min(tileSize, level.width() - levelX0);
        int tileHeight = Math.min(tileSize, level.height() - levelY0);

        double originalX0 = ((double) levelX0 * manifest.width()) / level.width();
        double originalY0 = ((double) levelY0 * manifest.height()) / level.height();
        double originalX1 = ((double) (levelX0 + tileWidth) * manifest.width()) / level.width();
        double originalY1 = ((double) (levelY0 + tileHeight) * manifest.height()) / level.height();

        ViewRequest.Rect rect = request.rect();
        double clippedX0 = Math.max(originalX0, rect.x());
        double clippedY0 = Math.max(originalY0, rect.y());
        double clippedX1 = Math.min(originalX1, rect.x1());
        double clippedY1 = Math.min(originalY1, rect.y1());
        if (clippedX1 <= clippedX0 || clippedY1 <= clippedY0) return false;

        double radiusX = ((double) focus.radiusPx() * rect.width()) / request.viewportPx().width();
        double radiusY = ((double) focus.radiusPx() * rect.height()) / request.viewportPx().height();

        double nearestX = clamp(focus.x(), clippedX0, clippedX1);
        double nearestY = clamp(focus.y(), clippedY0, clippedY1);
        double dx = (nearestX - focus.x()) / radiusX;
        double dy = (nearestY - focus.y()) / radiusY;
        return dx * dx + dy * dy <= 1.0;
    }

    static long bitmapBytes(ImageManifest manifest, TileRef ref) {
        ImageLevel level = manifest.levels().get(ref.z());
        int tileSize = manifest.tileSize();
        int x0 = ref.x() * tileSize;
        int y0 = ref.y() * tileSize;
        int width = Math.min(tileSize, level.width() - x0);
        int height = Math.min(tileSize, level.height() - y0);
        return Math.multiplyExact(4L, Math.multiplyExact((long) width, (long) height));
    }

    private static long ceilDiv(long numerator, long denominator) {
        return Math.floorDiv(numerator + denominator - 1L, denominator);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(value, max));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    public static final class PlanningException extends Exception {
        public PlanningException(String message) {
            super(message);
        }
    }
}
