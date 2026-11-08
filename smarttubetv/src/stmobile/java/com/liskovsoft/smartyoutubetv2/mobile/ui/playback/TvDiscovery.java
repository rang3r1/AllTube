package com.liskovsoft.smartyoutubetv2.mobile.ui.playback;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.SystemClock;

import com.liskovsoft.sharedutils.mylogger.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Finds Google TV / Android TV / Chromecast devices on the Wi-Fi the way the official app does:
 * mDNS "_googlecast._tcp" discovery, then Cast v2 (TLS + protobuf, hand-rolled, no Cast SDK) to
 * start YouTube on the TV and ask it for its lounge screenId, so no TV code is needed.
 * Also finds DIAL devices that offer YouTube (SmartTube's "Show in YouTube cast list", smart TVs):
 * SSDP search, then YouTube's DIAL launch with a pairing code that the lounge turns into a screenId.
 */
final class TvDiscovery {
    private static final String TAG = TvDiscovery.class.getSimpleName();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SEARCH_MS = 2_500;
    private static final String YOUTUBE_APP_ID = "233637DE";
    private static final String NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection";
    private static final String NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat";
    private static final String NS_RECEIVER = "urn:x-cast:com.google.cast.receiver";
    private static final String NS_MDX = "urn:x-cast:com.google.youtube.mdx";
    private static final String DIAL_ST = "urn:dial-multiscreen-org:service:dial:1";
    private static final String GET_SCREEN = "https://www.youtube.com/api/lounge/pairing/get_screen";

    static final class Tv {
        final String name;
        final String host;
        final int port;
        /** DIAL app URL of YouTube (".../apps/YouTube"); null for a Cast device. */
        final String dialApp;

        Tv(String name, String host, int port) {
            this(name, host, port, null);
        }

        Tv(String name, String host, int port, String dialApp) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.dialApp = dialApp;
        }
    }

    private TvDiscovery() {
    }

    /** Cast and DIAL-YouTube devices on the Wi-Fi; blocks ~2.5 s plus resolving. Empty when none. */
    static List<Tv> find(Context context) {
        NsdManager nsd = (NsdManager) context.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        List<NsdServiceInfo> services = Collections.synchronizedList(new ArrayList<>());
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(NsdServiceInfo service) {
                services.add(service);
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                Log.d(TAG, "mDNS discovery failed: %s", error);
            }

            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) { }
            @Override public void onServiceLost(NsdServiceInfo service) { }
            @Override public void onStopDiscoveryFailed(String type, int error) { }
        };

        nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        List<Tv> dial = Collections.synchronizedList(new ArrayList<>());
        Thread ssdp = new Thread(() -> dial.addAll(findDial()), "SsdpSearch");
        ssdp.start();
        SystemClock.sleep(SEARCH_MS);
        try {
            nsd.stopServiceDiscovery(listener);
        } catch (IllegalArgumentException alreadyStopped) {
            // Discovery never started.
        }

        List<Tv> tvs = new ArrayList<>();
        for (NsdServiceInfo service : new ArrayList<>(services)) {
            NsdServiceInfo resolved = resolve(nsd, service);
            if (resolved == null || resolved.getHost() == null) {
                continue;
            }
            byte[] friendlyName = resolved.getAttributes().get("fn");
            String name = friendlyName != null ? new String(friendlyName, UTF8) : resolved.getServiceName();
            tvs.add(new Tv(name, resolved.getHost().getHostAddress(), resolved.getPort()));
        }
        try {
            ssdp.join(15_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (dial) {
            tvs.addAll(dial);
        }
        return tvs;
    }

    /** SSDP M-SEARCH for DIAL devices, keeping those whose DIAL server knows the YouTube app. */
    private static List<Tv> findDial() {
        Set<String> locations = new LinkedHashSet<>();
        byte[] search = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\nST: " + DIAL_ST + "\r\n\r\n").getBytes(UTF8);
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) { // UDP: a second try for a lost packet
                socket.send(new DatagramPacket(search, search.length, group, 1900));
            }
            long end = SystemClock.elapsedRealtime() + SEARCH_MS;
            byte[] buffer = new byte[2048];
            while (SystemClock.elapsedRealtime() < end) {
                socket.setSoTimeout((int) Math.max(1, end - SystemClock.elapsedRealtime()));
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException timeUp) {
                    break;
                }
                String location = header(new String(reply.getData(), 0, reply.getLength(), UTF8), "LOCATION");
                if (location != null) {
                    locations.add(location);
                }
            }
        } catch (IOException e) {
            Log.d(TAG, "SSDP search failed: %s", e.getMessage());
        }

        List<Tv> tvs = new ArrayList<>();
        for (String location : locations) {
            try {
                HttpURLConnection description = open(location);
                String appsUrl = description.getHeaderField("Application-URL");
                String name = tag(read(description), "friendlyName");
                if (appsUrl == null || name == null) {
                    continue;
                }
                String app = (appsUrl.endsWith("/") ? appsUrl : appsUrl + "/") + "YouTube";
                HttpURLConnection probe = open(app);
                int code = probe.getResponseCode(); // Cast devices answer 404; mDNS already found them
                probe.disconnect();
                if (code == 200) {
                    URL url = new URL(app);
                    tvs.add(new Tv(name, url.getHost(), url.getPort(), app));
                }
            } catch (IOException e) {
                Log.d(TAG, "DIAL device %s skipped: %s", location, e.getMessage());
            }
        }
        return tvs;
    }

    /** Value of a header in an SSDP reply, or null. */
    static String header(String message, String name) {
        for (String line : message.split("\r?\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** Text of the first {@code <name>} element of a DIAL device description, or null. */
    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">\\s*([^<]*?)\\s*</" + name + ">").matcher(xml);
        return m.find() ? m.group(1).replace("&amp;", "&") : null;
    }

    /**
     * YouTube's DIAL launch: the TV registers our pairing code with the lounge, then get_screen
     * hands out its screenId (the same call as pairing with a TV code). Also brings the app up.
     */
    private static String dialScreenId(Tv tv) throws Exception {
        String pairingCode = UUID.randomUUID().toString();
        HttpURLConnection launch = open(tv.dialApp);
        launch.setRequestMethod("POST");
        launch.setDoOutput(true);
        launch.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
        try (OutputStream out = launch.getOutputStream()) {
            out.write(("pairingCode=" + pairingCode + "&theme=cl&dialLaunch=watch").getBytes(UTF8));
        }
        int code = launch.getResponseCode();
        launch.disconnect();
        if (code != 200 && code != 201) {
            throw new IOException("DIAL launch on " + tv.name + ": HTTP " + code);
        }

        byte[] form = ("pairing_code=" + pairingCode).getBytes(UTF8); // a UUID needs no URL encoding
        for (int i = 0; i < 10; i++) { // the TV registers the code a moment after the launch
            SystemClock.sleep(1_000);
            HttpURLConnection get = open(GET_SCREEN);
            get.setRequestMethod("POST");
            get.setDoOutput(true);
            get.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = get.getOutputStream()) {
                out.write(form);
            }
            if (get.getResponseCode() == 200) {
                return new JSONObject(read(get)).getJSONObject("screen").getString("screenId");
            }
            get.disconnect(); // 404 until the TV has registered the code
        }
        throw new IOException("No screenId from " + tv.name);
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(3_000);
        connection.setReadTimeout(10_000);
        return connection;
    }

    private static String read(HttpURLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int n; (n = in.read(buffer)) > 0 && text.size() < 64 * 1024; ) {
                text.write(buffer, 0, n);
            }
            return text.toString("UTF-8");
        } finally {
            connection.disconnect();
        }
    }

    /** NsdManager resolves one service at a time, so this blocks per service. */
    private static NsdServiceInfo resolve(NsdManager nsd, NsdServiceInfo service) {
        CountDownLatch done = new CountDownLatch(1);
        NsdServiceInfo[] result = new NsdServiceInfo[1];
        nsd.resolveService(service, new NsdManager.ResolveListener() {
            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                result[0] = info;
                done.countDown();
            }

            @Override
            public void onResolveFailed(NsdServiceInfo info, int error) {
                done.countDown();
            }
        });
        try {
            done.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return result[0];
    }

    /** Starts YouTube on the TV (waking it, like the official app) and returns its lounge screenId. */
    static String screenId(Tv tv) throws Exception {
        if (tv.dialApp != null) {
            return dialScreenId(tv);
        }
        try (SSLSocket socket = (SSLSocket) trustAll().getSocketFactory().createSocket(tv.host, tv.port)) {
            socket.setSoTimeout(20_000);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            send(out, "receiver-0", NS_CONNECTION, new JSONObject().put("type", "CONNECT"));
            send(out, "receiver-0", NS_RECEIVER, new JSONObject()
                    .put("type", "LAUNCH").put("appId", YOUTUBE_APP_ID).put("requestId", 1));

            String transportId = null;
            for (int i = 0; i < 50; i++) {
                String[] message = receive(in); // {sourceId, namespace, payload}
                JSONObject payload = new JSONObject(message[2]);
                String type = payload.optString("type");

                if ("PING".equals(type)) {
                    send(out, message[0], NS_HEARTBEAT, new JSONObject().put("type", "PONG"));
                } else if ("mdxSessionStatus".equals(type)) {
                    return payload.getJSONObject("data").getString("screenId");
                } else if (transportId == null && "RECEIVER_STATUS".equals(type)) {
                    JSONArray apps = payload.getJSONObject("status").optJSONArray("applications");
                    for (int j = 0; apps != null && j < apps.length(); j++) {
                        JSONObject app = apps.getJSONObject(j);
                        if (YOUTUBE_APP_ID.equals(app.optString("appId")) && app.has("transportId")) {
                            transportId = app.getString("transportId");
                            send(out, transportId, NS_CONNECTION, new JSONObject().put("type", "CONNECT"));
                            send(out, transportId, NS_MDX, new JSONObject().put("type", "getMdxSessionStatus"));
                        }
                    }
                }
            }
            throw new IOException("No screenId from " + tv.name);
        }
    }

    // CastMessage protobuf: 1 version=0, 2 source, 3 destination, 4 namespace, 5 payload type=0 (string), 6 payload.
    static void send(OutputStream out, String destination, String namespace, JSONObject payload) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        varint(message, 1 << 3);
        varint(message, 0);
        string(message, 2, "sender-0");
        string(message, 3, destination);
        string(message, 4, namespace);
        varint(message, 5 << 3);
        varint(message, 0);
        string(message, 6, payload.toString());

        // Length prefix and body in one write: Cast devices drop the connection when they arrive
        // in separate TLS records.
        byte[] body = message.toByteArray();
        int n = body.length;
        byte[] frame = new byte[4 + n];
        frame[0] = (byte) (n >>> 24);
        frame[1] = (byte) (n >>> 16);
        frame[2] = (byte) (n >>> 8);
        frame[3] = (byte) n;
        System.arraycopy(body, 0, frame, 4, n);
        out.write(frame);
        out.flush();
    }

    /** Returns {sourceId, namespace, payload} of the next message. */
    static String[] receive(DataInputStream in) throws IOException {
        int frameLength = in.readInt();
        if (frameLength < 0 || frameLength > 64 * 1024) {
            throw new IOException("Bad Cast frame length " + frameLength); // else a bad device could OOM the app
        }
        byte[] body = new byte[frameLength];
        in.readFully(body);

        String[] fields = new String[7];
        int[] pos = {0};
        while (pos[0] < body.length) {
            long key = readVarint(body, pos);
            int field = (int) (key >>> 3);
            if ((key & 7) == 2) {
                int length = (int) readVarint(body, pos);
                if (field < fields.length) {
                    fields[field] = new String(body, pos[0], length, UTF8);
                }
                pos[0] += length;
            } else {
                readVarint(body, pos);
            }
        }
        return new String[]{fields[2], fields[4], fields[6] != null ? fields[6] : "{}"};
    }

    private static void string(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(UTF8);
        varint(out, field << 3 | 2);
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void varint(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static long readVarint(byte[] bytes, int[] pos) {
        long value = 0;
        for (int shift = 0; ; shift += 7) {
            byte b = bytes[pos[0]++];
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
    }

    /** Cast devices use self-signed certs; only the screenId is read over this LAN socket. */
    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static SSLContext trustAll() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return context;
    }
}
