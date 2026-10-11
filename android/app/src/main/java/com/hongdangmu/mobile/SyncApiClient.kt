package com.hongdangmu.mobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SyncApiClient(private val context: Context, private val store: SyncLocalStore) {
    private val prefs = context.getSharedPreferences("sync_config", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    fun configure(urlInput: String, tokenInput: String) {
        val url = urlInput.trim().trimEnd('/')
        val uri = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("서버 주소가 올바르지 않습니다") }
        val local = uri.host in setOf("localhost", "127.0.0.1", "::1")
        require(uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.scheme == "https" || (uri.scheme == "http" && local))) {
            "외부 서버는 HTTPS를 사용해야 합니다. HTTP는 localhost 테스트만 허용됩니다"
        }
        val token = tokenInput.trim()
        require(token.isNotEmpty() && token.length <= 4096) { "유효한 접속 토큰을 입력하세요" }
        prefs.edit().putString("server_url", url).putString("token_enc", encrypt(token)).putString("cursor", prefs.getString("cursor", "0")).apply()
    }
    fun clear() { prefs.edit().remove("server_url").remove("token_enc").remove("cursor").apply() }
    fun isConfigured() = !prefs.getString("server_url", null).isNullOrBlank() && !prefs.getString("token_enc", null).isNullOrBlank()

    @Synchronized fun syncOnce(): JSONObject {
        val base = prefs.getString("server_url", null) ?: throw IllegalStateException("서버 주소를 먼저 설정하세요")
        val token = decrypt(prefs.getString("token_enc", null) ?: throw IllegalStateException("접속 토큰을 먼저 설정하세요"))
        val pending = store.pending()
        var pushed = 0
        if (pending.isNotEmpty()) {
            val arr = JSONArray(); pending.forEach { arr.put(it) }
            val response = request(base + "/v1/sync/push", token, "POST", JSONObject().put("events", arr).toString())
            val ack = response.optJSONArray("acknowledged_event_ids") ?: throw IllegalStateException("서버 확인 응답이 올바르지 않습니다")
            val allowed = pending.map { it.getString("event_id") }.toSet(); val confirmed = mutableSetOf<String>()
            for (i in 0 until ack.length()) { val id = ack.getString(i); require(allowed.contains(id)) { "요청에 없는 이벤트를 확인 처리한 서버 응답입니다" }; confirmed.add(id) }
            pushed = store.acknowledge(confirmed)
        }
        val cursor = prefs.getString("cursor", "0") ?: "0"
        val pulled = request("$base/v1/sync/pull?cursor=$cursor&limit=200", token, "GET", null)
        val events = pulled.optJSONArray("events") ?: throw IllegalStateException("수신 이벤트 목록이 올바르지 않습니다")
        val nextCursor = pulled.optString("next_cursor", "")
        require(nextCursor.matches(Regex("[0-9]{1,20}"))) { "서버 동기화 커서가 올바르지 않습니다" }
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            require(event.has("event_id") && event.has("entity_type") && event.has("entity_id") && event.has("revision") && event.has("payload") && event.has("origin_device")) { "수신 이벤트 필드가 부족합니다" }
            store.storeRemote(event)
        }
        prefs.edit().putString("cursor", nextCursor).apply()
        return JSONObject().put("pushed_acknowledged", pushed).put("pulled", events.length()).put("cursor", nextCursor).put("local", store.status())
    }

    private fun request(url: String, token: String, method: String, body: String?): JSONObject {
        val builder = Request.Builder().url(url).header("Authorization", "Bearer $token").header("Accept", "application/json")
        if (method == "POST") builder.post((body ?: "{}").toRequestBody(jsonType)) else builder.get()
        http.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("동기화 서버 오류: HTTP ${response.code}")
            val raw = response.body?.string() ?: "{}"
            return JSONObject(raw)
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("hongdangmu_sync_aes", null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder("hongdangmu_sync_aes", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return gen.generateKey()
    }
    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val data = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP); require(data.size > 12) { "저장된 토큰 형식이 올바르지 않습니다" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }
}
