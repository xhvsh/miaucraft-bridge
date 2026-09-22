package com.xhvsh.miaucraftbridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Reads the top surface biome of every generated chunk straight from the
 * on-disk level data (.mca region files) and mirrors it into the {@code biomes}
 * table. Per-region mtime watermarks in PersistedState make re-scans cheap:
 * only regions that were written to since the last pass are re-read, so a full
 * world is filled in progressively (center-out from spawn) without hogging the
 * server or re-doing work.
 */
public final class BiomeScanner {

  private static final List<String> DIMENSIONS = List.of("overworld", "nether", "end");

  private final SinkManager sinks;
  private final PersistedState state;
  private final Supplier<RemoteConfig> config;
  private final Logger log;

  private final AtomicBoolean scanning = new AtomicBoolean(false);
  private volatile long lastScanMs;
  private volatile long sessionRegions;
  private volatile long sessionChunks;
  private volatile String lastResult = "never scanned";

  public BiomeScanner(SinkManager sinks, PersistedState state, Supplier<RemoteConfig> config, Logger log) {
    this.sinks = sinks;
    this.state = state;
    this.config = config;
    this.log = log;
  }

  /** Async-friendly: guard once, run one budgeted pass. Never blocks callers. */
  public void scan() {
    RemoteConfig cfg = config.get();
    if (!cfg.collectorEnabled("biomes")) return;
    if (!scanning.compareAndSet(false, true)) return;
    try {
      int budget = Math.max(1, cfg.collectorInt("biomes", "regions-per-scan", 3));
      long maxRadius = Math.max(0, cfg.collectorLong("biomes", "max-blocks-radius", 0));
      scanOnePass(budget, maxRadius);
      lastScanMs = System.currentTimeMillis();
    } catch (Exception ex) {
      log.log(Level.WARNING, "Biome scan failed", ex);
    } finally {
      scanning.set(false);
    }
  }

  private void scanOnePass(int budget, long maxRadius) {
    File worldFolder = Bukkit.getWorlds().stream().findFirst()
        .map(w -> w.getWorldFolder()).orElse(null);
    if (worldFolder == null) return;

    int done = 0;
    for (String dim : DIMENSIONS) {
      if (done >= budget) break;
      File regionDir = regionDir(worldFolder, dim);
      if (regionDir == null || !regionDir.isDirectory()) continue;
      File[] files = regionDir.listFiles((dir, name) -> name.endsWith(".mca"));
      if (files == null) continue;

      List<SizedFile> ordered = centerFirst(files);
      for (SizedFile sf : ordered) {
        if (done >= budget) break;
        if (!upToDate(dim, sf)) continue;
        try {
          McRegion region = McRegion.open(sf.file);
          List<McRegion.ChunkRecord> rows = region.scan();
          int added = upsert(rows, dim, maxRadius);
          done++;
          sessionRegions++;
          sessionChunks += rows.size();
          markScanned(dim, sf.file, sf.lastModified);
          lastResult = dim + ": " + rows.size() + " chunk(s) from " + sf.file.getName()
              + (added > 0 ? "" : " (unchanged)");
        } catch (Exception ex) {
          log.log(Level.WARNING, "Biome scan failed for region " + sf.file, ex);
        }
      }
    }
  }

  private boolean upToDate(String dim, SizedFile sf) {
    synchronized (state) {
      JsonObject wm = watermark();
      JsonObject perDim = perDim(wm, dim);
      JsonElement el = perDim.get(sf.file.getName());
      if (el != null && el.isJsonPrimitive()) {
        try {
          if (el.getAsLong() >= sf.lastModified) return false;
        } catch (Exception ignored) {
        }
      }
      return true;
    }
  }

  private void markScanned(String dim, File file, long mtime) {
    synchronized (state) {
      perDim(watermark(), dim).addProperty(file.getName(), mtime);
    }
  }

