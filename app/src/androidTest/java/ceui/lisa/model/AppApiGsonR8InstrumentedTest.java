package ceui.lisa.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.LiveData;

import com.google.gson.Gson;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.List;
import java.util.Map;

import ceui.lisa.core.DownloadItem;
import ceui.pixiv.api.API;
import ceui.pixiv.api.model.Illust;
import ceui.pixiv.cache.ObjectPool;
import ceui.pixiv.snapshot.SnapshotManifest;
import ceui.pixiv.snapshot.AutoSnapshotBehaviorRecord;
import ceui.pixiv.snapshot.AutoSnapshotDwellSample;
import ceui.pixiv.ui.user.RequestPlanText;
import ceui.pixiv.ui.user.UserRequestPlansResponse;
import ceui.pixiv.plaza.CreatePost;
import ceui.pixiv.plaza.DeletePost;
import ceui.pixiv.plaza.PlazaApi;
import ceui.pixiv.plaza.PlazaBlockedUser;
import ceui.pixiv.plaza.PlazaBlocks;
import ceui.pixiv.plaza.PlazaCommentPreview;
import ceui.pixiv.plaza.PlazaImage;
import ceui.pixiv.plaza.PlazaPage;
import ceui.pixiv.plaza.PlazaPost;
import ceui.pixiv.plaza.PlazaReaction;
import ceui.pixiv.plaza.PlazaReportReceipt;
import ceui.pixiv.plaza.PlazaReportRequest;
import ceui.pixiv.sticker.StickerCatalog;
import ceui.pixiv.sticker.StickerPack;
import ceui.pixiv.sticker.Sticker;
import retrofit2.http.GET;

/** Guards App API wire fields and Retrofit response signatures in the minified release APK. */
@RunWith(AndroidJUnit4.class)
public final class AppApiGsonR8InstrumentedTest {

    @Test
    public void listIllustWireFieldsSurviveR8() throws Exception {
        // Gson reads these legacy fields by their source names. Checking the actual release class
        // avoids linking the test APK against Gson methods that target R8 is allowed to optimize.
        assertNotNull(ListIllust.class.getDeclaredField("next_url"));
        assertNotNull(ListIllust.class.getDeclaredField("illusts"));
        assertNotNull(Illust.class.getDeclaredField("id"));
        assertNotNull(Illust.class.getDeclaredField("title"));
    }

    @Test
    public void reflectionOnlyModelFieldsSurviveR8() throws Exception {
        // These models are only ever instantiated by Gson. Without an identity keep, full-mode R8
        // marks them uninstantiated and strips their fields even though -keepclassmembers lists
        // them. One representative per category: fully-qualified Retrofit response, nested wire
        // model, exported .shaftsnap manifest, and the Room-persisted download queue snapshot.
        assertNotNull(UserRequestPlansResponse.class.getDeclaredField("request_plans"));
        assertNotNull(RequestPlanText.class.getDeclaredField("translation"));
        assertNotNull(SnapshotManifest.class.getDeclaredField("snapshotId"));
        assertNotNull(DownloadItem.class.getDeclaredField("url"));
    }

    @Test
    public void autoSnapshotBehaviorSchemaSurvivesR8() throws Exception {
        for (String field : new String[]{"illustId", "type", "recentVisits", "visitCount",
                "lastDwellMs", "recentDwells", "lastAutoSnapshotAt", "lastTriggerSignal", "schemaVersion"}) {
            assertNotNull(AutoSnapshotBehaviorRecord.class.getDeclaredField(field));
        }
        assertNotNull(AutoSnapshotDwellSample.class.getDeclaredField("at"));
        assertNotNull(AutoSnapshotDwellSample.class.getDeclaredField("ms"));
    }

    @Test
    public void retrofitResponseGenericTypesSurviveR8FullMode() {
        // API is suspend-only: the response type lives in the trailing Continuation<? super T>
        // parameter, which Retrofit reads reflectively. Two endpoints from different call paths
        // (recommended feed via widgets, latest works via LatestIllustRepo).
        assertEquals(RecmdIllust.class, continuationResultType(findGetEndpoint(API.class, "v1/illust/recommended")));
        assertEquals(ListIllust.class, continuationResultType(findGetEndpoint(API.class, "v1/illust/new")));
    }

