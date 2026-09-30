package com.xhvsh.miaucraftbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Renders Anvil regions to 512px PNG tiles and publishes them to the site's
 * public Supabase Storage bucket (live map phases 1-4). Tile geometry matches
 * the site's Leaflet page: key {@code <basePath>/<world>/<zoom>/<x>_<z>.png},
 * where zoom {@code maxZoom} is one 512px tile per region and zoom {@code n}
 * is composited over 2^(maxZoom-n) regions.
 *
 * <p>The render loop runs entirely on worker threads: every region is parsed
 * straight from disk, shaded, merged into each zoom composite under a
 * per-composite lock, encoded and queued for upload. The main thread is only
 * used to capture the world spawn on start and to schedule the change sweep.
 * Nothing here touches net.minecraft.
 */
final class LiveMap {

  private static final class State {
    Map<String, String> uploadedSha = new HashMap<>();
  }

  private static final class RegionCoord {
    final String world;
    final int x;
    final int z;

    RegionCoord(String world, int x, int z) {
      this.world = world;
      this.x = x;
      this.z = z;
    }

    long key() {
      return ((long) x << 32) ^ ((long) z & 0xffffffffL) ^ (world.hashCode() & 0xffffffffL);
    }
  }

  private final JavaPlugin plugin;
  private final Logger log;
  private final Gson gson = new Gson();
  private final BooleanSupplier masterEnabled;

  private final Path mapDir;
  private final Path stateFile;
  private final Path tilesDir;

  private final String bucket;
  private final String basePath;
  private final int maxZoom;
  private final int radius;
  private final int sweepSeconds;
  private final int renderThreads;
  private final int uploadConcurrency;

  private final String[] worlds;
  private final Map<String, Integer> spawnX = new ConcurrentHashMap<>();
  private final Map<String, Integer> spawnZ = new ConcurrentHashMap<>();
  private final Map<String, Path> worldRegionDirs = new ConcurrentHashMap<>();

  private final SupabaseStorage storage;

  private final Set<Long> queuedOngoing = ConcurrentHashMap.newKeySet();
  private final Map<Long, Object> compositeLocks = new ConcurrentHashMap<>();
  private final Map<String, String> uploadedSha = new ConcurrentHashMap<>();
  private final Map<Long, Long> regionMtimes = new ConcurrentHashMap<>();

