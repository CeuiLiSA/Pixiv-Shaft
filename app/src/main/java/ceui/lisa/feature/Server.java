package ceui.lisa.feature;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import ceui.lisa.activities.Shaft;
import ceui.lisa.utils.Common;

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
