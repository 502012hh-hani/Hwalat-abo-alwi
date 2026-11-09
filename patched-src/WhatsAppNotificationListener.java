package com.abualawi.hawalat;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WhatsAppNotificationListener extends NotificationListenerService {
    private static final String PREFS="hawalat";
    private static final String SENDER="ابو علوي رقم جديد";
    private static final String G1="العمقي";
    private static final String G2="بن دول";

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if(sbn==null || !"com.whatsapp".equals(sbn.getPackageName())) return;
        Notification n=sbn.getNotification();
        if(n==null || n.extras==null) return;
        Bundle e=n.extras;
        String title=str(e.getCharSequence(Notification.EXTRA_TITLE));
        String sub=str(e.getCharSequence(Notification.EXTRA_SUB_TEXT));
        String big=str(e.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String body=big.isEmpty()?str(e.getCharSequence(Notification.EXTRA_TEXT)):big;
        CharSequence[] lines=e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if(lines!=null) for(CharSequence x:lines) body += "\n"+str(x);
        String all=title+"\n"+sub+"\n"+body;
        if(!all.contains(SENDER)) return;
        String groupText=title+" "+sub;
        String group=groupText.contains(G1)?"العمقي":groupText.contains(G2)?"بن دول » محمد علوي احمد":"";
        if(group.isEmpty()) return;

        Parsed p=parse(body);
        if(p==null) p=parse(all);
        if(p==null) {
            saveRaw(all,group,sbn.getPostTime());
            return;
        }
        long t=sbn.getPostTime();
        String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(t));
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date(t));
        String key="rows_"+group;
        String row=date+"\t"+time+"\t"+p.name+"\t"+p.amount;
        android.content.SharedPreferences prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        String old=prefs.getString(key,"");
        String duplicateKey=date+"\t"+p.name.replaceAll("\\s+","").toLowerCase(Locale.ROOT)+"\t"+p.amount;
        for(String line:old.split("\n")) {
            String[] a=line.split("\t",-1);
            if(a.length>=4 && (a[0]+"\t"+a[2].replaceAll("\\s+","").toLowerCase(Locale.ROOT)+"\t"+a[3]).equals(duplicateKey)) return;
        }
        prefs.edit().putString(key,row+"\n"+old).putString("last_status","تم تسجيل حوالة: "+p.name+" - "+p.amount+" ("+group+")").apply();
    }

    private void saveRaw(String raw,String group,long t) {
        String date=new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(t));
        String time=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date(t));
        getSharedPreferences(PREFS,MODE_PRIVATE).edit()
          .putString("unparsed_"+group,date+" "+time+" | "+raw+"\n"+getSharedPreferences(PREFS,MODE_PRIVATE).getString("unparsed_"+group,""))
          .putString("last_status","وصل إشعار من "+group+" لكن تعذر استخراج الاسم والمبلغ تلقائياً").apply();
    }

    private static class Parsed { String name,amount; Parsed(String n,String a){name=n;amount=a;} }
    private Parsed parse(String text) {
        if(text==null) return null;
        String s=normalize(text).replace('،',' ').replace('|',' ');
        String amount=null;
        Matcher m=Pattern.compile("(?<![\\p{L}])([0-9]{2,})(?![\\p{L}])").matcher(s);
        while(m.find()) {
            String before=s.substring(Math.max(0,m.start()-18),m.start()).toLowerCase(Locale.ROOT);
            String after=s.substring(m.end(),Math.min(s.length(),m.end()+20)).toLowerCase(Locale.ROOT);
            if(before.contains("رقم") || before.contains("هاتف") || before.contains("جوال")) continue;
            if(before.contains("المبلغ") || before.contains("مبلغ") || after.contains("ريال") || after.contains("سعود") || after.contains("رس") || before.trim().isEmpty()) { amount=m.group(1); break; }
            amount=m.group(1);
        }
        if(amount==null) return null;
        String candidate="";
        Matcher labeled=Pattern.compile("(?:المستلم|مستلم|المتسلم|الإسم|الاسم|باسم)\\s*[:：/\\-]?\\s*([^\\n\\r]+)",Pattern.CASE_INSENSITIVE).matcher(s);
        if(labeled.find()) candidate=labeled.group(1);
        if(candidate.isEmpty()) {
            String[] lines=s.split("[\\n\\r]+");
            for(String line:lines) {
                String clean=line.replaceAll("[0-9]+.*$","").replaceAll("(?i)ريال سعودي|ريال|سعودي|صافي|المكلا|حضرموت|عتق|شبوه|الروضة"," ").trim();
                if(words(clean)>=3 && !clean.contains("المرسل") && !clean.contains("المبلغ")) { candidate=clean; break; }
            }
        }
        candidate=candidate.replaceAll("(?i)المبلغ.*$"," ").replaceAll("[0-9٠-٩]+.*$"," ").trim();
        candidate=candidate.replaceAll("(?i)ريال سعودي|ريال|سعودي|صافي|المكلا|حضرموت|عتق|شبوه|الروضة"," ").trim();
        if(words(candidate)<2 || candidate.contains("المرسل") || candidate.contains("ابو علوي")) return null;
        return new Parsed(candidate,amount);
    }
    private static int words(String s){return s.trim().isEmpty()?0:s.trim().split("\\s+").length;}
    private static String normalize(String s){return s.replace('٠','0').replace('١','1').replace('٢','2').replace('٣','3').replace('٤','4').replace('٥','5').replace('٦','6').replace('٧','7').replace('٨','8').replace('٩','9').replace('۰','0').replace('۱','1').replace('۲','2').replace('۳','3').replace('۴','4').replace('۵','5').replace('۶','6').replace('۷','7').replace('۸','8').replace('۹','9');}
    private static String str(CharSequence x){return x==null?"":x.toString().trim();}
}