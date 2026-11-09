package com.abualawi.hawalat;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private TextView status, entries;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout=new LinearLayout(this); layout.setOrientation(1);
        layout.setPadding(24,32,24,24); layout.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        TextView title=new TextView(this); title.setText("حوالات أبو علوي"); title.setTextSize(27); title.setGravity(17); layout.addView(title);
        status=new TextView(this); status.setTextSize(17); status.setPadding(0,20,0,16); layout.addView(status);
        Button enable=new Button(this); enable.setText("تفعيل والوصول إلى إشعارات واتساب"); layout.addView(enable);
        enable.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        Button refresh=new Button(this); refresh.setText("تحديث سجل الحوالات"); layout.addView(refresh); refresh.setOnClickListener(v->showEntries());
        Button export=new Button(this); export.setText("تصدير ملفات الحوالات اليومية"); layout.addView(export); export.setOnClickListener(v->exportFiles());
        entries=new TextView(this); entries.setTextSize(14); entries.setPadding(0,20,0,20); layout.addView(entries);
        TextView note=new TextView(this); note.setTextSize(13); note.setText("يستقبل التطبيق الإشعارات التي يظهر فيها اسم المرسل والمجموعة. ملفات CSV تفتح ببرنامج Excel."); layout.addView(note);
        setContentView(layout);
    }
    @Override protected void onResume(){super.onResume();updateStatus();showEntries();}
    private void updateStatus(){
        String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");
        ComponentName me=new ComponentName(this,WhatsAppNotificationListener.class);
        boolean active=enabled!=null&&enabled.contains(me.flattenToString());
        String last=getSharedPreferences("hawalat",MODE_PRIVATE).getString("last_status","");
        status.setText("صلاحية الإشعارات: "+(active?"مفعّلة":"غير مفعّلة")+"\n"+last);
    }
    private void showEntries(){
        String a=getSharedPreferences("hawalat",MODE_PRIVATE).getString("rows_العمقي","");
        String b=getSharedPreferences("hawalat",MODE_PRIVATE).getString("rows_بن دول » محمد علوي احمد","");
        entries.setText("سجلات العمقي: "+count(a)+"\nسجلات بن دول: "+count(b)+"\n\nآخر السجلات محفوظة على الهاتف.");
        updateStatus();
    }
    private int count(String s){if(TextUtils.isEmpty(s))return 0;return s.split("\\n").length;}
    private void exportFiles(){
        if(!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())){Toast.makeText(this,"التخزين غير متاح",Toast.LENGTH_LONG).show();return;}
        try{
            File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),"حوالات أبو علوي");
            if(!dir.exists()&&!dir.mkdirs()) throw new Exception("تعذر إنشاء مجلد التصدير");
            String today=new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.US).format(new java.util.Date());
            int total=writeGroup(dir,"العمقي", "rows_العمقي",today)+writeGroup(dir,"بن دول", "rows_بن دول » محمد علوي احمد",today);
            Toast.makeText(this,"تم تصدير "+total+" سجل/سجلات إلى Documents/حوالات أبو علوي",Toast.LENGTH_LONG).show();
        }catch(Exception ex){Toast.makeText(this,"تعذر التصدير: "+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private int writeGroup(File dir,String label,String prefKey,String today)throws Exception{
        String data=getSharedPreferences("hawalat",MODE_PRIVATE).getString(prefKey,"");
        StringBuilder csv=new StringBuilder("\uFEFFاسم المستلم,المبلغ,التاريخ,الوقت\r\n"); int n=0;
        for(String row:data.split("\\n")){
            String[] p=row.split("\\t",-1); if(p.length<4||!today.equals(p[0]))continue;
            csv.append(q(p[2])).append(',').append(q(p[3])).append(',').append(q(p[0])).append(',').append(q(p[1])).append("\r\n"); n++;
        }
        File f=new File(dir,label+"-"+today+".csv");
        try(FileOutputStream out=new FileOutputStream(f)){out.write(csv.toString().getBytes(StandardCharsets.UTF_8));}
        return n;
    }
    private String q(String s){return "\""+s.replace("\"","\"\"")+"\"";}
}
