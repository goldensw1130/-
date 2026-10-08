package com.hongdangmu.mobile

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.core.content.ContextCompat

class MainActivity:Activity(){
    private lateinit var url:EditText; private lateinit var code:EditText; private lateinit var status:TextView
    private val prefs by lazy{getSharedPreferences("HongDangMu",MODE_PRIVATE)}
    private val pairReceiver=object:BroadcastReceiver(){ override fun onReceive(c:Context,i:Intent){ runOnUiThread{status.text="PC 연결됨\n페어링 코드 6자리를 입력하세요"} } }
    override fun onCreate(b:Bundle?){super.onCreate(b); createChannel(); registerReceiver(pairReceiver,IntentFilter(MobileBridgeService.ACTION_PAIR_REQUIRED),RECEIVER_NOT_EXPORTED)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,24,24,24)}
        status=TextView(this).apply{text="홍당무 모바일\n연결 안 됨";textSize=18f}; root.addView(status)
        url=EditText(this).apply{hint="PC 주소 예: ws://192.168.0.10:8765";setText(prefs.getString("pc_url","") ?: "")};root.addView(url)
        val connect=Button(this).apply{text="PC 연결 / 페어링 요청"};root.addView(connect)
        code=EditText(this).apply{hint="PC에 표시된 6자리 페어링 코드";inputType=2;setSingleLine()};root.addView(code)
        val pair=Button(this).apply{text="페어링 승인"};root.addView(pair)
        val send=Button(this).apply{text="문자 보내기 테스트"};root.addView(send)
        val phone=EditText(this).apply{hint="전화번호"}; root.addView(phone)
        val body=EditText(this).apply{hint="문자 내용"}; root.addView(body)
        setContentView(root)
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),200)
        if(checkSelfPermission(Manifest.permission.SEND_SMS)!=PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.SEND_SMS,Manifest.permission.RECEIVE_SMS,Manifest.permission.READ_SMS),201)
        connect.setOnClickListener{ ContextCompat.startForegroundService(this,Intent(this,MobileBridgeService::class.java).apply{action="CONNECT";putExtra("url",url.text.toString())}) }
        pair.setOnClickListener{ val s=Intent(this,MobileBridgeService::class.java).apply{action="PAIR_CODE";putExtra("code",code.text.toString())}; ContextCompat.startForegroundService(this,s) }
        send.setOnClickListener{ startService(Intent(this,MobileBridgeService::class.java).apply{action="SMS_SEND";putExtra("phone",phone.text.toString());putExtra("body",body.text.toString())}) }
    }
    private fun createChannel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("hongdangmu","홍당무 모바일",NotificationManager.IMPORTANCE_LOW))}
    override fun onDestroy(){unregisterReceiver(pairReceiver);super.onDestroy()}
}