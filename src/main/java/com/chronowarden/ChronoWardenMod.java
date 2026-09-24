package com.chronowarden;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import net.minecraftforge.server.ServerLifecycleHooks;

/** Отдельный, независимый мод: δ Хроно Варден. Не зависит от Δ и может ставиться без него. */
@Mod(ChronoWardenMod.MOD_ID)
public class ChronoWardenMod {
    public static final String MOD_ID = "chronowarden";

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, MOD_ID);
    public static final RegistryObject<Item> CHRONO_ACTIVATOR = ITEMS.register("chrono_activator",
            () -> new ChronoActivatorItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE)));

    private static final String PROTOCOL = "1";
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MOD_ID, "main"),
            () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public ChronoWardenMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(modBus);
        modBus.addListener(this::commonSetup);
        modBus.addListener(this::addCreative);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(0, ChronoMovePacket.class,
                ChronoMovePacket::encode, ChronoMovePacket::decode, ChronoMovePacket::handle);
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(CHRONO_ACTIVATOR);
        }
    }

    @Mod.EventBusSubscriber(modid = MOD_ID)
    public static class ForgeEvents {

        @SubscribeEvent
        public static void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) ChronoManager.tick(server);
        }

        @SubscribeEvent
        public static void onHurt(LivingHurtEvent event) {
            if (event.getEntity().level().isClientSide) return;
            ChronoManager.onHurt(event);
        }

        @SubscribeEvent
        public static void onDeath(LivingDeathEvent event) {
            if (event.getEntity().level().isClientSide) return;
            ChronoManager.onDeath(event);
        }

        @SubscribeEvent
        public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
            ChronoManager.forget(event.getEntity().getUUID());
        }
    }
}
