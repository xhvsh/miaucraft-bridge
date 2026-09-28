package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Self-updater.
 *
 * <p>Checks a small manifest for a newer plugin jar, downloads and verifies it,
 * then (on request, or automatically if configured) hands the swap to
 * {@link UpdateHelper}, which waits for this JVM to exit, replaces the locked
 * plugin jar, and relaunches the server.
 *
 * <p>Trust rules, because this path writes to the server's plugin folder:
 * <ul>
 *   <li>the manifest MUST carry a {@code sha256}; a digest-less manifest is
 *       refused instead of being trusted over the wire,</li>
 *   <li>only {@code https} URLs are downloaded,</li>
 *   <li>the jar is size-capped while streaming, so a bad manifest cannot fill
 *       the disk, and the digest is verified before the file is accepted,</li>
 *   <li>installs are atomic (temp file + move), never a partial overwrite,</li>
 *   <li>checks are single-flight, so a slow download cannot stack up.</li>
 * </ul>
 *
 * <p>Manifest shape:
 * <pre>
 * { "version": "2.1.0",
 *   "url": "https://.../miaucraft-bridge-plugin-2.1.0.jar",
 *   "sha256": "64 hex chars",
 *   "notes": "optional" }
 * </pre>
 */
public final class Updater {

  private static final String DEFAULT_MANIFEST =
      "https://api.github.com/repos/xhvsh/miaucraft-bridge/contents/plugin-update.json?ref=main";

  /** Refuse anything larger than this (plugin jars are a few hundred kB). */
  static final long MAX_JAR_BYTES = 64L * 1024 * 1024;

  /** raw.githubusercontent.com lags a push, so the manifest is read via the Contents API. */
  private static String manifestApi(String url) {
    String raw = "https://raw.githubusercontent.com/";
    if (url != null && url.startsWith(raw)) {
      String path = URI.create(url).getPath();
      String[] parts = path.split("/");
      if (parts.length >= 4) {
        String prefix = "/" + parts[1] + "/" + parts[2] + "/" + parts[3] + "/";
        return "https://api.github.com/repos/" + parts[1] + "/" + parts[2]
            + "/contents/" + path.substring(prefix.length()) + "?ref=" + parts[3];
      }
    }
    return url;
  }

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
  private final AtomicBoolean checking = new AtomicBoolean(false);
  private final AtomicBoolean applying = new AtomicBoolean(false);

  private volatile Path stagedJar;
  private volatile String stagedVersion;
  private volatile String lastResult = "never checked";
  private volatile String lastManifestSha = "";

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

  /**
   * raw.githubusercontent.com (and most CDNs) cache aggressively, so a fresh
   * check has to ask for a URL that cannot be served from cache. Only used for
   * the manifest: the jar digest already proves the bytes are the ones that
   * were published, and appending a parameter to a signed jar URL would
   * invalidate its signature.
   */
  private static String bust(String url) {
    String t = String.valueOf(System.currentTimeMillis());
    return url.indexOf('?') >= 0 ? url + "&t=" + t : url + "?t=" + t;
  }

  private static void requireHttps(String url, String what) {
    if (!url.toLowerCase(Locale.ROOT).startsWith("https://")) {
      throw new IllegalStateException(what + " must be an https URL, refusing " + url);
    }
  }

  /** Fetches the manifest and stages a newer jar if one exists. Never throws. */
  public CompletableFuture<String> check() {
    if (!enabled) {
      return CompletableFuture.completedFuture("updater disabled");
    }
    if (!checking.compareAndSet(false, true)) {
      return CompletableFuture.completedFuture("update check already running");
    }
    HttpRequest req;
    try {
      String manifest = bust(manifestApi(manifestUrl));
      requireHttps(manifest, "update.manifest-url");
      req = HttpRequest.newBuilder()
          .uri(URI.create(manifest))
          .timeout(Duration.ofSeconds(20))
          .header("User-Agent", "MiaucraftBridge/" + currentVersion())
          .header("Accept", "application/vnd.github.raw+json")
          .GET()
          .build();
    } catch (RuntimeException e) {
      checking.set(false);
      lastResult = "check failed: " + e.getMessage();
      log.warning("update " + lastResult);
      return CompletableFuture.completedFuture(lastResult);
    }

    return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
        .thenApply(res -> {
          if (res.statusCode() >= 300) {
            throw new RuntimeException("manifest HTTP " + res.statusCode());
          }
          try {
            return stage(JsonParser.parseString(res.body()).getAsJsonObject());
          } catch (Exception e) {
            throw new RuntimeException(e.getMessage() == null ? e.toString() : e.getMessage(), e);
          }
        })
        .exceptionally(err -> {
          String msg = "check failed: " + brief(err);
          lastResult = msg;
          log.warning("update " + msg);
          return msg;
        })
        .whenComplete((res, err) -> checking.set(false));
  }

