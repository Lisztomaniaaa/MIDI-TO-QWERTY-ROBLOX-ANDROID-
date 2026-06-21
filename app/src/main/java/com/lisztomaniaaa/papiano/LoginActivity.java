package com.lisztomaniaaa.papiano;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Login gate — intent gate, not security.
 *
 * Password: "papiano.fun" (case-insensitive).
 * Purpose: forces user to consciously start a MIDI bridge session.
 * Session persists (SharedPreferences flag) until Force Close resets it.
 *
 * Flow:
 *   LoginActivity → PermissionGateActivity → MainActivity (Home)
 *
 * If session is already active (user just switched apps, not force-closed),
 * LoginActivity auto-skips to PermissionGateActivity.
 */
public class LoginActivity extends Activity {

    private static final String PREFS = "data";
    private static final String KEY_SESSION_ACTIVE = "session_active";
    private static final String PASSWORD = "papiano.fun";

    private EditText etPassword;
    private TextView tvError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences sp = getSharedPreferences(PREFS, 0);

        // Skip login if session already active (user just switched apps)
        if (sp.getBoolean(KEY_SESSION_ACTIVE, false)) {
            navigateForward();
            return;
        }

        setContentView(R.layout.activity_login);

        etPassword = findViewById(R.id.et_password);
        tvError = findViewById(R.id.tv_error);
        Button btnLogin = findViewById(R.id.btn_login);

        btnLogin.setOnClickListener(v -> attemptLogin());

        // Handle keyboard "Done" action
        etPassword.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN)) {
                attemptLogin();
                return true;
            }
            return false;
        });
    }

    private void attemptLogin() {
        String input = etPassword.getText().toString().trim();

        if (input.isEmpty()) {
            showError("Enter the password to continue.");
            return;
        }

        if (input.equalsIgnoreCase(PASSWORD)) {
            // Mark session active
            getSharedPreferences(PREFS, 0)
                    .edit()
                    .putBoolean(KEY_SESSION_ACTIVE, true)
                    .apply();
            navigateForward();
        } else {
            showError("Incorrect password.");
            etPassword.setText("");
            etPassword.requestFocus();
        }
    }

    private void showError(String message) {
        tvError.setText(message);
        tvError.setVisibility(View.VISIBLE);
    }

    private void navigateForward() {
        startActivity(new Intent(this, PermissionGateActivity.class));
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Don't allow back — this is the entry point
        finishAffinity();
    }
}
