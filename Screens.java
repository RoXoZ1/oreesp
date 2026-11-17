package dev.oreesp;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

public final class Screens {
	private Screens() {}

	static Component onoff(String l, boolean v) { return Component.literal(l + ": " + (v ? "ON" : "OFF")); }

	static Button tog(int x, int y, int w, String l, BooleanSupplier get, Consumer<Boolean> set) {
		return Button.builder(onoff(l, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			b.setMessage(onoff(l, get.getAsBoolean()));
		}).bounds(x, y, w, 20).build();
	}

	public static class Slider extends AbstractSliderButton {
		private final String label;
		private final int min, max;
		private final IntConsumer set;

		public Slider(int x, int y, int w, String label, int min, int max, int val, IntConsumer set) {
			super(x, y, w, 20, Component.empty(), (val - min) / (double) (max - min));
			this.label = label; this.min = min; this.max = max; this.set = set;
			updateMessage();
		}
		int get() { return min + (int) Math.round(value * (max - min)); }
		@Override protected void updateMessage() { setMessage(Component.literal(label + ": " + get())); }
		@Override protected void applyValue() { set.accept(get()); }
	}

	// ------------------------------------------------------------------ main settings (Right Alt)
	public static class Settings extends Screen {
		private final Screen parent;
		public Settings(Screen parent) { super(Component.literal("Ore ESP Settings")); this.parent = parent; }

		@Override
		protected void init() {
			Config c = OreEspClient.cfg;
			int w = 108, g = 4, x0 = width / 2 - (3 * w + 2 * g) / 2, x1 = x0 + w + g, x2 = x1 + w + g, y = 28, r = 21;

			addRenderableWidget(tog(x0, y, w, "ESP", () -> c.enabled, v -> c.enabled = v));
			addRenderableWidget(tog(x0, y + r, w, "Tracers", () -> c.tracers, v -> c.tracers = v));
			addRenderableWidget(tog(x0, y + r * 2, w, "Boxes", () -> c.boxes, v -> c.boxes = v));
			addRenderableWidget(tog(x0, y + r * 3, w, "Names", () -> c.names, v -> c.names = v));
			addRenderableWidget(tog(x0, y + r * 4, w, "Distance", () -> c.distance, v -> c.distance = v));
			addRenderableWidget(tog(x0, y + r * 5, w, "Merge veins", () -> c.merge, v -> c.merge = v));
			addRenderableWidget(tog(x0, y + r * 6, w, "Dynamic text", () -> c.dynamicText, v -> c.dynamicText = v));
			addRenderableWidget(tog(x0, y + r * 7, w, "Alerts", () -> c.alerts, v -> c.alerts = v));
			addRenderableWidget(tog(x0, y + r * 8, w, "Alert sound", () -> c.alertSound, v -> c.alertSound = v));

			addRenderableWidget(new Slider(x1, y, w, "Chunks", 1, 32, c.chunkRadius, v -> { c.chunkRadius = v; c.touch(); }));
			addRenderableWidget(new Slider(x1, y + r, w, "Text %", 50, 300, c.textPercent, v -> c.textPercent = v));
			addRenderableWidget(new Slider(x1, y + r * 2, w, "Line width", 1, 4, c.lineWidth, v -> c.lineWidth = v));
			addRenderableWidget(new Slider(x1, y + r * 3, w, "Max targets", 50, 1000, c.maxTargets, v -> c.maxTargets = v));
			addRenderableWidget(tog(x1, y + r * 4, w, "Mobs", () -> c.mobs, v -> c.mobs = v));
			addRenderableWidget(tog(x1, y + r * 5, w, "Hostile", () -> c.hostile, v -> c.hostile = v));
			addRenderableWidget(tog(x1, y + r * 6, w, "Passive", () -> c.passive, v -> c.passive = v));
			addRenderableWidget(new Slider(x1, y + r * 7, w, "Mob range", 16, 128, c.mobRange, v -> c.mobRange = v));

			addRenderableWidget(Button.builder(Component.literal("Hostile color").withColor(c.hostileColor & 0xFFFFFF),
				b -> minecraft.gui.setScreen(new ColorScreen(this, c.hostileColor, v -> c.hostileColor = v))).bounds(x2, y, w, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Passive color").withColor(c.passiveColor & 0xFFFFFF),
				b -> minecraft.gui.setScreen(new ColorScreen(this, c.passiveColor, v -> c.passiveColor = v))).bounds(x2, y + r, w, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Blocks..."),
				b -> minecraft.gui.setScreen(new Blocks(this))).bounds(x2, y + r * 3, w, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x2, y + r * 8, w, 20).build());
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
			super.extractRenderState(g, mx, my, d);
			g.text(font, title.getString(), width / 2 - font.width(title.getString()) / 2, 12, 0xFFFFFFFF, true);
		}

		@Override
		public void onClose() { OreEspClient.cfg.save(); minecraft.gui.setScreen(parent); }
	}

	// ------------------------------------------------------------------ block list
	public static class Blocks extends Screen {
		private static final int PER = 7;
		private final Screen parent;
		private int page = 0;
		private EditBox box;
		private final List<String> rowKeys = new ArrayList<>();

		public Blocks(Screen parent) { super(Component.literal("Blocks to show / alert")); this.parent = parent; }

