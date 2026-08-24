import groovy.transform.Field

@Field static final Map HOUR_OPTIONS = (0..23).collectEntries { h ->
    String id = String.format('%02d', h)
    String label
    if (h == 0)       { label = '12 midnight' }
    else if (h == 12) { label = '12 noon' }
    else if (h < 12)  { label = String.format('%02d am', h) }
    else              { label = String.format('%02d pm', h - 12) }
    [(id): label]
}

@Field static final Map MINUTE_OPTIONS = (0..59).collectEntries { m ->
    [(String.format('%02d', m)): String.format('%02d', m)]
}

@Field static final Map COUNTDOWN_OPTIONS = ['10': '10min', '20': '20min', '30': '30min']

@Field static final Map RATE_OPTIONS = (1..10).collectEntries { [(it as String): (it as String)] }

metadata {
    definition (name: 'RD-250ZG Dimmer', namespace: 'repenic', author: 'Repenic Ltd.') {
        capability 'Actuator'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Switch'
        capability 'SwitchLevel'
        capability 'Light'
        capability 'EnergyMeter'
        capability 'PowerMeter'
        capability 'VoltageMeasurement'
        capability 'CurrentMeter'

        fingerprint profileId: '0104', deviceId: '0101', inClusters: '0000,0003,0004,0005,0006,0008,000A,0702,0B04,0204,E003', outClusters: '0019', manufacturer: 'Repenic Ltd.', model: 'RD-250ZG'
    }

    preferences {
        input name: 'start_up_on_off', type: 'enum', title: 'Start Up On Off', options: ['0': 'off', '1': 'on', '2': 'toggle', '255': 'previous value'], defaultValue: '0'
        input name: 'boost', type: 'enum', title: 'Boost', options: ['0': 'Off', '1': 'On'], defaultValue: '1'
        input name: 'onLevel2', type: 'number', title: 'Start level (%)', description: 'When the value is 0, it is closed. Start level must be less than maximum brightness.', range: '0..100', defaultValue: 0
        input name: 'minimumBrightness', type: 'number', title: 'Set minimum Brightness (%)', description: 'minimum brightness must be less than maximum brightness.', range: '1..100', defaultValue: 1
        input name: 'maximumBrightness', type: 'number', title: 'Set maximum Brightness (%)', description: 'maximum brightness must be greater than minimum brightness.', range: '1..100', defaultValue: 100
        input name: 'outEdge', type: 'enum', title: 'Dimming mode', options: ['0': 'Leading edge', '1': 'Trailing edge'], defaultValue: '1'
        input name: 'defaultMoveRate', type: 'enum', title: 'Default Move Rate', options: RATE_OPTIONS, defaultValue: '1'
        input name: 'childLock', type: 'bool', title: 'Child lock', defaultValue: false

        // Co-sleeping mode
        input name: 'sleepOnOff', type: 'bool', title: 'Co-sleeping mode', defaultValue: false
        input name: 'sleepHour', type: 'enum', title: 'Co-sleeping mode - Start time (hour)', options: HOUR_OPTIONS, defaultValue: '10'
        input name: 'sleepMinute', type: 'enum', title: 'Co-sleeping mode - Start time (minute)', options: MINUTE_OPTIONS, defaultValue: '00'
        input name: 'sleepCountdown', type: 'enum', title: 'Co-sleeping mode - Duration', options: COUNTDOWN_OPTIONS, defaultValue: '30'

        // Sunrise mode
        input name: 'wakeupOnOff', type: 'bool', title: 'Sunrise mode', defaultValue: false
        input name: 'wakeupHour', type: 'enum', title: 'Sunrise mode - Start time (hour)', options: HOUR_OPTIONS, defaultValue: '10'
        input name: 'wakeupMinute', type: 'enum', title: 'Sunrise mode - Start time (minute)', options: MINUTE_OPTIONS, defaultValue: '00'
        input name: 'wakeupBrightness', type: 'number', title: 'Sunrise mode - Brightness (%)', range: '1..100', defaultValue: 100
        input name: 'wakeupCountdown', type: 'enum', title: 'Sunrise mode - Duration', options: COUNTDOWN_OPTIONS, defaultValue: '30'

        // Moonlight mode
        input name: 'nightOnOff', type: 'bool', title: 'Moonlight mode', defaultValue: false
        input name: 'nightHour', type: 'enum', title: 'Moonlight mode - Start time hour (Today)', options: HOUR_OPTIONS, defaultValue: '00'
        input name: 'nightMinute', type: 'enum', title: 'Moonlight mode - Start time minute (Today)', options: MINUTE_OPTIONS, defaultValue: '00'
        input name: 'nightEndHour', type: 'enum', title: 'Moonlight mode - End time hour (Tomorrow)', options: HOUR_OPTIONS, defaultValue: '06'
        input name: 'nightEndMinute', type: 'enum', title: 'Moonlight mode - End time minute (Tomorrow)', options: MINUTE_OPTIONS, defaultValue: '00'
        input name: 'nightBrightness', type: 'number', title: 'Moonlight mode - Brightness (%)', range: '1..100', defaultValue: 10
    }
}


