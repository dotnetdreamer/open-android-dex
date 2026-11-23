package com.ccrstech.openandroiddex.launcher;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A message as a mail app reads it, for the tests: the headers (encoded words undone), the text part and the HTML part. */
final class MailParse {

    final Map<String, String> headers = new LinkedHashMap<>();
    String text = "";
    String html = "";
    /** The raw lines, to check their length and their characters. */
    final String raw;

    private MailParse(String raw) {
        this.raw = raw;
    }

    static MailParse of(byte[] data) {
        String raw = new String(data, StandardCharsets.ISO_8859_1);
        MailParse m = new MailParse(raw);
        int split = raw.indexOf("\r\n\r\n");
        String head = raw.substring(0, split);
        String body = raw.substring(split + 4);
        // Folded lines (a CRLF and a space) joined again.
        for (String line : head.replace("\r\n ", " ").split("\r\n")) {
            int colon = line.indexOf(':');
            m.headers.put(line.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT), line.substring(colon + 1).trim());
        }
        String type = m.headers.getOrDefault("content-type", "");
        Matcher b = Pattern.compile("boundary=\"([^\"]+)\"").matcher(type);
        if (b.find()) {
            String boundary = "--" + b.group(1);
            for (String part : body.split(Pattern.quote(boundary))) {
                int ps = part.indexOf("\r\n\r\n");
                if (ps < 0) continue;
                String ph = part.substring(0, ps).toLowerCase(java.util.Locale.ROOT);
                String pb = part.substring(ps + 4);
                String decoded = new String(Base64.getMimeDecoder().decode(pb.trim()), StandardCharsets.UTF_8);
                if (ph.contains("text/plain")) m.text = decoded;
                if (ph.contains("text/html")) m.html = decoded;
            }
        } else {
            m.text = new String(Base64.getMimeDecoder().decode(body.trim()), StandardCharsets.UTF_8);
        }
        return m;
    }

    /** A header as a person sees it: RFC 2047 encoded words decoded. */
    String header(String name) {
        return words(headers.getOrDefault(name.toLowerCase(java.util.Locale.ROOT), ""));
    }

    static String words(String value) {
        Matcher w = Pattern.compile("=\\?UTF-8\\?B\\?([A-Za-z0-9+/=]*)\\?=").matcher(value);
        StringBuilder out = new StringBuilder();
        int last = 0;
        boolean any = false;
        while (w.find()) {
            String between = value.substring(last, w.start());
            // Space between two encoded words is not part of the text.
            if (!(any && between.trim().isEmpty())) out.append(between);
            out.append(new String(Base64.getDecoder().decode(w.group(1)), StandardCharsets.UTF_8));
            last = w.end();
            any = true;
        }
        out.append(value.substring(last));
        return out.toString();
    }
}
