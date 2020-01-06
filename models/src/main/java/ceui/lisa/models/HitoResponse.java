package ceui.lisa.models;

public class HitoResponse {


    /**
     * id : 191
     * hitokoto : 代表月亮消灭你！
     * type : a
     * from : 美少女战士
     * creator : edvda
     * created_at : 1468605911
     */

    private int id;
    private String hitokoto;
    private String type;
    private String from;
    private String creator;
    private String created_at;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getHitokoto() {
        return hitokoto;
    }

    public void setHitokoto(String hitokoto) {
        this.hitokoto = hitokoto;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getCreator() {
        return creator;
    }

    public void setCreator(String creator) {
        this.creator = creator;
    }

    public String getCreated_at() {
        return created_at;
    }

    public void setCreated_at(String created_at) {
        this.created_at = created_at;
    }
}
