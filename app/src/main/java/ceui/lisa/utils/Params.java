package ceui.lisa.utils;

public class Params {

    public static final String ID            = "simple id";
    public static final String USER_ID       = "user id";
    public static final String ILLUST_ID     = "illust id";
    public static final String NOVEL_ID      = "novel id";
    public static final String ILLUST_TITLE  = "illust title";
    public static final String DATA_TYPE     = "data type";
    public static final String INDEX         = "index";
    public static final String CONTENT       = "content";
    public static final String DAY           = "day";
    public static final String URL           = "url";
    public static final String TITLE         = "title";
    public static final String KEY_WORD      = "key word";
    public static final String SORT_TYPE     = "sort type";
    public static final String CONTENT_TYPE  = "content type";
    public static final String SIZE          = "size";
    public static final String SEARCH_TYPE   = "search type";
    public static final String STAR_SIZE     = "star size";
    public static final String MANGA         = "is manga";
    public static final String SHOW_DIALOG   = "show dialog";
    public static final String USER_MODEL    = "user model";
    public static final String USE_DEBUG     = "use debug mode";
    public static final String FILE_PATH     = "file path";
    public static final String STAR_TYPE     = "star type";
    public static final String FLAG          = "flag";
    public static final String RESPONSE      = "response";
    public static final String MIME          = "mime";
    public static final String ENCODING      = "encoding";
    public static final String HISTORY_URL   = "history url";
    public static final String TYPE_ILLUST   = "illust";
    public static final String TYPE_NOVEL    = "novel";
    public static final String TYPE_MANGA    = "manga";
    public static final String NOVEL_KEY     = "pixiv_shaft_novel_";
    public static final String USER_KEY      = "pixiv_shaft_local_user";
    public static final String SECRET_PWD_KEY= "pixiv_secret_password:";
    public static final String PAGE_UUID     = "page_uuid";
    public static final String POSITION      = "position";
    public static final String IS_LIKED      = "is liked";
    public static final String IS_POPULAR    = "is popular";
    public static final String LAST_CLASS    = "last class";

    public static final String FILTER_ILLUST        = "ceui.lisa.fragments.NetListFragment FILTER_ILLUST";
    public static final String LIKED_ILLUST        = "ceui.lisa.fragments.NetListFragment LIKED_ILLUST";
    public static final String PLAY_GIF        = "ceui.lisa.fragments.FragmentSingleUgora PLAY_GIF";
    public static final String LIKED_USER          = "ceui.lisa.fragments.NetListFragment LIKED_USER";
    public static final String LIKED_NOVEL         = "ceui.lisa.fragments.NetListFragment LIKED_NOVEL";
    public static final String DOWNLOAD_FINISH         = "ceui.lisa.fragments.NetListFragment DOWNLOAD_FINISH";
    public static final String DOWNLOAD_ING         = "ceui.lisa.fragments.NetListFragment DOWNLOAD_ING";

    public static final String MAP_KEY = "Referer";
    public static final String MAP_KEY_SMALL = "referer";
    public static final String IMAGE_REFERER = "https://app-api.pixiv.net/";
    public static final String USER_AGENT = "User-Agent";
    public static final String PHONE_MODEL = "PixivIOSApp/5.8.0";
    public static final String HOST = "Host";
    public static final String HOST_NAME = "i.pximg.net";


    public static final String IMAGE_UNKNOWN = "https://s.pximg.net/common/images/limit_unknown_360.png";
    public static final String HEAD_UNKNOWN  = "https://s.pximg.net/common/images/no_profile.png";

    public static final String TYPE_PUBLUC = "public";
    public static final String TYPE_PRIVATE = "private";

    public static final String SHOW_LONG_DIALOG = "show long dialog";
    public static final String LONG_DIALOG_MESSAGE = "1.首先对前段时间自己生产的BUG跟各位说声抱歉。\n" +
            "2.android 11会全面启用SAF存储访问框架，所以我把曾经用的很舒服但是即将被废弃的传统存储文件方式换成了SAF的访问模式。为了软件后续的持久发展，这是必经之路。\n" +
            "3.自己在简单适配了原生安卓，三星，一加，索尼之后就发布新版本Shaft了，但是没有预料到有的国产厂商把手机系统魔改的这么狠。导致即便我严格遵守SAF框架细则来操作文件，仍然有的手机无法正常下载。这是我没适配好的问题，至今也是站在十字路口不知道改怎么解决，到底该返回舒适区，用即将被弃用的老版本文件系统，还是走一步看一步，用新的版本SAF框架呢？\n" +
            "4.关于下载速度，加载速度，我也尽力找所有能加速的方案，Pixivcat在我所在的上海地区真的是速度提升明显，所以自己就擅自更新了，导致部分地区的人加载速度反而变慢，这也是我草率做决策的问题\n" +
            "5.种种原因导致最近app问题偏多。但是请相信我，我愿意花时间花精力来让APP变好。即便自己能力比较菜，即便上班的闲暇时间也不多，但是请给我一点时间来做好。谢谢各位";


