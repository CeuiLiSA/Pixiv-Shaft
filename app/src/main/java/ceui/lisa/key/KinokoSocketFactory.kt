///*
// * MIT License
// *
// * Copyright (c) 2019 Perol_Notsfsssf
// *
// * Permission is hereby granted, free of charge, to any person obtaining a copy
// * of this software and associated documentation files (the "Software"), to deal
// * in the Software without restriction, including without limitation the rights
// * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// * copies of the Software, and to permit persons to whom the Software is
// * furnished to do so, subject to the following conditions:
// *
// * The above copyright notice and this permission notice shall be included in all
// * copies or substantial portions of the Software.
// *
// * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// * SOFTWARE
// */
//
//package ceui.lisa.key
//
//import com.orhanobut.logger.Logger
//import java.net.InetAddress
//import java.net.Socket
//import javax.net.ssl.SSLSocket
//import javax.net.ssl.SSLSocketFactory
//
//class KinokoSocketFactory : SSLSocketFactory() {
//
//    override fun getDefaultCipherSuites() = arrayOf<String>()
//
//    override fun getSupportedCipherSuites() = arrayOf<String>()
//
//    override fun createSocket(socket: Socket?, host: String?, port: Int, autoClose: Boolean): Socket {
//        val address = socket!!.inetAddress
//
//        if (autoClose) socket.close()
//
//        val sslSocket = (getDefault().createSocket(address, port) as SSLSocket).apply { enabledProtocols = supportedProtocols }
//        val sslSession = sslSocket.session
//
////        Log.i("!", "Address: ${address.hostAddress}, Protocol: ${sslSession.protocol}, PeerHost: ${sslSession.peerHost}, CipherSuite: ${sslSession.cipherSuite}.")
//        Logger.t("SSLSocketFactory")
//            .i("Address: ${address.hostAddress}, Protocol: ${sslSession.protocol}, PeerHost: ${sslSession.peerHost}, CipherSuite: ${sslSession.cipherSuite}.")
//        return sslSocket
//    }
//
//    override fun createSocket(host: String?, port: Int): Socket? = null
//
//    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket? = null
//
//    override fun createSocket(address: InetAddress?, port: Int): Socket? = null
//
//    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket? = null
//}
