package com.grannev.vrhandtrack;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MenuActivity extends AppCompatActivity {

    private TextView summary;
    private SettingsStore settings;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        settings = new SettingsStore(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("VR меню");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        addView(root, title);

        summary = new TextView(this);
        summary.setGravity(Gravity.CENTER);
        addView(root, summary);

        Button start = new Button(this);
        start.setText("Запустить VR");
        start.setOnClickListener(v -> startActivity(new Intent(this, VRActivity.class)));
        addView(root, start);

        Button settingsBtn = new Button(this);
        settingsBtn.setText("Настройки");
        settingsBtn.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        addView(root, settingsBtn);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        String mode = settings.getMode() == SettingsStore.MODE_6DOF ? "6DoF" : "3DoF";
        summary.setText("FPS: " + settings.getFps()
                + "  •  Режим: " + mode
                + "  •  Плавность: " + settings.getSmoothing() + "%");
    }

    private void addView(LinearLayout parent, View child) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        parent.addView(child, lp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