    public static final int REQUEST_CODE_CHOOSE = 10086;
    public static final String EXAMPLE_ILLUST = "{\n" +
            "    \"caption\":\"\",\n" +
            "    \"create_date\":\"2020-07-07T00:30:02+09:00\",\n" +
            "    \"gifDelay\":0,\n" +
            "    \"height\":2000,\n" +
            "    \"id\":82805170,\n" +
            "    \"image_urls\":{\n" +
            "        \"large\":\"https://i.pximg.net/c/600x1200_90/img-master/img/2020/07/07/00/30/02/82805170_p0_master1200.jpg\",\n" +
            "        \"medium\":\"https://i.pximg.net/c/540x540_70/img-master/img/2020/07/07/00/30/02/82805170_p0_master1200.jpg\",\n" +
            "        \"square_medium\":\"https://i.pximg.net/c/360x360_70/img-master/img/2020/07/07/00/30/02/82805170_p0_square1200.jpg\"\n" +
            "    },\n" +
            "    \"isChecked\":false,\n" +
            "    \"isShield\":false,\n" +
            "    \"is_bookmarked\":false,\n" +
            "    \"is_muted\":false,\n" +
            "    \"meta_pages\":[\n" +
            "        {\n" +
            "            \"image_urls\":{\n" +
            "                \"large\":\"https://i.pximg.net/c/600x1200_90/img-master/img/2020/07/07/00/30/02/82805170_p0_master1200.jpg\",\n" +
            "                \"medium\":\"https://i.pximg.net/c/540x540_70/img-master/img/2020/07/07/00/30/02/82805170_p0_master1200.jpg\",\n" +
            "                \"original\":\"https://i.pximg.net/img-original/img/2020/07/07/00/30/02/82805170_p0.png\",\n" +
            "                \"square_medium\":\"https://i.pximg.net/c/360x360_70/img-master/img/2020/07/07/00/30/02/82805170_p0_square1200.jpg\"\n" +
            "            }\n" +
            "        },\n" +
            "        {\n" +
            "            \"image_urls\":{\n" +
            "                \"large\":\"https://i.pximg.net/c/600x1200_90/img-master/img/2020/07/07/00/30/02/82805170_p1_master1200.jpg\",\n" +
            "                \"medium\":\"https://i.pximg.net/c/540x540_70/img-master/img/2020/07/07/00/30/02/82805170_p1_master1200.jpg\",\n" +
            "                \"original\":\"https://i.pximg.net/img-original/img/2020/07/07/00/30/02/82805170_p1.png\",\n" +
            "                \"square_medium\":\"https://i.pximg.net/c/360x360_70/img-master/img/2020/07/07/00/30/02/82805170_p1_square1200.jpg\"\n" +
            "            }\n" +
            "        }\n" +
            "    ],\n" +
            "    \"meta_single_page\":{\n" +
            "\n" +
            "    },\n" +
            "    \"page_count\":2,\n" +
            "    \"restrict\":0,\n" +
            "    \"sanity_level\":2,\n" +
            "    \"tags\":[\n" +
            "        {\n" +
            "            \"added_by_uploaded_user\":false,\n" +
            "            \"count\":0,\n" +
            "            \"isSelected\":false,\n" +
            "            \"name\":\"オリジナル\",\n" +
            "            \"translated_name\":\"原创\"\n" +
            "        },\n" +
            "        {\n" +
            "            \"added_by_uploaded_user\":false,\n" +
            "            \"count\":0,\n" +
            "            \"isSelected\":false,\n" +
            "            \"name\":\"女の子\",\n" +
            "            \"translated_name\":\"女孩子\"\n" +
            "        },\n" +
            "        {\n" +
            "            \"added_by_uploaded_user\":false,\n" +
            "            \"count\":0,\n" +
            "            \"isSelected\":false,\n" +
            "            \"name\":\"銀髪碧眼\",\n" +
            "            \"translated_name\":\"银发碧眼\"\n" +
            "        },\n" +
            "        {\n" +
            "            \"added_by_uploaded_user\":false,\n" +
            "            \"count\":0,\n" +
            "            \"isSelected\":false,\n" +
            "            \"name\":\"オリジナル10000users入り\",\n" +
            "            \"translated_name\":\"原创10000users加入书籤\"\n" +
            "        }\n" +
            "    ],\n" +
            "    \"title\":\"作品标题\",\n" +
            "    \"tools\":[\n" +
            "\n" +
            "    ],\n" +
            "    \"total_bookmarks\":14306,\n" +
            "    \"total_view\":47802,\n" +
            "    \"type\":\"illust\",\n" +
            "    \"user\":{\n" +
            "        \"account\":\"sinsihukunokonaka\",\n" +
            "        \"id\":1122006,\n" +
            "        \"is_followed\":false,\n" +
            "        \"is_login\":false,\n" +
            "        \"is_mail_authorized\":false,\n" +
            "        \"is_premium\":false,\n" +
            "        \"lastTokenTime\":-1,\n" +
            "        \"name\":\"画师昵称\",\n" +
            "        \"profile_image_urls\":{\n" +
            "            \"medium\":\"https://i.pximg.net/user-profile/img/2016/02/26/03/01/16/10587767_da08b7a0bc20d8eadbe8e7f9539b5400_170.png\"\n" +
            "        },\n" +
            "        \"require_policy_agreement\":false,\n" +
            "        \"x_restrict\":0\n" +
            "    },\n" +
            "    \"visible\":true,\n" +
            "    \"width\":1200,\n" +
            "    \"x_restrict\":0\n" +
            "}";


}