def installed() {
    log.info 'installed()'
    state.onOff = 0
    state.meterMult = 1
    state.meterDiv = 1
    state.acMult = 1
    state.acDiv = 1
    sendEvent(name: 'switch', value: 'off')
    sendEvent(name: 'level', value: 0)
    sendHubCommand(new hubitat.device.HubMultiAction(syncTimeCmd(), hubitat.device.Protocol.ZIGBEE))
}

def updated() {
    log.info 'updated()'
    List cmds = [
        setupStartUpOnOff(),
        setupBoost(),
        setupOnLevel(),
        setupMinBrightness(),
        setupMaxBrightness(),
        setupOutEdge(),
        setupDefaultMoveRate(),
        setupChildLock(),
        setupSleepPattern(),
        setupWakeupPattern(),
        setupNightPattern(),
        syncTimeCmd(),
    ].findAll { it != null }.flatten()
    if (cmds) {
        sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
    }
}


def parse(String description) {
    def descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap?.clusterInt == null) { return }
    switch (descMap.clusterInt) {
        case 0x0006:
            switch (descMap.attrInt) {
                case 0x0000: handleOnOffReport(hexToLong(descMap.value)); break
                case 0x4003: handleStartUpOnOffReport(hexToLong(descMap.value)); break
                default: break
            }
            break
        case 0x0008:
            switch (descMap.attrInt) {
                case 0x0000:  handleLevelReport(hexToLong(descMap.value)); break
                case 0x0002:  handleMinBrightnessReport(hexToLong(descMap.value)); break
                case 0x0003:  handleMaxBrightnessReport(hexToLong(descMap.value)); break
                case 0x0011:  handleOnLevelReport(hexToLong(descMap.value)); break
                case 0x0014:  handleDefaultMoveRateReport(hexToLong(descMap.value)); break
                case 0xA004:  handleBoostReport(hexToLong(descMap.value)); break
                case 0xB000:  handleOutEdgeReport(hexToLong(descMap.value)); break
                default: break
            }
            break
        case 0x000A:
            if (descMap.attrInt == 0x0000) {
                log.info 'time attribute received, re-syncing time'
                sendHubCommand(new hubitat.device.HubMultiAction(syncTimeCmd(), hubitat.device.Protocol.ZIGBEE))
            }
            break
        case 0x0204:
            if (descMap.attrInt == 0x0001) {
                handleChildLockReport(hexToLong(descMap.value))
            }
            break
        case 0x0702:
            allAttrs(descMap).each { m ->
                handleMeteringReport(m.attrInt, hexToLong(m.value))
            }
            break
        case 0x0B04:
            allAttrs(descMap).each { m ->
                handleElectricalReport(m.attrInt, hexToLong(m.value))
            }
            break
        case 0xE003:
            handleWorkModeReport(descMap, description)
            break
        default:
            break
    }
}


