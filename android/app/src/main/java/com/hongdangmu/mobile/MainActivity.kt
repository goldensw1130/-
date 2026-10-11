package com.hongdangmu.mobile

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var url: EditText
    private lateinit var code: EditText
    private lateinit var status: TextView
    private lateinit var token: EditText
    private lateinit var noteTitle: EditText
    private lateinit var noteBody: EditText
    private lateinit var syncStore: SyncLocalStore
    private lateinit var syncClient: SyncApiClient
    private val io = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences("HongDangMu", MODE_PRIVATE) }
    private val syncPrefs by lazy { getSharedPreferences("sync_config", MODE_PRIVATE) }
    private val pairReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { runOnUiThread { status.text = "PC 연결됨\n페어링 코드 6자리를 입력하세요" } }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        createChannel()
        syncStore = SyncLocalStore(this)
        syncClient = SyncApiClient(this, syncStore)
        registerReceiver(pairReceiver, IntentFilter(MobileBridgeService.ACTION_PAIR_REQUIRED), RECEIVER_NOT_EXPORTED)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun heading(text: String) { root.addView(TextView(this).apply { this.text = text; textSize = 18f; setPadding(0, 18, 0, 6) }) }
        fun field(hintText: String, value: String = ""): EditText = EditText(this).apply { hint = hintText; setText(value); setSingleLine(false) }
        fun button(text: String, action: () -> Unit) { root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }) }

        status = TextView(this).apply { text = "홍당무 모바일\n동기화 상태 확인 중"; textSize = 16f }
        root.addView(status)

        heading("PC 문자 연동")
        url = field("PC 주소 예: ws://192.168.0.10:8765", prefs.getString("pc_url", "") ?: "")
        root.addView(url)
        button("PC 연결 / 페어링 요청") {
            prefs.edit().putString("pc_url", url.text.toString().trim()).apply()
            ContextCompat.startForegroundService(this, Intent(this, MobileBridgeService::class.java).apply { action = "CONNECT"; putExtra("url", url.text.toString()) })
        }
        code = field("PC에 표시된 6자리 페어링 코드").apply { inputType = 2 }
        root.addView(code)
        button("페어링 승인") { ContextCompat.startForegroundService(this, Intent(this, MobileBridgeService::class.java).apply { action = "PAIR_CODE"; putExtra("code", code.text.toString()) }) }
        val phone = field("전화번호"); root.addView(phone)
        val body = field("문자 내용"); root.addView(body)
        button("문자 보내기 테스트") { startService(Intent(this, MobileBridgeService::class.java).apply { action = "SMS_SEND"; putExtra("phone", phone.text.toString()); putExtra("body", body.text.toString()) }) }

        heading("PC·모바일 데이터 동기화")
        val syncUrl = field("동기화 서버 주소 (외부는 HTTPS)", syncPrefs.getString("server_url", "") ?: "")
        root.addView(syncUrl)
        token = field("동기화 접속 토큰 (저장 시 암호화)")
        root.addView(token)
        button("동기화 서버 설정 저장") {
            try {
                syncClient.configure(syncUrl.text.toString(), token.text.toString())
                token.setText("")
                status.text = "동기화 서버 설정 저장 완료\n토큰은 Android Keystore로 암호화됩니다."
                refreshStatus()
            } catch (e: Exception) { status.text = "설정 오류: ${e.message}" }
        }
        button("동기화 실행") {
            status.text = "동기화 진행 중…"
            io.execute {
                try {
                    val result = syncClient.syncOnce()
                    runOnUiThread { status.text = "동기화 완료\n전송 확인: ${result.optInt("pushed_acknowledged")} · 수신: ${result.optInt("pulled")}\n${syncStore.status()}" }
                } catch (e: Exception) { runOnUiThread { status.text = "동기화 실패: ${e.message}\n대기 데이터는 로컬에 유지됩니다." } }
            }
        }
        button("동기화 설정 삭제") { syncClient.clear(); syncUrl.setText(""); token.setText(""); status.text = "동기화 설정을 삭제했습니다." }

        heading("모바일 메모 · 동기화 확인용 데이터")
        noteTitle = field("메모 제목"); root.addView(noteTitle)
        noteBody = field("메모 내용"); noteBody.minLines = 2; noteBody.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); root.addView(noteBody)
        button("메모를 동기화 대기열에 추가") {
            try {
                val eventId = syncStore.addNote(noteTitle.text.toString(), noteBody.text.toString())
                noteTitle.setText(""); noteBody.setText("")
                status.text = "메모를 로컬 대기열에 저장했습니다.\n이벤트: $eventId\n서버 동기화를 실행하면 전송됩니다."
                refreshStatus()
            } catch (e: Exception) { status.text = "메모 저장 오류: ${e.message}" }
        }
        button("저장된 메모 관리 · 수정/삭제") { showNotes() }
        button("저장된 동기화 상태 새로고침") { refreshStatus() }

        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 200)
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 201)
        refreshStatus()
    }

    private fun showNotes() {
        val notes = syncStore.recentNotes()
        if (notes.isEmpty()) { status.text = "저장된 메모가 없습니다."; return }
        val labels = notes.map { "${it.title}\n${it.body.take(80)}" }.toTypedArray()
        AlertDialog.Builder(this).setTitle("저장된 메모 · ${notes.size}개").setItems(labels) { _, which ->
            val note = notes[which]
            val titleField = EditText(this).apply { setText(note.title); hint = "메모 제목" }
            val bodyField = EditText(this).apply { setText(note.body); hint = "메모 내용"; minLines = 3 }
            val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 12, 36, 0); addView(titleField); addView(bodyField) }
            AlertDialog.Builder(this).setTitle("메모 수정").setView(panel)
                .setPositiveButton("수정 저장") { _, _ ->
                    try { val id = syncStore.updateNote(note.id, titleField.text.toString(), bodyField.text.toString()); status.text = "메모 수정이 동기화 대기열에 저장되었습니다.\n이벤트: $id"; refreshStatus() }
                    catch (e: Exception) { status.text = "메모 수정 오류: ${e.message}" }
                }
                .setNeutralButton("삭제") { _, _ ->
                    AlertDialog.Builder(this).setTitle("메모 삭제").setMessage("이 메모를 삭제하고 다른 기기에도 반영할까요?")
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                            try { val id = syncStore.deleteNote(note.id); status.text = "삭제 이벤트가 동기화 대기열에 저장되었습니다.\n이벤트: $id"; refreshStatus() }
                            catch (e: Exception) { status.text = "메모 삭제 오류: ${e.message}" }
                        }.show()
                }.setNegativeButton("닫기", null).show()
        }.setNegativeButton("닫기", null).show()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized || !::syncStore.isInitialized || !::syncClient.isInitialized) return
        val local = syncStore.status()
        status.text = "홍당무 모바일\n서버 설정: ${if (syncClient.isConfigured()) "완료" else "미설정"}\n전송 대기: ${local.optInt("pending")} · 수신 이벤트: ${local.optInt("received")} · 메모: ${local.optInt("notes")}"
    }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("hongdangmu", "홍당무 모바일", NotificationManager.IMPORTANCE_LOW)) }
    override fun onDestroy() { unregisterReceiver(pairReceiver); io.shutdown(); super.onDestroy() }
}
