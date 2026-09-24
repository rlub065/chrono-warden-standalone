package com.chronowarden;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/** Только клиент: клавиши Хроно Вардена. 0 Ball(держать), 1 Anchor, 2 Halt, 3 TS. */
public class ChronoClient {
    private static final String CATEGORY = "key.categories.chrono";
    private static final String[] NAMES = {"ball", "anchor", "halt", "ts"};
    private static final int[] DEFAULT_KEYS = {
            GLFW.GLFW_KEY_K, // Ball: тап = ур.1, короткое удержание = ур.2, долгое = ур.3
            GLFW.GLFW_KEY_L, // Anchor
            GLFW.GLFW_KEY_P, // Halt
            GLFW.GLFW_KEY_O  // TS
    };
    static final KeyMapping[] KEYS = new KeyMapping[NAMES.length];
    private static final boolean[] WAS_DOWN = new boolean[NAMES.length];

    static {
        for (int i = 0; i < NAMES.length; i++) {
            KEYS[i] = new KeyMapping("key.chrono." + NAMES[i], InputConstants.Type.KEYSYM, DEFAULT_KEYS[i], CATEGORY);
        }
    }

    @Mod.EventBusSubscriber(modid = ChronoWardenMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModBus {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            for (KeyMapping key : KEYS) event.register(key);
        }
    }

    @Mod.EventBusSubscriber(modid = ChronoWardenMod.MOD_ID, value = Dist.CLIENT)
    public static class ForgeBus {
        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            if (Minecraft.getInstance().player == null) return;
            for (int i = 0; i < KEYS.length; i++) {
                boolean pressed = false;
                while (KEYS[i].consumeClick()) {
                    pressed = true;
                    ChronoWardenMod.CHANNEL.sendToServer(new ChronoMovePacket(i, 0));
                }
                boolean down = KEYS[i].isDown();
                if (i == 0 && !down && (WAS_DOWN[i] || pressed)) {
                    ChronoWardenMod.CHANNEL.sendToServer(new ChronoMovePacket(0, 1)); // отпустили Ball - решаем уровень
                }
                WAS_DOWN[i] = down;
            }
        }
    }
}
