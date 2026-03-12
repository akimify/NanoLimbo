package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

public class LemeRenewer {

    private static final String EMAIL     = "bsos@usdtbeta.com";
    private static final String PASSWORD  = "AkiRa13218*#";
    private static final String SERVER_ID = "10084631";
    private static final String[] OCR_KEYS = {
        "K85259096088957",

    };

    private static final String BASE_URL         = "https://lemehost.com";
    private static final String LOGIN_URL        = BASE_URL + "/site/login";
    private static final String FREE_PLAN_URL    = BASE_URL + "/server/%s/free-plan";
    private static final String OCR_API_URL      = "https://api.ocr.space/parse/image";

    private static final int  RENEW_THRESHOLD    = 300;
    private static final long CHECK_INTERVAL_MS  = 30_000;
    private static final long RELOAD_INTERVAL_MS = 1_800_000;

    private static void log(String level, String msg) {
        java.time.LocalTime t = java.time.LocalTime.now();
        String time = String.format("%02d:%02d:%02d.%03d", t.getHour(), t.getMinute(), t.getSecond(), t.getNano()/1_000_000);
        System.out.println(time + " " + level + " LemeRenewer --  " + msg);
    }

    private final CookieManager cookieManager;
    private final HttpClient httpClient;

    private volatile boolean loggedIn = false;
    private int ocrKeyIdx = 0;

