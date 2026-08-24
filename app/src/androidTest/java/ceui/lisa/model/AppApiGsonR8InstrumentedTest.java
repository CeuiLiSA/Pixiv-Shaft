package ceui.lisa.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.lifecycle.LiveData;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;

import ceui.lisa.core.DownloadItem;
import ceui.lisa.http.AppApi;
import ceui.lisa.http.AppApiSuspend;
import ceui.loxia.Illust;
import ceui.loxia.ObjectPool;
import ceui.pixiv.snapshot.SnapshotManifest;
import ceui.pixiv.ui.user.RequestPlanText;
import ceui.pixiv.ui.user.UserRequestPlansResponse;
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
    public void retrofitResponseGenericTypesSurviveR8FullMode() {
        Method suspendMethod = findGetEndpoint(AppApiSuspend.class, "v1/illust/recommended");
        Type suspendContinuation = suspendMethod.getGenericParameterTypes()[
                suspendMethod.getGenericParameterTypes().length - 1];
        ParameterizedType continuation = (ParameterizedType) suspendContinuation;
        assertEquals(RecmdIllust.class, unwrapWildcard(continuation.getActualTypeArguments()[0]));

        // The old recommended Rx endpoint has no live call site and is correctly removed. Check a
        // legacy Rx endpoint that is still used by LatestIllustRepo instead.
        Method rxMethod = findGetEndpoint(AppApi.class, "v1/illust/new");
        ParameterizedType observable = (ParameterizedType) rxMethod.getGenericReturnType();
        assertEquals(ListIllust.class, unwrapWildcard(observable.getActualTypeArguments()[0]));
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

    private static Method findGetEndpoint(Class<?> apiClass, String endpointPrefix) {
        for (Method method : apiClass.getDeclaredMethods()) {
            GET get = method.getAnnotation(GET.class);
            if (get != null && get.value().startsWith(endpointPrefix)) {
                return method;
            }
        }
        throw new AssertionError("Missing Retrofit endpoint: " + endpointPrefix);
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
