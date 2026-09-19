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
        int insetTop = 0, insetBottom = 0;
        boolean effectsPanel = false;
    boolean sleepPanel = false;
    float effectScroll = 0, effectTouchStartY = 0;
    int effect = 0;
    int colorTemperature = 4000;
    int effectSpeed = 55;
    long sleepAt = 0;
    int[] favoriteColors = {Color.RED, Color.GREEN, Color.BLUE, Color.WHITE};
    final int[] EFFECT_CODES = {1,2,3,4,7,9,10,26,27,28,29,30,31,32,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,76,77,78,79,80,81,82,83,84,85,86,87,88,89,90,91,92,93,94,95};

    android.content.SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("nothing_light", MODE_PRIVATE);
    effectSpeed = prefs.getInt("effectSpeed", 55);
        colorTemperature = prefs.getInt("colorTemperature", 4000);
        rgb = prefs.getInt("rgb", Color.WHITE);
        brightness = prefs.getInt("brightness", 100);

        view = new MainView(this);
        setContentView(view);

        // Android 15+ enforces edge-to-edge for target SDK 35. Padding alone does not
        // move custom Canvas drawing, so store the insets and translate the canvas.
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                android.view.WindowInsets.Type.statusBars() |
                android.view.WindowInsets.Type.navigationBars());
            insetTop = bars.top;
            insetBottom = bars.bottom;
            v.invalidate();
            return insets;
        });
        view.requestApplyInsets();
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

    // MR Star effect packet: BC 06 02 EFFECT_HI EFFECT_LO 55
    byte[] effectFrame(int effectCode) {
        return frame(0x06, new byte[]{(byte)(effectCode >> 8), (byte)effectCode});
    }

    // MR Star effect speed packet: BC 08 01 SPEED 55
    byte[] effectSpeedFrame(int speed) {
        return frame(0x08, new byte[]{(byte)Math.max(0, Math.min(100, speed))});
    }

    byte[] temperatureFrame(int kelvin) {
        int k=Math.max(2000,Math.min(6500,kelvin));
        // Convert CCT to an RGB approximation and use the normal color command.
        int c = kelvinToRgb(k);
        return colorFrame(c);
    }

    int kelvinToRgb(int kelvin) {
        double t=kelvin/100.0, r,g,b;
        if(t<=66) r=255; else r=329.698727*Math.pow(t-60,-0.1332047592);
        if(t<=66) g=99.4708025861*Math.log(t)-161.1195681661;
        else g=288.1221695283*Math.pow(t-60,-0.0755148492);
        if(t>=66) b=255;
        else if(t<=19) b=0;
        else b=138.5177312231*Math.log(t-10)-305.0447927307;
        return Color.rgb((int)Math.max(0,Math.min(255,r)),
                (int)Math.max(0,Math.min(255,g)),
                (int)Math.max(0,Math.min(255,b)));
    }

    void setPower(boolean on){ powered=on; send(powerFrame(on)); view.invalidate(); }
    void setColor(int c){ rgb=c; powered=true; send(powerFrame(true)); handler.postDelayed(()->send(colorFrame(c)),50); view.invalidate(); }
    void setBrightness(int b){ brightness=b; prefs.edit().putInt("brightness",b).apply(); send(brightnessFrame(b)); view.invalidate(); }
    void setEffect(int e) {
        effect = Math.max(0, Math.min(EFFECT_CODES.length, e));
        prefs.edit().putInt("effect", effect).apply();

        if (effect == 0) {
            send(powerFrame(true));
            handler.postDelayed(() -> send(colorFrame(rgb)), 45);
        } else {
            send(effectSpeedFrame(effectSpeed));
            handler.postDelayed(() -> send(effectFrame(EFFECT_CODES[effect - 1])), 45);
        }
        view.invalidate();
    }

    void setEffectSpeed(int speed) {
        effectSpeed = Math.max(1, Math.min(100, speed));
        prefs.edit().putInt("effectSpeed", effectSpeed).apply();
        if (effect > 0) {
            send(effectSpeedFrame(effectSpeed));
        }
        view.invalidate();
    }

    void saveState() {
        prefs.edit()
                .putInt("rgb", rgb)
                .putInt("brightness", brightness)
                .putInt("colorTemperature", colorTemperature)
                .putInt("effect", effect)
                .putInt("effectSpeed", effectSpeed)
                .apply();
    }

    void showCustomSleepDialog(){
        final android.widget.EditText input=new android.widget.EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint("Minutes (1–1440)");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Custom sleep timer")
                .setMessage("Turn the strip off after how many minutes?")
                .setView(input)
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("SET",(d,w)->{
                    try{
                        long m=Long.parseLong(input.getText().toString().trim());
                        if(m>=1 && m<=1440) startSleepTimer(m);
                    }catch(Exception ignored){}
                }).show();
    }

    void startSleepTimer(long minutes) {
        sleepAt = System.currentTimeMillis() + minutes * 60000L;
        handler.postDelayed(() -> {
            if (sleepAt != 0 && System.currentTimeMillis() >= sleepAt) {
                setPower(false);
                sleepAt = 0;
            }
        }, minutes * 60000L);
        view.invalidate();
    }

    void cancelSleepTimer() {
        sleepAt = 0;
        view.invalidate();
    }

    boolean isWhiteColor() {
        float[] hsv=new float[3];
        Color.colorToHSV(rgb,hsv);
        return hsv[1] <= 0.10f && hsv[2] >= 0.75f;
    }

    void setTemperature(int k) {
        colorTemperature = Math.max(2000, Math.min(6500, k));
        prefs.edit().putInt("colorTemperature", colorTemperature).apply();
        rgb = kelvinToRgb(colorTemperature);
        setColor(rgb);
        view.invalidate();
    }

    @Override protected void onDestroy(){ try{if(gatt!=null)gatt.close();}catch(Exception ignored){} super.onDestroy(); }

    class MainView extends View {
        Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        float den,cx,cy,radius;
        int activePreset=-1;

        final int[] PRESETS={Color.WHITE,Color.RED,Color.rgb(255,128,0),Color.YELLOW,
            Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA};
        final String[] EFFECTS={"SOLID","AUTOMATIC LOOP","SYMPHONY","COLORFUL ENERGY","COLORFUL JUMPS","RAINBOW STROBE","YPB STROBE","RAINBOW GRADIENT","COLORFUL FLUTTERING","RGB FLUTTERING","YCP FLUTTERING","COLORFUL BRUSHING","RGB COLOR BRUSHING","YCP COLOR BRUSHING","COLOR BRUSH CLOSED PULL","RAINBOW OPEN / CLOSE","RGB OPEN / CLOSE","YCP OPEN / CLOSE","RED OPEN / CLOSE","GREEN OPEN / CLOSE","BLUE OPEN / CLOSE","YELLOW OPEN / CLOSE","CYAN OPEN / CLOSE","PURPLE OPEN / CLOSE","WHITE OPEN / CLOSE","RAINBOW LIGHT / DARK","RGB LIGHT / DARK","VGY LIGHT / DARK","RED LIGHT / DARK","GREEN LIGHT / DARK","BLUE LIGHT / DARK","CYAN LIGHT / DARK","YELLOW LIGHT / DARK","PURPLE LIGHT / DARK","WHITE LIGHT / DARK","RAINBOW FLOWING WATER","RGB RUNNING WATER","VY RUNNING WATER","RG RUNNING WATER","GB RUNNING WATER","YB RUNNING WATER","YC RUNNING WATER","GP RUNNING WATER","RAINBOW TRAILING","RED TRAILING","GREEN TRAILING","BLUE TRAILING","YELLOW TRAILING","CYAN TRAILING","PURPLE TRAILING","WHITE TRAILING","RED RUNNING","GREEN RUNNING","BLUE RUNNING","YELLOW RUNNING","CYAN RUNNING","PURPLE RUNNING","WHITE RUNNING","RAINBOW RUNNING","BGR RUNNING","PCY RUNNING","BPCY RUNNING","BGCY RUNNING"};

        MainView(Context c){
            super(c); den=getResources().getDisplayMetrics().density;
            setBackgroundColor(Color.BLACK);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        }
        float dp(float x){return x*den*0.90f;}
        float raw(float x){return x*den;}

        void txt(Canvas c,String s,float x,float y,float sz,int col){
            p.setStyle(Paint.Style.FILL); p.setTypeface(Typeface.create("monospace",Typeface.NORMAL));
            p.setTextSize(dp(sz)); p.setColor(col); c.drawText(s,x,y,p);
        }
        void centerTxt(Canvas c,String s,float y,float sz,int col){
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create("monospace",Typeface.NORMAL));
            p.setTextSize(dp(sz));
            p.setColor(col);
            c.drawText(s,(getWidth()-p.measureText(s))/2f,y,p);
        }

        @Override protected void onDraw(Canvas c){
            super.onDraw(c);
            c.save(); c.translate(0,insetTop);
            float w=getWidth(), h=getHeight()-insetTop-insetBottom;
            float side=Math.max(dp(24),w*.065f);
            float top=dp(48);

            // Before connection: deliberately minimal, centered state screen.
            if(!state.equals("CONNECTED")){
                drawConnectionState(c,w,h,side,top);
                c.restore(); return;
            }

            cx=w/2f; cy=top+dp(190);
            radius=Math.min(dp(126),(w-side*2)*.38f);

            txt(c,"NOTHING LIGHT",side,top,20,Color.WHITE);
            txt(c,"GATT—DEMO",side,top+dp(30),12,Color.GRAY);
            p.setStyle(Paint.Style.FILL); p.setColor(Color.WHITE);
            c.drawCircle(w-side-dp(46),top-dp(5),dp(4),p);
            txt(c,"CONNECTED",w-side-dp(132),top,10,Color.GRAY);
            drawSleepButton(c,w-side,top);

            drawWheel(c);

            float presetY=cy+radius+dp(32);
            drawPresets(c,presetY);

            float labelY=presetY+dp(45);
            txt(c,"COLOR",side,labelY,12,Color.GRAY);
            txt(c,String.format(Locale.US,"#%06X",rgb&0xFFFFFF),side,labelY+dp(25),16,Color.WHITE);

            float sy=labelY+dp(72);
            txt(c,"BRIGHTNESS",side,sy,12,Color.GRAY);
            float sliderY=sy+dp(28);
            drawSlider(c,side,sliderY,w-side,brightness/100f);
            txt(c,brightness+"%",side,sy+dp(63),15,Color.WHITE);

            boolean showTemperature = isWhiteColor();
            float ty=sy+dp(94);
            float ey;
            if (showTemperature) {
                txt(c,"TEMPERATURE",side,ty,12,Color.GRAY);
                drawSlider(c,side,ty+dp(28),w-side,(colorTemperature-2000)/4500f);
                txt(c,colorTemperature+"K",side,ty+dp(63),15,Color.WHITE);
                ey=ty+dp(94);
            } else {
                ey=ty;
            }
            txt(c,"EFFECT SPEED",side,ey,12,Color.GRAY);
            drawSlider(c,side,ey+dp(28),w-side,(effectSpeed-1)/99f);
            txt(c,effectSpeed+"%",side,ey+dp(63),15,Color.WHITE);

            float bottom=h-dp(32);
            drawEffectButton(c,side,bottom-dp(5));
            txt(c,powered?"ON":"OFF",side+dp(68),bottom,13,Color.WHITE);
            if (effect > 0) txt(c,"S"+effectSpeed,side+dp(68),bottom-dp(18),9,Color.GRAY);
            drawPower(c,w-side,bottom-dp(5),powered);

            p.setColor(Color.rgb(35,35,35)); p.setStrokeWidth(raw(1));
            c.drawLine(side,bottom+dp(12),w-side,bottom+dp(12),p);
            if(effectsPanel)drawEffectsPanel(c,w,h,side);
            if(sleepPanel)drawSleepPanel(c,w,h,side);
            c.restore();
        }

        void drawConnectionState(Canvas c,float w,float h,float side,float top){
            float centerY=h/2f;
            String title=state.equals("CONNECTING")?"CONNECTING":"DISCONNECTED";
            String sub=state.equals("CONNECTING")?"SEARCHING FOR GATT—DEMO":"TAP TO CONNECT";
            centerTxt(c,title,centerY,22,Color.WHITE);
            centerTxt(c,sub,centerY+dp(31),11,Color.GRAY);
            p.setStyle(Paint.Style.FILL);
            p.setColor(state.equals("CONNECTING")?Color.GRAY:Color.DKGRAY);
            c.drawCircle(w/2f,centerY-dp(45),dp(5),p);
        }
        void drawSleepButton(Canvas c,float x,float y){
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(raw(1)); p.setColor(Color.rgb(80,80,80));
            float bw=dp(58),bh=dp(30);
            c.drawRoundRect(x-bw,y-bh/2,x,y+bh/2,dp(5),dp(5),p);
            txt(c,"SLEEP",x-bw+dp(8),y+dp(4),9,Color.WHITE);
        }

        void drawSleepPanel(Canvas c,float w,float h,float side){
            float pw=Math.min(dp(300),w-side*2),ph=dp(285);
            float left=(w-pw)/2f, top=Math.max(dp(70),(h-ph)/2f);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(10,10,10));
            c.drawRoundRect(left,top,left+pw,top+ph,dp(8),dp(8),p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(raw(1));p.setColor(Color.rgb(70,70,70));
            c.drawRoundRect(left,top,left+pw,top+ph,dp(8),dp(8),p);
            txt(c,"SLEEP TIMER",left+dp(16),top+dp(28),12,Color.WHITE);
            txt(c,"TURN OFF AFTER",left+dp(16),top+dp(51),9,Color.GRAY);
            int[] mins={15,30,60,120,240};
            String[] labels={"15 MIN","30 MIN","1 HOUR","2 HOURS","4 HOURS"};
            long now=System.currentTimeMillis();
            long remaining=sleepAt>now?sleepAt-now:0;
            for(int i=0;i<mins.length;i++){
                float yy=top+dp(80)+i*dp(31);
                boolean active=remaining>0 && Math.abs(remaining-mins[i]*60000L)<15000L;
                p.setStyle(Paint.Style.STROKE);p.setColor(active?Color.WHITE:Color.rgb(65,65,65));
                c.drawRoundRect(left+dp(16),yy-dp(11),left+pw-dp(16),yy+dp(11),dp(5),dp(5),p);
                txt(c,labels[i],left+dp(27),yy+dp(4),10,Color.WHITE);
            }
            p.setColor(sleepAt>now?Color.WHITE:Color.GRAY);
            txt(c,sleepAt>now?"TIMER ACTIVE":"CUSTOM TIME",left+dp(16),top+ph-dp(22),9,p.getColor());
        }

        void drawEffectButton(Canvas c,float x,float y){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(raw(1));p.setColor(Color.rgb(80,80,80));
            c.drawRoundRect(x,y-dp(17),x+dp(58),y+dp(17),dp(5),dp(5),p);
            txt(c,"EFFECT",x+dp(8),y+dp(5),9,Color.WHITE);

        }

        void drawEffectsPanel(Canvas c,float w,float h,float side){
            float pw=Math.min(dp(330),w-side*2), ph=Math.min(dp(500),h-dp(130));
            float left=(w-pw)/2f, top=Math.max(dp(70),(h-ph)/2f);
            p.setStyle(Paint.Style.FILL);p.setColor(Color.rgb(10,10,10));
            c.drawRoundRect(left,top,left+pw,top+ph,dp(8),dp(8),p);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(raw(1));p.setColor(Color.rgb(70,70,70));
            c.drawRoundRect(left,top,left+pw,top+ph,dp(8),dp(8),p);

            txt(c,"EFFECTS",left+dp(16),top+dp(28),12,Color.GRAY);
            txt(c,"SWIPE",left+pw-dp(54),top+dp(28),8,Color.GRAY);

            c.save();
            c.clipRect(left,top+dp(42),left+pw,top+ph-dp(8));
            float rowH=dp(27);
            float y0=top+dp(64)-effectScroll;
            for(int i=0;i<EFFECTS.length;i++){
                float yy=y0+i*rowH;
                if(yy<top+dp(42)||yy>top+ph) continue;
                p.setStyle(Paint.Style.FILL);
                p.setColor(i==effect?Color.WHITE:Color.rgb(45,45,45));
                c.drawCircle(left+dp(18),yy-dp(4),dp(4),p);
                txt(c,EFFECTS[i],left+dp(32),yy,10,i==effect?Color.WHITE:Color.GRAY);
            }
            c.restore();
        }

        void drawPresets(Canvas c,float y){
            float w=getWidth(),side=Math.max(dp(24),w*.065f),step=(w-side*2)/8f,r=dp(17);
            for(int i=0;i<PRESETS.length;i++){
                float x=side+step*(i+.5f);
                p.setStyle(Paint.Style.FILL);p.setColor(PRESETS[i]);c.drawCircle(x,y,r,p);
                if(rgb==PRESETS[i]){
                    p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(raw(2));p.setColor(Color.WHITE);
                    c.drawCircle(x,y,r+dp(4),p);
                }
            }
        }

        void drawSlider(Canvas c,float x1,float y,float x2,float f){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(4));p.setColor(Color.rgb(55,55,55));
            c.drawLine(x1,y,x2,y,p);float e=x1+(x2-x1)*f;p.setColor(Color.WHITE);c.drawLine(x1,y,e,y,p);
            p.setStyle(Paint.Style.FILL);c.drawCircle(e,y,dp(8),p);
        }

        void drawPower(Canvas c,float x,float y,boolean on){
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2.5f));p.setColor(Color.WHITE);
            c.drawCircle(x,y,dp(15),p);p.setStyle(Paint.Style.FILL);c.drawRect(x-dp(2.5f),y-dp(19),x+dp(2.5f),y,p);
            if(!on){p.setColor(Color.BLACK);c.drawCircle(x,y,dp(12),p);}
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
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(2.5f));p.setColor(Color.WHITE);
            c.drawCircle(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r,dp(9),p);
        }

        @Override public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(),y=e.getY()-insetTop,w=getWidth(),h=getHeight()-insetTop-insetBottom;
            float side=Math.max(dp(24),w*.065f),top=dp(48);

            if(!state.equals("CONNECTED")){
                if(e.getAction()==MotionEvent.ACTION_UP){ startScan(); return true; }
                return true;
            }

            cx=w/2f;cy=top+dp(190);radius=Math.min(dp(126),(w-side*2)*.38f);
            float presetY=cy+radius+dp(32),labelY=presetY+dp(45),sy=labelY+dp(72),sliderY=sy+dp(28);
            boolean showTemperature = isWhiteColor();
            float tempY=sy+dp(94)+dp(28);
            float effectSpeedY=showTemperature ? sy+dp(188)+dp(28) : sy+dp(94)+dp(28);

            if(effectsPanel){
                float pw=Math.min(dp(330),w-side*2), ph=Math.min(dp(500),h-dp(130));
                float left=(w-pw)/2f, pt=Math.max(dp(70),(h-ph)/2f);
                float rowH=dp(27);
                float maxScroll=Math.max(0,EFFECTS.length*rowH-(ph-dp(62)));

                if(e.getAction()==MotionEvent.ACTION_DOWN){
                    effectTouchStartY=y;
                    return true;
                }
                if(e.getAction()==MotionEvent.ACTION_MOVE){
                    float dy=effectTouchStartY-y;
                    if(Math.abs(dy)>dp(4)){
                        effectScroll=Math.max(0,Math.min(maxScroll,effectScroll+dy));
                        effectTouchStartY=y;
                        invalidate();
                    }
                    return true;
                }
                if(e.getAction()==MotionEvent.ACTION_UP){
                    if(x<left||x>w-side||y<pt||y>pt+ph){effectsPanel=false;invalidate();return true;}
                    if(Math.abs(y-effectTouchStartY)>dp(8)){return true;}
                    if(y>=pt+dp(42)&&y<=pt+ph-dp(8)){
                        int idx=Math.round((y-(pt+dp(64))+effectScroll)/rowH);
                        if(idx>=0&&idx<EFFECTS.length){
                            setEffect(idx); effectsPanel=false; effectScroll=0;
                        }
                    }
                    invalidate();return true;
                }
                return true;
            }

            if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){
                float dx=x-cx,dy=y-cy,dist=(float)Math.sqrt(dx*dx+dy*dy);
                if(dist<=radius+dp(18)){
                    float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360),sat=Math.min(1f,dist/radius);
                    rgb=Color.HSVToColor(new float[]{hue,sat,1f});activePreset=-1;setColor(rgb);invalidate();return true;
                }
                if(Math.abs(y-sliderY)<dp(38)&&x>=side-dp(10)&&x<=w-side+dp(10)){
                    float f=Math.max(0,Math.min(1,(x-side)/(w-side*2)));
                    brightness=Math.max(1,Math.min(100,Math.round(f*100)));setBrightness(brightness);invalidate();return true;
                }
                if(showTemperature && Math.abs(y-tempY)<dp(38)&&x>=side-dp(10)&&x<=w-side+dp(10)){
                    float f=Math.max(0,Math.min(1,(x-side)/(w-side*2)));
                    setTemperature(Math.round(2000+4500*f));return true;
                }
                if(Math.abs(y-effectSpeedY)<dp(38)&&x>=side-dp(10)&&x<=w-side+dp(10)){
                    float f=Math.max(0,Math.min(1,(x-side)/(w-side*2)));
                    setEffectSpeed(Math.round(1+99*f));return true;
                }
                if(Math.abs(y-presetY)<dp(34)){
                    float step=(w-side*2)/8f;int idx=(int)((x-side)/step);
                    if(idx>=0&&idx<PRESETS.length){rgb=PRESETS[idx];activePreset=idx;setColor(rgb);invalidate();return true;}
                }
            }

            if(e.getAction()==MotionEvent.ACTION_UP){

                float bottom=h-dp(32),fx=side,fy=bottom-dp(5);
                if(x>=fx-dp(10)&&x<=fx+dp(70)&&Math.abs(y-fy)<dp(32)){effectsPanel=true;invalidate();return true;}


                float sleepX=w-side, sleepY=top;
                if(x>=sleepX-dp(64)&&x<=sleepX+dp(8)&&Math.abs(y-sleepY)<dp(28)){
                    sleepPanel=true; invalidate(); return true;
                }

                if(sleepPanel){
                    // handled below
                }

                if(sleepPanel){
                    float pw=Math.min(dp(300),w-side*2), ph=dp(285);
                    float left=(w-pw)/2f, pt=Math.max(dp(70),(h-ph)/2f);
                    if(x<left||x>w-side||y<pt||y>pt+ph){
                        sleepPanel=false;invalidate();return true;
                    }
                    if(y>=pt+dp(69)&&y<pt+dp(245)){
                        int row=(int)((y-(pt+dp(69)))/dp(31));
                        int[] mins={15,30,60,120,240};
                        if(row>=0&&row<mins.length){startSleepTimer(mins[row]);sleepPanel=false;invalidate();return true;}
                    }
                    if(y>=pt+ph-dp(45)){
                        showCustomSleepDialog();sleepPanel=false;invalidate();return true;
                    }
                    return true;
                }

                float bx=w-side,by=bottom-dp(5);
                if(Math.hypot(x-bx,y-by)<dp(38)){setPower(!powered);return true;}

                // Reconnect from the small status area.
                if(y<dp(100)&&x>w-dp(190)){try{if(gatt!=null)gatt.close();}catch(Exception ignored){}
                    writeChar=null;connecting=false;startScan();return true;}
            }
            return true;
        }
    }
}