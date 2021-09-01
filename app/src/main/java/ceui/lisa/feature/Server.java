package ceui.lisa.feature;

import java.net.ServerSocket;
import java.net.Socket;

import ceui.lisa.activities.Shaft;

public class Server extends Thread {

    @Override
    public void run() {
        try {
            ServerSocket s = new ServerSocket(WeissUtil.PORT);
            for (;;) {
                // 接受客户端请求
                Socket incoming = s.accept();
                String json = Shaft.sGson.toJson(incoming);



            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
