package com.ccrstech.openandroiddex.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * The Docker Engine API, spoken directly over the port QEMU forwards onto the
 * phone's loopback.
 *
 * There is deliberately no {@code docker} CLI on the Android side. The CLI is
 * a Go binary we would have to cross-compile, ship and keep in step with the
 * engine, and everything the window shows — containers, images, whether the
 * engine is even up — is one plain HTTP GET returning JSON. The user's own
 * {@code docker} and {@code docker compose} live INSIDE the VM, on the serial
 * console the window also exposes; this class is for the app's own UI.
 *
 * Every call is blocking and must not be made on the main thread.
 */
final class DockerApi {

    private DockerApi() {}

    /**
     * Pinned rather than negotiated. The engine happily serves an older API
     * version than it implements, and pinning means a guest upgrade cannot
     * quietly change the shape of the JSON this file parses.
     */
    private static final String API = "/v1.43";

    /** Short, because these calls sit in a poll loop while the VM is booting. */
    private static final int TIMEOUT_MS = 4000;

    /**
     * Is the engine answering yet?
     *
     * This is the app's real definition of "the VM is up". The console reaching
     * a login prompt is not: dockerd is started by openrc afterwards, and the
     * gap between the two is many seconds under TCG.
     */
    static boolean ping(int port) {
        try {
            return get(port, "/_ping") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** Engine version string, or null if it is not answering. */
    static String version(int port) {
        try {
            String body = get(port, API + "/version");
            if (body == null) return null;
            return new JSONObject(body).optString("Version", null);
        } catch (Exception e) {
            return null;
        }
    }

    /** One row of the window's container list. */
    static final class Container {
        String id = "";
        String name = "";
        String image = "";
        String state = "";
        String status = "";

        boolean isRunning() {
            return "running".equals(state);
        }
    }

    static List<Container> containers(int port) {
        List<Container> out = new ArrayList<>();
        try {
            String body = get(port, API + "/containers/json?all=1");
            if (body == null) return out;
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Container c = new Container();
                c.id = o.optString("Id", "");
                c.image = o.optString("Image", "");
                c.state = o.optString("State", "");
                c.status = o.optString("Status", "");
                JSONArray names = o.optJSONArray("Names");
                // Docker returns every name with a leading slash; the first is
                // the one people mean.
                if (names != null && names.length() > 0) {
                    c.name = names.optString(0, "").replaceFirst("^/", "");
                }
                if (c.name.isEmpty()) c.name = c.id.length() > 12 ? c.id.substring(0, 12) : c.id;
                out.add(c);
            }
        } catch (Exception e) {
            DexLog.warn("docker", "container list failed", e);
        }
        return out;
    }

    /** One row of the window's image list. */
    static final class Image {
        String id = "";
        String tag = "";
        long size;
    }

    static List<Image> images(int port) {
        List<Image> out = new ArrayList<>();
        try {
            String body = get(port, API + "/images/json");
            if (body == null) return out;
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Image im = new Image();
                im.id = o.optString("Id", "");
                im.size = o.optLong("Size", 0);
                JSONArray tags = o.optJSONArray("RepoTags");
                im.tag = (tags != null && tags.length() > 0)
                        ? tags.optString(0, "") : "<none>";
                out.add(im);
            }
        } catch (Exception e) {
            DexLog.warn("docker", "image list failed", e);
        }
        return out;
    }

    static boolean startContainer(int port, String id) {
        return post(port, API + "/containers/" + id + "/start");
    }

    /** Stop with the engine's default grace period before it escalates. */
    static boolean stopContainer(int port, String id) {
        return post(port, API + "/containers/" + id + "/stop?t=10");
    }

    static boolean removeContainer(int port, String id) {
        return delete(port, API + "/containers/" + id + "?force=1");
    }

    // ── transport ──

    private static String get(int port, String path) throws Exception {
        HttpURLConnection c = open(port, path, "GET");
        try {
            if (c.getResponseCode() / 100 != 2) return null;
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static boolean post(int port, String path) {
        try {
            HttpURLConnection c = open(port, path, "POST");
            try {
                c.setFixedLengthStreamingMode(0);
                int rc = c.getResponseCode();
                // 304 = already in that state, which is a success from the
                // caller's point of view: the button did what it promised.
                return rc / 100 == 2 || rc == 304;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            DexLog.warn("docker", "POST " + path + " failed", e);
            return false;
        }
    }

    private static boolean delete(int port, String path) {
        try {
            HttpURLConnection c = open(port, path, "DELETE");
            try {
                return c.getResponseCode() / 100 == 2;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            DexLog.warn("docker", "DELETE " + path + " failed", e);
            return false;
        }
    }

    private static HttpURLConnection open(int port, String path, String method)
            throws Exception {
        // 127.0.0.1 spelled out rather than "localhost": the resolver would
        // otherwise be in this path, and it has been known to answer with the
        // IPv6 loopback that QEMU's forward is not listening on.
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setUseCaches(false);
        return c;
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[1 << 13];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toString("UTF-8");
    }
}
