package ru.burlakovsg.tpms_monitor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;

import java.util.HashMap;
import java.util.Map;

public class BindActivity extends Activity {
    private final static String TAG = "TPMS_LOG";
    private static final Float  VISIBLE = 1F,
                                INVISIBLE = 0F;
    private String season;
    private String bindType = "";
    private byte bindedSensors;
    private byte countSelected;
    private String exchangeTire = "";
    private SharedPreferences sharedPreferences;
    private Button btnBindAll;

    public static final String  BIND_START          = "BIND_START",
                                BIND_STOP           = "BIND_STOP",
                                BIND_DATA           = "BIND_DATA",
                                SENSOR_ID           = "SENSOR_ID",
                                CHANGE_SENSOR_ID    = "CHANGE_SENSOR_ID";

    private Map<String, EditText> etMap = new HashMap<>();
    private Map<String, Boolean> selectMap = new HashMap<>();
    private Map<String, ImageView> tireMap = new HashMap<>();
    private Map<String, ImageView> exchangeMap = new HashMap<>();
    private Map<String, ImageView> ibExchangeMap = new HashMap<>();
    private Map<Integer, String> idMap = new HashMap<>();
    private Map<String, Button> btnBindMap = new HashMap<>();

    private BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(BIND_DATA)) {
                String id = intent.getStringExtra(SENSOR_ID);

                if (id.length() > 0) {
                    if (bindType.equals("ALL")) {
                        for (Map.Entry<String, EditText> entry : etMap.entrySet()) {
                            if (entry.getValue().getText().toString().length() > 0) {
                                if (entry.getValue().getText().toString().equals(id))
                                    break;
                            } else {
                                entry.getValue().setText(id);
                                bindedSensors++;
                                break;
                            }
                        }

                        if (bindedSensors > 3) {
                            bindType = "";
                            btnBindAll.setText(R.string.start_auto_bind_all);
                        }
                    } else {
                        for (Map.Entry<String, EditText> entry : etMap.entrySet()) {
                            if (entry.getKey().equals(bindType))
                                continue;

                            if (entry.getValue().getText().toString().equals(id)) {
                                return;
                            }
                        }

                        etMap.get(bindType).setText(id);
                        btnBindMap.get(bindType).setText(R.string.start_auto_bind);
                        bindType = "";
                    }

                    if (bindType.length() == 0) {
                        startService(new Intent(context, MainService.class)
                                .setAction(BindActivity.BIND_STOP));
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bind);

        Log.d(TAG, "BindActivity onCreate");

        btnBindAll = findViewById(R.id.btnBindAll);

        etMap.put("TL", findViewById(R.id.etTLsensorID));
        etMap.put("TR", findViewById(R.id.etTRsensorID));
        etMap.put("RL", findViewById(R.id.etRLsensorID));
        etMap.put("RR", findViewById(R.id.etRRsensorID));

        selectMap.put("TL", false);
        selectMap.put("TR", false);
        selectMap.put("RL", false);
        selectMap.put("RR", false);

        tireMap.put("TL", findViewById(R.id.ivTLselected));
        tireMap.put("TR", findViewById(R.id.ivTRselected));
        tireMap.put("RL", findViewById(R.id.ivRLselected));
        tireMap.put("RR", findViewById(R.id.ivRRselected));

        idMap.put(R.id.ivTLselected, "TL");
        idMap.put(R.id.ivTRselected, "TR");
        idMap.put(R.id.ivRLselected, "RL");
        idMap.put(R.id.ivRRselected, "RR");

        idMap.put(R.id.btnTLbind, "TL");
        idMap.put(R.id.btnTRbind, "TR");
        idMap.put(R.id.btnRLbind, "RL");
        idMap.put(R.id.btnRRbind, "RR");

        btnBindMap.put("TL", findViewById(R.id.btnTLbind));
        btnBindMap.put("TR", findViewById(R.id.btnTRbind));
        btnBindMap.put("RL", findViewById(R.id.btnRLbind));
        btnBindMap.put("RR", findViewById(R.id.btnRRbind));

        exchangeMap.put("RRRL", findViewById(R.id.ivRLRRexchange));
        exchangeMap.put("RRTL", findViewById(R.id.ivTLRRexchange));
        exchangeMap.put("RRTR", findViewById(R.id.ivTRRRexchange));
        exchangeMap.put("RLTL", findViewById(R.id.ivTLRLexchange));
        exchangeMap.put("RLTR", findViewById(R.id.ivTRRLexchange));
        exchangeMap.put("TRTL", findViewById(R.id.ivTLTRexchange));

        ibExchangeMap.put("RRRL", findViewById(R.id.ibRLRRexchange));
        ibExchangeMap.put("RRTL", findViewById(R.id.ibDiagonalExchange));
        ibExchangeMap.put("RRTR", findViewById(R.id.ibTRRRexchange));
        ibExchangeMap.put("RLTL", findViewById(R.id.ibTLRLexchange));
        ibExchangeMap.put("RLTR", findViewById(R.id.ibDiagonalExchange));
        ibExchangeMap.put("TRTL", findViewById(R.id.ibTLTRexchange));

        sharedPreferences = getSharedPreferences(MainActivity.APP_PREFERENCES, MODE_PRIVATE);

        countSelected = 0;
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "BindActivity onResume");

        SharedPreferences settings = PreferenceManager
                .getDefaultSharedPreferences(getBaseContext());
        season = settings.getString("lpTypeTires", "summer");

        for (Map.Entry<String, EditText> entry : etMap.entrySet()) {
            String id = sharedPreferences.getString(season + "_" + entry.getKey(), "");

            if (id != null && !id.equals("")) {
                entry.getValue().setText(id);
            }
        }

        LocalBroadcastManager.getInstance(this)
                .registerReceiver( receiver, new IntentFilter(BIND_DATA));

        super.onResume();
    }

    @Override
    protected void onPause() {
        Log.d(TAG, "BindActivity onPause");

        for (Map.Entry<String, EditText> entry : etMap.entrySet()) {
            if (!entry.getValue().getText().toString().equals("")) {
                sharedPreferences
                        .edit()
                        .putString(season + "_" + entry.getKey(), entry.getValue().getText().toString())
                        .apply();
            } else {
                sharedPreferences
                        .edit()
                        .remove(season + "_" + entry.getKey())
                        .apply();
            }
        }

        startService(new Intent(this, MainService.class)
                .setAction(BIND_STOP));

        startService(new Intent(this, MainService.class)
                .setAction(CHANGE_SENSOR_ID));

        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver( receiver );

        for (Map.Entry<String, Button> entry : btnBindMap.entrySet()) {
            entry.getValue().setText(R.string.start_auto_bind);
        }

        btnBindAll.setText(R.string.start_auto_bind_all);

        bindedSensors = 0;
        bindType = "";

        super.onPause();
    }

    private void clearSelected() {
        for (Map.Entry<String, ImageView> entry : tireMap.entrySet()) {
            entry.getValue().setAlpha(INVISIBLE);
        }
    }

    private void clearExchange() {
        if (ibExchangeMap.containsKey(exchangeTire))
            ibExchangeMap.get(exchangeTire).setVisibility(View.INVISIBLE);

        exchangeTire = "";
        for (Map.Entry<String, ImageView> entry : exchangeMap.entrySet()) {
            entry.getValue().setVisibility(View.INVISIBLE);
        }
    }

    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.btnBindAll:
                if (bindType.length() > 0 && bindType.equals("ALL")) {
                    bindType = "";
                    btnBindAll.setText(R.string.start_auto_bind_all);

                    startService(new Intent(this, MainService.class)
                            .setAction(BindActivity.BIND_STOP));
                } else {
                    if (bindType.length() > 0)
                        btnBindMap.get(bindType).setText(R.string.start_auto_bind);

                    bindType = "ALL";
                    for (Map.Entry<String, EditText> entry : etMap.entrySet()) {
                        entry.getValue().setText("");
                    }
                    btnBindAll.setText(R.string.stop_auto_bind_all);
                }
                bindedSensors = 0;
                break;

            case R.id.btnTLbind:
            case R.id.btnTRbind:
            case R.id.btnRLbind:
            case R.id.btnRRbind:
                if (bindType.length() > 0) {
                    if (bindType.equals("ALL"))
                        btnBindAll.setText(R.string.start_auto_bind_all);
                    else
                        btnBindMap.get(bindType).setText(R.string.start_auto_bind);
                }

                if (!bindType.equals(idMap.get(view.getId()))) {
                    bindType = idMap.get(view.getId());
                    btnBindMap.get(bindType).setText(R.string.stop_auto_bind);
                    etMap.get(idMap.get(view.getId())).setText("");
                } else {
                    bindType = "";
                    startService(new Intent(this, MainService.class)
                            .setAction(BindActivity.BIND_STOP));
                }

                break;

            case R.id.ivTLselected:
            case R.id.ivTRselected:
            case R.id.ivRLselected:
            case R.id.ivRRselected:
                if (selectMap.get(idMap.get(view.getId()))) {
                    tireMap.get(idMap.get(view.getId())).setAlpha(INVISIBLE);
                    selectMap.put(idMap.get(view.getId()), false);
                    countSelected--;
                } else {
                    if (exchangeTire.length() > 0)
                        clearExchange();

                    tireMap.get(idMap.get(view.getId())).setAlpha(VISIBLE);
                    selectMap.put(idMap.get(view.getId()), true);
                    countSelected++;
                }
                break;

            case R.id.ibDiagonalExchange:
            case R.id.ibRLRRexchange:
            case R.id.ibTLRLexchange:
            case R.id.ibTLTRexchange:
            case R.id.ibTRRRexchange:
                if (exchangeTire.length() == 0) {
                    view.setVisibility(View.INVISIBLE);
                    break;
                }

                if (etMap.containsKey(exchangeTire.substring(0, 2))
                        && etMap.containsKey(exchangeTire.substring(2, 4))) {
                    String from = etMap.get(exchangeTire.substring(0, 2)).getText().toString();
                    String to   = etMap.get(exchangeTire.substring(2, 4)).getText().toString();
                    etMap.get(exchangeTire.substring(2, 4)).setText(from);
                    etMap.get(exchangeTire.substring(0, 2)).setText(to);
                }

                clearExchange();
                break;
        }

        if (countSelected == 2) {
            clearSelected();
            countSelected = 0;
            exchangeTire = "";

            for (Map.Entry<String, Boolean> entry : selectMap.entrySet()) {
                if (entry.getValue()) {
                    entry.setValue(false);
                    exchangeTire = exchangeTire + entry.getKey();
                }
            }

            if (exchangeMap.containsKey(exchangeTire)
                    && ibExchangeMap.containsKey(exchangeTire)) {
                exchangeMap.get(exchangeTire).setVisibility(View.VISIBLE);
                ibExchangeMap.get(exchangeTire).setVisibility(View.VISIBLE);
            }
            else
                clearExchange();
        }

        if (bindType.length() > 0) {
            startService(new Intent(this, MainService.class)
                    .setAction(BindActivity.BIND_START));
        }
    }
}
