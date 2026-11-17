package dev.oreesp;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Scans loaded chunks for chosen blocks and draws tracers, boxes and labels on the HUD layer. */
public final class Esp {
	private Esp() {}

	record Hit(int x, int y, int z, Block b) {}
	record HD(Hit h, double d) {}
	record Target(double x, double y, double z, Block b, int color, String name, int count) {}
	static final class Cached { List<Hit> hits; int tick; }

	static final Map<Long, Cached> cache = new HashMap<>();
	static final Set<Block> wanted = new HashSet<>();
	static final Map<Block, Config.BlockCfg> byBlock = new HashMap<>();
	static final Map<Block, String> names = new HashMap<>();
	static final Set<Long> alerted = new HashSet<>();
	static ArrayDeque<int[]> queue = new ArrayDeque<>();
	static List<Target> targets = List.of();
	static ClientLevel lastLevel;
	static int lastVer = -1, tickN;
	static String toast = "", banner = "";
	static long toastUntil, bannerUntil;

	static Config cfg() { return OreEspClient.cfg; }
	static long key(int x, int z) { return ((long) x << 32) | (z & 0xFFFFFFFFL); }

	public static void toast(String s) { toast = s; toastUntil = Util.getMillis() + 1500; }

	static String nameOf(Block b) { return names.computeIfAbsent(b, k -> k.getName().getString()); }

