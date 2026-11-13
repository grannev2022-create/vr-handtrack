package com.grannev.vrhandtrack;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MenuActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.BLACK);

        TextView title = new TextView(this);
        title.setText("VR Handtrack");
        title.setTextSize(32);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 80);
        root.addView(title);

        Button btnStart = new Button(this);
        btnStart.setText("ЗАПУСТИТЬ");
        btnStart.setTextSize(24);
        btnStart.setPadding(50, 20, 50, 20);
        btnStart.setOnClickListener(v -> startActivity(new Intent(this, HandSkeletonActivity.class)));
        root.addView(btnStart);

        setContentView(root);
    }
}
