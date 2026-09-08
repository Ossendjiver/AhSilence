from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def replace_once(path, old, new):
    p = ROOT / path
    text = p.read_text()
    if old not in text:
        raise RuntimeError(f"pattern not found in {path}: {old[:120]!r}")
    text = text.replace(old, new, 1)
    p.write_text(text)


def replace_all(path, old, new, minimum=1):
    p = ROOT / path
    text = p.read_text()
    count = text.count(old)
    if count < minimum:
        raise RuntimeError(f"expected >= {minimum} matches in {path}, found {count}: {old[:120]!r}")
    p.write_text(text.replace(old, new))


# ---- ProfileStore: fourth persisted Room profile + recipe/settings storage ----
P = "anclab/src/main/java/com/p38/anclab/profile/ProfileStore.java"
replace_once(P,
    '    public static final String PROFILE_HEADPHONES = "headphones";\n',
    '    public static final String PROFILE_HEADPHONES = "headphones";\n'
    '    public static final String PROFILE_ROOM = "room";\n')
replace_once(P,
    '        if (!storage.exists("profiles/headphones/profile.json")) storage.writeJson("profiles/headphones/profile.json", defaultHeadphones().toString());\n',
    '        if (!storage.exists("profiles/headphones/profile.json")) storage.writeJson("profiles/headphones/profile.json", defaultHeadphones().toString());\n'
    '        if (!storage.exists("profiles/room/profile.json")) storage.writeJson("profiles/room/profile.json", defaultRoom().toString());\n')
replace_once(P,
    '        for (String p : new String[]{PROFILE_P38, PROFILE_E46, PROFILE_HEADPHONES}) {',
    '        for (String p : new String[]{PROFILE_P38, PROFILE_E46, PROFILE_HEADPHONES, PROFILE_ROOM}) {')
replace_once(P,
    '                try { o.put("antiNoisePercent", 50);o.put("speculativeBroadband", false); } catch (Exception ignored) { }',
    '                try { o.put("antiNoisePercent", 50);o.put("speculativeBroadband", PROFILE_ROOM.equals(p)); } catch (Exception ignored) { }')
replace_once(P,
    '        AppLog.i(TAG, "P38, E46 and Headphones profile folders ready in " + AncStorage.DISPLAY_PATH);',
    '        AppLog.i(TAG, "P38, E46, Headphones and Room profile folders ready in " + AncStorage.DISPLAY_PATH);')
replace_once(P,
    'String p=normalizeProfile(profileId);List<MechanicalFrequency> result=new ArrayList<>();if(PROFILE_HEADPHONES.equals(p))return result;',
    'String p=normalizeProfile(profileId);List<MechanicalFrequency> result=new ArrayList<>();if(PROFILE_HEADPHONES.equals(p)||PROFILE_ROOM.equals(p))return result;')
replace_once(P,
    'private String normalizeProfile(String id) {if (PROFILE_P38.equalsIgnoreCase(id)) return PROFILE_P38;if (PROFILE_E46.equalsIgnoreCase(id)) return PROFILE_E46;return PROFILE_HEADPHONES;}',
    'private String normalizeProfile(String id) {if (PROFILE_P38.equalsIgnoreCase(id)) return PROFILE_P38;if (PROFILE_E46.equalsIgnoreCase(id)) return PROFILE_E46;if (PROFILE_ROOM.equalsIgnoreCase(id)) return PROFILE_ROOM;return PROFILE_HEADPHONES;}')
replace_once(P,
    '    private JSONObject defaultHeadphones() {JSONObject o=new JSONObject();try {o.put("id",PROFILE_HEADPHONES);',
    '    private JSONObject defaultRoom() {JSONObject o=new JSONObject();try {o.put("id",PROFILE_ROOM);o.put("name","Room");o.put("description","Stationary room ANC using the selected microphone as the directly observed error sensor");o.put("algorithm","persistent narrowband discovery + measured-error feedback-fxnlms");o.put("monitorLogEnabled",true);o.put("directErrorMicrophone",true);o.put("recipeFile","../../recipes/room.jsonl");o.put("latencyHistoryFile","latency_profiles.jsonl");o.put("speculativeBroadbandDefault",true);o.put("broadbandExcludesPredictableLanes",true);o.put("notes","The room microphone hears the acoustic result of ANC, so successful measured speaker-to-mic path corrections are learned and fed into the persistent recipe book more aggressively than vehicle mode.");} catch (Exception ignored) { }return o;}\n'
    '\n'
    '    private JSONObject defaultHeadphones() {JSONObject o=new JSONObject();try {o.put("id",PROFILE_HEADPHONES);')

