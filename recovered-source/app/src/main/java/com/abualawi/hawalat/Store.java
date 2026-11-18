package com.abualawi.hawalat;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;

public class Store extends SQLiteOpenHelper {
    public Store(Context c) { super(c, "hawalat.db", null, 2); }
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE transfers(id INTEGER PRIMARY KEY AUTOINCREMENT, grp TEXT, recipient TEXT, amount TEXT, msgdate TEXT, msgtime TEXT, raw TEXT, UNIQUE(grp,recipient,amount,msgdate,msgtime))");
    }
    public void onUpgrade(SQLiteDatabase db,int oldV,int newV) { }
    public synchronized void add(String g,String n,String a,String d,String t,String raw) {
        if(n==null || n.trim().isEmpty() || a==null || a.trim().isEmpty()) return;
        ContentValues v=new ContentValues(); v.put("grp",g); v.put("recipient",n.trim().replaceAll("\\s+", " "));
        v.put("amount",a.trim()); v.put("msgdate",d); v.put("msgtime",t); v.put("raw",raw);
        getWritableDatabase().insertWithOnConflict("transfers",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public Cursor all(String group) {
        return getReadableDatabase().rawQuery("SELECT t.recipient,t.amount,t.msgdate,t.msgtime, CASE WHEN (SELECT COUNT(*) FROM transfers x WHERE x.msgdate=t.msgdate AND lower(trim(x.recipient))=lower(trim(t.recipient)))>1 THEN 1 ELSE 0 END AS duplicate, (SELECT x.msgtime FROM transfers x WHERE x.msgdate=t.msgdate AND lower(trim(x.recipient))=lower(trim(t.recipient)) AND x.id<t.id ORDER BY x.id DESC LIMIT 1) AS previous_time FROM transfers t WHERE t.grp=? ORDER BY t.id DESC",new String[]{group});
    }
    public Cursor today(String group, String date) {
        return getReadableDatabase().rawQuery("SELECT t.recipient,t.amount,t.msgdate,t.msgtime, CASE WHEN (SELECT COUNT(*) FROM transfers x WHERE x.msgdate=t.msgdate AND lower(trim(x.recipient))=lower(trim(t.recipient)))>1 THEN 1 ELSE 0 END AS duplicate, (SELECT x.msgtime FROM transfers x WHERE x.msgdate=t.msgdate AND lower(trim(x.recipient))=lower(trim(t.recipient)) AND x.id<t.id ORDER BY x.id DESC LIMIT 1) AS previous_time FROM transfers t WHERE t.grp=? AND t.msgdate=? ORDER BY t.id DESC", new String[]{group,date});
    }
    public int count() {
        Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM transfers",null);
        int n=0; if(c.moveToFirst()) n=c.getInt(0); c.close(); return n;
    }
}