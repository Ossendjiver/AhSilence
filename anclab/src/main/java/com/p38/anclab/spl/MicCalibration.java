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

import org.json.JSONArray;
import org.json.JSONObject;

/** Parses REW/miniDSP text calibration files and keeps an explicit SPL offset. */
public final class MicCalibration {
    private static final Pattern SENSITIVITY = Pattern.compile(
            "(?i)(?:sens\\s*factor\\s*=|sensitivity\\s+)\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*dB(?:FS)?");
    private static final Pattern SERIAL = Pattern.compile("(?i)SERNO\\s*:\\s*([A-Za-z0-9_-]+)");
    private static final Pattern ANALOG_GAIN = Pattern.compile("(?i)AGain\\s*=\\s*([-+]?\\d+(?:\\.\\d+)?)\\s*dB");

    public record Point(double frequencyHz, double correctionDb) { }

    private final String name;
    private final String serial;
    private final double sensitivityDbFsAt94Db;
    private final double analogGainDb;
    private final double explicitSplOffsetDb;
    private final List<Point> points;

    public MicCalibration(String name, String serial, double sensitivityDbFsAt94Db, List<Point> points) {
        this(name,serial,sensitivityDbFsAt94Db,Double.NaN,Double.NaN,points);
    }

    private MicCalibration(String name,String serial,double sensitivityDbFsAt94Db,double analogGainDb,double explicitSplOffsetDb,List<Point> points) {
        this.name=name==null?"Microphone":name;this.serial=serial==null?"":serial;
        this.sensitivityDbFsAt94Db=sensitivityDbFsAt94Db;this.analogGainDb=analogGainDb;this.explicitSplOffsetDb=explicitSplOffsetDb;
        this.points=List.copyOf(points==null?List.of():points);
    }

    public static MicCalibration benchmarked(String name,double splOffsetDb){return new MicCalibration(name,"",Double.NaN,Double.NaN,splOffsetDb,List.of());}
    public static MicCalibration combine(MicCalibration response,MicCalibration spl){
        if(response==null)return spl;if(spl==null)return response;
        return new MicCalibration(response.name,response.serial,response.sensitivityDbFsAt94Db,response.analogGainDb,
                spl.explicitSplOffsetDb,response.points);
    }
    public MicCalibration responseOnly(){return new MicCalibration(name,serial,sensitivityDbFsAt94Db,analogGainDb,Double.NaN,points);}
    public MicCalibration splOnly(){return new MicCalibration(name,"",Double.NaN,Double.NaN,explicitSplOffsetDb,List.of());}

    public static MicCalibration parse(InputStream stream, String name) throws IOException {
        String serial = "";
        double sensitivity = Double.NaN;
        double analogGain = Double.NaN;
        List<Point> points = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher sens = SENSITIVITY.matcher(line);
                if (sens.find()) sensitivity = Double.parseDouble(sens.group(1));
                Matcher serialMatcher = SERIAL.matcher(line);
                if (serialMatcher.find()) serial = serialMatcher.group(1);
                Matcher gainMatcher = ANALOG_GAIN.matcher(line);
                if (gainMatcher.find()) analogGain = Double.parseDouble(gainMatcher.group(1));
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
        return new MicCalibration(name, serial, sensitivity, analogGain, Double.NaN, points);
    }

    /** Absolute SPL is intentionally accepted only from an explicit acoustic benchmark. */
    public double provisionalSplOffsetDb() {
        return explicitSplOffsetDb;
    }

    public double correctionAt(double frequencyHz) {
        if(points.isEmpty())return 0.0;
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
                Double.isFinite(sensitivityDbFsAt94Db) ? String.format(Locale.US, " · factory sensitivity %.2f dB", sensitivityDbFsAt94Db) : "");
    }

    public boolean hasSensitivity() { return Double.isFinite(sensitivityDbFsAt94Db); }
    public boolean hasFrequencyResponse(){return !points.isEmpty();}
    public boolean hasSplOffset(){return Double.isFinite(provisionalSplOffsetDb());}
    public String name(){return name;}
    public String serial(){return serial;}
    public List<Point> points(){return points;}

    public JSONObject toJson(){JSONObject o=new JSONObject();try{o.put("format","anc-lab-mic-calibration-v2");o.put("name",name);o.put("serial",serial);if(Double.isFinite(sensitivityDbFsAt94Db))o.put("sensitivityFactorDb",sensitivityDbFsAt94Db);if(Double.isFinite(analogGainDb))o.put("analogGainDb",analogGainDb);if(Double.isFinite(explicitSplOffsetDb))o.put("splOffsetDb",explicitSplOffsetDb);JSONArray a=new JSONArray();for(Point p:points){JSONObject x=new JSONObject();x.put("frequencyHz",p.frequencyHz());x.put("correctionDb",p.correctionDb());a.put(x);}o.put("points",a);}catch(Exception ignored){}return o;}
    public static MicCalibration fromJson(String raw){if(raw==null||raw.isBlank())return null;try{JSONObject o=new JSONObject(raw);List<Point> p=new ArrayList<>();JSONArray a=o.optJSONArray("points");if(a!=null)for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null)p.add(new Point(x.optDouble("frequencyHz"),x.optDouble("correctionDb")));}double sensitivity=o.has("sensitivityFactorDb")?o.optDouble("sensitivityFactorDb",Double.NaN):o.optDouble("sensitivityDbFsAt94Db",Double.NaN);return new MicCalibration(o.optString("name","Microphone"),o.optString("serial",""),sensitivity,o.optDouble("analogGainDb",Double.NaN),o.optDouble("splOffsetDb",Double.NaN),p);}catch(Exception e){return null;}}
}
