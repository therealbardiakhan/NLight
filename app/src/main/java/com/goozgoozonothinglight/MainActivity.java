package com.goozgoozonothinglight;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.ColorDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.*;

public class MainActivity extends Activity {
    static final String DEVICE_NAME = "GATT--DEMO";
    static final UUID WRITE_UUID = UUID.fromString("0000fff3-0000-1000-8000-00805f9b34fb");

    BluetoothAdapter adapter;
    BluetoothGatt gatt;
    BluetoothGattCharacteristic writeChar;
    Handler handler = new Handler(Looper.getMainLooper());
    boolean powered = true;
    int rgb = Color.rgb(255, 255, 255);
    int brightness = 80;

    TextView status;
    MainView mainView;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        status = new TextView(this);
        status.setText("STARTING");
        mainView = new MainView(this);
        setContentView(mainView);

        BluetoothManager bm = (BluetoothManager)getSystemService(BLUETOOTH_SERVICE);
        adapter = bm.getAdapter();

        if (Build.VERSION.SDK_INT >= 31) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
            }, 42);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 43);
        }
    }

    @Override public void onRequestPermissionsResult(int r, String[] p, int[] g) {
        super.onRequestPermissionsResult(r,p,g);
        if (r == 42 || r == 43) startScan();
    }

    void startScan() {
        if (adapter == null || !adapter.isEnabled()) {
            status.setText("BLUETOOTH OFF");
            return;
        }
        status.setText("SCANNING");
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) return;

        ScanCallback cb = new ScanCallback() {
            @Override public void onScanResult(int type, ScanResult result) {
                BluetoothDevice d = result.getDevice();
                String n = result.getScanRecord() == null ? null :
                        result.getScanRecord().getDeviceName();
                if (n == null) n = safeName(d);
                if (matches(n)) {
                    scanner.stopScan(this);
                    connect(d);
                }
            }
            @Override public void onScanFailed(int e) {
                status.setText("SCAN ERROR");
            }
        };
        scanner.startScan(cb);
        handler.postDelayed(() -> {
            try { scanner.stopScan(cb); } catch(Exception ignored) {}
            if (writeChar == null) status.setText("NOT FOUND");
        }, 10000);
    }

    boolean matches(String n) {
        if (n == null) return false;
        String s = n.toUpperCase(Locale.US).replace("–","-");
        return s.contains("GATT") && s.contains("DEMO");
    }

    String safeName(BluetoothDevice d) {
        try { return d.getName(); } catch(SecurityException e) { return ""; }
    }

    void connect(BluetoothDevice d) {
        status.setText("CONNECTING");
        try {
            gatt = d.connectGatt(this, false, new BluetoothGattCallback() {
                @Override public void onConnectionStateChange(BluetoothGatt g, int st, int ns) {
                    runOnUiThread(() -> status.setText(
                            ns == BluetoothProfile.STATE_CONNECTED ? "CONNECTED" : "DISCONNECTED"));
                    if (ns == BluetoothProfile.STATE_CONNECTED) g.discoverServices();
                    if (ns == BluetoothProfile.STATE_DISCONNECTED) {
                        writeChar = null;
                        handler.postDelayed(() -> startScan(), 1500);
                    }
                }
                @Override public void onServicesDiscovered(BluetoothGatt g, int st) {
                    writeChar = null;
                    for (BluetoothGattService s : g.getServices()) {
                        BluetoothGattCharacteristic c = s.getCharacteristic(WRITE_UUID);
                        if (c != null) { writeChar = c; break; }
                        for (BluetoothGattCharacteristic x : s.getCharacteristics()) {
                            int p = x.getProperties();
                            if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0 ||
                                (p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                                // Fallback: use a writable characteristic if FFF3 isn't exposed.
                                if (x.getUuid().toString().toLowerCase(Locale.US).endsWith("fff3-0000-1000-8000-00805f9b34fb"))
                                    writeChar = x;
                            }
                        }
                    }
                    runOnUiThread(() -> {
                        status.setText(writeChar != null ? "CONNECTED" : "NO WRITE CHAR");
                        if (writeChar != null) {
                            send(powerFrame(true));
                            send(colorFrame(rgb));
                            send(brightnessFrame(brightness));
                        }
                    });
                }
            });
        } catch(SecurityException e) {
            status.setText("BLUETOOTH PERMISSION");
        }
    }

    void send(byte[] data) {
        if (gatt == null || writeChar == null) return;
        try {
            writeChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
            writeChar.setValue(data);
            gatt.writeCharacteristic(writeChar);
        } catch(Exception ignored) {}
    }

    byte[] powerFrame(boolean on) {
        return new byte[]{(byte)0xBC,0x01,0x01,(byte)(on?1:0),0x55};
    }

    byte[] colorFrame(int c) {
        float[] hsv = new float[3];
        Color.colorToHSV(c, hsv);
        int hue = Math.round(hsv[0]);
        if (hue == 360) hue = 0;
        int sat = Math.round(hsv[1] * 1000f);
        return new byte[]{(byte)0xBC,0x04,0x06,
                (byte)(hue/256),(byte)(hue%256),
                (byte)(sat/256),(byte)(sat%256),
                0,0,0x55};
    }

    byte[] brightnessFrame(int pct) {
        int v = Math.max(3, Math.min(100, pct));
        return new byte[]{(byte)0xBC,0x05,0x06,
                0,(byte)v,0,0,0,0,0x55};
    }

    void setPower(boolean on) {
        powered = on;
        send(powerFrame(on));
        mainView.invalidate();
    }

    void setColor(int c) {
        rgb = c;
        if (!powered) { setPower(true); return; }
        send(colorFrame(c));
    }

    void setBrightness(int b) {
        brightness = b;
        if (!powered) return;
        send(brightnessFrame(b));
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { if (gatt != null) gatt.close(); } catch(Exception ignored) {}
    }

    class MainView extends View {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF wheel = new RectF();
        float cx, cy, radius;
        boolean draggingBrightness = false;

        MainView(Context c) {
            super(c);
            p.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            setBackgroundColor(Color.BLACK);
        }

        void text(Canvas c, String s, float x, float y, float size, int color) {
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(size);
            p.setColor(color);
            p.setTypeface(Typeface.create("monospace", Typeface.NORMAL));
            c.drawText(s, x, y, p);
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            float w=getWidth(), h=getHeight();
            cx=w/2f;
            cy=Math.min(h*0.43f, 500f);
            radius=Math.min(w*0.31f, 170f);

            text(c, "NOTHING LIGHT", 28, 44, 16, Color.WHITE);
            text(c, "GATT—DEMO", 28, 72, 12, Color.LTGRAY);

            p.setColor(statusColor());
            c.drawCircle(w-34, 38, 5, p);
            text(c, status==null ? "..." : status.getText().toString(), w-125, 44, 10, Color.LTGRAY);

            drawWheel(c);
            text(c, "COLOR", 28, cy+radius+55, 11, Color.GRAY);
            text(c, String.format(Locale.US, "#%06X", rgb & 0xFFFFFF), 28, cy+radius+79, 14, Color.WHITE);

            float y=cy+radius+130;
            text(c, "BRIGHTNESS", 28, y, 11, Color.GRAY);
            drawSlider(c, 28, y+25, w-28, brightness/100f);
            text(c, brightness+"%", 28, y+67, 13, Color.WHITE);

            float bottom=h-45;
            text(c, powered ? "ON" : "OFF", 28, bottom, 12, Color.WHITE);
            drawPower(c, w-48, bottom-5, powered);

            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1);
            p.setColor(Color.rgb(45,45,45));
            c.drawLine(28,bottom+12,w-28,bottom+12,p);
        }

        int statusColor() {
            String s=status==null?"":status.getText().toString();
            if (s.equals("CONNECTED")) return Color.WHITE;
            if (s.equals("CONNECTING") || s.equals("SCANNING")) return Color.GRAY;
            return Color.rgb(110,110,110);
        }

        void drawPower(Canvas c,float x,float y,boolean on) {
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.WHITE);
            c.drawCircle(x,y,14,p);
            p.setStyle(Paint.Style.FILL);
            c.drawRect(x-2,y-17,x+2,y,p);
            if(!on) { p.setColor(Color.BLACK); c.drawCircle(x,y,11,p); }
        }

        void drawSlider(Canvas c,float x1,float y,float x2,float frac) {
            p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(55,55,55));
            c.drawRect(x1,y-1,x2,y+1,p);
            p.setColor(Color.WHITE);
            c.drawRect(x1,y-1,x1+(x2-x1)*frac,y+1,p);
            c.drawCircle(x1+(x2-x1)*frac,y,7,p);
        }

        void drawWheel(Canvas c) {
            int size=(int)(radius*2.0f);
            Bitmap b=Bitmap.createBitmap(size,size,Bitmap.Config.ARGB_8888);
            int[] px=new int[size*size];
            for(int yy=0;yy<size;yy++) for(int xx=0;xx<size;xx++) {
                float dx=xx-radius, dy=yy-radius;
                float rr=(float)Math.sqrt(dx*dx+dy*dy);
                if(rr>radius) { px[yy*size+xx]=Color.TRANSPARENT; continue; }
                float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360);
                float sat=Math.min(1f,rr/radius);
                px[yy*size+xx]=Color.HSVToColor(new float[]{hue,sat,1f});
            }
            b.setPixels(px,0,size,0,0,size,size);
            c.drawBitmap(b,cx-radius,cy-radius,p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.WHITE);
            float[] hsv=new float[3]; Color.colorToHSV(rgb,hsv);
            float a=(float)Math.toRadians(hsv[0]);
            float r=hsv[1]*radius;
            c.drawCircle(cx+(float)Math.cos(a)*r,cy+(float)Math.sin(a)*r,8,p);
        }

        @Override public boolean onTouchEvent(android.view.MotionEvent e) {
            float x=e.getX(), y=e.getY();
            if(e.getAction()==MotionEvent.ACTION_DOWN || e.getAction()==MotionEvent.ACTION_MOVE) {
                float dx=x-cx,dy=y-cy, dist=(float)Math.sqrt(dx*dx+dy*dy);
                if(dist<=radius+18 && dist>=0) {
                    float hue=(float)((Math.toDegrees(Math.atan2(dy,dx))+360)%360);
                    float sat=Math.min(1f,dist/radius);
                    rgb=Color.HSVToColor(new float[]{hue,sat,1f});
                    setColor(rgb);
                    invalidate();
                    return true;
                }
                float sy=cy+radius+155;
                if(Math.abs(y-sy)<30 && x>=28 && x<=getWidth()-28) {
                    brightness=Math.round((x-28)/(getWidth()-56)*100f);
                    brightness=Math.max(3,Math.min(100,brightness));
                    setBrightness(brightness);
                    invalidate();
                    return true;
                }
            }
            if(e.getAction()==MotionEvent.ACTION_UP) {
                float bx=getWidth()-48, by=getHeight()-50;
                if(Math.hypot(x-bx,y-by)<30) {
                    setPower(!powered);
                    return true;
                }
                if(y<90 && x>getWidth()-160) {
                    if(gatt!=null) try{gatt.close();}catch(Exception ignored){}
                    writeChar=null; startScan(); return true;
                }
            }
            return true;
        }
    }
}
