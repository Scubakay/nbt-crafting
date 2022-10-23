/*
 * Copyright 2020-2022 Siphalor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.
 * See the License for the specific language governing
 * permissions and limitations under the License.
 */

package de.siphalor.nbtcrafting3.ingredient;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import net.minecraft.util.JsonHelper;
import net.minecraft.util.Pair;
import net.minecraft.util.registry.Registry;

import de.siphalor.nbtcrafting3.NbtCrafting;
import de.siphalor.nbtcrafting3.api.nbt.NbtException;
import de.siphalor.nbtcrafting3.api.nbt.NbtIterator;
import de.siphalor.nbtcrafting3.api.nbt.NbtNumberRange;
import de.siphalor.nbtcrafting3.api.nbt.NbtUtil;
import de.siphalor.nbtcrafting3.dollar.DollarExtractor;
import de.siphalor.nbtcrafting3.dollar.DollarUtil;
import de.siphalor.nbtcrafting3.dollar.exception.DollarEvaluationException;
import de.siphalor.nbtcrafting3.dollar.exception.UnresolvedDollarReferenceException;
import de.siphalor.nbtcrafting3.dollar.part.DollarPart;

public class IngredientEntryCondition {
	public static final IngredientEntryCondition EMPTY = new IngredientEntryCondition(NbtUtil.EMPTY_COMPOUND, NbtUtil.EMPTY_COMPOUND);

	public NbtCompound requiredElements;
	public NbtCompound deniedElements;
	public List<Pair<String, DollarPart>> dollarPredicates;
	private NbtCompound previewTag;

	protected IngredientEntryCondition() {
		requiredElements = NbtUtil.EMPTY_COMPOUND;
		deniedElements = NbtUtil.EMPTY_COMPOUND;
		dollarPredicates = null;
	}

	public IngredientEntryCondition(NbtCompound requiredElements, NbtCompound deniedElements) {
		this.requiredElements = requiredElements;
		this.deniedElements = deniedElements;
	}

	public boolean matches(ItemStack stack) {
		if (!stack.hasTag()) {
			return requiredElements.isEmpty();
		}
		NbtCompound tag = stack.getTag();
		//noinspection ConstantConditions
		if (!deniedElements.isEmpty() && NbtUtil.compoundsOverlap(tag, deniedElements))
			return false;
		if (!requiredElements.isEmpty() && !NbtUtil.isCompoundContained(requiredElements, tag))
			return false;
		if (dollarPredicates != null && !dollarPredicates.isEmpty()) {
			for (Pair<String, DollarPart> predicate : dollarPredicates) {
				try {
					if (!DollarUtil.asBoolean(predicate.getRight().evaluate(ref -> {
						if ("$".equals(ref)) {
							return tag;
						}
						throw new UnresolvedDollarReferenceException(ref);
					}))) {
						return false;
					}
				} catch (DollarEvaluationException e) {
					NbtCrafting.logWarn("Failed to evaluate dollar predicate (" + predicate.getLeft() + "): " + e.getMessage());
				}
			}
		}
		return true;
	}

	public void addToJson(JsonObject json) {
		if (requiredElements.getSize() > 0)
			json.add("require", NbtUtil.toJson(requiredElements));
		if (deniedElements.getSize() > 0)
			json.add("deny", NbtUtil.toJson(deniedElements));
		if (dollarPredicates != null && !dollarPredicates.isEmpty()) {
			JsonArray array = new JsonArray();
			for (Pair<String, DollarPart> condition : dollarPredicates) {
				array.add(condition.getLeft());
			}
			json.add("conditions", array);
		}
	}

	public NbtCompound getPreviewTag() {
		if (previewTag == null) {
			previewTag = requiredElements.copy();
			List<Pair<String[], NbtElement>> dollarRangeKeys = new ArrayList<>();
			NbtIterator.iterateTags(previewTag, (path, key, tag) -> {
				if (NbtUtil.isString(tag)) {
					String text = NbtUtil.asString(tag);
					if (text.startsWith("$")) {
						dollarRangeKeys.add(new Pair<>(NbtUtil.splitPath(path + key), NbtNumberRange.ofString(text.substring(1)).getExample()));
					}
				}
				return NbtIterator.Action.RECURSE;
			});
			for (Pair<String[], NbtElement> dollarRangeKey : dollarRangeKeys) {
				try {
					NbtUtil.put(previewTag, dollarRangeKey.getLeft(), dollarRangeKey.getRight());
				} catch (NbtException e) {
					NbtCrafting.logWarn("Failed to set dollar range value " + dollarRangeKey.getRight() + " for key " + String.join(".", dollarRangeKey.getLeft()) + " in preview tag " + previewTag);
				}
			}
		}
		return previewTag;
	}

	public static IngredientEntryCondition fromJson(JsonObject json) {
		IngredientEntryCondition condition = new IngredientEntryCondition();

		boolean flatObject = true;

		if (json.has("require")) {
			if (!json.get("require").isJsonObject())
				throw new JsonParseException("data.require must be an object");
			condition.requiredElements = (NbtCompound) NbtUtil.asTag(json.getAsJsonObject("require"));
			flatObject = false;
		}
		if (json.has("potion")) {
			Identifier potion = new Identifier(JsonHelper.getString(json, "potion"));
			if (Registry.POTION.getOrEmpty(potion).isPresent()) {
				if (condition.requiredElements == NbtUtil.EMPTY_COMPOUND) {
					condition.requiredElements = new NbtCompound();
				}
				condition.requiredElements.putString("Potion", potion.toString());
			} else {
				new JsonSyntaxException("Unknown potion '" + potion + "'").printStackTrace();
			}
			flatObject = false;
		}
		if (json.has("deny")) {
			if (!json.get("deny").isJsonObject())
				throw new JsonParseException("data.deny must be an object");
			condition.deniedElements = (NbtCompound) NbtUtil.asTag(json.getAsJsonObject("deny"));
			flatObject = false;
		}
		if (json.has("conditions")) {
			if (!json.get("conditions").isJsonArray())
				throw new JsonParseException("data.conditions must be an array");
			JsonArray array = json.getAsJsonArray("conditions");
			List<Pair<String, DollarPart>> predicates = new ArrayList<>(array.size());
			for (JsonElement jsonElement : array) {
				if (!JsonHelper.isString(jsonElement))
					throw new JsonParseException("data.conditions must be an array of strings");
				predicates.add(new Pair<>(jsonElement.getAsString(), DollarExtractor.parse(jsonElement.getAsString())));
			}
			condition.dollarPredicates = predicates;
			flatObject = false;
		}

		if (flatObject) {
			condition.requiredElements = (NbtCompound) NbtUtil.asTag(json);
		}

		return condition;
	}

	public void write(PacketByteBuf buf) {
		buf.writeNbt(requiredElements);
		buf.writeNbt(deniedElements);
	}

	public static IngredientEntryCondition read(PacketByteBuf buf) {
		return new IngredientEntryCondition(buf.readNbt(), buf.readNbt());
	}
}
