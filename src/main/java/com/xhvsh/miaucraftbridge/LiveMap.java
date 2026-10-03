package com.xhvsh.miaucraftbridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
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
 * Live map engine (phases 1-4). Data no longer comes from .mca files: every
 * chunk is captured as a thread-safe {@link ChunkSnapshot} on the main thread
 * when it loads or a block in it changes, colourised off-thread into a
 * {@link MapChunk}, and stored in memory. A changed chunk marks its region
 * (512x512 blocks) dirty, which re-renders the region tile and merges it into
 * each zoom composite before the changed tiles are uploaded to Supabase.
 *
 * <p>On start the already-generated chunks around spawn are loaded in small
 * main-thread batches (the {@code map.bootstrap-num-chunks-per-tick} config),
 * and every chunk the server loads while players explore is captured for free,
 * so the map grows live. Tile geometry is unchanged: key
 * {@code <basePath>/<world>/<zoom>/<x>_<z>.png}, zoom {@code maxZoom} is one
 * tile per region, zoom {@code n} composites 2^(maxZoom-n) regions.
 */
final class LiveMap {

  /** Coordinates of a chunk waiting for its once-per-tick capture. */
  private final Map<Long, Boolean> pendingCapture = new ConcurrentHashMap<>();

  private static final class MapEvents implements Listener {
    private final LiveMap map;

    MapEvents(LiveMap map) {
      this.map = map;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
      map.onChunkLoaded(e.getChunk());
    }

