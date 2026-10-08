package com.hongdangmu.mobile

import android.content.*
import android.provider.Telephony

class SmsReceiver: BroadcastReceiver(){
    override fun onReceive(context:Context,intent:Intent){
        if(intent.action!=Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val service=Intent(context,MobileBridgeService::class.java).apply{ action="CONNECT" }
        androidx.core.content.ContextCompat.startForegroundService(context,service)
        val messages=Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val prefs=context.getSharedPreferences("HongDangMu",Context.MODE_PRIVATE)
        val url=prefs.getString("pc_url","") ?: ""
        val deviceId=prefs.getString("device_id","") ?: ""
        if(url.isBlank()||deviceId.isBlank()) return
        val body=messages.joinToString(""){it.messageBody ?: ""}; val phone=messages.firstOrNull()?.originatingAddress ?: ""
        val b=Intent(context,MobileBridgeService::class.java).apply{ action="INCOMING"; putExtra("phone",phone); putExtra("body",body) }
        context.startService(b)
    }
}