    @Test
    public void plazaEmptyAndPopulatedResponsesDeserializeInRelease() throws Exception {
        assertEquals(PlazaPage.class, continuationResultType(findGetEndpoint(PlazaApi.class, "v1/plaza/posts")));
        assertEquals(PlazaBlocks.class, continuationResultType(findGetEndpoint(PlazaApi.class, "v1/plaza/blocks")));
        Object empty = parseWire(PlazaPage.class, "{\"items\":[],\"nextBefore\":null}");
        assertTrue(((List<?>) wireField(empty, "items")).isEmpty());
        assertEquals(null, wireField(empty, "nextBefore"));

        Object page = parseWire(PlazaPage.class, """
                {"items":[{"id":14,"uid":42,"displayName":"author","text":"hello",
                  "createdAt":1700000000000,"objectId":123,"objectType":"illust","replyTo":null,
                  "likeCount":2,"replyCount":1,"liked":true,"title":"title","avatarUrl":"https://example.com/a",
                  "images":[{"mediaId":"media-1","width":80,"height":60,"contentType":"image/png",
                    "url":"https://example.com/image","expiresAt":1800000000000}],
                  "reactions":[{"emoji":"sticker:650863185465585230","count":3,"selected":true,"stickerId":650863185465585230}],
                  "commentsPreview":[{"id":15,"uid":43,"displayName":"reply","text":"comment","avatarUrl":null,"createdAt":1700000001000}],
                  "objectExtensions":{"illust":{"id":123,"title":"linked"}}}],"nextBefore":14}
                """);
        assertEquals(14L, wireField(page, "nextBefore"));
        Object post = ((List<?>) wireField(page, "items")).get(0);
        assertEquals(PlazaPost.class, post.getClass());
        assertEquals(14L, wireField(post, "id"));
        assertEquals("author", wireField(post, "displayName"));
        assertEquals("title", wireField(post, "title"));
        Object image = ((List<?>) wireField(post, "images")).get(0);
        assertEquals(PlazaImage.class, image.getClass());
        assertEquals("media-1", wireField(image, "mediaId"));
        assertEquals(80, wireField(image, "width"));
        Object reaction = ((List<?>) wireField(post, "reactions")).get(0);
        assertEquals(PlazaReaction.class, reaction.getClass());
        assertEquals(650863185465585230L, wireField(reaction, "stickerId"));
        Object reply = ((List<?>) wireField(post, "commentsPreview")).get(0);
        assertEquals(PlazaCommentPreview.class, reply.getClass());
        assertEquals("comment", wireField(reply, "text"));
        Object linked = wireField(wireField(post, "objectExtensions"), "illust");
        assertEquals(Illust.class, linked.getClass());
        assertEquals("linked", wireField(linked, "title"));
        // The same schema is persisted in the first-page cache and passed to the image viewer.
        Object restored = parseWire(PlazaPage.class, encodeWire(page));
        assertEquals(14L, wireField(((List<?>) wireField(restored, "items")).get(0), "id"));
    }

    @Test
    public void plazaRequestsAndModerationResponsesKeepTheirWireSchema() throws Exception {
        String createJson = """
                {"requestId":"request-id-123456","text":"hello","displayName":"author","mediaIds":["media-1"],
                 "objectId":123,"objectType":"illust","replyTo":14,"title":"title","avatarUrl":"https://example.com/a",
                 "policyVersion":"2026-09-16","objectExtensions":{"illust":{"id":123,"title":"linked"}}}
                """;
        JSONObject request = new JSONObject(encodeWire(parseWire(CreatePost.class, createJson)));
        assertEquals(11, request.length());
        assertEquals("request-id-123456", request.getString("requestId"));
        assertEquals("author", request.getString("displayName"));
        assertEquals("media-1", request.getJSONArray("mediaIds").getString(0));
        assertEquals(14L, request.getLong("replyTo"));
        assertEquals("2026-09-16", request.getString("policyVersion"));
        assertEquals(123L, request.getJSONObject("objectExtensions").getJSONObject("illust").getLong("id"));

        JSONObject report = new JSONObject(encodeWire(parseWire(PlazaReportRequest.class,
                "{\"targetType\":\"post\",\"reason\":\"spam\",\"details\":\"reason\",\"mediaIds\":[\"media-1\"]}")));
        assertEquals(4, report.length());
        assertEquals("post", report.getString("targetType"));
        assertEquals("spam", report.getString("reason"));
        Object receipt = parseWire(PlazaReportReceipt.class, "{\"id\":1,\"status\":\"pending\",\"duplicate\":false}");
        assertEquals(1L, wireField(receipt, "id"));
        assertEquals("pending", wireField(receipt, "status"));
        assertEquals(false, wireField(receipt, "duplicate"));
        assertEquals(true, wireField(parseWire(DeletePost.class, "{\"ok\":true}"), "ok"));
        Object blocks = parseWire(PlazaBlocks.class, "{\"items\":[{\"uid\":42,\"displayName\":\"blocked\"}]}");
        Object blocked = ((List<?>) wireField(blocks, "items")).get(0);
        assertEquals(PlazaBlockedUser.class, blocked.getClass());
        assertEquals("blocked", wireField(blocked, "displayName"));
    }

