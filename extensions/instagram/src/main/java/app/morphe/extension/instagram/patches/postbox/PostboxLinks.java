package app.morphe.extension.instagram.patches.postbox;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Postbox (Vantage fork only): decides whether a link may open (L2, D12).
 *
 * Pure Java, no Android types, so the whole table runs under JUnit (PostboxLinksTest). The
 * runtime side is {@link PostboxLinkRouter}, which feeds it the parts of an activity's data URI.
 *
 * Postbox opens single items (a post, a reel, a story, a profile, a DM thread) and the account
 * pages, and nothing that is a feed: Home, Explore, the Reels tab, hashtag, location, search
 * and audio pages are bounced to the inbox. A link to a single reel is opened through its
 * single-post form ({@link #reelAsPostLink}), because the Reels viewer cannot load in Postbox.
 *
 * Defaults for what is not listed, per kind of link:
 *
 * - {@code instagram://} with a host this table does not know: BOUNCE. These are Instagram's
 *   own routes and new ones appear with every release; an unknown one may well be a feed, and a
 *   wrongly bounced one only costs a trip to the inbox and a "bounce" line in logcat that says
 *   which host to add.
 * - {@code https://(www.|m.)instagram.com} and {@code instagr.am} with a path this table does
 *   not know (reserved first segment, or more than a profile's worth of segments): BOUNCE, for
 *   the same reason. One unreserved segment is a username and opens the profile.
 * - Other {@code *.instagram.com} hosts (help, about, accountscenter, the l.instagram.com link
 *   shim, ...): only the paths that name a forbidden surface are bounced. These hosts serve web
 *   pages, not app surfaces, and the in-app browser carries them as its data URI; a default-deny
 *   here would break the Help Centre, Accounts Centre and every external link opened from a DM.
 * - Every other host and every other scheme (content://, file://, the instagram-stories://
 *   share scheme, any web page): ALLOW. They are not Instagram navigation and not ours to
 *   police; they are how media is shared in and how the in-app browser opens.
 * - No link at all (no scheme): ALLOW. This is what makes a bounce unable to bounce itself:
 *   the intent a bounce produces carries no data URI.
 */
public final class PostboxLinks {
    public enum Verdict { ALLOW, BOUNCE }

    // ---- hosts ------------------------------------------------------------------------------

    /** Hosts whose paths are Instagram app links: classified strictly, unknown paths bounce. */
    private static final Set<String> APP_LINK_HOSTS = setOf(
            "instagram.com", "www.instagram.com", "m.instagram.com", "instagr.am", "www.instagr.am");

    /** Instagram's short-link host. Only its DM forms are let through. */
    private static final Set<String> SHORT_LINK_HOSTS = setOf("ig.me", "www.ig.me");

    /** Any other host under these suffixes is an Instagram web property (lenient, see above). */
    private static final String[] FAMILY_SUFFIXES = {".instagram.com", ".instagr.am", ".ig.me"};

    // ---- web paths --------------------------------------------------------------------------

    /**
     * First segments that name an app surface. On the lenient hosts these (and only these) are
     * still run through the strict table.
     */
    private static final Set<String> SURFACE_ROOTS = setOf(
            "explore", "reels", "reel", "clips", "tags", "locations", "location", "search", "topics",
            "discover", "tv", "igtv", "web", "feed", "home", "audio", "music", "hashtag", "_n");

    /** Sub-pages of /reels/ that are listings, not a reel's shortcode. */
    private static final Set<String> REELS_PAGES = setOf(
            "audio", "music", "videos", "video", "trending", "explore", "popular", "templates",
            "effects", "saved", "liked", "friends", "following", "tagged", "new", "home", "feed",
            "tab", "discover", "create", "drafts", "insights", "for_you", "foryou", "search");

    /** Second segments of /stories/ that are discovery pages, not a username. */
    private static final Set<String> STORIES_PAGES = setOf(
            "tags", "locations", "location", "explore", "live", "popular", "discover", "search",
            "direct", "archive");

    /** The only /accounts/ pages that are discovery ("people you may know"), not management. */
    private static final Set<String> ACCOUNTS_DISCOVERY = setOf(
            "suggested", "suggestions", "discover", "discover_people", "explore", "contact_history");

    /**
     * Account, login and security roots besides /accounts/. A logged-out or settings flow needs
     * these (login, 2FA, checkpoint challenges, email preferences, data download), and none of
     * them is a feed.
     */
    private static final Set<String> ACCOUNT_ROOTS = setOf(
            "challenge", "oauth", "emails", "session", "auth_platform", "two_factor", "login",
            "logout", "signup", "emailsignup", "password", "settings", "your_activity", "download");

    /** Static information pages (terms, privacy, help). Web pages, linked from Settings > About. */
    private static final Set<String> INFO_ROOTS = setOf("about", "legal", "privacy", "terms", "help");

    /**
     * Every other first segment that is an Instagram route and therefore never a username.
     * A single-segment path is otherwise a profile, so anything missing here that Instagram
     * routes somewhere would be let through as one; when in doubt a word belongs in this set
     * (the cost is that an account with that exact name bounces).
     */
    private static final Set<String> RESERVED_ROOTS = setOf(
            // surfaces Postbox removes
            "explore", "tags", "locations", "location", "search", "topics", "discover", "clips",
            "igtv", "feed", "home", "audio", "music", "hashtag", "hashtags", "places", "people",
            "popular", "trending", "suggested", "channel", "channels", "guide", "guides", "shop",
            "shopping", "live", "saved", "archive", "notifications", "activity", "create", "threads",
            // plumbing and non-user-facing endpoints
            "web", "api", "graphql", "static", "ajax", "data", "embed", "embed.js", "n", "lite",
            "ar", "invites", "qr", "nametag", "fxcal", "direct_v2", "accountscenter", "security",
            "favicon.ico", "robots.txt", "sitemap.xml", "manifest.json",
            // corporate pages
            "developer", "developers", "press", "support", "safety", "community", "blog", "jobs",
            "brand", "business", "creators", "ads");

    /** /<user>/<this> pages that are part of a profile Postbox keeps. Grids (tagged, reels, ...) are not. */
    private static final Set<String> PROFILE_LISTS = setOf("followers", "following");

    /** /<user>/<this>/<code> is the single-item form with a username in front. */
    private static final Set<String> ITEM_KINDS = setOf("p", "reel", "tv");

    // ---- instagram:// -----------------------------------------------------------------------

    /**
     * Checked first, as substrings, so that no prefix or suffix rule below can let a feed in:
     * mainfeed, reels_home, clips_home, explore, discover_people, search, tag, location, ...
     */
    private static final String[] APP_DENY_TOKENS = {
            "feed", "home", "explore", "discover", "search", "clips", "tag", "location", "topic",
            "trending", "suggest", "timeline", "popular",
    };

    /** Single items. Only with something that names the item (a query or a path). */
    private static final Set<String> APP_ITEMS = setOf("media", "reel", "reels_share", "user");

    /** Screens Postbox keeps: own profile, the activity (heart) screen, creation, settings. */
    private static final Set<String> APP_SCREENS = setOf(
            "profile", "news", "camera", "library", "share", "sharesheet", "editprofile", "edit_profile");

    /**
     * Host families that are let through whole: DMs ("direct", "direct-inbox", "direct_thread"),
     * stories ("story", "story-camera", "stories"), and the login / 2FA / account flows, whose
     * exact host names cannot all be listed and where a wrong bounce locks someone out.
     */
    private static final String[] APP_ALLOWED_PREFIXES = {
            "direct", "story", "stories", "account", "login", "logout", "signup", "password", "confirm",
            "two_factor", "twofactor", "challenge", "checkpoint", "onetap", "one_tap", "settings",
    };

    /** "emaillogin", "smslogin", "web_emaillogin": the one-tap login links sent by email and SMS. */
    private static final String APP_ALLOWED_SUFFIX = "login";

    /**
     * What a link to a single reel is rewritten to: the same shortcode in the single-post
     * viewer. See {@link #reelAsPostLink}.
     */
    private static final String POST_LINK_PREFIX = "https://www.instagram.com/p/";

    private static final int MAX_USERNAME = 30;
    private static final int MAX_CODE = 128;
    private static final int MAX_DESCRIPTION = 160;

    private PostboxLinks() {
    }

    // ---- entry points -----------------------------------------------------------------------

    /**
     * Classifies a link from its parts, as {@code android.net.Uri} reports them: scheme, host,
     * DECODED path, and the raw query (only ever tested for being empty). Any of them may be
     * null. Never throws.
     */
    public static Verdict classify(String scheme, String host, String path, String query) {
        try {
            String s = normalize(scheme);
            if (s.isEmpty()) return Verdict.ALLOW;
            if (s.equals("instagram") || s.equals("ig")) return classifyApp(host, path, query);
            if (s.equals("http") || s.equals("https")) return classifyWeb(host, path, query);
            return Verdict.ALLOW;
        } catch (Throwable t) {
            // Only reachable with a link in hand that this code could not read: fail closed.
            return Verdict.BOUNCE;
        }
    }

    /** Classifies a link given as text. Parsed the way {@code android.net.Uri} would. */
    public static Verdict classify(String link) {
        if (link == null) return Verdict.ALLOW;
        try {
            String[] parts = split(link);
            return classify(parts[0], parts[1], parts[2], parts[3]);
        } catch (Throwable t) {
            return Verdict.BOUNCE;
        }
    }

    public static Verdict classify(URI link) {
        return link == null ? Verdict.ALLOW : classify(link.toString());
    }

    /**
     * The single-post form of a link to a single reel, or null for every other link (which is
     * then classified as usual).
     *
     * L2 says a reel link opens as a single item. Instagram's reel link handler does not do
     * that in Postbox: it opens the Reels viewer, which fetches even its first item through the
     * Reels feed endpoint, and that endpoint is blocked (and must stay blocked), so the viewer
     * shows an error. The single-post viewer plays the very same shortcode with nothing below
     * it (seen on 447.0.0.55.81). So {@code /reel/<code>}, {@code /reels/<code>} and
     * {@code /<user>/reel/<code>} on the app-link hosts become
     * {@code https://www.instagram.com/p/<code>/}.
     *
     * Only the shortcode is carried over: no query (share tokens), nothing after the code.
     * Deliberately NOT rewritten, because there is no shortcode in them to carry over:
     * {@code /share/reel/<id>} (a share id) and {@code instagram://reel?...} /
     * {@code instagram://reels_share?...} (media ids, in parameters this code does not know).
     *
     * No loop: the result starts with /p/, which is never a reel link (PostboxLinksTest).
     */
    public static String reelAsPostLink(String scheme, String host, String path) {
        try {
            String s = normalize(scheme);
            if (!s.equals("http") && !s.equals("https")) return null;
            String h = normalize(host);
            while (h.endsWith(".")) h = h.substring(0, h.length() - 1);
            if (!APP_LINK_HOSTS.contains(h)) return null;
            List<String> segments = segments(path);
            if (segments == null) return null;
            String code = reelCode(segments);
            return code == null ? null : POST_LINK_PREFIX + code + "/";
        } catch (Throwable t) {
            // Not rewritten is always safe: the link is still classified.
            return null;
        }
    }

    /** {@link #reelAsPostLink(String, String, String)} for a link given as text. */
    public static String reelAsPostLink(String link) {
        if (link == null) return null;
        try {
            String[] parts = split(link);
            return reelAsPostLink(parts[0], parts[1], parts[2]);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The shortcode of a path that names one reel, in the three forms the table lets open. */
    private static String reelCode(List<String> segments) {
        int count = segments.size();
        if (count < 2) return null;
        // Whatever is rewritten must be a link that was allowed to open in the first place.
        if (classifyWebPath(segments, null) != Verdict.ALLOW) return null;
        String root = lower(segments.get(0));
        if (root.equals("reel")) return segments.get(1);
        if (root.equals("reels")) return segments.get(1);
        // /<user>/reel/<code>. The roots with a meaning of their own come first: /p/reel/<code>
        // is the post "reel", and /share/reel/<id> carries a share id, not a shortcode.
        if (root.equals("p") || root.equals("tv") || root.equals("share") || root.equals("s")
                || root.equals("stories") || root.equals("direct") || root.equals("accounts")
                || root.equals("_u") || root.equals("_n")
                || ACCOUNT_ROOTS.contains(root) || INFO_ROOTS.contains(root)) {
            return null;
        }
        return count >= 3 && lower(segments.get(1)).equals("reel") ? segments.get(2) : null;
    }

    /** What a bounce logs: scheme, host and path. Never the query, which can carry tokens. */
    public static String describe(String scheme, String host, String path) {
        String text = (scheme == null ? "" : scheme) + "://" + (host == null ? "" : host)
                + (path == null ? "" : path);
        StringBuilder clean = new StringBuilder(Math.min(text.length(), MAX_DESCRIPTION));
        for (int index = 0; index < text.length() && index < MAX_DESCRIPTION; index++) {
            char c = text.charAt(index);
            clean.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return clean.toString();
    }

    // ---- web --------------------------------------------------------------------------------

    private static Verdict classifyWeb(String rawHost, String path, String query) {
        String host = normalize(rawHost);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        // http(s) with no host is not a link any browser produces; it only reaches an activity
        // through an explicit intent, and then nobody knows how Instagram would read it.
        if (host.isEmpty()) return Verdict.BOUNCE;

        boolean appLinks = APP_LINK_HOSTS.contains(host);
        boolean shortLinks = SHORT_LINK_HOSTS.contains(host);
        if (!appLinks && !shortLinks && !isFamilyHost(host)) return Verdict.ALLOW;

        List<String> segments = segments(path);
        if (segments == null) return Verdict.BOUNCE;
        if (shortLinks) return classifyShortLink(segments);
        if (appLinks) return classifyWebPath(segments, query);
        // Another Instagram web property: bounce only what names a forbidden surface.
        if (segments.isEmpty() || !SURFACE_ROOTS.contains(lower(segments.get(0)))) return Verdict.ALLOW;
        return classifyWebPath(segments, query);
    }

    private static boolean isFamilyHost(String host) {
        for (String suffix : FAMILY_SUFFIXES) {
            if (host.endsWith(suffix)) return true;
        }
        return false;
    }

    /** ig.me/m/<user> opens a chat and ig.me/j/<code> joins a group chat. Nothing else is known. */
    private static Verdict classifyShortLink(List<String> segments) {
        if (segments.size() < 2) return Verdict.BOUNCE;
        String root = lower(segments.get(0));
        return root.equals("m") || root.equals("j") ? Verdict.ALLOW : Verdict.BOUNCE;
    }

    private static Verdict classifyWebPath(List<String> segments, String query) {
        int count = segments.size();
        if (count == 0) return Verdict.BOUNCE; // "/" is Home.
        String root = lower(segments.get(0));
        switch (root) {
            case "p":
            case "reel":
            case "tv":
                // A shortcode is required: bare /tv/ is the IGTV listing, bare /reel/ nothing.
                return allowIf(count >= 2 && isCode(segments.get(1)));
            case "reels":
                // /reels/<code> is a single reel; bare /reels/ and /reels/audio/... are not.
                return allowIf(count >= 2 && isReelCode(segments.get(1)));
            case "share":
                // /share/<id>, /share/p/<id>, /share/reel/<id>: the share-sheet links.
                return allowIf(count >= 2);
            case "s":
                // /s/<code>: a shared highlight.
                return allowIf(count >= 2);
            case "stories":
                return classifyStories(segments);
            case "direct":
                return Verdict.ALLOW;
            case "accounts":
                return allowIf(count < 2 || !ACCOUNTS_DISCOVERY.contains(lower(segments.get(1))));
            case "_u":
                return allowIf(count == 2 && isUsername(segments.get(1)));
            case "_n":
                // /_n/<target> is the web wrapper around instagram://<target>.
                if (count < 2) return Verdict.BOUNCE;
                return classifyApp(segments.get(1), count > 2 ? "/" + segments.get(2) : null, query);
            default:
                break;
        }
        if (ACCOUNT_ROOTS.contains(root) || INFO_ROOTS.contains(root)) return Verdict.ALLOW;
        if (RESERVED_ROOTS.contains(root)) return Verdict.BOUNCE;

        // What is left is /<username>...
        if (!isUsername(segments.get(0))) return Verdict.BOUNCE;
        if (count == 1) return Verdict.ALLOW;
        String page = lower(segments.get(1));
        if (PROFILE_LISTS.contains(page)) return Verdict.ALLOW;
        return allowIf(count >= 3 && ITEM_KINDS.contains(page) && isCode(segments.get(2)));
    }

    private static Verdict classifyStories(List<String> segments) {
        int count = segments.size();
        if (count < 2) return Verdict.BOUNCE;
        String second = lower(segments.get(1));
        if (second.equals("highlights")) return allowIf(count >= 3);
        if (STORIES_PAGES.contains(second)) return Verdict.BOUNCE;
        // /stories/<user>/ and /stories/<user>/<id>/
        return allowIf(isUsername(segments.get(1)));
    }

    // ---- instagram:// -----------------------------------------------------------------------

    private static Verdict classifyApp(String rawHost, String path, String query) {
        String host = normalize(rawHost);
        if (host.isEmpty()) return Verdict.BOUNCE; // "instagram://" just opens the app on Home.
        for (String token : APP_DENY_TOKENS) {
            if (host.contains(token)) return Verdict.BOUNCE;
        }
        List<String> segments = segments(path);
        if (segments == null) return Verdict.BOUNCE;
        if (APP_ITEMS.contains(host)) {
            // "instagram://reel" with nothing behind it names no reel; it is the tab if anything.
            boolean namesAnItem = !segments.isEmpty() || (query != null && !query.isEmpty());
            return allowIf(namesAnItem);
        }
        if (APP_SCREENS.contains(host)) return Verdict.ALLOW;
        for (String prefix : APP_ALLOWED_PREFIXES) {
            if (host.startsWith(prefix)) return Verdict.ALLOW;
        }
        return allowIf(host.endsWith(APP_ALLOWED_SUFFIX));
    }

    // ---- helpers ----------------------------------------------------------------------------

    private static Verdict allowIf(boolean condition) {
        return condition ? Verdict.ALLOW : Verdict.BOUNCE;
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(values)));
    }

    private static String lower(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    /** Lower-cased and trimmed; null becomes empty. Schemes and hosts are case-insensitive. */
    private static String normalize(String value) {
        return value == null ? "" : lower(value.trim());
    }

    /**
     * The non-empty segments of a decoded path, or null for a path nobody should guess at:
     * dot segments, backslashes, whitespace or control characters. A null or empty path has no
     * segments.
     */
    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        if (path == null) return result;
        for (String segment : path.split("/")) {
            if (segment.isEmpty()) continue;
            if (segment.equals(".") || segment.equals("..")) return null;
            for (int index = 0; index < segment.length(); index++) {
                char c = segment.charAt(index);
                if (c <= 0x20 || c == 0x7f || c == '\\') return null;
            }
            result.add(segment);
        }
        return result;
    }

    /** Instagram usernames: letters, digits, dots and underscores, up to 30 characters. */
    private static boolean isUsername(String value) {
        if (value.isEmpty() || value.length() > MAX_USERNAME) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!(isAsciiLetterOrDigit(c) || c == '.' || c == '_')) return false;
        }
        return true;
    }

    /** Media shortcodes: URL-safe base64 characters. */
    private static boolean isCode(String value) {
        if (value.isEmpty() || value.length() > MAX_CODE) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (!(isAsciiLetterOrDigit(c) || c == '-' || c == '_')) return false;
        }
        return true;
    }

    /**
     * A shortcode directly under /reels/, where it shares the position with the tab's own
     * sub-pages (/reels/audio/, /reels/trending/, ...). Besides the list of known pages, a
     * code must contain an upper-case letter or a digit: page names are lower-case words, and
     * a real shortcode without either is a one-in-several-thousand accident (that reel still
     * opens through its /reel/<code> link, which is what the share sheet produces).
     */
    private static boolean isReelCode(String value) {
        if (!isCode(value) || REELS_PAGES.contains(lower(value))) return false;
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
        }
        return false;
    }

    private static boolean isAsciiLetterOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    /**
     * Splits a link into {scheme, host, decoded path, raw query}, each null when absent, the
     * way {@code android.net.Uri} does: the scheme ends at the first ':', an authority follows
     * "//", the host is what is left of it after the user info and the port, and a link without
     * "//" or a leading "/" is opaque (no host, no path). {@code java.net.URI} is not used: it
     * rejects hosts with an underscore ("direct_thread") and throws on characters Android accepts.
     */
    static String[] split(String link) {
        String scheme = null;
        String rest = link;
        int colon = link.indexOf(':');
        int delimiter = firstOf(link, "/?#");
        if (colon >= 0 && (delimiter < 0 || delimiter > colon)) {
            scheme = link.substring(0, colon);
            rest = link.substring(colon + 1);
        }
        int fragment = rest.indexOf('#');
        if (fragment >= 0) rest = rest.substring(0, fragment);
        String query = null;
        int question = rest.indexOf('?');
        if (question >= 0) {
            query = rest.substring(question + 1);
            rest = rest.substring(0, question);
        }

        String host = null;
        String path = null;
        if (rest.startsWith("//")) {
            int slash = rest.indexOf('/', 2);
            String authority = slash < 0 ? rest.substring(2) : rest.substring(2, slash);
            path = slash < 0 ? "" : rest.substring(slash);
            int at = authority.lastIndexOf('@');
            host = at < 0 ? authority : authority.substring(at + 1);
            int port = host.lastIndexOf(':');
            if (port >= 0 && isDigits(host.substring(port + 1))) host = host.substring(0, port);
            host = percentDecode(host);
        } else if (rest.startsWith("/") || scheme == null) {
            path = rest;
        }
        return new String[] {scheme, host, path == null ? null : percentDecode(path), query};
    }

    private static int firstOf(String text, String characters) {
        for (int index = 0; index < text.length(); index++) {
            if (characters.indexOf(text.charAt(index)) >= 0) return index;
        }
        return -1;
    }

    private static boolean isDigits(String value) {
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Decodes %XX escapes as UTF-8, once. A malformed escape is kept as written. */
    private static String percentDecode(String value) {
        if (value.indexOf('%') < 0) return value;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        byte[] raw = value.getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < raw.length; index++) {
            if (raw[index] == '%' && index + 2 < raw.length) {
                int high = Character.digit((char) raw[index + 1], 16);
                int low = Character.digit((char) raw[index + 2], 16);
                if (high >= 0 && low >= 0) {
                    bytes.write((high << 4) | low);
                    index += 2;
                    continue;
                }
            }
            bytes.write(raw[index]);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
