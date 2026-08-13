package com.carstream.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class EmbeddedServer implements Closeable {
    private final Context context;
    private final int port;
    private final String token;
    private final PollingRegistry registry;
    private final TorBoxLibraryClient library;
    private final CellularNetworkProvider networks;
    private final ExecutorService pool=Executors.newCachedThreadPool();
    private volatile boolean open;
    private ServerSocket socket;

    public EmbeddedServer(Context context,int port,String token,PollingRegistry registry,
                          TorBoxLibraryClient library,CellularNetworkProvider networks){
        this.context=context.getApplicationContext();this.port=port;this.token=token;
        this.registry=registry;this.library=library;this.networks=networks;
    }
    public EmbeddedServer(Context context,int port,String token,ClientRegistry ignored,
                          TorBoxLibraryClient library,CellularNetworkProvider networks){
        this(context,port,token,new PollingRegistry(),library,networks);
    }

    public synchronized void start() throws IOException {
        if(open)return;socket=new ServerSocket();socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress("0.0.0.0",port));open=true;pool.execute(this::acceptLoop);
    }
    private void acceptLoop(){while(open){try{Socket client=socket.accept();client.setTcpNoDelay(true);pool.execute(()->handle(client));}catch(IOException e){if(open)close();}}}

    private void handle(Socket client){
        try(Socket c=client;BufferedInputStream in=new BufferedInputStream(c.getInputStream());OutputStream out=new BufferedOutputStream(c.getOutputStream())){
            Request r=Request.read(in);if(r==null)return;
            if("OPTIONS".equals(r.method)){headers(out,204,"No Content","text/plain",0,null);out.flush();return;}
            if("/".equals(r.path)||"/index.html".equals(r.path)){asset(out,"index2.html","text/html",r.method);return;}
            if("/app2.js".equals(r.path)){asset(out,"app2.js","application/javascript",r.method);return;}
            if("/styles2.css".equals(r.path)){asset(out,"styles2.css","text/css",r.method);return;}
            if(!authorized(r)){text(out,401,"Unauthorized");return;}
            if("/api/library".equals(r.path)){library(out,r.method);return;}
            if("/api/state".equals(r.path)&&"POST".equals(r.method)){state(out,r);return;}
            if(r.path.startsWith("/stream/")){stream(out,r,decode(r.path.substring(8)));return;}
            text(out,404,"Not found");
        }catch(Exception ignored){}
    }

    private boolean authorized(Request r){String value=r.query.get("token");if(value==null)value=r.headers.get("x-carstream-token");return token.equals(value);}

    private void asset(OutputStream out,String name,String type,String method) throws IOException {
        byte[] data;try(InputStream input=context.getAssets().open(name)){data=readAll(input);}
        headers(out,200,"OK",type,data.length,null);if(!"HEAD".equals(method))out.write(data);out.flush();
    }
    private void library(OutputStream out,String method) throws Exception {
        JSONArray array=new JSONArray();for(MediaItem item:library.currentLibrary())array.put(item.toJson());
        byte[] data=new JSONObject().put("items",array).toString().getBytes(StandardCharsets.UTF_8);
        headers(out,200,"OK","application/json",data.length,null);if(!"HEAD".equals(method))out.write(data);out.flush();
    }
    private void state(OutputStream out,Request r) throws Exception {
        JSONObject input=new JSONObject(new String(r.body,StandardCharsets.UTF_8));
        long seen=input.optLong("commandVersion",0);PollingRegistry.Session session=registry.update(input);
        if(session==null){text(out,400,"Missing clientId");return;}
        byte[] data=registry.response(session,seen).toString().getBytes(StandardCharsets.UTF_8);
        headers(out,200,"OK","application/json",data.length,null);out.write(data);out.flush();
    }

    private void stream(OutputStream out,Request r,String id) throws Exception {
        MediaItem item=library.find(id);if(item==null){text(out,404,"Media not found");return;}
        HttpURLConnection upstream=networks.open(library.getDownloadUrl(item));
        upstream.setConnectTimeout(20000);upstream.setReadTimeout(30000);upstream.setInstanceFollowRedirects(true);
        String range=r.headers.get("range");if(range!=null)upstream.setRequestProperty("Range",range);
        upstream.setRequestProperty("User-Agent","CarStream/1.0");upstream.connect();
        int code=upstream.getResponseCode();String reason=upstream.getResponseMessage();
        InputStream source=code>=400?upstream.getErrorStream():upstream.getInputStream();
        if(source==null){text(out,502,"Upstream returned no data");upstream.disconnect();return;}
        LinkedHashMap<String,String> extra=new LinkedHashMap<>();
        copyHeader(upstream,extra,"Content-Range");copyHeader(upstream,extra,"Accept-Ranges");
        copyHeader(upstream,extra,"ETag");copyHeader(upstream,extra,"Last-Modified");
        String type=upstream.getContentType();if(type==null)type="video/mp4";
        long length=upstream.getContentLengthLong();headers(out,code,reason==null?status(code):reason,type,length,extra);
        if(!"HEAD".equals(r.method)){byte[] buffer=new byte[64*1024];int n;try(InputStream input=source){while((n=input.read(buffer))!=-1){out.write(buffer,0,n);out.flush();}}}
        out.flush();upstream.disconnect();
    }
    private static void copyHeader(HttpURLConnection c,Map<String,String> map,String name){String value=c.getHeaderField(name);if(value!=null)map.put(name,value);}

    private static void text(OutputStream out,int code,String value) throws IOException {
        byte[] data=value.getBytes(StandardCharsets.UTF_8);headers(out,code,status(code),"text/plain; charset=utf-8",data.length,null);out.write(data);out.flush();
    }
    private static void headers(OutputStream out,int code,String reason,String type,long length,Map<String,String> extra) throws IOException {
        StringBuilder h=new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Access-Control-Allow-Origin: *\r\n")
                .append("Access-Control-Allow-Headers: Content-Type, X-CarStream-Token, Range\r\n")
                .append("Access-Control-Allow-Methods: GET, HEAD, POST, OPTIONS\r\n");
        if(length>=0)h.append("Content-Length: ").append(length).append("\r\n");
        if(extra!=null)for(Map.Entry<String,String> e:extra.entrySet())h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        h.append("\r\n");out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
    private static String status(int code){switch(code){case 200:return"OK";case 204:return"No Content";case 206:return"Partial Content";case 400:return"Bad Request";case 401:return"Unauthorized";case 404:return"Not Found";case 416:return"Range Not Satisfiable";case 502:return"Bad Gateway";default:return"Response";}}
    private static String decode(String value){try{return URLDecoder.decode(value,"UTF-8");}catch(Exception e){return value;}}
    private static byte[] readAll(InputStream in) throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] x=new byte[8192];int n;while((n=in.read(x))!=-1)b.write(x,0,n);return b.toByteArray();}

    @Override public synchronized void close(){open=false;if(socket!=null)try{socket.close();}catch(IOException ignored){}socket=null;pool.shutdownNow();}

    private static final class Request {
        String method,path;Map<String,String> query=new HashMap<>(),headers=new HashMap<>();byte[] body=new byte[0];
        static Request read(BufferedInputStream in) throws IOException {
            String first=line(in);if(first==null||first.isEmpty())return null;String[] p=first.split(" ");if(p.length<2)return null;
            Request r=new Request();r.method=p[0].toUpperCase(Locale.US);String target=p[1];int q=target.indexOf('?');
            r.path=decode(q<0?target:target.substring(0,q));if(q>=0)parseQuery(target.substring(q+1),r.query);
            String value;while((value=line(in))!=null&&!value.isEmpty()){int colon=value.indexOf(':');if(colon>0)r.headers.put(value.substring(0,colon).trim().toLowerCase(Locale.US),value.substring(colon+1).trim());}
            int length=0;try{length=Integer.parseInt(r.headers.getOrDefault("content-length","0"));}catch(Exception ignored){}
            if(length>0){r.body=new byte[length];int off=0,n;while(off<length&&(n=in.read(r.body,off,length-off))>0)off+=n;if(off<length)r.body=Arrays.copyOf(r.body,off);}
            return r;
        }
        private static String line(InputStream in) throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();int c,prev=-1;while((c=in.read())!=-1){if(prev=='\r'&&c=='\n')break;if(prev!=-1)b.write(prev);prev=c;if(b.size()>16384)throw new IOException("Header too large");}if(c==-1&&prev==-1)return null;if(prev!=-1&&prev!='\r')b.write(prev);return b.toString("ISO-8859-1");}
        private static void parseQuery(String input,Map<String,String> out){for(String pair:input.split("&")){int i=pair.indexOf('=');String k=decode(i<0?pair:pair.substring(0,i));String v=decode(i<0?"":pair.substring(i+1));out.put(k,v);}}
    }
}
