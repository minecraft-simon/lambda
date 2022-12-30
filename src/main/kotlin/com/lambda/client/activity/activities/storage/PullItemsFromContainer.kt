package com.lambda.client.activity.activities.storage

import com.lambda.client.activity.Activity
import com.lambda.client.activity.activities.inventory.QuickMoveSlot
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.util.items.countEmpty
import com.lambda.client.util.items.inventorySlots
import net.minecraft.item.Item
import net.minecraft.item.ItemStack

class PullItemsFromContainer(
    private val item: Item,
    private val amount: Int, // 0 = all
    private val predicateItem: (ItemStack) -> Boolean = { true }
) : Activity() {
    override fun SafeClientEvent.onInitialize() {
        val maxEmpty = player.inventorySlots.countEmpty() - 1

        val take = if (amount > 0) amount.coerceAtMost(maxEmpty) else maxEmpty

        player.openContainer.inventorySlots.filter { slot ->
            slot.stack.item == item && predicateItem(slot.stack)
        }.take(take).forEach {
            addSubActivities(QuickMoveSlot(it))
        }
    }
}