    @Test
    public void stickerCatalogAndNestedCollectionsSurviveRelease() throws Exception {
        Object catalog = parseWire(StickerCatalog.class, """
                {"versions":{"list":[{"name":"static","version":"v1"}]},"packs":{"static":{
                  "pkgList":[{"width":128,"height":128,"url":"https://example.com/stickers.zip",
                    "path":"emoji/128","size":1234,"sha256":"hash"}],
                  "groupList":[{"name":"test","stickerList":[{"stickerId":650863185465585230,"name":"smile",
                    "media":{"baseUrl":"https://example.com/","resourceList":[{"width":128,"height":128,"url":"smile.png"}]}}]}],
                  "stickerList":[]}}}
                """);
        Object version = ((List<?>) wireField(wireField(catalog, "versions"), "list")).get(0);
        assertEquals("v1", wireField(version, "version"));
        Object pack = ((Map<?, ?>) wireField(catalog, "packs")).get("static");
        assertEquals(StickerPack.class, pack.getClass());
        Object pkg = ((List<?>) wireField(pack, "pkgList")).get(0);
        assertEquals(1234L, wireField(pkg, "size"));
        Object group = ((List<?>) wireField(pack, "groupList")).get(0);
        Object sticker = ((List<?>) wireField(group, "stickerList")).get(0);
        assertEquals(Sticker.class, sticker.getClass());
        assertEquals(650863185465585230L, wireField(sticker, "stickerId"));
        Object media = wireField(sticker, "media");
        Object resource = ((List<?>) wireField(media, "resourceList")).get(0);
        assertEquals("smile.png", wireField(resource, "url"));
        JSONObject persisted = new JSONObject(encodeWire(catalog));
        assertEquals("v1", persisted.getJSONObject("versions").getJSONArray("list").getJSONObject(0).getString("version"));
    }

    // Reflect over the Gson shipped in the target APK: androidTest is shrunk separately and
    // cannot assume that Gson's production method names/ABI survive target R8 unchanged.
    private static Object parseWire(Class<?> type, String json) throws Exception {
        assertTrue("Gson response type became abstract: " + type, !Modifier.isAbstract(type.getModifiers()));
        Object gson = Gson.class.getDeclaredConstructor().newInstance();
        for (Method method : Gson.class.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 2 && params[0] == String.class && params[1] == Class.class) {
                method.setAccessible(true);
                return method.invoke(gson, json, type);
            }
        }
        throw new AssertionError("Missing Gson string/class parser");
    }

    private static String encodeWire(Object value) throws Exception {
        Object gson = Gson.class.getDeclaredConstructor().newInstance();
        for (Method method : Gson.class.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == Object.class && method.getReturnType() == String.class) {
                method.setAccessible(true);
                return (String) method.invoke(gson, value);
            }
        }
        throw new AssertionError("Missing Gson object serializer");
    }

    private static Object wireField(Object value, String name) throws Exception {
        Field field = value.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(value);
    }

    private static Type continuationResultType(Method suspendMethod) {
        Type[] params = suspendMethod.getGenericParameterTypes();
        ParameterizedType continuation = (ParameterizedType) params[params.length - 1];
        return unwrapWildcard(continuation.getActualTypeArguments()[0]);
    }

    @Test
    public void objectPoolTypeKeysDoNotDependOnObfuscatedClassNames() throws Exception {
        long id = 9_223_372_036_854_770_000L;
        Illust illust = new Illust(
                null, null, 800, id, null, false, 0, false, null, null,
                1, null, null, null, null, "r8-object-pool", null, 10, 20,
                "illust", null, true, 1200, null, false, null);

        // Target R8 may staticize ObjectPool's instance methods, while androidTest is optimized in
        // a separate APK. Invoke the actual release methods reflectively so this test validates
        // ObjectPool semantics without adding production keep rules just for the test ABI.
        boolean updated = false;
        for (Method method : ObjectPool.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == Illust.class
                    && method.getReturnType() == void.class) {
                method.setAccessible(true);
                method.invoke(receiverFor(method), illust);
                updated = true;
                break;
            }
        }
        assertTrue("Missing ObjectPool Illust update method", updated);

        for (Method method : ObjectPool.class.getDeclaredMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == long.class
                    && LiveData.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                LiveData<?> result = (LiveData<?>) method.invoke(receiverFor(method), id);
                if (result != null && result.getValue() == illust) {
                    assertSame(illust, result.getValue());
                    return;
                }
            }
        }
        fail("Obfuscated ObjectPool could not read the Illust that it just stored");
    }

    private static Object receiverFor(Method method) throws Exception {
        if (Modifier.isStatic(method.getModifiers())) {
            return null;
        }
        for (Field field : ObjectPool.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ObjectPool.class) {
                field.setAccessible(true);
                return field.get(null);
            }
        }
        throw new AssertionError("Missing ObjectPool singleton instance");
    }

    private static Method findGetEndpoint(Class<?> apiClass, String endpointPath) {
        for (Method method : apiClass.getDeclaredMethods()) {
            GET get = method.getAnnotation(GET.class);
            if (get != null && get.value().split("\\?", 2)[0].equals(endpointPath)) {
                return method;
            }
        }
        throw new AssertionError("Missing Retrofit endpoint: " + endpointPath);
    }

    private static Type unwrapWildcard(Type type) {
        if (!(type instanceof WildcardType)) {
            return type;
        }
        WildcardType wildcard = (WildcardType) type;
        Type[] lowerBounds = wildcard.getLowerBounds();
        return lowerBounds.length == 1 ? lowerBounds[0] : wildcard.getUpperBounds()[0];
    }
}
