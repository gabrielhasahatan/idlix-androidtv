package com.idlix.data.api

import android.content.Context
import com.idlix.data.BuildConfig
import com.idlix.data.model.MovieListResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object ApiClient {

    private const val PREF_KEY_SERVER_URL = "custom_backend_url"
    private const val DEFAULT_URL = BuildConfig.BACKEND_API_URL

    @Volatile
    var applicationContext: Context? = null
        internal set

    @Volatile
    private var userAgentHeader: String = "Idlix-Client/1.0"

    fun init(context: Context, userAgent: String? = null) {
        this.applicationContext = context.applicationContext
        if (!userAgent.isNullOrBlank()) {
            this.userAgentHeader = userAgent
        }
    }

    val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    @Volatile
    private var currentBaseUrl: String? = null

    fun getDefaultUrl(): String = if (DEFAULT_URL.endsWith("/")) DEFAULT_URL else "$DEFAULT_URL/"

    fun getBaseUrl(): String {
        currentBaseUrl?.let { return it }
        val saved = try {
            val prefs = applicationContext?.getSharedPreferences("idlix_config", Context.MODE_PRIVATE)
            prefs?.getString(PREF_KEY_SERVER_URL, null)?.trim()
        } catch (_: Exception) {
            null
        }

        val url = if (!saved.isNullOrBlank()) {
            if (saved.endsWith("/")) saved else "$saved/"
        } else {
            getDefaultUrl()
        }
        currentBaseUrl = url
        return url
    }

    fun setBaseUrl(newUrl: String) {
        val trimmed = newUrl.trim()
        val formatted = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        currentBaseUrl = formatted
        try {
            val prefs = applicationContext?.getSharedPreferences("idlix_config", Context.MODE_PRIVATE)
            prefs?.edit()?.putString(PREF_KEY_SERVER_URL, formatted)?.apply()
        } catch (_: Exception) {}

        synchronized(this) {
            _idlixApi = null
            _majorplayApi = null
        }
    }

    suspend fun testBackendConnection(targetUrl: String): Result<String> = withContext(Dispatchers.IO) {
        val trimmed = targetUrl.trim()
        if (trimmed.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Server URL cannot be empty"))
        }
        val formatted = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        val testEndpoint = "${formatted}api/movies?page=1&limit=1"
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url(testEndpoint)
                .get()
                .build()
            okHttpClient.newCall(req).execute().use { resp ->
                val duration = System.currentTimeMillis() - start
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val parsed = try {
                        json.decodeFromString<MovieListResponse>(body)
                    } catch (_: Exception) { null }
                    val count = parsed?.data?.size ?: 0
                    Result.success("Connected successfully (${duration}ms, $count items)")
                } else {
                    Result.failure(Exception("HTTP error: ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val headerInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        val hostOrigin = "${originalRequest.url.scheme}://${originalRequest.url.host}"

        val builder = originalRequest.newBuilder()
            .header("Accept", "application/json, text/plain, */*")
            .header("User-Agent", userAgentHeader)

        if (originalRequest.header("Referer") == null) {
            builder.header("Referer", "$hostOrigin/")
        }
        if (originalRequest.header("Origin") == null) {
            builder.header("Origin", hostOrigin)
        }

        chain.proceed(builder.build())
    }

    private fun createCloudflareDoh(context: Context?): Dns {
        return try {
            val appCache = if (context != null) {
                try {
                    Cache(File(context.cacheDir, "doh_cache"), 10L * 1024 * 1024)
                } catch (_: Exception) {
                    null
                }
            } else null

            val bootstrapClient = OkHttpClient.Builder()
                .apply {
                    if (appCache != null) cache(appCache)
                }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()

            val doh = DnsOverHttps.Builder()
                .client(bootstrapClient)
                .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
                .bootstrapDnsHosts(
                    InetAddress.getByName("1.1.1.1"),
                    InetAddress.getByName("1.0.0.1"),
                    InetAddress.getByName("2606:4700:4700::1111"),
                    InetAddress.getByName("2606:4700:4700::1001")
                )
                .includeIPv6(true)
                .build()

            // Composite DNS with fallback to system Dns.SYSTEM if DoH fails
            object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    return try {
                        doh.lookup(hostname)
                    } catch (e: Exception) {
                        try {
                            Dns.SYSTEM.lookup(hostname)
                        } catch (sysEx: Exception) {
                            throw UnknownHostException("DNS resolution failed for $hostname: ${e.message} / ${sysEx.message}")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            Dns.SYSTEM
        }
    }

    val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(createCloudflareDoh(applicationContext))
            .addInterceptor(headerInterceptor)
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun buildRetrofit(baseUrl: String): Retrofit {
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Volatile
    private var _idlixApi: IdlixApiService? = null
    val idlixApi: IdlixApiService
        get() = _idlixApi ?: synchronized(this) {
            _idlixApi ?: buildRetrofit(getBaseUrl()).create(IdlixApiService::class.java).also { _idlixApi = it }
        }

    @Volatile
    private var _majorplayApi: MajorplayApiService? = null
    val majorplayApi: MajorplayApiService
        get() = _majorplayApi ?: synchronized(this) {
            _majorplayApi ?: buildRetrofit(getBaseUrl()).create(MajorplayApiService::class.java).also { _majorplayApi = it }
        }
}
