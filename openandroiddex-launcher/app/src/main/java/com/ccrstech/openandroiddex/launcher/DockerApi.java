package com.ccrstech.openandroiddex.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
     * Long, for the one call that is a download. Between progress lines rather
     * than for the whole pull: the engine keeps talking while it works, so a
     * gap this size means it has genuinely stopped, however big the image is.
     */
    private static final int PULL_TIMEOUT_MS = 120_000;
    /** Logs are one read of a possibly large tail, not a poll. */
    private static final int LOG_TIMEOUT_MS = 20_000;
    /** An exec is someone else's command, and it runs under TCG. */
    private static final int EXEC_TIMEOUT_MS = 60_000;

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

        /** Paused is not stopped: start refuses it, unpause is what it wants. */
        boolean isPaused() {
            return "paused".equals(state);
        }

        String shortId() {
            return id.length() > 12 ? id.substring(0, 12) : id;
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
        /** Unix seconds, 0 when the engine did not say. */
        long created;

        /** The 12 hex digits people actually quote, without the algorithm. */
        String shortId() {
            String s = id.startsWith("sha256:") ? id.substring(7) : id;
            return s.length() > 12 ? s.substring(0, 12) : s;
        }

        /** What to hand the engine to mean this image. */
        String ref() {
            return (tag == null || tag.isEmpty() || tag.startsWith("<")) ? id : tag;
        }
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
                im.created = o.optLong("Created", 0);
                out.add(im);
            }
        } catch (Exception e) {
            DexLog.warn("docker", "image list failed", e);
        }
        return out;
    }

    /**
     * The container verbs.
     *
     * All of them answer null for "done" and the engine's message otherwise —
     * "container is paused", "port is already allocated", "removal in progress"
     * are the entire answer to what just happened, and a boolean throws it away.
     */
    static String startContainer(int port, String id) {
        return act(port, "/containers/" + id + "/start");
    }

    /** Stop with the engine's default grace period before it escalates. */
    static String stopContainer(int port, String id) {
        return act(port, "/containers/" + id + "/stop?t=10");
    }

    static String restartContainer(int port, String id) {
        return act(port, "/containers/" + id + "/restart?t=10");
    }

    static String pauseContainer(int port, String id) {
        return act(port, "/containers/" + id + "/pause");
    }

    static String unpauseContainer(int port, String id) {
        return act(port, "/containers/" + id + "/unpause");
    }

    static String renameContainer(int port, String id, String name) {
        return act(port, "/containers/" + id + "/rename?name=" + enc(name));
    }

    static String removeContainer(int port, String id) {
        return request(port, API + "/containers/" + id + "?force=1",
                "DELETE", null, TIMEOUT_MS).error;
    }

    private static String act(int port, String path) {
        return request(port, API + path, "POST", null, TIMEOUT_MS).error;
    }

    /**
     * The tail of a container's output, ready to put in front of someone.
     *
     * Without a TTY the engine multiplexes stdout and stderr into framed
     * chunks, so the bytes are not text until they have been unpacked — a raw
     * read shows a stray control byte and a length in front of every line.
     *
     * @return the log text, or null if the engine would not give it up.
     */
    static String logs(int port, String id, int tail) {
        byte[] out = raw(port, API + "/containers/" + id
                + "/logs?stdout=1&stderr=1&tail=" + tail, "GET", null, LOG_TIMEOUT_MS);
        return out == null ? null : demux(out);
    }

    /**
     * A response read as bytes.
     *
     * Logs and exec output are framed streams, and the frame header carries a
     * length in raw bytes — decode it as text first and a multi-byte character
     * anywhere in the output moves everything after it.
     */
    private static byte[] raw(int port, String path, String method, String body,
                              int readTimeout) {
        try {
            HttpURLConnection c = open(port, path, method, readTimeout);
            try {
                if (body != null) {
                    byte[] out = body.getBytes("UTF-8");
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(out.length);
                    c.setDoOutput(true);
                    OutputStream os = c.getOutputStream();
                    os.write(out);
                    os.flush();
                }
                if (c.getResponseCode() / 100 != 2) return null;
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[1 << 13];
                int n;
                InputStream in = c.getInputStream();
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toByteArray();
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            DexLog.warn("docker", method + " " + path + " failed", e);
            return null;
        }
    }

    /**
     * Unpack the engine's stream framing: one byte of stream id, three of
     * padding, four of big-endian length, then that many bytes of output.
     *
     * A container started with a TTY is not framed at all — its output is the
     * bytes themselves — so a first chunk that does not look like a header is
     * taken at face value rather than mangled.
     */
    private static String demux(byte[] raw) {
        try {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i + 8 <= raw.length) {
                if ((raw[i] & 0xFF) > 2 || raw[i + 1] != 0 || raw[i + 2] != 0
                        || raw[i + 3] != 0) {
                    break;
                }
                int len = ((raw[i + 4] & 0xFF) << 24) | ((raw[i + 5] & 0xFF) << 16)
                        | ((raw[i + 6] & 0xFF) << 8) | (raw[i + 7] & 0xFF);
                i += 8;
                if (len < 0 || i + len > raw.length) len = raw.length - i;
                sb.append(new String(raw, i, len, "UTF-8"));
                i += len;
            }
            if (sb.length() == 0) return new String(raw, "UTF-8");
            if (i < raw.length) sb.append(new String(raw, i, raw.length - i, "UTF-8"));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Run one command inside a container and bring back what it printed.
     *
     * One shot, not a session: the interactive form of this is a hijacked
     * bidirectional stream, which is a socket protocol rather than an HTTP
     * request, and a window that already has a real console one tab away does
     * not need to reimplement one badly. {@code sh -c} so a pipe or a glob in
     * what the user typed means what they expect.
     *
     * @return the output, or null if the exec could not be created.
     */
    static String exec(int port, String id, String command) {
        try {
            JSONObject spec = new JSONObject();
            spec.put("AttachStdout", true);
            spec.put("AttachStderr", true);
            spec.put("Tty", false);
            spec.put("Cmd", new JSONArray().put("sh").put("-c").put(command));
            Reply made = request(port, API + "/containers/" + id + "/exec",
                    "POST", spec.toString(), TIMEOUT_MS);
            if (made.error != null) return made.error;
            String execId = new JSONObject(made.body).optString("Id", "");
            if (execId.isEmpty()) return null;

            JSONObject start = new JSONObject();
            start.put("Detach", false);
            start.put("Tty", false);
            byte[] out = raw(port, API + "/exec/" + execId + "/start", "POST",
                    start.toString(), EXEC_TIMEOUT_MS);
            return out == null ? null : demux(out);
        } catch (Exception e) {
            DexLog.warn("docker", "exec failed", e);
            return String.valueOf(e.getMessage());
        }
    }

    /** What has changed in the container's filesystem since its image. */
    static String changes(int port, String id, int limit) {
        try {
            String body = get(port, API + "/containers/" + id + "/changes");
            if (body == null) return null;
            // The engine answers the JSON literal null, newline and all, when
            // nothing has changed — the commonest case, and not a failure.
            String trimmed = body.trim();
            if (trimmed.isEmpty() || "null".equals(trimmed)) return "";
            JSONArray arr = new JSONArray(trimmed);
            StringBuilder sb = new StringBuilder();
            int shown = Math.min(arr.length(), limit);
            for (int i = 0; i < shown; i++) {
                JSONObject o = arr.getJSONObject(i);
                // 0 modified, 1 added, 2 deleted — the same letters docker diff
                // prints, because they are what anyone reading this expects.
                int kind = o.optInt("Kind", 0);
                sb.append(kind == 1 ? 'A' : kind == 2 ? 'D' : 'C')
                        .append(' ').append(o.optString("Path", "")).append('\n');
            }
            if (arr.length() > shown) {
                sb.append("\n+ ").append(arr.length() - shown);
            }
            return sb.toString();
        } catch (Exception e) {
            DexLog.warn("docker", "changes " + id + " failed", e);
            return null;
        }
    }

    /** Everything the container Details sheet shows. */
    static final class ContainerInfo {
        String id = "";
        String name = "";
        String image = "";
        String state = "";
        String created = "";
        String started = "";
        String finished = "";
        String exit = "";
        String error = "";
        String command = "";
        String entrypoint = "";
        String workdir = "";
        String restart = "";
        String ports = "";
        String mounts = "";
        String networks = "";
        String ip = "";
        String env = "";
        /** The command that would make this container again. */
        String runLine = "";
    }

    static ContainerInfo inspectContainer(int port, String id) {
        try {
            String body = get(port, API + "/containers/" + id + "/json");
            if (body == null) return null;
            JSONObject o = new JSONObject(body);
            JSONObject cfg = o.optJSONObject("Config");
            JSONObject host = o.optJSONObject("HostConfig");
            JSONObject state = o.optJSONObject("State");

            ContainerInfo info = new ContainerInfo();
            info.id = o.optString("Id", "");
            info.name = o.optString("Name", "").replaceFirst("^/", "");
            info.created = o.optString("Created", "");
            info.image = cfg == null ? "" : cfg.optString("Image", "");
            if (state != null) {
                info.state = state.optString("Status", "");
                info.started = state.optString("StartedAt", "");
                info.finished = state.optString("FinishedAt", "");
                info.error = state.optString("Error", "");
                if (!"running".equals(info.state)) {
                    info.exit = String.valueOf(state.optInt("ExitCode", 0));
                }
            }
            if (cfg != null) {
                info.command = join(cfg.optJSONArray("Cmd"), " ");
                info.entrypoint = join(cfg.optJSONArray("Entrypoint"), " ");
                info.workdir = cfg.optString("WorkingDir", "");
            }
            if (host != null) {
                JSONObject policy = host.optJSONObject("RestartPolicy");
                info.restart = policy == null ? "" : policy.optString("Name", "");
                info.mounts = join(host.optJSONArray("Binds"), "\n");
            }
            info.ports = portsText(o.optJSONObject("NetworkSettings"));

            JSONObject net = o.optJSONObject("NetworkSettings");
            JSONObject nets = net == null ? null : net.optJSONObject("Networks");
            if (nets != null) {
                StringBuilder names = new StringBuilder();
                for (java.util.Iterator<String> it = nets.keys(); it.hasNext(); ) {
                    String key = it.next();
                    if (names.length() > 0) names.append(", ");
                    names.append(key);
                    JSONObject one = nets.optJSONObject(key);
                    String ip = one == null ? "" : one.optString("IPAddress", "");
                    if (info.ip.isEmpty() && !ip.isEmpty()) info.ip = ip;
                }
                info.networks = names.toString();
            }

            // The image's own defaults, so the Environment we show and the
            // docker run we build are what THIS container adds rather than a
            // copy of everything the image already carried.
            JSONObject imageCfg = null;
            String imageId = o.optString("Image", "");
            if (!imageId.isEmpty()) {
                String ibody = get(port, API + "/images/" + imageId + "/json");
                if (ibody != null) imageCfg = new JSONObject(ibody).optJSONObject("Config");
            }
            info.env = join(minus(cfg == null ? null : cfg.optJSONArray("Env"),
                    imageCfg == null ? null : imageCfg.optJSONArray("Env")), "\n");
            info.runLine = runLine(o, cfg, host, imageCfg, info);
            return info;
        } catch (Exception e) {
            DexLog.warn("docker", "inspect container " + id + " failed", e);
            return null;
        }
    }

    /**
     * Rebuild the {@code docker run} that would produce this container.
     *
     * Only what was asked for: everything the image already specifies is left
     * out, so the line is the one someone would have typed rather than a dump
     * of the container's whole resolved configuration.
     */
    private static String runLine(JSONObject o, JSONObject cfg, JSONObject host,
                                  JSONObject imageCfg, ContainerInfo info) {
        StringBuilder sb = new StringBuilder("docker run -d");
        if (cfg != null && cfg.optBoolean("Tty", false)) sb.append(" -t");
        if (cfg != null && cfg.optBoolean("OpenStdin", false)) sb.append(" -i");
        if (!info.name.isEmpty()) sb.append(" --name ").append(quote(info.name));

        if (host != null) {
            JSONObject bindings = host.optJSONObject("PortBindings");
            if (bindings != null) {
                for (java.util.Iterator<String> it = bindings.keys(); it.hasNext(); ) {
                    String key = it.next(); // "80/tcp"
                    JSONArray list = bindings.optJSONArray(key);
                    for (int i = 0; list != null && i < list.length(); i++) {
                        JSONObject b = list.optJSONObject(i);
                        if (b == null) continue;
                        String hostIp = b.optString("HostIp", "");
                        if ("0.0.0.0".equals(hostIp)) hostIp = ""; // the default
                        String hostPort = b.optString("HostPort", "");
                        String guest = key.endsWith("/tcp")
                                ? key.substring(0, key.length() - 4) : key;
                        sb.append(" -p ").append(hostIp.isEmpty() ? "" : hostIp + ":")
                                .append(hostPort).append(':').append(guest);
                    }
                }
            }
            JSONArray binds = host.optJSONArray("Binds");
            for (int i = 0; binds != null && i < binds.length(); i++) {
                sb.append(" -v ").append(quote(binds.optString(i, "")));
            }
            JSONObject policy = host.optJSONObject("RestartPolicy");
            String name = policy == null ? "" : policy.optString("Name", "");
            if (!name.isEmpty() && !"no".equals(name)) {
                sb.append(" --restart ").append(name);
            }
        }

        for (String e : minus(cfg == null ? null : cfg.optJSONArray("Env"),
                imageCfg == null ? null : imageCfg.optJSONArray("Env"))) {
            sb.append(" -e ").append(quote(e));
        }

        String entry = cfg == null ? "" : join(cfg.optJSONArray("Entrypoint"), " ");
        String imageEntry = imageCfg == null ? "" : join(imageCfg.optJSONArray("Entrypoint"), " ");
        if (!entry.isEmpty() && !entry.equals(imageEntry)) {
            sb.append(" --entrypoint ").append(quote(entry));
        }

        sb.append(' ').append(info.image.isEmpty() ? o.optString("Image", "") : info.image);

        String cmd = cfg == null ? "" : join(cfg.optJSONArray("Cmd"), " ");
        String imageCmd = imageCfg == null ? "" : join(imageCfg.optJSONArray("Cmd"), " ");
        // Quoted per argument, unlike the plain join the Inspect sheet shows:
        // this line is meant to be pasted, and sh -c "echo hi; sleep 1" is four
        // arguments and a broken command once the quotes are gone.
        if (!cmd.isEmpty() && !cmd.equals(imageCmd)) {
            sb.append(' ').append(joinQuoted(cfg.optJSONArray("Cmd")));
        }
        return sb.toString();
    }

    /** The entries of {@code mine} that {@code base} does not already have. */
    private static List<String> minus(JSONArray mine, JSONArray base) {
        List<String> out = new ArrayList<>();
        if (mine == null) return out;
        List<String> skip = new ArrayList<>();
        for (int i = 0; base != null && i < base.length(); i++) skip.add(base.optString(i, ""));
        for (int i = 0; i < mine.length(); i++) {
            String v = mine.optString(i, "");
            if (!v.isEmpty() && !skip.contains(v)) out.add(v);
        }
        return out;
    }

    /** "8080->80/tcp" per published port, which is how people read them. */
    private static String portsText(JSONObject netSettings) {
        JSONObject ports = netSettings == null ? null : netSettings.optJSONObject("Ports");
        if (ports == null) return "";
        StringBuilder sb = new StringBuilder();
        for (java.util.Iterator<String> it = ports.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray list = ports.optJSONArray(key);
            if (sb.length() > 0) sb.append(", ");
            if (list == null || list.length() == 0) {
                sb.append(key);
                continue;
            }
            for (int i = 0; i < list.length(); i++) {
                JSONObject b = list.optJSONObject(i);
                if (b == null) continue;
                if (i > 0) sb.append(", ");
                sb.append(b.optString("HostPort", "")).append("->").append(key);
            }
        }
        return sb.toString();
    }

    private static String joinQuoted(JSONArray arr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; arr != null && i < arr.length(); i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(quote(arr.optString(i, "")));
        }
        return sb.toString();
    }

    private static String quote(String s) {
        return s.indexOf(' ') < 0 ? s : "\"" + s + "\"";
    }

    // ── image actions ──

    // ── image actions ──

    /**
     * Everything the Details sheet shows, flattened here.
     *
     * Flattened rather than handing the window a JSONObject: the shape of the
     * engine JSON is this file's business, and a version bump that moves a
     * field should break one place, not the UI.
     */
    static final class ImageInfo {
        String id = "";
        String tags = "";
        String digest = "";
        String created = "";
        String platform = "";
        String entrypoint = "";
        String command = "";
        String workdir = "";
        String ports = "";
        String env = "";
        int layers;
        long size;
    }

    static ImageInfo inspect(int port, String ref) {
        try {
            String body = get(port, API + "/images/" + ref + "/json");
            if (body == null) return null;
            JSONObject o = new JSONObject(body);
            ImageInfo info = new ImageInfo();
            info.id = o.optString("Id", "");
            info.created = o.optString("Created", "");
            info.size = o.optLong("Size", 0);
            String os = o.optString("Os", "");
            String arch = o.optString("Architecture", "");
            info.platform = (os + "/" + arch).replaceAll("^/|/$", "");
            info.tags = join(o.optJSONArray("RepoTags"), ", ");
            info.digest = firstOf(o.optJSONArray("RepoDigests"));
            JSONObject cfg = o.optJSONObject("Config");
            if (cfg != null) {
                info.entrypoint = join(cfg.optJSONArray("Entrypoint"), " ");
                info.command = join(cfg.optJSONArray("Cmd"), " ");
                info.workdir = cfg.optString("WorkingDir", "");
                info.env = join(cfg.optJSONArray("Env"), "\n");
                JSONObject exposed = cfg.optJSONObject("ExposedPorts");
                if (exposed != null) {
                    StringBuilder sb = new StringBuilder();
                    for (java.util.Iterator<String> it = exposed.keys(); it.hasNext(); ) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(it.next());
                    }
                    info.ports = sb.toString();
                }
            }
            JSONObject rootfs = o.optJSONObject("RootFS");
            if (rootfs != null) {
                JSONArray layers = rootfs.optJSONArray("Layers");
                info.layers = layers == null ? 0 : layers.length();
            }
            return info;
        } catch (Exception e) {
            DexLog.warn("docker", "inspect " + ref + " failed", e);
            return null;
        }
    }

    /**
     * @return null when the image is gone, else what the engine objected to —
     *         nearly always a container still using it, which is worth reading
     *         rather than being told "that failed".
     */
    static String removeImage(int port, String ref) {
        return request(port, API + "/images/" + ref + "?force=1",
                "DELETE", null, TIMEOUT_MS).error;
    }

    /** What the Run sheet collected. Everything but the image is optional. */
    static final class RunSpec {
        String image = "";
        String name = "";
        String command = "";
        final List<String> ports = new ArrayList<>();
        final List<String> volumes = new ArrayList<>();
        final List<String> env = new ArrayList<>();
    }

    /**
     * Create a container from an image and start it — {@code docker run -d}.
     *
     * Detached, always: this window has no terminal to attach to, and the one
     * console it does have belongs to the VM rather than to a container.
     *
     * @return null on success, else the engine's own message.
     */
    static String run(int port, RunSpec spec) {
        try {
            JSONObject body = new JSONObject();
            body.put("Image", spec.image);
            List<String> cmd = splitArgs(spec.command);
            if (!cmd.isEmpty()) body.put("Cmd", new JSONArray(cmd));
            if (!spec.env.isEmpty()) body.put("Env", new JSONArray(spec.env));

            JSONObject exposed = new JSONObject();
            JSONObject bindings = new JSONObject();
            for (String p : spec.ports) {
                String[] parts = p.split(":");
                String host = parts[0].trim();
                String guest = (parts.length > 1 ? parts[1] : parts[0]).trim();
                String proto = "tcp";
                int slash = guest.indexOf('/');
                if (slash >= 0) {
                    proto = guest.substring(slash + 1).trim();
                    guest = guest.substring(0, slash).trim();
                }
                if (host.isEmpty() || guest.isEmpty()) continue;
                String key = guest + "/" + proto;
                exposed.put(key, new JSONObject());
                bindings.put(key, new JSONArray().put(
                        new JSONObject().put("HostIp", "0.0.0.0").put("HostPort", host)));
            }
            if (exposed.length() > 0) body.put("ExposedPorts", exposed);

            JSONObject host = new JSONObject();
            if (bindings.length() > 0) host.put("PortBindings", bindings);
            if (!spec.volumes.isEmpty()) host.put("Binds", new JSONArray(spec.volumes));
            body.put("HostConfig", host);

            String path = API + "/containers/create";
            if (!spec.name.isEmpty()) path += "?name=" + enc(spec.name);
            Reply created = request(port, path, "POST", body.toString(), TIMEOUT_MS);
            if (created.error != null) return created.error;
            String id = new JSONObject(created.body).optString("Id", "");
            if (id.isEmpty()) return created.body;
            // A container that was created but will not start is reported
            // rather than swallowed: it exists either way, and it is in the
            // list by the time the message is read.
            return request(port, API + "/containers/" + id + "/start",
                    "POST", null, TIMEOUT_MS).error;
        } catch (Exception e) {
            DexLog.warn("docker", "run failed", e);
            return String.valueOf(e.getMessage());
        }
    }

    /**
     * Re-pull an image, the way Docker Desktop's Pull does.
     *
     * The engine streams progress as newline-delimited JSON and only tells you
     * it went wrong in the body, so the status code is not the answer here —
     * the stream is read to the end and any error object in it wins.
     *
     * @return null on success, else the engine's message.
     */
    static String pull(int port, String ref) {
        String repo = ref;
        String tag = "latest";
        int colon = ref.lastIndexOf(':');
        if (colon > ref.lastIndexOf('/')) {
            repo = ref.substring(0, colon);
            tag = ref.substring(colon + 1);
        }
        Reply reply = request(port, API + "/images/create?fromImage=" + enc(repo)
                + "&tag=" + enc(tag), "POST", null, PULL_TIMEOUT_MS);
        if (reply.error != null) return reply.error;
        String out = reply.body;
        int at = out.lastIndexOf("\"error\"");
        if (at < 0) return null;
        try {
            int start = out.lastIndexOf('{', at);
            int end = out.indexOf('}', at);
            if (start >= 0 && end > start) {
                return new JSONObject(out.substring(start, end + 1))
                        .optString("error", out.substring(at));
            }
        } catch (Exception ignored) {
        }
        return out.substring(at);
    }

    /** The containers made from one image, running or not. */
    static List<Container> containersOf(List<Container> all, Image im) {
        List<Container> out = new ArrayList<>();
        if (all == null) return out;
        String shortId = im.shortId();
        for (Container c : all) {
            String ref = c.image == null ? "" : c.image;
            if (ref.isEmpty()) continue; // else endsWith("") matches everything
            if (ref.equals(im.tag) || ref.startsWith(shortId)
                    || ref.contains(shortId) || im.id.endsWith(ref)) {
                out.add(c);
            }
        }
        return out;
    }

    /**
     * Split a command line the way a shell would, minus the shell.
     *
     * Quotes matter here: {@code node -e "console.log(1)"} is the shape of half
     * the commands anyone types into the Run sheet, and splitting it on spaces
     * hands the engine four arguments and a syntax error.
     */
    static List<String> splitArgs(String line) {
        List<String> out = new ArrayList<>();
        if (line == null) return out;
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        boolean any = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (quote != 0) {
                if (ch == quote) quote = 0;
                else cur.append(ch);
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                any = true;
            } else if (Character.isWhitespace(ch)) {
                if (any || cur.length() > 0) out.add(cur.toString());
                cur.setLength(0);
                any = false;
            } else {
                cur.append(ch);
            }
        }
        if (any || cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String i : items) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(i);
        }
        return sb.toString();
    }

    private static String join(JSONArray arr, String sep) {
        if (arr == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(arr.optString(i, ""));
        }
        return sb.toString();
    }

    private static String firstOf(JSONArray arr) {
        return arr == null || arr.length() == 0 ? "" : arr.optString(0, "");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
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

    /**
     * One response, with "did it work" kept apart from "what did it say".
     *
     * The two are not the same string and conflating them is a bug waiting to
     * happen: a successful image delete answers 200 with a JSON array of what
     * it untagged, which a caller reading the body as a complaint would put in
     * front of the user as an error.
     */
    private static final class Reply {
        /** null when the engine was happy. */
        String error;
        String body = "";
    }

    /**
     * One request, with the engine's own words on the way out.
     *
     * The older {@link #post}/{@link #delete} pair answers true or false, which
     * is all a Start button needs. Anything the user typed — a name that is
     * taken, a port that is busy, an image a container still holds — deserves
     * the message the engine wrote rather than "that failed".
     */
    private static Reply request(int port, String path, String method, String body,
                                 int readTimeout) {
        Reply reply = new Reply();
        try {
            HttpURLConnection c = open(port, path, method, readTimeout);
            try {
                byte[] out = body == null
                        ? new byte[0] : body.getBytes("UTF-8");
                if ("POST".equals(method) || "PUT".equals(method)) {
                    c.setRequestProperty("Content-Type", "application/json");
                    c.setFixedLengthStreamingMode(out.length);
                    if (out.length > 0) {
                        c.setDoOutput(true);
                        OutputStream os = c.getOutputStream();
                        os.write(out);
                        os.flush();
                    }
                }
                int rc = c.getResponseCode();
                InputStream in = rc / 100 == 2 ? c.getInputStream() : c.getErrorStream();
                reply.body = in == null ? "" : read(in);
                if (rc / 100 != 2 && rc != 304) reply.error = messageOf(reply.body, rc);
                return reply;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            DexLog.warn("docker", method + " " + path + " failed", e);
            String why = e.getMessage();
            reply.error = why == null ? e.getClass().getSimpleName() : why;
            return reply;
        }
    }

    /** The engine puts its complaint in {@code message}; fall back to the code. */
    private static String messageOf(String body, int rc) {
        try {
            if (body != null && body.trim().startsWith("{")) {
                String m = new JSONObject(body).optString("message", "");
                if (!m.isEmpty()) return m;
            }
        } catch (Exception ignored) {
        }
        return body == null || body.trim().isEmpty() ? ("HTTP " + rc) : body.trim();
    }

    private static HttpURLConnection open(int port, String path, String method)
            throws Exception {
        return open(port, path, method, TIMEOUT_MS);
    }

    private static HttpURLConnection open(int port, String path, String method,
                                          int readTimeout) throws Exception {
        // 127.0.0.1 spelled out rather than "localhost": the resolver would
        // otherwise be in this path, and it has been known to answer with the
        // IPv6 loopback that QEMU's forward is not listening on.
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(readTimeout);
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
