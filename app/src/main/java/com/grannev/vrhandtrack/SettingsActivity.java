package com.grannev.vrhandtrack;

import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    public static final String PREFS = "vr_prefs";
    public static final String KEY_FPS = "fps";
    public static final String KEY_DOF = "dof";
    public static final String KEY_SMOOTH = "smooth";

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);
        root.setPadding(50, 50, 50, 50);

        TextView title = new TextView(this);
        title.setText("Настройки VR");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 60);
        root.addView(title);

        TextView fpsLabel = new TextView(this);
        fpsLabel.setText("Частота кадров (FPS):");
        fpsLabel.setTextColor(Color.LTGRAY);
        fpsLabel.setTextSize(18);
        root.addView(fpsLabel);

        RadioGroup fpsGroup = new RadioGroup(this);
        fpsGroup.setOrientation(RadioGroup.HORIZONTAL);
        int[] fpsOptions = {0, 30, 60};
        String[] fpsNames = {"Авто", "30", "60"};
        for (int i = 0; i < fpsOptions.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(fpsNames[i]);
            rb.setTextColor(Color.WHITE);
            rb.setId(i);
            rb.setTag(fpsOptions[i]);
            fpsGroup.addView(rb);
            if (prefs.getInt(KEY_FPS, 0) == fpsOptions[i]) rb.setChecked(true);
        }
        fpsGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            prefs.edit().putInt(KEY_FPS, (int) rb.getTag()).apply();
        });
        root.addView(fpsGroup);

        TextView dofLabel = new TextView(this);
        dofLabel.setText("Режим трекинга головы:");
        dofLabel.setTextColor(Color.LTGRAY);
        dofLabel.setTextSize(18);
        dofLabel.setPadding(0, 40, 0, 0);
        root.addView(dofLabel);

        RadioGroup dofGroup = new RadioGroup(this);
        dofGroup.setOrientation(RadioGroup.HORIZONTAL);
        String[] dofNames = {"3DoF (Гироскоп)", "6DoF (ARCore)"};
        String[] dofValues = {"3dof", "6dof"};
        for (int i = 0; i < dofNames.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(dofNames[i]);
            rb.setTextColor(Color.WHITE);
            rb.setId(i);
            rb.setTag(dofValues[i]);
            dofGroup.addView(rb);
            if (prefs.getString(KEY_DOF, "3dof").equals(dofValues[i])) rb.setChecked(true);
            if (i == 1) rb.setEnabled(false);
        }
        dofGroup.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton rb = group.findViewById(checkedId);
            prefs.edit().putString(KEY_DOF, (String) rb.getTag()).apply();
        });
        root.addView(dofGroup);

        TextView smoothLabel = new TextView(this);
        smoothLabel.setText("Сглаживание рук (0-10):");
        smoothLabel.setTextColor(Color.LTGRAY);
        smoothLabel.setTextSize(18);
        smoothLabel.setPadding(0, 40, 0, 0);
        root.addView(smoothLabel);

        android.widget.SeekBar smoothBar = new android.widget.SeekBar(this);
        smoothBar.setMax(10);
        smoothBar.setProgress(prefs.getInt(KEY_SMOOTH, 6));
        smoothBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(KEY_SMOOTH, progress).apply();
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });
        root.addView(smoothBar);

        Button back = new Button(this);
        back.setText("НАЗАД");
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = 60;
        root.addView(back, lp);

        setContentView(root);
    }
}
