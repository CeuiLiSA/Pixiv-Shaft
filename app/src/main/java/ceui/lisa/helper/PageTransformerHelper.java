package ceui.lisa.helper;

import com.ToxicBakery.viewpager.transforms.ABaseTransformer;
import com.ToxicBakery.viewpager.transforms.AccordionTransformer;
import com.ToxicBakery.viewpager.transforms.BackgroundToForegroundTransformer;
import com.ToxicBakery.viewpager.transforms.CubeInTransformer;
import com.ToxicBakery.viewpager.transforms.CubeOutTransformer;
import com.ToxicBakery.viewpager.transforms.DefaultTransformer;
import com.ToxicBakery.viewpager.transforms.DepthPageTransformer;
import com.ToxicBakery.viewpager.transforms.DrawerTransformer;
import com.ToxicBakery.viewpager.transforms.FlipHorizontalTransformer;
import com.ToxicBakery.viewpager.transforms.FlipVerticalTransformer;
import com.ToxicBakery.viewpager.transforms.ForegroundToBackgroundTransformer;
import com.ToxicBakery.viewpager.transforms.RotateDownTransformer;
import com.ToxicBakery.viewpager.transforms.RotateUpTransformer;
import com.ToxicBakery.viewpager.transforms.ScaleInOutTransformer;
import com.ToxicBakery.viewpager.transforms.StackTransformer;
import com.ToxicBakery.viewpager.transforms.TabletTransformer;
import com.ToxicBakery.viewpager.transforms.ZoomInTransformer;
import com.ToxicBakery.viewpager.transforms.ZoomOutSlideTransformer;
import com.ToxicBakery.viewpager.transforms.ZoomOutTransformer;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import ceui.lisa.activities.Shaft;

public class PageTransformerHelper {
    private final static List<TransformerType> transformers = Arrays.asList(
            new TransformerType(DefaultTransformer.class),
            new TransformerType(AccordionTransformer.class),

            new TransformerType(BackgroundToForegroundTransformer.class),
            new TransformerType(ForegroundToBackgroundTransformer.class),

            new TransformerType(CubeInTransformer.class),
            new TransformerType(CubeOutTransformer.class),

            new TransformerType(DepthPageTransformer.class),

            new TransformerType(FlipHorizontalTransformer.class),
            new TransformerType(FlipVerticalTransformer.class),

            new TransformerType(RotateDownTransformer.class),
            new TransformerType(RotateUpTransformer.class),

            new TransformerType(ScaleInOutTransformer.class),
            new TransformerType(ZoomOutSlideTransformer.class),

            new TransformerType(ZoomInTransformer.class),
            new TransformerType(ZoomOutTransformer.class),

            new TransformerType(StackTransformer.class),
            new TransformerType(TabletTransformer.class),
            new TransformerType(DrawerTransformer.class)
    );

    public static int getCurrentTransformerIndex(){
        return Shaft.sSettings.getTransformerType();
    }

    public static ABaseTransformer getCurrentTransformer(){
        try {
            return transformers.get(Shaft.sSettings.getTransformerType()).pageTransformer.newInstance();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        }
        return new DefaultTransformer();
    }

    public static String[] getTransformerNames(){
        return transformers.stream().map(TransformerType::getName).toArray(String[]::new);
    }

    public static void setCurrentTransformer(int index){
        if (index < 0 || index >= transformers.size()){
            return;
        }
        Shaft.sSettings.setTransformerType(index);
    }

    private static class TransformerType {
        private int nameResId;
        private Class<? extends ABaseTransformer> pageTransformer;

        public TransformerType(Class<? extends ABaseTransformer> pageTransformer) {
            this.pageTransformer = pageTransformer;
        }

        public TransformerType(int nameResId, Class<? extends ABaseTransformer> pageTransformer) {
            this.nameResId = nameResId;
            this.pageTransformer = pageTransformer;
        }

        public String getName(){
            return pageTransformer.getSimpleName().replace("Transformer", "");
        }
    }
}
