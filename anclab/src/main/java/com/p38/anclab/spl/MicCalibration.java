package com.p38.anclab.spl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses REW/miniDSP text calibration files and keeps an explicit SPL offset. */
public final class MicCalibration {
    private static final Pattern SENSITIVITY = Pattern.compile(
            "(?i)(?:sens\\s*factor\\s*=|sensitivity\\s+)\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*dB(?:FS)?");
    private static final Pattern SERIAL = Pattern.compile("(?i)SERNO\\s*:\\s*([A-Za-z0-9_-]+)");

    public record Point(double frequencyHz, double correctionDb) { }

    private final String name;
    private final String serial;
    private final double sensitivityDbFsAt94Db;
    private final List<Point> points;

    public MicCalibration(String name, String serial, double sensitivityDbFsAt94Db, List<Point> points) {
        this.name = name;
        this.serial = serial;
        this.sensitivityDbFsAt94Db = sensitivityDbFsAt94Db;
        this.points = List.copyOf(points);
    }

    public static MicCalibration parse(InputStream stream, String name) throws IOException {
        String serial = "";
        double sensitivity = Double.NaN;
        List<Point> points = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher sens = SENSITIVITY.matcher(line);
                if (sens.find()) sensitivity = Double.parseDouble(sens.group(1));
                Matcher serialMatcher = SERIAL.matcher(line);
                if (serialMatcher.find()) serial = serialMatcher.group(1);
                String trimmed = line.trim().replace(',', ' ');
                if (trimmed.isEmpty() || !(Character.isDigit(trimmed.charAt(0)) || trimmed.charAt(0) == '.')) continue;
                String[] values = trimmed.split("\\s+");
                if (values.length >= 2) {
                    try { points.add(new Point(Double.parseDouble(values[0]), Double.parseDouble(values[1]))); }
                    catch (NumberFormatException ignored) { }
                }
            }
        }
        if (points.size() < 2) throw new IOException("Calibration file contains fewer than two frequency points");
        return new MicCalibration(name, serial, sensitivity, points);
    }

    /** Assumes the file's sensitivity is the dBFS response to a 94 dB SPL calibrator. */
    public double provisionalSplOffsetDb() {
        return Double.isFinite(sensitivityDbFsAt94Db) ? 94.0 - sensitivityDbFsAt94Db : Double.NaN;
    }

    public double correctionAt(double frequencyHz) {
        if (frequencyHz <= points.get(0).frequencyHz()) return points.get(0).correctionDb();
        for (int i = 1; i < points.size(); i++) {
            Point high = points.get(i);
            if (frequencyHz <= high.frequencyHz()) {
                Point low = points.get(i - 1);
                double fraction = (frequencyHz - low.frequencyHz()) / (high.frequencyHz() - low.frequencyHz());
                return low.correctionDb() + fraction * (high.correctionDb() - low.correctionDb());
            }
        }
        return points.get(points.size() - 1).correctionDb();
    }

    public String description() {
        String id = serial.isEmpty() ? name : name + " · " + serial;
        return String.format(Locale.US, "%s · %d points%s", id, points.size(),
                Double.isFinite(sensitivityDbFsAt94Db) ? String.format(Locale.US, " · Sens %.2f dBFS", sensitivityDbFsAt94Db) : "");
    }

    public boolean hasSensitivity() { return Double.isFinite(sensitivityDbFsAt94Db); }
}
