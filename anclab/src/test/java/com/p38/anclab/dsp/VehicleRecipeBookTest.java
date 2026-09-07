package com.p38.anclab.dsp;

import com.p38.anclab.profile.MechanicalFrequency;
import com.p38.anclab.profile.VehicleCancellationRecipe;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class VehicleRecipeBookTest {
    @Test public void observationsAreAddedInsteadOfOverwritingPriorRuns() {
        VehicleCancellationRecipe first = recipe("route-a", 80, 34.2, 1.0, 0.0, 2);
        VehicleCancellationRecipe second = recipe("route-a", 80, 34.4, 3.0, 2.0, 1);
        VehicleRecipeBook book = new VehicleRecipeBook(List.of(first));

        book.add(second);
        VehicleCancellationRecipe combined = book.find("route-a", "prop-1",
                MechanicalFrequency.SourceType.BEST_SPEED, 80, 34.3);

        assertNotNull(combined);
        assertEquals(1, book.size());
        assertEquals(3, combined.observations());
        assertEquals(5.0 / 3.0, combined.secondaryReal(), 1.0e-9);
        assertEquals(2.0 / 3.0, combined.secondaryImag(), 1.0e-9);
    }

    @Test public void recipesDoNotCrossCalibrationRoutes() {
        VehicleRecipeBook book = new VehicleRecipeBook(List.of(
                recipe("route-a", 80, 34.2, 1.0, 0.0, 1)));

        assertNull(book.find("route-b", "prop-1",
                MechanicalFrequency.SourceType.BEST_SPEED, 80, 34.2));
    }

    private static VehicleCancellationRecipe recipe(String route, double speed, double frequency,
                                                      double real, double imaginary, int observations) {
        return new VehicleCancellationRecipe(route, "prop-1",
                MechanicalFrequency.SourceType.BEST_SPEED,
                VehicleCancellationRecipe.quantizeSource(MechanicalFrequency.SourceType.BEST_SPEED,
                        speed, frequency), frequency, real, imaginary, 4.0, observations, 1);
    }
}
