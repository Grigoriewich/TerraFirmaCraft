/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.tileentity;

import java.util.Arrays;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.apache.commons.lang3.tuple.Triple;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.items.ItemStackHandler;

import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.blocks.devices.CharcoalForgeBlock;
import net.dries007.tfc.common.capabilities.heat.Heat;
import net.dries007.tfc.common.capabilities.heat.HeatCapability;
import net.dries007.tfc.common.container.CharcoalForgeContainer;
import net.dries007.tfc.common.recipes.HeatingRecipe;
import net.dries007.tfc.common.recipes.ItemStackRecipeWrapper;
import net.dries007.tfc.common.types.Fuel;
import net.dries007.tfc.common.types.FuelManager;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.IntArrayBuilder;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.ICalendarTickable;

import static net.dries007.tfc.TerraFirmaCraft.MOD_ID;

public class CharcoalForgeTileEntity extends TickableInventoryTileEntity<ItemStackHandler> implements ICalendarTickable, MenuProvider
{
    public static final int SLOT_FUEL_MIN = 0;
    public static final int SLOT_FUEL_MAX = 4;
    public static final int SLOT_INPUT_MIN = 5;
    public static final int SLOT_INPUT_MAX = 9;
    public static final int SLOT_EXTRA_MIN = 10;
    public static final int SLOT_EXTRA_MAX = 13;
    public static final int DATA_SLOT_TEMPERATURE = 0;

    private static final Component NAME = new TranslatableComponent(MOD_ID + ".tile_entity.charcoal_forge");
    private static final int MAX_AIR_TICKS = 600;

    public static void serverTick(Level level, BlockPos pos, BlockState state, CharcoalForgeTileEntity forge)
    {
        forge.checkForLastTickSync();
        forge.checkForCalendarUpdate();

        boolean isRaining = level.isRainingAt(pos);
        if (state.getValue(CharcoalForgeBlock.HEAT) > 0)
        {
            if (isRaining && level.random.nextFloat() < 0.15F)
            {
                Helpers.playSound(level, pos, SoundEvents.LAVA_EXTINGUISH);
            }
            int heatLevel = Mth.clamp((int) (forge.temperature / Heat.maxVisibleTemperature() * 6) + 1, 1, 7); // scaled 1 through 7
            if (heatLevel != state.getValue(CharcoalForgeBlock.HEAT))
            {
                level.setBlockAndUpdate(pos, state.setValue(CharcoalForgeBlock.HEAT, heatLevel));
                forge.markForSync();
            }
            // Update fuel
            if (forge.burnTicks > 0)
            {
                // Double fuel consumption if using bellows
                forge.burnTicks -= forge.airTicks > 0 || isRaining ? 2 : 1; // Fuel burns twice as fast using bellows, or in the rain
            }
            if (forge.burnTicks <= 0)
            {
                // Consume fuel
                ItemStack stack = forge.inventory.getStackInSlot(SLOT_FUEL_MIN);
                if (stack.isEmpty())
                {
                    forge.extinguish(state);
                }
                else
                {
                    forge.inventory.setStackInSlot(SLOT_FUEL_MIN, ItemStack.EMPTY);
                    forge.needsSlotUpdate = true;
                    Fuel fuel = FuelManager.get(stack);
                    if (fuel != null)
                    {
                        forge.burnTicks = fuel.getDuration();
                        forge.burnTemperature = fuel.getTemperature();
                    }
                    forge.markForSync();
                }
            }
        }
        else if (forge.burnTemperature > 0)
        {
            forge.extinguish(state);
        }
        if (forge.airTicks > 0)
        {
            forge.airTicks--;
        }

        // Always update temperature / cooking, until the fire pit is not hot anymore
        if (forge.temperature > 0 || forge.burnTemperature > 0)
        {
            forge.temperature = HeatCapability.adjustDeviceTemp(forge.temperature, forge.burnTemperature, forge.airTicks, isRaining);
                /*todo // Provide heat to blocks that are one block above
                Block blockUp = world.getBlockState(pos.above()).getBlock();
                if (blockUp instanceof IHeatConsumerBlock)
                {
                    ((IHeatConsumerBlock) blockUp).acceptHeat(world, pos.above(), temperature);
                }*/

            for (int i = SLOT_INPUT_MIN; i <= SLOT_INPUT_MAX; i++)
            {
                ItemStack stack = forge.inventory.getStackInSlot(i);
                int slot = i;
                stack.getCapability(HeatCapability.CAPABILITY).ifPresent(cap -> {
                    // Update temperature of item
                    float itemTemp = cap.getTemperature();
                    if (forge.temperature > itemTemp)
                    {
                        HeatCapability.addTemp(cap, forge.temperature);
                    }

                    // Handle possible melting, or conversion (if reach 1599 = pit kiln temperature)
                    forge.handleInputMelting(stack, slot);
                });
            }
            forge.markForSync();
        }

        // This is here to avoid duplication glitches
        if (forge.needsSlotUpdate)
        {
            forge.cascadeFuelSlots();
        }
    }

