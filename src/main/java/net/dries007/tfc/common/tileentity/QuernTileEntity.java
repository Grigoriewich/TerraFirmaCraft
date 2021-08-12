/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.tileentity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.items.ItemStackHandler;

import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.items.TFCItems;
import net.dries007.tfc.common.recipes.ItemStackRecipeWrapper;
import net.dries007.tfc.common.recipes.QuernRecipe;
import net.dries007.tfc.util.Helpers;

import static net.dries007.tfc.TerraFirmaCraft.MOD_ID;

public class QuernTileEntity extends InventoryTileEntity<ItemStackHandler>
{
    public static final int SLOT_HANDSTONE = 0;
    public static final int SLOT_INPUT = 1;
    public static final int SLOT_OUTPUT = 2;

    private static final Component NAME = new TranslatableComponent(MOD_ID + ".tile_entity.quern");

    public static void serverTick(Level level, BlockPos pos, BlockState state, QuernTileEntity quern)
    {
        if (quern.rotationTimer > 0)
        {
            quern.rotationTimer--;
            if (quern.rotationTimer == 0)
            {
                quern.grindItem();
                Helpers.playSound(level, pos, SoundEvents.ARMOR_STAND_FALL);
                Helpers.damageItem(quern.inventory.getStackInSlot(SLOT_HANDSTONE), 1);

                if (quern.inventory.getStackInSlot(SLOT_HANDSTONE).isEmpty())
                {
                    Helpers.playSound(level, pos, SoundEvents.STONE_BREAK);
                    Helpers.playSound(level, pos, SoundEvents.ITEM_BREAK);
                }
                quern.setAndUpdateSlots(SLOT_HANDSTONE);
            }
        }
    }

    public static void clientTick(Level level, BlockPos pos, BlockState state, QuernTileEntity quern)
    {
        if (quern.rotationTimer > 0)
        {
            quern.rotationTimer--;
            addParticle(level, pos, quern.inventory.getStackInSlot(SLOT_INPUT));
            if (quern.rotationTimer == 0)
            {
                Helpers.damageItem(quern.inventory.getStackInSlot(SLOT_HANDSTONE), 1);
                if (quern.inventory.getStackInSlot(SLOT_HANDSTONE).isEmpty())
                {
                    for (int i = 0; i < 15; i++)
                    {
                        addParticle(level, pos, new ItemStack(TFCItems.HANDSTONE.get()));
                    }
                }
            }
        }
    }

    private static void addParticle(Level level, BlockPos pos, ItemStack item)
    {
        level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, item), pos.getX() + 0.5D, pos.getY() + 0.875D, pos.getZ() + 0.5D, Helpers.fastGaussian(level.random) / 2.0D, level.random.nextDouble() / 4.0D, Helpers.fastGaussian(level.random) / 2.0D);
    }

    private int rotationTimer;
    private boolean hasHandstone;

    public QuernTileEntity(BlockPos pos, BlockState state)
    {
        super(TFCTileEntities.QUERN.get(), pos, state, defaultInventory(3), NAME);
        rotationTimer = 0;
    }

    @Override
    public int getSlotStackLimit(int slot)
    {
        return slot == SLOT_HANDSTONE ? 1 : 64;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack)
    {
        switch (slot)
        {
            case SLOT_HANDSTONE:
                return TFCTags.Items.HANDSTONE.contains(stack.getItem()); // needs to be handstone
            case SLOT_INPUT:
                assert level != null;
                return QuernRecipe.getRecipe(level, new ItemStackRecipeWrapper(stack)) != null; // recipe must exist + weird item swap glitch workaround
            case SLOT_OUTPUT:
                return true;
        }
        return false;
    }

    @Override
    public void setAndUpdateSlots(int slot)
    {
        markForBlockUpdate();
        if (slot == SLOT_HANDSTONE)
        {
            hasHandstone = TFCTags.Items.HANDSTONE.contains(inventory.getStackInSlot(SLOT_HANDSTONE).getItem());
        }
        super.setAndUpdateSlots(slot);
    }

    @Override
    public void load(CompoundTag nbt)
    {
        rotationTimer = nbt.getInt("rotationTimer");
        super.load(nbt);
        hasHandstone = TFCTags.Items.HANDSTONE.contains(inventory.getStackInSlot(SLOT_HANDSTONE).getItem());
    }

    @Override
    public CompoundTag save(CompoundTag nbt)
    {
        nbt.putInt("rotationTimer", rotationTimer);
        return super.save(nbt);
    }

    @Override
    public boolean canInteractWith(Player player)
    {
        return super.canInteractWith(player) && rotationTimer == 0;
    }

    public int getRotationTimer()
    {
        return rotationTimer;
    }

    public boolean isGrinding()
    {
        return rotationTimer > 0;
    }

    public boolean hasHandstone()
    {
        return hasHandstone;
    }

    public void grind()
    {
        this.rotationTimer = 90;
        markForBlockUpdate();
    }

    private void addParticleSafely(ItemStack item)
    {
        assert level != null;
        if (level.isClientSide && !item.isEmpty())
        {
            level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, item), worldPosition.getX() + 0.5D, worldPosition.getY() + 0.875D, worldPosition.getZ() + 0.5D, Helpers.fastGaussian(level.random) / 2.0D, level.random.nextDouble() / 4.0D, Helpers.fastGaussian(level.random) / 2.0D);
        }
    }

    private void grindItem()
    {
        assert level != null;
        ItemStack inputStack = inventory.getStackInSlot(SLOT_INPUT);
        if (!level.isClientSide && !inputStack.isEmpty())
        {
            ItemStackRecipeWrapper wrapper = new ItemStackRecipeWrapper(inputStack);
            QuernRecipe recipe = QuernRecipe.getRecipe(level, wrapper);
            if (recipe != null)
            {
                inputStack.shrink(1);
                ItemStack outputStack = recipe.assemble(wrapper);
                outputStack = inventory.insertItem(SLOT_OUTPUT, outputStack, false);
                //todo: mergeItemStacksIgnoreCreationDate
                if (!outputStack.isEmpty())
                {
                    // Still having leftover items, dumping in world
                    Helpers.spawnItem(level, worldPosition, outputStack);
                }
            }
        }
    }
}