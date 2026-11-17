package dev.oreesp;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

public class OreEspClient implements ClientModInitializer {
	public static final Config cfg = Config.load();
	private static KeyMapping toggleKey, menuKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category cat = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("oreesp", "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oreesp.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_X, cat));
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.oreesp.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RALT, cat));

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (toggleKey.consumeClick()) {
				cfg.enabled = !cfg.enabled;
				cfg.save();
				Esp.toast("Ore ESP: " + (cfg.enabled ? "ON" : "OFF"));
			}
			while (menuKey.consumeClick()) {
				if (mc.gui.screen() == null) mc.gui.setScreen(new Screens.Settings(null));
			}
			Esp.tick(mc);
		});

		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("oreesp", "esp"), Esp::hud);
	}
}
