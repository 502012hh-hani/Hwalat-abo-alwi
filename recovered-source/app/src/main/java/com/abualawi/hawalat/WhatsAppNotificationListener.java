package com.abualawi.hawalat;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.content.SharedPreferences;
import android.text.TextUtils;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhatsAppNotificationListener extends NotificationListenerService {
    private static final String SENDER = "ابو علوي رقم جديد";
    private static final Pattern NAME = Pattern.compile("(?:الاسم|المستلم|اسم\\s*المستلم)\\s*[:：/\\-]?\\s*([\\p{L}ء-يـ ]{4,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBER = Pattern.compile("(?<![\\d٠-٩])([0-9٠-٩][0-9٠-٩٬,٫.]*)");
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        String pkg=sbn.getPackageName();
        if (!"com.whatsapp".equals(pkg) && !"com.whatsapp.w4b".equals(pkg)) return;
        Bundle e=sbn.getNotification().extras; if(e==null)return;
        String title=value(e.getCharSequence(Notification.EXTRA_TITLE));
        String conversation=value(e.getCharSequence("android.conversationTitle"));
        String text=value(e.getCharSequence(Notification.EXTRA_TEXT));
        String big=value(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body=TextUtils.isEmpty(big)?text:big;
        String all=title+"\\n"+conversation+"\\n"+body;
        if(!all.contains(SENDER))return;
        String gt=conversation+" "+title+" "+body;
        String group;
        if(gt.contains("العمقي")||gt.contains("العمقى"))group="العمقي";
        else if(gt.contains("بن دول")||gt.contains("محمد علوي احمد"))group="بن دول » محمد علوي احمد";
        else return;
        Matcher nm=NAME.matcher(body), am=NUMBER.matcher(body);
        String name=nm.find()?nm.group(1).replaceAll("[\\s.،؛]+$","").trim():"";
        String amount=am.find()?am.group(1).replace("٬","").replace(",","").replace("٫","."):"";
        SharedPreferences p=getSharedPreferences("pending_transfers",MODE_PRIVATE);
        String key=group.equals("العمقي")?"amqi":"bindol";
        if(!name.isEmpty())p.edit().putString(key,name).putLong(key+"_time",sbn.getPostTime()).apply();
        if(!amount.isEmpty()){
            if(name.isEmpty()){long t=p.getLong(key+"_time",0L);if(t>0&&sbn.getPostTime()-t<=600000L)name=p.getString(key,"");}
            if(!name.isEmpty()){
                long ts=sbn.getPostTime();
                new Store(this).add(group,name,amount,new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(ts)),new SimpleDateFormat("HH:mm",Locale.US).format(new Date(ts)),all);
                p.edit().remove(key).remove(key+"_time").apply();
            }
        }
    }
    private String value(CharSequence s){return s==null?"":s.toString();}
}