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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Hides tweets and replies from verified accounts (blue check / X Premium,
 * including a hidden checkmark, plus gold/grey org and legacy verified), as well
 * as tweets that retweet or quote-tweet a verified account, and replies TO a
 * verified account (even when the replier is not verified).
 *
 * It works on the raw JSON server response, hooked at the same Jackson
 * createParser(InputStream) point as "Log server response", BEFORE the app parses
 * it into its obfuscated model. That makes it independent of per-version obfuscation
 * and covers the home timeline, profiles, conversations/replies, and search alike.
 *
 * Fail-safe by construction: if anything is unexpected the original bytes are
 * returned untouched, so a response is never corrupted.
 */
public final class HideVerified {

    /** Wraps the response stream, filtering timeline JSON in place. */
    public static InputStream filter(InputStream in) {
        if (in == null) return null;
        byte[] raw;
        try {
            raw = readAll(in);
            in.close();
        } catch (Throwable t) {
            // Could not buffer the stream; hand back the original (nothing better available).
            return in;
        }
        try {
            String json = new String(raw, StandardCharsets.UTF_8);
            // Cheap gate: only pay for parsing when a verified flag is present.
            if (json.indexOf("is_blue_verified") < 0
                    && json.indexOf("verified_type") < 0
                    && json.indexOf("\"verified\"") < 0) {
                return new ByteArrayInputStream(raw);
            }
            String filtered = filterJson(json);
            if (filtered == null || filtered == json) return new ByteArrayInputStream(raw);
            return new ByteArrayInputStream(filtered.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // Never break the app: hand back the original bytes, re-readable.
            return new ByteArrayInputStream(raw);
        }
    }

    /** Returns filtered JSON, or the same string reference if nothing changed. */
    static String filterJson(String input) {
        try {
            Object root = new JSONTokener(input).nextValue();
            // First pass: every verified user's id, so replies TO them can be matched.
            Set<String> verifiedIds = new HashSet<>();
            collectVerifiedIds(root, verifiedIds);
            int removed = walk(root, verifiedIds);
            if (removed <= 0) return input;
            String out = root.toString();   // Android JSONObject.toString() can return null on internal error
            return out != null ? out : input;
        } catch (Throwable t) {
            return input;
        }
    }

    private static void collectVerifiedIds(Object node, Set<String> ids) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (userVerified(o)) {
                String id = o.optString("rest_id", "");
                if (!id.isEmpty()) ids.add(id);
            }
            Iterator<String> it = o.keys();
            while (it.hasNext()) collectVerifiedIds(o.opt(it.next()), ids);
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collectVerifiedIds(a.opt(i), ids);
        }
    }

    private static int walk(Object node, Set<String> ids) {
        int removed = 0;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            List<String> keys = new ArrayList<>();
            Iterator<String> it = o.keys();   // Android org.json exposes keys(), not keySet()
            while (it.hasNext()) keys.add(it.next());
            for (String k : keys) {
                Object v = o.opt(k);
                if ("instructions".equals(k) && v instanceof JSONArray) {
                    // Singular pinned/replaced entries and module pagination live here,
                    // not inside an "entries" array.
                    removed += filterInstructions((JSONArray) v, ids);
                } else if ("entries".equals(k) && v instanceof JSONArray) {
                    removed += filterEntries((JSONArray) v, ids);
                }
                removed += walk(v, ids);
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) removed += walk(a.opt(i), ids);
        }
        return removed;
    }

    // TimelinePinEntry / TimelineReplaceEntry carry a single "entry"; TimelineAddToModule
    // carries "moduleItems". "entries" arrays inside instructions are handled by walk().
    private static int filterInstructions(JSONArray instructions, Set<String> ids) {
        int removed = 0;
        for (int i = instructions.length() - 1; i >= 0; i--) {
            JSONObject instr = instructions.optJSONObject(i);
            if (instr == null) continue;
            JSONObject entry = instr.optJSONObject("entry");
            if (entry != null && entryHidden(entry, ids)) {
                instructions.remove(i);
                removed++;
                continue;
            }
            JSONArray moduleItems = instr.optJSONArray("moduleItems");
            if (moduleItems != null) removed += filterItems(moduleItems, ids);
        }
        return removed;
    }

    private static int filterEntries(JSONArray entries, Set<String> ids) {
        int removed = 0;
        for (int i = entries.length() - 1; i >= 0; i--) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;

            // A single tweet entry (content.content on current X, content.itemContent on older).
            if (entryHidden(entry, ids)) {
                entries.remove(i);
                removed++;
                continue;
            }

            // A module of items (conversations, carousels): items[].item.content|itemContent.
            JSONObject content = entry.optJSONObject("content");
            JSONArray items = content == null ? null : content.optJSONArray("items");
            if (items != null) {
                int r = filterItems(items, ids);
                removed += r;
                // Only drop the module if WE emptied it, not if the server sent it empty.
                if (r > 0 && items.length() == 0) entries.remove(i);
            }
        }
        return removed;
    }

    /** Removes hidden items from an items[]/moduleItems[] array. */
    private static int filterItems(JSONArray items, Set<String> ids) {
        int removed = 0;
        for (int j = items.length() - 1; j >= 0; j--) {
            JSONObject moduleItem = items.optJSONObject(j);
            JSONObject item = moduleItem == null ? null : moduleItem.optJSONObject("item");
            JSONObject ic = item == null ? null : firstObject(item, "content", "itemContent");
            if (ic != null && itemHidden(ic, ids)) {
                items.remove(j);
                removed++;
            }
        }
        return removed;
    }

    /** True if the single tweet in this entry should be hidden. */
    private static boolean entryHidden(JSONObject entry, Set<String> ids) {
        JSONObject content = entry.optJSONObject("content");
        if (content == null) return false;
        JSONObject itemContent = firstObject(content, "content", "itemContent");
        return itemContent != null && itemHidden(itemContent, ids);
    }

    private static boolean itemHidden(JSONObject itemContent, Set<String> ids) {
        // Current X calls this tweetResult; older builds used tweet_results.
        JSONObject tweetResult = firstObject(itemContent, "tweetResult", "tweet_results");
        return tweetResult != null && shouldHide(tweetResult.optJSONObject("result"), ids, 0);
    }

    // A tweet is hidden if it is FROM a verified account, REPLIES TO one, or RETWEETS /
    // QUOTES one (recursively, so a retweet of a quote of a verified account is caught too).
    private static boolean shouldHide(JSONObject result, Set<String> ids, int depth) {
        if (result == null || depth > 4) return false;
        if (result.has("tweet")) {
            JSONObject inner = result.optJSONObject("tweet");
            if (inner != null) result = inner;
        }
        // The tweet's own author.
        if (userVerified(authorOf(result))) return true;
        JSONObject legacy = result.optJSONObject("legacy");
        if (legacy != null) {
            // A reply to a verified account (the replier itself may be unverified).
            String replyTo = legacy.optString("in_reply_to_user_id_str", "");
            if (!replyTo.isEmpty() && ids.contains(replyTo)) return true;
            // A retweet of a hidden tweet.
            JSONObject rt = legacy.optJSONObject("retweeted_status_result");
            if (rt != null && shouldHide(rt.optJSONObject("result"), ids, depth + 1)) return true;
        }
        // A quote of a hidden tweet.
        JSONObject quoted = result.optJSONObject("quoted_status_result");
        if (quoted != null && shouldHide(quoted.optJSONObject("result"), ids, depth + 1)) return true;
        return false;
    }

    /** result.core.user_result.result (current X) or result.core.user_results.result (older). */
    private static JSONObject authorOf(JSONObject result) {
        if (result == null) return null;
        JSONObject core = result.optJSONObject("core");
        if (core == null) return null;
        JSONObject userResults = firstObject(core, "user_result", "user_results");
        if (userResults == null) return null;
        return userResults.optJSONObject("result");
    }

    /** First present child object among the given keys, or null. */
    private static JSONObject firstObject(JSONObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            JSONObject v = o.optJSONObject(k);
            if (v != null) return v;
        }
        return null;
    }

    private static boolean userVerified(JSONObject user) {
        if (user == null) return false;
        // is_blue_verified stays true in the API even when the badge is hidden in-UI.
        // ext_is_blue_verified is the same signal on the older REST user shape.
        if (user.optBoolean("is_blue_verified", false)) return true;
        if (user.optBoolean("ext_is_blue_verified", false)) return true;
        // Android org.json's optString turns an explicit JSON null into the string "null";
        // guard with isNull so an unset verified_type is not misread as a Business/Gov badge.
        if (!user.isNull("verified_type")) {
            String vt = user.optString("verified_type", "");
            if (vt.length() > 0 && !vt.equalsIgnoreCase("None") && !vt.equalsIgnoreCase("null")) return true;
        }
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
