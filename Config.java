package dev.oreesp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class Config {
	public static class BlockCfg {
		public boolean show = true, alert = false;
		public int color = 0xFFFFFF; // RGB
		public BlockCfg() {}
		public BlockCfg(boolean show, boolean alert, int color) { this.show = show; this.alert = alert; this.color = color; }
	}

	public boolean enabled = false;
	public boolean tracers = true, boxes = true, names = true, distance = true;
	public boolean merge = true, dynamicText = true, alerts = true, alertSound = true;
	public boolean mobs = true, hostile = true, passive = true;
	public int chunkRadius = 32, textPercent = 100, lineWidth = 1, maxTargets = 300, mobRange = 64;
	public int hostileColor = 0xFF4040, passiveColor = 0x40FF40;
	public Map<String, BlockCfg> blocks = new LinkedHashMap<>();

	/** Bumped when something that affects scanning changes (block list, radius). */
	public transient int version = 0;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static Path file() { return FabricLoader.getInstance().getConfigDir().resolve("oreesp.json"); }

	public static Config load() {
		try {
			if (Files.exists(file())) {
				Config c = GSON.fromJson(Files.readString(file()), Config.class);
				if (c != null) {
					if (c.blocks == null || c.blocks.isEmpty()) c.defaults();
					return c;
				}
			}
		} catch (Exception ignored) {}
		Config c = new Config();
		c.defaults();
		return c;
	}

	public void save() { try { Files.writeString(file(), GSON.toJson(this)); } catch (Exception ignored) {} }
	public void touch() { version++; }

	private void put(String id, int color, boolean show, boolean alert) { blocks.put("minecraft:" + id, new BlockCfg(show, alert, color)); }

	private void defaults() {
		String[] kinds = {"coal", "iron", "copper", "gold", "redstone", "lapis", "diamond", "emerald"};
		int[] cols = {0x8C8C8C, 0xD8AF93, 0xE07A3E, 0xFFD700, 0xFF2020, 0x2850FF, 0x40FFFF, 0x00FF50};
		for (int i = 0; i < kinds.length; i++) {
			boolean rare = kinds[i].equals("diamond") || kinds[i].equals("emerald");
			put(kinds[i] + "_ore", cols[i], !kinds[i].equals("coal"), kinds[i].equals("diamond"));
			put("deepslate_" + kinds[i] + "_ore", cols[i], !kinds[i].equals("coal"), kinds[i].equals("diamond"));
		}
		put("nether_gold_ore", 0xFFD700, true, false);
		put("nether_quartz_ore", 0xF0F0E0, true, false);
		put("ancient_debris", 0xC06A30, true, true);
		put("spawner", 0xB040FF, false, false);
	}
}