private handleOnOffReport(Long raw) {
    if (raw == null) { return }
    state.onOff = raw.intValue()
    String value = raw == 0 ? 'off' : 'on'
    String desc = "${device.displayName} is ${value}"
    sendEvent(name: 'switch', value: value, descriptionText: desc)
}

private handleLevelReport(Long raw) {
    if (raw == null) { return }
    Integer level = Math.round(raw / 2.54) as Integer
    if (state.onOff == 0) { level = 0 }
    String desc = "${device.displayName} level is ${level}%"
    sendEvent(name: 'level', value: level, unit: '%', descriptionText: desc)
}


private handleStartUpOnOffReport(Long raw) {
    if (raw == null) { return }
    Map map = [0: '0', 1: '1', 2: '2', 3: '255', 255: '255']
    String value = map[raw.intValue()]
    if (value != null) { device.updateSetting('start_up_on_off', [value: value, type: 'enum']) }
}

private handleBoostReport(Long raw) {
    if (raw == null) { return }
    Map map = [0: '0', 1: '1']
    String value = map[raw.intValue()]
    if (value != null) { device.updateSetting('boost', [value: value, type: 'enum']) }
}

private handleOutEdgeReport(Long raw) {
    if (raw == null) { return }
    Map map = [0: '0', 1: '1']
    String value = map[raw.intValue()]
    if (value != null) { device.updateSetting('outEdge', [value: value, type: 'enum']) }
}

private handleDefaultMoveRateReport(Long raw) {
    if (raw == null) { return }
    device.updateSetting('defaultMoveRate', [value: raw.toString(), type: 'enum'])
}

private handleOnLevelReport(Long raw) {
    if (raw == null) { return }
    Integer value = (raw == 0xFF) ? 0 : Math.min(Math.round(raw / 2.54) as Integer, 100)
    device.updateSetting('onLevel2', [value: value, type: 'number'])
}

private handleMinBrightnessReport(Long raw) {
    if (raw == null) { return }
    device.updateSetting('minimumBrightness', [value: raw.intValue(), type: 'number'])
}

private handleMaxBrightnessReport(Long raw) {
    if (raw == null) { return }
    device.updateSetting('maximumBrightness', [value: raw.intValue(), type: 'number'])
}

private handleChildLockReport(Long raw) {
    if (raw == null) { return }
    Boolean locked = raw != 0
    device.updateSetting('childLock', [value: locked, type: 'bool'])
}


private handleWorkModeReport(descMap, String description) {
    if (descMap.attrInt in [0, 1, 2]) {
        def data = zigbee.parse(description)?.data
        if (data?.size() != 10) {
            return
        }
        switch (descMap.attrInt) {
            case 0: // sleepPattern: [onOff, hour, minute, -, countdown, -]
                device.updateSetting('sleepOnOff', [value: data[4] != 0, type: 'bool'])
                device.updateSetting('sleepHour', [value: String.format('%02d', data[5]), type: 'enum'])
                device.updateSetting('sleepMinute', [value: String.format('%02d', data[6]), type: 'enum'])
                device.updateSetting('sleepCountdown', [value: data[8].toString(), type: 'enum'])
                break
            case 1: // wakeUpPattern: [onOff, hour, minute, brightness, countdown, -]
                device.updateSetting('wakeupOnOff', [value: data[4] != 0, type: 'bool'])
                device.updateSetting('wakeupHour', [value: String.format('%02d', data[5]), type: 'enum'])
                device.updateSetting('wakeupMinute', [value: String.format('%02d', data[6]), type: 'enum'])
                device.updateSetting('wakeupBrightness', [value: Math.max(1, Math.round(data[7] / 2.54) as Integer), type: 'number'])
                device.updateSetting('wakeupCountdown', [value: data[8].toString(), type: 'enum'])
                break
            case 2: // nightPattern: [onOff, hour, minute, brightness, endHour, endMinute]
                device.updateSetting('nightOnOff', [value: data[4] != 0, type: 'bool'])
                device.updateSetting('nightHour', [value: String.format('%02d', data[5]), type: 'enum'])
                device.updateSetting('nightMinute', [value: String.format('%02d', data[6]), type: 'enum'])
                device.updateSetting('nightBrightness', [value: Math.max(1, Math.round(data[7] / 2.54) as Integer), type: 'number'])
                device.updateSetting('nightEndHour', [value: String.format('%02d', data[8]), type: 'enum'])
                device.updateSetting('nightEndMinute', [value: String.format('%02d', data[9]), type: 'enum'])
                break
        }
    }
}


