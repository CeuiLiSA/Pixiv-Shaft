package ceui.lisa.utils;

/** 编译期开关，不是运行时状态——全部 final。 */
public class Dev {

    public static final boolean hideMainActivityStatus = true;


    public static final boolean use_weiss = false;
    public static final boolean show_url_detail = false;

    // 主页底部「我」tab,发版前先收起,放开就是 true
    public static final boolean showMeTab = false;

    // 插画详情「更多」菜单里「分享至广场」入口,plaza 没全量前先收,放开就是 true
    public static final boolean showPlazaShareInArtwork = false;

}
