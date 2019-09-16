/*MIT License

Copyright (c) 2019 Perol_Notsfsssf

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.*/

package ceui.lisa.http

import okhttp3.Dns
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.net.InetAddress


class RubyHttpDns : Dns {
    var retrofit = Retrofit.Builder()
            .baseUrl("https://1.0.0.1/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    var oneZeroService: OneZeroService
    val list = ArrayList<InetAddress>()

    init {

        oneZeroService = retrofit.create(OneZeroService::class.java)
    }

    override fun lookup(hostname: String): List<InetAddress> {
        if (list.isNotEmpty()) {
            return list
        }
        try {
            val response = oneZeroService.getItem("application/dns-json", hostname, "A", "false", "false").execute()
            val oneZeroResponse = response.body()
            if (oneZeroResponse != null) {
                if (oneZeroResponse.answer != null) {
                    if (oneZeroResponse.answer.isNotEmpty())
                        for (i in oneZeroResponse.answer) {
                            list.addAll(InetAddress.getAllByName(i.data))
                        }
                } else {
                    list.add(InetAddress.getByName("210.140.131.222"))
                    list.add(InetAddress.getByName("210.140.131.219"))
                    return list
                }

            }
            return list
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list.add(InetAddress.getByName("210.140.131.222"))
        list.add(InetAddress.getByName("210.140.131.219"))
        return list
    }

}