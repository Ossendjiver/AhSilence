package replay;

import com.p38.anclab.dsp.FeedbackFxNlms;
import com.p38.anclab.dsp.HeadphoneBandLimiter;
import com.p38.anclab.dsp.PredictableFrequencyExcluder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.File;
import java.util.Locale;

/** Closed-loop synthetic-path replay for the experimental vehicle broadband controller. */
public final class P38BroadbandClosedLoopReplay {
    private static final int SR = 48000;
    private static final int DELAY = 2400;
    private static final float SAFE_CEILING = 0.08f;
    private static final double[] P38_EXCLUSIONS = {20.45, 34.42, 61.5};
    private static final float[] SCALES = {1.0f, 0.50f, 0.25f, 0.10f};

    private record Result(String section, String mode, float scale, double baselineRms, double residualRms,
                          double totalDeltaDb, double eligibleBaselineRms, double eligibleResidualRms,
                          double eligibleDeltaDb, double outputRms, double peakOutput,
                          double ceilingOccupancy, int safetyTrips, boolean latchedOff) { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("usage: WAV startSec endSec");
        double start = Double.parseDouble(args[1]);
        double end = Double.parseDouble(args[2]);
        float[] source = readSection(new File(args[0]), start, end);
        String section = String.format(Locale.US, "%.0f-%.0f", start, end);
        for (float scale : SCALES) {
            print(run(section, "integrated-exclusions", scale, source, P38_EXCLUSIONS));
            print(run(section, "broadband-only", scale, source, new double[0]));
        }
    }

    private static Result run(String section, String mode, float scale, float[] source, double[] exclusions) {
        FeedbackFxNlms fx = new FeedbackFxNlms(new float[]{1f}, DELAY, 128, SAFE_CEILING);
        fx.setUserOutputScale(scale);
        fx.setRouteGainCompensation(1f);
        fx.setExcludedFrequencies(exclusions);

        float[] routeDelay = new float[DELAY];
        int routePos = 0;
        HeadphoneBandLimiter baselineBand = new HeadphoneBandLimiter(SR, true);
        PredictableFrequencyExcluder baselineEligible = new PredictableFrequencyExcluder(SR, exclusions);
        PredictableFrequencyExcluder residualEligible = new PredictableFrequencyExcluder(SR, exclusions);

        int measureFrom = source.length / 4;
        double baseSq = 0, residualSq = 0, eligibleBaseSq = 0, eligibleResidualSq = 0, outSq = 0;
        long count = 0;
        double peakOut = 0;

        for (int i = 0; i < source.length; i++) {
            float speakerReturn = routeDelay[routePos];
            float measured = source[i] + speakerReturn;
            float output = fx.process(measured);
            routeDelay[routePos] = output;
            if (++routePos == routeDelay.length) routePos = 0;

            float base = baselineBand.process(source[i]);
            float residual = fx.diagnosticMeasuredResidual();
            float eligibleBase = baselineEligible.process(base);
            float eligibleResidual = residualEligible.process(residual);
            peakOut = Math.max(peakOut, Math.abs(output));

            if (i >= measureFrom) {
                baseSq += base * (double) base;
                residualSq += residual * (double) residual;
                eligibleBaseSq += eligibleBase * (double) eligibleBase;
                eligibleResidualSq += eligibleResidual * (double) eligibleResidual;
                outSq += output * (double) output;
                count++;
            }
        }

        double baseRms = Math.sqrt(baseSq / Math.max(1, count));
        double residualRms = Math.sqrt(residualSq / Math.max(1, count));
        double eligibleBaseRms = Math.sqrt(eligibleBaseSq / Math.max(1, count));
        double eligibleResidualRms = Math.sqrt(eligibleResidualSq / Math.max(1, count));
        double outputRms = Math.sqrt(outSq / Math.max(1, count));
        return new Result(section, mode, scale, baseRms, residualRms, dbRatio(residualRms, baseRms),
                eligibleBaseRms, eligibleResidualRms, dbRatio(eligibleResidualRms, eligibleBaseRms),
                outputRms, peakOut, fx.ceilingOccupancy(), fx.safetyTrips(), fx.isLatchedOff());
    }

    private static double dbRatio(double residual, double baseline) {
        return 20.0 * Math.log10(Math.max(residual, 1e-12) / Math.max(baseline, 1e-12));
    }

    private static void print(Result r) {
        System.out.printf(Locale.US,
                "BROADBAND section=%s mode=%s scale=%.2f total_delta_db=%.3f eligible_delta_db=%.3f " +
                "baseline_rms=%.6f residual_rms=%.6f eligible_baseline_rms=%.6f eligible_residual_rms=%.6f " +
                "output_rms=%.6f peak_output=%.6f rail_occ=%.4f trips=%d latched=%s%n",
                r.section, r.mode, r.scale, r.totalDeltaDb, r.eligibleDeltaDb, r.baselineRms, r.residualRms,
                r.eligibleBaselineRms, r.eligibleResidualRms, r.outputRms, r.peakOutput,
                r.ceilingOccupancy, r.safetyTrips, r.latchedOff);
    }

    private static float[] readSection(File file, double start, double end) throws Exception {
        try (AudioInputStream in = AudioSystem.getAudioInputStream(file)) {
            AudioFormat f = in.getFormat();
            if (f.getSampleRate() != SR || f.getChannels() != 1 || f.getSampleSizeInBits() != 16 || f.isBigEndian())
                throw new IllegalArgumentException("unexpected WAV format: " + f);
            long bytes = (long) (start * SR) * 2;
            while (bytes > 0) { long n = in.skip(bytes); if (n <= 0) throw new IllegalStateException("seek failed"); bytes -= n; }
            byte[] raw = in.readNBytes((int) ((end - start) * SR) * 2);
            float[] out = new float[raw.length / 2];
            for (int i = 0; i < out.length; i++) {
                int lo = raw[2 * i] & 255, hi = raw[2 * i + 1];
                out[i] = (short) ((hi << 8) | lo) / 32768f;
            }
            return out;
        }
    }
}
