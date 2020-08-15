package ceui.lisa.viewmodel;

public class Hito {


    /**
     * hitokoto : 所以，他们的祭典还没结束。
     * author : 阿布碳。
     * source : 我的青春恋爱物语果然有问题
     * date : 2013.07.23 22:04:49
     * catname : Anime - 动画
     * id : 1374588289000
     */

    private String hitokoto;
    private String author;
    private String source;
    private String date;
    private String catname;
    private String id;

    public String getHitokoto() {
        return hitokoto;
    }

    public void setHitokoto(String hitokoto) {
        this.hitokoto = hitokoto;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getCatname() {
        return catname;
    }

    public void setCatname(String catname) {
        this.catname = catname;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "Hito{" +
                "hitokoto='" + hitokoto + '\'' +
                ", author='" + author + '\'' +
                ", source='" + source + '\'' +
                ", date='" + date + '\'' +
                ", catname='" + catname + '\'' +
                ", id='" + id + '\'' +
                '}';
    }
}
