package me.zeroeightsix.kami.module.modules.misc

import baritone.api.BaritoneAPI
import baritone.api.pathing.goals.GoalXZ
import me.zeroeightsix.kami.KamiMod
import me.zeroeightsix.kami.event.events.RenderEvent
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.module.modules.player.NoBreakAnimation
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.*
import me.zeroeightsix.kami.util.colourUtils.ColourHolder
import net.minecraft.block.*
import net.minecraft.client.Minecraft
import net.minecraft.client.audio.PositionedSoundRecord
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.item.EntityXPOrb
import net.minecraft.init.SoundEvents
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.client.CPacketPlayerDigging
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import java.util.*
import kotlin.math.roundToInt

/**
 * @author Avanatiker
 * @since 20/08/2020
 */

@Module.Info(
        name = "HighwayTools",
        description = "Even Better High-ways for the greater good.",
        category = Module.Category.MISC
)
class HighwayTools : Module() {
    private val mode = register(Settings.e<Mode>("Mode", Mode.HIGHWAY))
    private val baritoneModee = register(Settings.b("Baritone", true))
    private val infoMessage = register(Settings.b("Logs", true))
    private val blocksPerTick = register(Settings.integerBuilder("BlocksPerTick").withMinimum(1).withValue(1).withMaximum(9).build())
    private val tickDelay = register(Settings.integerBuilder("TickDelay").withMinimum(0).withValue(1).withMaximum(10).build())
    private val rotate = register(Settings.b("Rotate", true))
    private val filled = register(Settings.b("Filled", true))
    private val outline = register(Settings.b("Outline", true))
    private val aFilled = register(Settings.integerBuilder("FilledAlpha").withMinimum(0).withValue(31).withMaximum(255).withVisibility { filled.value }.build())
    private val aOutline = register(Settings.integerBuilder("OutlineAlpha").withMinimum(0).withValue(127).withMaximum(255).withVisibility { outline.value }.build())

    private var playerHotbarSlot = -1
    private var buildDirectionSaved = 0
    private var buildDirectionCoordinateSaved = 0.0
    private var buildDirectionCoordinateSavedY = 0.0
    private val directions = listOf("North", "East", "South", "West")

    private var isSneaking = false

    //Stats
    var totalBlocksPlaced = 0
    var totalBlocksDestroyed = 0
    var totalBlocksDistanceWent = 0

    val blockQueue: Queue<BlockTask> = LinkedList<BlockTask>()
    private val doneQueue: Queue<BlockTask> = LinkedList<BlockTask>()
    var a = mutableListOf<Pair<BlockPos, Boolean>>()
    var waitTicks = 0

    override fun onEnable() {
        if (mc.player == null) {
            disable()
            return
        }
        buildDirectionSaved = getPlayerDirection()

        playerHotbarSlot = mc.player.inventory.currentItem
        buildDirectionCoordinateSavedY = mc.player.positionVector.y
        if (buildDirectionSaved == 0 || buildDirectionSaved == 2) {
            buildDirectionCoordinateSaved = mc.player.positionVector.x
        }
        else {
            buildDirectionCoordinateSaved = mc.player.positionVector.z
        }

        blockQueue.clear()
        doneQueueReset()
        updateTasks()
        MessageSendHelper.sendChatMessage("$chatName Module started." +
                "\n    §9> §rSelected direction: §a" + directions[buildDirectionSaved] + "§r" +
                "\n    §9> §rSnap to coordinate: §a" + buildDirectionCoordinateSaved.roundToInt() + "§r" +
                "\n    §9> §rBaritone mode: §a" + baritoneModee.value + "§r")
    }

    override fun onUpdate() {
        if (!BaritoneAPI.getProvider().primaryBaritone.customGoalProcess.isActive) {
            if (isDone()) {
                if (baritoneModee.value) {
                    moveOneBlock()
                }
                doneQueueReset()
                updateTasks()
                totalBlocksDistanceWent++
            } else {
                doTask()
                //getDebug()
            }
        }
    }

    private fun addTask(bps: BlockPos, ts: TaskState, bb: Boolean) {
        blockQueue.add(BlockTask(bps, ts, bb))
    }

    private fun getDebug() {
        MessageSendHelper.sendChatMessage("#### LOG ####")
        for (bt in blockQueue) {
            MessageSendHelper.sendChatMessage(bt.getBlockPos().toString() + " " + bt.getTaskState().toString() + " " + bt.getBlock().toString())
        }
        MessageSendHelper.sendChatMessage("#### DONE ####")
        for (bt in doneQueue) {
            MessageSendHelper.sendChatMessage(bt.getBlockPos().toString() + " " + bt.getTaskState().toString() + " " + bt.getBlock().toString())
        }
    }

