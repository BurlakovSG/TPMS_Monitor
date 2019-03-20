package ru.burlakovsg.tpms_monitor;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.media.MediaPlayer;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.rabtman.wsmanager.WsManager;
import com.rabtman.wsmanager.listener.WsStatusListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Response;

import static ru.burlakovsg.tpms_monitor.MainActivity.RL;
import static ru.burlakovsg.tpms_monitor.MainActivity.RR;
import static ru.burlakovsg.tpms_monitor.MainActivity.TL;
import static ru.burlakovsg.tpms_monitor.MainActivity.TR;

public class MainService extends Service {
    private Map<String, Integer> tpmsID = new HashMap<>();
    private Map<String, Double> tpmsValue = new HashMap<>();
    private Map<String, Long> timestamp = new HashMap<>();
    private WsManager wsManager;
    private static MediaPlayer mp;
    private WindowManager windowManager;
    private View floatyView;

    private Notification.Builder notificationBuilder;
    private NotificationManager notificationManager;
    private static final int DEFAULT_NOTIFICATION_ID = 101;

    private SharedPreferences sharedPreferences;

    private static final String TAG = "TPMS_LOG";
    private boolean bind_sensor = false;
    private String wsURL = "ws://192.168.1.108/ws_tpms";
    private String season = "summer";
    private int pingInterval;

    private double  topTirePressureMax,
                    topTirePressureMin,
                    rearTirePressureMax,
                    rearTirePressureMin,
                    maxTireTemperature;

    private Map<Integer, Boolean> warningFlagMap = new HashMap<Integer, Boolean>() {
        {
            put(TL, false);
            put(TR, false);
            put(RL, false);
            put(RR, false);
        }
    };

    private Map<Integer, Integer> stringMap = new HashMap<Integer, Integer>() {
        {
            put(TL, R.string.front_left);
            put(TR, R.string.front_right);
            put(RL, R.string.rear_left);
            put(RR, R.string.rear_right);
        }
    };

    private void readSensors() {
        Map<String, Integer> tires = new HashMap<String, Integer>() {
            {
                put("TL", TL);
                put("TR", TR);
                put("RL", RL);
                put("RR", RR);
            }
        };

        tpmsID.clear();
        tpmsValue.clear();
        timestamp.clear();

        for (Map.Entry<String, Integer> entry : tires.entrySet()) {
            String id = sharedPreferences.getString(season + "_" + entry.getKey(), "");

            if (id != null && !id.equals("")) {
                tpmsID.put(id, entry.getValue());
                warningFlagMap.put(entry.getValue(), false);

                tpmsValue.put(id + "_pressure", 0d);
                tpmsValue.put(id + "_temperature", 0d);
                tpmsValue.put(id + "_voltage", 0d);
                timestamp.put(id, 0L);
            }
        }
    }

    private void readSettings() {
        SharedPreferences settings = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());

        String IPaddress = settings.getString("etpIPaddress", "192.168.1.1");
        wsURL = "ws://" + IPaddress + "/ws_tpms";