  private JsonObject watermark() {
    JsonObject root = state.object("biomes");
    JsonElement el = root.get("watermark");
    if (el == null || !el.isJsonObject()) {
      el = new JsonObject();
      root.add("watermark", el);
    }
    return el.getAsJsonObject();
  }

  private JsonObject perDim(JsonObject wm, String dim) {
    JsonElement el = wm.get(dim);
    if (el == null || !el.isJsonObject()) {
      el = new JsonObject();
      wm.add(dim, el);
    }
    return el.getAsJsonObject();
  }

  private synchronized int upsert(List<McRegion.ChunkRecord> rows, String dim, long maxRadius) {
    int added = 0;
    for (McRegion.ChunkRecord r : rows) {
      if (maxRadius > 0) {
        double cx = (double) r.chunkX() * 16.0 + 8.0;
        double cz = (double) r.chunkZ() * 16.0 + 8.0;
        if (Math.hypot(cx, cz) > maxRadius) continue;
      }
      JsonObject row = new JsonObject();
      row.addProperty("dimension", dim);
      row.addProperty("chunk_x", r.chunkX());
      row.addProperty("chunk_z", r.chunkZ());
      row.addProperty("biome", r.biome());
      row.addProperty("updated_at", Instant.now().toString());
      sinks.sink("biomes", "dimension,chunk_x,chunk_z", true, "dimension", "chunk_x", "chunk_z").add(row);
      added++;
    }
    return added;
  }

  static File regionDir(File worldFolder, String dim) {
    return switch (dim) {
      case "overworld" -> new File(worldFolder, "region");
      case "nether" -> new File(worldFolder, "DIM-1" + File.separator + "region");
      case "end" -> new File(worldFolder, "DIM1" + File.separator + "region");
      default -> null;
    };
  }

  private List<SizedFile> centerFirst(File[] files) {
    List<SizedFile> out = new ArrayList<>(files.length);
    for (File f : files) {
      String n = f.getName();
      int a = n.indexOf('.') + 1;
      int b = n.indexOf('.', a);
      int c = n.indexOf('.', b + 1);
      if (a <= 0 || b <= a || c <= b) continue;
      try {
        int rx = Integer.parseInt(n.substring(a, b));
        int rz = Integer.parseInt(n.substring(b + 1, c));
        out.add(new SizedFile(f, f.lastModified(), rx, rz));
      } catch (NumberFormatException ignored) {
      }
    }
    out.sort(Comparator
        .comparingInt((SizedFile s) -> Math.max(Math.abs(s.rx), Math.abs(s.rz)))
        .thenComparingInt(s -> Math.abs(s.rx) + Math.abs(s.rz))
        .thenComparing(s -> s.file.getName()));
    return out;
  }

  private record SizedFile(File file, long lastModified, int rx, int rz) {
  }

  /** Drops the watermark for one dimension (or all) so the next scan re-reads its regions. */
  public void rescan(String dim) {
    synchronized (state) {
      JsonObject wm = watermark();
      if (dim == null || dim.isBlank() || dim.equals("all")) {
        wm.entrySet().clear();
      } else if (DIMENSIONS.contains(dim)) {
        wm.remove(dim);
      }
    }
  }

  public String lastResult() {
    return lastResult;
  }

  public List<String> statusLines() {
    RemoteConfig cfg = config.get();
    List<String> lines = new ArrayList<>();
    lines.add("§7biomes: §f" + (cfg.collectorEnabled("biomes") ? "on" : "off")
        + " §7(last scan §f" + ago(lastScanMs) + "§7, regions §f" + sessionRegions
        + "§7, chunks §f" + sessionChunks + "§7)");
    lines.add("§7last region: §f" + lastResult);
    return lines;
  }

  private String ago(long millis) {
    if (millis <= 0) return "never";
    long seconds = (System.currentTimeMillis() - millis) / 1000L;
    return seconds + "s ago";
  }
}