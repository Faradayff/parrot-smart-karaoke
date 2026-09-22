package me.farnasx.parrotkaraoke;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import me.farnasx.parrotkaraoke.util.Prefs;

/** Minimal settings screen (relay URL, Basic Auth, poll interval). */
public class SettingsActivity extends Activity {

    private EditText urlEt;
    private EditText userEt;
    private EditText passEt;
    private EditText pollEt;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        urlEt = (EditText) findViewById(R.id.setUrl);
        userEt = (EditText) findViewById(R.id.setUser);
        passEt = (EditText) findViewById(R.id.setPass);
        pollEt = (EditText) findViewById(R.id.setPoll);

        // android:inputType is an API 11 manifest attribute; set it programmatically
        // so this also works on 2.3.x (InputType API 1).
        passEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pollEt.setInputType(InputType.TYPE_CLASS_NUMBER);

        urlEt.setText(Prefs.get(this, Prefs.KEY_URL, Prefs.DEFAULT_URL));
        userEt.setText(Prefs.get(this, Prefs.KEY_USER, Prefs.DEFAULT_USER));
        passEt.setText(Prefs.get(this, Prefs.KEY_PASS, Prefs.DEFAULT_PASS));
        pollEt.setText(String.valueOf(Prefs.getInt(this, Prefs.KEY_POLL_MS, Prefs.DEFAULT_POLL_MS)));

        Button save = (Button) findViewById(R.id.btnSave);
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                save();
            }
        });
    }

    private void save() {
        String url = urlEt.getText().toString().trim();
        if (url.length() == 0) {
            url = Prefs.DEFAULT_URL;
        }

        int ms;
        try {
            ms = Integer.parseInt(pollEt.getText().toString().trim());
        } catch (Exception e) {
            ms = Prefs.DEFAULT_POLL_MS;
        }
        if (ms < 250) {
            ms = 250;
        }
        if (ms > 60000) {
            ms = 60000;
        }

        SharedPreferences.Editor ed = Prefs.sp(this).edit();
        ed.putString(Prefs.KEY_URL, url);
        ed.putString(Prefs.KEY_USER, userEt.getText().toString().trim());
        ed.putString(Prefs.KEY_PASS, passEt.getText().toString().trim());
        ed.putInt(Prefs.KEY_POLL_MS, ms);
        ed.commit();

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
        finish();
    }
}
