package gt.lupa.session;

import com.fasterxml.jackson.databind.node.ObjectNode;
import gt.lupa.protocol.LupaControlException;
import gt.lupa.protocol.LupaJson;
import gt.lupa.protocol.LupaProtocol;
import gt.lupa.storage.ImageLevel;
import gt.lupa.storage.ImageManifest;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ViewPlannerTest {
    private final ViewPlanner planner = new ViewPlanner();
    private final LupaJson json = new LupaJson();

    @Test
    void oddDimensionsUseExactManifestLevelsAndDetailOffsets() throws Exception {
        ImageManifest manifest = oddManifest();

        ViewPlanner.Plan full = planner.plan(
                manifest,
                request(1, new ViewRequest.Rect(0, 0, 1001, 777),
                        new ViewRequest.Viewport(500, 388), 0),
                64L * 1024 * 1024,
                false);
        assertEquals(2, full.automaticLevel());
        assertEquals(2, full.appliedLevel());
        assertEquals(2, full.contextLevel());

        ViewPlanner.Plan minusOne = planner.plan(
                manifest,
                request(2, new ViewRequest.Rect(0, 0, 1001, 777),
                        new ViewRequest.Viewport(500, 388), -1),
                64L * 1024 * 1024,
                false);
        assertEquals(1, minusOne.appliedLevel());

        ViewPlanner.Plan minusTwo = planner.plan(
                manifest,
                request(3, new ViewRequest.Rect(0, 0, 1001, 777),
                        new ViewRequest.Viewport(500, 388), -2),
                64L * 1024 * 1024,
                false);
        assertEquals(0, minusTwo.appliedLevel());
    }

    @Test
    void exactTileBoundaryDoesNotSelectExtraColumnOrRow() {
        ImageManifest manifest = regularManifest();
        List<ViewPlanner.TileRef> refs = ViewPlanner.visibleTiles(
                manifest, 2, new ViewRequest.Rect(0, 0, 256, 256));

        assertEquals(List.of(new ViewPlanner.TileRef(2, 0, 0)), refs);

        refs = ViewPlanner.visibleTiles(
                manifest, 2, new ViewRequest.Rect(768, 512, 256, 256));
        assertEquals(List.of(new ViewPlanner.TileRef(2, 3, 2)), refs);
    }

    @Test
    void focusUsesAnEllipseWhenViewportScalesDiffer() throws Exception {
        ImageManifest manifest = new ImageManifest(
                1, "photo", "v1", 1000, 500, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 250, 125),
                        new ImageLevel(1, 500, 250),
                        new ImageLevel(2, 1000, 500)));

        ViewRequest request = new ViewRequest(
                1, "photo", "v1",
                new ViewRequest.Rect(0, 0, 1000, 500),
                new ViewRequest.Viewport(1000, 1000),
                0,
                ViewRequest.Mode.FOCUS,
                new ViewRequest.Focus(500, 250, 100));

        List<ViewPlanner.TileRef> focus = ViewPlanner.focusTiles(manifest, 2, request);
        assertEquals(
                Set.of(
                        new ViewPlanner.TileRef(2, 1, 0),
                        new ViewPlanner.TileRef(2, 2, 0),
                        new ViewPlanner.TileRef(2, 1, 1),
                        new ViewPlanner.TileRef(2, 2, 1)),
                Set.copyOf(focus));
    }

    @Test
    void focusOutsideVisibleRectDoesNotCreateDetailWork() throws Exception {
        ImageManifest manifest = regularManifest();
        ViewRequest request = new ViewRequest(
                7, "photo", "v1",
                new ViewRequest.Rect(0, 0, 400, 300),
                new ViewRequest.Viewport(800, 600),
                0,
                ViewRequest.Mode.FOCUS,
                new ViewRequest.Focus(900, 700, 40));

        ViewPlanner.Plan plan = planner.plan(
                manifest, request, 64L * 1024 * 1024, false);

        assertEquals(plan.contextLevel(), plan.appliedLevel(),
                "when the clipped focus has no intersection, PLAN must not announce unused detail");
        assertTrue(plan.tiles().stream().noneMatch(t -> t.role() == ViewPlanner.TileRole.FOCUS));
    }

    @Test
    void bitmapBudgetReducesWholePlanInsteadOfTruncatingTiles() throws Exception {
        ImageManifest manifest = squareManifest();
        ViewRequest request = new ViewRequest(
                1, "photo", "v1",
                new ViewRequest.Rect(0, 0, 4096, 4096),
                new ViewRequest.Viewport(2048, 2048),
                0,
                ViewRequest.Mode.UNIFORM,
                null);

        ViewPlanner.Plan plan = planner.plan(
                manifest,
                request,
                LupaProtocol.BITMAP_RESERVED_BYTES + 5L * 1024 * 1024,
                false);

        assertEquals(3, plan.automaticLevel());
        assertEquals(2, plan.appliedLevel(),
                "z=3 needs 16 MiB of managed bitmaps; z=2 needs only 4 MiB");
        assertEquals(16, plan.tiles().size());
        assertEquals(4L * 1024 * 1024, plan.bitmapBytes());
    }

    @Test
    void descriptorLimitAlsoReducesLevelDeterministically() throws Exception {
        ImageManifest manifest = largeManifest();
        ViewRequest request = new ViewRequest(
                1, "photo", "v1",
                new ViewRequest.Rect(0, 0, 40000, 30131),
                new ViewRequest.Viewport(40000, 30131),
                0,
                ViewRequest.Mode.UNIFORM,
                null);

        ViewPlanner.Plan plan = planner.plan(
                manifest,
                request,
                512L * 1024 * 1024,
                false);

        assertEquals(8, plan.automaticLevel());
        assertEquals(5, plan.appliedLevel());
        assertEquals(300, plan.tiles().size());
        assertTrue(plan.tiles().size() <= LupaProtocol.MAX_SELECTION_DESCRIPTORS);
    }

    @Test
    void focusOrderingIsMinimumContextThenFocusThenRemainingContextWithoutDuplicates() throws Exception {
        ImageManifest manifest = regularManifest();
        ViewRequest request = new ViewRequest(
                4, "photo", "v1",
                new ViewRequest.Rect(0, 0, 1024, 768),
                new ViewRequest.Viewport(1024, 768),
                0,
                ViewRequest.Mode.FOCUS,
                new ViewRequest.Focus(512, 384, 150));

        ViewPlanner.Plan plan = planner.plan(
                manifest, request, 64L * 1024 * 1024, false);

        assertEquals(2, plan.appliedLevel());
        assertEquals(1, plan.contextLevel());
        assertFalse(plan.tiles().isEmpty());
        assertEquals(ViewPlanner.TileRole.CONTEXT_MINIMUM, plan.tiles().getFirst().role());

        int firstFocus = indexOf(plan, ViewPlanner.TileRole.FOCUS);
        int firstRest = indexOf(plan, ViewPlanner.TileRole.VISIBLE_CONTEXT);
        assertTrue(firstFocus > 0);
        assertTrue(firstRest > firstFocus);

        Set<ViewPlanner.TileRef> unique = new HashSet<>();
        for (ViewPlanner.TileDescriptor descriptor : plan.tiles()) {
            assertTrue(unique.add(descriptor.ref()), "tile identities must be unique");
        }
    }

    @Test
    void levelZeroFocusDoesNotDuplicateSameTile() throws Exception {
        ImageManifest manifest = regularManifest();
        ViewRequest request = new ViewRequest(
                2, "photo", "v1",
                new ViewRequest.Rect(0, 0, 1024, 768),
                new ViewRequest.Viewport(64, 48),
                -2,
                ViewRequest.Mode.FOCUS,
                new ViewRequest.Focus(512, 384, 40));

        ViewPlanner.Plan plan = planner.plan(
                manifest, request, 64L * 1024 * 1024, true);

        assertEquals(0, plan.appliedLevel());
        assertEquals(0, plan.contextLevel());
        assertEquals(1, plan.tiles().size());
        assertEquals(new ViewPlanner.TileRef(0, 0, 0), plan.tiles().getFirst().ref());
    }

    @Test
    void impossibleBitmapReservationIsRejectedBeforePlanning() {
        assertThrows(
                ViewPlanner.PlanningException.class,
                () -> planner.plan(
                        regularManifest(),
                        request(1, new ViewRequest.Rect(0, 0, 1024, 768),
                                new ViewRequest.Viewport(512, 384), 0),
                        LupaProtocol.BITMAP_RESERVED_BYTES - 1,
                        false));
    }

    @Test
    void viewModelValidatesUniformAndFocusPoliciesBeforeStateMutation() throws Exception {
        ObjectNode uniform = json.parseControl("""
                {"type":"VIEW","epoch":9,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":10,"y":20,"width":500,"height":300},
                 "viewportPx":{"width":1000,"height":600},
                 "detailOffset":-1,"mode":"uniform","focus":null}
                """);
        ViewRequest parsed = ViewRequest.parse(uniform, "photo", "v1", 1024, 768);
        assertEquals(ViewRequest.Mode.UNIFORM, parsed.mode());
        assertNull(parsed.focus());

        ObjectNode focus = json.parseControl("""
                {"type":"VIEW","epoch":10,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":10,"y":20,"width":500,"height":300},
                 "viewportPx":{"width":1000,"height":600},
                 "detailOffset":0,"mode":"focus",
                 "focus":{"x":200,"y":200,"radiusPx":512}}
                """);
        ViewRequest focused = ViewRequest.parse(focus, "photo", "v1", 1024, 768);
        assertEquals(512, focused.focus().radiusPx());

        ObjectNode badUniform = json.parseControl("""
                {"type":"VIEW","epoch":11,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"uniform",
                 "focus":{"x":1,"y":1,"radiusPx":1}}
                """);
        assertThrows(
                LupaControlException.class,
                () -> ViewRequest.parse(badUniform, "photo", "v1", 1024, 768));

        ObjectNode badRadius = json.parseControl("""
                {"type":"VIEW","epoch":12,"imageId":"photo","imageVersion":"v1",
                 "rect":{"x":0,"y":0,"width":1024,"height":768},
                 "viewportPx":{"width":512,"height":384},
                 "detailOffset":0,"mode":"focus",
                 "focus":{"x":500,"y":300,"radiusPx":513}}
                """);
        assertThrows(
                LupaControlException.class,
                () -> ViewRequest.parse(badRadius, "photo", "v1", 1024, 768));
    }

    private static int indexOf(ViewPlanner.Plan plan, ViewPlanner.TileRole role) {
        for (int i = 0; i < plan.tiles().size(); i++) {
            if (plan.tiles().get(i).role() == role) return i;
        }
        return -1;
    }

    private static ViewRequest request(
            int epoch,
            ViewRequest.Rect rect,
            ViewRequest.Viewport viewport,
            int detailOffset) {
        return new ViewRequest(
                epoch, "photo", "v1", rect, viewport, detailOffset,
                ViewRequest.Mode.UNIFORM, null);
    }

    private static ImageManifest regularManifest() {
        return new ImageManifest(
                1, "photo", "v1", 1024, 768, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 256, 192),
                        new ImageLevel(1, 512, 384),
                        new ImageLevel(2, 1024, 768)));
    }

    private static ImageManifest oddManifest() {
        return new ImageManifest(
                1, "photo", "v1", 1001, 777, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 126, 98),
                        new ImageLevel(1, 251, 195),
                        new ImageLevel(2, 501, 389),
                        new ImageLevel(3, 1001, 777)));
    }

    private static ImageManifest squareManifest() {
        return new ImageManifest(
                1, "photo", "v1", 4096, 4096, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 256, 256),
                        new ImageLevel(1, 512, 512),
                        new ImageLevel(2, 1024, 1024),
                        new ImageLevel(3, 2048, 2048),
                        new ImageLevel(4, 4096, 4096)));
    }

    private static ImageManifest largeManifest() {
        return new ImageManifest(
                1, "photo", "v1", 40000, 30131, 256, 0, "onetile",
                List.of(
                        new ImageLevel(0, 157, 118),
                        new ImageLevel(1, 313, 236),
                        new ImageLevel(2, 625, 471),
                        new ImageLevel(3, 1250, 942),
                        new ImageLevel(4, 2500, 1884),
                        new ImageLevel(5, 5000, 3767),
                        new ImageLevel(6, 10000, 7533),
                        new ImageLevel(7, 20000, 15066),
                        new ImageLevel(8, 40000, 30131)));
    }
}
