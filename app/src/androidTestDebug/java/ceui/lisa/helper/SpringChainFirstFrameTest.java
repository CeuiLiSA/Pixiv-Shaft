package ceui.lisa.helper;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import ceui.lisa.activities.TemplateActivity;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Renders the entry before attachment against the unminified debug animation API. */
@RunWith(AndroidJUnit4.class)
public class SpringChainFirstFrameTest {
    @Test
    public void firstDrawStartsAtTheEntryOffset() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Intent intent = new Intent(instrumentation.getTargetContext(), TemplateActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity = instrumentation.startActivitySync(intent);
        CountDownLatch drawn = new CountDownLatch(1);
        AtomicReference<Float> firstPosition = new AtomicReference<>();
        try {
            instrumentation.runOnMainSync(() -> {
                LinearLayout content = new LinearLayout(activity);
                View row = new View(activity);
                content.addView(row, new LinearLayout.LayoutParams(100, 100));
                content.getViewTreeObserver().addOnDrawListener(() -> {
                    if (firstPosition.compareAndSet(null, row.getTranslationX())) drawn.countDown();
                });
                // The real caller starts the chain before its fragment view is attached.
                ReboundSpringChain.animate(content);
                activity.setContentView(content);
            });
            assertTrue("The window must draw", drawn.await(10, TimeUnit.SECONDS));
            assertTrue("The first draw flashed the resting row: " + firstPosition.get(),
                    firstPosition.get() > 0f);
        } finally {
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
