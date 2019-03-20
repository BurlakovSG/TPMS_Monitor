package ru.burlakovsg.tpms_monitor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {
    private final static String TAG = "TPMS_LOG";
    public final static int REQUEST_CODE = 10101;

    private static boolean visible;
    private String season;
    private int dataTimeout;
    private Map<Integer, ImageView> infoMap= new HashMap<>();
    private Map<Integer, ImageView> warningMap= new HashMap<>();
    private Map<Integer, ImageView> connectMap= new HashMap<>();
    private Map<Integer, ImageView> disconnectMap= new HashMap<>();
    private Map<Integer, TextView> pressureMap= new HashMap<>();
    private Map<Integer, TextView> tempearatureMap= new HashMap<>();
    private Map<Integer, TextView> batteryMap= new HashMap<>();

    private Map<Integer, Long> tsMap = new HashMap<Integer, Long>() {
        {
            put(TL, (long) 0);
            put(TR, (long) 0);
            put(RL, (long) 0);
            put(RR, (long) 0);
        }
    };

    private Map<Integer, Boolean> warningFlagMap = new HashMap<Integer, Boolean>() {
        {
            put(TL, false);
            put(TR, false);
            put(RL, false);
            put(RR, false);
        }
    };

    private Map<Integer, String> tireMap = new HashMap<Integer, String>() {
        {
            put(TL, "TL");
            put(TR, "TR");
            put(RL, "RL");
            put(RR, "RR");
        }
    };

    private double  topTirePressureMax,
                    topTirePressureMin,
                    rearTirePressureMax,
                    rearTirePressureMin,
                    maxTireTemperature;

    public static final int     TL                  = 1,
                                TR                  = 2,
                                RL                  = 3,
                                RR                  = 4;

    public static final String  GET_STATE           = "GET_STATE",
                                CONNECTION_STATE    = "CONNECTION_STATE",
                                GET_DATA            = "GET_DATA",
                                DATA                = "DATA",
                                POSITION            = "POSITION",
                                PRESSURE            = "PRESSURE",
                                TEMPERATURE         = "TEMPERATURE",
                                VOLTAGE             = "VOLTAGE",
                                TIMESTAMP           = "TIMESTAMP",
                                APP_PREFERENCES     = "APP_PREFERENCES",
                                SETTINGS_CHANGED    = "SETTINGS_CHANGED";

    private ImageView   ivConnected,
                        ivDisconnected,
                        ivTypeTires;

    private boolean isMyServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MainService.class.getName().equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private void readSettings() {
        SharedPreferences settings = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());

        season = settings.getString("lpTypeTires", "summer");
        topTirePressureMax = Double.parseDouble(settings.getString("etpMaxFrontTirePressure", "0.0"));
        topTirePressureMin = Double.parseDouble(settings.getString("etpMinFrontTirePressure", "0.0"));
        rearTirePressureMax = Double.parseDouble(settings.getString("etpMaxRearTirePressure", "0.0"));
        rearTirePressureMin = Double.parseDouble(settings.getString("etpMinRearTirePressure", "0.0"));
        maxTireTemperature = Double.parseDouble(settings.getString("etpMaxTireTemperature", "0.0"));
        dataTimeout = Integer.parseInt(settings.getString("etpDataTimeout", "10"));
    }

    public void checkDrawOverlayPermission() {
        // Checks if app already has permission to draw overlays
        if (!Settings.canDrawOverlays(this)) {

            // If not, form up an Intent to launch the permission request
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));

            // Launch Intent, with the supplied request code
            startActivityForResult(intent, REQUEST_CODE);
        }
    }

    public static boolean isActivityVisible() {
        return visible;
    }

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            int tire = msg.what;

            if (tire == 0)
                return;

            long timestamp = System.currentTimeMillis();
            long diff = timestamp - tsMap.get(tire);

            if (diff > dataTimeout * 1000) {
                disconnectMap.get(tire).setVisibility(View.VISIBLE);
                connectMap.get(tire).setVisibility(View.INVISIBLE);
            }
        }
    };

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null) {
                switch (intent.getAction()) {
                    case CONNECTION_STATE:
                        ivConnected.setVisibility(intent
                                .getBooleanExtra(CONNECTION_STATE, false) ? View.VISIBLE : View.INVISIBLE);

                        ivDisconnected.setVisibility(intent
                                .getBooleanExtra(CONNECTION_STATE, false) ? View.INVISIBLE : View.VISIBLE);
                        break;

                    case DATA:
                        long currentTimestamp = System.currentTimeMillis();
                        long importTimestamp = intent.getLongExtra(TIMESTAMP, 0L);
                        int tire = intent.getIntExtra(POSITION, 0);

                        if (importTimestamp == 0 || tire == 0)
                            break;

                        double pressure = intent.getDoubleExtra(PRESSURE, 0d);
                        double temperature = intent.getDoubleExtra(TEMPERATURE, 0d);
                        double voltage = intent.getDoubleExtra(VOLTAGE, 0d);
                        long diff = currentTimestamp - importTimestamp;

                        pressureMap.get(tire).setText(String.format(Locale.getDefault(), "%.2f", pressure));
                        tempearatureMap.get(tire).setText(String.format(Locale.getDefault(),"%.2f", temperature));
                        batteryMap.get(tire).setText(String.format(Locale.getDefault(),"%.2f", voltage));
                        tsMap.put(tire, importTimestamp);

                        if (    ( (tire == TL || tire == TR)
                                    && pressure < topTirePressureMin
                                    || pressure > topTirePressureMax
                                ) ||
                                ( (tire == RL || tire == RR)
                                    && pressure < rearTirePressureMin
                                    || pressure > rearTirePressureMax
                                ) || temperature > maxTireTemperature ) {
                            if (!warningFlagMap.get(tire)) {
                                MainService.playSound();
                                infoMap.get(tire).setVisibility(View.INVISIBLE);
                                warningMap.get(tire).setVisibility(View.VISIBLE);
                                warningFlagMap.put(tire, true);
                            }
                        } else {
                            if (warningFlagMap.get(tire)) {
                                infoMap.get(tire).setVisibility(View.VISIBLE);
                                warningMap.get(tire).setVisibility(View.INVISIBLE);
                                warningFlagMap.put(tire, false);
                            }
                        }

                        if (diff < dataTimeout * 1000) {
                            disconnectMap.get(tire).setVisibility(View.INVISIBLE);
                            connectMap.get(tire).setVisibility(View.VISIBLE);
                            mHandler.sendMessageDelayed(mHandler.obtainMessage(tire),
                                    ((dataTimeout * 1000) - diff));
                        } else {
                            disconnectMap.get(tire).setVisibility(View.VISIBLE);
                            connectMap.get(tire).setVisibility(View.INVISIBLE);
                        }
                        break;
                }
            }
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode,  Intent data) {
        if (requestCode == REQUEST_CODE) {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Sorry. Can't draw overlays without permission...",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "tpmsActivity onCreate");

        checkDrawOverlayPermission();

        if (!isMyServiceRunning()){
            startService(new Intent(this, MainService.class));
            Log.d(TAG, "tpmsActivity start service");
        }

        ivConnected         = findViewById(R.id.ivConnected);
        ivDisconnected      = findViewById(R.id.ivDisconnected);
        ivTypeTires         = findViewById(R.id.ivTypeTires);

        for (Map.Entry<Integer, String> tire : tireMap.entrySet()) {
            infoMap.put(tire.getKey(), (ImageView) findViewById(MainActivity.this.getResources()
                    .getIdentifier("iv" + tire.getValue() + "info",
                            "id",
                            MainActivity.this.getPackageName())));

            warningMap.put(tire.getKey(), (ImageView) findViewById(MainActivity.this.getResources()
                    .getIdentifier("iv" + tire.getValue() + "warning",
                            "id",
                            MainActivity.this.getPackageName())));

            connectMap.put(tire.getKey(), (ImageView) findViewById(MainActivity.this.getResources()
                    .getIdentifier("iv" + tire.getValue() + "connectionOk",
                            "id",
                            MainActivity.this.getPackageName())));

            disconnectMap.put(tire.getKey(), (ImageView) findViewById(MainActivity.this.getResources()
                    .getIdentifier("iv" + tire.getValue() + "connectionError",
                            "id",
                            MainActivity.this.getPackageName())));

            pressureMap.put(tire.getKey(), (TextView) findViewById(MainActivity.this.getResources()
                    .getIdentifier("tv" + tire.getValue() + "pressureValue",
                            "id",
                            MainActivity.this.getPackageName())));

            tempearatureMap.put(tire.getKey(), (TextView) findViewById(MainActivity.this.getResources()
                    .getIdentifier("tv" + tire.getValue() + "tempValue",
                            "id",
                            MainActivity.this.getPackageName())));

            batteryMap.put(tire.getKey(), (TextView) findViewById(MainActivity.this.getResources()
                    .getIdentifier("tv" + tire.getValue() + "batValue",
                            "id",
                            MainActivity.this.getPackageName())));
        }

        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });

        ImageButton btnBind = findViewById(R.id.btnBind);
        btnBind.setOnClickListener(v -> {
            Intent intent = new Intent(this, BindActivity.class);
            startActivity(intent);
        });
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "tpmsActivity onResume");

        readSettings();

        ivTypeTires.setImageResource(MainActivity.this.getResources()
                .getIdentifier(season, "drawable", MainActivity.this.getPackageName()));

        for (Map.Entry<Integer, String> tire : tireMap.entrySet()) {
            pressureMap.get(tire.getKey()).setText(R.string.str_empty_value);
            tempearatureMap.get(tire.getKey()).setText(R.string.str_empty_value);
            batteryMap.get(tire.getKey()).setText(R.string.str_empty_value);
            infoMap.get(tire.getKey()).setVisibility(View.VISIBLE);
            warningMap.get(tire.getKey()).setVisibility(View.INVISIBLE);
            connectMap.get(tire.getKey()).setVisibility(View.INVISIBLE);
            disconnectMap.get(tire.getKey()).setVisibility(View.VISIBLE);
            warningFlagMap.put(tire.getKey(), false);
        }

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(CONNECTION_STATE);
        intentFilter.addAction(DATA);

        LocalBroadcastManager.getInstance(this)
                .registerReceiver( receiver, intentFilter );

        startService(new Intent(this, MainService.class)
                .setAction(GET_STATE));

        startService(new Intent(this, MainService.class)
                .setAction(GET_DATA));

        visible = true;
        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "tpmsActivity onPause");

        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver( receiver );

        visible = false;
        super.onPause();
    }
}
