package com.kail.location.utils

import android.content.Context
import com.kail.location.BuildConfig
import com.kail.location.models.UpdateInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object UpdateChecker {
    private const val TAG = "UpdateChecker"

    private val trustAllCertificates = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val okHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(
            SSLContext.getInstance("TLS").apply {
                init(null, arrayOf<TrustManager>(trustAllCertificates), java.security.SecureRandom())
            }.socketFactory,
            trustAllCertificates
        )
        .hostnameVerifier { _, _ -> true }
        .build()

    fun check(context: Context, callback: (UpdateInfo?, String?) -> Unit) {
        val currentVersionCode = try {
            context.packageManager.getPackageInfo(context.packageName, 0).let {
                it.longVersionCode.toInt()
            }
        } catch (e: Exception) {
            0
        }

        val url = "${BuildConfig.APP_API_URL}/infra/app-version/check?versionCode=$currentVersionCode"
        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("tenant-id", "1")
            .build()

        KailLog.i(context, TAG, "check: requesting latest version, current versionCode=$currentVersionCode")

        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                KailLog.w(context, TAG, "check: network failure: ${e.message}")
                callback(null, e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string()
                if (body == null) {
                    KailLog.w(context, TAG, "check: empty response (http=${response.code})")
                    callback(null, "Empty response")
                    return
                }

                try {
                    val root = JSONObject(body)
                    val code = root.optInt("code", -1)
                    if (code != 0) {
                        KailLog.w(context, TAG, "check: api error code=$code msg=${root.optString("msg")}")
                        callback(null, root.optString("msg", "请求失败"))
                        return
                    }

                    val data = root.optJSONObject("data") ?: run {
                        callback(null, null)
                        return
                    }

                    val hasUpdate = data.optBoolean("hasUpdate", false)
                    if (!hasUpdate) {
                        KailLog.i(context, TAG, "check: no update available")
                        callback(null, null)
                        return
                    }

                    val updateInfo = UpdateInfo(
                        version = data.optString("versionName", ""),
                        content = data.optString("description", ""),
                        downloadUrl = data.optString("fileUrl", ""),
                        filename = "kail-location-${data.optString("versionName", "")}.apk"
                    )

                    KailLog.i(context, TAG, "check: update available ${updateInfo.version}")
                    callback(updateInfo, null)
                } catch (e: Exception) {
                    KailLog.e(context, TAG, "check: parse error", e)
                    callback(null, e.message)
                }
            }
        })
    }
}
