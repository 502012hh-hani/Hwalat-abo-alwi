package com.abualawi.hawalat;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
public class KeepAliveService extends Service {
 private static final String CHANNEL="hawalat_background_monitor";
 private final Handler handler=new Handler(Looper.getMainLooper());
 private final Runnable watchdog=new Runnable(){public void run(){checkHealth();handler.postDelayed(this,15000L);}};
 @Override public void onCreate(){super.onCreate();if(Build.VERSION.SDK_INT>=26){NotificationChannel c=new NotificationChannel(CHANNEL,"مراقبة حوالات أبو علوي",NotificationManager.IMPORTANCE_MIN);c.setDescription("إبقاء استقبال إشعارات واتساب متاحاً في الخلفية");c.setShowBadge(false);NotificationManager m=getSystemService(NotificationManager.class);if(m!=null)m.createNotificationChannel(c);}startForeground(8042,makeNotification());handler.post(watchdog);}
 @Override public int onStartCommand(Intent i,int flags,int id){startForeground(8042,makeNotification());return START_STICKY;}
 private Notification makeNotification(){Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return b.setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("حوالات أبو علوي").setContentText("خدمة مراقبة إشعارات واتساب تعمل").setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).build();}
 private void checkHealth(){try{String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");ComponentName c=new ComponentName(this,WhatsAppNotificationListener.class);boolean allowed=enabled!=null&&enabled.contains(c.flattenToString());android.content.SharedPreferences p=getSharedPreferences("hawalat",MODE_PRIVATE);long now=System.currentTimeMillis(),last=p.getLong("active_probe_at_ms",0L),requested=p.getLong("watchdog_last_rebind_ms",0L);if(!allowed)p.edit().putString("watchdog_status","صلاحية إشعارات التطبيق غير مفعّلة").apply();else if(last==0L||now-last>30000L){if(Build.VERSION.SDK_INT>=24&&now-requested>60000L){NotificationListenerService.requestRebind(c);p.edit().putLong("watchdog_last_rebind_ms",now).putString("watchdog_status","الفحص متوقف؛ طلب النظام إعادة ربط خدمة الإشعارات").apply();}else p.edit().putString("watchdog_status","بانتظار عودة خدمة استقبال الإشعارات").apply();}else p.edit().putString("watchdog_status","خدمة الخلفية نشطة؛ آخر فحص حديث").apply();}catch(Exception e){getSharedPreferences("hawalat",MODE_PRIVATE).edit().putString("watchdog_status","خطأ مراقبة الخلفية: "+e.getClass().getSimpleName()).apply();}}
 @Override public void onDestroy(){handler.removeCallbacks(watchdog);super.onDestroy();}
 @Override public IBinder onBind(Intent i){return null;}
}