# ---- VehicleNarrowbandBank: direct-feedback room learning cadence ----
N = "anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java"
replace_once(N,
    '    private static final int RECIPE_SUCCESS_UPDATES=12;\n    private static final long RECIPE_SAVE_INTERVAL_MS=30000;\n',
    '    private static final int RECIPE_SUCCESS_UPDATES=12;\n'
    '    private static final long RECIPE_SAVE_INTERVAL_MS=30000;\n'
    '    private static final int DIRECT_FEEDBACK_RECIPE_SUCCESS_UPDATES=4;\n'
    '    private static final long DIRECT_FEEDBACK_RECIPE_SAVE_INTERVAL_MS=8000;\n')
replace_once(N,
    '    private final double cancellationMaximumHz;\n',
    '    private final double cancellationMaximumHz;\n'
    '    private final boolean directFeedbackLearning;\n')
replace_once(N,
    '        this(models,telemetry,safeOutputCeiling,userScale,\n                FrequencyLanePolicy.DEFAULT_CANCELLATION_MINIMUM_HZ,\n                FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,"",List.of());\n',
    '        this(models,telemetry,safeOutputCeiling,userScale,\n'
    '                FrequencyLanePolicy.DEFAULT_CANCELLATION_MINIMUM_HZ,\n'
    '                FrequencyLanePolicy.MONITOR_MAXIMUM_HZ,"",List.of(),false);\n')
replace_once(N,
    '    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,\n                                 float safeOutputCeiling,float userScale,\n                                 double cancellationMinimumHz,double cancellationMaximumHz,\n                                 String routeKey,List<VehicleCancellationRecipe> recipes){\n        this.telemetry=telemetry;\n',
    '    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,\n'
    '                                 float safeOutputCeiling,float userScale,\n'
    '                                 double cancellationMinimumHz,double cancellationMaximumHz,\n'
    '                                 String routeKey,List<VehicleCancellationRecipe> recipes){\n'
    '        this(models,telemetry,safeOutputCeiling,userScale,cancellationMinimumHz,cancellationMaximumHz,routeKey,recipes,false);\n'
    '    }\n'
    '\n'
    '    public VehicleNarrowbandBank(List<MechanicalFrequency> models,VehicleTelemetryRuntime telemetry,\n'
    '                                 float safeOutputCeiling,float userScale,\n'
    '                                 double cancellationMinimumHz,double cancellationMaximumHz,\n'
    '                                 String routeKey,List<VehicleCancellationRecipe> recipes,boolean directFeedbackLearning){\n'
    '        this.telemetry=telemetry;\n'
    '        this.directFeedbackLearning=directFeedbackLearning;\n')
