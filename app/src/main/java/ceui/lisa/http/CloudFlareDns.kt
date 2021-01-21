package ceui.lisa.http

import okhttp3.Dns
import java.net.InetAddress

class CloudFlareDns(private val service: CloudFlareDNSService) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()

        when (hostname) {
            "oauth.secure.pixiv.net" -> addresses.addAll(listOf("210.140.131.209").map { InetAddress.getByName(it) })
            "app-api.pixiv.net" -> addresses.addAll(listOf("210.140.131.208").map { InetAddress.getByName(it) })
        }

        val response = service.query(name = hostname).execute().body()
        response?.Answer?.flatMap {
            InetAddress.getAllByName(it.data).toList()
        }?.run {
            addresses.addAll(this)
        }

        return addresses
    }
}