  private synchronized String stage(JsonObject manifest) throws Exception {
    String version = normalizeVersion(string(manifest, "version"));
    if (version == null) throw new IllegalStateException("manifest has no usable version");
    String url = string(manifest, "url");
    if (url == null) throw new IllegalStateException("manifest has no url");
    requireHttps(url, "manifest url");
    String sha = string(manifest, "sha256");
    if (sha == null || !isSha256(sha)) {
      throw new IllegalStateException("manifest has no valid sha256 - refusing an unverified update");
    }
    this.lastManifestSha = sha;

    int cmp = compareVersions(version, currentVersion());
    if (cmp < 0 || (cmp == 0 && currentJarMatches(sha))) {
      lastResult = "up to date (v" + currentVersion() + " · sha " + runningShaShort() + ")";
      return lastResult;
    }

    Files.createDirectories(updateDir);
    Path dest = updateDir.resolve("miaucraft-bridge-plugin-" + version + ".jar");
    if (Files.exists(dest) && sha.equalsIgnoreCase(sha256(dest))) {
      markStaged(dest, version);
      lastResult = "v" + version + " already staged (sha " + shortSha(dest) + ")";
      return lastResult;
    }

    Path tmp = updateDir.resolve("download.tmp");
    Files.deleteIfExists(tmp);
    download(url, tmp);
    if (Files.size(tmp) > MAX_JAR_BYTES) {
      Files.deleteIfExists(tmp);
      throw new IllegalStateException("downloaded jar exceeds " + MAX_JAR_BYTES + " bytes");
    }
    if (!sha.equalsIgnoreCase(sha256(tmp))) {
      Files.deleteIfExists(tmp);
      throw new IllegalStateException("sha256 mismatch for v" + version);
    }
    validateJar(tmp, version);
    moveInto(tmp, dest);
    markStaged(dest, version);
    lastResult = "staged v" + version + " (" + Files.size(dest) + " bytes · sha " + shortSha(dest) + ")";
    log.info("update " + lastResult + " - run /bridge update apply to install");
    report("info", "update.staged", lastResult, version);
    return lastResult;
  }

