package com.xhvsh.miaucraftbridge;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A CommandSender that records messages instead of showing them, so an action
 * requested from the website can report the same output an operator would have
 * seen in game.
 *
 * Everything other than messaging is delegated to the console sender, so a
 * captured action still runs with exactly the permissions and server context it
 * would have had in game - the website cannot escalate by routing around a
 * permission check.
 */
public final class CapturingSender implements CommandSender {

  private static final int MAX_LINES = 200;

  private final CommandSender delegate;
  private final List<String> lines = new ArrayList<>();
  private boolean sawError;

  public CapturingSender(CommandSender delegate) {
    this.delegate = delegate;
  }

  public List<String> lines() {
    return List.copyOf(lines);
  }

  /** Captured output as one newline-joined block, ready for the website. */
  public String text() {
    return String.join("\n", lines);
  }

  public boolean isEmpty() {
    return lines.isEmpty();
  }

  /**
   * True when any captured line used the red colour code. Every failure path in
   * this plugin reports errors that way, so it is the one signal available for
   * judging a captured run without changing each command's return type.
   */
  public boolean sawError() {
    return sawError;
  }

  private void capture(String message) {
    if (message == null) return;
    for (String part : message.split("\n", -1)) {
      if (lines.size() >= MAX_LINES) {
        lines.add("(output truncated at " + MAX_LINES + " lines)");
        return;
      }
      if (part.indexOf('\u00a7') >= 0 && part.indexOf("\u00a7c") >= 0) sawError = true;
      lines.add(stripColors(part));
    }
  }

  /** Drops Minecraft section-sign colour codes, which are meaningless on a web page. */
  public static String stripColors(String input) {
    if (input == null || input.indexOf('\u00a7') < 0) return input;
    StringBuilder out = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      if (c == '\u00a7' && i + 1 < input.length()) {
        i++;
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  // ----------------------------------------------------------------- messages

  @Override
  public void sendMessage(String message) {
    capture(message);
  }

  @Override
  public void sendMessage(String... messages) {
    if (messages != null) for (String m : messages) capture(m);
  }

  @Override
  public void sendMessage(UUID source, String message) {
    capture(message);
  }

  @Override
  public void sendMessage(UUID source, String... messages) {
    if (messages != null) for (String m : messages) capture(m);
  }

  @Override
  public void sendMessage(Component message) {
    if (message != null) capture(PlainTextComponentSerializer.plainText().serialize(message));
  }

  // ------------------------------------------------------------------ server

  @Override
  public Server getServer() {
    return delegate.getServer();
  }

  @Override
  public String getName() {
    return "MiaucraftBridge";
  }

  @Override
  public CommandSender.Spigot spigot() {
    return delegate.spigot();
  }

  @Override
  public Component name() {
    return Component.text(getName());
  }

  // ------------------------------------------------------------- permissions

  @Override
  public boolean isPermissionSet(String name) {
    return delegate.isPermissionSet(name);
  }

  @Override
  public boolean isPermissionSet(Permission perm) {
    return delegate.isPermissionSet(perm);
  }

  @Override
  public boolean hasPermission(String name) {
    return delegate.hasPermission(name);
  }

  @Override
  public boolean hasPermission(Permission perm) {
    return delegate.hasPermission(perm);
  }

  @Override
  public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value) {
    return delegate.addAttachment(plugin, name, value);
  }

  @Override
  public PermissionAttachment addAttachment(Plugin plugin) {
    return delegate.addAttachment(plugin);
  }

  @Override
  public PermissionAttachment addAttachment(Plugin plugin, String name, boolean value, int ticks) {
    return delegate.addAttachment(plugin, name, value, ticks);
  }

  @Override
  public PermissionAttachment addAttachment(Plugin plugin, int ticks) {
    return delegate.addAttachment(plugin, ticks);
  }

  @Override
  public void removeAttachment(PermissionAttachment attachment) {
    delegate.removeAttachment(attachment);
  }

  @Override
  public void recalculatePermissions() {
    delegate.recalculatePermissions();
  }

  @Override
  public Set<PermissionAttachmentInfo> getEffectivePermissions() {
    return delegate.getEffectivePermissions();
  }

  @Override
  public boolean isOp() {
    return delegate.isOp();
  }

  @Override
  public void setOp(boolean value) {
    delegate.setOp(value);
  }
}
