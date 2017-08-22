package com.longpaws.eegapp;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.androidplot.Plot;
import com.androidplot.util.Redrawer;
import com.androidplot.xy.BoundaryMode;
import com.androidplot.xy.CatmullRomInterpolator;
import com.androidplot.xy.FastLineAndPointRenderer;
import com.androidplot.xy.LineAndPointFormatter;
import com.androidplot.xy.SimpleXYSeries;
import com.androidplot.xy.StepMode;
import com.androidplot.xy.XYGraphWidget;
import com.androidplot.xy.XYPlot;
import com.neurosky.thinkgear.TGDevice;

import java.text.DecimalFormat;
import java.util.Arrays;

import static java.lang.Math.abs;

public class MainActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener, Tone.OnPlaybackChangedListener {
    TGDevice tgDevice;
    BluetoothAdapter btAdapter;
    TextView data;
    TextView dataAttention;
    TextView dataMeditation;
    Button soundControlButton;
    Button b_reconnect;
    TextView devStatus;

    private SeekBar seekVolume;
    private Spinner spinnerWaveTypes;
    private ChronometerEx chronometer;

    private double alpha = 0.98;
    private double rawLast = 1;

    private static final int HISTORY_SIZE = 500;
    private XYPlot aprHistoryPlot = null;
    private SimpleXYSeries rawSeries;

    private Redrawer redrawer;

    private Tone mTone;
    private AudioManager mAudioManager;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // set text view
        data = (TextView) findViewById(R.id.signal);
        dataAttention = (TextView) findViewById(R.id.signal_attention);
        dataMeditation = (TextView) findViewById(R.id.signal_meditation);
        Log.v("HelloEEG", "Created layout. Started onCreate");

        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if(btAdapter != null) {
            tgDevice = new TGDevice(btAdapter, handler);
            Log.v("HelloEEG", "CREATED TGDevice");
        }

        tgDevice.connect(true);
        tgDevice.start();

