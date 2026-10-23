package cn.xm1221.ygomc.common.deck;

import cn.xm1221.ygomc.common.card.CardComponents;
import cn.xm1221.ygomc.common.card.DeckData;
import cn.xm1221.ygomc.common.collection.CollectionServer;
import cn.xm1221.ygomc.common.collection.CollectionText;
import cn.xm1221.ygomc.common.collection.DeckCards;
import cn.xm1221.ygomc.common.collection.PlayerCollections;
import cn.xm1221.ygomc.common.duel.DuelLobby;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 卡组盒。普通盒子的卡组写在物品上，跟着盒子走；末影卡盒只是一把钥匙，内容跟玩家走。
 *
 * <p>放进盒子的卡从物品栏或物品栏里的卡册扣掉，取出来退回物品栏。
 * 卡号清单（{@link DeckData}）和实体卡（{@link DeckCards}）一起写，决斗读卡号，取卡读实体卡。
 */
public class DeckBoxItem extends Item {

    private final boolean ender;

    public DeckBoxItem(Properties properties) {
        this(properties, false);
    }

    public DeckBoxItem(Properties properties, boolean ender) {
        super(properties);
        this.ender = ender;
    }

    public boolean ender() {
        return ender;
    }

    public static DeckData deck(ItemStack stack) {
        return cards(stack).toDeckData();
    }

    public static DeckCards cards(ItemStack stack) {
        DeckCards stored = stack.get(CardComponents.DECK_CARDS.get());
        if (stored != null) {
            return stored;
        }
        DeckData codes = stack.get(CardComponents.DECK_DATA.get());
        return codes == null ? DeckCards.EMPTY : DeckCards.fromCodes(codes);
    }

    /** 两份一起写，避免卡号清单和实体卡各改各的。 */
    public static void setCards(ItemStack stack, DeckCards cards) {
        stack.set(CardComponents.DECK_CARDS.get(), cards);
        stack.set(CardComponents.DECK_DATA.get(), cards.toDeckData());
    }

    public static void setDeck(ItemStack stack, DeckData data) {
        setCards(stack, DeckCards.fromCodes(data));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                DeckData deck = ender
                        ? PlayerCollections.get(serverPlayer).deck(serverPlayer.getUUID()).toDeckData()
                        : cards(stack).toDeckData();
                String label = ender ? "item.ygomc.ender_deck_box" : "item.ygomc.deck_box";
                Component problem = DuelLobby.selectDeck(serverPlayer, deck, label, true);
                if (problem != null) {
                    serverPlayer.displayClientMessage(problem, false);
                }
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            CollectionServer.openDeck(serverPlayer, hand);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        if (ender) {
            tooltip.add(CollectionText.c(CollectionText.FOLLOWS_PLAYER));
            return;
        }
        DeckData deck = deck(stack);
        tooltip.add(CollectionText.c(CollectionText.DECK_COUNT,
                deck.main().size(), deck.extra().size(), deck.side().size()));
    }
}
