package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Self-updater.
 *
 * <p>Checks a small manifest for a newer plugin jar, downloads and verifies it,
 * then (on request, or automatically if configured) hands the swap to
 * {@link UpdateHelper}, which waits for this JVM to exit, replaces the locked
 * plugin jar, and relaunches the server. This is how the plugin can be updated
 * with no access to the server's filesystem.
 *
 * <p>Manifest shape:
 * <pre>
 * { "version": "2.1.0",
 *   "url": "https://.../miaucraft-bridge-plugin-2.1.0.jar",
 *   "sha256": "optional hex digest",
 *   "notes": "optional" }
 * </pre>
 */
public final class Updater {

  private static final String DEFAULT_MANIFEST =
      "https://raw.githubusercontent.com/xhvsh/miaucraft/main/bridge/plugin-update.json";

  private final MiaucraftBridgePlugin plugin;
  private final Logger log;
  private final Path updateDir;
  private final boolean enabled;
  private final String manifestUrl;
  private final boolean autoApply;
  private final boolean relaunch;
  private final String restartCommand;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  private volatile Path stagedJar;
  private volatile String stagedVersion;
  private volatile String lastResult = "never checked";

  public Updater(MiaucraftBridgePlugin plugin, String remoteUrl) {
    this.plugin = plugin;
    this.log = plugin.getLogger();
    this.updateDir = plugin.getDataFolder().toPath().resolve("update");
    this.enabled = plugin.getConfig().getBoolean("update.enabled", true);
    this.autoApply = plugin.getConfig().getBoolean("update.auto-apply", false);
    this.relaunch = plugin.getConfig().getBoolean("update.relaunch", true);
    this.restartCommand = plugin.getConfig().getString("update.restart-command", "");

    String configured = plugin.getConfig().getString("update.manifest-url", "");
    if (configured != null && !configured.isBlank()) {
      this.manifestUrl = configured.trim();
    } else if (remoteUrl != null && remoteUrl.contains("remote-config.json")) {
      this.manifestUrl = remoteUrl.replace("remote-config.json", "plugin-update.json");
    } else {
      this.manifestUrl = DEFAULT_MANIFEST;
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public boolean autoApply() {
    return autoApply;
  }

  public boolean hasStaged() {
    return stagedJar != null && Files.isRegularFile(stagedJar);
  }

  public String stagedVersion() {
    return stagedVersion;
  }

  public String currentVersion() {
    return plugin.getDescription().getVersion();
  }

  /** raw.githubusercontent.com caches files for a few minutes; a fresh query
   *  param forces a cache miss so a freshly deployed manifest/jar is seen
   *  within one check tick instead of minutes later. */
  private static String bust(String url) {
    String t = String.valueOf(System.currentTimeMillis());
    return url.indexOf('?') >= 0 ? url + "&t=" + t : url + "?t=" + t;
  }

  /** Fetches the manifest and stages a newer jar if one exists. Never throws. */
  public CompletableFuture<String> check() {
    if (!enabled) {
      return CompletableFuture.completedFuture("updater disabled");
    }
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(bust(manifestUrl)))
        .timeout(Duration.ofSeconds(20))
        .header("User-Agent", "MiaucraftBridge/" + currentVersion())
        .GET()
        .build();
    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(res -> {
          if (res.statusCode() >= 300) {
            throw new RuntimeException("manifest HTTP " + res.statusCode());
          }
          String body = res.body();
          try {
            return stage(JsonParser.parseString(body).getAsJsonObject());
          } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
          }
        })
        .exceptionally(err -> {
          String msg = "check failed: " + brief(err);
          lastResult = msg;
          log.warning("update " + msg);
          return msg;
        });
  }

