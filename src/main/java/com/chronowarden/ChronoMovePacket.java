package com.chronowarden;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Клиент -> сервер. key: 0 Ball, 1 Anchor, 2 Halt, 3 TS. action: 0 нажали, 1 отпустили. */
public class ChronoMovePacket {
    private final int key;
    private final int action;

    public ChronoMovePacket(int key, int action) {
        this.key = key;
        this.action = action;
    }

    public static void encode(ChronoMovePacket msg, FriendlyByteBuf buf) {
        buf.writeByte(msg.key);
        buf.writeByte(msg.action);
    }

    public static ChronoMovePacket decode(FriendlyByteBuf buf) {
        return new ChronoMovePacket(buf.readByte(), buf.readByte());
    }

    public static void handle(ChronoMovePacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() -> {
            ServerPlayer sender = c.getSender();
            if (sender != null) ChronoManager.input(sender, msg.key, msg.action);
        });
        c.setPacketHandled(true);
    }
}
