# 4 个自定义 View 由 LayoutInflater 按类名反射实例化（:app 的 5 个布局直接写全类名），
# 混淆后找不到 (Context, AttributeSet) 构造就是 InflateException。
# :app 目前 minifyEnabled=false，这条是给将来开混淆时的保险。
-keep public class ceui.pixiv.witstudio.widget.** {
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