    private fun doTask(): Boolean {
        if (!isDone()) {
            if (waitTicks == 0) {
                var blockAction = blockQueue.peek()
                if (blockAction.getTaskState() == TaskState.BREAK) {
                    mineBlock(blockAction.getBlockPos(), true)
                    blockAction.setTaskState(TaskState.BREAKING)
                    val block = mc.world.getBlockState(blockAction.getBlockPos()).block
                    if (block is BlockNetherrack) {
                        waitTicks = 0
                    } else {
                        val efficiencyLevel = 5
                        waitTicks = (block.blockHardness * 5.0 / (8 + efficiencyLevel * efficiencyLevel + 1) / 20).toInt()
                        waitTicks = 20
                    }
                } else if (blockAction.getTaskState() == TaskState.BREAKING) {
                    mineBlock(blockAction.getBlockPos(), false)
                    if (blockAction.getBlock()) {
                        blockAction.setTaskState(TaskState.PLACE)
                    } else {
                        blockAction.setTaskState(TaskState.DONE)
                    }
                } else if (blockAction.getTaskState() == TaskState.PLACE) {
                    if (placeBlock(blockAction.getBlockPos())) {
                        blockAction.setTaskState(TaskState.PLACED)
                    } else {
                        return false
                    }
                } else if (blockAction.getTaskState() == TaskState.PLACED) {
                    blockAction.setTaskState(TaskState.DONE)
                    doTask()
                } else if (blockAction.getTaskState() == TaskState.DONE) {
                    blockQueue.remove()
                    doneQueue.add(blockAction)
                    doTask()
                }
            } else {
                waitTicks--
            }
            return true
        } else {
            return false
        }
    }

    private fun updateTasks() {
        updateBlockArray()
        for ((a, b) in a) {
            val block = mc.world.getBlockState(a).block
            if (b && block is BlockAir) { addTask(a, TaskState.PLACE, true) }
            else if (b && block !is BlockAir && block !is BlockObsidian) { addTask(a, TaskState.BREAK, true) }
            else if (!b && block !is BlockAir) { addTask(a, TaskState.BREAK, false) }
            else if (b && block is BlockObsidian) { addTask(a, TaskState.DONE, true) }
            else if (!b && block is BlockAir) { addTask(a, TaskState.DONE, false) }
        }
    }

    private fun updateRenderer(renderer: ESPRenderer): ESPRenderer {
        val side = GeometryMasks.Quad.ALL
        for (bt in blockQueue) {
            if (bt.getTaskState() != TaskState.DONE) { renderer.add(bt.getBlockPos(), bt.getTaskState().color, side) }
        }
        for (bt in doneQueue) {
            if (bt.getBlock()) { renderer.add(bt.getBlockPos(), bt.getTaskState().color, side) }
        }
        return renderer
    }

    private fun moveOneBlock() {
        // set head rotation to get max walking speed
        var nextBlockPos: BlockPos
        if (buildDirectionSaved == 0) {
            nextBlockPos = BlockPos(mc.player.positionVector).north()
            mc.player.rotationYaw = -180F
        }
        else if (buildDirectionSaved == 1) {
            nextBlockPos = BlockPos(mc.player.positionVector).east()
            mc.player.rotationYaw = -90F
        }
        else if (buildDirectionSaved == 2) {
            nextBlockPos = BlockPos(mc.player.positionVector).south()
            mc.player.rotationYaw = 0F
        } else {
            nextBlockPos = BlockPos(mc.player.positionVector).west()
            mc.player.rotationYaw = 90F
        }
        mc.player.rotationPitch = 0F
        BaritoneAPI.getProvider().primaryBaritone.customGoalProcess.setGoalAndPath(GoalXZ(nextBlockPos.getX(), nextBlockPos.getZ()))
    }

