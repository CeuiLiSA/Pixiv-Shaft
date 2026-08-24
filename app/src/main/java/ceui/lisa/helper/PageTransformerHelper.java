package ceui.lisa.helper;

import com.ToxicBakery.viewpager.transforms.ABaseTransformer;
import com.ToxicBakery.viewpager.transforms.AccordionTransformer;
import com.ToxicBakery.viewpager.transforms.BackgroundToForegroundTransformer;
import ceui.lisa.transformer.CubeInTransformer;
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
import java.util.function.Supplier;

import ceui.lisa.activities.Shaft;

public class PageTransformerHelper {

    private final static IndexedLinkedHashMap<Integer, TransformerType> transformerMap = Stream.of(
            new TransformerType(0, "Default", DefaultTransformer::new),
            new TransformerType(1, "Accordion", AccordionTransformer::new),
            new TransformerType(2, "BackgroundToForeground", BackgroundToForegroundTransformer::new),
            new TransformerType(3, "ForegroundToBackground", ForegroundToBackgroundTransformer::new),
            new TransformerType(4, "CubeIn", CubeInTransformer::new),
            new TransformerType(5, "CubeOut", CubeOutTransformer::new),
            new TransformerType(6, "DepthPage", DepthPageTransformer::new),
            new TransformerType(7, "FlipHorizontal", FlipHorizontalTransformer::new),
            new TransformerType(8, "FlipVertical", FlipVerticalTransformer::new),
            new TransformerType(9, "RotateDown", RotateDownTransformer::new),
            new TransformerType(10, "RotateUp", RotateUpTransformer::new),
            new TransformerType(11, "ScaleInOut", ScaleInOutTransformer::new),
            new TransformerType(12, "ZoomOutSlide", ZoomOutSlideTransformer::new),
            new TransformerType(13, "ZoomIn", ZoomInTransformer::new),
            new TransformerType(14, "ZoomOut", ZoomOutTransformer::new),
            new TransformerType(15, "Stack", StackTransformer::new),
            new TransformerType(16, "Tablet", TabletTransformer::new),
            new TransformerType(17, "Drawer", DrawerTransformer::new)
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
        TransformerType transformer = transformerMap.get(Shaft.sSettings.getTransformerType());
        return transformer == null ? new DefaultTransformer() : transformer.factory.get();
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
        private final String name;
        private final Supplier<? extends ABaseTransformer> factory;

        public TransformerType(
                int typeId,
                String name,
                Supplier<? extends ABaseTransformer> factory
        ) {
            this.typeId = typeId;
            this.name = name;
            this.factory = factory;
        }

        public int getTypeId() {
            return typeId;
        }

        public String getName() {
            return name;
        }
    }
}
