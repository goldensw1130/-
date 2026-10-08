package com.hongdangmu.mobile

import android.app.*; import android.content.*; import android.os.*; import android.telephony.SmsManager
import androidx.core.app.NotificationCompat; import androidx.core.app.ServiceCompat; import android.content.pm.ServiceInfo; import okhttp3.*; import org.json.JSONObject
import java.security.KeyFactory; import java.security.KeyPairGenerator; import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement; import java.util.Base64; import java.util.UUID; import java.util.concurrent.ConcurrentLinkedQueue; import java.util.concurrent.TimeUnit
import javax.crypto.Cipher; import javax.crypto.Mac; import javax.crypto.spec.GCMParameterSpec; import javax.crypto.spec.SecretKeySpec
import javax.crypto.KeyGenerator
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class MobileBridgeService:Service(){
 private var ws:WebSocket?=null; private lateinit var client:OkHttpClient; @Volatile private var authenticated=false; private var reconnecting=false
 private val queue=ConcurrentLinkedQueue<String>(); private val prefs by lazy{getSharedPreferences("HongDangMu",MODE_PRIVATE)}
 override fun onCreate(){super.onCreate(); channel(); client=OkHttpClient.Builder().readTimeout(0,TimeUnit.MILLISECONDS).build(); ServiceCompat.startForeground(this,1001,notice("모바일 브릿지 준비"),ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)}
 override fun onStartCommand(i:Intent?,f:Int,s:Int):Int{loadQueue(); when(i?.action){"CONNECT"->connect(i.getStringExtra("url")?:prefs.getString("pc_url","")?:"");"SMS_SEND"->sendSms(i.getStringExtra("phone")?:"",i.getStringExtra("body")?:"");"INCOMING"->sendIncoming(i.getStringExtra("phone")?:"",i.getStringExtra("body")?:"");"PAIR_CODE"->pair(i.getStringExtra("code")?:"")}; return START_STICKY}
 private fun connect(raw:String){val u=raw.trim().replaceFirst("http://","ws://").replaceFirst("https://","wss://");if(u.isBlank())return;prefs.edit().putString("pc_url",u).apply();ws?.cancel();ws=client.newWebSocket(Request.Builder().url(u).build(),object:WebSocketListener(){
  override fun onOpen(w:WebSocket,r:Response){authenticated=false;val key=loadSecret("auth_key")?:"";if(key.isNotBlank())w.send(auth(prefs.getString("device_id",deviceId())!!,key))else{val kp=genKeys();prefs.edit().putString("pair_priv",b64(kp.private.encoded)).putString("pair_pub",b64(kp.public.encoded)).putString("pair_client_nonce",UUID.randomUUID().toString()).apply();w.send(JSONObject().put("type","pair_request").put("client_pub",b64(kp.public.encoded)).put("client_nonce",prefs.getString("pair_client_nonce","")).toString())};update("PC 연결됨")}
  override fun onMessage(w:WebSocket,t:String){handle(JSONObject(t),w)};override fun onFailure(w:WebSocket,t:Throwable,r:Response?){authenticated=false;update("연결 끊김");schedule()};override fun onClosed(w:WebSocket,c:Int,s:String){authenticated=false;schedule()}})}
 private fun handle(o:JSONObject,w:WebSocket){if(o.optString("type")=="secure"){val k=loadSecret("auth_key");if(k==null){update("보안키 없음");return};try{val plain=openSecure(k,o);handle(JSONObject(plain),w)}catch(_:Exception){update("암호화 메시지 검증 실패")};return};when(o.optString("type")){"pair_token"->{prefs.edit().putString("pair_token",o.optString("token")).putString("server_pub",o.optString("server_pub")).putString("server_nonce",o.optString("server_nonce")).apply();update("PC에서 페어링 코드 입력");sendBroadcast(Intent(ACTION_PAIR_REQUIRED).setPackage(packageName))}
 "registered"->{val key=decryptKey(o);prefs.edit().putString("device_id",o.optString("device_id")).remove("pair_token").remove("pair_priv").remove("pair_pub").remove("pair_client_nonce").remove("server_pub").remove("server_nonce").apply();saveSecret("auth_key",key);authenticated=true;update("PC 페어링 완료");flushQueues(w)}
 "authenticated"->{authenticated=true;update("PC 연결됨");flushQueues(w)};"sms_send"->{if(verifyEvent(o))sendSms(o.optString("phone"),o.optString("body"))};"ack"->{if(verifyEvent(o))o.optString("message_id").takeIf{it.isNotBlank()}?.let{removeQueued(it)}};"error"->update("오류: "+o.optString("code"))}}
 private fun pair(code:String){val token=prefs.getString("pair_token","")?:"";if(token.isBlank()||code.length!=6)return;val id=prefs.getString("device_id",deviceId())!!;val proof=hmacSha256(code,(prefs.getString("server_nonce","")?:"")+"|"+(prefs.getString("pair_client_nonce","")?:"")+"|"+id+"|"+(prefs.getString("server_pub","")?:""));ws?.send(JSONObject().put("type","register").put("token",token).put("pair_code","PROOF").put("proof",proof).put("client_pub",prefs.getString("pair_pub","")).put("device_id",id).put("name",android.os.Build.MODEL).put("platform","android").toString())}
 private fun saveSecret(alias:String,value:String){
  try{
    val ks=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    if(!ks.containsAlias(alias)){
      val kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
      kg.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setRandomizedEncryptionRequired(true).build())
      kg.generateKey()
    }
    val k=ks.getKey(alias,null) as javax.crypto.SecretKey
    val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE,k)
    prefs.edit().putString(alias,"ks1:"+b64(c.iv)+":"+b64(c.doFinal(value.toByteArray(Charsets.UTF_8)))).apply()
  }catch(e:Exception){ throw IllegalStateException("Secure key storage unavailable",e) }
}
private fun loadSecret(alias:String):String?{
  val raw=prefs.getString(alias,null)?:return null
  if(!raw.startsWith("ks1:")) return null
  return try{
    val ks=java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
    val k=ks.getKey(alias,null) as javax.crypto.SecretKey
    val z=raw.split(":"); if(z.size!=3) return null
    val c=Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE,k,GCMParameterSpec(128,b64d(z[1])))
    String(c.doFinal(b64d(z[2])),Charsets.UTF_8)
  }catch(_:Exception){null}
}
private fun sealSecure(key:String,obj:JSONObject):String{val nonce=ByteArray(12).also{java.security.SecureRandom().nextBytes(it)};val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,SecretKeySpec(key.toByteArray(),"AES"),GCMParameterSpec(128,nonce));val ct=c.doFinal(obj.toString().toByteArray(Charsets.UTF_8));return JSONObject().put("type","secure").put("nonce",b64(nonce)).put("ciphertext",b64(ct)).toString()}
private fun openSecure(key:String,o:JSONObject):String{val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,SecretKeySpec(key.toByteArray(),"AES"),GCMParameterSpec(128,b64d(o.getString("nonce"))));return String(c.doFinal(b64d(o.getString("ciphertext"))),Charsets.UTF_8)}
private fun eventMac(key:String,type:String,id:String,phone:String,body:String,status:String,target:String=""):String{val raw="$type|$id|$phone|$body|$status|$target";return hmacSha256(key,raw)}
private fun auth(id:String,key:String)=JSONObject().put("type","auth").put("device_id",id).put("nonce",UUID.randomUUID().toString()).let{it.put("signature",hmacSha256(key,it.getString("nonce"))).toString()}
 private fun decryptKey(o:JSONObject):String{val priv=loadPrivate();val server=KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(b64d(o.getString("server_pub"))));val ka=KeyAgreement.getInstance("ECDH");ka.init(priv);ka.doPhase(server,true);val shared=ka.generateSecret();val hk=Mac.getInstance("HmacSHA256");hk.init(SecretKeySpec(ByteArray(32),"HmacSHA256"));val prk=hk.doFinal(shared);val info="hongdangmu-mobile-pair-v1".toByteArray(Charsets.UTF_8);hk.init(SecretKeySpec(prk,"HmacSHA256"));val t=hk.doFinal(info+byteArrayOf(1));val sharedKey=t.copyOf(32);val k=SecretKeySpec(sharedKey,"AES");val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,k,GCMParameterSpec(128,b64d(o.getString("nonce"))));return String(c.doFinal(b64d(o.getString("ciphertext"))),Charsets.UTF_8)}
 private fun verifyEvent(o:JSONObject):Boolean{val k=loadSecret("auth_key")?:return false;return hmacSha256(k,o.optString("type")+"|"+o.optString("message_id")+"|"+o.optString("phone")+"|"+o.optString("body")+"|"+o.optString("status")+"|"+o.optString("target_device_id"))==o.optString("mac")}
 private fun sendSms(p:String,b:String){if(p.isBlank()||b.isBlank())return;val id=UUID.randomUUID().toString();try{val sm=SmsManager.getDefault();if(b.length>160)sm.sendMultipartTextMessage(p,null,sm.divideMessage(b),null,null)else sm.sendTextMessage(p,null,b,null,null);emit(JSONObject().put("type","sms_sent").put("message_id",id).put("phone",p).put("body",b).put("status","sent").toString())}catch(e:Exception){emit(JSONObject().put("type","sms_sent").put("message_id",id).put("phone",p).put("body",b).put("status","failed").put("error",e.message?:"send_failed").toString())}}
 private fun sendIncoming(p:String,b:String){emit(JSONObject().put("type","sms_inbox").put("message_id",UUID.randomUUID().toString()).put("phone",p).put("body",b).put("status","received").toString())}
 private fun emit(x:String){val o=JSONObject(x);val key=loadSecret("auth_key")?:return;o.put("mac",eventMac(key,o.optString("type"),o.optString("message_id"),o.optString("phone"),o.optString("body"),o.optString("status"),o.optString("target_device_id")));val y=o.toString();if(ws!=null&&authenticated)ws!!.send(sealSecure(key,o))else{queue.offer(y);saveQueue()}}
 private fun flushQueues(w:WebSocket){if(!authenticated)return;val key=loadSecret("auth_key")?:return;queue.forEach{runCatching{w.send(sealSecure(key,JSONObject(it)))} };saveQueue()}
 private fun saveQueue(){prefs.edit().putStringSet("pending_sms",queue.toSet()).apply()};private fun loadQueue(){queue.clear();prefs.getStringSet("pending_sms",emptySet())?.forEach{queue.offer(it)}};private fun removeQueued(id:String){val kept=queue.filterNot{runCatching{JSONObject(it).optString("message_id")==id}.getOrDefault(false)};queue.clear();kept.forEach{queue.offer(it)};saveQueue()}
 private fun schedule(){if(reconnecting)return;reconnecting=true;Handler(Looper.getMainLooper()).postDelayed({reconnecting=false;connect(prefs.getString("pc_url","")?:"")},3000)}
 private fun deviceId()="android-"+UUID.randomUUID().toString().also{prefs.edit().putString("device_id",it).apply()};private fun genKeys()=KeyPairGenerator.getInstance("EC").apply{initialize(256)}.generateKeyPair();private fun loadPrivate()=KeyFactory.getInstance("EC").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(b64d(prefs.getString("pair_priv","")!!)));private fun b64(x:ByteArray)=Base64.getEncoder().encodeToString(x);private fun b64d(x:String)=Base64.getDecoder().decode(x);private fun hmacSha256(k:String,d:String)=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(k.toByteArray(),"HmacSHA256"));doFinal(d.toByteArray()).joinToString(""){"%02x".format(it)}}
 private fun notice(t:String)=NotificationCompat.Builder(this,"hongdangmu").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setContentTitle("홍당무 모바일").setContentText(t).setOngoing(true).build();private fun channel(){if(Build.VERSION.SDK_INT>=26)getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("hongdangmu","홍당무 모바일",NotificationManager.IMPORTANCE_LOW))};private fun update(t:String)=getSystemService(NotificationManager::class.java).notify(1001,notice(t));override fun onBind(i:Intent?)=null
 companion object{const val ACTION_PAIR_REQUIRED="com.hongdangmu.mobile.PAIR_REQUIRED"}
}