	static String nameOfKey(String key) {
		Identifier id = Identifier.tryParse(key);
		if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) return BuiltInRegistries.BLOCK.getValue(id).getName().getString();
		return key;
	}

	static void clear() {
		cache.clear(); alerted.clear(); queue.clear(); targets = List.of(); lastVer = -1;
	}

	static void rebuildWanted() {
		wanted.clear(); byBlock.clear();
		for (var e : cfg().blocks.entrySet()) {
			Config.BlockCfg bc = e.getValue();
			if (!bc.show && !bc.alert) continue;
			Identifier id = Identifier.tryParse(e.getKey());
			if (id != null && BuiltInRegistries.BLOCK.containsKey(id)) {
				Block b = BuiltInRegistries.BLOCK.getValue(id);
				wanted.add(b); byBlock.put(b, bc);
			}
		}
	}

	// ---------------------------------------------------------------- scanning (client tick)

	static void tick(Minecraft mc) {
		Config cfg = cfg();
		if (mc.level == null || mc.player == null) { clear(); lastLevel = null; return; }
		if (mc.level != lastLevel) { clear(); lastLevel = mc.level; }
		if (!cfg.enabled) return;
		if (lastVer != cfg.version) { lastVer = cfg.version; rebuildWanted(); cache.clear(); queue.clear(); }
		tickN++;
		int pcx = Mth.floor(mc.player.getX()) >> 4, pcz = Mth.floor(mc.player.getZ()) >> 4;
		if (tickN % 40 == 1) refreshQueue(mc, pcx, pcz);

		long end = System.nanoTime() + 3_000_000L; // 3 ms budget per tick
		while (!queue.isEmpty() && System.nanoTime() < end) {
			int[] q = queue.poll();
			if (mc.level.getChunk(q[0], q[1], ChunkStatus.FULL, false) instanceof LevelChunk lc) {
				Cached c = new Cached();
				c.hits = scan(lc); c.tick = tickN;
				cache.put(key(q[0], q[1]), c);
			}
		}
		if (tickN % 10 == 0) rebuild(mc);
	}

	static void refreshQueue(Minecraft mc, int pcx, int pcz) {
		int R = cfg().chunkRadius;
		cache.keySet().removeIf(k -> Math.abs((int) (k >> 32) - pcx) > R || Math.abs((int) (long) k - pcz) > R);
		List<int[]> need = new ArrayList<>();
		for (int dx = -R; dx <= R; dx++) for (int dz = -R; dz <= R; dz++) {
			int cx = pcx + dx, cz = pcz + dz; long k = key(cx, cz);
			if (!(mc.level.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof LevelChunk)) { cache.remove(k); continue; }
			Cached c = cache.get(k);
			int maxAge = (Math.abs(dx) <= 3 && Math.abs(dz) <= 3) ? 60 : 1200; // near chunks refresh faster (mined ores vanish)
			if (c == null || tickN - c.tick > maxAge) need.add(new int[]{cx, cz, dx * dx + dz * dz});
		}
		need.sort(Comparator.comparingInt(a -> a[2]));
		queue = new ArrayDeque<>(need);
	}

	static List<Hit> scan(LevelChunk lc) {
		List<Hit> out = new ArrayList<>();
		if (wanted.isEmpty()) return out;
		LevelChunkSection[] secs = lc.getSections();
		int minSec = lc.getMinSectionY();
		int bx = lc.getPos().getMinBlockX(), bz = lc.getPos().getMinBlockZ();
		for (int i = 0; i < secs.length; i++) {
			LevelChunkSection s = secs[i];
			if (s == null || s.hasOnlyAir() || !s.maybeHas(st -> wanted.contains(st.getBlock()))) continue; // palette check skips most sections
			int by = (minSec + i) << 4;
			for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
				Block b = s.getBlockState(x, y, z).getBlock();
				if (wanted.contains(b)) out.add(new Hit(bx + x, by + y, bz + z, b));
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- targets, veins, alerts

	static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }

	static void rebuild(Minecraft mc) {
		Config cfg = cfg();
		Vec3 e = mc.player.getEyePosition();
		double max = cfg.chunkRadius * 16.0 + 8, m2 = max * max;
		List<HD> all = new ArrayList<>();
		Map<Block, double[]> fresh = new LinkedHashMap<>();

		for (Cached c : cache.values()) for (Hit h : c.hits) {
			Config.BlockCfg bc = byBlock.get(h.b());
			if (bc == null) continue;
			double dx = h.x() + .5 - e.x, dy = h.y() + .5 - e.y, dz = h.z() + .5 - e.z, d2 = dx * dx + dy * dy + dz * dz;
			if (d2 > m2) continue;
			if (bc.show) all.add(new HD(h, d2));
			if (bc.alert && cfg.alerts && alerted.add(BlockPos.asLong(h.x(), h.y(), h.z()))) {
				double[] a = fresh.computeIfAbsent(h.b(), k -> new double[]{0, 1e9});
				a[0]++; a[1] = Math.min(a[1], Math.sqrt(d2));
			}
		}
		if (alerted.size() > 200_000) alerted.clear();

		all.sort(Comparator.comparingDouble(HD::d));
		if (all.size() > 25_000) all = new ArrayList<>(all.subList(0, 25_000));
		List<Target> out = new ArrayList<>();

		if (cfg.merge) { // union-find over 26-neighbourhood, same block type = one vein
			int n = all.size();
			int[] par = new int[n];
			Map<Long, Integer> idx = new HashMap<>(n * 2);
			for (int i = 0; i < n; i++) { par[i] = i; Hit h = all.get(i).h(); idx.put(BlockPos.asLong(h.x(), h.y(), h.z()), i); }
			for (int i = 0; i < n; i++) {
				Hit h = all.get(i).h();
				for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					Integer j = idx.get(BlockPos.asLong(h.x() + dx, h.y() + dy, h.z() + dz));
					if (j != null && all.get(j).h().b() == h.b()) par[find(par, i)] = find(par, j);
				}
			}
			Map<Integer, double[]> agg = new LinkedHashMap<>(); // insertion order = nearest vein first
			for (int i = 0; i < n; i++) {
				Hit h = all.get(i).h();
				double[] a = agg.computeIfAbsent(find(par, i), k -> new double[]{0, 0, 0, 0, i});
				a[0] += h.x() + .5; a[1] += h.y() + .5; a[2] += h.z() + .5; a[3]++;
			}
			for (double[] a : agg.values()) {
				if (out.size() >= cfg.maxTargets) break;
				Block b = all.get((int) a[4]).h().b();
				out.add(new Target(a[0] / a[3], a[1] / a[3], a[2] / a[3], b, byBlock.get(b).color, nameOf(b), (int) a[3]));
			}
		} else {
			for (int i = 0; i < all.size() && out.size() < cfg.maxTargets; i++) {
				Hit h = all.get(i).h();
				out.add(new Target(h.x() + .5, h.y() + .5, h.z() + .5, h.b(), byBlock.get(h.b()).color, nameOf(h.b()), 1));
			}
		}
		targets = out;

		if (!fresh.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (var en : fresh.entrySet()) {
				if (sb.length() > 0) sb.append("  |  ");
				sb.append(nameOf(en.getKey())).append(" x").append((int) en.getValue()[0]).append(" (").append((int) en.getValue()[1]).append("m)");
			}
			banner = "Found: " + sb;
			bannerUntil = Util.getMillis() + 5000;
			if (cfg.alertSound) mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f));
		}
	}

	// ---------------------------------------------------------------- drawing

	static Vec3 E, F, R, U;
	static double TH, AS;
	static int SW, SH;

	/** Projects a world point to GUI coordinates. Off-screen / behind-camera points are clamped to the screen edge. Returns true if on screen. */
	static boolean proj(double x, double y, double z, float[] out) {
		double dx = x - E.x, dy = y - E.y, dz = z - E.z;
		double zc = dx * F.x + dy * F.y + dz * F.z, xc = dx * R.x + dy * R.y + dz * R.z, yc = dx * U.x + dy * U.y + dz * U.z;
		float cx = SW / 2f, cy = SH / 2f;
		boolean front = zc > 0.05;
		if (front) {
			out[0] = (float) (cx * (1 + xc / (zc * TH * AS)));
			out[1] = (float) (cy * (1 - yc / (zc * TH)));
		} else {
			double len = Math.hypot(xc, yc);
			if (len < 1e-6) { xc = 0; yc = -1; len = 1; }
			out[0] = (float) (cx + xc / len * SW * 4); out[1] = (float) (cy - yc / len * SH * 4);
		}
		float vx = out[0] - cx, vy = out[1] - cy, m = 12;
		double tx = vx == 0 ? 1e9 : (cx - m) / Math.abs(vx), ty = vy == 0 ? 1e9 : (cy - m) / Math.abs(vy);
		double t = Math.min(1, Math.min(tx, ty));
		out[0] = (float) (cx + vx * t); out[1] = (float) (cy + vy * t);
		return front && t >= 1;
	}

	static void line(GuiGraphicsExtractor g, float x1, float y1, float x2, float y2, int color, int w) {
		float dx = x2 - x1, dy = y2 - y1;
		int len = (int) Math.hypot(dx, dy);
		if (len < 1) return;
		var p = g.pose();
		p.pushMatrix();
		p.translate(x1, y1);
		p.rotate((float) Math.atan2(dy, dx));
		g.fill(0, 0, len, w, color);
		p.popMatrix();
	}

	static void label(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String s, float x, float y, int color, float scale) {
		var p = g.pose();
		p.pushMatrix();
		p.translate(x, y);
		p.scale(scale, scale);
		g.text(font, s, -font.width(s) / 2, 0, color, true);
		p.popMatrix();
	}

	public static void hud(GuiGraphicsExtractor g, DeltaTracker dt) {
		Minecraft mc = Minecraft.getInstance();
		Config cfg = cfg();
		if (mc.player == null || mc.level == null) return;
		var font = mc.font;
		long now = Util.getMillis();
		SW = mc.getWindow().getGuiScaledWidth(); SH = mc.getWindow().getGuiScaledHeight();

		if (now < toastUntil) g.text(font, toast, SW / 2 - font.width(toast) / 2, SH - 65, 0xFFFFFFFF, true);
		if (now < bannerUntil) label(g, font, banner, SW / 2f, 24, 0xFFFFD040, 1.6f);
		if (!cfg.enabled) return;

		float pt = dt.getGameTimeDeltaPartialTick(false);
		E = mc.player.getEyePosition(pt);
		F = mc.player.getViewVector(pt);
		R = F.cross(new Vec3(0, 1, 0));
		R = R.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : R.normalize();
		U = R.cross(F);
		TH = Math.tan(Math.toRadians(mc.options.fov().get()) / 2.0);
		AS = (double) SW / SH;

		float cx = SW / 2f, cy = SH / 2f, base = cfg.textPercent / 100f;
		float[] o = new float[2];
		int lw = Math.max(1, cfg.lineWidth);

		for (Target t : targets) {
			boolean on = proj(t.x(), t.y(), t.z(), o);
			double dist = E.distanceTo(new Vec3(t.x(), t.y(), t.z()));
			int col = 0xFF000000 | t.color();
			if (cfg.tracers) line(g, cx, cy, o[0], o[1], (0xCC << 24) | t.color(), lw);
			if (!on) continue;
			int s = Mth.clamp((int) (120 / Math.max(dist, 2)), 4, 14);
			if (cfg.boxes) g.outline((int) o[0] - s / 2, (int) o[1] - s / 2, s, s, col);
			if (cfg.names || cfg.distance) {
				StringBuilder sb = new StringBuilder();
				if (cfg.names) sb.append(t.name()).append(t.count() > 1 ? " x" + t.count() : "");
				if (cfg.distance) sb.append(sb.length() > 0 ? " " : "").append((int) dist).append("m");
				float sc = base * (cfg.dynamicText ? Mth.clamp((float) (40 / (dist + 10)), 0.5f, 1.5f) : 1f);
				label(g, font, sb.toString(), o[0], o[1] - s / 2f - 10 * sc, col, sc);
			}
		}

		if (cfg.mobs) {
			int drawn = 0;
			for (Entity en : mc.level.entitiesForRendering()) {
				if (drawn >= 200) break;
				if (!(en instanceof Mob m) || !m.isAlive()) continue;
				double d = m.distanceTo(mc.player);
				if (d > cfg.mobRange) continue;
				boolean hostile = m instanceof Enemy;
				if (hostile ? !cfg.hostile : !cfg.passive) continue;
				int rgb = hostile ? cfg.hostileColor : cfg.passiveColor, col = 0xFF000000 | rgb;
				Vec3 p = m.getPosition(pt).add(0, m.getBbHeight() / 2, 0);
				boolean on = proj(p.x, p.y, p.z, o);
				drawn++;
				if (cfg.tracers) line(g, cx, cy, o[0], o[1], (0xCC << 24) | rgb, lw);
				if (!on) continue;
				int s = Mth.clamp((int) (160 / Math.max(d, 2)), 5, 18);
				if (cfg.boxes) g.outline((int) o[0] - s / 2, (int) o[1] - s / 2, s, s, col);
				if (cfg.names || cfg.distance) {
					String txt = (cfg.names ? m.getName().getString() : "") + (cfg.distance ? (cfg.names ? " " : "") + (int) d + "m" : "");
					float sc = base * (cfg.dynamicText ? Mth.clamp((float) (40 / (d + 10)), 0.5f, 1.5f) : 1f);
					label(g, font, txt, o[0], o[1] - s / 2f - 10 * sc, col, sc);
				}
			}
		}
	}
}