replace_all(N, 'RECIPE_SUCCESS_UPDATES', 'recipeSuccessUpdates()', minimum=2)
replace_all(N, 'RECIPE_SAVE_INTERVAL_MS', 'recipeSaveIntervalMs()', minimum=2)
# Undo declarations mangled by the broad substitutions and insert helpers.
replace_once(N, 'private static final int recipeSuccessUpdates()=12;', 'private static final int RECIPE_SUCCESS_UPDATES=12;')
replace_once(N, 'private static final long recipeSaveIntervalMs()=30000;', 'private static final long RECIPE_SAVE_INTERVAL_MS=30000;')
replace_once(N, 'private static final int DIRECT_FEEDBACK_recipeSuccessUpdates()=4;', 'private static final int DIRECT_FEEDBACK_RECIPE_SUCCESS_UPDATES=4;')
replace_once(N, 'private static final long DIRECT_FEEDBACK_recipeSaveIntervalMs()=8000;', 'private static final long DIRECT_FEEDBACK_RECIPE_SAVE_INTERVAL_MS=8000;')
replace_once(N,
    '    private void captureRecipe(String modelId,MechanicalFrequency.SourceType sourceType,double source,\n',
    '    private int recipeSuccessUpdates(){return directFeedbackLearning?DIRECT_FEEDBACK_RECIPE_SUCCESS_UPDATES:RECIPE_SUCCESS_UPDATES;}\n'
    '    private long recipeSaveIntervalMs(){return directFeedbackLearning?DIRECT_FEEDBACK_RECIPE_SAVE_INTERVAL_MS:RECIPE_SAVE_INTERVAL_MS;}\n'
    '\n'
    '    private void captureRecipe(String modelId,MechanicalFrequency.SourceType sourceType,double source,\n')
replace_once(N,
    '        if(path.magnitude()<1.0e-5||!Double.isFinite(improvement)||improvement<1.0)return;',
    '        double minimumImprovement=directFeedbackLearning?0.35:1.0;\n'
    '        if(path.magnitude()<1.0e-5||!Double.isFinite(improvement)||improvement<minimumImprovement)return;')
replace_once(N,
    '            status=String.format(Locale.US,"No cancellable GPS/OBD lane · auto-discovering stable 8–200 Hz lines · %d found · %d cancelling · %d monitor-only%s",\n                    discovered.size(),cancelling,monitorOnly,telem.isEmpty()?"":"\\n"+telem);',
    '            status=directFeedbackLearning\n'
    '                    ?String.format(Locale.US,"Room direct feedback · auto-discovering stable 8–200 Hz lines · %d found · %d cancelling · recipes update after %d successful observations",discovered.size(),cancelling,recipeSuccessUpdates())\n'
    '                    :String.format(Locale.US,"No cancellable GPS/OBD lane · auto-discovering stable 8–200 Hz lines · %d found · %d cancelling · %d monitor-only%s",discovered.size(),cancelling,monitorOnly,telem.isEmpty()?"":"\\n"+telem);')

# ---- AudioEngine: Room runtime combines fallback narrowband recipes + measured-error FxNLMS ----
A = "anclab/src/main/java/com/p38/anclab/audio/AudioEngine.java"
replace_once(A, '    private enum Mode { NONE, HEADPHONES, VEHICLE }', '    private enum Mode { NONE, HEADPHONES, VEHICLE, ROOM }')
replace_once(A,
    '        if(mode!=Mode.VEHICLE||!running.get())return;',
    '        if((mode!=Mode.VEHICLE&&mode!=Mode.ROOM)||!running.get())return;')
replace_once(A,
    '    public synchronized boolean startVehicleAnc(String profileId,boolean broadbandEnabled,List<MechanicalFrequency> mechanicalModels){if(calibration==null){lastError="No stored vehicle route calibration";return false;}return startInternal(Mode.VEHICLE,profileId,broadbandEnabled,mechanicalModels==null?List.of():mechanicalModels);}\n',
    '    public synchronized boolean startVehicleAnc(String profileId,boolean broadbandEnabled,List<MechanicalFrequency> mechanicalModels){if(calibration==null){lastError="No stored vehicle route calibration";return false;}return startInternal(Mode.VEHICLE,profileId,broadbandEnabled,mechanicalModels==null?List.of():mechanicalModels);}\n'
    '\n'
    '    @SuppressLint("MissingPermission")\n'
    '    public synchronized boolean startRoomAnc(boolean broadbandEnabled){if(calibration==null){lastError="No stored room route calibration";return false;}return startInternal(Mode.ROOM,ProfileStore.PROFILE_ROOM,broadbandEnabled,List.of());}\n')
