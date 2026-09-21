package com.xhvsh.miaucraftbridge;

import java.nio.charset.StandardCharsets;import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs as its own JVM after the server has shut down, so it can replace the
 * plugin jar that the (now dead) server had locked. It is launched with the
 * <b>staged</b> jar on its classpath - never the target - which keeps the
 * target free to overwrite.
 *
 * <p>Deliberately dependency-free (JDK only): no Bukkit/Paper classes are
 * available in this process.
 */
public final class UpdateHelper {

  private UpdateHelper() {
  }

  public static void main(String[] args) {
    if (args.length < 1) {
      return;
    }
    Path conf = Path.of(args[0]);
    Path log = conf.resolveSibling("apply-update.log");
    try {
      Map<String, String> p = load(conf);
      log(log, "updater: started");

      long pid = Long.parseLong(p.getOrDefault("serverPid", "-1"));
      Path staged = Path.of(p.get("staged"));
      Path target = Path.of(p.get("target"));
      boolean relaunch = Boolean.parseBoolean(p.getOrDefault("relaunch", "true"));

      waitForExit(pid, log);

      // Give the OS a moment to fully release the jar handle.
      Thread.sleep(800);
      Files.copy(staged, target, StandardCopyOption.REPLACE_EXISTING);
      log(log, "updater: replaced " + target);

      if (relaunch) {
        relaunch(p, log);
      } else {
        log(log, "updater: relaunch disabled - start the server manually");
      }
    } catch (Exception e) {
      log(log, "updater: FAILED: " + e);
    }
  }

  private static void waitForExit(long pid, Path log) throws InterruptedException {
    while (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
      Thread.sleep(400);
    }
    log(log, "updater: server process " + pid + " exited");
  }

  private static void relaunch(Map<String, String> p, Path log) throws Exception {
    String command = p.getOrDefault("command", "").trim();
    boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
    ProcessBuilder pb;
    if (!command.isBlank()) {
      pb = win ? new ProcessBuilder("cmd.exe", "/c", command)
          : new ProcessBuilder("sh", "-c", command);
    } else {
      List<String> cmd = new ArrayList<>();
      cmd.add(p.get("java"));
      for (int i = 0; ; i++) {
        String a = p.get("arg" + i);
        if (a == null) {
          break;
        }
        cmd.add(a);
      }
      pb = new ProcessBuilder(cmd);
    }

    String workdir = p.getOrDefault("workdir", "");
    if (!workdir.isBlank()) {
      pb.directory(Path.of(workdir).toFile());
    }
    Path out = log.getParent() == null
        ? Path.of("relaunch.log")
        : log.getParent().resolve("relaunch.log");
    pb.redirectErrorStream(true);
    pb.redirectOutput(ProcessBuilder.Redirect.appendTo(out.toFile()));
    pb.start();
    log(log, "updater: relaunched server");
  }

  /** Simple KEY=VALUE lines, split on the first '=' - no escape processing. */
  private static Map<String, String> load(Path conf) throws Exception {
    Map<String, String> map = new LinkedHashMap<>();
    for (String line : Files.readString(conf, StandardCharsets.UTF_8).split("\\R")) {
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      int eq = line.indexOf('=');
      if (eq > 0) {
        map.put(line.substring(0, eq).trim(), line.substring(eq + 1));
      }
    }
    return map;
  }

  private static void log(Path log, String msg) {
    try {
      Files.writeString(log, Instant.now() + " " + msg + System.lineSeparator(),
          StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception ignored) {
    }
  }
}
