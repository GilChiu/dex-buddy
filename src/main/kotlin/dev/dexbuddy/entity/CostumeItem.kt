package dev.dexbuddy.entity

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.TooltipFlag

/** A costume for the companion. Right-click your companion with it to unlock that look for good. */
class CostumeItem(val look: Look, props: Properties) : Item(props) {
    override fun appendHoverText(stack: ItemStack, context: TooltipContext, tooltip: MutableList<Component>, flag: TooltipFlag) {
        tooltip += Component.literal("Give it to your Dex Buddy to unlock this look").withStyle(ChatFormatting.GRAY)
    }
}
