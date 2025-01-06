package ceui.lisa.models;

import java.io.Serializable;

public class NovelSeriesItem implements Serializable {
    /**
     * id : 1222375
     * title : 桔梗成り代わり主の大正御伽草子
     * caption :
     これは転生したら桔梗に成り代わってしまった女の物語。


     クロスオーバー、成り代わり、オリ主なんでも大丈夫な方だけお願いします。



     現在最優先で更新。次の投稿は諸事情により2月10日の戌の刻(19時から21時)更新です。
     申し訳ございません。
     * is_original : false
     * is_concluded : false
     * content_count : 12
     * total_character_count : 95743
     * user : {"id":16968169,"name":"hibari","account":"0wv2j056785262u","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2020/04/03/15/55/53/18253336_630324a5569112e7521d0249447a9195_170.jpg"},"is_followed":false}
     * display_text :
     これは転生したら桔梗に成り代わってしまった女の物語。


     クロスオーバー、成り代わり、オリ主なんでも大丈夫な方だけお願いします。



     現在最優先で更新。次の投稿は諸事情により2月10日の戌の刻(19時から21時)更新です。
     申し訳ございません。

     */

    private int id;
    private String title;
    private String caption;
    private boolean is_original;
    private boolean is_concluded;
    private int content_count;
    private int total_character_count;
    private UserBean user;
    private String display_text;
    private boolean watchlist_added;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCaption() {
        return caption;
    }

    public void setCaption(String caption) {
        this.caption = caption;
    }

    public boolean isIs_original() {
        return is_original;
    }

    public void setIs_original(boolean is_original) {
        this.is_original = is_original;
    }

    public boolean isIs_concluded() {
        return is_concluded;
    }

    public void setIs_concluded(boolean is_concluded) {
        this.is_concluded = is_concluded;
    }

    public int getContent_count() {
        return content_count;
    }

    public void setContent_count(int content_count) {
        this.content_count = content_count;
    }

    public int getTotal_character_count() {
        return total_character_count;
    }

    public void setTotal_character_count(int total_character_count) {
        this.total_character_count = total_character_count;
    }

    public UserBean getUser() {
        return user;
    }

    public void setUser(UserBean user) {
        this.user = user;
    }

    public String getDisplay_text() {
        return display_text;
    }

    public void setDisplay_text(String display_text) {
        this.display_text = display_text;
    }

    public boolean isWatchlist_added() {
        return watchlist_added;
    }

    public void setWatchlist_added(boolean watchlist_added) {
        this.watchlist_added = watchlist_added;
    }

}
