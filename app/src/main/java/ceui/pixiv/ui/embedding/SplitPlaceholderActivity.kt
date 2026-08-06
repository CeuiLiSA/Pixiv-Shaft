package ceui.pixiv.ui.embedding

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ceui.lisa.R

/**
 * 平板双栏右侧 2/3 的占位页：大屏上首页没打开详情时由系统按
 * [TabletActivityEmbedding] 里的 SplitPlaceholderRule 自动拉起/收起，
 * 手机上永远不会出现。纯装饰，无交互、无文案。
 */
class SplitPlaceholderActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split_placeholder)
    }
}
