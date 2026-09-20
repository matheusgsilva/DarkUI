package com.matheus.darkui.generatedpack;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class PackActivity extends Activity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("DarkUI Generated icon pack. Apply it through Samsung Theme Park.");
        text.setTextSize(18f);
        int p = (int) (24 * getResources().getDisplayMetrics().density);
        text.setPadding(p, p, p, p);
        setContentView(text);
    }
}
