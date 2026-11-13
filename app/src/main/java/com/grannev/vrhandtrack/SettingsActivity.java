package com.grannev.vrhandtrack;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SettingsStore store = new SettingsStore(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        root.setPadding(pad, pad, pad, pad);

        SwitchCompat xr = new SwitchCompat(this);
        xr.setText("Режим XR: камера телефона в VR");
        xr.setTextSize(18);
        xr.setChecked(store.isXrCamera());
        xr.setOnCheckedChangeListener((b, checked) -> store.setXrCamera(checked));
        root.addView(xr);

        root.addView(label("Частота кадров (FPS)"));
        Spinner fpsSpinner = new Spinner(this);
        String[] fpsLabels = new String[SettingsStore.FPS_OPTIONS.length];
        int selected = 0;
        for (int i = 0; i < fpsLabels.length; i++) {
            fpsLabels[i] = SettingsStore.FPS_OPTIONS[i] + " FPS";
            if (SettingsStore.FPS_OPTIONS[i] == store.getFps()) selected = i;
        }
        fpsSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, fpsLabels));
        fpsSpinner.setSelection(selected);
        fpsSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) { store.setFps(SettingsStore.FPS_OPTIONS[pos]); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        root.addView(fpsSpinner);

        root.addView(label("Режим отслеживания"));
        RadioGroup group = new RadioGroup(this);
        RadioButton r3 = new RadioButton(this); r3.setId(View.generateViewId()); r3.setText("3DoF (только повороты)");
        RadioButton r6 = new RadioButton(this); r6.setId(View.generateViewId()); r6.setText("6DoF (повороты + перемещение, ARCore)");
        group.addView(r3); group.addView(r6);
        group.check(store.getMode() == SettingsStore.MODE_6DOF ? r6.getId() : r3.getId());
        group.setOnCheckedChangeListener((g, id) -> store.setMode(id == r6.getId() ? SettingsStore.MODE_6DOF : SettingsStore.MODE_3DOF));
        root.addView(group);

        TextView smoothLabel = label("Плавность: " + store.getSmoothing() + "%");
        root.addView(smoothLabel);
        SeekBar seek = new SeekBar(this);
        seek.setMax(100); seek.setProgress(store.getSmoothing());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { smoothLabel.setText("Плавность: " + progress + "%"); store.setSmoothing(progress); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        root.addView(seek);

        setContentView(root);
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text); tv.setTextSize(18); tv.setPadding(0, dp(20), 0, dp(6));
        return tv;
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