  private final AtomicLong rendered = new AtomicLong();
  private final AtomicInteger uploadFailures = new AtomicInteger();
  private final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());

  private ThreadPoolExecutor renderers;
  private ThreadPoolExecutor uploaders;
  private volatile boolean running;
  private BukkitTask sweepTask;

  LiveMap(JavaPlugin plugin, SupabaseStorage storage, BooleanSupplier masterEnabled) {
    this.plugin = plugin;
    this.log = plugin.getLogger();
    this.storage = storage;
    this.masterEnabled = masterEnabled;

    this.mapDir = plugin.getDataFolder().toPath().resolve("map");
    this.stateFile = mapDir.resolve("map-state.json");
    this.tilesDir = mapDir.resolve("tiles");

    this.bucket = plugin.getConfig().getString("map.bucket", "maps");
    this.basePath = plugin.getConfig().getString("map.base-path", "");
    int maxZoom = plugin.getConfig().getInt("map.max-zoom", 3);
    this.maxZoom = Math.max(1, Math.min(5, maxZoom));
    this.radius = Math.max(512, plugin.getConfig().getInt("map.radius", 1000));
    this.sweepSeconds = Math.max(30, plugin.getConfig().getInt("map.update-sweep-seconds", 120));

    int cores = Runtime.getRuntime().availableProcessors();
    int threads = plugin.getConfig().getInt("map.max-render-threads", -1);
    this.renderThreads = threads <= 0 ? Math.max(1, Math.min(6, cores / 2)) : Math.max(1, threads);
    this.uploadConcurrency = Math.max(1, plugin.getConfig().getInt("map.upload-concurrency", 2));

    List<String> worlds = plugin.getConfig().getStringList("map.worlds");
    this.worlds = worlds.isEmpty() ? new String[] {"world"} : worlds.toArray(new String[0]);
  }

  // -------------------------------------------------------------- lifecycle

  /** Captures spawns + world folders (main thread) and starts the queues. */
  void start() {
    try {
      Files.createDirectories(tilesDir);
    } catch (IOException e) {
      log.warning("map: cannot create " + tilesDir + ": " + e.getMessage());
    }

    loadState();

    for (String world : worlds) {
      org.bukkit.World bw = Bukkit.getWorld(world);
      if (bw == null) {
        log.warning("map: world '" + world + "' is not loaded, skipping");
        continue;
      }
      org.bukkit.Location spawn = bw.getSpawnLocation();
      spawnX.put(world, spawn.getBlockX());
      spawnZ.put(world, spawn.getBlockZ());
      worldRegionDirs.put(world, bw.getWorldFolder().toPath().resolve("region"));
    }

    running = true;
    renderers = new ThreadPoolExecutor(renderThreads, renderThreads, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), daemonFactory("map-render"));
    uploaders = new ThreadPoolExecutor(uploadConcurrency, uploadConcurrency, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), daemonFactory("map-upload"));
    uploaders.allowCoreThreadTimeOut(true);

    for (String world : spawnX.keySet()) {
      enqueueRadius(world, radius);
    }

    sweepTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
        this::sweep, 20L * sweepSeconds, 20L * sweepSeconds);

    publishWorldsMeta();
    log.info("map: enabled worlds=" + String.join(",", spawnX.keySet())
        + " radius=" + radius + " threads=" + renderThreads
        + " zooms=0.." + maxZoom + " bucket=" + bucket);
  }

  void stop() {
    running = false;
    if (sweepTask != null) {
      try {
        sweepTask.cancel();
      } catch (Exception ignored) {
      }
      sweepTask = null;
    }
    if (renderers != null) {
      renderers.shutdownNow();
    }
    drainUploads();
    if (uploaders != null) {
      uploaders.shutdownNow();
    }
    saveState();
  }

  // ------------------------------------------------------------- public API

  long renderedCount() {
    return rendered.get();
  }

  int pendingCount() {
    return renderers == null ? 0 : renderers.getQueue().size();
  }

  boolean isRunning() {
    return running && renderers != null && !renderers.isShutdown() && masterEnabled.getAsBoolean();
  }

  int uploadFailures() {
    return uploadFailures.get();
  }

  long lastActivityMs() {
    return lastActivityMs.get();
  }

  /** Re-renders the region containing the given block coords. */
  void rerenderAround(int bx, int bz, String world) {
    enqueueAt(world, Math.floorDiv(bx, 512), Math.floorDiv(bz, 512));
  }

  /** Renders (or re-renders) everything within {@code radius} blocks of spawn on the first configured world. */
  void renderRadius(int radiusBlocks) {
    String world = worlds.length > 0 ? worlds[0] : "world";
    enqueueRadius(world, radiusBlocks > 0 ? radiusBlocks : this.radius);
  }

  private void enqueueRadius(String world, int radiusBlocks) {
    Integer sx = spawnX.get(world);
    Integer sz = spawnZ.get(world);
    if (sx == null || sz == null) {
      log.warning("map: no spawn captured for world '" + world + "', cannot render radius");
      return;
    }
    int regX0 = Math.floorDiv(sx - radiusBlocks, 512);
    int regX1 = Math.floorDiv(sx + radiusBlocks, 512);
    int regZ0 = Math.floorDiv(sz - radiusBlocks, 512);
    int regZ1 = Math.floorDiv(sz + radiusBlocks, 512);
    int cx = (regX0 + regX1) / 2;
    int cz = (regZ0 + regZ1) / 2;
    List<RegionCoord> regions = new ArrayList<>();
    for (int rx = regX0; rx <= regX1; rx++) {
      for (int rz = regZ0; rz <= regZ1; rz++) {
        regions.add(new RegionCoord(world, rx, rz));
      }
    }
    // nearest-first so the centre (where visitors usually zoom in) is ready
    // before the edges of a big radius render
    regions.sort((a, b) -> {
      int da = (a.x - cx) * (a.x - cx) + (a.z - cz) * (a.z - cz);
      int db = (b.x - cx) * (b.x - cx) + (b.z - cz) * (b.z - cz);
      return Integer.compare(da, db);
    });
    for (RegionCoord rc : regions) {
      enqueue(rc);
    }
    log.info("map: enqueued " + regions.size() + " region(s) for radius " + radiusBlocks
        + " around spawn of '" + world + "'");
  }

  private void enqueueAt(String world, int regX, int regZ) {
    enqueue(new RegionCoord(world, regX, regZ));
  }

  private void enqueue(RegionCoord rc) {
    long key = rc.key();
    if (!queuedOngoing.add(key)) {
      return; // already queued or in flight
    }
    lastActivityMs.set(System.currentTimeMillis());
    try {
      renderers.execute(() -> {
        try {
          renderRegion(rc);
        } finally {
          queuedOngoing.remove(key);
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException e) {
      queuedOngoing.remove(key); // shutting down: never got queued
    }
  }

  // ------------------------------------------------------------------- sweep

  private void sweep() {
    if (!running || !masterEnabled.getAsBoolean()) {
      return;
    }
    for (String world : worldRegionDirs.keySet()) {
      Path regionDir = worldRegionDirs.get(world);
      if (!Files.isDirectory(regionDir)) {
        continue;
      }
      try (var stream = Files.list(regionDir)) {
        stream.filter(p -> p.getFileName().toString().endsWith(".mca")).forEach(file -> {
          int[] rc = coordsFromName(file.getFileName().toString());
          if (rc == null) {
            return;
          }
          int[] worldRc = rc;
          long mtime;
          try {
            mtime = Files.getLastModifiedTime(file).toMillis();
          } catch (IOException e) {
            return;
          }
          long key = new RegionCoord(world, worldRc[0], worldRc[1]).key();
          Long known = regionMtimes.get(key);
          if (known == null) {
            // baseline pass: remember without re-rendering
            regionMtimes.put(key, mtime);
            return;
          }
          if (mtime > known) {
            regionMtimes.put(key, mtime);
            enqueue(new RegionCoord(world, worldRc[0], worldRc[1]));
          }
        });
      } catch (IOException e) {
        log.warning("map: sweep of " + regionDir + " failed: " + e.getMessage());
      }
    }
  }

  // ------------------------------------------------------------- rendering

  private void renderRegion(RegionCoord rc) {
    if (!running || !masterEnabled.getAsBoolean()) {
      return;
    }
    Path regionDir = worldRegionDirs.get(rc.world);
    if (regionDir == null) {
      return;
    }
    Path file = regionDir.resolve("r." + rc.x + "." + rc.z + ".mca");
    if (!Files.isRegularFile(file)) {
      return; // no such region (e.g. beyond the world border)
    }
    AnvilRegion region;
    try {
      region = new AnvilRegion(file);
    } catch (IOException e) {
      log.warning("map: cannot open " + file + ": " + e.getMessage());
      return;
    }
    BufferedImage tile;
    try {
      tile = MapRegionRenderer.render(regionDir, region);
    } catch (IOException e) {
      log.warning("map: render of " + file + " failed: " + e.getMessage());
      return;
    } finally {
      region.close();
    }

    int changed = mergeComposites(rc, tile);
    rendered.incrementAndGet();
    lastActivityMs.set(System.currentTimeMillis());
    if (changed > 0 || rendered.get() == 1) {
      publishWorldsMeta();
    }
    saveStateAsync();
  }

  /** Merges the fresh region tile into the composite for each zoom, then queues uploads for changed tiles. */
  private int mergeComposites(RegionCoord rc, BufferedImage tile) {
    int changed = 0;
    for (int zoom = maxZoom; zoom >= 0; zoom--) {
      int step = 1 << (maxZoom - zoom);
      int size = MapRegionRenderer.TILE / step;
      int scX = Math.floorDiv(rc.x, step);
      int scZ = Math.floorDiv(rc.z, step);
      int offX = Math.floorMod(rc.x, step) * size;
      int offZ = Math.floorMod(rc.z, step) * size;
      Path compFile = SupabaseStorage.localTilePath(tilesDir, rc.world, zoom, scX, scZ);
      String key = SupabaseStorage.key(basePath, rc.world, zoom, scX, scZ);

      Object lock = compositeLocks.computeIfAbsent(
          ((long) (rc.world.hashCode() & 0xffffffffL) << 32) ^ zoom ^ ((long) scX << 32) ^ scZ,
          k -> new Object());
      synchronized (lock) {
        try {
          BufferedImage comp = zoom == maxZoom
              ? tile
              : TilePng.decode(compFile, MapRegionRenderer.TILE, MapRegionRenderer.TILE);
          TilePng.drawScaled(tile, comp, offX, offZ, step, size);
          byte[] bytes = TilePng.encode(comp);
          writeLocal(compFile, bytes);
          String sha = sha256(bytes);
          if (sha.equals(uploadedSha.get(key))) {
            continue; // unchanged tile
          }
          changed++;
          uploadAsync(key, bytes, sha);
        } catch (IOException e) {
          log.warning("map: composite " + compFile + " failed: " + e.getMessage());
        }
      }
    }
    return changed;
  }

  private void uploadAsync(String key, byte[] bytes, String sha) {
    try {
      uploaders.execute(() -> {
        try {
          storage.upload(bucket, key, bytes, "image/png");
          uploadedSha.put(key, sha);
        } catch (IOException e) {
          uploadFailures.incrementAndGet();
          log.warning("map: upload " + key + " failed: " + e.getMessage());
        }
      });
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
      // shutting down - tile stays on disk and will re-upload when it changes again
    }
  }

  private void drainUploads() {
    if (uploaders == null) {
      return;
    }
    long deadline = System.currentTimeMillis() + 5000;
    while (System.currentTimeMillis() < deadline) {
      if (uploaders.getActiveCount() == 0
          && !uploaders.isShutdown()
          && uploaders.getQueue().isEmpty()) {
        return;
      }
      try {
        Thread.sleep(50L);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private void writeLocal(Path file, byte[] bytes) throws IOException {
    Files.createDirectories(file.getParent());
    Files.write(file, bytes);
  }

  // ---------------------------------------------------------------- state

  private void loadState() {
    try {
      if (!Files.isRegularFile(stateFile)) {
        return;
      }
      State state = gson.fromJson(Files.readString(stateFile), new TypeToken<State>() {
      }.getType());
      if (state != null && state.uploadedSha != null) {
        uploadedSha.putAll(state.uploadedSha);
      }
    } catch (IOException e) {
      log.warning("map: cannot read state: " + e.getMessage());
    }
  }

  private void saveStateAsync() {
    Thread t = new Thread(this::saveState, "map-state-writer");
    t.setDaemon(true);
    t.start();
  }

  private void saveState() {
    try {
      State state = new State();
      state.uploadedSha = new HashMap<>(uploadedSha);
      Files.createDirectories(stateFile.getParent());
      Files.write(stateFile, gson.toJson(state).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (IOException e) {
      log.warning("map: cannot save state: " + e.getMessage());
    }
  }

  /** Publishes a tiny per-world metadata file the site reads to build the map. */
  private void publishWorldsMeta() {
    JsonObject root = new JsonObject();
    for (String world : spawnX.keySet()) {
      JsonObject w = new JsonObject();
      w.addProperty("spawnX", spawnX.get(world));
      w.addProperty("spawnZ", spawnZ.get(world));
      w.addProperty("maxZoom", maxZoom);
      w.addProperty("lastUpdate", System.currentTimeMillis());
      w.addProperty("renderedRegions", rendered.get());
      root.add(world, w);
    }
    byte[] bytes = root.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    uploadAsync(keyForFile("worlds.json"), bytes, sha256(bytes));
  }

  private String keyForFile(String name) {
    String prefix = basePath == null || basePath.isBlank() ? "" : basePath.strip().replace("/", "");
    return prefix.isEmpty() ? name : prefix + "/" + name;
  }

  // -------------------------------------------------------------- low-level

  private static int[] coordsFromName(String name) {
    int[] rc = AnvilRegion.parseCoords(name);
    return rc[0] == Integer.MIN_VALUE ? null : rc;
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(bytes);
      StringBuilder sb = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return sb.toString();
    } catch (Exception e) {
      return "";
    }
  }

  private static ThreadFactory daemonFactory(String prefix) {
    AtomicInteger counter = new AtomicInteger();
    return runnable -> {
      Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
      t.setDaemon(true);
      return t;
    };
  }
}