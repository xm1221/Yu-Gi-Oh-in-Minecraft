package cn.xm1221.ygomc.common.net;

import cn.xm1221.ygomc.common.card.CardRarity;
import cn.xm1221.ygomc.common.pack.model.PrintRef;
import dev.architectury.networking.NetworkManager;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 开包结果：服务端 → 客户端。抽卡在服务端之前已经定了本包，这里只负责展示。
 * 额外带 booster id 与包面 URL，客户端用来渲染真实包面（缺则按需下载）。
 */
@SuppressWarnings("removal")
public final class PackNet {

    public static final ResourceLocation OPENED =
            ResourceLocation.fromNamespaceAndPath("ygomc", "pack_opened");

    public record OpenedPack(List<PrintRef> cards, ResourceLocation boosterId, String faceUrl) {
        public OpenedPack {
            cards = List.copyOf(cards);
            faceUrl = faceUrl == null ? "" : faceUrl;
        }
    }

    private static Consumer<OpenedPack> handler;

    private PackNet() {
    }

    /** 只在客户端调用。 */
    public static void registerClient(Consumer<OpenedPack> opened) {
        handler = opened;
        NetworkManager.registerReceiver(NetworkManager.Side.S2C, OPENED, (buf, ctx) -> {
            OpenedPack pack = read(buf);
            Consumer<OpenedPack> got = handler;
            if (got != null) {
                ctx.queue(() -> got.accept(pack));
            }
        });
    }

    public static void send(ServerPlayer player, List<PrintRef> cards) {
        send(player, cards, null, "");
    }

    public static void send(ServerPlayer player, List<PrintRef> cards,
                            ResourceLocation boosterId, String faceUrl) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        buf.writeVarInt(cards.size());
        for (PrintRef card : cards) {
            buf.writeVarInt(card.code());
            buf.writeVarInt(card.rarity().id());
            buf.writeVarInt(card.variant());
        }
        buf.writeBoolean(boosterId != null);
        if (boosterId != null) {
            buf.writeResourceLocation(boosterId);
        }
        buf.writeUtf(faceUrl == null ? "" : faceUrl, 512);
        NetworkManager.sendToPlayer(player, OPENED, buf);
    }

    private static OpenedPack read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<PrintRef> cards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int code = buf.readVarInt();
            CardRarity rarity = CardRarity.byId(buf.readVarInt());
            int variant = buf.readVarInt();
            cards.add(new PrintRef(code, rarity, variant, 1));
        }
        ResourceLocation boosterId = buf.readBoolean() ? buf.readResourceLocation() : null;
        String faceUrl = buf.readUtf(512);
        return new OpenedPack(cards, boosterId, faceUrl);
    }
}