    private fun mineBlock(pos: BlockPos, pre: Boolean) {
        if (InventoryUtils.getSlotsHotbar(278) == null && InventoryUtils.getSlotsNoHotbar(278) != null) {
            InventoryUtils.moveToHotbar(278, 130, (tickDelay.value * 16).toLong())
            return
        } else if (InventoryUtils.getSlots(0, 35, 278) == null) {
            MessageSendHelper.sendChatMessage("$chatName No pickaxe was found in inventory, disabling.")
            mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
            return
        }
        InventoryUtils.swapSlotToItem(278)
        lookAtBlock(pos)

        if (pre) {
            mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.START_DESTROY_BLOCK, pos, mc.objectMouseOver.sideHit))
        } else {
            mc.connection!!.sendPacket(CPacketPlayerDigging(CPacketPlayerDigging.Action.STOP_DESTROY_BLOCK, pos, mc.objectMouseOver.sideHit))
        }
        mc.player.swingArm(EnumHand.MAIN_HAND)
    }

    private fun placeBlock(pos: BlockPos): Boolean
    {
        // check if block is already placed
        val block = mc.world.getBlockState(pos).block
        if (block !is BlockAir && block !is BlockLiquid) {
            return false
        }

        // check if entity blocks placing
        for (entity in mc.world.getEntitiesWithinAABBExcludingEntity(null, AxisAlignedBB(pos))) {
            if (entity !is EntityItem && entity !is EntityXPOrb) {
                return false
            }
        }
        val side = getPlaceableSide(pos) ?: return false

        // check if we have a block adjacent to blockpos to click at
        val neighbour = pos.offset(side)
        val opposite = side.opposite

        // check if neighbor can be right clicked
        if (!BlockUtils.canBeClicked(neighbour)) {
            return false
        }

        //Swap to Obsidian in Hotbar or get from inventory
        if (InventoryUtils.getSlotsHotbar(49) == null && InventoryUtils.getSlotsNoHotbar(49) != null) {
            InventoryUtils.moveToHotbar(49, 130, (tickDelay.value * 16).toLong())
            return false
        } else if (InventoryUtils.getSlots(0, 35, 49) == null) {
            MessageSendHelper.sendChatMessage("$chatName No Obsidian was found in inventory, disabling.")
            mc.getSoundHandler().playSound(PositionedSoundRecord.getRecord(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f))
            disable()
            return false
        }
        InventoryUtils.swapSlotToItem(49)

        val hitVec = Vec3d(neighbour).add(0.5, 0.5, 0.5).add(Vec3d(opposite.directionVec).scale(0.5))
        val neighbourBlock = mc.world.getBlockState(neighbour).block

        if (!isSneaking && BlockUtils.blackList.contains(neighbourBlock) || BlockUtils.shulkerList.contains(neighbourBlock)) {
            mc.player.connection.sendPacket(CPacketEntityAction(mc.player, CPacketEntityAction.Action.START_SNEAKING))
            isSneaking = true
        }
        if (rotate.value) {
            BlockUtils.faceVectorPacketInstant(hitVec)
        }

        mc.playerController.processRightClickBlock(mc.player, mc.world, neighbour, opposite, hitVec, EnumHand.MAIN_HAND)
        mc.player.swingArm(EnumHand.MAIN_HAND)
        mc.rightClickDelayTimer = 4

        if (KamiMod.MODULE_MANAGER.isModuleEnabled(NoBreakAnimation::class.java)) {
            KamiMod.MODULE_MANAGER.getModuleT(NoBreakAnimation::class.java).resetMining()
        }
        return true
    }

    private fun getPlaceableSide(pos: BlockPos): EnumFacing? {
        for (side in EnumFacing.values()) {
            val neighbour = pos.offset(side)
            if (!mc.world.getBlockState(neighbour).block.canCollideCheck(mc.world.getBlockState(neighbour), false)) {
                continue
            }
            val blockState = mc.world.getBlockState(neighbour)
            if (!blockState.material.isReplaceable) {
                return side
            }
        }
        return null
    }

    private fun lookAtBlock(pos: BlockPos) {
        val vec3d = Vec3d((pos.x + 0.5) - mc.player.posX, pos.y - (mc.player.eyeHeight + mc.player.posY), (pos.z + 0.5) - mc.player.posZ)
        val lookAt = EntityUtils.getRotationFromVec3d(vec3d)
        mc.player.rotationYaw = lookAt[0].toFloat()
        mc.player.rotationPitch = lookAt[1].toFloat()
    }

    private fun updateBlockArray() {
        a.clear()
        var b = BlockPos(mc.player.positionVector)

        when(mode.value) {
            Mode.HIGHWAY -> {
                when (buildDirectionSaved) {
                    0 -> { //NORTH
                        a.add(Pair(b.down(), true))
                        a.add(Pair(b.down().north(), true))
                        a.add(Pair(b.down().north().north(), true))
                        a.add(Pair(b.down().north().north().east(), true))
                        a.add(Pair(b.down().north().north().west(), true))
                        a.add(Pair(b.down().north().north().east().east(), true))
                        a.add(Pair(b.down().north().north().west().west(), true))
                        a.add(Pair(b.down().north().north().east().east().east(), true))
                        a.add(Pair(b.down().north().north().west().west().west(), true))
                        a.add(Pair(b.north().north().east().east().east(), true))
                        a.add(Pair(b.north().north().west().west().west(), true))
                        a.add(Pair(b.north().north(), false))
                        a.add(Pair(b.north().north().east(), false))
                        a.add(Pair(b.north().north().west(), false))
                        a.add(Pair(b.north().north().east().east(), false))
                        a.add(Pair(b.north().north().west().west(), false))
                        a.add(Pair(b.up().north().north(), false))
                        a.add(Pair(b.up().north().north().east(), false))
                        a.add(Pair(b.up().north().north().west(), false))
                        a.add(Pair(b.up().north().north().east().east(), false))
                        a.add(Pair(b.up().north().north().west().west(), false))
                        a.add(Pair(b.up().north().north().east().east().east(), false))
                        a.add(Pair(b.up().north().north().west().west().west(), false))
                        a.add(Pair(b.up().up().north().north(), false))
                        a.add(Pair(b.up().up().north().north().east(), false))
                        a.add(Pair(b.up().up().north().north().west(), false))
                        a.add(Pair(b.up().up().north().north().east().east(), false))
                        a.add(Pair(b.up().up().north().north().west().west(), false))
                        a.add(Pair(b.up().up().north().north().east().east().east(), false))
                        a.add(Pair(b.up().up().north().north().west().west().west(), false))
                    }
                    1 -> { //EAST
                        a.add(Pair(b.down(), true))
                        a.add(Pair(b.down().east(), true))
                        a.add(Pair(b.down().east().east(), true))
                        a.add(Pair(b.down().east().east().south(), true))
                        a.add(Pair(b.down().east().east().north(), true))
                        a.add(Pair(b.down().east().east().south().south(), true))
                        a.add(Pair(b.down().east().east().north().north(), true))
                        a.add(Pair(b.down().east().east().south().south().south(), true))
                        a.add(Pair(b.down().east().east().north().north().north(), true))
                        a.add(Pair(b.east().east().south().south().south(), true))
                        a.add(Pair(b.east().east().north().north().north(), true))
                        a.add(Pair(b.east().east(), false))
                        a.add(Pair(b.east().east().south(), false))
                        a.add(Pair(b.east().east().north(), false))
                        a.add(Pair(b.east().east().south().south(), false))
                        a.add(Pair(b.east().east().north().north(), false))
                        a.add(Pair(b.up().east().east(), false))
                        a.add(Pair(b.up().east().east().south(), false))
                        a.add(Pair(b.up().east().east().north(), false))
                        a.add(Pair(b.up().east().east().south().south(), false))
                        a.add(Pair(b.up().east().east().north().north(), false))
                        a.add(Pair(b.up().east().east().south().south().south(), false))
                        a.add(Pair(b.up().east().east().north().north().north(), false))
                        a.add(Pair(b.up().up().east().east(), false))
                        a.add(Pair(b.up().up().east().east().south(), false))
                        a.add(Pair(b.up().up().east().east().north(), false))
                        a.add(Pair(b.up().up().east().east().south().south(), false))
                        a.add(Pair(b.up().up().east().east().north().north(), false))
                        a.add(Pair(b.up().up().east().east().south().south().south(), false))
                        a.add(Pair(b.up().up().east().east().north().north().north(), false))
                    }
                    2 -> { //SOUTH
                        a.add(Pair(b.down(), true))
                        a.add(Pair(b.down().south(), true))
                        a.add(Pair(b.down().south().south(), true))
                        a.add(Pair(b.down().south().south().east(), true))
                        a.add(Pair(b.down().south().south().west(), true))
                        a.add(Pair(b.down().south().south().east().east(), true))
                        a.add(Pair(b.down().south().south().west().west(), true))
                        a.add(Pair(b.down().south().south().east().east().east(), true))
                        a.add(Pair(b.down().south().south().west().west().west(), true))
                        a.add(Pair(b.south().south().east().east().east(), true))
                        a.add(Pair(b.south().south().west().west().west(), true))
                        a.add(Pair(b.south().south(), false))
                        a.add(Pair(b.south().south().east(), false))
                        a.add(Pair(b.south().south().west(), false))
                        a.add(Pair(b.south().south().east().east(), false))
                        a.add(Pair(b.south().south().west().west(), false))
                        a.add(Pair(b.up().south().south(), false))
                        a.add(Pair(b.up().south().south().east(), false))
                        a.add(Pair(b.up().south().south().west(), false))
                        a.add(Pair(b.up().south().south().east().east(), false))
                        a.add(Pair(b.up().south().south().west().west(), false))
                        a.add(Pair(b.up().south().south().east().east().east(), false))
                        a.add(Pair(b.up().south().south().west().west().west(), false))
                        a.add(Pair(b.up().up().south().south(), false))
                        a.add(Pair(b.up().up().south().south().east(), false))
                        a.add(Pair(b.up().up().south().south().west(), false))
                        a.add(Pair(b.up().up().south().south().east().east(), false))
                        a.add(Pair(b.up().up().south().south().west().west(), false))
                        a.add(Pair(b.up().up().south().south().east().east().east(), false))
                        a.add(Pair(b.up().up().south().south().west().west().west(), false))
                    }
                    3 -> { //WEST
                        a.add(Pair(b.down(), true))
                        a.add(Pair(b.down().west(), true))
                        a.add(Pair(b.down().west().west(), true))
                        a.add(Pair(b.down().west().west().south(), true))
                        a.add(Pair(b.down().west().west().north(), true))
                        a.add(Pair(b.down().west().west().south().south(), true))
                        a.add(Pair(b.down().west().west().north().north(), true))
                        a.add(Pair(b.down().west().west().south().south().south(), true))
                        a.add(Pair(b.down().west().west().north().north().north(), true))
                        a.add(Pair(b.west().west().south().south().south(), true))
                        a.add(Pair(b.west().west().north().north().north(), true))
                        a.add(Pair(b.west().west(), false))
                        a.add(Pair(b.west().west().south(), false))
                        a.add(Pair(b.west().west().north(), false))
                        a.add(Pair(b.west().west().south().south(), false))
                        a.add(Pair(b.west().west().north().north(), false))
                        a.add(Pair(b.up().west().west(), false))
                        a.add(Pair(b.up().west().west().south(), false))
                        a.add(Pair(b.up().west().west().north(), false))
                        a.add(Pair(b.up().west().west().south().south(), false))
                        a.add(Pair(b.up().west().west().north().north(), false))
                        a.add(Pair(b.up().west().west().south().south().south(), false))
                        a.add(Pair(b.up().west().west().north().north().north(), false))
                        a.add(Pair(b.up().up().west().west(), false))
                        a.add(Pair(b.up().up().west().west().south(), false))
                        a.add(Pair(b.up().up().west().west().north(), false))
                        a.add(Pair(b.up().up().west().west().south().south(), false))
                        a.add(Pair(b.up().up().west().west().north().north(), false))
                        a.add(Pair(b.up().up().west().west().south().south().south(), false))
                        a.add(Pair(b.up().up().west().west().north().north().north(), false))
                    }
                }
            }
        }
    }

    fun isDone(): Boolean { return blockQueue.size == 0 }
    fun doneQueueReset() { doneQueue.clear() }

    override fun onWorldRender(event: RenderEvent) {
        if (mc.player == null) return
        val renderer = ESPRenderer(event.partialTicks)
        renderer.aFilled = if (filled.value) aFilled.value else 0
        renderer.aOutline = if (outline.value) aOutline.value else 0
        updateRenderer(renderer)
        renderer.render()
    }

    fun getPlayerDirection(): Int {
        val yaw = (mc.player.rotationYaw % 360 + 360) % 360
        if (yaw >= 135 && yaw < 225) { return 0 } //NORTH
        else if (yaw >= 225 && yaw < 315) { return 1 } //EAST
        else if (yaw >= 315 || yaw < 45) { return 2 } //SOUTH
        else if (yaw >= 45 && yaw < 135){ return 3 } //WEST
        else { return -1 } //WRONG
    }
}

class BlockTask(private val bp: BlockPos, private var tt: TaskState, private val bb: Boolean) {
    fun getBlockPos(): BlockPos { return bp }
    fun getTaskState(): TaskState { return tt }
    fun setTaskState(tts: TaskState) { tt = tts }
    fun getBlock(): Boolean { return bb }
}

enum class TaskState(val color: ColourHolder) {
    BREAK(ColourHolder(222, 0, 0)),
    BREAKING(ColourHolder(240, 222, 60)),
    PLACE(ColourHolder(35, 188, 254)),
    PLACED(ColourHolder(53, 222, 66)),
    DONE(ColourHolder(50, 50, 50))
}

enum class Mode {
    FLAT, HIGHWAY
}