  private synchronized String stage(JsonObject manifest) throws Exception {
    String version = manifest.get("version").getAsString().trim();
    if (version.startsWith("v") || version.startsWith("V")) {
      version = version.substring(1);
    }
    String url = manifest.get("url").getAsString();
    String sha = manifest.has("sha256") ? manifest.get("sha256").getAsString().trim() : "";

    int cmp = compareVersions(version, currentVersion());
    if (cmp < 0 || (cmp == 0 && currentJarMatches(sha))) {
      lastResult = "up to date (v" + currentVersion() + ")";
      return lastResult;
    }

    Files.createDirectories(updateDir);
    Path dest = updateDir.resolve("miaucraft-bridge-plugin-" + version + ".jar");
    if (Files.exists(dest) && (sha.isBlank() || sha.equalsIgnoreCase(sha256(dest)))) {
      markStaged(dest, version);
      lastResult = "v" + version + " already staged";
      return lastResult;
    }

    Path tmp = updateDir.resolve("download.tmp");
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(bust(url)))
        .timeout(Duration.ofMinutes(2))
        .header("User-Agent", "MiaucraftBridge/" + currentVersion())
        .GET()
        .build();
    HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
    if (res.statusCode() >= 300) {
      Files.deleteIfExists(tmp);
      throw new RuntimeException("jar HTTP " + res.statusCode());
    }
    if (!sha.isBlank() && !sha.equalsIgnoreCase(sha256(tmp))) {
      Files.deleteIfExists(tmp);
      throw new RuntimeException("sha256 mismatch for v" + version);
    }
    validateJar(tmp, version);
    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
    markStaged(dest, version);
    lastResult = "staged v" + version + " (" + Files.size(dest) + " bytes)";
    log.info("update " + lastResult + " - run /bridge update apply to install");
    return lastResult;
  }

  private void markStaged(Path dest, String version) {
    stagedJar = dest;
    stagedVersion = version;
  }

  private static void validateJar(Path jar, String expectedVersion) throws Exception {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry("plugin.yml");
      if (entry == null) {
        throw new IllegalStateException("downloaded jar has no plugin.yml");
      }
      String text;
      try (InputStream in = zip.getInputStream(entry)) {
        text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      }
      String version = value(text, "version");
      if (version != null && !version.equals(expectedVersion)) {
        throw new IllegalStateException(
            "jar version " + version + " does not match manifest " + expectedVersion);
      }
      if (value(text, "main") == null) {
        throw new IllegalStateException("downloaded jar has no main class in plugin.yml");
      }
    }
  }

  private static String value(String yaml, String key) {
    for (String line : yaml.split("\\R")) {
      String t = line.trim();
      if (t.startsWith(key + ":")) {
        return t.substring(key.length() + 1).trim();
      }
    }
    return null;
  }

  /**
   * True when the manifest sha is blank or matches the currently loaded jar,
   * i.e. a same-version manifest really is the build already running. A
   * same-version manifest with a DIFFERENT sha means a re-build must stage.
   */
  private boolean currentJarMatches(String sha) {
    if (sha.isBlank()) return true;
    try {
      Path jar = plugin.pluginFile().toPath();
      return jar.toFile().exists() && sha.equalsIgnoreCase(sha256(jar));
    } catch (Exception e) {
      return true; // can't verify - don't loop re-staging on every check
    }
  }

  private static String sha256(Path file) throws Exception {
    MessageDigest md = MessageDigest.getInstance("SHA-256");
    byte[] buf = new byte[8192];
    try (InputStream in = Files.newInputStream(file)) {
      int n;
      while ((n = in.read(buf)) > 0) {
        md.update(buf, 0, n);
      }
    }
    return HexFormat.of().formatHex(md.digest());
  }

  /** Stages are applied here: swap in place when possible, else via the detached helper. */
  public void apply(CommandSender sender) {
    if (!hasStaged()) {
      sender.sendMessage("§c[MiaucraftBridge] No staged update - run §f/bridge update §cfirst.");
      return;
    }
    Path target = ownJar();
    if (target == null) {
      sender.sendMessage("§c[MiaucraftBridge] Can't locate my own jar (exploded/dev run?) - not applying.");
      return;
    }
    String version = stagedVersion;
    Path staged = stagedJar;
    try {
      if (installInPlace(staged, target)) {
        stagedJar = null;
        stagedVersion = null;
        lastResult = "installed v" + version + " in place";
        sender.sendMessage("§a[MiaucraftBridge] Installed v" + version
            + " - the server will restart now.");
        log.info("update " + lastResult + " (" + target + ").");
        Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
        return;
      }
      Path conf = writeConf(target);
      launchHelper(conf);
      sender.sendMessage("§a[MiaucraftBridge] Installing v" + version
          + " - the server will restart now.");
      log.info("Applying update v" + version + " (jar -> " + target + "), restarting.");
      Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
    } catch (Exception e) {
      lastResult = "apply failed: " + brief(e);
      log.warning("update " + lastResult);
      sender.sendMessage("§c[MiaucraftBridge] Update apply failed: " + brief(e));
    }
  }

  /**
   * Replaces the running jar with the staged copy while the server is still
   * up - POSIX lets us replace a file a JVM has open, so no detached helper
   * is needed on Unix hosts (some servers kill or never start that helper).
   * Returns false when the platform refuses the overwrite (e.g. a locked
   * file on Windows), so the caller can defer to the detached helper.
   */
  private boolean installInPlace(Path staged, Path target) throws Exception {
    if (!staged.equals(target)) {
      try {
        Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        log.info("update: in-place install not possible (" + brief(e)
            + ") - using detached helper");
        return false;
      }
    }
    Files.deleteIfExists(staged);
    return true;
  }

  private Path ownJar() {
    // Paper remaps plugins into plugins/.paper-remapped/. The file that must be
    // replaced is the ORIGINAL jar in plugins/ - the remapped copy is
    // regenerated from it on the next start.
    try {
      java.io.File file = plugin.pluginFile();
      if (file != null) {
        java.io.File parent = file.getParentFile();
        if (parent != null && ".paper-remapped".equals(parent.getName())
            && parent.getParentFile() != null) {
          java.io.File original = new java.io.File(parent.getParentFile(), file.getName());
          if (original.isFile()) {
            return original.toPath();
          }
        }
        if (file.isFile()) {
          return file.toPath();
        }
      }
    } catch (Exception ignored) {
    }
    try {
      URL loc = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
      Path p = Path.of(loc.toURI());
      return Files.isRegularFile(p) ? p : null;
    } catch (Exception e) {
      return null;
    }
  }

  private Path writeConf(Path target) throws Exception {
    Files.createDirectories(updateDir);
    boolean win = isWindows();
    String javaExe = ProcessHandle.current().info().command()
        .orElse(Path.of(System.getProperty("java.home"), "bin", win ? "java.exe" : "java").toString());

    String command = restartCommand == null ? "" : restartCommand.trim();
    List<String> args = new ArrayList<>();
    if (command.isBlank()) {
      // Prefer the real command line: it is exact, including any launcher
      // flags we can't otherwise reconstruct.
      String commandLine = ProcessHandle.current().info().commandLine().orElse("").trim();
      if (!commandLine.isBlank()) {
        command = commandLine;
      } else {
        // Fallback. getInputArguments() omits "-jar", and for jar launches
        // sun.java.command is "app.jar args", so put "-jar" back.
        args.addAll(java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        String sun = System.getProperty("sun.java.command", "").trim();
        if (!sun.isBlank()) {
          String[] parts = sun.split("\\s+");
          if (parts[0].toLowerCase().endsWith(".jar")) {
            args.add("-jar");
          }
          for (String s : parts) {
            if (!s.isBlank()) {
              args.add(s);
            }
          }
        }
      }
    }

    StringBuilder sb = new StringBuilder();
    sb.append("serverPid=").append(ProcessHandle.current().pid()).append('\n');
    sb.append("staged=").append(slash(stagedJar)).append('\n');
    sb.append("target=").append(slash(target)).append('\n');
    sb.append("workdir=").append(slash(Path.of(System.getProperty("user.dir")))).append('\n');
    sb.append("java=").append(slash(javaExe)).append('\n');
    sb.append("relaunch=").append(relaunch).append('\n');
    if (!command.isBlank()) {
      sb.append("command=").append(command).append('\n');
    }
    for (int i = 0; i < args.size(); i++) {
      sb.append("arg").append(i).append('=').append(args.get(i)).append('\n');
    }

    Path conf = updateDir.resolve("apply-update.properties");
    Files.writeString(conf, sb.toString(), StandardCharsets.UTF_8);
    return conf;
  }

  private void launchHelper(Path conf) throws Exception {
    String javaExe = ProcessHandle.current().info().command()
        .orElse(Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java").toString());
    String cp = stagedJar.toString();
    String main = "com.xhvsh.miaucraftbridge.UpdateHelper";

    if (isWindows()) {
      // "start" detaches the helper onto its own console so it survives this
      // process (and its console) going away.
      String line = "start \"\" \"" + javaExe + "\" -cp \"" + cp + "\" " + main
          + " \"" + conf + "\"";
      new ProcessBuilder("cmd.exe", "/c", line).start();
    } else {
      // setsid fully detaches from this process group / controlling terminal.
      String line = "setsid \"" + javaExe + "\" -cp \"" + cp + "\" " + main
          + " \"" + conf + "\" >/dev/null 2>&1 < /dev/null &";
      try {
        new ProcessBuilder("sh", "-c", line).start();
      } catch (IOException e) {
        // setsid is absent in some slim server images - nohup detaches well enough.
        String alt = "nohup \"" + javaExe + "\" -cp \"" + cp + "\" " + main
            + " \"" + conf + "\" >/dev/null 2>&1 < /dev/null &";
        new ProcessBuilder("sh", "-c", alt).start();
      }
    }
  }

  private static String slash(Path path) {
    return path.toAbsolutePath().normalize().toString().replace('\\', '/');
  }

  private static String slash(String path) {
    return path.replace('\\', '/');
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  public List<String> statusLines() {
    List<String> lines = new ArrayList<>();
    lines.add("§7updater: §f" + (enabled ? "on" : "off")
        + " §7(current §f" + currentVersion() + "§7, auto-apply §f" + autoApply + "§7)");
    lines.add("§7update manifest: §f" + manifestUrl);
    lines.add("§7update last check: §f" + lastResult);
    if (hasStaged()) {
      lines.add("§7staged update: §a" + stagedVersion + " §7(§f" + stagedJar.getFileName() + "§7)");
    }
    return lines;
  }

  /** Dotted-numeric compare; non-numeric parts compared lexically. */
  static int compareVersions(String a, String b) {
    String[] pa = a.split("[.\\-+]");
    String[] pb = b.split("[.\\-+]");
    int n = Math.max(pa.length, pb.length);
    for (int i = 0; i < n; i++) {
      String x = i < pa.length ? pa[i] : "0";
      String y = i < pb.length ? pb[i] : "0";
      Integer ix = parse(x);
      Integer iy = parse(y);
      int c = (ix != null && iy != null) ? Integer.compare(ix, iy) : x.compareTo(y);
      if (c != 0) {
        return c;
      }
    }
    return 0;
  }

  private static Integer parse(String s) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String brief(Throwable err) {
    Throwable cause = err.getCause() != null ? err.getCause() : err;
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }
}