    protected final ContainerData syncableData;
    private final HeatingRecipe[] cachedRecipes = new HeatingRecipe[5];
    private boolean needsSlotUpdate = false;
    private float temperature; // Current Temperature
    private int burnTicks; // Ticks remaining on the current item of fuel
    private float burnTemperature; // Temperature provided from the current item of fuel
    private int airTicks; // Ticks of air provided by bellows
    private long lastPlayerTick; // Last player tick this forge was ticked (for purposes of catching up)

    public CharcoalForgeTileEntity(BlockPos pos, BlockState state)
    {
        super(TFCTileEntities.CHARCOAL_FORGE.get(), pos, state, defaultInventory(14), NAME);

        temperature = 0;
        burnTemperature = 0;
        burnTicks = 0;
        airTicks = 0;
        lastPlayerTick = Calendars.SERVER.getTicks();
        syncableData = new IntArrayBuilder().add(() -> (int) temperature, value -> temperature = value);

        Arrays.fill(cachedRecipes, null);
    }

    public void onAirIntake(int amount)
    {
        airTicks += amount;
        if (airTicks > MAX_AIR_TICKS)
        {
            airTicks = MAX_AIR_TICKS;
        }
    }

    @Override
    public void onCalendarUpdate(long deltaPlayerTicks)
    {
        assert level != null;
        BlockState state = level.getBlockState(worldPosition);
        if (state.getValue(CharcoalForgeBlock.HEAT) == 0) return;

        Triple<Integer, Float, Long> triple = Helpers.consumeFuelForTicks(deltaPlayerTicks, inventory, burnTicks, burnTemperature, SLOT_FUEL_MIN, SLOT_FUEL_MAX);
        burnTicks = triple.getLeft();
        burnTemperature = triple.getMiddle();
        deltaPlayerTicks = triple.getRight();
        needsSlotUpdate = true;
        if (deltaPlayerTicks > 0)
        {
            // Consumed all fuel, so extinguish and cool instantly
            extinguish(state);
            for (int i = SLOT_INPUT_MIN; i <= SLOT_INPUT_MAX; i++)
            {
                ItemStack stack = inventory.getStackInSlot(i);
                stack.getCapability(HeatCapability.CAPABILITY).ifPresent(cap -> cap.setTemperature(0f));
            }
        }
    }

    @Override
    public long getLastUpdateTick()
    {
        return lastPlayerTick;
    }

    @Override
    public void setLastUpdateTick(long tick)
    {
        lastPlayerTick = tick;
    }

    public ContainerData getSyncableData()
    {
        return syncableData;
    }

    public void onCreate()
    {
        burnTicks = 200;
        burnTemperature = 500;
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int windowID, Inventory playerInv, Player player)
    {
        return new CharcoalForgeContainer(this, playerInv, windowID);
    }

    @Override
    public void load(CompoundTag nbt)
    {
        temperature = nbt.getFloat("temperature");
        burnTicks = nbt.getInt("burnTicks");
        airTicks = nbt.getInt("airTicks");
        burnTemperature = nbt.getFloat("burnTemperature");
        lastPlayerTick = nbt.getLong("lastPlayerTick");

        updateCachedRecipes();
        super.load(nbt);
    }

