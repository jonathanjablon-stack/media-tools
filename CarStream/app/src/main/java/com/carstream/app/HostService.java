package com.carstream.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class HostService extends Service implements WifiDirectController.Listener {
    public interface Listener { void onChanged(); }
    public static final int PORT = 8844;
    private static final int NOTICE_ID = 8844;
    private static final String CHANNEL = "carstream";

    public final class LocalBinder extends Binder { public HostService service() { return HostService.this; } }

    public static final class Snapshot {
        public final boolean running, cellularReady, keyConfigured;
        public final String status, networkName, passphrase, tabletUrl, libraryMessage;
        public final List<PollingRegistry.Session> clients;
        public final List<MediaItem> library;
        Snapshot(boolean running, boolean cellularReady, boolean keyConfigured,
                 String status, String networkName, String passphrase, String tabletUrl,
                 String libraryMessage, List<PollingRegistry.Session> clients, List<MediaItem> library) {
            this.running=running;this.cellularReady=cellularReady;this.keyConfigured=keyConfigured;
            this.status=status;this.networkName=networkName;this.passphrase=passphrase;
            this.tabletUrl=tabletUrl;this.libraryMessage=libraryMessage;
            this.clients=Collections.unmodifiableList(new ArrayList<>(clients));
            this.library=Collections.unmodifiableList(new ArrayList<>(library));
        }
    }

    private final LocalBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners=new CopyOnWriteArrayList<>();
    private SecureStore store; private CellularNetworkProvider networks; private TorBoxAccess torBox;
    private PollingRegistry registry; private WifiDirectController wifi; private EmbeddedServer server;
    private volatile boolean running; private volatile String status="Stopped",networkName="",passphrase="",tabletUrl="";
    private volatile String libraryMessage="Save the TorBox API key, then refresh the library."; private String token;

    @Override public void onCreate(){super.onCreate();store=new SecureStore(this);networks=new CellularNetworkProvider(this);
        torBox=new TorBoxAccess(networks);registry=new PollingRegistry();registry.addListener(this::changed);
        wifi=new WifiDirectController(this,this);token=randomToken();server=new EmbeddedServer(this,PORT,token,registry,torBox,networks);
        String key=store.loadTorBoxKey();if(!Strings.isBlank(key))torBox.setApiKey(key);createChannel();}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){return START_NOT_STICKY;}
    public void addListener(Listener value){listeners.addIfAbsent(value);} public void removeListener(Listener value){listeners.remove(value);}

    public synchronized void startHost(){if(running){wifi.start();return;}startForeground(NOTICE_ID,notice("Starting CarStream"));
        networks.start();try{server.start();running=true;status="Starting Wi-Fi Direct...";wifi.start();refreshLibrary();}
        catch(Exception e){status="Server error: "+message(e);stopForeground(STOP_FOREGROUND_REMOVE);}changed();}
    public synchronized void stopHost(){running=false;try{wifi.stop(true);}catch(Exception ignored){}try{server.close();}catch(Exception ignored){}
        networks.stop();networkName="";passphrase="";tabletUrl="";status="Stopped";stopForeground(STOP_FOREGROUND_REMOVE);changed();}

    public void saveKey(String input){String key=input==null?"":input.trim();if(key.isEmpty()){libraryMessage="Enter the TorBox API key.";changed();return;}
        worker.execute(()->{try{store.saveTorBoxKey(key);torBox.setApiKey(key);refreshNow(key);}catch(Exception e){libraryMessage="Could not save key: "+message(e);changed();}});}
    public void refreshLibrary(){String key=store.loadTorBoxKey();if(Strings.isBlank(key)){libraryMessage="TorBox API key is not configured.";changed();return;}worker.execute(()->refreshNow(key));}
    private void refreshNow(String key){try{libraryMessage="Refreshing TorBox library...";changed();int count=torBox.refresh(key).size();libraryMessage=count+" playable video file"+(count==1?"":"s")+" loaded.";}catch(Exception e){libraryMessage="Library error: "+message(e);}changed();}
    public void addMagnet(String input){String key=store.loadTorBoxKey(),value=input==null?"":input.trim();
        if(Strings.isBlank(key)){libraryMessage="Save the TorBox API key first.";changed();return;}
        if(!value.startsWith("magnet:?")){libraryMessage="Paste a complete magnet link.";changed();return;}
        worker.execute(()->{try{libraryMessage="Adding item to TorBox...";changed();torBox.addMagnet(key,value);refreshNow(key);}catch(Exception e){libraryMessage="Add error: "+message(e);changed();}});}

    public boolean command(String clientId,String action){try{return registry.send(clientId,new JSONObject().put("action",action));}catch(Exception ignored){return false;}}
    public boolean seek(String clientId,double seconds){try{return registry.send(clientId,new JSONObject().put("action","seekRelative").put("seconds",seconds));}catch(Exception ignored){return false;}}
    public boolean playMedia(String clientId,String mediaId){MediaItem item=torBox.find(mediaId);if(item==null)return false;try{return registry.send(clientId,new JSONObject().put("action","setMedia").put("media",item.toJson()).put("startAt",0).put("autoplay",true));}catch(Exception ignored){return false;}}
    public boolean setMode(String clientId,PollingRegistry.Session.ControlMode mode){return registry.setMode(clientId,mode);}
    public Snapshot snapshot(){String key=store.loadTorBoxKey();return new Snapshot(running,networks.hasCellularNetwork(),!Strings.isBlank(key),status,networkName,passphrase,tabletUrl,libraryMessage,registry.snapshot(),torBox.currentLibrary());}

    @Override public void onStatus(String value){status=value==null?"":value;updateNotice();changed();}
    @Override public void onGroupReady(String name,String password,String address){networkName=name==null?"":name;passphrase=password==null?"":password;
        tabletUrl=Strings.isBlank(address)?"":"http://"+address+":"+PORT+"/?token="+token;status="Ready for tablets";updateNotice();changed();}
    @Override public void onStopped(){changed();}
    private void changed(){main.post(()->{for(Listener value:listeners)value.onChanged();});}
    private void createChannel(){if(Build.VERSION.SDK_INT<26)return;NotificationManager manager=getSystemService(NotificationManager.class);if(manager!=null)manager.createNotificationChannel(new NotificationChannel(CHANNEL,"CarStream host",NotificationManager.IMPORTANCE_LOW));}
    private Notification notice(String text){Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this);return builder.setContentTitle("CarStream").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_upload).setOngoing(true).build();}
    private void updateNotice(){if(!running)return;NotificationManager manager=getSystemService(NotificationManager.class);if(manager!=null)manager.notify(NOTICE_ID,notice(status));}
    private static String randomToken(){byte[] value=new byte[24];new SecureRandom().nextBytes(value);return Base64.encodeToString(value,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);}
    private static String message(Throwable error){if(error==null)return"Unknown error";return Strings.isBlank(error.getMessage())?error.getClass().getSimpleName():error.getMessage();}
    @Override public void onDestroy(){stopHost();worker.shutdownNow();super.onDestroy();}
}