    public LemeRenewer() {
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        this.httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public void start() {
        log("INFO ", "🌸 LemeRenewer 启动，服务器ID: " + SERVER_ID);

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "leme-renewer");
            t.setDaemon(true);
            return t;
        });

        scheduler.execute(() -> {
            try {
                if (!loggedIn) {
                    boolean ok = login();
                    if (!ok) {
                        return;
                    }
                }
                renewWithRetry(3);
            } catch (Exception e) {
            }
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                tick();
            } catch (Exception e) {
            }
        }, CHECK_INTERVAL_MS, CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);

        scheduler.scheduleAtFixedRate(() -> {
            loggedIn = false;
        }, RELOAD_INTERVAL_MS, RELOAD_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void tick() throws Exception {
        if (!loggedIn) {
            boolean ok = login();
            if (!ok) {
                return;
            }
        }

        PageInfo info = fetchFreePlanPage();
        if (info == null) {
            loggedIn = false;
            return;
        }

        

        if (info.remainSeconds < RENEW_THRESHOLD) {
            renewWithRetry(3);
        }
    }

    private boolean login() {
        log("INFO ", "🔐 开始登录...");

        boolean loginPrinted = false;
        while (true) {
            try {
                String loginPage = httpGet(LOGIN_URL);
                if (loginPage == null) throw new IOException("无法获取登录页");

                String csrf       = extractInputValue(loginPage, "_csrf-frontend");
                String captchaUrl = extractCaptchaUrl(loginPage, "/site/captcha");
                long   hash       = extractYiiCaptchaHash(loginPage);
                String key        = String.valueOf(System.currentTimeMillis() / 1000.0);

                if (csrf == null) throw new IOException("未找到csrf token");

                String captchaAnswer = "";
                if (captchaUrl != null && hash != 0 && OCR_KEYS.length > 0) {
                    if (!loginPrinted) log("INFO ", "🔑 获取验证码...");
                    loginPrinted = true;
                    String fullUrl = captchaUrl.startsWith("http") ? captchaUrl : BASE_URL + captchaUrl;
                    captchaAnswer  = solveCaptcha(fullUrl, hash);
                    if (captchaAnswer == null || captchaAnswer.isEmpty()) {
                        Thread.sleep(2000);
                        continue;
                    }
                } else {
                    if (!loginPrinted) log("INFO ", "🟢 无需验证...");
                    loginPrinted = true;
                }

                Map<String, String> params = new LinkedHashMap<>();
                params.put("_csrf-frontend",        csrf);
                params.put("LoginForm[email]",      EMAIL);
                params.put("LoginForm[password]",   PASSWORD);
                params.put("LoginForm[key]",        key);
                params.put("LoginForm[key2]",       key);
                params.put("LoginForm[verifyCode]", captchaAnswer);
                params.put("LoginForm[rememberMe]", "1");

                String resp = httpPost(LOGIN_URL, params, LOGIN_URL);
                if (resp == null) throw new IOException("登录POST无响应");

                if (!resp.contains("LoginForm") && !resp.contains("loginform-email")) {
                    loggedIn = true;
                    log("INFO ", "✅ 登录成功！");
                    return true;
                } else {
                    Thread.sleep(3000);
                }

            } catch (Exception e) {
                try { Thread.sleep(3000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private PageInfo fetchFreePlanPage() {
        try {
            String url  = String.format(FREE_PLAN_URL, SERVER_ID);
            String html = httpGet(url);
            if (html == null) return null;

            long timestamp = extractCountdownTimestamp(html);
            long remainMs  = timestamp - System.currentTimeMillis();
            int  remainSec = (int) Math.max(0, remainMs / 1000);

            String csrf        = extractInputValue(html, "_csrf-frontend");
            String captchaUrl  = extractCaptchaUrl(html, "/site/captcha");
            long   captchaHash = extractYiiCaptchaHash(html);

            PageInfo info = new PageInfo();
            info.remainSeconds = remainSec;
            info.csrf          = csrf;
            info.captchaUrl    = captchaUrl;
            info.captchaHash   = captchaHash;
            info.hasCaptcha    = captchaUrl != null;
            return info;

        } catch (Exception e) {
            return null;
        }
    }

    private void renewWithRetry(int ignored) {
        boolean printed = false;
        while (true) {
            try {
                boolean ok = renewOnce(printed);
                if (ok) return;
                printed = true;
                Thread.sleep(3000);
            } catch (Exception e) {
                printed = true;
                try { Thread.sleep(3000); } catch (InterruptedException ig) {}
            }
        }
    }

    private boolean renewOnce(boolean silent) throws Exception {
        String url  = String.format(FREE_PLAN_URL, SERVER_ID);
        String html = httpGet(url);
        if (html == null) throw new IOException("无法获取续期页面");

        String csrf          = extractInputValue(html, "_csrf-frontend");
        String captchaImgUrl = extractCaptchaUrl(html, "/site/captcha");
        long   captchaHash   = extractYiiCaptchaHash(html);

        if (csrf == null) {
            loggedIn = false;
            throw new IOException("csrf token未找到，可能session已过期");
        }

        String captchaAnswer = "";
        if (captchaImgUrl != null && captchaHash != 0 && OCR_KEYS.length > 0) {
            if (!silent) {
                log("INFO ", "🔍 开始续期...");
                log("INFO ", "🔍 获取验证码...");
            }
            String fullUrl = captchaImgUrl.startsWith("http") ? captchaImgUrl : BASE_URL + captchaImgUrl;
            captchaAnswer  = solveCaptcha(fullUrl, captchaHash);
            if (captchaAnswer == null || captchaAnswer.isEmpty()) {
                return false;
            }
        } else {
            if (!silent) {
                log("INFO ", "🔍 开始续期...");
                log("INFO ", "🟢 无需验证...");
            }
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("_csrf-frontend",             csrf);
        params.put("ExtendFreePlanForm[captcha]", captchaAnswer);

        String resp = httpPostPjax(url, params, url);
        if (resp == null) throw new IOException("续期POST无响应");

        long newTimestamp = extractCountdownTimestamp(resp);
        long newRemainMs  = newTimestamp - System.currentTimeMillis();
        int  newRemainMin = (int)(newRemainMs / 60000);

        if (newRemainMin >= 29) {
            log("INFO ", "🌸 续期成功！剩余约 " + newRemainMin + " 分钟");
            return true;
        } else {
            String err = extractErrorMessage(resp);
            return false;
        }
    }

    private String solveCaptcha(String imgUrl, long targetHash) throws Exception {
        byte[] imgBytes = httpGetBytes(imgUrl);
        if (imgBytes == null || imgBytes.length == 0) throw new IOException("验证码图片下载失败");

        byte[] processed = preprocessImage(imgBytes);
        String base64 = Base64.getEncoder().encodeToString(processed);
        String dataUrl = "data:image/png;base64," + base64;

        List<String> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            String r = ocrOnce(dataUrl);
            if (r != null && r.length() >= 3) results.add(r);
            if (i < 2) Thread.sleep(500);
        }

        if (results.isEmpty()) return null;

        String code = voteBest(results);

        if (calcYiiHash(code) == targetHash) return code;

        String corrected = findByHash(code, targetHash);
        if (corrected != null) {
            return corrected;
        }

        return null;
    }

    private String ocrOnce(String base64DataUrl) {
        if (OCR_KEYS.length == 0) return null;
        String key = OCR_KEYS[ocrKeyIdx % OCR_KEYS.length];

        try {
            String body = "apikey=" + URLEncoder.encode(key, StandardCharsets.UTF_8)
                    + "&base64Image=" + URLEncoder.encode(base64DataUrl, StandardCharsets.UTF_8)
                    + "&language=eng"
                    + "&isOverlayRequired=false"
                    + "&detectOrientation=false"
                    + "&scale=true"
                    + "&OCREngine=2"
                    + "&filetype=PNG";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(OCR_API_URL))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            String json = resp.body();

            if (json.contains("\"IsErroredOnProcessing\":true")) {
                ocrKeyIdx++;
                return null;
            }

            Pattern p = Pattern.compile("\"ParsedText\"\\s*:\\s*\"([^\"]+)\"");
            Matcher m = p.matcher(json);
            if (m.find()) {
                String raw = m.group(1).replace("\\n", "").replace("\\r", "").replace("\\t", "");
                return raw.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            }
        } catch (Exception e) {
        }
        return null;
    }

    private long calcYiiHash(String s) {
        long h = 0;
        String v = s.toLowerCase();
        for (int i = v.length() - 1; i >= 0; i--) {
            h += (long) v.charAt(i) << i;
        }
        return h;
    }

    private String findByHash(String ocr, long targetHash) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";

        for (int p = 0; p < ocr.length(); p++) {
            for (char c : chars.toCharArray()) {
                if (c == ocr.charAt(p)) continue;
                String s = ocr.substring(0, p) + c + ocr.substring(p + 1);
                if (calcYiiHash(s) == targetHash) return s;
            }
        }

        for (int p1 = 0; p1 < ocr.length(); p1++) {
            for (char c1 : chars.toCharArray()) {
                if (c1 == ocr.charAt(p1)) continue;
                String mid = ocr.substring(0, p1) + c1 + ocr.substring(p1 + 1);
                for (int p2 = p1 + 1; p2 < mid.length(); p2++) {
                    for (char c2 : chars.toCharArray()) {
                        if (c2 == mid.charAt(p2)) continue;
                        String s = mid.substring(0, p2) + c2 + mid.substring(p2 + 1);
                        if (calcYiiHash(s) == targetHash) return s;
                    }
                }
            }
        }
        String chars2 = chars;

        // 长度-1：删除某一位
        for (int p = 0; p < ocr.length(); p++) {
            String s = ocr.substring(0, p) + ocr.substring(p + 1);
            if (s.length() >= 3 && calcYiiHash(s) == targetHash) return s;
        }

        // 长度+1：在某一位插入一个字符
        for (int p = 0; p <= ocr.length(); p++) {
            for (char c : chars2.toCharArray()) {
                String s = ocr.substring(0, p) + c + ocr.substring(p);
                if (calcYiiHash(s) == targetHash) return s;
            }
        }

        return null;
    }

    private String voteBest(List<String> results) {
        if (results.size() == 1) return results.get(0);

        Map<Integer, Integer> lenCount = new HashMap<>();
        for (String r : results) lenCount.merge(r.length(), 1, Integer::sum);
        int bestLen = lenCount.entrySet().stream()
                .max(Map.Entry.comparingByValue()).get().getKey();

        List<String> cands = new ArrayList<>();
        for (String r : results) if (r.length() == bestLen) cands.add(r);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bestLen; i++) {
            Map<Character, Integer> cc = new HashMap<>();
            for (String r : cands) {
                if (i < r.length()) cc.merge(r.charAt(i), 1, Integer::sum);
            }
            sb.append(cc.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey());
        }
        return sb.toString();
    }

    private String httpGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private byte[] httpGetBytes(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        return resp.body();
    }

    private String httpPost(String url, Map<String, String> params, String referer) throws Exception {
        String body = buildFormBody(params);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", referer)
                .header("Origin", BASE_URL)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private String httpPostPjax(String url, Map<String, String> params, String referer) throws Exception {

        String pageHtml   = httpGet(url);
        String csrfHeader = "";
        if (pageHtml != null) {
            String metaCsrf = extractMetaContent(pageHtml, "csrf-token");
            if (metaCsrf != null) csrfHeader = metaCsrf;
        }

        String body = buildFormBody(params);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Referer", referer)
                .header("Origin", BASE_URL)
                .header("Accept", "text/html, */*; q=0.01")
                .header("X-Requested-With", "XMLHttpRequest")
                .header("X-PJAX", "true")
                .header("X-PJAX-Container", "#p0");

        if (!csrfHeader.isEmpty()) builder.header("X-Csrf-Token", csrfHeader);

        HttpResponse<String> resp = httpClient.send(
                builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private String extractInputValue(String html, String name) {
        Pattern p = Pattern.compile(
                "<input[^>]+name=[\"']" + Pattern.quote(name) + "[\"'][^>]+value=[\"']([^\"']*)[\"']" +
                "|<input[^>]+value=[\"']([^\"']*)[\"'][^>]+name=[\"']" + Pattern.quote(name) + "[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    private String extractMetaContent(String html, String name) {
        Pattern p = Pattern.compile(
                "<meta[^>]+name=[\"']" + Pattern.quote(name) + "[\"'][^>]+content=[\"']([^\"']+)[\"']" +
                "|<meta[^>]+content=[\"']([^\"']+)[\"'][^>]+name=[\"']" + Pattern.quote(name) + "[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    private String extractCaptchaUrl(String html, String urlContains) {
        Pattern p = Pattern.compile(
                "<img[^>]+id=[\"'][^\"']*captcha[^\"']*[\"'][^>]+src=[\"']([^\"']+)[\"']" +
                "|<img[^>]+src=[\"']([^\"']*" + Pattern.quote(urlContains) + "[^\"']*)[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(1) != null ? m.group(1) : m.group(2);
        return null;
    }

    private long extractYiiCaptchaHash(String html) {
        Pattern p = Pattern.compile("\"hash\"\\s*:\\s*(\\d+)");
        Matcher m = p.matcher(html);
        if (m.find()) return Long.parseLong(m.group(1));
        return 0;
    }

    private long extractCountdownTimestamp(String html) {
        Pattern p = Pattern.compile(
                "id=[\"']countdown-free-plan[\"'][^>]+data-timestamp=[\"'](\\d+)[\"']" +
                "|data-timestamp=[\"'](\\d+)[\"'][^>]+id=[\"']countdown-free-plan[\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            String ts = m.group(1) != null ? m.group(1) : m.group(2);
            return Long.parseLong(ts);
        }
        return System.currentTimeMillis() + 1_800_000;
    }

    private String extractErrorMessage(String html) {
        Pattern p = Pattern.compile(
                "<[^>]+class=[\"'][^\"']*(help-block|alert|text-danger)[^\"']*[\"'][^>]*>\\s*([^<]+)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) return m.group(2).trim();
        return "未知错误";
    }

    private byte[] preprocessImage(byte[] imgBytes) {
        try {
            java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(imgBytes);
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(bis);
            if (src == null) return imgBytes;

            int scale = 2;
            int w = src.getWidth() * scale;
            int h = src.getHeight() * scale;

            java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = out.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();

            // 二值化
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = out.getRGB(x, y);
                    int r = (rgb >> 16) & 0xff;
                    int gr = (rgb >> 8) & 0xff;
                    int b = rgb & 0xff;
                    int gray = (int)(0.299 * r + 0.587 * gr + 0.114 * b);
                    int val = gray < 128 ? 0x000000 : 0xffffff;
                    out.setRGB(x, y, 0xff000000 | val);
                }
            }

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(out, "png", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return imgBytes;
        }
    }

    private String buildFormBody(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
              .append('=')
              .append(URLEncoder.encode(e.getValue() != null ? e.getValue() : "", StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private String formatSeconds(int secs) {
        return String.format("%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60);
    }

    private static class PageInfo {
        int     remainSeconds;
        String  csrf;
        String  captchaUrl;
        long    captchaHash;
        boolean hasCaptcha;
    }
}
