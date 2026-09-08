from pathlib import Path

bank = Path('anclab/src/main/java/com/p38/anclab/dsp/VehicleNarrowbandBank.java')
s = bank.read_text()
old = '''                DiscoveredLane replace=null;\n                if(discovered.size()>=fallbackLaneLimit())replace=findReplaceableLane(candidate,now);\n                if(discovered.size()>=fallbackLaneLimit()&&replace==null)continue;'''
new = '''                DiscoveredLane replace=null;\n                int controllerSlots=discoveredControllerSlotCount();\n                boolean candidateCancellable=FrequencyLanePolicy.cancellable(candidate.frequencyHz(),\n                        cancellationMinimumHz,cancellationMaximumHz);\n                if(candidateCancellable&&controllerSlots>=fallbackLaneLimit())replace=findReplaceableLane(candidate,now);\n                if(candidateCancellable&&controllerSlots>=fallbackLaneLimit()&&replace==null)continue;'''
if old in s:
    s = s.replace(old, new, 1)
elif 'int controllerSlots=discoveredControllerSlotCount();' not in s:
    raise SystemExit('admission capacity block not found')

marker = '''    private int fallbackLaneLimit(){\n        return broadbandEnabled?MAX_DISCOVERED_LANES_BROADBAND:MAX_DISCOVERED_LANES_NARROWBAND_ONLY;\n    }\n'''
helper = marker + '''\n    /** Monitor-only discoveries stay visible but do not consume cancellation-controller capacity. */\n    private int discoveredControllerSlotCount(){\n        int count=0;\n        for(DiscoveredLane lane:discovered)if(lane.cancellable)count++;\n        return count;\n    }\n'''
if 'private int discoveredControllerSlotCount()' not in s:
    if marker not in s: raise SystemExit('fallbackLaneLimit marker not found')
    s = s.replace(marker, helper, 1)
bank.write_text(s)

build = Path('anclab/build.gradle.kts')
b = build.read_text().replace('versionCode = 15', 'versionCode = 16').replace('versionName = "0.5.9-rebuild"', 'versionName = "0.5.9.1-rebuild"')
build.write_text(b)

test = Path('anclab/src/test/java/com/p38/anclab/dsp/VehicleMonitorOnlyCapacityTest.java')
test.write_text('''package com.p38.anclab.dsp;\n\nimport org.junit.Test;\n\nimport java.lang.reflect.Constructor;\nimport java.lang.reflect.Field;\nimport java.lang.reflect.Method;\nimport java.util.List;\n\nimport static org.junit.Assert.assertEquals;\n\npublic class VehicleMonitorOnlyCapacityTest {\n    @Test\n    public void monitorOnlyDiscoveryDoesNotConsumeControllerSlot() throws Exception {\n        VehicleNarrowbandBank bank = new VehicleNarrowbandBank(List.of(), null, 0.08f, 0.5f, 20.0, 200.0, "test", List.of());\n        Field discoveredField = VehicleNarrowbandBank.class.getDeclaredField("discovered");\n        discoveredField.setAccessible(true);\n        @SuppressWarnings("unchecked") List<Object> discovered = (List<Object>) discoveredField.get(bank);\n        Class<?> laneClass = Class.forName("com.p38.anclab.dsp.VehicleNarrowbandBank$DiscoveredLane");\n        Constructor<?> ctor = laneClass.getDeclaredConstructor(String.class, double.class, long.class);\n        ctor.setAccessible(true);\n        Field cancellable = laneClass.getDeclaredField("cancellable");\n        cancellable.setAccessible(true);\n        for (int i = 0; i < 7; i++) {\n            Object monitor = ctor.newInstance("monitor-" + i, 10.0 + i, 0L);\n            cancellable.setBoolean(monitor, false);\n            discovered.add(monitor);\n        }\n        for (int i = 0; i < 3; i++) {\n            Object controller = ctor.newInstance("controller-" + i, 30.0 + i * 2.0, 0L);\n            cancellable.setBoolean(controller, true);\n            discovered.add(controller);\n        }\n        Method count = VehicleNarrowbandBank.class.getDeclaredMethod("discoveredControllerSlotCount");\n        count.setAccessible(true);\n        assertEquals(3, ((Integer) count.invoke(bank)).intValue());\n    }\n}\n''')

ch = Path('CHANGELOG.md')
c = ch.read_text()
entry = '''## [0.5.9.1-rebuild] — 2026-09-08\n\n### Fixed\n- Keep 8–20 Hz microphone discoveries monitor-only without allowing them to consume the bounded fallback cancellation-controller bank.\n- Capacity and replacement decisions now count only cancellable discovered lanes; monitoring, logging, broadband exclusion visibility and existing output ceilings remain unchanged.\n\n### Validation basis\n- On the real P38 20–200 second WAV section with the same synthetic unity-gain 50 ms secondary path, true 33–36 Hz RUNNING time increased from 1.2 s to 17.5 s cold and from 0.0 s to 50.1 s warm in the isolated A/B.\n- Total true RUNNING lane-time increased from 351.9 s to 397.7 s cold and from 433.2 s to 495.5 s warm. These are controller-orchestration replay figures, not measured in-cabin attenuation.\n\n'''
if '## [0.5.9.1-rebuild]' not in c:
    c = c.replace('## [0.5.9-rebuild]', entry + '## [0.5.9-rebuild]', 1)
ch.write_text(c)
