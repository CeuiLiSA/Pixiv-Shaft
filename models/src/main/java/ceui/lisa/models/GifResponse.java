package ceui.lisa.models;

import java.io.Serializable;
import java.util.List;

public class GifResponse implements Serializable{

    /**
     * ugoira_metadata : {"zip_urls":{"medium":"https://i.pximg.net/img-zip-ugoira/img/2014/11/29/09/09/57/47297805_ugoira600x600.zip"},"frames":[{"file":"000000.jpg","delay":120},{"file":"000001.jpg","delay":120},{"file":"000002.jpg","delay":120},{"file":"000003.jpg","delay":120},{"file":"000004.jpg","delay":120},{"file":"000005.jpg","delay":120},{"file":"000006.jpg","delay":120},{"file":"000007.jpg","delay":120},{"file":"000008.jpg","delay":120},{"file":"000009.jpg","delay":120},{"file":"000010.jpg","delay":120},{"file":"000011.jpg","delay":120},{"file":"000012.jpg","delay":120},{"file":"000013.jpg","delay":120},{"file":"000014.jpg","delay":120},{"file":"000015.jpg","delay":120},{"file":"000016.jpg","delay":120},{"file":"000017.jpg","delay":120},{"file":"000018.jpg","delay":120},{"file":"000019.jpg","delay":120},{"file":"000020.jpg","delay":120},{"file":"000021.jpg","delay":120},{"file":"000022.jpg","delay":100},{"file":"000023.jpg","delay":120},{"file":"000024.jpg","delay":120},{"file":"000025.jpg","delay":120},{"file":"000026.jpg","delay":120},{"file":"000027.jpg","delay":120},{"file":"000028.jpg","delay":120},{"file":"000029.jpg","delay":120},{"file":"000030.jpg","delay":120},{"file":"000031.jpg","delay":100},{"file":"000032.jpg","delay":100},{"file":"000033.jpg","delay":100},{"file":"000034.jpg","delay":120},{"file":"000035.jpg","delay":120},{"file":"000036.jpg","delay":120},{"file":"000037.jpg","delay":120},{"file":"000038.jpg","delay":100},{"file":"000039.jpg","delay":120},{"file":"000040.jpg","delay":100},{"file":"000041.jpg","delay":100},{"file":"000042.jpg","delay":120},{"file":"000043.jpg","delay":120},{"file":"000044.jpg","delay":120},{"file":"000045.jpg","delay":120},{"file":"000046.jpg","delay":120},{"file":"000047.jpg","delay":120},{"file":"000048.jpg","delay":120},{"file":"000049.jpg","delay":120},{"file":"000050.jpg","delay":120},{"file":"000051.jpg","delay":100},{"file":"000052.jpg","delay":200},{"file":"000053.jpg","delay":130},{"file":"000054.jpg","delay":130},{"file":"000055.jpg","delay":130},{"file":"000056.jpg","delay":130},{"file":"000057.jpg","delay":130},{"file":"000058.jpg","delay":300},{"file":"000059.jpg","delay":100},{"file":"000060.jpg","delay":500},{"file":"000061.jpg","delay":100},{"file":"000062.jpg","delay":120},{"file":"000063.jpg","delay":120},{"file":"000064.jpg","delay":120},{"file":"000065.jpg","delay":120},{"file":"000066.jpg","delay":120},{"file":"000067.jpg","delay":120},{"file":"000068.jpg","delay":120},{"file":"000069.jpg","delay":120},{"file":"000070.jpg","delay":120},{"file":"000071.jpg","delay":120},{"file":"000072.jpg","delay":120},{"file":"000073.jpg","delay":100},{"file":"000074.jpg","delay":100},{"file":"000075.jpg","delay":120},{"file":"000076.jpg","delay":120},{"file":"000077.jpg","delay":120}]}
     */

    private static final int DEFAULT_DELAY = 60;

    public int getDelay() {
        if (ugoira_metadata != null) {
            if (ugoira_metadata.getFrames() != null && ugoira_metadata.getFrames().size() > 0) {
                return ugoira_metadata.getFrames().get(0).getDelay();
            }
        }
        return DEFAULT_DELAY;
    }

    private UgoiraMetadataBean ugoira_metadata;

    public UgoiraMetadataBean getUgoira_metadata() {
        return ugoira_metadata;
    }

    public void setUgoira_metadata(UgoiraMetadataBean ugoira_metadata) {
        this.ugoira_metadata = ugoira_metadata;
    }

    @Override
    public String toString() {
        return "GifResponse{" +
                "ugoira_metadata=" + ugoira_metadata +
                '}';
    }
}
