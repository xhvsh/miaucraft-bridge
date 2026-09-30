package com.xhvsh.miaucraftbridge;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public final class BridgeCommand implements CommandExecutor, TabCompleter {

  private static final List<String> SUBCOMMANDS = List.of("reload", "status", "test", "stats", "update", "drain", "flush", "map");
  private static final List<String> UPDATE_ARGS = List.of("check", "apply", "status");
  private static final List<String> MAP_ARGS = List.of("status", "render", "rerender");

  private final MiaucraftBridgePlugin plugin;

  public BridgeCommand(MiaucraftBridgePlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
    if (!sender.hasPermission("miaucraftbridge.admin")) {
      sender.sendMessage("§cYou don't have permission to use this.");
      return true;
    }

    String sub = args.length == 0 ? "status" : args[0].toLowerCase();
    switch (sub) {
      case "reload" -> plugin.reloadRemote(sender);
      case "status" -> plugin.statusLines().forEach(sender::sendMessage);
      case "test" -> plugin.testConnection(sender);
      case "stats" -> plugin.forceStats(sender);
      case "drain" -> plugin.drainAchievements(sender);
      case "flush" -> plugin.flushSinks(sender);
      case "update" -> {
        String action = args.length >= 2 ? args[1].toLowerCase() : "check";
        switch (action) {
          case "apply" -> plugin.applyUpdate(sender);
          case "status" -> plugin.statusLines().forEach(sender::sendMessage);
          default -> plugin.checkUpdate(sender);
        }
      }
      case "map" -> mapCommand(sender, args);
      default -> {
        sender.sendMessage("§e/bridge reload §7- re-fetch the remote config");
        sender.sendMessage("§e/bridge status §7- show plugin/remote state");
        sender.sendMessage("§e/bridge test §7- check the Supabase connection");
        sender.sendMessage("§e/bridge stats §7- force a stat reconcile now");
        sender.sendMessage("§e/bridge update [check|apply] §7- check for / install a newer jar");
        sender.sendMessage("§e/bridge drain §7- force-resend queued achievement rows and show the raw result");
        sender.sendMessage("§e/bridge flush §7- push every queued row now instead of waiting for the next cycle");
        sender.sendMessage("§e/bridge map ... §7- live map status/render controls");
      }
    }
    return true;
  }

  @Override
  public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
    if (args.length == 1) {
      String prefix = args[0].toLowerCase();
      return SUBCOMMANDS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("update")) {
      String prefix = args[1].toLowerCase();
      return UPDATE_ARGS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
    if (args.length == 2 && args[0].equalsIgnoreCase("map")) {
      String prefix = args[1].toLowerCase();
      return MAP_ARGS.stream().filter(s -> s.startsWith(prefix)).toList();
    }
    return List.of();
  }

  private void mapCommand(CommandSender sender, String[] args) {
    LiveMap map = plugin.liveMap();
    if (map == null) {
      sender.sendMessage("§c[MiaucraftBridge] The live map is not enabled (set map.enabled: true in config.yml).");
      return;
    }
    String action = args.length >= 2 ? args[1].toLowerCase() : "status";
    switch (action) {
      case "status" -> {
        sender.sendMessage("§6[MiaucraftBridge] live map");
        sender.sendMessage("§7running: §f" + map.isRunning());
        sender.sendMessage("§7rendered regions: §f" + map.renderedCount());
        sender.sendMessage("§7pending: §f" + map.pendingCount());
        sender.sendMessage("§7upload failures: §f" + map.uploadFailures());
        sender.sendMessage("§7last activity: §f" + ago(map.lastActivityMs()));
      }
      case "render" -> {
        int radius = args.length >= 3 ? parsePositive(args[2], -1) : -1;
        if (radius <= 0) {
          sender.sendMessage("§e[MiaucraftBridge] /bridge map render [radius] - render radius ~ spawn (use update-sweep for new terrain).");
          return;
        }
        map.renderRadius(radius);
        sender.sendMessage("§a[MiaucraftBridge] Render enqueued for radius " + radius + " blocks.");
      }
      case "rerender" -> {
        if (args.length < 4) {
          sender.sendMessage("§e[MiaucraftBridge] /bridge map rerender <blockX> <blockZ> - force the region containing those blocks.");
          return;
        }
        int bx = parsePositive(args[2], Integer.MIN_VALUE);
        int bz = parsePositive(args[3], Integer.MIN_VALUE);
        if (bx == Integer.MIN_VALUE || bz == Integer.MIN_VALUE) {
          sender.sendMessage("§c[MiaucraftBridge] Block coordinates must be numbers.");
          return;
        }
        map.rerenderAround(bx, bz, "world");
        sender.sendMessage("§a[MiaucraftBridge] Region at " + bx + "," + bz + " re-rendered.");
      }
      default -> {
        sender.sendMessage("§e/bridge map status §7- show render/upload state");
        sender.sendMessage("§e/bridge map render [radius] §7- render radius ~ spawn");
        sender.sendMessage("§e/bridge map rerender <bx> <bz> §7- force one region");
      }
    }
  }

  private static int parsePositive(String s, int fallback) {
    try {
      return Integer.parseInt(s);
    } catch (NumberFormatException e) {
      return fallback;
    }
  }

  private static String ago(long millis) {
    if (millis <= 0) return "never";
    long seconds = (System.currentTimeMillis() - millis) / 1000L;
    return seconds + "s ago";
  }
}
