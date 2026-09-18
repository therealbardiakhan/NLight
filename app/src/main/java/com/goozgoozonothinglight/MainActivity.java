package com.goozgoozonothinglight;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import java.util.*;

public class MainActivity extends Activity {
    static final UUID SERVICE_UUID = UUID.fromString("00002022-0000-1000-8000-00805f9b34fb");
    static final UUID WRITE_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    BluetoothAdapter adapter;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic writeChar;
    Handler handler = new Handler(Looper.getMainLooper());
    MainView view;
    String state = "SCANNING";
    int rgb = Color.RED;
    int brightness = 80;
    boolean powered = true, connecting = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        view = new MainView(this);
        setContentView(view);
        BluetoothManager bm = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = bm.getAdapter();
        if (Build.VERSION.SDK_INT >= 31)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, 10);
        else
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 11);
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == 10 || r == 11) startScan();
    }

    void startScan() {
        if (adapter == null || !adapter.isEnabled()) { state="BLUETOOTH OFF"; view.invalidate(); return; }
        if (connecting || writeChar != null) return;
        state="SCANNING"; view.invalidate();
        final BluetoothLeScanner scanner=adapter.getBluetoothLeScanner();
        if (scanner==null) return;

        final ScanCallback cb=new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                BluetoothDevice d=result.getDevice();
                ScanRecord sr=result.getScanRecord();
                boolean serviceMatch=sr!=null && sr.getServiceUuids()!=null &&
                    sr.getServiceUuids().stream().anyMatch(x->x.getUuid().equals(SERVICE_UUID));
                String n="";
                try { n=d.getName(); } catch(Exception ignored) {}
                boolean nameMatch=n!=null && (n.toUpperCase(Locale.US).contains("GATT") ||
                    n.toUpperCase(Locale.US).contains("DEMO") || n.toUpperCase(Locale.US).contains("MR STAR"));
                if(serviceMatch || nameMatch) {
                    try { scanner.stopScan(this); } catch(Exception ignored) {}
                    connect(d);
                }
            }
            @Override public void onScanFailed(int e) { state="SCAN ERROR"; view.invalidate(); }
        };
        try {
            scanner.startScan(cb);
            handler.postDelayed(()->{
                try { scanner.stopScan(cb); } catch(Exception ignored) {}
                if(writeChar==null && !connecting){ state="NOT FOUND"; view.invalidate(); }
            },12000);
        } catch(Exception e) { state="SCAN ERROR"; view.invalidate(); }
    }

    void connect(BluetoothDevice d) {
        if(connecting) return;
        connecting=true; state="CONNECTING"; view.invalidate();
        try {
            gatt=d.connectGatt(this,false,new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g,int st,int ns) {
                    if(ns==BluetoothProfile.STATE_CONNECTED) {
                        handler.postDelayed(()->{ try{g.discoverServices();}catch(Exception ignored){} },250);
                    } else if(ns==BluetoothProfile.STATE_DISCONNECTED) {
                        writeChar=null; connecting=false; state="DISCONNECTED"; view.invalidate();
                        handler.postDelayed(()->startScan(),1200);
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g,int st) {
                    BluetoothGattService s=g.getService(SERVICE_UUID);
                    if(s!=null) writeChar=s.getCharacteristic(WRITE_UUID);
                    if(writeChar==null) for(BluetoothGattService x:g.getServices()){
                        BluetoothGattCharacteristic c=x.getCharacteristic(WRITE_UUID);
                        if(c!=null){writeChar=c;break;}
                    }
                    connecting=false;
                    if(writeChar!=null){
                        state="CONNECTED"; view.invalidate();
                        handler.postDelayed(()->{
                            send(powerFrame(powered));
                            handler.postDelayed(()->send(colorFrame(rgb)),80);
                            handler.postDelayed(()->send(brightnessFrame(brightness)),160);
                        },120);
                    } else {
                        state="NO FFF3"; view.invalidate();
                        try{g.disconnect();}catch(Exception ignored){}
                        handler.postDelayed(()->startScan(),1000);
                    }
                }
            });
        } catch(Exception e){ connecting=false; state="BLUETOOTH ERROR"; view.invalidate(); }
    }

    void send(byte[] payload) {
        if(gatt==null || writeChar==null) return;
        try {
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            writeChar.setValue(payload);
            gatt.writeCharacteristic(writeChar);
        } catch(Exception ignored) {}
    }

    byte[] frame(int command, byte[] args) {
        byte[] out=new byte[4+args.length];
        out[0]=(byte)0xBC; out[1]=(byte)command; out[2]=(byte)args.length;
        System.arraycopy(args,0,out,3,args.length);
        out[out.length-1]=(byte)0x55;
        return out;
    }

    byte[] powerFrame(boolean on){ return frame(0x01,new byte[]{(byte)(on?1:0)}); }

    byte[] colorFrame(int color) {
        float[] hsv=new float[3]; Color.colorToHSV(color,hsv);
        int hue=Math.round(hsv[0]); if(hue>=360) hue=0;
        int sat=Math.round(hsv[1]*100f);
        int sat10=sat*10;
        return frame(0x04,new byte[]{(byte)(hue>>8),(byte)hue,(byte)(sat10>>8),(byte)sat10,0,0});
    }

    byte[] brightnessFrame(int pct) {
        int v=Math.max(0,Math.min(100,pct));
        int raw=1024*v/100;
        return frame(0x05,new byte[]{(byte)(raw>>8),(byte)raw,0,0,0,0});
    }

    void setPower(boolean on){ powered=on; send(powerFrame(on)); view.invalidate(); }
    void setColor(int c){ rgb=c; powered=true; send(powerFrame(true)); handler.postDelayed(()->send(colorFrame(c)),50); view.invalidate(); }
    void setBrightness(int b){ brightness=b; send(brightnessFrame(b)); view.invalidate(); }

    @Override protected void onDestroy(){ try{if(gatt!=null)gatt.close();}catch(Exception ignored){} super.onDestroy(); }

    class MainView extends View {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        float den,cx,cy,radius;
        MainView(Context c){ super(c); den=getResources().getDisplayMetrics().density; setBackgroundColor(Color.BLACK); }
        float dp(float x){return x*den;}
        void txt(Canvas c,String s,float x,float y,float sz,int col){
            p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.create("monospace",Typeface.NORMAL));
            p.setTextSize(dp(sz)); p.setColor(col); c.drawText(s,x,y,p);
        }
        @Override protected void onDraw(Canvas c){
            super.onDraw(c); float w=getWidth(),h=getHeight();
            float top=dp(34); cx=w/2f; cy=top+dp(245); radius=Math.min(dp(122),w*.37f);
            txt(c,"NOTHING LIGHT",dp(22),top,17,Color.WHITE);
            txt(c,"GATT—DEMO",dp(22),top+dp(28),11,Color.GRAY);
            p.setColor(state.equals("CONNECTED")?Color.WHITE:(state.equals("CONNECTING")||state.equals("SCANNING")?Color.GRAY:Color.DKGRAY));
            c.drawCircle(w-dp(27),top-dp(5),dp(4),p); txt(c,state,w-dp(125),top,9,Color.GRAY);
            drawWheel(c);
            float labelY=cy+radius+dp(45);
            txt(c,"COLOR",dp(22),labelY,10,Color.GRAY);
            txt(c,String.format(Locale.US,"#%06X",rgb&0xFFFFFF),dp(22),labelY+dp(22),14,Color.WHITE);
            float sy=labelY+dp(75); txt(c,"BRIGHTNESS",dp(22),sy,10,Color.GRAY);
            drawSlider(c,dp(22),sy+dp(25),w-dp(22),brightness/100f);
            txt(c,brightness+"%",dp(22),sy+dp(58),13,Color.WHITE);
            float bottom=h-dp(28); txt(c,powered?"ON":"OFF",dp(22),bottom,11,Color.WHITE);
            drawPower(c,w-dp(32),bottom-dp(5),powered);
            p.setColor(Color.rgb(35,35,35)); p.setStrokeWidth(dp(1));
            c.drawLine(dp(22),bottom+dp(11),w-dp(22),bottom+dp(11),p);
        }
        void drawSlider(Canvas c,float x1,float y,float x2,float f){
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(55,55,55));c.drawRect(x1,y-dp(1),x2,y+dp(1),p);
            p.setColor(Color.WHITE);float e=x1+(x2-x1)*f;c.drawRect(x1,y-dp(1),e,y+dp(1),p);c.drawCircle(e,y,dp(6),p);
        }
        void drawPower(Canvas c,float x,float y,boolean on){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(Color.WHITE);c.drawCircle(x,y,dp(13),p);
            p.setStyle(Paint.Style.FILL);c.drawRect(x-dp(2),y-dp(16),x+dp(2),y,p);
            if(!on){p.setColor(Color.BLACK);c.drawCircle(x,y,dp(10),p);}
        }
        void drawWheel(Canvas c){
            int size=Math.max(2,(int)(radius*2));Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
            int[] px=new int[size*size];
            for(int yy=0;yy<size;yy++)for(int xx=0;xx<size;xx++){
                float dx=xx-radius,dy=yy-radius,rr=(float)Math.sqrt(dx*dx+dy*dy);
                if(rr>radius){px[yy*size+xx]=Color.TRANSPARENT;continue;}
                float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360),sat=Math.min(1f,rr/radius);
                px[yy*size+xx]=Color.HSVToColor(new float[]{hue,sat,1f});
            }
            b.setPixels(px,0,size,0,0,size,size);c.drawBitmap(b,cx-radius,cy-radius,p);
            float[] hsv=new float[3];Color.colorToHSV(rgb,hsv);float a=(float)Math.toRadians(hsv[0]),r=hsv[1]*radius;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2));p.setColor(Color.WHITE);
            c.drawCircle(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r,dp(7),p);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(),y=e.getY(),labelY=cy+radius+dp(45),sy=labelY+dp(75),sliderY=sy+dp(25);
            if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){
                float dx=x-cx,dy=y-cy,dist=(float)Math.sqrt(dx*dx+dy*dy);
                if(dist<=radius+dp(15)){
                    float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360),sat=Math.min(1f,dist/radius);
                    rgb=Color.HSVToColor(new float[]{hue,sat,1f});setColor(rgb);invalidate();return true;
                }
                if(Math.abs(y-sliderY)<dp(25)&&x>=dp(22)&&x<=getWidth()-dp(22)){
                    brightness=Math.max(1,Math.min(100,Math.round((x-dp(22))/(getWidth()-dp(44))*100f)));
                    setBrightness(brightness);return true;
                }
            }
            if(e.getAction()==MotionEvent.ACTION_UP){
                float bx=getWidth()-dp(32),by=getHeight()-dp(33);
                if(Math.hypot(x-bx,y-by)<dp(30)){setPower(!powered);return true;}
                if(y<dp(90)&&x>getWidth()-dp(150)){try{if(gatt!=null)gatt.close();}catch(Exception ignored){}writeChar=null;connecting=false;startScan();return true;}
            }
            return true;
        }
    }
}