private handleMeteringReport(Integer attrId, Long raw) {
    switch (attrId) {
        case 0x0301:
            state.meterMult = raw.intValue()
            break
        case 0x0302:
            state.meterDiv = raw.intValue()
            break
        case 0x0000:
            Double mult = (state.meterMult ?: 1) as Double
            Double div = (state.meterDiv ?: 1) as Double
            Double energy = roundTo(raw * mult / div, 4)
            String desc = "${device.displayName} energy is ${energy} kWh"
            sendEvent(name: 'kwh', value: energy, unit: 'kWh', descriptionText: desc)
            break
    }
}

private handleElectricalReport(Integer attrId, Long raw) {
    switch (attrId) {
        case 0x0604:
            state.acMult = raw.intValue()
            break
        case 0x0605:
            state.acDiv = raw.intValue()
            break
        case 0x050B: // activePower
            Long signed = raw > 32767 ? raw - 65536 : raw
            Double multP = (state.acMult ?: 1) as Double
            Double divP = (state.acDiv ?: 1) as Double
            Double power = roundTo(signed * multP / divP, 2)
            String powerDesc = "${device.displayName} power is ${power} W"
            sendEvent(name: 'power', value: power, unit: 'W', descriptionText: powerDesc)
            break
        case 0x0505: // rmsVoltage
            Double multV = (state.acMult ?: 1) as Double
            Double divV = (state.acDiv ?: 1) as Double
            Double voltage = roundTo(raw * multV / divV, 2)
            String voltageDesc = "${device.displayName} voltage is ${voltage} V"
            sendEvent(name: 'voltage', value: voltage, unit: 'V', descriptionText: voltageDesc)
            break
        case 0x0508: // rmsCurrent
            Double multC = (state.acMult ?: 1) as Double
            Double divC = (state.acDiv ?: 1) as Double
            Double current = roundTo(raw * multC / divC, 3)
            String currentDesc = "${device.displayName} current is ${current} A"
            sendEvent(name: 'current', value: current, unit: 'A', descriptionText: currentDesc)
            break
    }
}


def on() {
    state.onOff = 1
    String desc = "${device.displayName} is on"
    sendEvent(name: 'switch', value: 'on', descriptionText: desc)
    zigbee.on()
}

def off() {
    state.onOff = 0
    String desc = "${device.displayName} is off"
    sendEvent(name: 'level', value: 0)
    sendEvent(name: 'switch', value: 'off', descriptionText: desc)
    zigbee.off()
}

def setLevel(BigDecimal level, BigDecimal duration = 1) {
    Integer lvl = Math.max(1, Math.min(level as Integer, 100))
    state.onOff = 1
    String desc = "${device.displayName} level is ${lvl}%"
    sendEvent(name: 'level', value: lvl, unit: '%', descriptionText: desc)
    sendEvent(name: 'switch', value: 'on')
    zigbee.setLevel(lvl, Math.round((duration ?: 0) * 10) as Integer)
}


private setupStartUpOnOff() {
    def v = settings.start_up_on_off
    if (v == null) { return null }
    zigbee.writeAttribute(0x0006, 0x4003, 0x30, v as Integer)
}