    @Override
    @Nonnull
    public CompoundTag save(CompoundTag nbt)
    {
        nbt.putFloat("temperature", temperature);
        nbt.putInt("burnTicks", burnTicks);
        nbt.putInt("airTicks", airTicks);
        nbt.putFloat("burnTemperature", burnTemperature);
        nbt.putLong("lastPlayerTick", lastPlayerTick);
        return super.save(nbt);
    }

    @Override
    public void setAndUpdateSlots(int slot)
    {
        super.setAndUpdateSlots(slot);
        needsSlotUpdate = true;
        updateCachedRecipes();
    }

    @Override
    public int getSlotStackLimit(int slot)
    {
        return 1;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack)
    {
        if (slot <= SLOT_FUEL_MAX)
        {
            return TFCTags.Items.FORGE_FUEL.contains(stack.getItem());
        }
        else if (slot <= SLOT_INPUT_MAX)
        {
            return stack.getCapability(HeatCapability.CAPABILITY).isPresent();
        }
        else
        {
            return stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY).isPresent() && stack.getCapability(HeatCapability.CAPABILITY).isPresent();
        }
    }

    private void extinguish(BlockState state)
    {
        assert level != null;
        level.setBlockAndUpdate(worldPosition, state.setValue(CharcoalForgeBlock.HEAT, 0));
        burnTicks = 0;
        burnTemperature = 0;
        markForSync();
    }

    private void handleInputMelting(ItemStack stack, int startIndex)
    {
        HeatingRecipe recipe = cachedRecipes[startIndex - SLOT_INPUT_MIN];
        stack.getCapability(HeatCapability.CAPABILITY).ifPresent(cap -> {
            if (recipe != null && recipe.isValidTemperature(cap.getTemperature()))
            {
                // Handle possible metal output
                FluidStack fluidStack = recipe.getOutputFluid(new ItemStackRecipeWrapper(stack));
                ItemStack outputStack = recipe.assemble(new ItemStackRecipeWrapper(stack));
                float itemTemperature = cap.getTemperature();
                if (!fluidStack.isEmpty())
                {
                    // Loop through all input slots
                    for (int slot = SLOT_EXTRA_MIN; slot <= SLOT_EXTRA_MAX; slot++)
                    {
                        FluidStack leftover = Helpers.mergeOutputFluidIntoSlot(inventory, fluidStack, itemTemperature, slot);
                        if (leftover.isEmpty()) break;
                    }
                }
                //todo CapabilityFood.applyTrait(outputStack, FoodTrait.CHARCOAL_GRILLED);
                inventory.setStackInSlot(startIndex, outputStack);
            }
        });
    }

    private void cascadeFuelSlots()
    {
        // This will cascade all fuel down to the lowest available slot
        int lowestAvailSlot = 0;
        for (int i = 0; i <= SLOT_FUEL_MAX; i++)
        {
            ItemStack stack = inventory.getStackInSlot(i);
            if (!stack.isEmpty())
            {
                // Move to lowest avail slot
                if (i > lowestAvailSlot)
                {
                    inventory.setStackInSlot(lowestAvailSlot, stack.copy());
                    inventory.setStackInSlot(i, ItemStack.EMPTY);
                }
                lowestAvailSlot++;
            }
        }
        needsSlotUpdate = false;
    }

    private void updateCachedRecipes()
    {
        // cache heat recipes for each input
        assert level != null;
        for (int i = SLOT_INPUT_MIN; i <= SLOT_INPUT_MAX; i++)
        {
            cachedRecipes[i - SLOT_INPUT_MIN] = null;
            ItemStack inputStack = inventory.getStackInSlot(i);
            if (!inputStack.isEmpty())
            {
                cachedRecipes[i - SLOT_INPUT_MIN] = HeatingRecipe.getRecipe(level, new ItemStackRecipeWrapper(inputStack));
            }
        }
    }
}
