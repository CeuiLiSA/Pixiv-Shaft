package ceui.lisa.models;

import java.util.List;

public class Live {


    /**
     * id : 4488265493601149950
     * created_at : 2020-01-17T09:13:36+09:00
     * owner : {"user":{"id":1174108,"name":"をこめ","account":"wokome","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2013/04/17/15/05/20/6117056_1f2488a1d3cfa18ace358b5556aa9ce1_170.png"},"is_followed":false}}
     * performers : []
     * name : げんこう配信
     * is_single : true
     * is_adult : false
     * is_r18 : false
     * is_r15 : false
     * publicity : open
     * is_closed : false
     * mode : screencast
     * server : wss://sfu1.pixivsketch.net/signaling
     * channel_id : sketch-live-4488265493601149950-1174108
     * is_enabled_mic_input : false
     * thumbnail_image_url : https://img-sketch.pximg.net/c!/w=400,f=webp:jpeg/uploads/room_thumbnail/file/2350981/5817960521414605043.jpg
     * member_count : 59
     * total_audience_count : 1153
     * performer_count : 0
     * is_muted : false
     */

    private String id;
    private String created_at;
    private OwnerBean owner;
    private String name;
    private boolean is_single;
    private boolean is_adult;
    private boolean is_r18;
    private boolean is_r15;
    private String publicity;
    private boolean is_closed;
    private String mode;
    private String server;
    private String channel_id;
    private boolean is_enabled_mic_input;
    private String thumbnail_image_url;
    private int member_count;
    private int total_audience_count;
    private int performer_count;
    private boolean is_muted;
    private List<?> performers;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCreated_at() {
        return created_at;
    }

    public void setCreated_at(String created_at) {
        this.created_at = created_at;
    }

    public OwnerBean getOwner() {
        return owner;
    }

    public void setOwner(OwnerBean owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isIs_single() {
        return is_single;
    }

    public void setIs_single(boolean is_single) {
        this.is_single = is_single;
    }

    public boolean isIs_adult() {
        return is_adult;
    }

    public void setIs_adult(boolean is_adult) {
        this.is_adult = is_adult;
    }

    public boolean isIs_r18() {
        return is_r18;
    }

    public void setIs_r18(boolean is_r18) {
        this.is_r18 = is_r18;
    }

    public boolean isIs_r15() {
        return is_r15;
    }

    public void setIs_r15(boolean is_r15) {
        this.is_r15 = is_r15;
    }

    public String getPublicity() {
        return publicity;
    }

    public void setPublicity(String publicity) {
        this.publicity = publicity;
    }

    public boolean isIs_closed() {
        return is_closed;
    }

    public void setIs_closed(boolean is_closed) {
        this.is_closed = is_closed;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getServer() {
        return server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getChannel_id() {
        return channel_id;
    }

    public void setChannel_id(String channel_id) {
        this.channel_id = channel_id;
    }

    public boolean isIs_enabled_mic_input() {
        return is_enabled_mic_input;
    }

    public void setIs_enabled_mic_input(boolean is_enabled_mic_input) {
        this.is_enabled_mic_input = is_enabled_mic_input;
    }

    public String getThumbnail_image_url() {
        return thumbnail_image_url;
    }

    public void setThumbnail_image_url(String thumbnail_image_url) {
        this.thumbnail_image_url = thumbnail_image_url;
    }

    public int getMember_count() {
        return member_count;
    }

    public void setMember_count(int member_count) {
        this.member_count = member_count;
    }

    public int getTotal_audience_count() {
        return total_audience_count;
    }

    public void setTotal_audience_count(int total_audience_count) {
        this.total_audience_count = total_audience_count;
    }

    public int getPerformer_count() {
        return performer_count;
    }

    public void setPerformer_count(int performer_count) {
        this.performer_count = performer_count;
    }

    public boolean isIs_muted() {
        return is_muted;
    }

    public void setIs_muted(boolean is_muted) {
        this.is_muted = is_muted;
    }

    public List<?> getPerformers() {
        return performers;
    }

    public void setPerformers(List<?> performers) {
        this.performers = performers;
    }

    public static class OwnerBean {
        /**
         * user : {"id":1174108,"name":"をこめ","account":"wokome","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2013/04/17/15/05/20/6117056_1f2488a1d3cfa18ace358b5556aa9ce1_170.png"},"is_followed":false}
         */

        private UserBean user;

        public UserBean getUser() {
            return user;
        }

        public void setUser(UserBean user) {
            this.user = user;
        }
    }
}
