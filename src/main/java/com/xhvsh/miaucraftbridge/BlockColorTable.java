package com.xhvsh.miaucraftbridge;

import java.util.HashMap;
import java.util.Map;

/**
 * Vanilla block-id -> ARGB map colors, keyed on the base id (state properties
 * like {@code [snowy=true]} are handled by the renderer). Values are the
 * vanilla map-color palette so output resembles a squaremap-style render;
 * unknown blocks fall back to a stable hash color instead of black so modded
 * content still reads as something.
 */
final class BlockColorTable {

  static final int TRANSPARENT = 0x00000000;
  static final int OPAQUE = 0xFF000000;

  private static final Map<String, Integer> COLORS = new HashMap<>();
  private static final Map<String, Boolean> FIXED_HASH = new HashMap<>();

  static {
    put("air", TRANSPARENT);
    put("cave_air", TRANSPARENT);
    put("void_air", TRANSPARENT);
    put("short_grass", TRANSPARENT);
    put("tall_grass", TRANSPARENT);
    put("fern", TRANSPARENT);
    put("large_fern", TRANSPARENT);
    put("dead_bush", TRANSPARENT);
    put("torch", TRANSPARENT);
    put("wall_torch", TRANSPARENT);
    put("soul_torch", TRANSPARENT);
    put("redstone_wire", TRANSPARENT);
    put("string", TRANSPARENT);
    put("tripwire", TRANSPARENT);
    put("flower_pot", TRANSPARENT);
    put("dandelion", TRANSPARENT);
    put("poppy", TRANSPARENT);
    put("blue_orchid", TRANSPARENT);
    put("allium", TRANSPARENT);
    put("azure_bluet", TRANSPARENT);
    put("red_tulip", TRANSPARENT);
    put("pink_tulip", TRANSPARENT);
    put("white_tulip", TRANSPARENT);
    put("orange_tulip", TRANSPARENT);
    put("oxeye_daisy", TRANSPARENT);
    put("cornflower", TRANSPARENT);
    put("lily_of_the_valley", TRANSPARENT);
    put("wither_rose", TRANSPARENT);
    put("torchflower", TRANSPARENT);
    put("sunflower", TRANSPARENT);
    put("lilac", TRANSPARENT);
    put("rose_bush", TRANSPARENT);
    put("peony", TRANSPARENT);
    put("pink_petals", TRANSPARENT);

    put("stone", 0x999999);
    put("granite", 0x9C6E55);
    put("diorite", 0xC5C5C5);
    put("andesite", 0x8A8A8A);
    put("calcite", 0xD6D6D6);
    put("tuff", 0x6A6A6A);
    put("deepslate", 0x646464);
    put("cobblestone", 0x747474);
    put("mossy_cobblestone", 0x6B7858);
    put("stone_bricks", 0x828282);
    put("mossy_stone_bricks", 0x74805F);
    put("cracked_stone_bricks", 0x787878);
    put("chiseled_stone_bricks", 0x828282);
    put("mud_bricks", 0x5F4F3C);

    put("gravel", 0x8C8C8C);
    put("sand", 0xDBD3A0);
    put("red_sand", 0xA77B4F);
    put("sandstone", 0xD8CDA4);
    put("smooth_sandstone", 0xD8CDA4);
    put("red_sandstone", 0xA8845F);
    put("smooth_red_sandstone", 0xA8845F);
    put("clay", 0x9F9D9B);
    put("mud", 0x6B5A45);
    put("dirt", 0x966C4A);
    put("coarse_dirt", 0x8F6A4E);
    put("rooted_dirt", 0x7B5A3A);
    put("farmland", 0x8F6743);
    put("grass_block", 0x7CBD6B);
    put("mycelium", 0x786F76);
    put("podzol", 0x5D4835);
    put("moss_block", 0x4E7D28);
    put("moss_carpet", 0x4E7D28);
    put("sugar_cane", 0x6AA84F);
    put("bamboo", 0x6EA13C);

    put("snow_block", 0xFAF9F6);
    put("snow_layer", 0xFAF9F6);
    put("powder_snow", 0xFAF9F6);
    put("ice", 0xA7CFEC);
    put("packed_ice", 0x90D0F0);
    put("blue_ice", 0x7B9EE0);
    put("frosted_ice", 0xA7CFEC);

    put("oak_planks", 0x9D8C62);
    put("spruce_planks", 0x6B5A40);
    put("birch_planks", 0xD9D2B6);
    put("jungle_planks", 0x8A6A4F);
    put("acacia_planks", 0xB47A4A);
    put("dark_oak_planks", 0x4A3A25);
    put("mangrove_planks", 0x8A5B48);
    put("cherry_planks", 0xE3C6C6);
    put("bamboo_planks", 0xB6B24A);
    put("crimson_planks", 0x6A2C36);
    put("warped_planks", 0x3A6A5C);
    put("oak_log", 0x6B5C40);
    put("spruce_log", 0x2E251B);
    put("birch_log", 0xD9D2B6);
    put("jungle_log", 0x5B3B22);
    put("acacia_log", 0x9B5B3B);
    put("dark_oak_log", 0x30201A);
    put("mangrove_log", 0x6E4938);
    put("cherry_log", 0x5D343D);
    put("crimson_stem", 0x6A2C36);
    put("warped_stem", 0x3A6A5C);

    put("cobweb", 0xDBD7D7);
    put("bedrock", 0x4C4C4C);
    put("obsidian", 0x14141C);
    put("bricks", 0x9D5952);
    put("sponge", 0xBDC146);
    put("wet_sponge", 0x71814E);
    put("coal_ore", 0x777777);
    put("deepslate_coal_ore", 0x777777);
    put("iron_ore", 0x99998C);
    put("copper_ore", 0x9B6B5C);
    put("gold_ore", 0xBFB259);
    put("redstone_ore", 0x96635C);
    put("emerald_ore", 0x5A9663);
    put("lapis_ore", 0x4A6FA8);
    put("diamond_ore", 0x6BB5C9);
    put("raw_iron_block", 0x9E8F79);
    put("raw_copper_block", 0xA4715A);
    put("raw_gold_block", 0xD1B250);
    put("iron_block", 0xD8D8D8);
    put("gold_block", 0xF3D63D);
    put("copper_block", 0xB87362);
    put("exposed_copper", 0xA38386);
    put("weathered_copper", 0x7BAE82);
    put("oxidized_copper", 0x6AAE8A);
    put("cut_copper", 0xB87362);
    put("diamond_block", 0x5EC7C7);
    put("emerald_block", 0x44CC6F);
    put("lapis_block", 0x2E4DA0);
    put("netherite_block", 0x4B3B42);
    put("quartz_block", 0xECE9E4);

    put("water", 0x3F76E4);
    put("bubble_column", 0x3F76E4);
    put("lava", 0xFF7F2A);
    put("magma_block", 0xAF4225);
    put("glass", 0xE9F0EF);
    put("glass_pane", 0xE9F0EF);
    put("tinted_glass", 0x3C3C3C);
    put("sea_lantern", 0xC7E7E7);
    put("end_stone", 0xDFDFB8);
    put("end_stone_bricks", 0xC8C8A5);
    put("purpur_block", 0x8A6EC2);
    put("purpur_pillar", 0x8A6EC2);
    put("purpur_slab", 0x8A6EC2);

    put("netherrack", 0x803838);
    put("crimson_nylium", 0x9B3330);
    put("warped_nylium", 0x3D5F5A);
    put("nether_bricks", 0x2F2020);
    put("red_nether_bricks", 0x4B1818);
    put("soul_sand", 0x554555);
    put("soul_soil", 0x3C2F3C);
    put("basalt", 0x515151);
    put("blackstone", 0x2A2A2E);
    put("polished_blackstone", 0x34343A);
    put("gilded_blackstone", 0x503C22);
    put("prismarine", 0x5A9684);
    put("prismarine_bricks", 0x5A8A93);
    put("dark_prismarine", 0x3A5B62);

    put("mushroom_block", 0x977A5C);
    put("brown_mushroom_block", 0x977A5C);
    put("red_mushroom_block", 0x9E4A43);
    put("brown_mushroom", 0x977A5C);
    put("red_mushroom", 0x9E4A43);
    put("nether_wart", 0xB03A31);
    put("warped_wart", 0x3D5F5A);
    put("warped_fungus", 0x3D5F5A);
    put("crimson_fungus", 0x9B3330);
    put("wheat", 0xD9B94A);
    put("carrots", 0xE08C2E);
    put("potatoes", 0xA8A83A);
    put("beetroots", 0x8E4A3A);
    put("pumpkin", 0xD98A2B);
    put("carved_pumpkin", 0xD98A2B);
    put("jack_o_lantern", 0xD98A2B);
    put("melon", 0x6AA84F);
    put("mangrove_propagule", 0x6E4938);
    put("cocoa", 0x6B4423);
    put("vine", 0x4C7A2C);

    put("white_wool", 0xE9ECEC);
    put("orange_wool", 0xEA7E35);
    put("magenta_wool", 0xC455A2);
    put("light_blue_wool", 0x6699D8);
    put("yellow_wool", 0xFCD90F);
    put("lime_wool", 0x6BE231);
    put("pink_wool", 0xF6A7D0);
    put("gray_wool", 0x4E5555);
    put("light_gray_wool", 0xA0A7A7);
    put("cyan_wool", 0x3E8991);
    put("purple_wool", 0x7D3CC4);
    put("blue_wool", 0x253193);
    put("brown_wool", 0x6B4423);
    put("green_wool", 0x4C7A2C);
    put("red_wool", 0x9E4B48);
    put("black_wool", 0x1D1D21);

    for (Map.Entry<String, Integer> e : new java.util.ArrayList<>(COLORS.entrySet())) {
      String key = e.getKey();
      if (key.endsWith("_wool")) {
        String concrete = key.replace("_wool", "_concrete");
        String terracotta = key.replace("_wool", "_terracotta");
        int c = e.getValue();
        if (!COLORS.containsKey(concrete)) COLORS.put(concrete, c);
        if (!COLORS.containsKey(terracotta)) COLORS.put(terracotta, lighten(c));
      }
    }

    // Stripped logs/wood reuse the base bark color; deepslate ores reuse their
    // stone-ore color. These used to be "*" wildcard keys that could never
    // match a real block id, so list the actual ids instead.
    for (String prefix : new String[] {
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry", "bamboo"}) {
      Integer bark = COLORS.get(prefix + "_log");
      if (bark == null) bark = 0x8A7350;
      if (!COLORS.containsKey("stripped_" + prefix + "_log")) COLORS.put("stripped_" + prefix + "_log", bark);
      if (!COLORS.containsKey("stripped_" + prefix + "_wood")) COLORS.put("stripped_" + prefix + "_wood", bark);
    }
    for (String prefix : new String[] {
        "coal", "iron", "copper", "gold", "redstone", "emerald", "lapis", "diamond"}) {
      Integer ore = COLORS.get(prefix + "_ore");
      if (ore != null && !COLORS.containsKey("deepslate_" + prefix + "_ore")) {
        COLORS.put("deepslate_" + prefix + "_ore", ore);
      }
    }
  }

  private static void put(String id, int rgb) {
    // Transparent entries (air, plants, torches...) must keep their zero alpha -
    // the classic map-palette colors are opaque, so the alpha bit is what tells
    // the renderer "nothing to draw" for these blocks.
    COLORS.put(id, rgb == TRANSPARENT ? TRANSPARENT : (0xFF000000 | (rgb & 0xFFFFFF)));
  }

  private static int lighten(int argb) {
    int r = argb >> 16 & 0xFF, g = argb >> 8 & 0xFF, b = argb & 0xFF;
    r = Math.min(255, (int) (r + (255 - r) * 0.25));
    g = Math.min(255, (int) (g + (255 - g) * 0.25));
    b = Math.min(255, (int) (b + (255 - b) * 0.25));
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  private BlockColorTable() {
  }

  /** Stable non-black fallback for blocks the table does not cover. */
  static int fallback(String baseId) {
    int hash = baseId.hashCode();
    int r = 60 + (hash & 0x7F);
    int g = 60 + ((hash >> 8) & 0x7F);
    int b = 60 + ((hash >> 16) & 0x7F);
    return 0xFF000000 | (r << 16) | (g << 8) | b;
  }

  static boolean isTransparent(String baseId) {
    Integer c = COLORS.get(baseId);
    if (c == null) {
      return false;
    }
    return (c & 0xFF000000) == 0;
  }

  static int colorOf(String baseId) {
    Integer c = COLORS.get(baseId);
    return c != null ? c : fallback(baseId);
  }

  /** Blocks whose color should be overlaid with the biome grass/foliage tint. */
  static boolean isTintable(String baseId) {
    return baseId.equals("grass_block")
        || baseId.equals("short_grass")
        || baseId.equals("tall_grass")
        || baseId.equals("fern")
        || baseId.equals("large_fern")
        || baseId.equals("vine")
        || baseId.endsWith("_leaves")
        || baseId.endsWith("_sapling");
  }

  /** Blocks that count as "air" while scanning down to a visible surface. */
  static boolean skipThrough(String baseId) {
    return isTransparent(baseId)
        || baseId.equals("snow_layer")
        || baseId.equals("powder_snow")
        || baseId.equals("moss_carpet")
        || baseId.equals("carpet")
        || baseId.endsWith("_carpet")
        || baseId.equals("water")
        || baseId.equals("bubble_column");
  }

  /** True when the block is a hard stop for a down-scan (not air/water/plants). */
  static boolean isSolidSurface(String baseId) {
    return !skipThrough(baseId) && !isTransparent(baseId);
  }
}