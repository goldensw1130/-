package com.hongdangmu.mobile

import android.app.*
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var token: EditText
    private lateinit var noteTitle: EditText
    private lateinit var noteBody: EditText
    private var editingNoteId: String? = null
    private lateinit var syncStore: SyncLocalStore
    private lateinit var syncClient: SyncApiClient
    private val io = Executors.newSingleThreadExecutor()
    private val syncPrefs by lazy { getSharedPreferences("sync_config", MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        syncStore = SyncLocalStore(this)
        syncClient = SyncApiClient(this, syncStore)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        fun heading(text: String) { root.addView(TextView(this).apply { this.text = text; textSize = 18f; setPadding(0, 18, 0, 6) }) }
        fun field(hintText: String, value: String = ""): EditText = EditText(this).apply { hint = hintText; setText(value); setSingleLine(false) }
        fun button(text: String, action: () -> Unit) { root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } }) }

        status = TextView(this).apply { text = "홍당무 모바일\n동기화 상태 확인 중"; textSize = 16f }
        root.addView(status)

        heading("보안 안내")
        root.addView(TextView(this).apply {
            text = "안전 동기화 버전: SMS 읽기·수신·발신 권한, 접근성 서비스, 화면 오버레이 권한을 요청하지 않습니다. 외부 동기화 서버는 HTTPS만 허용합니다."
            textSize = 14f
        })

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
        button("동기화 설정 삭제") { syncClient.clear(); syncUrl.setText(""); token.setText(""); status.text = "동기화 서버 설정을 삭제했습니다." }

        heading("모바일 메모 · 동기화 확인용 데이터")
        noteTitle = field("메모 제목"); root.addView(noteTitle)
        noteBody = field("메모 내용"); noteBody.minLines = 2
        noteBody.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        root.addView(noteBody)
        button("새 메모 저장 / 선택 메모 수정") {
            try {
                val currentId = editingNoteId
                val eventId = if (currentId == null) syncStore.addNote(noteTitle.text.toString(), noteBody.text.toString())
                    else syncStore.updateNote(currentId, noteTitle.text.toString(), noteBody.text.toString())
                editingNoteId = null; noteTitle.setText(""); noteBody.setText("")
                status.text = "메모를 로컬 대기열에 저장했습니다.\n이벤트: $eventId\n서버 동기화를 실행하면 전달됩니다."
                refreshStatus()
            } catch (e: Exception) { status.text = "메모 저장 오류: ${e.message}" }
        }
        button("저장된 메모 선택 / 불러오기") {
            val notes = syncStore.listNotes()
            if (notes.isEmpty()) { status.text = "저장된 메모가 없습니다."; return@button }
            val labels = notes.map { "${it.title} · ${it.updatedAt}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("수정할 메모 선택")
                .setItems(labels) { _, which ->
                    val note = notes[which]; editingNoteId = note.id
                    noteTitle.setText(note.title); noteBody.setText(note.body)
                    status.text = "메모를 불러왔습니다. 수정 후 저장하거나 아래 삭제 버튼을 사용하세요."
                }.setNegativeButton("취소", null).show()
        }
        button("선택 메모 삭제") {
            val notes = syncStore.listNotes()
            if (notes.isEmpty()) { status.text = "삭제할 메모가 없습니다."; return@button }
            val labels = notes.map { "${it.title} · ${it.updatedAt}" }.toTypedArray()
            AlertDialog.Builder(this).setTitle("삭제할 메모 선택")
                .setItems(labels) { _, which ->
                    val note = notes[which]
                    AlertDialog.Builder(this).setTitle("메모 삭제 확인").setMessage("‘${note.title}’ 메모를 삭제하고 동기화 대기열에 기록할까요?")
                        .setNegativeButton("취소", null).setPositiveButton("삭제") { _, _ ->
                            try {
                                val eventId = syncStore.deleteNote(note.id)
                                if (editingNoteId == note.id) { editingNoteId = null; noteTitle.setText(""); noteBody.setText("") }
                                status.text = "삭제 변경사항을 저장했습니다. 서버 동기화를 실행하면 다른 기기로 전달됩니다.\n이벤트: $eventId"
                                refreshStatus()
                            } catch (e: Exception) { status.text = "메모 삭제 오류: ${e.message}" }
                        }.show()
                }.setNegativeButton("취소", null).show()
        }
        button("저장된 메모 목록 새로고침") {
            val notes = syncStore.listNotes()
            status.text = if (notes.isEmpty()) "저장된 메모가 없습니다." else "저장된 메모 (${notes.size})\n\n" + notes.joinToString("\n\n──────────\n\n") { "${it.title}\n${it.body}\n${it.updatedAt}" }
        }
        button("저장된 동기화 상태 새로고침") { refreshStatus() }

        setContentView(ScrollView(this).apply { addView(root) })
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::status.isInitialized || !::syncStore.isInitialized || !::syncClient.isInitialized) return
        val local = syncStore.status()
        status.text = "홍당무 모바일\n서버 설정: ${if (syncClient.isConfigured()) "완료" else "미설정"}\n전송 대기: ${local.optInt("pending")} · 수신 이벤트: ${local.optInt("received")} · 메모: ${local.optInt("notes")}"
    }

    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}