		@Override
		protected void init() {
			Config c = OreEspClient.cfg;
			List<String> keys = new ArrayList<>(c.blocks.keySet());
			int pages = Math.max(1, (keys.size() + PER - 1) / PER);
			page = Math.min(page, pages - 1);
			rowKeys.clear();
			int x0 = width / 2 - 170, cx = width / 2;
			for (int i = 0; i < PER; i++) {
				int idx = page * PER + i;
				if (idx >= keys.size()) break;
				String k = keys.get(idx);
				Config.BlockCfg bc = c.blocks.get(k);
				rowKeys.add(k);
				int y = 36 + i * 24;
				addRenderableWidget(tog(x0 + 150, y, 52, "Show", () -> bc.show, v -> { bc.show = v; c.touch(); }));
				addRenderableWidget(tog(x0 + 206, y, 58, "Alert", () -> bc.alert, v -> { bc.alert = v; c.touch(); }));
				addRenderableWidget(Button.builder(Component.literal("\u25A0").withColor(bc.color & 0xFFFFFF),
					b -> minecraft.gui.setScreen(new ColorScreen(this, bc.color, v -> bc.color = v))).bounds(x0 + 268, y, 24, 20).build());
				addRenderableWidget(Button.builder(Component.literal("X"), b -> {
					c.blocks.remove(k); c.touch(); rebuildWidgets();
				}).bounds(x0 + 296, y, 24, 20).build());
			}
			int by = 36 + PER * 24 + 2;
			int p = page;
			addRenderableWidget(Button.builder(Component.literal("<"), b -> { page = Math.max(0, p - 1); rebuildWidgets(); }).bounds(x0, by, 20, 20).build());
			addRenderableWidget(Button.builder(Component.literal(">"), b -> { page = Math.min(pages - 1, p + 1); rebuildWidgets(); }).bounds(x0 + 22, by, 20, 20).build());
			box = new EditBox(font, x0 + 50, by, 150, 20, Component.literal("block id"));
			box.setHint(Component.literal("block id, e.g. diamond_ore"));
			box.setMaxLength(100);
			addRenderableWidget(box);
			addRenderableWidget(Button.builder(Component.literal("Add"), b -> addBlock()).bounds(x0 + 204, by, 40, 20).build());
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x0 + 270, by, 50, 20).build());
		}

		private void addBlock() {
			Config c = OreEspClient.cfg;
			String s = box.getValue().trim().toLowerCase();
			if (s.isEmpty()) return;
			if (!s.contains(":")) s = "minecraft:" + s;
			Identifier id = Identifier.tryParse(s);
			if (id == null || !BuiltInRegistries.BLOCK.containsKey(id) || c.blocks.containsKey(s)) return;
			c.blocks.put(s, new Config.BlockCfg(true, false, 0xFFFFFF));
			c.touch();
			page = Integer.MAX_VALUE; // jump to last page (clamped in init)
			rebuildWidgets();
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
			super.extractRenderState(g, mx, my, d);
			g.text(font, title.getString(), width / 2 - font.width(title.getString()) / 2, 12, 0xFFFFFFFF, true);
			int x0 = width / 2 - 170;
			for (int i = 0; i < rowKeys.size(); i++) {
				Config.BlockCfg bc = OreEspClient.cfg.blocks.get(rowKeys.get(i));
				int col = 0xFF000000 | (bc == null ? 0xFFFFFF : bc.color);
				g.text(font, Esp.nameOfKey(rowKeys.get(i)), x0, 36 + i * 24 + 6, col, true);
			}
		}

		@Override
		public void onClose() { OreEspClient.cfg.save(); minecraft.gui.setScreen(parent); }
	}

	// ------------------------------------------------------------------ RGB colour picker
	public static class ColorScreen extends Screen {
		private final Screen parent;
		private final IntConsumer apply;
		private int rgb;

		public ColorScreen(Screen parent, int rgb, IntConsumer apply) {
			super(Component.literal("Pick a colour"));
			this.parent = parent; this.rgb = rgb & 0xFFFFFF; this.apply = apply;
		}

		@Override
		protected void init() {
			int w = 160, x = width / 2 - w / 2, y = height / 2 - 40;
			addRenderableWidget(new Slider(x, y, w, "Red", 0, 255, (rgb >> 16) & 255, v -> { rgb = (rgb & 0x00FFFF) | (v << 16); apply.accept(rgb); }));
			addRenderableWidget(new Slider(x, y + 24, w, "Green", 0, 255, (rgb >> 8) & 255, v -> { rgb = (rgb & 0xFF00FF) | (v << 8); apply.accept(rgb); }));
			addRenderableWidget(new Slider(x, y + 48, w, "Blue", 0, 255, rgb & 255, v -> { rgb = (rgb & 0xFFFF00) | v; apply.accept(rgb); }));
			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(x, y + 76, w, 20).build());
		}

		@Override
		public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float d) {
			super.extractRenderState(g, mx, my, d);
			g.text(font, title.getString(), width / 2 - font.width(title.getString()) / 2, height / 2 - 70, 0xFFFFFFFF, true);
			g.fill(width / 2 - 80, height / 2 - 58, width / 2 + 80, height / 2 - 46, 0xFF000000 | rgb);
		}

		@Override
		public void onClose() { minecraft.gui.setScreen(parent); }
	}
}
