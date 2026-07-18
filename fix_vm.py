import re

with open('app/src/main/java/com/example/RgbControllerViewModel.kt', 'r') as f:
    content = f.read()

# Remove the SharedPreferences declarations
content = re.sub(r'private val statePrefs =.*?\n', '', content)
content = re.sub(r'private val ambiancePrefs =.*?\n', '', content)
content = re.sub(r'private val pacingPrefs =.*?\n', '', content)
content = re.sub(r'private val prefs =.*?\n', '', content)
content = re.sub(r'private val calibrationPrefs =.*?\n', '', content)
content = re.sub(r'private val cctCalibrationPrefs =.*?\n', '', content)
content = re.sub(r'val database = AppDatabase.getDatabase\(application\)\n\s+repository = RgbRepository\(database.rgbDao\(\)\)\n', '', content)

# Replace getters
content = content.replace('statePrefs.getBoolean', 'prefsRepo.getAppStatePrefBoolean')
content = content.replace('statePrefs.getInt', 'prefsRepo.getAppStatePrefInt')
content = content.replace('statePrefs.getFloat', 'prefsRepo.getAppStatePrefFloat')
content = content.replace('statePrefs.getLong', 'prefsRepo.getAppStatePrefLong')
content = content.replace('statePrefs.getString', 'prefsRepo.getAppStatePrefString')

content = content.replace('ambiancePrefs.getInt', 'prefsRepo.getAmbiancePrefInt')
content = content.replace('ambiancePrefs.getFloat', 'prefsRepo.getAmbiancePrefFloat')
content = content.replace('ambiancePrefs.getString', 'prefsRepo.getAmbiancePrefString')

content = content.replace('pacingPrefs.getInt', 'prefsRepo.getPacingPrefInt')
content = content.replace('calibrationPrefs.getInt', 'prefsRepo.getCalibrationDelayPrefInt')

# Replace edit().xyz().apply() with just prefsRepo.xyz()
# For single putX
content = re.sub(r'statePrefs\.edit\(\)\.put([A-Za-z]+)\(([^)]+)\)\.apply\(\)', r'prefsRepo.putAppStatePref\1(\2)', content)
content = re.sub(r'ambiancePrefs\.edit\(\)\.put([A-Za-z]+)\(([^)]+)\)\.apply\(\)', r'prefsRepo.putAmbiancePref\1(\2)', content)
content = re.sub(r'pacingPrefs\.edit\(\)\.put([A-Za-z]+)\(([^)]+)\)\.apply\(\)', r'prefsRepo.putPacingPref\1(\2)', content)
content = re.sub(r'calibrationPrefs\.edit\(\)\.put([A-Za-z]+)\(([^)]+)\)\.apply\(\)', r'prefsRepo.putCalibrationDelayPref\1(\2)', content)
content = re.sub(r'cctCalibrationPrefs\.edit\(\)\.put([A-Za-z]+)\(([^)]+)\)\.apply\(\)', r'prefsRepo.putCctCalibrationString(\2)', content)
content = re.sub(r'prefs\.edit\(\)\.put([A-Za-z]+)\(([^)]+)\)\.apply\(\)', r'prefsRepo.putProtocolOverrideString(\2)', content)

# For multiple putX in a chain? (e.g. statePrefs.edit().putInt().putString().apply())
# Wait, let's look for chained calls
content = re.sub(r'ambiancePrefs\.edit\(\)\.putFloat\("response_speed", value\)\.putString\("ambiance_preset", "Custom"\)\.apply\(\)', r'prefsRepo.putAmbiancePrefFloat("response_speed", value)\n        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")', content)
content = re.sub(r'ambiancePrefs\.edit\(\)\.putInt\("smoothness_ms", value\)\.putString\("ambiance_preset", "Custom"\)\.apply\(\)', r'prefsRepo.putAmbiancePrefInt("smoothness_ms", value)\n        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")', content)
content = re.sub(r'ambiancePrefs\.edit\(\)\.putFloat\("saturation_boost", value\)\.putString\("ambiance_preset", "Custom"\)\.apply\(\)', r'prefsRepo.putAmbiancePrefFloat("saturation_boost", value)\n        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")', content)
content = re.sub(r'ambiancePrefs\.edit\(\)\.putFloat\("brightness_compensation", value\)\.putString\("ambiance_preset", "Custom"\)\.apply\(\)', r'prefsRepo.putAmbiancePrefFloat("brightness_compensation", value)\n        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")', content)
content = re.sub(r'ambiancePrefs\.edit\(\)\.putInt\("update_rate_cap_fps", value\)\.putString\("ambiance_preset", "Custom"\)\.apply\(\)', r'prefsRepo.putAmbiancePrefInt("update_rate_cap_fps", value)\n        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")', content)
content = re.sub(r'ambiancePrefs\.edit\(\)\.putFloat\("scene_cut_sensitivity", value\)\.putString\("ambiance_preset", "Custom"\)\.apply\(\)', r'prefsRepo.putAmbiancePrefFloat("scene_cut_sensitivity", value)\n        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")', content)
content = re.sub(r'ambiancePrefs\.edit\(\)\.putFloat\("noise_deadband", value\)\.putString\("ambiance_preset", "Custom"\)\.apply\(\)', r'prefsRepo.putAmbiancePrefFloat("noise_deadband", value)\n        prefsRepo.putAmbiancePrefString("ambiance_preset", "Custom")', content)

# Clear calls
content = content.replace('calibrationPrefs.edit().clear().apply()', 'prefsRepo.clearCalibrationDelayPrefs()')
content = content.replace('cctCalibrationPrefs.edit().clear().apply()', 'prefsRepo.clearCctCalibrationPrefs()')
content = content.replace('pacingPrefs.edit().clear().apply()', 'prefsRepo.clearPacingPrefs()')

# Remove calls
content = re.sub(r'cctCalibrationPrefs\.edit\(\)\.remove\(([^)]+)\)\.apply\(\)', r'prefsRepo.removeCctCalibration(\1)', content)
content = re.sub(r'prefs\.edit\(\)\.remove\(([^)]+)\)\.apply\(\)', r'prefsRepo.removeProtocolOverride(\1)', content)

# all
content = content.replace('prefs.all', 'prefsRepo.getProtocolOverrideAll()')
content = content.replace('cctCalibrationPrefs.all', 'prefsRepo.getCctCalibrationAll()')

# Scenes
content = content.replace('loadScenesFromPrefs(application)', 'prefsRepo.loadScenes()')
content = content.replace('saveScenesToPrefs(getApplication(), current)', 'prefsRepo.saveScenes(current)')

with open('app/src/main/java/com/example/RgbControllerViewModel.kt', 'w') as f:
    f.write(content)

