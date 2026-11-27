package com.salah.tracker;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Opens a download from GitHub. With a token (private repository), the request to
 * api.github.com carries it, and the redirect to GitHub's file storage is followed by
 * hand WITHOUT it (that storage rejects a second form of sign-in).
 */
final class GitHubNet {
    private GitHubNet() {}

    static HttpURLConnection open(String url, String token, long rangeFrom) throws Exception {
        String u = url;
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            boolean api = u.startsWith("https://api.github.com/");
            if (api && token != null && !token.isEmpty()) {
                c.setRequestProperty("Authorization", "Bearer " + token);
                c.setRequestProperty("Accept", "application/octet-stream");
            }
            if (rangeFrom > 0) c.setRequestProperty("Range", "bytes=" + rangeFrom + "-");
            c.connect();
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new Exception("Download failed (no redirect target)");
                u = new URL(new URL(u), loc).toString();
                if (!u.startsWith("https://")) throw new Exception("Download failed (insecure redirect)");
                continue;
            }
            return c;
        }
        throw new Exception("Download failed (too many redirects)");
    }
}
