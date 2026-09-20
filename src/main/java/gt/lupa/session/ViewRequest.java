package gt.lupa.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import gt.lupa.protocol.LupaControlException;
import gt.lupa.protocol.LupaJson;
import gt.lupa.protocol.LupaProtocol;
import gt.lupa.storage.CatalogException;
import gt.lupa.storage.CatalogValidator;

import java.util.Locale;

/**
 * Fully validated VIEW intent. Construction happens before any session state is mutated.
 */
public record ViewRequest(
        int epoch,
        String imageId,
        String imageVersion,
        Rect rect,
        Viewport viewportPx,
        int detailOffset,
        Mode mode,
        Focus focus) {

    public enum Mode {
        UNIFORM,
        FOCUS
    }

    public record Rect(int x, int y, int width, int height) {
        long x1() {
            return (long) x + width;
        }

        long y1() {
            return (long) y + height;
        }
    }

    public record Viewport(int width, int height) {}

    /**
     * x/y use original-image coordinates. radiusPx uses physical viewport pixels.
     */
    public record Focus(int x, int y, int radiusPx) {}

    public static ViewRequest parse(
            ObjectNode control,
            String openedImageId,
            String openedImageVersion,
            int imageWidth,
            int imageHeight) throws LupaControlException {
        LupaJson.requireOnly(
                control,
                "type", "epoch", "imageId", "imageVersion", "rect",
                "viewportPx", "detailOffset", "mode", "focus");

        int epoch = LupaJson.requireInt(control, "epoch", 1, LupaProtocol.MAX_EPOCH);
        String imageId = LupaJson.requireText(control, "imageId");
        String imageVersion = LupaJson.requireText(control, "imageVersion");

        try {
            CatalogValidator.validateId(imageId);
            CatalogValidator.validateVersion(imageVersion);
        } catch (CatalogException e) {
            throw new LupaControlException("VIEW image identifier or version is invalid", e);
        }

        if (!imageId.equals(openedImageId) || !imageVersion.equals(openedImageVersion)) {
            throw new LupaControlException("VIEW does not match the opened image version");
        }

        ObjectNode rectNode = LupaJson.requireObject(control, "rect");
        LupaJson.requireOnly(rectNode, "x", "y", "width", "height");
        int x = LupaJson.requireInt(rectNode, "x", 0, Integer.MAX_VALUE);
        int y = LupaJson.requireInt(rectNode, "y", 0, Integer.MAX_VALUE);
        int width = LupaJson.requireInt(rectNode, "width", 1, Integer.MAX_VALUE);
        int height = LupaJson.requireInt(rectNode, "height", 1, Integer.MAX_VALUE);
        Rect rect = new Rect(x, y, width, height);
        if (rect.x1() > imageWidth || rect.y1() > imageHeight) {
            throw new LupaControlException("rect must be fully contained in the opened image");
        }

        ObjectNode viewportNode = LupaJson.requireObject(control, "viewportPx");
        LupaJson.requireOnly(viewportNode, "width", "height");
        int viewportWidth = LupaJson.requireInt(viewportNode, "width", 1, Integer.MAX_VALUE);
        int viewportHeight = LupaJson.requireInt(viewportNode, "height", 1, Integer.MAX_VALUE);
        long viewportPixels = Math.multiplyExact((long) viewportWidth, (long) viewportHeight);
        if (viewportPixels > LupaProtocol.MAX_VIEWPORT_PIXELS) {
            throw new LupaControlException(
                    "viewportPx exceeds " + LupaProtocol.MAX_VIEWPORT_PIXELS + " pixels");
        }

        int detailOffset = LupaJson.requireInt(control, "detailOffset", -2, 0);
        String rawMode = LupaJson.requireText(control, "mode");
        final Mode mode;
        try {
            mode = Mode.valueOf(rawMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new LupaControlException("mode must be uniform or focus");
        }

        JsonNode focusNode = control.get("focus");
        Focus focus;
        if (mode == Mode.UNIFORM) {
            if (focusNode == null || !focusNode.isNull()) {
                throw new LupaControlException("focus must be null in uniform mode");
            }
            focus = null;
        } else {
            if (!(focusNode instanceof ObjectNode focusObject)) {
                throw new LupaControlException("focus must be an object in focus mode");
            }
            LupaJson.requireOnly(focusObject, "x", "y", "radiusPx");
            int focusX = LupaJson.requireInt(focusObject, "x", 0, imageWidth - 1);
            int focusY = LupaJson.requireInt(focusObject, "y", 0, imageHeight - 1);
            int radiusPx = LupaJson.requireInt(
                    focusObject, "radiusPx", 1, LupaProtocol.MAX_FOCUS_RADIUS_PX);
            focus = new Focus(focusX, focusY, radiusPx);
        }

        return new ViewRequest(
                epoch,
                imageId,
                imageVersion,
                rect,
                new Viewport(viewportWidth, viewportHeight),
                detailOffset,
                mode,
                focus);
    }
}
