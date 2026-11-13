package com.grannev.vrhandtrack;

import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class VRActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("VR Mode (в разработке)");
        tv.setTextSize(24);
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundColor(Color.BLACK);
        setContentView(tv);
    }
}