        initViews();

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        seekVolume.setOnSeekBarChangeListener(this);
        seekVolume.setMax(mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        spinnerWaveTypes.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, Tone.Type.names()));

        seekVolume.setProgress(seekVolume.getMax() / 3);
        mTone = new Tone(Tone.Type.SINE, 445, this);

        rawSeries = new SimpleXYSeries("R");
        rawSeries.useImplicitXVals();

        // setup the APR History plot:
        aprHistoryPlot = (XYPlot) findViewById(R.id.plot);


        LineAndPointFormatter rawSeriesFormat =
                new FastLineAndPointRenderer.Formatter(Color.parseColor("#ffc200"), null, null);
        rawSeriesFormat.getLinePaint().setAntiAlias(false);

        aprHistoryPlot.setRangeBoundaries(-820, 820, BoundaryMode.FIXED);
        aprHistoryPlot.setDomainBoundaries(0, HISTORY_SIZE, BoundaryMode.FIXED);
        aprHistoryPlot.addSeries(rawSeries, rawSeriesFormat);
        aprHistoryPlot.setDomainStepMode(StepMode.INCREMENT_BY_VAL);
        aprHistoryPlot.setDomainStepValue(100);
        aprHistoryPlot.setLinesPerRangeLabel(3);
        aprHistoryPlot.setDomainLabel("Обозначения");
        aprHistoryPlot.getDomainTitle().pack();
        aprHistoryPlot.setRangeLabel("Амплитуда");

        aprHistoryPlot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.LEFT).
                setFormat(new DecimalFormat("#"));

        aprHistoryPlot.getGraph().getLineLabelStyle(XYGraphWidget.Edge.BOTTOM).
                setFormat(new DecimalFormat("#"));

        redrawer = new Redrawer(
                Arrays.asList(new Plot[]{aprHistoryPlot}),
                20, false);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        return super.onOptionsItemSelected(item);
    }

    private final Handler handler = new Handler() {
        int attention;
        int meditation;
        double raw;
        float prev;
        Boolean once = false;

        @Override
        public void handleMessage(Message msg) {

            switch (msg.what) {
                case TGDevice.MSG_STATE_CHANGE:
                    switch (msg.arg1) {
                        case TGDevice.STATE_IDLE:
                            Log.v("HelloEEG", "IDLE STATE");
                            break;
                        case TGDevice.STATE_CONNECTING:
                            Log.v("HelloEEG", "CONNECTING...");
                            devStatus.setText("Подключение...");
                            break;
                        case TGDevice.STATE_CONNECTED:
                            Log.v("HelloEEG", "CONNECTED");
                            tgDevice.start();
                            devStatus.setText("Подключено");
                            break;
                        case TGDevice.STATE_DISCONNECTED:
                            Log.v("HelloEEG", "DISCONNECTED");
                            mTone.pause();
                            soundControlButton.setText("Включить звук");
                            soundStatus = false;
                            devStatus.setText("Не подключено");
                            chronometer.setStarted(false);
                            once = false;
                            break;
                        case TGDevice.STATE_NOT_FOUND:
                            Log.v("HelloEEG", "STATE NOT FOUND");
                            break;
                        case TGDevice.STATE_NOT_PAIRED:
                            Log.v("HelloEEG", "STATE NOT PAIRED");
                            break;
                        default:
                            break;
                    }
                    break;
                case TGDevice.MSG_POOR_SIGNAL:
                    data.setText("Signal: " + String.valueOf(msg.arg1));
                    break;
                case TGDevice.MSG_ATTENTION:
                    dataAttention.setText("Attention: " + String.valueOf(msg.arg1));
                    attention = msg.arg1;
                    break;
                case TGDevice.MSG_MEDITATION:
                    dataMeditation.setText("Meditation: " + String.valueOf(msg.arg1));
                    meditation = msg.arg1;
                case TGDevice.MSG_RAW_DATA:
                    data.setText("Signal: " + String.valueOf(msg.arg1));
                    raw = msg.arg1;
                    if (!once) {
                        chronometer.setStarted(true);
                        once = true;
                    }
                    break;
                case TGDevice.MSG_EEG_POWER:
                    // TGEegPower ep = (TGEegPower)msg.arg1;
                    // Log.v("HelloEEG", "Delta: " + ep.delta);
                    break;
                default:
                    break;
            }

            raw = alpha * rawLast + (1 - alpha) * raw;
            rawLast = raw;

            if (rawSeries.size() > HISTORY_SIZE)
                rawSeries.removeFirst();

            rawSeries.addLast(null, raw);
            
            float delta = SystemClock.elapsedRealtime() - prev;
            if (delta/100 >= 1.000) {

                Log.v("jarobNeuro", "Att: " + attention + "\t\tMed: " + meditation + "\t\tRaw: " + raw);
                mTone.changeTo(Tone.Type.values[spinnerWaveTypes.getSelectedItemPosition()], raw*4 + 1200);
                prev = SystemClock.elapsedRealtime();
            }
        }
    };

    @Override
    public void onResume() {
        super.onResume();
        redrawer.start();
    }

    @Override
    protected void onPause () {
        super.onPause();
        redrawer.pause();
    }

    @Override
    public void onDestroy() {
        redrawer.finish();
        super.onDestroy();
    }

    boolean soundStatus;
    public void genStart(View v){
        soundStatus = !soundStatus;
        if (soundStatus)    {
            soundControlButton.setText("Выключить звук");
            mTone.play();
        } else {
            soundControlButton.setText("Включить звук");
            mTone.pause();
        }
    }

    public void reconnect(View v){
        tgDevice = null;
        if(btAdapter != null) {
            tgDevice = new TGDevice(btAdapter, handler);
            Log.v("HelloEEG", "CREATED TGDevice");
            if (tgDevice.getState() != TGDevice.STATE_CONNECTING && tgDevice.getState() != TGDevice.STATE_CONNECTED) {
                tgDevice.connect(true);
                tgDevice.start();
                b_reconnect.isClickable();
            }
        }
    }

    @Override
    public void onPlaybackChanged (boolean isPlaying) {
        spinnerWaveTypes.setEnabled(!isPlaying);
    }

    @Override
    public void onProgressChanged (SeekBar seekBar, int progress, boolean fromUser) {
        switch (seekBar.getId())
        {
            case R.id.seekVolume:
                mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0);
                break;
        }
    }

    @Override
    public void onStartTrackingTouch (SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch (SeekBar seekBar) {

    }

    private void initViews () {
        seekVolume = (SeekBar) findViewById(R.id.seekVolume);
        soundControlButton = (Button) findViewById(R.id.soundControlButton);
        b_reconnect = (Button) findViewById(R.id.b_reconnect);
        spinnerWaveTypes = (Spinner) findViewById(R.id.spinnerWaveTypes);
        chronometer = (ChronometerEx) findViewById(R.id.chronometer);
        devStatus = (TextView) findViewById(R.id.devStatus);
    }
}