    @EventHandler
    public void onPlace(BlockPlaceEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onMultiPlace(BlockMultiPlaceEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onFromTo(BlockFromToEvent e) {
      map.onBlockChanged(e.getBlock());
      map.onBlockChanged(e.getToBlock());
    }

    @EventHandler
    public void onFluid(FluidLevelChangeEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent e) {
      for (Block b : e.blockList()) map.onBlockChanged(b);
    }

    @EventHandler
    public void onEntityExplode(EntityExplodeEvent e) {
      for (Block b : e.blockList()) map.onBlockChanged(b);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onGrow(BlockGrowEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onSpread(BlockSpreadEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onForm(BlockFormEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onEntityBlockForm(EntityBlockFormEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onFade(BlockFadeEvent e) {
      map.onBlockChanged(e.getBlock());
    }

    @EventHandler
    public void onBurn(BlockBurnEvent e) {
      map.onBlockChanged(e.getBlock());
    }
  }

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

  private final Path tilesDir;
  private final Path stateFile;

  private final String bucket;
  private final String basePath;
  private final int maxZoom;
  private final int radius;
  private final int bootstrapPerTick;
  private final int renderThreads;
  private final int uploadConcurrency;

  private final String[] worlds;
  private final Map<String, Integer> spawnX = new ConcurrentHashMap<>();
  private final Map<String, Integer> spawnZ = new ConcurrentHashMap<>();

  /** Captured live chunks, keyed by {@link #terrainKey}. */
  private final Map<Long, MapChunk> tracker = new ConcurrentHashMap<>();
  /** Long-live pump counters for status. */
  private final AtomicLong captured = new AtomicLong();

  private final SupabaseStorage storage;

  private final Set<Long> queuedOngoing = ConcurrentHashMap.newKeySet();
  private final Map<Long, Object> compositeLocks = new ConcurrentHashMap<>();
  private final Map<String, String> uploadedSha = new ConcurrentHashMap<>();

  private final AtomicLong rendered = new AtomicLong();
  private final AtomicInteger uploadFailures = new AtomicInteger();
  private final AtomicLong lastActivityMs = new AtomicLong(System.currentTimeMillis());

  private ThreadPoolExecutor renderers;
  private ThreadPoolExecutor uploaders;
  private volatile boolean running;
  private final List<BukkitTask> bootstrapTasks = new ArrayList<>();

  LiveMap(JavaPlugin plugin, SupabaseStorage storage, BooleanSupplier masterEnabled) {
    this.plugin = plugin;
    this.log = plugin.getLogger();
    this.storage = storage;
    this.masterEnabled = masterEnabled;

    this.tilesDir = plugin.getDataFolder().toPath().resolve("map").resolve("tiles");
    this.stateFile = plugin.getDataFolder().toPath().resolve("map").resolve("map-state.json");

    this.bucket = plugin.getConfig().getString("map.bucket", "maps");
    this.basePath = plugin.getConfig().getString("map.base-path", "");
    int maxZoom = plugin.getConfig().getInt("map.max-zoom", 3);
    this.maxZoom = Math.max(1, Math.min(5, maxZoom));
    this.radius = Math.max(512, plugin.getConfig().getInt("map.radius", 1000));
    this.bootstrapPerTick = Math.max(1,
        plugin.getConfig().getInt("map.bootstrap-num-chunks-per-tick", 4));

    int cores = Runtime.getRuntime().availableProcessors();
    int threads = plugin.getConfig().getInt("map.max-render-threads", -1);
    this.renderThreads = threads <= 0 ? Math.max(1, Math.min(6, cores / 2)) : Math.max(1, threads);
    this.uploadConcurrency = Math.max(1, plugin.getConfig().getInt("map.upload-concurrency", 2));

    List<String> worlds = plugin.getConfig().getStringList("map.worlds");
    this.worlds = worlds.isEmpty() ? new String[] {"world"} : worlds.toArray(new String[0]);
  }

  // -------------------------------------------------------------- lifecycle

  /** Captures spawns (main thread) and starts the queues + capture listeners. */
  void start() {
    try {
      Files.createDirectories(tilesDir);
    } catch (IOException e) {
      log.warning("map: cannot create " + tilesDir + ": " + e.getMessage());
    }

    loadState();

    for (String world : worlds) {
      World bw = Bukkit.getWorld(world);
      if (bw == null) {
        log.warning("map: world '" + world + "' is not loaded, skipping");
        continue;
      }
      org.bukkit.Location spawn = bw.getSpawnLocation();
      spawnX.put(world, spawn.getBlockX());
      spawnZ.put(world, spawn.getBlockZ());
    }

    running = true;
    renderers = new ThreadPoolExecutor(renderThreads, renderThreads, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), daemonFactory("map-render"));
    uploaders = new ThreadPoolExecutor(uploadConcurrency, uploadConcurrency, 60L, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(), daemonFactory("map-upload"));
    uploaders.allowCoreThreadTimeOut(true);

    Bukkit.getPluginManager().registerEvents(new MapEvents(this), plugin);

    for (String world : spawnX.keySet()) {
      if (!masterEnabled.getAsBoolean()) {
        break;
      }
      bootstrap(world);
    }

    publishWorldsMeta();
    log.info("map: enabled worlds=" + String.join(",", spawnX.keySet())
        + " radius=" + radius + " threads=" + renderThreads
        + " zooms=0.." + maxZoom + " bucket=" + bucket
        + " colors=" + (MapColors.usingVanillaColors() ? "nms" : "builtin-table"));
  }

  void stop() {
    running = false;
    for (BukkitTask task : bootstrapTasks) {
      try {
        task.cancel();
      } catch (Exception ignored) {
      }
    }
    bootstrapTasks.clear();
    if (renderers != null) {
      renderers.shutdownNow();
    }
    drainUploads();
    if (uploaders != null) {
      uploaders.shutdownNow();
    }
    tracker.clear();
    pendingCapture.clear();
    queuedOngoing.clear();
    saveState();
  }

  // ------------------------------------------------------------- public API

  long renderedCount() {
    return rendered.get();
  }

  long capturedCount() {
    return captured.get();
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

  /** Captures this chunk's current state and renders whatever regions it touches. */
  void onChunkLoaded(Chunk chunk) {
    if (!running || !masterEnabled.getAsBoolean()) {
      return;
    }
    ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, true, false);
    try {
      renderers.execute(() -> captureChunk(chunk.getWorld().getName(), snapshot));
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
      // shutting down: nothing runs anymore
    }
  }

  /** Debounced to one capture per chunk per tick (a block edit burst = one capture). */
  void onBlockChanged(Block block) {
    if (!running || !masterEnabled.getAsBoolean()) {
      return;
    }
    Chunk chunk = block.getChunk();
    final String world = chunk.getWorld().getName();
    final int cx = chunk.getX();
    final int cz = chunk.getZ();
    long key = terrainKey(world, cx, cz);
    if (pendingCapture.putIfAbsent(key, Boolean.TRUE) != null) {
      return;
    }
    Bukkit.getScheduler().runTask(plugin, () -> {
      pendingCapture.remove(key);
      if (!running) {
        return;
      }
      Chunk current = null;
      World bw = Bukkit.getWorld(world);
      if (bw != null && bw.isChunkGenerated(cx, cz)) {
        current = bw.getChunkAt(cx, cz);
      }
      if (current != null && current.isLoaded()) {
        onChunkLoaded(current);
      }
    });
  }

  /** Re-renders the region containing the given block coords. */
  void rerenderAround(int bx, int bz, String world) {
    enqueueRegion(new RegionCoord(world, Math.floorDiv(bx, 512), Math.floorDiv(bz, 512)));
  }

  /** Renders (or re-renders) everything within {@code radiusBlocks} blocks of spawn on the first configured world. */
  void renderRadius(int radiusBlocks) {
    String world = worlds.length > 0 ? worlds[0] : "world";
    enqueueRadius(world, radiusBlocks > 0 ? radiusBlocks : this.radius);
  }

  /** Re-captures every currently loaded chunk of the given world. */
  void recaptureWorld(String world) {
    World bw = Bukkit.getWorld(world);
    if (bw == null) {
      log.warning("map: world '" + world + "' is not loaded");
      return;
    }
    for (Chunk chunk : bw.getLoadedChunks()) {
      if (chunk.isLoaded()) {
        onChunkLoaded(chunk);
      }
    }
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
      enqueueRegion(rc);
    }
    log.info("map: enqueued " + regions.size() + " region(s) for radius " + radiusBlocks
        + " around spawn of '" + world + "'");
  }

  private void enqueueRegion(RegionCoord rc) {
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

  // ------------------------------------------------------------- capture+render

  /** Off-thread: colourises the snapshot into the tracker and dirties the region. */
  private void captureChunk(String world, ChunkSnapshot snapshot) {
    if (!running || !masterEnabled.getAsBoolean()) {
      return;
    }
    try {
      MapChunk chunk = new MapChunk(snapshot.getX(), snapshot.getZ(),
          new MapChunk.SnapshotSampler(snapshot));
      int cx = snapshot.getX();
      int cz = snapshot.getZ();
      tracker.put(terrainKey(world, cx, cz), chunk);
      captured.incrementAndGet();
      lastActivityMs.set(System.currentTimeMillis());
      enqueueRegion(new RegionCoord(world, Math.floorDiv(cx, 32), Math.floorDiv(cz, 32)));
    } catch (Exception e) {
      log.warning("map: capture of chunk @x=" + snapshot.getX() + ",z=" + snapshot.getZ()
          + " failed: " + e.getMessage());
    }
  }

  private void renderRegion(RegionCoord rc) {
    if (!running || !masterEnabled.getAsBoolean()) {
      return;
    }
    List<MapChunk> chunks = new ArrayList<>();
    for (int sl = 0; sl < 32; sl++) {
      for (int sc = 0; sc < 32; sc++) {
        MapChunk m = tracker.get(terrainKey(rc.world, rc.x * 32 + sc, rc.z * 32 + sl));
        if (m != null) {
          chunks.add(m);
        }
      }
    }
    BufferedImage tile = MapRegionRenderer.render(chunks, rc.x, rc.z);
    if (MapRegionRenderer.isBlank(tile)) {
      return; // nothing captured for this region yet - upload nothing
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

  // ------------------------------------------------------------- bootstrap

  /** Loads the already-generated chunks around spawn in main-thread batches. */
  private void bootstrap(final String world) {
    Integer sx = spawnX.get(world);
    Integer sz = spawnZ.get(world);
    if (sx == null || sz == null) {
      return;
    }
    World bw = Bukkit.getWorld(world);
    if (bw == null) {
      return;
    }
    int cxs = Math.floorDiv(sx, 16);
    int czs = Math.floorDiv(sz, 16);
    int r = (radius + 15) / 16;
    List<int[]> coords = new ArrayList<>();
    int r2 = r * r;
    for (int dx = -r; dx <= r; dx++) {
      if (!running || !masterEnabled.getAsBoolean()) {
        return;
      }
      for (int dz = -r; dz <= r; dz++) {
        if (dx * dx + dz * dz > r2) {
          continue;
        }
        int cx = cxs + dx;
        int cz = czs + dz;
        // Only pre-existing chunks: never force world generation for the map.
        if (bw.isChunkGenerated(cx, cz)) {
          coords.add(new int[] {cx, cz});
        }
      }
    }
    coords.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));

    BukkitTask[] task = new BukkitTask[1];
    final AtomicInteger cursor = new AtomicInteger();
    Runnable step = new Runnable() {
      @Override
      public void run() {
        if (!running) {
          return;
        }
        int done = Math.min(coords.size(), cursor.get() + bootstrapPerTick);
        while (cursor.get() < done) {
          int[] c = coords.get(cursor.getAndIncrement());
          if (!running || !masterEnabled.getAsBoolean()) {
            return;
          }
          if (bw.isChunkGenerated(c[0], c[1])) {
            Chunk chunk = bw.getChunkAt(c[0], c[1]);
            if (chunk.isLoaded()) {
              onChunkLoaded(chunk);
            }
          }
        }
        if (cursor.get() < coords.size() && running) {
          task[0] = Bukkit.getScheduler().runTask(plugin, this);
        } else {
          log.info("map: bootstrap done for '" + world + "', processed "
              + (cursor.get() - 1) + " generated chunk(s)");
        }
      }
    };
    if (coords.isEmpty()) {
      log.info("map: no pre-generated chunks around spawn of '" + world + "' - map will grow as chunks load");
      return;
    }
    task[0] = Bukkit.getScheduler().runTask(plugin, step);
    bootstrapTasks.add(task[0]);
    log.info("map: bootstrap '" + world + "' scanning " + coords.size() + " generated chunk(s) around spawn");
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

  /** Stable 64-bit key for a (world, chunk x, chunk z) triple. */
  private static long terrainKey(String world, int cx, int cz) {
    return (long) (world.hashCode() & 0xFFFFF) << 42
        | (cx & 0x1FFFFF) << 21
        | (cz & 0x1FFFFF);
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