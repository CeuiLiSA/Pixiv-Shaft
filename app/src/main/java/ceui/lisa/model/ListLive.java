package ceui.lisa.model;

import java.util.List;

import ceui.lisa.interfaces.ListShow;
import ceui.lisa.models.Live;

public class ListLive implements ListShow<Live> {


    /**
     * lives : [{"id":"4488265493601149950","created_at":"2020-01-17T09:13:36+09:00","owner":{"user":{"id":1174108,"name":"をこめ","account":"wokome","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2013/04/17/15/05/20/6117056_1f2488a1d3cfa18ace358b5556aa9ce1_170.png"},"is_followed":false}},"performers":[],"name":"げんこう配信","is_single":true,"is_adult":false,"is_r18":false,"is_r15":false,"publicity":"open","is_closed":false,"mode":"screencast","server":"wss://sfu1.pixivsketch.net/signaling","channel_id":"sketch-live-4488265493601149950-1174108","is_enabled_mic_input":false,"thumbnail_image_url":"https://img-sketch.pximg.net/c!/w=400,f=webp:jpeg/uploads/room_thumbnail/file/2350981/5817960521414605043.jpg","member_count":59,"total_audience_count":1153,"performer_count":0,"is_muted":false},{"id":"1961863261461821437","created_at":"2020-01-17T10:32:59+09:00","owner":{"user":{"id":3984968,"name":"百瀬あん","account":"momoan89","profile_image_urls":{"medium":"https://i.pximg.net/user-profile/img/2015/12/15/23/59/09/10247204_c39aa04c60428ef4f45a3983e07ae611_170.png"},"is_followed":false}},"performers":[],"name":"商業BLペン入れ","is_single":true,"is_adult":false,"is_r18":false,"is_r15":false,"publicity":"open","is_closed":false,"mode":"screencast","server":"wss://sfu1.pixivsketch.net/signaling","channel_id":"sketch-live-1961863261461821437-3984968","is_enabled_mic_input":false,"thumbnail_image_url":"https://img-sketch.pximg.net/c!/w=400,f=webp:jpeg/uploads/room_thumbnail/file/2350993/5715692081403504119.jpg","member_count":43,"total_audience_count":682,"performer_count":0,"is_muted":false}]
     * live_info : null
     * next_url : null
     */

    private Object live_info;
    private String next_url;
    private List<Live> lives;

    public Object getLive_info() {
        return live_info;
    }

    public void setLive_info(Object live_info) {
        this.live_info = live_info;
    }

    public String getNext_url() {
        return next_url;
    }

    public void setNext_url(String next_url) {
        this.next_url = next_url;
    }

    public List<Live> getLives() {
        return lives;
    }

    public void setLives(List<Live> lives) {
        this.lives = lives;
    }

    @Override
    public List<Live> getList() {
        return lives;
    }

    @Override
    public String getNextUrl() {
        return next_url;
    }
}
