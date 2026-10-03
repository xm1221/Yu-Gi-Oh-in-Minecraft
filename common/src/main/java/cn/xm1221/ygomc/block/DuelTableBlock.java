package cn.xm1221.ygomc.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 决斗台（固定在场上的决斗场地）。
 *
 * <h2>为什么既有决斗盘又有决斗台</h2>
 * 两者是同一个 {@code DuelRoom} 的两个入口：决斗盘是「随身带着、见到谁就跟谁打」，
 * 决斗台是「摆在场景里的一处场地」——服务器大厅、剧情关卡、观众围观都用它。
 * 台子本身<b>不持有任何对局状态</b>（不存玩家、不存房间），
 * 它只是个「点击起点」：真正的房间由服务端管理器创建，
 * 这样台子可以被随意破坏/搬运而不影响正在进行的对局。
 *
 * <h2>1.21 的方块交互被拆成了两个回调</h2>
 * 1.21 以前只有一个 {@code use}；现在拆成
 * {@link #useWithoutItem}（空手右键）与 {@link #useItemOn}（手持物品右键），
 * 因为原版需要区分「物品要起作用」与「只是按一下方块」。这里两个都留了实现位置：
 * 手持卡组盒点台子、与空手点台子都可能是有效动作。
 */
public class DuelTableBlock extends Block {

    public DuelTableBlock(Properties properties) {
        super(properties);
    }

    /**
     * 1.21 起方块要能提供自己的 {@link MapCodec}（数据驱动方块的基础设施）。
     *
     * <p>这里直接沿用父类实现：本方块没有自定义方块状态，也没有需要序列化的字段。
     * 一旦以后要给决斗台加状态（朝向、是否被占用），就必须改成
     * {@code simpleCodec(DuelTableBlock::new)} 这类真实编解码，
     * 否则数据包无法描述这个方块。
     */
    // TODO(M2): 若加入方块状态（朝向 / 是否被占用），同步实现 codec() 与 createBlockStateDefinition。
    @Override
    protected MapCodec<? extends Block> codec() {
        return super.codec();
    }

    /**
     * 手持物品右键决斗台。
     *
     * <p>返回类型是 {@link ItemInteractionResult}（不是 {@link InteractionResult}）：
     * 它多携带一个「手上的物品是否发生变化」的信息，原版据此决定要不要给客户端
     * 回发物品更新。用错类型会导致客户端手里的物品显示与服务端不同步。
     */
    // TODO(M2): 手持卡组盒点台子 = 用这副卡组开局；手持决斗盘点台子 = 加入等待中的房间。
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                             Player player, InteractionHand hand, BlockHitResult hitResult) {
        return super.useItemOn(stack, state, level, pos, player, hand, hitResult);
    }

    /**
     * 空手右键决斗台。
     */
    // TODO(M2): 空手右键 = 查看该台子当前房间状态（谁在打、观众几人）；无房间时提示如何开局。
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hitResult) {
        return super.useWithoutItem(state, level, pos, player, hitResult);
    }
}
