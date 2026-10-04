package cn.xm1221.ygomc.common.duel;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 便携决斗盘：向另一名玩家发起对局邀请（PLAN §5.2 的「便携入口」）。
 *
 * <h2>为什么交互挂在 {@code interactLivingEntity} 而不是 {@code use}</h2>
 * {@code use} 只在「空手/持物对空气或方块右键」时触发，拿不到「你对准了哪个玩家」。
 * 而决斗盘的语义就是<b>指定一个对手</b>，所以必须用
 * {@link Item#interactLivingEntity}——它带着被点击的实体一起来。
 * 这也是 YgoDuelingMod 验证过的做法（{@code DuelDiskItem} 用同一族回调）。
 *
 * <h2>为什么逻辑入口必须显式判 {@code isClientSide}</h2>
 * 这个回调在<b>两端都会跑</b>。如果客户端也去走一遍房间状态机，就会得到
 * 「客户端自己以为开了房、服务端完全不知道」的分裂状态。
 * 约定：客户端只负责发动作（这里就是原版靠 {@code InteractionResult} 自动回包的
 * 交互），房间的建立与校验一律在服务端；这里先早早返回，
 * 让服务端成为唯一的权威。
 */
public class DuelDiskItem extends Item {

    public DuelDiskItem(Properties properties) {
        super(properties);
    }

    /**
     * 手持决斗盘右键某个生物 → 邀请它（玩家）决斗。
     *
     * <p>本轮只留结构：真正的邀请要走 {@code DuelRoom} 管理器
     * （房间、邀请、观众席、准备/选卡组/先后手，状态机 IDLE→PREPARING→DUELING→SIDING→END），
     * 属于 M2。
     */
    // TODO(M2): 实现 DuelRoom 邀请流程：
    //   1) 服务端校验发起者与目标是否都已空闲（不在其它房间）；
    //   2) 目标必须是 Player 且不是自己、不是旁观者；
    //   3) 建房间 -> 发邀请包 -> 等对方接受 -> 进入选卡组阶段；
    //   4) 返回 InteractionResult.SUCCESS 让客户端播挥手动画。
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                 InteractionHand hand) {
        // M2 落实现时的第一步就是这一句（现在写了也没用，因为两侧都只返回 PASS）：
        //   if (player.level().isClientSide()) { return InteractionResult.PASS; }
        return InteractionResult.PASS;
    }

    /**
     * 对着空气右键：以后用于打开「决斗盘界面」（查看当前房间状态 / 取消邀请 / 准备）。
     *
     * <p>注意返回类型必须是 {@link InteractionResultHolder}，这是
     * {@code Item#use} 在 1.21 的签名（它要把「用完这格物品变成什么」一并带回去）；
     * 写成 {@link InteractionResult} 会编译不过。
     */
    // TODO(M2): 打开决斗盘 GUI（只读状态面板）；没有进行中的房间时给出提示。
    //
    // 目前这一步直接开局：对着空气右键 = 和本地贪心对手打一局（单人可测）。
    // 之后有了卡组选择界面，这里改成先开面板、由玩家选卡组再开局。
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide()) {
            // 客户端只给「挥一下手」的即时反馈。房间的建立与校验一律在服务端，
            // 客户端这边走一遍状态机只会造出「客户自以为开了房」的分裂状态。
            return InteractionResultHolder.success(stack);
        }
        if (!(player instanceof net.minecraft.server.level.ServerPlayer server)) {
            return InteractionResultHolder.pass(stack);
        }
        String problem = DuelRoom.startFor(server,
                cn.xm1221.ygomc.common.command.YgomcCommand.loadoutForDuel(),
                cn.xm1221.ygomc.common.command.YgomcCommand.opponentLoadoutForDuel());
        if (problem != null) {
            // 开局失败必须说出来。静默失败的表现是「右键没反应」，
            // 与「这个物品根本没实现」长得一样。
            server.displayClientMessage(
                    net.minecraft.network.chat.Component.literal(problem), false);
            return InteractionResultHolder.fail(stack);
        }
        return InteractionResultHolder.success(stack);
    }
}
