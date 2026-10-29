package dev.amenhancer.module.lyrics.source

/**
 * The URLs that can serve one file from a GitHub repository, in the order they are tried.
 *
 * Both lyric repositories this module reads live on GitHub, and `raw.githubusercontent.com`
 * is exactly the host that a censored or flaky mobile network resets: when it does, both
 * `AmllTtmlClient` and `AmLyricsClient` fail together and the automatic completion silently
 * produces nothing. A CDN or a reverse proxy for the same bytes turns that "no lyric at all"
 * into "one slower request".
 *
 * The order is deliberate and measured from a network where raw is blocked:
 *
 * 1. **jsDelivr** (`cdn.jsdelivr.net/gh/<owner>/<repo>@<ref>/<path>`) — a CDN for GitHub
 *    content: 0.8 s cold for a 37 KB TTML and reachable where raw answers a reset;
 * 2. **gh-proxy.com** — the plain reverse proxy that takes the whole raw URL as its path;
 * 3. **ghproxy.net** — the same shape, kept as a second proxy in case the first is down;
 * 4. **raw.githubusercontent.com** — GitHub's own host, last: it is the fastest when it
 *    works, but it is also the one this module has actually seen reset.
 *
 * Every URL points at the same commit (`<ref>`), so falling back never changes the
 * content — only who serves it. Endpoints that answered 404/403 are not distinguished
 * here: the transport returns null for anything that is not a 200, and the caller treats
 * "every endpoint missed" exactly like the miss it already handles.
 */
object GitHubContentEndpoints {

    /** jsDelivr's GitHub mirror: `gh/<owner>/<repo>@<ref>/<path>`. */
    private const val JSDELIVR = "https://cdn.jsdelivr.net/gh"

    /** Plain reverse proxies that accept `<base>/<raw url>`. */
    private const val GH_PROXY = "https://gh-proxy.com"
    private const val GHPROXY_NET = "https://ghproxy.net"

    /** GitHub's own raw host, tried last. */
    private const val RAW = "https://raw.githubusercontent.com"

    /**
     * Every endpoint for [path] in [owner]/[repo] at [ref], most reachable first.
     *
     * [path] is passed through as given (the callers encode it themselves), because
     * AM-Lyrics index paths carry non-ASCII artist names that must stay encoded exactly
     * once.
     */
    fun urls(
        owner: String,
        repo: String,
        ref: String,
        path: String,
    ): List<String> {
        val cleanOwner = owner.trim().trim('/')
        val cleanRepo = repo.trim().trim('/')
        val cleanRef = ref.trim().trim('/')
        val cleanPath = path.trim().trimStart('/')
        if (cleanOwner.isEmpty() || cleanRepo.isEmpty() || cleanRef.isEmpty() || cleanPath.isEmpty()) {
            return emptyList()
        }
        val rawUrl = "$RAW/$cleanOwner/$cleanRepo/$cleanRef/$cleanPath"
        return listOf(
            "$JSDELIVR/$cleanOwner/$cleanRepo@$cleanRef/$cleanPath",
            "$GH_PROXY/$rawUrl",
            "$GHPROXY_NET/$rawUrl",
            rawUrl,
        )
    }
}