private setupBoost() {
    def v = settings.boost
    if (v == null) { return null }
    zigbee.writeAttribute(0x0008, 0xA004, 0x20, v as Integer)
}

private setupOnLevel() {
    def v = settings.onLevel2
    if (v == null) { return null }
    Integer level = v as Integer
    Integer raw = (level <= 0) ? 0xFF : Math.min(Math.round(level * 2.54) as Integer, 0xFE)
    zigbee.writeAttribute(0x0008, 0x0011, 0x20, raw)
}

private setupMinBrightness() {
    def v = settings.minimumBrightness
    if (v == null) { return null }
    Integer raw = Math.min(v as Integer, 99)
    zigbee.writeAttribute(0x0008, 0xA000, 0x20, raw)
}

private setupMaxBrightness() {
    def v = settings.maximumBrightness
    if (v == null) { return null }
    Integer raw = Math.max(1, Math.min(v as Integer, 100))
    zigbee.writeAttribute(0x0008, 0xA003, 0x20, raw)
}

private setupOutEdge() {
    def v = settings.outEdge
    if (v == null) { return null }
    zigbee.writeAttribute(0x0008, 0xB000, 0x30, v as Integer)
}

private setupDefaultMoveRate() {
    def v = settings.defaultMoveRate
    if (v == null) { return null }
    zigbee.writeAttribute(0x0008, 0x0014, 0x20, v as Integer)
}

private setupChildLock() {
    if (settings.childLock == null) { return null }
    // 0x0204 keypadLockout (enum8): 0 = noLockout, 1 = level1Lockout
    zigbee.writeAttribute(0x0204, 0x0001, 0x30, settings.childLock ? 1 : 0)
}

private setupSleepPattern() {
    if (settings.sleepHour == null || settings.sleepMinute == null || settings.sleepCountdown == null) { return null }
    List<Integer> pattern = [
        settings.sleepOnOff ? 1 : 0,
        settings.sleepHour as Integer,
        settings.sleepMinute as Integer,
        0,                              // brightness
        settings.sleepCountdown as Integer,
        0,
    ]
    log.debug "setupSleepPattern: ${pattern}"
    patternCmd(0x00, pattern)
}

private setupWakeupPattern() {
    if (settings.wakeupHour == null || settings.wakeupMinute == null || settings.wakeupBrightness == null || settings.wakeupCountdown == null) { return null }
    Integer brightness = Math.min(Math.round((settings.wakeupBrightness as Integer) * 2.54) as Integer, 0xFE)
    List<Integer> pattern = [
        settings.wakeupOnOff ? 1 : 0,
        settings.wakeupHour as Integer,
        settings.wakeupMinute as Integer,
        brightness,
        settings.wakeupCountdown as Integer,
        0,
    ]
    patternCmd(0x01, pattern)
}

private setupNightPattern() {
    if (settings.nightHour == null || settings.nightMinute == null || settings.nightBrightness == null ||
        settings.nightEndHour == null || settings.nightEndMinute == null) { return null }
    Integer brightness = Math.min(Math.round((settings.nightBrightness as Integer) * 2.54) as Integer, 0xFE)
    List<Integer> pattern = [
        settings.nightOnOff ? 1 : 0,
        settings.nightHour as Integer,
        settings.nightMinute as Integer,
        brightness,
        settings.nightEndHour as Integer,
        settings.nightEndMinute as Integer,
    ]
    patternCmd(0x02, pattern)
}

private patternCmd(Integer cmdId, List<Integer> bytes) {
    String payload = bytes.collect { String.format('%02x', it & 0xFF) }.join()
    "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0xE003 ${String.format('%02x', cmdId)} {${payload}} {}"
}