old_start = '''            if(requested==Mode.HEADPHONES){
                if(routed==null||!isHeadphoneLike(routed.getType()))throw new IllegalStateException("ANC stopped: output is not routed to headphones");
                headphoneFx=new HeadphoneFeedforwardFxNlms(calibration.secondaryPath,calibration.delaySamples,calibration.safeOutputCeiling);headphoneFx.setAdaptationRate(calibration.delaySamples>2400?0.012f:0.035f);headphoneFx.setUserOutputScale(antiNoisePercent/100f);headphoneTones=new HeadphoneToneBank(calibration.safeOutputCeiling,antiNoisePercent/100f);stationaryNoiseProfiler.reset();lastHeadphoneToneRevision=-1L;
            }else{
                if(vehicleTelemetry==null)throw new IllegalStateException("Vehicle telemetry runtime is unavailable");
                vehicleTelemetry.activateProfile(profileId,mechanicalModels);
                vehicleNarrowband=new VehicleNarrowbandBank(mechanicalModels,vehicleTelemetry,
                        calibration.safeOutputCeiling,antiNoisePercent/100f,
                        calibration.minimumCancellationHz,calibration.maximumCancellationHz,
                        vehicleRecipeRouteKey(),profiles.loadCancellationRecipes(profileId));
                vehicleNarrowband.setBroadbandEnabled(broadbandEnabled);
                lastVehicleFrequencyRevision=vehicleNarrowband.frequencyRevision();lastVehicleExcluderUpdateMs=System.currentTimeMillis();
                if(broadbandEnabled){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);vehicleFx.setExcludedFrequencies(vehicleNarrowband.frequenciesHz());}else vehicleFx=null;
            }
'''
new_start = '''            if(requested==Mode.HEADPHONES){
                if(routed==null||!isHeadphoneLike(routed.getType()))throw new IllegalStateException("ANC stopped: output is not routed to headphones");
                headphoneFx=new HeadphoneFeedforwardFxNlms(calibration.secondaryPath,calibration.delaySamples,calibration.safeOutputCeiling);headphoneFx.setAdaptationRate(calibration.delaySamples>2400?0.012f:0.035f);headphoneFx.setUserOutputScale(antiNoisePercent/100f);headphoneTones=new HeadphoneToneBank(calibration.safeOutputCeiling,antiNoisePercent/100f);stationaryNoiseProfiler.reset();lastHeadphoneToneRevision=-1L;
            }else{
                VehicleTelemetryRuntime telemetryForBank=null;
                boolean room=requested==Mode.ROOM;
                if(!room){
                    if(vehicleTelemetry==null)throw new IllegalStateException("Vehicle telemetry runtime is unavailable");
                    vehicleTelemetry.activateProfile(profileId,mechanicalModels);telemetryForBank=vehicleTelemetry;
                }
                vehicleNarrowband=new VehicleNarrowbandBank(room?List.of():mechanicalModels,telemetryForBank,
                        calibration.safeOutputCeiling,antiNoisePercent/100f,
                        calibration.minimumCancellationHz,calibration.maximumCancellationHz,
                        vehicleRecipeRouteKey(),profiles.loadCancellationRecipes(profileId),room);
                vehicleNarrowband.setBroadbandEnabled(broadbandEnabled);
                lastVehicleFrequencyRevision=vehicleNarrowband.frequencyRevision();lastVehicleExcluderUpdateMs=System.currentTimeMillis();
                if(broadbandEnabled){vehicleFx=new FeedbackFxNlms(calibration.secondaryPath,calibration.delaySamples,128,calibration.safeOutputCeiling);configureVehicleFx(vehicleFx);vehicleFx.setExcludedFrequencies(vehicleNarrowband.frequenciesHz());}else vehicleFx=null;
                if(room)safetyStatus="Room direct-feedback loop ready · measured microphone residual feeds adaptive cancellation and recipes";
            }
'''
replace_once(A, old_start, new_start)
replace_all(A, 'else if(mode==Mode.VEHICLE){', 'else if(mode==Mode.VEHICLE||mode==Mode.ROOM){', minimum=2)
replace_once(A,
    '            String algorithm=mode==Mode.HEADPHONES?"HEADPHONE_TONES_PLUS_PREDICTIVE_FXNLMS_15_600":vehicleBroadbandEnabled?"VEHICLE_TELEMETRY_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"VEHICLE_TELEMETRY_NARROWBAND";',
    '            String algorithm=mode==Mode.HEADPHONES?"HEADPHONE_TONES_PLUS_PREDICTIVE_FXNLMS_15_600":mode==Mode.ROOM?(vehicleBroadbandEnabled?"ROOM_DIRECT_ERROR_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"ROOM_DIRECT_ERROR_NARROWBAND"):vehicleBroadbandEnabled?"VEHICLE_TELEMETRY_NARROWBAND_PLUS_MEASURED_ERROR_BROADBAND":"VEHICLE_TELEMETRY_NARROWBAND";')

