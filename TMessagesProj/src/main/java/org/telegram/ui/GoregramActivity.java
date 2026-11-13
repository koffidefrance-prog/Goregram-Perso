package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
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

public class GoregramActivity extends Activity {
    private static final String API = "https://goregram-api-production.up.railway.app";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private String accessToken;
    private String myName;
    private LinearLayout root;
    private LinearLayout content;
    private TextView notice;
    private EditText phoneField;
    private EditText passwordField;
    private EditText messageField;
    private long currentConversationId = -1;
    private String currentTitle = "Conversation";
    private ScrollView messageScroll;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(6, 26, 20));
        getWindow().setNavigationBarColor(Color.rgb(6, 26, 20));
        accessToken = getSharedPreferences("goregram_account", MODE_PRIVATE).getString("access_token", null);
        myName = getSharedPreferences("goregram_account", MODE_PRIVATE).getString("display_name", "Goregram");
        showShell();
        if (accessToken == null || accessToken.isEmpty()) showLogin();
        else loadConversations();
    }

    private void showShell() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(6, 26, 20));
        root.setPadding(dp(18), dp(22), dp(18), dp(18));

        TextView brand = new TextView(this);
        brand.setText("✦  GOREGRAM");
        brand.setTextColor(Color.rgb(242, 201, 76));
        brand.setTextSize(23);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(brand, matchWrap());

        notice = new TextView(this);
        notice.setTextColor(Color.rgb(255, 150, 150));
        notice.setTextSize(14);
        LinearLayout.LayoutParams noteParams = matchWrap();
        noteParams.topMargin = dp(8);
        root.addView(notice, noteParams);

        ScrollView pageScroll = new ScrollView(this);
        pageScroll.setFillViewport(true);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(-1, 0, 1);
        scrollParams.topMargin = dp(12);
        root.addView(pageScroll, scrollParams);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        pageScroll.addView(content);
        setContentView(root);
    }

    private void showLogin() {
        content.removeAllViews();
        title("Connexion à Goregram");
        phoneField = field("Numéro international, ex. +2250700000000");
        content.addView(phoneField, spaced());
        passwordField = field("Mot de passe");
        passwordField.setInputType(129);
        content.addView(passwordField, spaced());
        Button login = button("Se connecter");
        content.addView(login, spaced());
        login.setOnClickListener(v -> {
            String phone = phoneField.getText().toString().trim();
            String password = passwordField.getText().toString();
            if (!phone.matches("^\\+[1-9][0-9]{7,14}$") || password.isEmpty()) {
                showNotice("Saisissez un numéro au format international et votre mot de passe.");
                return;
            }
            busy(login, true);
            executor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("phone", phone);
                    body.put("password", password);
                    JSONObject result = request("POST", "/api/auth/login", body, false);
                    saveSession(result, phone);
                    runOnUiThread(() -> { busy(login, false); loadConversations(); });
                } catch (Exception e) {
                    runOnUiThread(() -> { busy(login, false); showNotice(message(e)); });
                }
            });
        });
    }

    private void loadConversations() {
        content.removeAllViews();
        title("Bonjour " + myName);
        TextView sub = text("Vos discussions Goregram", 14, Color.rgb(205, 224, 214));
        content.addView(sub, spaced());
        Button telegramDiagnostic = button("Assistant de diagnostic Telegram");
        content.addView(telegramDiagnostic, spaced());
        telegramDiagnostic.setOnClickListener(v -> showTelegramDiagnostic());

        LinearLayout newRow = new LinearLayout(this);
        newRow.setOrientation(LinearLayout.VERTICAL);
        newRow.setPadding(dp(14), dp(14), dp(14), dp(14));
        newRow.setBackgroundColor(Color.rgb(13, 43, 32));
        TextView newTitle = text("Nouvelle discussion", 17, Color.rgb(242, 201, 76));
        newTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        newRow.addView(newTitle, matchWrap());
        EditText target = field("Numéro du contact, ex. +2250700000000");
        LinearLayout.LayoutParams targetParams = matchWrap();
        targetParams.topMargin = dp(10);
        newRow.addView(target, targetParams);
        Button start = button("Rechercher et discuter");
        LinearLayout.LayoutParams startParams = matchWrap();
        startParams.topMargin = dp(8);
        newRow.addView(start, startParams);
        content.addView(newRow, spaced());
        start.setOnClickListener(v -> startConversation(target.getText().toString().trim(), start));

        Button refresh = button("Actualiser les discussions");
        content.addView(refresh, spaced());
        refresh.setOnClickListener(v -> loadConversations());

        TextView listTitle = text("Discussions récentes", 18, Color.WHITE);
        listTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(listTitle, spaced());
        executor.execute(() -> {
            try {
                JSONObject result = request("GET", "/api/conversations", null, true);
                JSONArray conversations = result.optJSONArray("conversations");
                runOnUiThread(() -> renderConversations(conversations));
            } catch (Exception e) {
                runOnUiThread(() -> showNotice(message(e)));
            }
        });
    }

    private void showTelegramDiagnostic() {
        content.removeAllViews();
        title("Diagnostic Telegram");
        TextView intro = text(
                "Décris le message affiché par Telegram. Le diagnostic fonctionne localement sur ton téléphone et n'envoie pas tes messages à un serveur.",
                14, Color.rgb(205, 224, 214));
        content.addView(intro, spaced());

        EditText errorField = field("Colle le message ou le code d'erreur Telegram");
        errorField.setSingleLine(false);
        errorField.setMinLines(3);
        content.addView(errorField, spaced());

        TextView diagnosis = text("Le résultat apparaîtra ici.", 15, Color.WHITE);
        diagnosis.setPadding(dp(14), dp(14), dp(14), dp(14));
        diagnosis.setBackgroundColor(Color.rgb(13, 43, 32));
        content.addView(diagnosis, spaced());

        Button analyze = button("Analyser le blocage");
        content.addView(analyze, spaced());
        analyze.setOnClickListener(v -> {
            String raw = errorField.getText().toString().trim();
            String value = raw.toLowerCase(java.util.Locale.ROOT);
            if (value.isEmpty()) {
                diagnosis.setText("Colle d'abord le texte exact affiché par Telegram.");
            } else if (value.contains("contacts communs") || value.contains("mutual contacts")
                    || value.contains("only send messages to") || value.contains("can't message non-contacts")) {
                diagnosis.setText("Restriction des nouveaux messages : Telegram limite ce compte aux personnes qui ont enregistré son numéro ou qui lui écrivent en premier. Goregram ne peut pas supprimer cette règle côté serveur. Essaie le recours officiel avec @SpamBot et évite les tentatives répétées.");
            } else if (value.contains("peer_flood") || value.contains("flood_wait") || value.contains("too many requests")) {
                diagnosis.setText("Limitation temporaire anti-abus ou de fréquence. Arrête les tentatives répétées et respecte le délai indiqué par Telegram. Une application tierce ne peut pas lever ce délai.");
            } else if (value.contains("user_restricted") || value.contains("chat_write_forbidden")
                    || value.contains("write forbidden") || value.contains("not enough rights")) {
                diagnosis.setText("L'envoi est interdit dans cette conversation ou pour ce compte. Vérifie les permissions du groupe ou l'état du compte, puis utilise le recours officiel si tu penses qu'il s'agit d'une erreur.");
            } else if (value.contains("phone_number_banned") || value.contains("banned")) {
                diagnosis.setText("Telegram signale un compte ou un numéro bloqué. Il faut demander une vérification au support Telegram. Ne réinstalle pas l'application en boucle et ne partage aucun code de connexion.");
            } else {
                diagnosis.setText("Erreur non reconnue. Garde le texte exact et demande une vérification via l'aide officielle Telegram. Ce diagnostic ne modifie pas les restrictions du compte.");
            }
        });

        Button appeal = button("Ouvrir le bot officiel @SpamBot");
        content.addView(appeal, spaced());
        appeal.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://t.me/SpamBot")));
            } catch (Exception e) {
                showNotice("Impossible d'ouvrir Telegram. Vérifie que l'application est installée.");
            }
        });

        Button back = button("Retour aux discussions");
        content.addView(back, spaced());
        back.setOnClickListener(v -> loadConversations());
    }

    private void renderConversations(JSONArray rows) {
        if (rows == null || rows.length() == 0) {
            content.addView(text("Aucune discussion pour le moment. Recherchez un contact Goregram avec son numéro.", 14, Color.rgb(205, 224, 214)), spaced());
            return;
        }
        for (int i = 0; i < rows.length(); i++) {
            JSONObject item = rows.optJSONObject(i);
            if (item == null) continue;
            long id = item.optLong("conversation_id", -1);
            String name = item.optString("peer_name", item.optString("peer_phone", "Contact Goregram"));
            String last = item.optString("last_message", "Démarrer la conversation");
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setBackgroundColor(Color.rgb(13, 43, 32));
            TextView heading = text(name, 16, Color.WHITE);
            heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(heading, matchWrap());
            card.addView(text(last, 14, Color.rgb(185, 207, 193)), matchWrap());
            content.addView(card, spaced());
            card.setOnClickListener(v -> openConversation(id, name));
        }
    }

    private void startConversation(String phone, Button source) {
        if (!phone.matches("^\\+[1-9][0-9]{7,14}$")) {
            showNotice("Le numéro doit être au format international, par exemple +2250700000000.");
            return;
        }
        busy(source, true);
        executor.execute(() -> {
            try {
                JSONObject lookupBody = new JSONObject();
                lookupBody.put("phone", phone);
                JSONObject lookup = request("POST", "/api/contacts/lookup", lookupBody, true);
                JSONObject contact = lookup.optJSONObject("contact");
                if (contact == null) {
                    JSONObject invitation = request("POST", "/api/invitations", lookupBody, true);
                    String link = invitation.optString("inviteLink", "");
                    if (link.isEmpty()) throw new Exception("Le lien d'invitation n'a pas pu être généré.");
                    runOnUiThread(() -> {
                        busy(source, false);
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType("text/plain");
                        share.putExtra(Intent.EXTRA_SUBJECT, "Invitation Goregram");
                        share.putExtra(Intent.EXTRA_TEXT, "Je t'invite à rejoindre Goregram pour discuter avec moi : " + link);
                        startActivity(Intent.createChooser(share, "Partager l’invitation Goregram"));
                        showNotice("Lien d'invitation prêt. Partagez-le par WhatsApp, SMS ou une autre application.");
                    });
                    return;
                }
                JSONObject directBody = new JSONObject();
                directBody.put("userId", contact.getLong("id"));
                JSONObject created = request("POST", "/api/conversations/direct", directBody, true);
                long id = created.getLong("conversationId");
                String name = contact.optString("display_name", phone);
                runOnUiThread(() -> { busy(source, false); openConversation(id, name); });
            } catch (Exception e) {
                runOnUiThread(() -> { busy(source, false); showNotice(message(e)); });
            }
        });
    }

    private void openConversation(long id, String title) {
        currentConversationId = id;
        currentTitle = title;
        content.removeAllViews();
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        Button back = button("‹ Discussions");
        header.addView(back, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView name = text(title, 17, Color.rgb(242, 201, 76));
        name.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(name, new LinearLayout.LayoutParams(0, dp(48), 2));
        Button refresh = button("↻");
        header.addView(refresh, new LinearLayout.LayoutParams(dp(55), dp(48)));
        content.addView(header, matchWrap());
        back.setOnClickListener(v -> loadConversations());
        refresh.setOnClickListener(v -> loadMessages());

        messageScroll = new ScrollView(this);
        messageScroll.setFillViewport(true);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(-1, dp(0), 1);
        messageParams.topMargin = dp(10);
        messageParams.bottomMargin = dp(10);
        content.addView(messageScroll, messageParams);
        LinearLayout messageList = new LinearLayout(this);
        messageList.setOrientation(LinearLayout.VERTICAL);
        messageList.setPadding(dp(6), dp(8), dp(6), dp(8));
        messageScroll.addView(messageList);

        LinearLayout compose = new LinearLayout(this);
        compose.setOrientation(LinearLayout.HORIZONTAL);
        messageField = field("Écrire un message…");
        compose.addView(messageField, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button send = button("Envoyer");
        compose.addView(send, new LinearLayout.LayoutParams(dp(100), dp(52)));
        content.addView(compose, matchWrap());
        send.setOnClickListener(v -> sendMessage(send));
        loadMessagesInto(messageList);
    }

    private void loadMessages() {
        if (currentConversationId < 1) return;
        LinearLayout list = (LinearLayout) messageScroll.getChildAt(0);
        loadMessagesInto(list);
    }

    private void loadMessagesInto(LinearLayout list) {
        long conversationId = currentConversationId;
        executor.execute(() -> {
            try {
                JSONObject result = request("GET", "/api/conversations/" + conversationId + "/messages", null, true);
                JSONArray messages = result.optJSONArray("messages");
                runOnUiThread(() -> {
                    if (conversationId != currentConversationId) return;
                    list.removeAllViews();
                    if (messages == null || messages.length() == 0) {
                        list.addView(text("Dites bonjour pour commencer.", 14, Color.rgb(205, 224, 214)), spaced());
                    } else {
                        for (int i = 0; i < messages.length(); i++) {
                            JSONObject m = messages.optJSONObject(i);
                            if (m == null) continue;
                            boolean mine = String.valueOf(m.opt("sender_id")).equals(getSharedPreferences("goregram_account", MODE_PRIVATE).getString("user_id", ""));
                            TextView bubble = text(m.optString("body"), 15, mine ? Color.rgb(6, 26, 20) : Color.WHITE);
                            bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
                            bubble.setBackgroundColor(mine ? Color.rgb(242, 201, 76) : Color.rgb(24, 66, 48));
                            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
                            p.topMargin = dp(5);
                            p.bottomMargin = dp(5);
                            p.gravity = mine ? Gravity.RIGHT : Gravity.LEFT;
                            list.addView(bubble, p);
                        }
                    }
                    messageScroll.post(() -> messageScroll.fullScroll(View.FOCUS_DOWN));
                });
            } catch (Exception e) {
                runOnUiThread(() -> showNotice(message(e)));
            }
        });
    }

    private void sendMessage(Button send) {
        String bodyText = messageField.getText().toString().trim();
        if (bodyText.isEmpty()) return;
        long conversationId = currentConversationId;
        busy(send, true);
        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("body", bodyText);
                request("POST", "/api/conversations/" + conversationId + "/messages", body, true);
                runOnUiThread(() -> {
                    busy(send, false);
                    messageField.setText("");
                    loadMessages();
                });
            } catch (Exception e) {
                runOnUiThread(() -> { busy(send, false); showNotice(message(e)); });
            }
        });
    }

    private void saveSession(JSONObject result, String fallbackPhone) throws Exception {
        String token = result.getString("token");
        JSONObject user = result.optJSONObject("user");
        String name = user == null ? "Goregram" : user.optString("display_name", "Goregram");
        String phone = user == null ? fallbackPhone : user.optString("phone_e164", fallbackPhone);
        String id = user == null ? "" : user.optString("id", "");
        getSharedPreferences("goregram_account", MODE_PRIVATE).edit()
                .putString("access_token", token).putString("display_name", name)
                .putString("phone", phone).putString("user_id", id).apply();
        accessToken = token;
        myName = name;
    }

    private JSONObject request(String method, String path, JSONObject body, boolean auth) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        if (auth) connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        StringBuilder raw = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) raw.append(line);
            }
        }
        connection.disconnect();
        JSONObject result = raw.length() == 0 ? new JSONObject() : new JSONObject(raw.toString());
        if (code < 200 || code >= 300) throw new Exception(result.optString("error", "Erreur serveur HTTP " + code));
        return result;
    }

    private void title(String value) {
        TextView t = text(value, 22, Color.WHITE);
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(18);
        p.bottomMargin = dp(4);
        content.addView(t, p);
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setHint(hint);
        e.setHintTextColor(Color.rgb(140, 166, 151));
        e.setTextColor(Color.WHITE);
        e.setPadding(dp(13), dp(8), dp(13), dp(8));
        e.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(90, 130, 106)));
        return e;
    }

    private Button button(String value) {
        Button b = new Button(this);
        b.setText(value);
        b.setTextColor(Color.rgb(6, 26, 20));
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.rgb(242, 201, 76)));
        return b;
    }

    private TextView text(String value, int size, int color) {
        TextView t = new TextView(this);
        t.setText(value == null ? "" : value);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams p = matchWrap();
        p.topMargin = dp(10);
        return p;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void showNotice(String value) {
        notice.setText(value == null ? "Une erreur est survenue." : value);
    }

    private String message(Exception e) {
        return e.getMessage() == null ? "Impossible de contacter le serveur Goregram." : e.getMessage();
    }

    private void busy(Button b, boolean value) {
        if (value) {
            if (b.getTag() == null) b.setTag(b.getText());
            b.setText("Patientez…");
        } else if (b.getTag() != null) {
            b.setText(String.valueOf(b.getTag()));
            b.setTag(null);
        }
        b.setEnabled(!value);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