private syncTimeCmd() {
    TimeZone tz = location.timeZone ?: TimeZone.getDefault()
    Date now = new Date()
    Integer offsetSeconds = tz.getOffset(now.time) / 1000 as Integer
    Long epoch = (now.time / 1000 as Long) + offsetSeconds
    String timePayload = (0..<4).collect { i -> String.format('%02x', (epoch >> (8 * i)) & 0xFF) }.join()
    String offsetPayload = (3..0).collect { i -> String.format('%02x', (offsetSeconds >> (8 * i)) & 0xFF) }.join()
    log.debug "syncTimeCmd: ${epoch},${timePayload}  ${offsetSeconds},${offsetPayload}"
    [
        "he wattr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x000A 0x0000 0xE2 {${timePayload}} {}",
        "he wattr 0x${device.deviceNetworkId} 0x${device.endpointId} 0x000A 0x0002 0x23 {${offsetPayload}} {}",
    ]
}


def configure() {
    log.info 'configure()'
    List cmds = []
    // bindings
    [0x0006, 0x0008, 0x000A, 0x0702, 0x0B04, 0x0204, 0xE003].each { c ->
        cmds << "zdo bind 0x${device.deviceNetworkId} 0x${device.endpointId} 1 0x${zigbee.convertToHexString(c, 4)} {${device.zigbeeId}} {}"
    }
    // reporting
    cmds += [
        zigbee.configureReporting(0x0006, 0x0000, 0x10, 0, 300, null, [:], 0),          // onOff
        zigbee.configureReporting(0x0008, 0x0000, 0x20, 0, 300, 1, [:], 0),             // currentLevel
        zigbee.configureReporting(0x0702, 0x0000, 0x25, 1, 300, 1, [:], 0),             // currentSummationDelivered
        zigbee.configureReporting(0x0B04, 0x0505, 0x21, 10, 300, 1, [:], 0),            // rmsVoltage
        zigbee.configureReporting(0x0B04, 0x0508, 0x21, 10, 300, 1, [:], 0),            // rmsCurrent
        zigbee.configureReporting(0x0B04, 0x050B, 0x29, 10, 300, 1, [:], 0),            // activePower
        zigbee.configureReporting(0x0204, 0x0001, 0x30, 0, 300, null, [:], 0),          // keypadLockout
    ]
    cmds += syncTimeCmd()
    // 读取计量换算系数与当前状态
    cmds += readAllAttributes()
    return delayBetween(cmds.flatten() as List, 250)
}

def refresh() {
    log.info 'refresh()'
    List cmds = readAllAttributes()
    cmds += syncTimeCmd()
    return delayBetween(cmds.flatten() as List, 200)
}

private readAllAttributes() {
    [
        zigbee.readAttribute(0x0702, [0x0301, 0x0302]),                       // multiplier(769) / divisor(770), uint24
        zigbee.readAttribute(0x0B04, [0x0604, 0x0605]),                       // acPowerMultiplier / acPowerDivisor
        zigbee.readAttribute(0x0006, [0x0000, 0x4003]),
        zigbee.readAttribute(0x0008, [0x0000, 0x0002, 0x0003, 0x0011, 0x0014, 0xA004, 0xB000]),
        zigbee.readAttribute(0x0702, [0x0000]),
        zigbee.readAttribute(0x0B04, [0x0505, 0x0508, 0x050B]),
        zigbee.readAttribute(0x0204, [0x0001]),
        zigbee.readAttribute(0xE003, [0x0000, 0x0001, 0x0002]),
    ]
}

private List allAttrs(Map descMap) {
    List attrs = [[attrInt: descMap.attrInt, value: descMap.value]]
    (descMap.additionalAttrs ?: []).each { e ->
        Integer attrInt = e.attrInt != null ? e.attrInt : (e.attrId != null ? Integer.parseInt(e.attrId.toString(), 16) : null)
        attrs << [attrInt: attrInt, value: e.value]
    }
    attrs.findAll { it.attrInt != null && it.value != null }
}

private hexToLong(String hex) {
    if (hex == null) { return null }
    Long.parseLong(hex, 16)
}

private double roundTo(double v, int digits = 2) {
    double f = Math.pow(10, digits)
    Math.round(v * f) / f
}
