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
    float uiScale = 1.15f;
    int insetTop = 0, insetBottom = 0;
    boolean sizePanel = false;
    android.content.SharedPreferences prefs;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("nothing_light", MODE_PRIVATE);
        uiScale = prefs.getFloat("ui_scale", 1.15f);

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

    void setPower(boolean on){ powered=on; send(powerFrame(on)); view.invalidate(); }
    void setColor(int c){ rgb=c; powered=true; send(powerFrame(true)); handler.postDelayed(()->send(colorFrame(c)),50); view.invalidate(); }
    void setBrightness(int b){ brightness=b; send(brightnessFrame(b)); view.invalidate(); }

    void setUiScale(float scale) {
        uiScale = Math.max(0.80f, Math.min(1.35f, scale));
        prefs.edit().putFloat("ui_scale", uiScale).apply();
        if (view != null) view.invalidate();
    }

    @Override protected void onDestroy(){ try{if(gatt!=null)gatt.close();}catch(Exception ignored){} super.onDestroy(); }

    class MainView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float den, cx, cy, radius;
        int activePreset = -1;

        final int[] PRESETS = {
            Color.WHITE, Color.RED, Color.rgb(255, 128, 0), Color.YELLOW,
            Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA
        };

        MainView(Context c) {
            super(c);
            den = getResources().getDisplayMetrics().density;
            setBackgroundColor(Color.BLACK);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            setFocusable(true);
        }

        float dp(float x) { return x * den * uiScale; }
        float rawDp(float x) { return x * den; }

        void txt(Canvas c, String s, float x, float y, float sz, int col) {
            p.setStyle(Paint.Style.FILL);
            p.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            p.setTextSize(dp(sz));
            p.setColor(col);
            c.drawText(s, x, y, p);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);

            // Move ALL custom drawing below the real status bar and above the navigation
            // area. This is the key fix for Android 15/16 edge-to-edge phones.
            c.save();
            c.translate(0, insetTop);

            float w = getWidth();
            float h = getHeight() - insetTop - insetBottom;
            float side = Math.max(dp(24), w * 0.065f);
            float top = dp(48); // extra breathing room below the status bar
            cx = w / 2f;
            cy = top + dp(190);
            radius = Math.min(dp(126), (w - side * 2) * 0.38f);

            txt(c, "NOTHING LIGHT", side, top, 20, Color.WHITE);
            txt(c, "GATT—DEMO", side, top + dp(30), 12, Color.GRAY);

            // Small UI-size button in the safe top-right corner.
            drawSizeButton(c, w - side, top - dp(3));

            p.setStyle(Paint.Style.FILL);
            p.setColor(state.equals("CONNECTED") ? Color.WHITE :
                    state.equals("CONNECTING") || state.equals("SCANNING") ?
                    Color.GRAY : Color.DKGRAY);
            c.drawCircle(w - side - dp(46), top - dp(5), dp(4), p);
            txt(c, state, w - side - dp(132), top, 10, Color.GRAY);

            drawWheel(c);

            float presetY = cy + radius + dp(32);
            drawPresets(c, presetY);

            float labelY = presetY + dp(45);
            txt(c, "COLOR", side, labelY, 12, Color.GRAY);
            txt(c, String.format(Locale.US, "#%06X", rgb & 0xFFFFFF),
                    side, labelY + dp(25), 16, Color.WHITE);

            float sy = labelY + dp(72);
            txt(c, "BRIGHTNESS", side, sy, 12, Color.GRAY);
            float sliderLeft = side;
            float sliderRight = w - side;
            float sliderY = sy + dp(28);
            drawSlider(c, sliderLeft, sliderY, sliderRight, brightness / 100f);
            txt(c, brightness + "%", side, sy + dp(63), 15, Color.WHITE);

            float bottom = h - dp(32);
            txt(c, powered ? "ON" : "OFF", side, bottom, 13, Color.WHITE);
            drawPower(c, w - side, bottom - dp(5), powered);

            p.setColor(Color.rgb(35,35,35));
            p.setStrokeWidth(Math.max(1f, rawDp(1)));
            c.drawLine(side, bottom + dp(12), w - side, bottom + dp(12), p);

            if (sizePanel) drawSizePanel(c, w, h, side);

            c.restore();
        }

        void drawSizeButton(Canvas c, float x, float y) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(rawDp(1.5f));
            p.setColor(Color.WHITE);
            c.drawRoundRect(x-dp(17), y-dp(15), x+dp(17), y+dp(15), dp(4), dp(4), p);
            txt(c, "UI", x-dp(9), y+dp(6), 10, Color.WHITE);
        }

        void drawSizePanel(Canvas c, float w, float h, float side) {
            float panelW = Math.min(dp(270), w - side*2);
            float panelH = dp(100);
            float left = w - side - panelW;
            float top = dp(82);

            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.rgb(10,10,10));
            c.drawRoundRect(left, top, w-side, top+panelH, dp(8), dp(8), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(rawDp(1));
            p.setColor(Color.rgb(70,70,70));
            c.drawRoundRect(left, top, w-side, top+panelH, dp(8), dp(8), p);

            txt(c, "UI SIZE", left+dp(15), top+dp(25), 11, Color.GRAY);
            txt(c, String.format(Locale.US, "%.0f%%", uiScale*100f),
                    left+dp(15), top+dp(52), 19, Color.WHITE);

            // Minus and plus are deliberately large touch targets.
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(rawDp(1.5f));
            p.setColor(Color.WHITE);
            float minusX = left + panelW - dp(68);
            float plusX = left + panelW - dp(24);
            float by = top + dp(50);
            c.drawCircle(minusX, by, dp(15), p);
            c.drawCircle(plusX, by, dp(15), p);
            txt(c, "−", minusX-dp(7), by+dp(7), 20, Color.WHITE);
            txt(c, "+", plusX-dp(7), by+dp(7), 18, Color.WHITE);

            txt(c, "TAP UI TO CLOSE", left+dp(15), top+dp(82), 9, Color.GRAY);
        }

        void drawPresets(Canvas c, float y) {
            float w = getWidth();
            float side = Math.max(dp(24), w * 0.065f);
            float usable = w - side * 2;
            float step = usable / 8f;
            float r = dp(17);

            for (int i = 0; i < PRESETS.length; i++) {
                float x = side + step * (i + 0.5f);
                p.setStyle(Paint.Style.FILL);
                p.setColor(PRESETS[i]);
                c.drawCircle(x, y, r, p);

                if (rgb == PRESETS[i]) {
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeWidth(rawDp(2));
                    p.setColor(Color.WHITE);
                    c.drawCircle(x, y, r + dp(4), p);
                }
            }
        }

        void drawSlider(Canvas c, float x1, float y, float x2, float frac) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(4));
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(Color.rgb(55,55,55));
            c.drawLine(x1, y, x2, y, p);

            float end = x1 + (x2-x1)*frac;
            p.setColor(Color.WHITE);
            c.drawLine(x1, y, end, y, p);
            p.setStyle(Paint.Style.FILL);
            c.drawCircle(end, y, dp(8), p);
        }

        void drawPower(Canvas c, float x, float y, boolean on) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(Color.WHITE);
            c.drawCircle(x, y, dp(15), p);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(x-dp(2.5f), y-dp(19), x+dp(2.5f), y, p);
            if (!on) {
                p.setColor(Color.BLACK);
                c.drawCircle(x, y, dp(12), p);
            }
        }

        void drawWheel(Canvas c) {
            int size = Math.max(2, (int)(radius * 2));
            Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            int[] px = new int[size * size];

            for (int yy=0; yy<size; yy++) for (int xx=0; xx<size; xx++) {
                float dx=xx-radius, dy=yy-radius;
                float rr=(float)Math.sqrt(dx*dx+dy*dy);
                if(rr>radius){px[yy*size+xx]=Color.TRANSPARENT;continue;}
                float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360);
                float sat=Math.min(1f,rr/radius);
                px[yy*size+xx]=Color.HSVToColor(new float[]{hue,sat,1f});
            }

            b.setPixels(px,0,size,0,0,size,size);
            c.drawBitmap(b,cx-radius,cy-radius,p);

            float[] hsv=new float[3];
            Color.colorToHSV(rgb,hsv);
            float a=(float)Math.toRadians(hsv[0]), r=hsv[1]*radius;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2.5f));
            p.setColor(Color.WHITE);
            c.drawCircle(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r,dp(9),p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            // Convert raw touch coordinates from edge-to-edge view coordinates to our
            // content coordinates below the status bar.
            float x=e.getX(), y=e.getY()-insetTop;
            float w=getWidth();
            float side=Math.max(dp(24),w*0.065f);
            float top=dp(48);
            float wheelCx=w/2f, wheelCy=top+dp(190);
            float wheelR=Math.min(dp(126),(w-side*2)*0.38f);
            float presetY=wheelCy+wheelR+dp(32);
            float labelY=presetY+dp(45);
            float sy=labelY+dp(72);
            float sliderY=sy+dp(28);

            if(e.getAction()==MotionEvent.ACTION_DOWN ||
               e.getAction()==MotionEvent.ACTION_MOVE) {

                // UI scale panel.
                if(sizePanel) {
                    float panelW=Math.min(dp(270),w-side*2);
                    float left=w-side-panelW, panelTop=dp(82), panelH=dp(100);
                    float minusX=left+panelW-dp(68), plusX=left+panelW-dp(24), by=panelTop+dp(50);
                    if(Math.hypot(x-minusX,y-by)<dp(27)) {
                        setUiScale(uiScale-0.05f); return true;
                    }
                    if(Math.hypot(x-plusX,y-by)<dp(27)) {
                        setUiScale(uiScale+0.05f); return true;
                    }
                    if(x>=left && x<=w-side && y>=panelTop && y<=panelTop+panelH)
                        return true;
                }

                float dx=x-wheelCx,dy=y-wheelCy;
                float dist=(float)Math.sqrt(dx*dx+dy*dy);
                if(dist<=wheelR+dp(18)) {
                    float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360);
                    float sat=Math.min(1f,dist/wheelR);
                    rgb=Color.HSVToColor(new float[]{hue,sat,1f});
                    activePreset=-1; setColor(rgb); invalidate(); return true;
                }

                if(Math.abs(y-sliderY)<dp(38) &&
                   x>=side-dp(10)&&x<=w-side+dp(10)) {
                    float f=(x-side)/(w-side*2);
                    f=Math.max(0f,Math.min(1f,f));
                    brightness=Math.max(1,Math.min(100,Math.round(f*100f)));
                    setBrightness(brightness); invalidate(); return true;
                }

                if(Math.abs(y-presetY)<dp(34)) {
                    float usable=w-side*2,step=usable/8f;
                    int index=(int)((x-side)/step);
                    if(index>=0&&index<PRESETS.length){
                        rgb=PRESETS[index];activePreset=index;setColor(rgb);invalidate();return true;
                    }
                }
            }

            if(e.getAction()==MotionEvent.ACTION_UP) {
                // UI size control.
                float uiX=w-side, uiY=top-dp(3);
                if(Math.hypot(x-uiX,y-uiY)<dp(28)) {
                    sizePanel=!sizePanel; invalidate(); return true;
                }

                // If panel is open, tapping elsewhere closes it.
                if(sizePanel) { sizePanel=false; invalidate(); return true; }

                float bottom=getHeight()-insetTop-insetBottom-dp(32);
                float bx=w-side,by=bottom-dp(5);
                if(Math.hypot(x-bx,y-by)<dp(38)){
                    setPower(!powered); return true;
                }

                // Status area: tap to force a fresh scan/reconnect.
                if(y<dp(100)&&x>w-dp(190)){
                    try{if(gatt!=null)gatt.close();}catch(Exception ignored){}
                    writeChar=null;connecting=false;startScan();return true;
                }
            }
            return true;
        }
    }
}