# Room route calibration uses the same conservative acoustic ceiling as vehicle, but admits 15 Hz lanes.
replace_once(A,
    'c.safeOutputCeiling=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?(bestLag>2400?0.12f:0.18f):0.08f;c.minimumCancellationHz=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?15f:20f;c.maximumCancellationHz=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?600f:200f;',
    'c.safeOutputCeiling=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?(bestLag>2400?0.12f:0.18f):0.08f;c.minimumCancellationHz=(ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)||ProfileStore.PROFILE_ROOM.equals(activeProfile))?15f:20f;c.maximumCancellationHz=ProfileStore.PROFILE_HEADPHONES.equals(activeProfile)?600f:200f;')

# ---- MainActivity: expose Room profile and direct-feedback copy ----
M = "anclab/src/main/java/com/p38/anclab/MainActivity.java"
replace_once(M,
    '        TextView subtitle=text("v0.6 development · predictive headphones + telemetry/sensor-learning vehicle ANC",11,MUTED);',
    '        TextView subtitle=text("v0.6.2 development · headphones + vehicle + direct-feedback room ANC",11,MUTED);')
replace_once(M,
    'profileSpinner=new Spinner(this);ArrayAdapter<String> pa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"P38 car","E46 car","Headphones"});pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profileSpinner.setAdapter(pa);profileSpinner.setSelection(ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?2:ProfileStore.PROFILE_E46.equals(currentProfile)?1:0);',
    'profileSpinner=new Spinner(this);ArrayAdapter<String> pa=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,new String[]{"P38 car","E46 car","Room","Headphones"});pa.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);profileSpinner.setAdapter(pa);profileSpinner.setSelection(ProfileStore.PROFILE_HEADPHONES.equals(currentProfile)?3:ProfileStore.PROFILE_ROOM.equals(currentProfile)?2:ProfileStore.PROFILE_E46.equals(currentProfile)?1:0);')
replace_once(M,
    'profileSpinner.setOnItemSelectedListener(new SimpleItemListener(position->{String next=position==2?ProfileStore.PROFILE_HEADPHONES:position==1?ProfileStore.PROFILE_E46:ProfileStore.PROFILE_P38;',
    'profileSpinner.setOnItemSelectedListener(new SimpleItemListener(position->{String next=position==3?ProfileStore.PROFILE_HEADPHONES:position==2?ProfileStore.PROFILE_ROOM:position==1?ProfileStore.PROFILE_E46:ProfileStore.PROFILE_P38;')
replace_once(M,
    'meter.addView(text("Reference/error RMS is shown in the live status. Vehicle ANC uses this same selected microphone to verify narrowband cancellation and to learn small corrections to telemetry-predicted mechanical orders.",12,TEXT),topSpaced());',
    'meter.addView(text("Reference/error RMS is shown in the live status. Vehicle mode verifies narrowband cancellation here; Room mode treats this microphone as the directly observed error sensor and repeatedly feeds successful path corrections into its recipe book.",12,TEXT),topSpaced());')
replace_once(M,
    'bench.addView(text("Headphones: place the IEMs beside the phone microphone only for calibration. Vehicle: selected cabin microphone should be at the listening position and the selected car audio output active. Calibration also records the current Android media-volume gain for later ANC volume compensation.",11,MUTED),topSpaced());',
    'bench.addView(text("Headphones: place the IEMs beside the phone microphone only for calibration. Vehicle: put the selected cabin microphone at the listening position. Room: keep the error microphone fixed at the listening position where it will hear the ANC result. Calibration also records current media-volume gain for ANC transport compensation.",11,MUTED),topSpaced());')
