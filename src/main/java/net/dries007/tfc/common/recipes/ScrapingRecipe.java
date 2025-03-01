/*
 * Licensed under the EUPL, Version 1.2.
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 */

package net.dries007.tfc.common.recipes;

import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.GsonHelper;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import net.dries007.tfc.common.recipes.inventory.ItemStackInventory;
import net.dries007.tfc.common.recipes.outputs.ItemStackProvider;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.JsonHelpers;
import net.dries007.tfc.util.collections.IndirectHashCollection;

public class ScrapingRecipe extends SimpleItemRecipe
{
    public static final IndirectHashCollection<Item, ScrapingRecipe> CACHE = IndirectHashCollection.createForRecipe(ScrapingRecipe::getValidItems, TFCRecipeTypes.SCRAPING);

    @Nullable
    public static ScrapingRecipe getRecipe(Level world, ItemStackInventory wrapper)
    {
        for (ScrapingRecipe recipe : CACHE.getAll(wrapper.getStack().getItem()))
        {
            if (recipe.matches(wrapper, world))
            {
                return recipe;
            }
        }
        return null;
    }

    private final ResourceLocation inputTexture;
    private final ResourceLocation outputTexture;

    public ScrapingRecipe(ResourceLocation id, Ingredient ingredient, ItemStackProvider result, ResourceLocation inputTexture, ResourceLocation outputTexture)
    {
        super(id, ingredient, result);
        this.inputTexture = inputTexture;
        this.outputTexture = outputTexture;
    }

    @Override
    public RecipeSerializer<?> getSerializer()
    {
        return TFCRecipeSerializers.SCRAPING.get();
    }

    public ResourceLocation getInputTexture()
    {
        return inputTexture;
    }

    public ResourceLocation getOutputTexture()
    {
        return outputTexture;
    }

    @Override
    public RecipeType<?> getType()
    {
        return TFCRecipeTypes.SCRAPING.get();
    }

    public static class Serializer extends RecipeSerializerImpl<ScrapingRecipe>
    {
        @Override
        public ScrapingRecipe fromJson(ResourceLocation id, JsonObject json)
        {
            final Ingredient ingredient = Ingredient.fromJson(JsonHelpers.get(json, "ingredient"));
            final ItemStackProvider result = ItemStackProvider.fromJson(GsonHelper.getAsJsonObject(json, "result"));
            final ResourceLocation inputTexture = new ResourceLocation(JsonHelpers.getAsString(json, "input_texture"));
            final ResourceLocation outputTexture = new ResourceLocation(JsonHelpers.getAsString(json, "output_texture"));
            return new ScrapingRecipe(id, ingredient, result, inputTexture, outputTexture);
        }

        @Nullable
        @Override
        public ScrapingRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buffer)
        {
            final Ingredient ingredient = Ingredient.fromNetwork(buffer);
            final ItemStackProvider result = ItemStackProvider.fromNetwork(buffer);
            final ResourceLocation inputTexture = new ResourceLocation(buffer.readUtf());
            final ResourceLocation outputTexture = new ResourceLocation(buffer.readUtf());
            return new ScrapingRecipe(id, ingredient, result, inputTexture, outputTexture);
        }

        @Override
        public void toNetwork(FriendlyByteBuf buffer, ScrapingRecipe recipe)
        {
            recipe.getIngredient().toNetwork(buffer);
            recipe.getResult().toNetwork(buffer);
            buffer.writeUtf(recipe.getInputTexture().toString());
            buffer.writeUtf(recipe.getOutputTexture().toString());
        }
    }
}
