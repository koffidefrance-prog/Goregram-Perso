package org.telegram.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GoregramInviteActivity extends Activity {
    private static final String API = "https://goregram-api-production.up.railway.app";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String inviteToken;
    private String invitedPhone;
    private EditText nameInput;
    private EditText passwordInput;
    private Button createButton;
    private ProgressBar progress;
    private TextView status;
    private TextView phoneLabel;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        inviteToken = getIntent() != null && getIntent().getData() != null
                ? getIntent().getData().getQueryParameter("token") : null;
        if (inviteToken == null && getIntent() != null) {
            Uri data = getIntent().getData();
            if (data != null) inviteToken = data.getQueryParameter("token");
        }
        buildScreen();
        if (inviteToken == null || !inviteToken.matches("[A-Za-z0-9_-]{32,256}")) {
            showStatus("Lien d’invitation incomplet ou incorrect. Demandez un nouveau lien.", true);
            createButton.setEnabled(false);
            return;
        }
        validateInvitation();
    }

    private void buildScreen() {
        getWindow().setStatusBarColor(Color.rgb(6, 26, 20));
        getWindow().setNavigationBarColor(Color.rgb(6, 26, 20));
        getWindow().getDecorView().setSystemUiVisibility(0);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(6, 26, 20));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_HORIZONTAL);
        page.setPadding(dp(26), dp(42), dp(26), dp(32));
        scroll.addView(page);

        TextView logo = new TextView(this);
        logo.setText("✦");
        logo.setTextSize(44);
        logo.setTextColor(Color.rgb(242, 201, 76));
        logo.setGravity(Gravity.CENTER);
        page.addView(logo, matchWrap());

        TextView title = new TextView(this);
        title.setText("Rejoindre Goregram");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = matchWrap();
        titleParams.topMargin = dp(10);
        page.addView(title, titleParams);

        TextView intro = new TextView(this);
        intro.setText("Créez votre compte avec le numéro associé à votre invitation.");
        intro.setTextColor(Color.rgb(205, 224, 214));
        intro.setTextSize(15);
        intro.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams introParams = matchWrap();
        introParams.topMargin = dp(10);
        introParams.bottomMargin = dp(28);
        page.addView(intro, introParams);

        phoneLabel = new TextView(this);
        phoneLabel.setText("Vérification de votre invitation…");
        phoneLabel.setTextColor(Color.rgb(242, 201, 76));
        phoneLabel.setTextSize(16);
        phoneLabel.setGravity(Gravity.CENTER);
        page.addView(phoneLabel, matchWrap());

        nameInput = field("Votre nom ou pseudonyme");
        LinearLayout.LayoutParams fieldParams = matchWrap();
        fieldParams.topMargin = dp(22);
        page.addView(nameInput, fieldParams);

        passwordInput = field("Mot de passe (10 caractères minimum)");
        passwordInput.setInputType(129);
        LinearLayout.LayoutParams passwordParams = matchWrap();
        passwordParams.topMargin = dp(12);
        page.addView(passwordInput, passwordParams);

        createButton = new Button(this);
        createButton.setText("Créer mon compte Goregram");
        createButton.setTextColor(Color.rgb(6, 26, 20));
        createButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242, 201, 76)));
        createButton.setOnClickListener(v -> registerAccount());
        LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(22);
        page.addView(createButton, buttonParams);

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(42), dp(42));
        progressParams.topMargin = dp(18);
        page.addView(progress, progressParams);

        status = new TextView(this);
        status.setTextColor(Color.rgb(205, 224, 214));
        status.setTextSize(14);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(18);
        page.addView(status, statusParams);

        setContentView(scroll);
        createButton.setEnabled(false);
    }

    private EditText field(String hint) {
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextSize(16);
        edit.setHint(hint);
        edit.setHintTextColor(Color.rgb(140, 166, 151));
        edit.setTextColor(Color.WHITE);
        edit.setPadding(dp(16), dp(12), dp(16), dp(12));
        edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(90, 130, 106)));
        return edit;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void validateInvitation() {
        setBusy(true);
        executor.execute(() -> {
            try {
                JSONObject response = request("GET", "/api/invitations/validate/" + inviteToken, null);
                if (!response.optBoolean("valid", false)) throw new Exception("Cette invitation n’est plus valide.");
                invitedPhone = response.getString("phone");
                String inviter = response.optString("inviterName", "votre contact");
                runOnUiThread(() -> {
                    phoneLabel.setText("Invitation de " + inviter + "\nNuméro : " + invitedPhone);
                    showStatus("Invitation valide. Vous pouvez créer votre compte.", false);
                    setBusy(false);
                    createButton.setEnabled(true);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    showStatus(e.getMessage() == null ? "Impossible de vérifier le lien. Réessayez avec une connexion Internet." : e.getMessage(), true);
                    setBusy(false);
                    createButton.setEnabled(false);
                });
            }
        });
    }

    private void registerAccount() {
        String displayName = nameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (displayName.length() < 2 || displayName.length() > 80) {
            showStatus("Le nom doit contenir entre 2 et 80 caractères.", true);
            return;
        }
        if (password.length() < 10 || password.length() > 128) {
            showStatus("Le mot de passe doit contenir entre 10 et 128 caractères.", true);
            return;
        }
        setBusy(true);
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(passwordInput.getWindowToken(), 0);
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("phone", invitedPhone);
                body.put("displayName", displayName);
                body.put("password", password);
                body.put("inviteToken", inviteToken);
                JSONObject response = request("POST", "/api/auth/register", body);
                String token = response.getString("token");
                JSONObject user = response.optJSONObject("user");
                String userId = user == null ? "" : user.optString("id", "");
                getSharedPreferences("goregram_account", MODE_PRIVATE).edit()
                        .putString("access_token", token)
                        .putString("user_id", userId)
                        .putString("phone", invitedPhone)
                        .putString("display_name", displayName)
                        .apply();
                runOnUiThread(() -> {
                    setBusy(false);
                    createButton.setEnabled(false);
                    nameInput.setEnabled(false);
                    passwordInput.setEnabled(false);
                    showStatus("Votre compte Goregram a été créé. Vous pouvez maintenant ouvrir vos discussions.", false);
                    createButton.setText("Ouvrir Goregram");
                    createButton.setEnabled(true);
                    createButton.setOnClickListener(v -> {
                        startActivity(new android.content.Intent(GoregramInviteActivity.this, GoregramActivity.class));
                        finish();
                    });
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showStatus(e.getMessage() == null ? "La création du compte a échoué. Réessayez." : e.getMessage(), true);
                });
            }
        });
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder text = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) text.append(line);
            }
        }
        connection.disconnect();
        JSONObject result = text.length() == 0 ? new JSONObject() : new JSONObject(text.toString());
        if (code < 200 || code >= 300) {
            throw new Exception(result.optString("error", "Erreur serveur HTTP " + code));
        }
        return result;
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        createButton.setEnabled(!busy && invitedPhone != null);
        nameInput.setEnabled(!busy);
        passwordInput.setEnabled(!busy);
    }

    private void showStatus(String message, boolean error) {
        status.setText(message);
        status.setTextColor(error ? Color.rgb(255, 145, 145) : Color.rgb(205, 224, 214));
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