replace_once(M,
    '    private boolean isHeadphones(){return ProfileStore.PROFILE_HEADPHONES.equals(currentProfile);}\n',
    '    private boolean isHeadphones(){return ProfileStore.PROFILE_HEADPHONES.equals(currentProfile);}\n'
    '    private boolean isRoom(){return ProfileStore.PROFILE_ROOM.equals(currentProfile);}\n')
old_ui = '''    private void updateProfileUi(){
        boolean h=isHeadphones();
        if(broadbandCheck!=null)broadbandCheck.setVisibility(h?View.GONE:View.VISIBLE);
        if(laneCountText!=null)laneCountText.setVisibility(h?View.GONE:View.VISIBLE);
        if(laneListText!=null)laneListText.setVisibility(h?View.GONE:View.VISIBLE);
        if(broadbandText!=null){broadbandText.setVisibility(h?View.GONE:View.VISIBLE);if(!h)broadbandText.setText("Experimental residual 15–600 Hz feedback FxNLMS. It is limited to 25% of the selected ANC allowance, dynamically excludes active narrow lanes, latches off if its output becomes ceiling-pinned, and now also requires measurable residual benefit.");}
        if(profileWarning!=null){if(h){profileWarning.setText("HEADPHONES · predictive 15–600 Hz feed-forward FxNLMS · stable-frequency discovery learning active");profileWarning.setTextColor(CYAN);}else{profileWarning.setText((ProfileStore.PROFILE_E46.equals(currentProfile)?"E46":"P38")+" · telemetry-tracked orders when available; automatic stable-line discovery otherwise.");profileWarning.setTextColor(AMBER);}}
        if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":h?"START HEADPHONE ANC":"START VEHICLE ANC");
        if(graphButton!=null)graphButton.setEnabled(true);
        if(positionCheck!=null){positionCheck.setChecked(false);positionCheck.setText(h?"Calibration only: IEMs are beside the phone microphone and are not being worn":"Vehicle is stationary/safe and the selected microphone/output are positioned for a low-level route probe");}
    }
'''
new_ui = '''    private void updateProfileUi(){
        boolean h=isHeadphones(),room=isRoom();
        if(broadbandCheck!=null){broadbandCheck.setVisibility(h?View.GONE:View.VISIBLE);broadbandCheck.setText(room?"MEASURED BROADBAND ROOM ANC":"SPECULATIVE BROADBAND ANC");}
        if(laneCountText!=null)laneCountText.setVisibility(h?View.GONE:View.VISIBLE);
        if(laneListText!=null)laneListText.setVisibility(h?View.GONE:View.VISIBLE);
        if(broadbandText!=null){broadbandText.setVisibility(h?View.GONE:View.VISIBLE);if(!h)broadbandText.setText(room?"Room mode uses the fixed microphone as a directly observed error sensor. Stable 8–200 Hz lanes are iteratively verified and learned into recipes; measured-error 15–600 Hz FxNLMS handles predictable residual outside owned lanes.":"Experimental residual 15–600 Hz feedback FxNLMS. It is limited to 25% of the selected ANC allowance, dynamically excludes active narrow lanes, latches off if its output becomes ceiling-pinned, and requires measurable residual benefit.");}
        if(profileWarning!=null){if(h){profileWarning.setText("HEADPHONES · predictive 15–600 Hz feed-forward FxNLMS · stable-frequency discovery learning active");profileWarning.setTextColor(CYAN);}else if(room){profileWarning.setText("ROOM · direct error-microphone feedback · persistent tone discovery · strengthened iterative recipe learning");profileWarning.setTextColor(CYAN);}else{profileWarning.setText((ProfileStore.PROFILE_E46.equals(currentProfile)?"E46":"P38")+" · telemetry-tracked orders when available; automatic stable-line discovery otherwise.");profileWarning.setTextColor(AMBER);}}
        if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":h?"START HEADPHONE ANC":room?"START ROOM ANC":"START VEHICLE ANC");
        if(graphButton!=null)graphButton.setEnabled(true);
        if(positionCheck!=null){positionCheck.setChecked(false);positionCheck.setText(h?"Calibration only: IEMs are beside the phone microphone and are not being worn":room?"Room microphone is fixed at the listening position and the selected output is ready for a low-level route probe":"Vehicle is stationary/safe and the selected microphone/output are positioned for a low-level route probe");}
    }
'''
replace_once(M, old_ui, new_ui)
replace_once(M,
    '        boolean ok=isHeadphones()?rt.audio.startHeadphoneAnc():rt.audio.startVehicleAnc(currentProfile,broadbandCheck!=null&&broadbandCheck.isChecked(),profiles.loadMechanicalFrequencies(currentProfile));',
    '        boolean ok=isHeadphones()?rt.audio.startHeadphoneAnc():isRoom()?rt.audio.startRoomAnc(broadbandCheck!=null&&broadbandCheck.isChecked()):rt.audio.startVehicleAnc(currentProfile,broadbandCheck!=null&&broadbandCheck.isChecked(),profiles.loadMechanicalFrequencies(currentProfile));')
