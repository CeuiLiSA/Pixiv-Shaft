package ceui.lisa.activities;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import androidx.annotation.Nullable;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringConfig;
import com.facebook.rebound.SpringListener;
import com.facebook.rebound.SpringSystem;

import java.io.File;

import ceui.lisa.R;
import ceui.lisa.utils.Local;

import static com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade;

//启动页，不是软件主页
public class PikaActivity extends BaseActivity {

    public static final String FILE_PATH = "/storage/emulated/0/Shaft/pikaImage";
    public static final String FILE_NAME = "PikaImage.png";

    @Override
    protected void initLayout() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        params.systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        getWindow().setAttributes(params);
        mLayoutID = R.layout.activity_pika;
    }

    @Override
    protected void initView() {
        ImageView imageView = findViewById(R.id.pika_image_view);
        File file = new File(FILE_PATH, Local.getPikaImageFileName());
        if(file.exists()){
            SpringSystem springSystem = SpringSystem.create();
            Spring spring = springSystem.createSpring();
            Glide.with(mContext)
                    .load(file)
                    .transition(withCrossFade())
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            spring.setEndValue(1.0f);
                            return false;
                        }
                    }).into(imageView);

            spring.setSpringConfig(SpringConfig.fromOrigamiTensionAndFriction(1, 8));
            spring.setCurrentValue(1.5f);
            spring.addListener(new SpringListener() {
                @Override
                public void onSpringUpdate(Spring spring) {
                    imageView.setScaleX((float) spring.getCurrentValue());
                    imageView.setScaleY((float) spring.getCurrentValue());
                }

                @Override
                public void onSpringAtRest(Spring spring) {
                    Intent intent = new Intent(mContext, CoverActivity.class);
                    startActivity(intent);
                    finish();
                }

                @Override
                public void onSpringActivate(Spring spring) {

                }

                @Override
                public void onSpringEndStateChange(Spring spring) {

                }
            });
        } else {
            Intent intent = new Intent(mContext, CoverActivity.class);
            startActivity(intent);
            finish();
        }
    }

    @Override
    protected void initData() {

    }
}
