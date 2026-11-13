package app.goregram;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final String API="https://goregram-api-production.up.railway.app";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private String token, myName="Goregram";
    private LinearLayout page, body;
    private TextView status;
    private long conversation=-1;
    private EditText phoneInput, passwordInput, nameInput, messageInput;
    private String inviteToken;

    @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setStatusBarColor(Color.rgb(6,26,20));getWindow().setNavigationBarColor(Color.rgb(6,26,20));
        token=getPreferences(0).getString("token",null); myName=getPreferences(0).getString("name","Goregram");
        Uri u=getIntent().getData(); if(u!=null) inviteToken=u.getQueryParameter("token");
        shell(); if(inviteToken!=null) inviteScreen(); else if(token==null) loginScreen(); else conversations();
    }
    private void shell(){page=new LinearLayout(this);page.setOrientation(1);page.setPadding(22,24,22,16);page.setBackgroundColor(Color.rgb(6,26,20));
        TextView brand=new TextView(this);brand.setText("✦ GOREGRAM");brand.setTextSize(24);brand.setTypeface(null,1);brand.setTextColor(Color.rgb(242,201,76));page.addView(brand);
        status=new TextView(this);status.setTextColor(0xffffaaaa);page.addView(status);
        ScrollView scroll=new ScrollView(this);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));body=new LinearLayout(this);body.setOrientation(1);scroll.addView(body);setContentView(page);}
    private TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.WHITE);t.setPadding(0,12,0,8);return t;}
    private EditText field(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);e.setTextColor(Color.WHITE);e.setHintTextColor(0xffaabbb2);return e;}
    private Button button(String s,Runnable r){Button b=new Button(this);b.setText(s);b.setOnClickListener(v->r.run());return b;}
    private void title(String s){body.removeAllViews();body.addView(text(s,22));}
    private void say(String s){runOnUiThread(()->status.setText(s));}
    private void loginScreen(){title("Connexion à Goregram");phoneInput=field("Numéro international +225...");passwordInput=field("Mot de passe");passwordInput.setInputType(129);body.addView(phoneInput);body.addView(passwordInput);
        body.addView(button("Se connecter",()->auth(false)));
        body.addView(button("Créer un compte",()->registerScreen(null)));}
    private void registerScreen(String invite){title("Créer mon compte");phoneInput=field("Numéro international +225...");nameInput=field("Nom ou pseudonyme");passwordInput=field("Mot de passe (10 caractères minimum)");passwordInput.setInputType(129);
        body.addView(phoneInput);body.addView(nameInput);body.addView(passwordInput);body.addView(button("Créer le compte",()->auth(true)));}
    private void inviteScreen(){title("Rejoindre Goregram");body.addView(text("Votre invitation sera vérifiée avant la création du compte.",15));nameInput=field("Nom ou pseudonyme");passwordInput=field("Mot de passe (10 caractères minimum)");passwordInput.setInputType(129);body.addView(nameInput);body.addView(passwordInput);
        body.addView(button("Vérifier l'invitation",()->{if(inviteToken==null){say("Lien invalide");return;}io.execute(()->{try{JSONObject v=request("GET","/api/invitations/validate/"+inviteToken,null,false);String p=v.optString("phone");runOnUiThread(()->{registerScreen(inviteToken);phoneInput.setText(p);});}catch(Exception e){say(e.getMessage());}});}));}
    private void auth(boolean register){String p=phoneInput.getText().toString().trim(),pw=passwordInput.getText().toString();String n=register?nameInput.getText().toString().trim():"";
        if(!p.matches("^\\+[1-9][0-9]{7,14}$")){say("Numéro international invalide.");return;}
        io.execute(()->{try{JSONObject j=new JSONObject();j.put("phone",p);j.put("password",pw);String path="/api/auth/login";
            if(register){j.put("displayName",n);if(inviteToken!=null)j.put("inviteToken",inviteToken);path="/api/auth/register";}
            JSONObject result=request("POST",path,j,false);JSONObject user=result.optJSONObject("user");token=result.optString("token");myName=user==null?n:user.optString("display_name",n);
            getPreferences(0).edit().putString("token",token).putString("name",myName).apply();runOnUiThread(this::conversations);
        }catch(Exception e){say(e.getMessage());}});}
    private void conversations(){title("Bonjour "+myName);body.addView(text("Vos discussions Goregram",16));EditText target=field("Numéro du destinataire +225...");body.addView(target);
        body.addView(button("Nouvelle discussion",()->startChat(target.getText().toString().trim())));
        body.addView(button("Actualiser",this::loadChats));body.addView(button("Se déconnecter",()->{token=null;getPreferences(0).edit().clear().apply();loginScreen();}));loadChats();}
    private void loadChats(){io.execute(()->{try{JSONObject j=request("GET","/api/conversations",null,true);JSONArray a=j.optJSONArray("conversations");runOnUiThread(()->{if(a==null)return;for(int i=0;i<a.length();i++){JSONObject c=a.optJSONObject(i);if(c==null)continue;long id=c.optLong("id",c.optLong("conversation_id"));String label=c.optString("title","Discussion "+id);body.addView(button(label,()->openChat(id,label)));}});}catch(Exception e){say(e.getMessage());}});}
    private void startChat(String phone){if(!phone.matches("^\\+[1-9][0-9]{7,14}$")){say("Numéro international invalide.");return;}io.execute(()->{try{JSONObject found=new JSONObject();found.put("phone",phone);JSONObject lookup=request("POST","/api/contacts/lookup",found,true);
        JSONObject contact=lookup.optJSONObject("contact");if(contact==null){JSONObject inv=request("POST","/api/invitations",found,true);String link=inv.optString("inviteLink");if(link.isEmpty())link=inv.optString("url");String share="Rejoins-moi sur Goregram : "+link;runOnUiThread(()->{Intent send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_TEXT,share);startActivity(Intent.createChooser(send,"Inviter sur Goregram"));});return;}
        JSONObject req=new JSONObject();req.put("userId",contact.optLong("id"));JSONObject created=request("POST","/api/conversations/direct",req,true);long id=created.optLong("conversationId");runOnUiThread(()->openChat(id,phone));
    }catch(Exception e){say(e.getMessage());}});}
    private void openChat(long id,String label){conversation=id;title(label);body.addView(button("← Discussions",this::conversations));body.addView(button("Actualiser les messages",this::loadMessages));messageInput=field("Écrire un message...");body.addView(messageInput);body.addView(button("Envoyer",this::sendMessage));loadMessages();}
    private void loadMessages(){long id=conversation;if(id<0)return;io.execute(()->{try{JSONObject j=request("GET","/api/conversations/"+id+"/messages",null,true);JSONArray a=j.optJSONArray("messages");runOnUiThread(()->{if(a==null)return;int insert=Math.max(1,body.getChildCount()-3);for(int i=body.getChildCount()-1;i>=insert;i--)body.removeViewAt(i);for(int i=0;i<a.length();i++){JSONObject m=a.optJSONObject(i);if(m!=null)body.addView(text(m.optString("body"),15));}});}catch(Exception e){say(e.getMessage());}});}
    private void sendMessage(){String value=messageInput.getText().toString().trim();if(value.isEmpty()||conversation<0)return;io.execute(()->{try{JSONObject j=new JSONObject();j.put("body",value);request("POST","/api/conversations/"+conversation+"/messages",j,true);runOnUiThread(()->{messageInput.setText("");loadMessages();});}catch(Exception e){say(e.getMessage());}});}
    private JSONObject request(String method,String path,JSONObject data,boolean auth)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(API+path).openConnection();c.setRequestMethod(method);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("Accept","application/json");if(auth&&token!=null)c.setRequestProperty("Authorization","Bearer "+token);
        if(data!=null){c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");try(OutputStream o=c.getOutputStream()){o.write(data.toString().getBytes(StandardCharsets.UTF_8));}}
        int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();ByteArrayOutputStream out=new ByteArrayOutputStream();in.transferTo(out);String raw=out.toString("UTF-8");c.disconnect();JSONObject result=new JSONObject(raw.isEmpty()?"{}":raw);if(code>=400)throw new Exception(result.optString("error","Erreur serveur "+code));return result;}
}
