package com.p38.anclab.dsp;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.VehicleCancellationRecipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines successful route-path observations instead of overwriting previous runs. */
public final class VehicleRecipeBook {
    private final Map<String, VehicleCancellationRecipe> recipes = new LinkedHashMap<>();

    public VehicleRecipeBook(List<VehicleCancellationRecipe> initial) {
        if (initial != null) for (VehicleCancellationRecipe recipe : initial) add(recipe);
    }

    public synchronized VehicleCancellationRecipe find(String routeKey, String modelId,
                                                        MechanicalFrequency.SourceType sourceType,
                                                        double sourceValue, double frequencyHz) {
        double bin = VehicleCancellationRecipe.quantizeSource(sourceType, sourceValue, frequencyHz);
        VehicleCancellationRecipe exact = recipes.get(key(routeKey, modelId, sourceType, bin));
        if (usable(exact, frequencyHz)) return exact;
        VehicleCancellationRecipe best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (VehicleCancellationRecipe recipe : recipes.values()) {
            if (!recipe.routeKey().equals(routeKey) || !recipe.modelId().equals(modelId)
                    || recipe.sourceType() != sourceType || !usable(recipe, frequencyHz)) continue;
            double distance = Math.abs(recipe.sourceBin() - bin);
            if (distance < bestDistance) { best = recipe; bestDistance = distance; }
        }
        return best;
    }

    /** Confidence-weighted complex averaging preserves every successful observation. */
    public synchronized VehicleCancellationRecipe add(VehicleCancellationRecipe incoming) {
        if (incoming == null || incoming.secondaryPath().magnitude() < 1.0e-5) return null;
        VehicleCancellationRecipe old = recipes.get(incoming.key());
        if (old == null) { recipes.put(incoming.key(), incoming); return incoming; }
        int oldWeight = Math.max(1, old.observations());
        int newWeight = Math.max(1, incoming.observations());
        double denominator = oldWeight + (double)newWeight;
        VehicleCancellationRecipe aggregate = new VehicleCancellationRecipe(
                incoming.routeKey(), incoming.modelId(), incoming.sourceType(), incoming.sourceBin(),
                (old.frequencyHz() * oldWeight + incoming.frequencyHz() * newWeight) / denominator,
                (old.secondaryReal() * oldWeight + incoming.secondaryReal() * newWeight) / denominator,
                (old.secondaryImag() * oldWeight + incoming.secondaryImag() * newWeight) / denominator,
                Math.max(old.improvementDb(), incoming.improvementDb()),
                Math.min(1000, oldWeight + newWeight),
                Math.max(old.updatedUtcMs(), incoming.updatedUtcMs()));
        recipes.put(aggregate.key(), aggregate);
        return aggregate;
    }

    public synchronized int size() { return recipes.size(); }

    private static boolean usable(VehicleCancellationRecipe recipe, double frequencyHz) {
        return recipe != null && recipe.secondaryPath().magnitude() >= 1.0e-5
                && Math.abs(recipe.frequencyHz() - frequencyHz)
                <= Math.max(1.5, Math.abs(frequencyHz) * 0.04);
    }

    private static String key(String routeKey, String modelId,
                              MechanicalFrequency.SourceType sourceType, double sourceBin) {
        return (routeKey == null ? "" : routeKey) + "|" + (modelId == null ? "" : modelId)
                + "|" + (sourceType == null ? MechanicalFrequency.SourceType.FIXED : sourceType).name()
                + "|" + String.format(java.util.Locale.US, "%.3f", sourceBin);
    }
}
