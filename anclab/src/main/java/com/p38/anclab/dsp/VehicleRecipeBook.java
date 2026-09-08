package com.p38.anclab.dsp;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.VehicleCancellationRecipe;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Combines successful route-path observations instead of overwriting previous runs.
 * Room routes use recent actively verified observations and polar interpolation between
 * neighbouring learned frequencies. AutoController still validates every reused path.
 */
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
        if (isRoomRoute(routeKey)) {
            VehicleCancellationRecipe interpolated = interpolateRoomPath(routeKey, modelId, sourceType, frequencyHz);
            if (interpolated != null) return interpolated;
        }
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

    public synchronized VehicleCancellationRecipe add(VehicleCancellationRecipe incoming) {
        if (incoming == null || incoming.secondaryPath().magnitude() < 1.0e-5) return null;
        VehicleCancellationRecipe old = recipes.get(incoming.key());
        if (old == null) { recipes.put(incoming.key(), incoming); return incoming; }
        boolean room = isRoomRoute(incoming.routeKey());
        double oldWeight = Math.max(1, old.observations());
        double newWeight = Math.max(1, incoming.observations());
        if (room) {
            oldWeight = Math.min(3.0, oldWeight);
            if (incoming.updatedUtcMs() >= old.updatedUtcMs()) {
                double ageHours = (incoming.updatedUtcMs() - old.updatedUtcMs()) / 3_600_000.0;
                oldWeight *= Math.max(0.15, Math.exp(-ageHours / 12.0));
            } else newWeight *= 0.25;
        }
        double denominator = oldWeight + newWeight;
        VehicleCancellationRecipe aggregate = new VehicleCancellationRecipe(
                incoming.routeKey(), incoming.modelId(), incoming.sourceType(), incoming.sourceBin(),
                (old.frequencyHz() * oldWeight + incoming.frequencyHz() * newWeight) / denominator,
                (old.secondaryReal() * oldWeight + incoming.secondaryReal() * newWeight) / denominator,
                (old.secondaryImag() * oldWeight + incoming.secondaryImag() * newWeight) / denominator,
                Math.max(old.improvementDb(), incoming.improvementDb()),
                Math.min(room ? 64 : 1000, old.observations() + incoming.observations()),
                Math.max(old.updatedUtcMs(), incoming.updatedUtcMs()));
        recipes.put(aggregate.key(), aggregate);return aggregate;
    }

    public synchronized int size() { return recipes.size(); }

    private VehicleCancellationRecipe interpolateRoomPath(String routeKey,String modelId,
            MechanicalFrequency.SourceType sourceType,double frequencyHz) {
        VehicleCancellationRecipe lower=null,upper=null;
        double radius=Math.max(2.0,Math.min(5.0,Math.abs(frequencyHz)*0.03));
        for(VehicleCancellationRecipe recipe:recipes.values()){
            if(!recipe.routeKey().equals(routeKey)||!recipe.modelId().equals(modelId)
                    ||recipe.sourceType()!=sourceType||recipe.secondaryPath().magnitude()<1.0e-5
                    ||recipe.improvementDb()<1.0)continue;
            double delta=recipe.frequencyHz()-frequencyHz;if(Math.abs(delta)>radius)continue;
            if(delta<0&&(lower==null||recipe.frequencyHz()>lower.frequencyHz()))lower=recipe;
            else if(delta>0&&(upper==null||recipe.frequencyHz()<upper.frequencyHz()))upper=recipe;
        }
        if(lower==null||upper==null||upper.frequencyHz()-lower.frequencyHz()<0.05)return null;
        double t=clamp((frequencyHz-lower.frequencyHz())/(upper.frequencyHz()-lower.frequencyHz()),0,1);
        Complex a=lower.secondaryPath(),b=upper.secondaryPath();
        double magnitude=lerp(a.magnitude(),b.magnitude(),t);
        double phase=a.phaseRadians()+t*wrapPi(b.phaseRadians()-a.phaseRadians());
        Complex path=Complex.polar(magnitude,phase);if(path.magnitude()<1.0e-5)return null;
        double sourceBin=VehicleCancellationRecipe.quantizeSource(sourceType,frequencyHz,frequencyHz);
        return new VehicleCancellationRecipe(routeKey,modelId,sourceType,sourceBin,frequencyHz,
                path.re(),path.im(),lerp(lower.improvementDb(),upper.improvementDb(),t),
                Math.max(1,Math.min(lower.observations(),upper.observations())),
                Math.max(lower.updatedUtcMs(),upper.updatedUtcMs()));
    }

    private static boolean usable(VehicleCancellationRecipe recipe,double frequencyHz){
        return recipe!=null&&recipe.secondaryPath().magnitude()>=1.0e-5
                &&(!isRoomRoute(recipe.routeKey())||recipe.improvementDb()>=1.0)
                &&Math.abs(recipe.frequencyHz()-frequencyHz)<=Math.max(1.5,Math.abs(frequencyHz)*0.04);
    }
    private static boolean isRoomRoute(String routeKey){return routeKey!=null&&routeKey.startsWith("room|");}
    private static double lerp(double a,double b,double t){return a+(b-a)*t;}
    private static double clamp(double v,double lo,double hi){return Math.max(lo,Math.min(hi,v));}
    private static double wrapPi(double r){while(r>Math.PI)r-=2*Math.PI;while(r<-Math.PI)r+=2*Math.PI;return r;}
    private static String key(String routeKey,String modelId,MechanicalFrequency.SourceType sourceType,double sourceBin){
        return(routeKey==null?"":routeKey)+"|"+(modelId==null?"":modelId)+"|"
                +(sourceType==null?MechanicalFrequency.SourceType.FIXED:sourceType).name()+"|"
                +String.format(java.util.Locale.US,"%.3f",sourceBin);
    }
}
