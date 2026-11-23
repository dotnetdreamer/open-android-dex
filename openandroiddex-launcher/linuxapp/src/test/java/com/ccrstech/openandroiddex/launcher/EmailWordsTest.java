package com.ccrstech.openandroiddex.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The words of "Email me when an address changes" (the em_ strings), in all
 * nine languages, held to the app's plain-language rules: every word in every
 * language, the same placeholders, no dashes, no full stop at the end of a
 * line that is a line (a status, a change, a headline), one at the end of a
 * paragraph, and in English no "it", "they" or "them" standing in for a thing.
 */
public class EmailWordsTest {

    private static final String[] LANGUAGES = {"values", "values-ar", "values-de", "values-es", "values-fr",
            "values-gu", "values-hi", "values-pt", "values-zh"};

    /** Paragraphs: several sentences, each ending as a sentence does. Everything else is a line. */
    private static boolean paragraph(String key) {
        return key.equals("em_set_sub") || (key.startsWith("em_note_") && !key.equals("em_note_title"))
                || key.equals("em_set_links_sub")
                || key.equals("em_test_banner") || key.startsWith("em_how_") || key.equals("em_private")
                || key.equals("em_footer");
    }

    private static File res() {
        File here = new File("../app/src/main/res");
        if (!here.isDirectory()) here = new File("openandroiddex-launcher/app/src/main/res");
        assertTrue("the resources, from " + new File(".").getAbsolutePath(), here.isDirectory());
        return here;
    }

    private static Map<String, String> words(String language) throws Exception {
        File f = new File(res(), language + "/strings.xml");
        Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f).getDocumentElement();
        NodeList list = root.getElementsByTagName("string");
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < list.getLength(); i++) {
            Element e = (Element) list.item(i);
            String name = e.getAttribute("name");
            if (name.startsWith("em_")) out.put(name, e.getTextContent().replace("\\'", "'").replace("\\\"", "\""));
        }
        return out;
    }

    private static TreeSet<String> placeholders(String s) {
        TreeSet<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("%(\\d+)\\$([sd])").matcher(s);
        while (m.find()) out.add(m.group(1) + m.group(2));
        return out;
    }

    @Test
    public void everyLanguage_hasEveryWord_withTheSamePlaceholders() throws Exception {
        Map<String, String> english = words("values");
        assertTrue("the block is there", english.size() > 100);
        for (String lang : LANGUAGES) {
            Map<String, String> w = words(lang);
            assertEquals(lang + " has exactly the English keys", english.keySet(), w.keySet());
            for (Map.Entry<String, String> e : english.entrySet()) {
                assertEquals(lang + " " + e.getKey() + ": the same placeholders", placeholders(e.getValue()),
                        placeholders(w.get(e.getKey())));
                assertFalse(lang + " " + e.getKey() + " is empty", w.get(e.getKey()).trim().isEmpty());
            }
        }
    }

    @Test
    public void noDashes_inAnyLanguage() throws Exception {
        for (String lang : LANGUAGES) {
            for (Map.Entry<String, String> e : words(lang).entrySet()) {
                assertFalse(lang + " " + e.getKey() + " has a dash: " + e.getValue(),
                        e.getValue().matches("(?s).*[\u2012\u2013\u2014\u2015].*") || e.getValue().contains(" - "));
            }
        }
    }

    @Test
    public void lines_endWithoutAFullStop_paragraphsWithOne() throws Exception {
        for (String lang : LANGUAGES) {
            for (Map.Entry<String, String> e : words(lang).entrySet()) {
                String v = e.getValue().trim();
                char last = v.charAt(v.length() - 1);
                boolean stop = last == '.' || last == '\u3002' || last == '\u0964';
                if (paragraph(e.getKey())) {
                    assertTrue(lang + " " + e.getKey() + " is a paragraph and ends as one: " + v, stop);
                } else {
                    assertFalse(lang + " " + e.getKey() + " is a line and ends with no full stop: " + v, stop);
                }
            }
        }
    }

    @Test
    public void english_namesTheThing() throws Exception {
        Pattern pronoun = Pattern.compile("\\b(it|its|they|them|their|theirs|those|these|themselves|itself)\\b",
                Pattern.CASE_INSENSITIVE);
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> e : words("values").entrySet()) {
            Matcher m = pronoun.matcher(e.getValue());
            if (m.find()) found.add(e.getKey() + ": " + e.getValue());
        }
        assertTrue("name the thing instead: " + found, found.isEmpty());
    }

    @Test
    public void theSignInPage_namesTheThingToo() throws Exception {
        File assets = new File(res().getParentFile(), "assets/linux/browser");
        for (String f : new String[]{"signin.html", "signin.js"}) {
            String text = new String(java.nio.file.Files.readAllBytes(new File(assets, f).toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            // The words a person reads: quoted strings and the page's text.
            Matcher q = Pattern.compile("'([A-Z][^'\\n]{12,})'|\"([A-Z][^\"\\n]{12,})\"|`([A-Z][^`\\n]{12,})`|>([A-Z][^<]{12,})<")
                    .matcher(text);
            while (q.find()) {
                String words = q.group(1) != null ? q.group(1) : q.group(2) != null ? q.group(2) : q.group(3) != null ? q.group(3) : q.group(4);
                assertFalse(f + ": " + words, Pattern.compile("\\b(it|its|they|them|their|those|these)\\b",
                        Pattern.CASE_INSENSITIVE).matcher(words).find());
                assertFalse(f + " has a dash: " + words, words.matches(".*[\u2013\u2014].*"));
            }
        }
    }
}