replace_once(M,
    '    private String profileName(){return isHeadphones()?"Headphones":ProfileStore.PROFILE_E46.equals(currentProfile)?"E46":"P38";}',
    '    private String profileName(){return isHeadphones()?"Headphones":isRoom()?"Room":ProfileStore.PROFILE_E46.equals(currentProfile)?"E46":"P38";}')
replace_once(M,
    'if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":isHeadphones()?"START HEADPHONE ANC":"START VEHICLE ANC");',
    'if(startButton!=null)startButton.setText(rt.audio.isRunning()?"STOP ANC":isHeadphones()?"START HEADPHONE ANC":isRoom()?"START ROOM ANC":"START VEHICLE ANC");')

# ---- Media service: cold-start Room like other calibrated profiles ----
S = "anclab/src/main/java/com/p38/anclab/AncMediaService.java"
replace_once(S,
    '        boolean ok=ProfileStore.PROFILE_HEADPHONES.equals(profile)\n                ?audio.startHeadphoneAnc()\n                :audio.startVehicleAnc(profile,store.loadSpeculativeBroadband(profile),store.loadMechanicalFrequencies(profile));',
    '        boolean ok=ProfileStore.PROFILE_HEADPHONES.equals(profile)\n'
    '                ?audio.startHeadphoneAnc()\n'
    '                :ProfileStore.PROFILE_ROOM.equals(profile)\n'
    '                ?audio.startRoomAnc(store.loadSpeculativeBroadband(profile))\n'
    '                :audio.startVehicleAnc(profile,store.loadSpeculativeBroadband(profile),store.loadMechanicalFrequencies(profile));')
replace_once(S,
    '        if(ProfileStore.PROFILE_P38.equals(p))return "P38";if(ProfileStore.PROFILE_E46.equals(p))return "E46";return "Headphones";',
    '        if(ProfileStore.PROFILE_P38.equals(p))return "P38";if(ProfileStore.PROFILE_E46.equals(p))return "E46";if(ProfileStore.PROFILE_ROOM.equals(p))return "Room";return "Headphones";')

# ---- Version + CI ----
G = "anclab/build.gradle.kts"
replace_once(G, '        versionCode = 18\n        versionName = "0.6.1-adaptive-discovery-dev"', '        versionCode = 19\n        versionName = "0.6.2-room-direct-feedback-dev"')

W = ".github/workflows/build-anclab.yml"
replace_once(W,
    '      - feature/multisensor-anc-v060\n',
    '      - feature/multisensor-anc-v060\n      - feature/room-anc-v062\n')
replace_once(W, '          name: ANC-Lab-v0.6-multisensor-dev-debug', '          name: ANC-Lab-v0.6.2-room-dev-debug')

print("Room ANC v0.6.2 patch applied")
