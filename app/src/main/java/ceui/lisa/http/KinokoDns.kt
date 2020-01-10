//package ceui.lisa.http
//
//import ceui.lisa.key.ServiceFactory
//import okhttp3.Dns
//import java.net.InetAddress
//import okhttp3.HttpUrl.Companion.toHttpUrl
//
//
//class KinokoDns : Dns {
//    private val addressList = mutableListOf<InetAddress>()
//    override fun lookup(hostname: String): List<InetAddress> {
//        if (addressList.isNotEmpty()) return addressList
//        val defaultList = listOf(
//                "210.140.131.219",
//                "210.140.131.222",
//                "210.140.131.224"
//        ).map { InetAddress.getByName(it) }
//        val service =
//                ServiceFactory.create<CloudflareService>(CloudflareService.URL_DNS_RESOLVER.toHttpUrl())
//
//        try {
//            val response = service.queryDns(name = hostname).blockingSingle()
//            response.answer.flatMap { InetAddress.getAllByName(it.data).toList() }.also {
//                addressList.addAll(it)
//            }
//        } catch (e: Exception) {
//
//        }
//
//        return if (addressList.isNotEmpty())
//            addressList
//        else {
//            addressList.addAll(defaultList)
//            addressList
//        }
//
//    }
//}