package com.lambda.client.activity.activities.interaction

import com.lambda.client.activity.Activity
import com.lambda.client.activity.activities.highlevel.BuildStructure
import com.lambda.client.activity.activities.inventory.AcquireItemInActiveHand
import com.lambda.client.activity.activities.travel.PlaceGoal
import com.lambda.client.activity.activities.types.*
import com.lambda.client.event.LambdaEventBus
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.gui.hudgui.elements.client.ActivityManagerHud
import com.lambda.client.module.modules.client.BuildTools
import com.lambda.client.module.modules.client.BuildTools.autoPathing
import com.lambda.client.module.modules.client.BuildTools.directionForce
import com.lambda.client.module.modules.client.BuildTools.placeStrictness
import com.lambda.client.util.items.blockBlacklist
import com.lambda.client.util.math.CoordinateConverter.asString
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.math.Vec2f
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.PlaceInfo
import com.lambda.client.util.world.getNeighbour
import com.lambda.client.util.world.isReplaceable
import net.minecraft.block.*
import net.minecraft.block.state.IBlockState
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.util.EnumActionResult
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.IStringSerializable
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.fml.common.gameevent.TickEvent
import kotlin.properties.Delegates

class PlaceBlock(
    private val blockPos: BlockPos,
    private val targetState: IBlockState,
    private val ignoreProperties: Boolean = false,
    private val ignoreFacing: Boolean = false,
    override var rotation: Vec2f? = null,
    override var distance: Double = 1337.0,
    override val timeout: Long = 200L,
    override val maxAttempts: Int = 8,
    override var usedAttempts: Int = 0,
    override val toRender: MutableSet<RenderAABBActivity.Companion.RenderAABBCompound> = mutableSetOf()
) : RotatingActivity, TimeoutActivity, AttemptActivity, RenderAABBActivity, BuildActivity, TimedActivity, Activity() {
    private var placeInfo: PlaceInfo? = null
    private var spoofedDirection = false

    override var context: BuildActivity.BuildContext by Delegates.observable(BuildActivity.BuildContext.NONE) { _, old, new ->
        if (old == new) return@observable
        renderContext.color = new.color
    }

    override var action: BuildActivity.BuildAction by Delegates.observable(BuildActivity.BuildAction.UNINIT) { _, old, new ->
        if (old == new) return@observable
        renderAction.color = new.color
    }

    override var earliestFinish: Long
        get() = BuildTools.placeDelay.toLong()
        set(_) {}

    private val renderContext = RenderAABBActivity.Companion.RenderBlockPos(
        blockPos, context.color
    ).also { toRender.add(it) }

    private val renderAction = RenderAABBActivity.Companion.RenderBlockPos(
        blockPos, action.color
    ).also { toRender.add(it) }

    private enum class PlacementOffset(val offset: Vec3d) {
        UPPER(Vec3d(0.0, 0.1, 0.0)),
        CENTER(Vec3d.ZERO),
        LOWER(Vec3d(0.0, -0.1, 0.0))
    }

    private val blocksToOppositeDirection = listOf(
        BlockPistonBase::class,
        BlockEnderChest::class,
        BlockEndPortalFrame::class,
        BlockGlazedTerracotta::class,
        BlockPumpkin::class,
        BlockRedstoneDiode::class,
    )

    init {
        runSafe {
            if (!world.worldBorder.contains(blockPos) || world.isOutsideBuildHeight(blockPos)) {
                // ToDo: add support for placing blocks outside of world border
                failedWith(BlockOutsideOfBoundsException(blockPos))
            }

            updateState()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener

            updateState()
        }

        safeListener<PacketEvent.PostReceive> {
            if (it.packet !is SPacketBlockChange || it.packet.blockPosition != blockPos) return@safeListener

            if (it.packet.blockState == targetState
                || (ignoreProperties && it.packet.blockState.block == targetState.block)
            ) {
                ActivityManagerHud.totalBlocksPlaced++
                success()
            } else {
                failedWith(UnexpectedBlockStateException(blockPos, targetState, it.packet.blockState))
            }
        }
    }

    override fun SafeClientEvent.onInitialize() {
        updateState()

        when (action) {
            BuildActivity.BuildAction.PLACE -> {
                placeInfo?.let { placeInfo ->
                    checkPlace(placeInfo)
                }
            }
            BuildActivity.BuildAction.WRONG_POS_PLACE -> {
                if (autoPathing) addSubActivities(PlaceGoal(blockPos))
            }
            else -> {
                // ToDo: place neighbours
//                failedWith(NoNeighbourException(blockPos))
            }
        }
    }

    private fun SafeClientEvent.updateState() {
        val allowedSides = EnumFacing.VALUES.toMutableList()
        var placementOffset = PlacementOffset.CENTER

//        var allowedRotations = targetState.block.getValidRotations(world, blockPos)?.toMutableSet()

        val currentState = world.getBlockState(blockPos)

        if (!currentState.isReplaceable && currentState != targetState
            && subActivities.filterIsInstance<BreakBlock>().isEmpty()
        ) {
            val breakBlock = BreakBlock(blockPos)
            addSubActivities(breakBlock)
            LambdaEventBus.subscribe(breakBlock)
            return
        }

        targetState.properties.entries.firstOrNull { it.key.name == "facing" }?.let { entry ->
            val direction = entry.value as EnumFacing

            if (targetState.block is BlockButton) {
                allowedSides.clear()
                allowedSides.add(direction.opposite)
            }
        }

        targetState.properties.entries.firstOrNull { it.key.name == "half" }?.let { entry ->
            val half = entry.value as IStringSerializable
            placementOffset = when (half.name) {
                "top" -> {
                    allowedSides.remove(EnumFacing.DOWN)
                    PlacementOffset.UPPER
                }
                else -> {
                    allowedSides.remove(EnumFacing.UP)
                    PlacementOffset.LOWER
                }
            }
        }

        targetState.properties.entries.firstOrNull { it.key.name == "axis" }?.let { entry ->
            val axis = entry.value as IStringSerializable

            when (axis.name) {
                "x" -> allowedSides.removeIf { it.axis != EnumFacing.Axis.X }
                "y" -> allowedSides.removeIf { it.axis != EnumFacing.Axis.Y }
                "z" -> allowedSides.removeIf { it.axis != EnumFacing.Axis.Z }
                else -> {}
            }
        }

        /* quartz is a special snowflake */
        targetState.properties.entries.firstOrNull { it.key.name == "variant" }?.let { entry ->
            when (entry.value) {
                BlockQuartz.EnumType.LINES_X -> allowedSides.removeIf { it.axis != EnumFacing.Axis.X }
                BlockQuartz.EnumType.LINES_Y -> allowedSides.removeIf { it.axis != EnumFacing.Axis.Y }
                BlockQuartz.EnumType.LINES_Z -> allowedSides.removeIf { it.axis != EnumFacing.Axis.Z }
                else -> {}
            }
        }

//        /* check if block is replaceable */
//        allowedSides.removeIf {
//            !targetState.block.canPlaceBlockOnSide(world, blockPos, it.opposite)
//        }

        getNeighbour(
            blockPos,
            attempts = BuildTools.placementSearch,
            visibleSideCheck = placeStrictness != BuildTools.PlacementStrictness.ANY,
            range = BuildTools.maxReach,
            sides = allowedSides.toTypedArray()
        )?.let {
            action = BuildActivity.BuildAction.PLACE
            it.hitVec = it.hitVec.add(placementOffset.offset)
            distance = player.distanceTo(it.hitVec)
            placeInfo = it
        } ?: run {
            getNeighbour(
                blockPos,
                attempts = BuildTools.placementSearch,
                visibleSideCheck = false,
                range = 256f,
                sides = allowedSides.toTypedArray()
            )?.let {
                action = BuildActivity.BuildAction.WRONG_POS_PLACE
                distance = player.distanceTo(it.hitVec.add(placementOffset.offset))
            } ?: run {
                action = BuildActivity.BuildAction.INVALID_PLACE
                distance = 1337.0
            }
            placeInfo = null
            rotation = null
        }
    }

    private fun SafeClientEvent.checkPlace(placeInfo: PlaceInfo) {
        /* check if item has required metadata (declares the type) */
        val heldItemStack = player.getHeldItem(EnumHand.MAIN_HAND)
        val optimalStack = if (targetState.block is BlockShulkerBox) {
            ItemStack(targetState.block, 1, targetState.block.getMetaFromState(targetState))
        } else {
            @Suppress("DEPRECATION")
            targetState.block.getItem(world, blockPos, targetState)
        }

        if (heldItemStack.item != optimalStack.item
            || (!ignoreProperties && optimalStack.metadata != heldItemStack.metadata)
        ) {
            context = BuildActivity.BuildContext.RESTOCK

            addSubActivities(AcquireItemInActiveHand(
                optimalStack.item,
                metadata = optimalStack.metadata
            ))
            return
        }

        /* check if no entity collides */
        if (!world.checkNoEntityCollision(targetState.getSelectedBoundingBox(world, blockPos), player)) {
            // ToDo: this only handles the case where the player is inside the block
            addSubActivities(PlaceGoal(blockPos))
            return
        }

        targetState.properties.entries.firstOrNull { it.key.name == "facing" }?.let { entry ->
            if (ignoreProperties || ignoreFacing) return@let

            var direction = entry.value as EnumFacing

//            BlockDirectional
//            BlockHorizontal
//            BlockTorch
//            BlockLever

            if (targetState.block::class in blocksToOppositeDirection) direction = direction.opposite

            /* rotate block to right direction if possible */
            if (directionForce
                && !spoofedDirection
                && player.horizontalFacing != direction
            ) {
                addSubActivities(Rotate(Vec2f(direction.horizontalAngle, player.rotationPitch)))
                return
            }
        }

        /* last check for placement state */
//            val resultingState = targetState.block.getStateForPlacement(
//                world,
//                it.pos,
//                it.side,
//                hitVec.x.toFloat(), hitVec.y.toFloat(), hitVec.z.toFloat(),
//                heldItem.metadata,
//                player,
//                EnumHand.MAIN_HAND
//            )
//
//            if (resultingState != targetState
//                && !spoofedDirection
//                && targetState.block !is BlockButton // ToDo: find out why buttons don't work with this
//            ) {
//                failedWith(PlacementStateException(resultingState, targetState))
//                return
//            }

        doPlace(placeInfo)
    }

    private fun SafeClientEvent.doPlace(placeInfo: PlaceInfo) {
        val isBlacklisted = world.getBlockState(placeInfo.pos).block in blockBlacklist

        if (isBlacklisted) {
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
        }

        rotation = getRotationTo(placeInfo.hitVec)

        val result = playerController.processRightClickBlock(
            player,
            world,
            placeInfo.pos,
            placeInfo.side,
            placeInfo.hitVec,
            EnumHand.MAIN_HAND
        )

        if (result != EnumActionResult.SUCCESS) {
            failedWith(ProcessRightClickException(result))
            return
        }

        player.swingArm(EnumHand.MAIN_HAND)

        if (isBlacklisted) {
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
        }

        context = BuildActivity.BuildContext.PENDING
    }

    override fun SafeClientEvent.onChildSuccess(childActivity: Activity) {
        when (childActivity) {
            is Rotate -> {
                spoofedDirection = true
                status = Status.UNINITIALIZED
            }
            else -> {
                spoofedDirection = false
                status = Status.UNINITIALIZED
            }
        }
    }

    class NoNeighbourException(blockPos: BlockPos) : Exception("No neighbour for (${blockPos.asString()}) found")
    class BlockNotPlaceableException(targetState: IBlockState) : Exception("Block $targetState is not placeable")
    class ProcessRightClickException(result: EnumActionResult) : Exception("Processing right click failed with result $result")
    class UnexpectedBlockStateException(blockPos: BlockPos, expected: IBlockState, actual: IBlockState) : Exception("Unexpected block state at (${blockPos.asString()}) expected $expected but got $actual")
    class BlockOutsideOfBoundsException(blockPos: BlockPos) : Exception("Block at (${blockPos.asString()}) is outside of world")
}