        pingInterval = Integer.parseInt(settings.getString("etpPingInterval", "10"));
        season = settings.getString("lpTypeTires", "summer");
        topTirePressureMax = Double.parseDouble(settings.getString("etpMaxFrontTirePressure", "0.0"));
        topTirePressureMin = Double.parseDouble(settings.getString("etpMinFrontTirePressure", "0.0"));
        rearTirePressureMax = Double.parseDouble(settings.getString("etpMaxRearTirePressure", "0.0"));
        rearTirePressureMin = Double.parseDouble(settings.getString("etpMinRearTirePressure", "0.0"));
        maxTireTemperature = Double.parseDouble(settings.getString("etpMaxTireTemperature", "0.0"));
    }

    private void wsConnect() {
        if (wsManager != null) {
            wsManager.stopConnect();
            wsManager = null;
        }

        Log.d(TAG, "WsManager connecting to:" + wsURL);

        wsManager = new WsManager.Builder(getBaseContext())
                .client(
                        new OkHttpClient().newBuilder()
                                .pingInterval(pingInterval, TimeUnit.SECONDS)
                                .retryOnConnectionFailure(true)
                                .build())
                .needReconnect(true)
                .wsUrl(wsURL)
                .build();
        wsManager.setWsStatusListener(wsStatusListener);
        wsManager.startConnect();
    }

    private void displayFloatingView(String msg) {
        if (!Settings.canDrawOverlays(this))
            return;

        if (floatyView != null) {
            windowManager.removeView(floatyView);
            floatyView = null;
        }

        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_PRIORITY_PHONE, //TYPE_APPLICATION_OVERLAY
                        0,
                        PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.CENTER | Gravity.TOP;
        params.x = 0;
        params.y = 0;

        floatyView = ((LayoutInflater) getSystemService(Context.LAYOUT_INFLATER_SERVICE))
                .inflate(R.layout.floating_warning, null);

        final RelativeLayout floatyLayout = floatyView.findViewById(R.id.rlFloating);
        floatyLayout.setOnClickListener(v -> {
            windowManager.removeView(floatyView);
            floatyView = null;

            Intent intent = new Intent(this, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TASK);

            startActivity(intent);
        });

        final ImageView btnClose = floatyView.findViewById(R.id.btnClose);
        btnClose.setOnClickListener( v -> {
            windowManager.removeView(floatyView);
            floatyView = null;
        });

        final TextView tvMesssage = floatyView.findViewById(R.id.tvMessage);
        tvMesssage.setText(msg);

        windowManager.addView(floatyView, params);
    }

    public static void playSound() {
        mp.start();
    }

    private WsStatusListener wsStatusListener = new WsStatusListener() {
        @Override
        public void onOpen(Response response) {
            Log.d(TAG, "WsManager onOpen");

            notificationBuilder.setContentText(getResources().getString(R.string.system_connected));
            notificationBuilder.setColor(ContextCompat.getColor(getBaseContext(), R.color.colorConnected));
            notificationManager.notify(DEFAULT_NOTIFICATION_ID, notificationBuilder.build());

            LocalBroadcastManager.getInstance( getBaseContext() )
                    .sendBroadcast(new Intent(MainActivity.CONNECTION_STATE)
                            .putExtra(MainActivity.CONNECTION_STATE, wsManager.isWsConnected()));
        }

        @Override
        public void onMessage(String msg) {
            Log.d(TAG, "WsManager onMessage:" + msg);

            try{
                JSONObject jsonObject = new JSONObject(msg);

                String type_msg = jsonObject.getString("type");

                if (type_msg.equals("TPMS")) {
                    JSONObject tpms_data = jsonObject.getJSONObject("data");

                    String sensorID = tpms_data.getString("ID");

                    if (tpmsID.containsKey(sensorID)) {
                        tpmsValue.put(sensorID + "_pressure", tpms_data.getDouble("pressure"));
                        tpmsValue.put(sensorID + "_temperature", tpms_data.getDouble("temperature"));
                        tpmsValue.put(sensorID + "_voltage", tpms_data.getDouble("bat_voltage"));
                        timestamp.put(sensorID, System.currentTimeMillis());

                        Integer position = tpmsID.get(sensorID);

                        LocalBroadcastManager.getInstance( getBaseContext() )
                                .sendBroadcast(new Intent(MainActivity.DATA)
                                        .putExtra(MainActivity.POSITION, position)
                                        .putExtra(MainActivity.PRESSURE, tpms_data.getDouble("pressure"))
                                        .putExtra(MainActivity.TEMPERATURE, tpms_data.getDouble("temperature"))
                                        .putExtra(MainActivity.VOLTAGE, tpms_data.getDouble("bat_voltage"))
                                        .putExtra(MainActivity.TIMESTAMP, System.currentTimeMillis()));

                        double maxPressure = 0.0D;
                        double minPressure = 0.0D;

                        switch (position) {
                            case TL:
                            case TR:
                                maxPressure = topTirePressureMax;
                                minPressure = topTirePressureMin;
                                break;

                            case RL:
                            case RR:
                                maxPressure = rearTirePressureMax;
                                minPressure = rearTirePressureMin;
                                break;
                        }

                        if ( tpms_data.getDouble("pressure") < minPressure
                                || tpms_data.getDouble("pressure") > maxPressure
                                || tpms_data.getDouble("temperature") > maxTireTemperature ) {

                            if (!warningFlagMap.get(tpmsID.get(sensorID))) {
                                if (!MainActivity.isActivityVisible()) {
                                    String msgWarning = "";

                                    if (tpms_data.getDouble("pressure") < minPressure)
                                        msgWarning = String.format(Locale.getDefault(),
                                                getResources().getString(R.string.lower_pressure),
                                                getResources().getString(stringMap.get(tpmsID.get(sensorID))));

                                    if (tpms_data.getDouble("pressure") > maxPressure)
                                        msgWarning = String.format(Locale.getDefault(),
                                                getResources().getString(R.string.higher_pressure),
                                                getResources().getString(stringMap.get(tpmsID.get(sensorID))));

                                    if (tpms_data.getDouble("temperature") > maxTireTemperature)
                                        msgWarning = String.format(Locale.getDefault(),
                                                getResources().getString(R.string.higher_temperature),
                                                getResources().getString(stringMap.get(tpmsID.get(sensorID))));

                                    playSound();
                                    displayFloatingView(msgWarning);
                                }
                                warningFlagMap.put(tpmsID.get(sensorID), true);
                            }
                        } else {
                            if (warningFlagMap.get(tpmsID.get(sensorID)))
                                warningFlagMap.put(tpmsID.get(sensorID), false);
                        }
                    }

                    if (bind_sensor) {
                        LocalBroadcastManager.getInstance( getBaseContext() )
                                .sendBroadcast(new Intent(BindActivity.BIND_DATA)
                                        .putExtra(BindActivity.SENSOR_ID, sensorID));
                    }
                }
            } catch (JSONException e){
                Log.d(TAG, "JSON error:" + e);
            }
        }

        @Override
        public void onReconnect() {
            Log.d(TAG, "WsManager onReconnect");

            notificationBuilder.setContentText(getResources().getString(R.string.reconnect));
            notificationBuilder.setColor(ContextCompat.getColor(getBaseContext(), R.color.colorDisconnected));
            notificationManager.notify(DEFAULT_NOTIFICATION_ID, notificationBuilder.build());
        }

        @Override
        public void onFailure(Throwable t, Response response) {
            Log.d(TAG, "WsManager onFailure");

            LocalBroadcastManager.getInstance( getBaseContext() )
                    .sendBroadcast(new Intent(MainActivity.CONNECTION_STATE)
                            .putExtra(MainActivity.CONNECTION_STATE, wsManager.isWsConnected()));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service OnCreate");

        sharedPreferences = getSharedPreferences(MainActivity.APP_PREFERENCES, Context.MODE_PRIVATE);
        mp = MediaPlayer.create(this, R.raw.alert);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        Intent notificationIntent = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK);

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        notificationIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT);

        notificationBuilder = new Notification.Builder(this)
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(getResources().getString(R.string.service_running))
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(ContextCompat.getColor(this, R.color.colorDisconnected))
                .setContentIntent(pendingIntent);

        Notification notification = notificationBuilder.build();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(DEFAULT_NOTIFICATION_ID, notification);
        startForeground(DEFAULT_NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand:" + intent.getAction());

        if (intent.getAction() == null) {
            readSettings();
            readSensors();
            wsConnect();
        } else {
            switch (intent.getAction()) {
                case MainActivity.GET_STATE:
                    LocalBroadcastManager.getInstance( getBaseContext() )
                            .sendBroadcast(new Intent(MainActivity.CONNECTION_STATE)
                            .putExtra(MainActivity.CONNECTION_STATE, wsManager.isWsConnected()));
                    break;

                case MainActivity.GET_DATA:
                    for (Map.Entry<String, Integer> entry : tpmsID.entrySet()) {
                        LocalBroadcastManager.getInstance( getBaseContext() )
                                .sendBroadcast(new Intent(MainActivity.DATA)
                                        .putExtra(MainActivity.POSITION, (int)entry.getValue())
                                        .putExtra(MainActivity.PRESSURE, (double)tpmsValue.get(entry.getKey() + "_pressure"))
                                        .putExtra(MainActivity.TEMPERATURE, (double)tpmsValue.get(entry.getKey() + "_temperature"))
                                        .putExtra(MainActivity.VOLTAGE, (double)tpmsValue.get(entry.getKey() + "_voltage"))
                                        .putExtra(MainActivity.TIMESTAMP, (long)timestamp.get(entry.getKey())));
                    }
                    break;

                case BindActivity.CHANGE_SENSOR_ID:
                    readSensors();
                    break;

                case BindActivity.BIND_START:
                    bind_sensor = true;
                    break;

                case BindActivity.BIND_STOP:
                    bind_sensor = false;
                    break;

                case MainActivity.SETTINGS_CHANGED:
                    readSettings();
                    readSensors();
                    wsConnect();
                    break;
            }
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service OnDestroy");

        if (wsManager != null) {
            wsManager.stopConnect();
            wsManager = null;
        }

        if (mp != null) {
            mp.release();
            mp = null;
        }

        if (floatyView != null) {
            windowManager.removeView(floatyView);
            floatyView = null;
        }

        super.onDestroy();
        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
//        throw new UnsupportedOperationException("Not yet implemented");
        return null;
    }
}
