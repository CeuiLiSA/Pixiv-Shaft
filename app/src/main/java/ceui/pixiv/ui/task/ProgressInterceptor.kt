package ceui.pixiv.ui.task

import okhttp3.Interceptor
import okhttp3.Response

class ProgressInterceptor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        val listener = request.tag(KProgressListener::class.java)
        val body = response.body
        if (listener == null) return response

        val progressBody = KProgressResponseBody(body, listener)
        return response.newBuilder().body(progressBody).build()
    }
}
