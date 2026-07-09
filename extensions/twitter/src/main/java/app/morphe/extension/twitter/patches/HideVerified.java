/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.morphe.extension.twitter.patches;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Hides tweets and replies from verified accounts (blue check / X Premium,
 * including a hidden checkmark, plus gold/grey org and legacy verified).
 *
 * It works on the raw JSON server response, hooked at the same Jackson
 * createParser(InputStream) point as "Log server response", BEFORE the app parses
 * it into its obfuscated model. That makes it independent of per-version obfuscation
 * and applies uniformly to the home timeline, conversations/replies, and search.
 *
 * Fail-safe by construction: if anything is unexpected the original bytes are
 * returned untouched, so a response is never corrupted.
 */
public final class HideVerified {

    /** Wraps the response stream, filtering timeline JSON in place. */
    public static InputStream filter(InputStream in) {
        if (in == null) return null;
        try {
            byte[] raw = readAll(in);
            in.close();
            String json = new String(raw, StandardCharsets.UTF_8);
            // Cheap gate: only pay for parsing when a verified flag is present.
            if (json.indexOf("is_blue_verified") < 0
                    && json.indexOf("verified_type") < 0
                    && json.indexOf("\"verified\"") < 0) {
                return new ByteArrayInputStream(raw);
            }
            String filtered = filterJson(json);
            if (filtered == json) return new ByteArrayInputStream(raw);
            return new ByteArrayInputStream(filtered.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // Never break the app: fall back to the untouched stream.
            return in;
        }
    }

    /** Returns filtered JSON, or the same string reference if nothing changed. */
    static String filterJson(String input) {
        try {
            Object root = new JSONTokener(input).nextValue();
            int removed = walk(root);
            return removed > 0 ? root.toString() : input;
        } catch (Throwable t) {
            return input;
        }
    }

    private static int walk(Object node) {
        int removed = 0;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();   // Android org.json exposes keys(), not keySet()
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = o.opt(k);
                if ("entries".equals(k) && v instanceof JSONArray) {
                    removed += filterEntries((JSONArray) v);
                }
                removed += walk(v);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) removed += walk(a.opt(i));
        }
        return removed;
    }

    private static int filterEntries(JSONArray entries) {
        int removed = 0;
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            JSONObject content = entry.optJSONObject("content");
            if (content == null) continue;

            JSONObject itemContent = content.optJSONObject("itemContent");
            if (itemContent != null && tweetAuthorVerified(itemContent.optJSONObject("tweet_results"))) {
                entries.remove(i);
                removed++;
                continue;
            }

            JSONArray items = content.optJSONArray("items");
            if (items != null) {
                for (int j = items.length() - 1; j >= 0; j--) {
                    JSONObject moduleItem = items.optJSONObject(j);
                    JSONObject item = moduleItem == null ? null : moduleItem.optJSONObject("item");
                    JSONObject ic = item == null ? null : item.optJSONObject("itemContent");
                    if (ic != null && tweetAuthorVerified(ic.optJSONObject("tweet_results"))) {
                        items.remove(j);
                        removed++;
                    }
                }
                if (items.length() == 0) entries.remove(i);
            }
        }
        return removed;
    }

    private static boolean tweetAuthorVerified(JSONObject tweetResults) {
        if (tweetResults == null) return false;
        JSONObject result = tweetResults.optJSONObject("result");
        if (result == null) return false;
        if (result.has("tweet")) {
            JSONObject inner = result.optJSONObject("tweet");
            if (inner != null) result = inner;
        }
        JSONObject core = result.optJSONObject("core");
        if (core == null) return false;
        JSONObject userResults = core.optJSONObject("user_results");
        if (userResults == null) return false;
        return userVerified(userResults.optJSONObject("result"));
    }

    private static boolean userVerified(JSONObject user) {
        if (user == null) return false;
        // is_blue_verified stays true in the API even when the badge is hidden in-UI.
        // ext_is_blue_verified is the same signal on the older REST user shape.
        if (user.optBoolean("is_blue_verified", false)) return true;
        if (user.optBoolean("ext_is_blue_verified", false)) return true;
        String vt = user.optString("verified_type", "");
        if (vt.length() > 0 && !vt.equalsIgnoreCase("None")) return true;
        JSONObject legacy = user.optJSONObject("legacy");
        return legacy != null && legacy.optBoolean("verified", false);
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 16);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private HideVerified() {}
}