  /** Streams the jar to disk, aborting as soon as the size cap is passed. */
  private void download(String url, Path tmp) throws Exception {
    HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofMinutes(2))
        .header("User-Agent", "MiaucraftBridge/" + currentVersion())
        .GET()
        .build();
    HttpResponse<InputStream> res =
        http.send(req, HttpResponse.BodyHandlers.ofInputStream());
    if (res.statusCode() >= 300) {
      res.body().close();
      Files.deleteIfExists(tmp);
      throw new RuntimeException("jar HTTP " + res.statusCode());
    }
    long written = 0L;
    try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
      byte[] buf = new byte[16384];
      int n;
      while ((n = in.read(buf)) > 0) {
        written += n;
        if (written > MAX_JAR_BYTES) {
          throw new IllegalStateException("download exceeded " + MAX_JAR_BYTES + " bytes");
        }
        out.write(buf, 0, n);
      }
    } catch (Exception e) {
      Files.deleteIfExists(tmp);
      throw e;
    }
  }

  private void markStaged(Path dest, String version) {
    stagedJar = dest;
    stagedVersion = version;
  }

  static boolean isSha256(String sha) {
    if (sha == null) return false;
    String s = sha.trim();
    if (s.length() != 64) return false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) return false;
    }
    return true;
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
      if (version != null && !normalizeVersion(version).equals(expectedVersion)) {
        throw new IllegalStateException(
            "jar version " + version + " does not match manifest " + expectedVersion);
      }
      if (value(text, "main") == null) {
        throw new IllegalStateException("downloaded jar has no main class in plugin.yml");
      }
    }
  }

  /** Same-directory temp + atomic move, so a reader never sees a half file. */
  private static void moveInto(Path from, Path to) throws IOException {
    Path tmp = to.resolveSibling(to.getFileName() + ".new");
    Files.copy(from, tmp, StandardCopyOption.REPLACE_EXISTING);
    try {
      Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException e) {
      Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
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

  private static String string(JsonObject manifest, String key) {
    if (!manifest.has(key) || manifest.get(key).isJsonNull()) return null;
    try {
      return manifest.get(key).getAsString().trim();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * A version doubles as a file name, so it is restricted to semver-ish
   * characters. Anything else (path separators, quotes, spaces) is rejected
   * rather than sanitised: the manifest is remote input and the update folder is
   * the one place it must never be able to write outside of.
   */
  private static final java.util.regex.Pattern VERSION_PATTERN =
      java.util.regex.Pattern.compile("\\d+(\\.\\d+)*(-[0-9A-Za-z][0-9A-Za-z.\\-]*)?(\\+[0-9A-Za-z][0-9A-Za-z.\\-]*)?$");

  /** Trims an optional leading "v" and rejects anything that is not a version. */
  static String normalizeVersion(String raw) {
    if (raw == null) return null;
    String v = raw.trim();
    if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
    return VERSION_PATTERN.matcher(v).matches() ? v : null;
  }

  /**
   * True when the manifest sha matches the currently loaded jar, i.e. a
   * same-version manifest really is the build already running. A same-version
   * manifest with a DIFFERENT sha means a re-build must stage.
   */
  private boolean currentJarMatches(String sha) {
    if (sha == null || sha.isBlank()) return false;
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

  /** Short id of the jar currently loaded, so status shows what is really running. */
  public String runningShaShort() {
    try {
      Path jar = plugin.pluginFile().toPath();
      return jar.toFile().exists() ? shortSha(jar) : "?";
    } catch (Exception e) {
      return "?";
    }
  }

  private static String shortSha(Path file) {
    try {
      return shortSha(sha256(file));
    } catch (Exception e) {
      return "?";
    }
  }

  private static String shortSha(String full) {
    if (full == null) return "?";
    full = full.trim();
    return full.isEmpty() ? "?" : full.substring(0, Math.min(8, full.length()));
  }

  /** Stages are applied here: swap in place when possible, else via the detached helper. */
  public void apply(CommandSender sender) {
    if (!hasStaged()) {
      sender.sendMessage("§c[MiaucraftBridge] No staged update - run §f/bridge update §cfirst.");
      report("warn", "update.no_staged", "Update apply requested with nothing staged.", null);
      return;
    }
    if (!applying.compareAndSet(false, true)) {
      sender.sendMessage("§c[MiaucraftBridge] An update is already being applied.");
      report("warn", "update.busy", "Update apply requested while one is already running.", null);
      return;
    }
    Path target = ownJar();
    if (target == null) {
      applying.set(false);
      sender.sendMessage("§c[MiaucraftBridge] Can't locate my own jar (exploded/dev run?) - not applying.");
      return;
    }
    String version = stagedVersion;
    Path staged = stagedJar;
    String stagedShaShort = shortSha(staged);
    try {
      if (installInPlace(staged, target)) {
        stagedJar = null;
        stagedVersion = null;
        lastResult = "installed v" + version + " in place";
        sender.sendMessage("§a[MiaucraftBridge] Installed v" + version + " (sha " + stagedShaShort
            + ") - the server will restart now.");
        log.info("update " + lastResult + " (" + target + ").");
        report("info", "update.applied", "Installed v" + version + " in place.", version);
        Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
        return;
      }
      Path conf = writeConf(target);
      launchHelper(conf);
      stagedJar = null;
      stagedVersion = null;
      sender.sendMessage("§a[MiaucraftBridge] Installing v" + version + " (sha " + stagedShaShort
          + ") - the server will restart now.");
      log.info("Applying update v" + version + " (jar -> " + target + "), restarting.");
      report("info", "update.applied", "Installing v" + version + " via the detached helper.", version);
      Bukkit.getScheduler().runTask(plugin, Bukkit::shutdown);
    } catch (Exception e) {
      applying.set(false);
      lastResult = "apply failed: " + brief(e);
      log.warning("update " + lastResult);
      sender.sendMessage("§c[MiaucraftBridge] Update apply failed: " + brief(e));
      report("error", "update.failed", "Update apply failed: " + brief(e), version);
    }
  }

  /** Publishes an update event when diagnostics are available. */
  private void report(String level, String event, String message, String version) {
    BridgeOps ops = plugin.opsApi();
    if (ops == null) return;
    com.google.gson.JsonObject details = new com.google.gson.JsonObject();
    if (version != null) details.addProperty("version", version);
    ops.report(level, "update", event, message, details);
  }

  /**
   * Replaces the running jar with the staged copy while the server is still
   * up. A move swaps the directory entry (the old inode stays valid for the
   * running JVM), so no detached helper is needed on POSIX hosts. Returns
   * false when the platform refuses it (e.g. a locked file on Windows), so
   * the caller can defer to the detached helper.
   */
  private boolean installInPlace(Path staged, Path target) throws Exception {
    if (!staged.equals(target)) {
      try {
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException e) {
        log.info("update: in-place install not possible (" + brief(e)
            + ") - using detached helper");
        return false;
      }
    } else {
      Files.deleteIfExists(staged);
    }
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
    // The relaunch line is written to a flat KEY=VALUE file and later run
    // through sh/cmd, so a newline would silently become a second entry.
    if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
      throw new IllegalStateException("update.restart-command must be a single line");
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
    // The staged jar stays on disk (it is the helper's classpath) until the
    // helper has copied it into place.
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
    return path == null ? "" : path.replace('\\', '/');
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase().contains("win");
  }

  public List<String> statusLines() {
    List<String> lines = new ArrayList<>();
    lines.add("§7updater: §f" + (enabled ? "on" : "off")
        + " §7(current §f" + currentVersion() + "§7 · sha §f" + runningShaShort()
        + "§7, auto-apply §f" + autoApply + "§7)");
    lines.add("§7update manifest: §f" + manifestUrl
        + (lastManifestSha.isBlank() ? "" : " §7· sha §f" + shortSha(lastManifestSha)));
    lines.add("§7update last check: §f" + lastResult);
    if (hasStaged()) {
      lines.add("§7staged update: §a" + stagedVersion + " §7(§f" + stagedJar.getFileName()
          + "§7 · sha §f" + shortSha(stagedJar) + "§7)");
    }
    return lines;
  }

  /**
   * Dotted-numeric compare with semver precedence: numeric parts compare as
   * numbers, a pre-release sorts BELOW the matching release (so 2.4.3-SNAPSHOT
   * never replaces a running 2.4.3), build metadata is ignored, and a missing
   * part counts as 0.
   */
  static int compareVersions(String a, String b) {
    Core va = parseVersion(a);
    Core vb = parseVersion(b);
    for (int i = 0; i < 3; i++) {
      int c = Integer.compare(va.nums[i], vb.nums[i]);
      if (c != 0) return c;
    }
    if (va.pre == null && vb.pre == null) return 0;
    if (va.pre == null) return 1;
    if (vb.pre == null) return -1;
    return comparePreRelease(va.pre, vb.pre);
  }

  private static final class Core {
    private final int[] nums = new int[3];
    private String pre;
  }

  private static Core parseVersion(String v) {
    Core core = new Core();
    if (v == null) return core;
    String s = v.trim();
    if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
    int plus = s.indexOf('+');
    if (plus >= 0) s = s.substring(0, plus);
    int dash = s.indexOf('-');
    if (dash >= 0) {
      core.pre = s.substring(dash + 1);
      s = s.substring(0, dash);
    }
    String[] parts = s.split("\\.");
    for (int i = 0; i < parts.length && i < 3; i++) {
      Integer n = parse(parts[i].trim());
      core.nums[i] = n == null ? 0 : n;
    }
    return core;
  }

  private static int comparePreRelease(String a, String b) {
    String[] as = a.split("[.\\-]");
    String[] bs = b.split("[.\\-]");
    int n = Math.max(as.length, bs.length);
    for (int i = 0; i < n; i++) {
      if (i >= as.length) return -1;
      if (i >= bs.length) return 1;
      String x = as[i];
      String y = bs[i];
      Integer ix = parse(x);
      Integer iy = parse(y);
      if (ix != null && iy != null) {
        int c = Integer.compare(ix, iy);
        if (c != 0) return c;
      } else if (ix != null) {
        return -1;
      } else if (iy != null) {
        return 1;
      } else {
        int c = x.compareToIgnoreCase(y);
        if (c != 0) return c;
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
