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

import java.util.ArrayList;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import ceui.lisa.activities.Shaft;

public class PageTransformerHelper {

    private final static IndexedLinkedHashMap<Integer, TransformerType> transformerMap = Stream.of(
            new TransformerType(0, DefaultTransformer.class),
            new TransformerType(1, AccordionTransformer.class),
            new TransformerType(2, BackgroundToForegroundTransformer.class),
            new TransformerType(3, ForegroundToBackgroundTransformer.class),
            new TransformerType(4, CubeInTransformer.class),
            new TransformerType(5, CubeOutTransformer.class),
            new TransformerType(6, DepthPageTransformer.class),
            new TransformerType(7, FlipHorizontalTransformer.class),
            new TransformerType(8, FlipVerticalTransformer.class),
            new TransformerType(9, RotateDownTransformer.class),
            new TransformerType(10, RotateUpTransformer.class),
            new TransformerType(11, ScaleInOutTransformer.class),
            new TransformerType(12, ZoomOutSlideTransformer.class),
            new TransformerType(13, ZoomInTransformer.class),
            new TransformerType(14, ZoomOutTransformer.class),
            new TransformerType(15, StackTransformer.class),
            new TransformerType(16, TabletTransformer.class),
            new TransformerType(17, DrawerTransformer.class)
    ).collect(Collectors.toMap(TransformerType::getTypeId, t -> t, (v1, v2) -> v1, IndexedLinkedHashMap::new)).tidyIndexes();

    public static int getCurrentTransformerIndex() {
        int transformerType = Shaft.sSettings.getTransformerType();
        if (!transformerMap.containsKey(transformerType)) {
            return 0;
        }
        int index = new ArrayList<>(transformerMap.keySet()).indexOf(transformerType);
        return Math.min(Math.max(index, 0), transformerMap.size() - 1);
    }

    public static ABaseTransformer getCurrentTransformer() {
        try {
            return transformerMap.get(Shaft.sSettings.getTransformerType()).pageTransformer.newInstance();
        } catch (IllegalAccessException | InstantiationException e) {
            e.printStackTrace();
        }
        return new DefaultTransformer();
    }

    public static String[] getTransformerNames() {
        return transformerMap.values().stream().map(TransformerType::getName).toArray(String[]::new);
    }

    public static void setCurrentTransformer(int index) {
        if (index < 0 || index >= transformerMap.size()) {
            index = 0;
        }
        Shaft.sSettings.setTransformerType(transformerMap.getIndexed(index).getTypeId());
    }

    private static class TransformerType {

        private final int typeId;
        private int nameResId;
        private final Class<? extends ABaseTransformer> pageTransformer;

        public TransformerType(int typeId, Class<? extends ABaseTransformer> pageTransformer) {
            this.typeId = typeId;
            this.pageTransformer = pageTransformer;
        }

        public int getTypeId() {
            return typeId;
        }

        public String getName() {
            return pageTransformer.getSimpleName().replace("Transformer", "");
        }
    }
}
