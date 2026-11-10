package com.grannev.vrhandtrack;

import android.os.Bundle;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("VR Handtrack — skeleton works");
        tv.setTextSize(24);
        setContentView(tv);
    }
}
