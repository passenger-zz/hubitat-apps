/*
    CTC GSi12 SmartGrid Tibber App V2 (Recovery-Aware Planner)
    -----------------------------------------------------
    Controls CTC SmartGrid modes via two virtual/physical switches (SmartA, SmartB)
    based on Tibber 15-minute prices and optional solar production.

    • OVER_CAPACITY is reserved for strategic thermal precharge before a selected HIGH_PRICE block.
      A precharge is planned only when the preceding electricity is cheap enough and the
      price spread to the HIGH block is large enough. Negative price alone is not a trigger.

    • Mapping to CTC SmartGrid inputs:
        OVER_CAPACITY → both A & B ON
        LOW_PRICE     → B ON, A OFF
        HIGH_PRICE    → both OFF
        BLOCKED       → A ON, B OFF

    Author: Magnus Forslund
    V2.2.6 continuation fix: temperature-triggered replanning is deferred while an already-planned HIGH_PRICE block is active, so a valid peak cut is not abandoned mid-block (2026-09-22)
    V2.2.5 precharge timing fix retained: OVER_CAPACITY may be placed in an earlier cheaper slot before HIGH instead of being forced immediately adjacent to HIGH.
    V2.2.4 telemetry retained: corrected Pulse aggregation + estimated house-load telemetry
    V2.2.3 planner fix retained: each priceListAll value is applied to the following 15-minute wall-clock slot.
    Negative prices alone no longer trigger OVER_CAPACITY.
*/

import groovy.transform.Field
import java.math.RoundingMode
definition(
    name: "CTC GSi12 Dynamic SmartGrid Tibber App V2.2.6",
    namespace: "CTC",
    author: "Magnus Forslund",
    description: "Recovery-aware SmartGrid controller using Tibber 15-min prices",
    category: "CTC",
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "Settings", install: true, uninstall: true) {

        section("<h2>Tibber Price Source</h2>") {
            input "tibber", "capability.sensor", title: "Select Tibber device", submitOnChange: true, required: true
        }

        
        section("<h2>Tibber Pulse Live Consumption (optional)</h2>") {
            input "tibberLive", "capability.powerMeter", title: "Select Tibber Live Consumption device (Pulse)", submitOnChange: true, required: false
            input "enablePulsePassiveLogging", "bool", title: "Enable passive Pulse logging (no control impact)", defaultValue: true, submitOnChange: true
            input "pulseLogIntervalSec", "number", title: "Passive Pulse summary interval (seconds)", defaultValue: 60, range: "30..600"
            paragraph "V2.2.6 retains the V2.2.4 whole-house estimate: solar production + Tibber net grid power. This is telemetry only and does not affect SmartGrid control."
            input "enableSelfBalance", "bool", title: "Enable self-balancing (learn rebound behavior) [experimental]", defaultValue: false, submitOnChange: true
        }

        section("<h2>Solar Power Input (optional)</h2>") {
            input "solarPower", "capability.energyMeter", title: "Solar power meter", submitOnChange: true, required: false
            input type: "number", name: "SolarHeatingThreshold", title: "Solar heating threshold (kW)", defaultValue: 3
        }

        section("<h2>Peak Cut Planner V2 (15-min schedule)</h2>") {
            paragraph "V2 selects variable-length HIGH_PRICE blocks and evaluates the cost of the recovery period after each block. The goal is to leave HIGH_PRICE when electricity is cheap enough for the heat pump to catch up."
            input name: "tibberPriceListAttr", type: "enum", title: "Tibber attribute that contains the 15-minute price list", required: true, submitOnChange: true,
                  options: (tibber ? tibber.supportedAttributes*.name?.sort() : [])
            input name: "minHighContMin", type: "number", title: "Minimum HIGH_PRICE block (minutes)", defaultValue: 30, range: "15..360"
            input name: "maxHighContMin", type: "number", title: "Hard maximum HIGH_PRICE block (minutes)", defaultValue: 120, range: "15..360"
            input name: "maxHighTotalMin", type: "number", title: "Max total HIGH_PRICE per day (minutes)", defaultValue: 120, range: "0..720"

            input name: "recoveryLookaheadQuarters", type: "number", title: "Recovery look-ahead after a peak (quarters)", defaultValue: 8, range: "1..16"
            input name: "recoveryMinQuarters", type: "number", title: "Recovery window used for scoring (quarters)", defaultValue: 2, range: "1..8"
            input name: "recoveryPriceToleranceOre", type: "number", title: "Recovery exit tolerance above cheapest recovery slot (öre)", defaultValue: 15, range: "0..500"
            input name: "recoveryWeight", type: "decimal", title: "Recovery cost weight (0.0-2.0)", defaultValue: 0.70, range: "0..2"
            input name: "minEconomicSpreadOre", type: "number", title: "Minimum HIGH-to-recovery price spread (öre)", defaultValue: 20, range: "0..1000"
            input name: "extendHighToRecovery", type: "bool", title: "Extend HIGH_PRICE to a cheap recovery landing zone", defaultValue: true, submitOnChange: true

            input name: "useDynamicBuffer", type: "bool", title: "Temperature-limit maximum HIGH_PRICE duration", defaultValue: false, submitOnChange: true
            input name: "outsideTempSensor", type: "capability.temperatureMeasurement", title: "Outdoor temperature sensor", required: false
            input name: "coldTempC", type: "decimal", title: "Cold reference temperature (°C)", defaultValue: -20, range: "-50..20"
            input name: "warmTempC", type: "decimal", title: "Warm reference temperature (°C)", defaultValue: 5, range: "-30..30"
            input name: "bufferMinAtCold", type: "number", title: "Max HIGH_PRICE minutes at cold reference", defaultValue: 30, range: "15..360"
            input name: "bufferMinAtWarm", type: "number", title: "Max HIGH_PRICE minutes at warm reference", defaultValue: 120, range: "15..360"
            input name: "bufferCurve", type: "decimal", title: "Temperature curve (1.0 = linear, >1 more conservative when cold)", defaultValue: 1.0, range: "0.2..5"
            input name: "tempRecalcDeltaC", type: "decimal", title: "Recalculate plan when outdoor temp changes by (°C)", defaultValue: 1.0, range: "0..10"
        }

        section("<h2>Strategic Precharge / Thermal Charging</h2>") {
            paragraph "OVER_CAPACITY is used only as a planned thermal precharge before a selected HIGH_PRICE block. V2.2.5 may move the precharge earlier within a limited look-back window when that is cheaper than charging in the quarter immediately before HIGH. Negative prices alone do not trigger OVER_CAPACITY."
            input name:"preheatEnable",          type:"bool",   title:"Enable OVER_CAPACITY precharge", defaultValue:true, submitOnChange:true
            input name:"preheatWindowQuarters", type:"number", title:"Maximum precharge duration (quarters)", defaultValue:2, range:"1..16"
            input name:"preheatLookbackQuarters", type:"number", title:"How far before HIGH precharge may be placed (quarters)", defaultValue:4, range:"1..16"
            input name:"preheatSpikeDelta",     type:"number", title:"Minimum HIGH-to-precharge price spread (öre)", defaultValue:50, range:"0..10000"
        }

        section("<h2>Plan Logging</h2>") {
            input name: "planLogEnable", type: "bool", title: "Log plan summary when recalculated", defaultValue: true
            input name: "planLogVerbose", type: "bool", title: "Verbose plan logging (prints every 15-min slot)", defaultValue: false
        }

        section("<h2>SmartGrid Switch Outputs</h2>") {
            input "smartA", "capability.switch", title: "SmartA switch", required: true
            input "smartB", "capability.switch", title: "SmartB switch", required: true
        }

        section("<h2>Logging</h2>") {
            input name: "logEnable", type: "bool", title: "Enable debug logging?", defaultValue: true
        }
    }
}

    // Extract price in minor units (öre) from a parsed price-list entry.
    // Accepts common schemas:
    //  - {startsAt:"...", priceMinor:156}
    //  - {startsAt:"...", price:156}
    //  - {startsAt:"...", total:1.56}  (if driver sends major units)
    private Integer _extractPriceMinorFromEntry(def e) {
        if (e == null) return null
        try {
            if (e instanceof Map) {
                if (e.containsKey("priceMinor") && e.priceMinor != null) return (e.priceMinor as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP) as Integer
                if (e.containsKey("price") && e.price != null) return (e.price as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP) as Integer
                if (e.containsKey("total") && e.total != null) {
                    // If total is in currency units, convert to minor by *100 (best effort)
                    BigDecimal v = (e.total as BigDecimal)
                    // Heuristic: if value looks like < 20, assume major units (SEK) and convert
                    if (v < 20) v = v.multiply(100)
                    return v.setScale(0, BigDecimal.ROUND_HALF_UP) as Integer
                }
                // fall back: first numeric value
                def num = e.values().find { it instanceof Number }
                if (num != null) return (num as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP) as Integer
            }
            if (e instanceof Number) return (e as BigDecimal).setScale(0, BigDecimal.ROUND_HALF_UP) as Integer
        } catch (ignored) {}
        return null
    }

/* ===================== Lifecycle ===================== */

def installed() {
    if (logEnable) log.debug "Installed"
    updated()
}

def updated() {
    if (logEnable) log.debug "Updated"
    unschedule()
    initialize()
}

def initialize() {
        if (!state.currentSmartGridState) state.currentSmartGridState = "HIGH_PRICE"
    if (!state.lastChangeEpoch) state.lastChangeEpoch = now()

    if (logEnable) log.debug("Initialize")

    // Start a fresh passive Pulse aggregation window after app re-initialization.
    // Historical HIGH-slot accounting is intentionally kept across initialize().
    state.pulseAggSum = 0G
    state.pulseAggCount = 0
    state.pulseAggMin = null
    state.pulseAggMax = null
    state.pulseHouseAggSum = 0G
    state.pulseHouseAggCount = 0
    state.pulseHouseAggMin = null
    state.pulseHouseAggMax = null
    state.pulseAggLastLogTs = now()

    getDeviceDriver()

    // Rebuild plan now and whenever Tibber updates the selected list attribute
    unsubscribe()
    if (tibber && settings?.tibberPriceListAttr) {
        subscribe(tibber, settings.tibberPriceListAttr as String, "onTibberPriceListChanged")
        subscribe(tibber, "price", "onTibberCurrentPriceChanged")
    }

    
    // Optional: Tibber Pulse Live Consumption. Subscribe when either active
    // self-balancing OR passive logging is enabled. V2.1.4 keeps net grid power
    // and raw household consumption separate.
    if (settings?.tibberLive && (settings?.enableSelfBalance || settings?.enablePulsePassiveLogging)) {
        subscribe(settings.tibberLive, "power", "onTibberLiveNetPowerChanged")
        subscribe(settings.tibberLive, "powerConsumption", "onTibberLiveConsumptionChanged")

        // Seed both values immediately so logs do not show stale data while
        // waiting for the next Pulse event.
        try {
            def currentNet = settings.tibberLive.currentValue("power")
            if (currentNet != null) {
                String s = currentNet.toString().trim().replace(",", ".")
                state.liveNetPowerkW = new BigDecimal(s)
                state.liveNetPowerLastTs = now()
            }

            def currentConsumption = settings.tibberLive.currentValue("powerConsumption")
            if (currentConsumption != null) {
                String s = currentConsumption.toString().trim().replace(",", ".")
                state.liveConsumptionkW = new BigDecimal(s)
                state.liveConsumptionLastTs = now()
            }

            // Backward-compatible alias for any older dashboard code.
            state.livePowerkW = state.liveConsumptionkW != null ? state.liveConsumptionkW : state.liveNetPowerkW
            state.livePowerLastTs = now()

            if (settings?.enablePulsePassiveLogging) {
                log.info("Pulse initialized: consumption=${_pulseConsumptionKwStr()} kW; net=${_pulseNetKwStr()} kW")
            }
        } catch (Throwable t) {
            logDebug("Failed to initialize Tibber Pulse values: ${t}")
        }
    }
if (settings?.useDynamicBuffer && settings?.outsideTempSensor) {
        subscribe(settings.outsideTempSensor, "temperature", "onOutsideTempChanged")
    }

    ensureDailyPlan()


    // Publish full daily plan for driver/dashboard
    publishFullDailyPlanToDriver()
    // Apply at the start of every quarter (00/15/30/45), a couple seconds after boundary
    schedule("2 0/15 * ? * * *", "quarterTick")
    // Run once shortly after startup/update so outputs become correct immediately
    runIn(3, "quarterTick")
// Rebuild shortly after midnight (new day prices)
    schedule("15 0 0 ? * * *", "rebuildDailyPlan")         // 00:00:15
}

def uninstalled() {
    unschedule()
}

/* ===================== Core Logic ===================== */

def calculateSmartGridState(evt = null, boolean forceApply = false) {
    // Ensure plan exists (or rebuild if day changed / settings changed)
    ensureDailyPlan()

    // --- Prices from Tibber (current + day stats) ---
    BigDecimal nowPrice = _dec(tibber, "price", BigDecimal.ZERO)
    BigDecimal maxDay   = _dec(tibber, "priceMaxDay", nowPrice)
    BigDecimal medDay   = _dec(tibber, "priceMedDay", nowPrice)
    BigDecimal minDay   = _dec(tibber, "priceMinDay", nowPrice)

    // --- Solar info ---
    BigDecimal solarKW = safeDivide(_dec(solarPower, "power", BigDecimal.ZERO), new BigDecimal("1000"))
    BigDecimal solarThreshold = (SolarHeatingThreshold ?: 3) as BigDecimal
    boolean solarPowered = (solarKW >= solarThreshold)

    // --- Plan lookup for current 15-minute slot ---
    // V2.2.1 fix: these variables were accidentally removed from V2.2, which
    // caused quarterTick to call _recordAppliedHighSlot(null, null) on startup.
    long slotEpoch = _floorToQuarter(now())
    String plannedRaw = (state?.dailyPlan ?: [:])[slotEpoch.toString()]
    String planned = plannedRaw ?: "LOW_PRICE"  // safe default if today's Tibber plan is not available yet

    // OVER_CAPACITY is fully planned in V2.2. Runtime does not invent precharge
    // from the current price; this prevents orphan precharge after a plan rebuild.
    String proposed = planned

    // V2.2 defensive boundary guard. A recoveryEpoch is an EXCLUSIVE end
    // of the preceding HIGH block. It must never be interpreted as HIGH again,
    // even if an old/stale dailyPlan entry says otherwise.
    if (proposed == "HIGH_PRICE" && _isRecoverySlot(slotEpoch)) {
        log.warn("Recovery boundary guard: ${_fmtTime(new Date(slotEpoch))} was HIGH in dailyPlan; forcing LOW_PRICE")
        proposed = "LOW_PRICE"
    }

    if (solarPowered && proposed == "HIGH_PRICE") {
        // With solar we can allow heating even during an expensive slot.
        proposed = "LOW_PRICE"
    }

    // Track what we actually command, one unique 15-minute slot at a time.
    // This lets a mid-day plan rebuild subtract HIGH time already used today
    // instead of accidentally granting a fresh full daily budget.
    _recordAppliedHighSlot(slotEpoch, proposed)

    if (logEnable) {
        log.debug("Now=${nowPrice}öre; solar=${solarKW.setScale(2, RoundingMode.HALF_UP)}kW(solarPowered=${solarPowered}); planned=${planned}; proposed=${proposed}; pulseConsumption=${_pulseConsumptionKwStr()}kW; pulseNet=${_pulseNetKwStr()}kW")
    }

    // --- Apply planned state ---
    boolean switchesOk = checkSwitchStateConsistency(proposed)

    if (forceApply || proposed != state.currentSmartGridState || !switchesOk) {
        if (forceApply) log.info("Quarter tick: enforcing ${proposed}")
        else if (!switchesOk) log.warn("Switch state mismatch detected – reapplying ${proposed}")
        else log.info("${proposed} MODE")

        state.currentSmartGridState = proposed
        state.lastChangeEpoch = now()
        applySmartGridState(proposed, forceApply)
    } else if (logEnable) {
        log.debug("State unchanged and switches consistent: ${state.currentSmartGridState}")
    }

    // --- Update child device (Dashboard HTML) ---
    try {
        publishDashboard()
        publishCurrentPlanPointer()
    } catch (ignored) { }
}

/* ===================== Output Control ===================== */

private void applySmartGridState(String mode, boolean forceApply = false) {
    switch(mode) {
        case "OVER_CAPACITY":
            _setSwitch(smartA, true, forceApply)
            _setSwitch(smartB, true, forceApply)
            break
        case "LOW_PRICE":
            _setSwitch(smartA, false, forceApply)
            _setSwitch(smartB, true, forceApply)
            break
        case "HIGH_PRICE":
            _setSwitch(smartA, false, forceApply)
            _setSwitch(smartB, false, forceApply)
            break
        case "BLOCKED":
            _setSwitch(smartA, true, forceApply)
            _setSwitch(smartB, false, forceApply)
            break
        default:
            log.warn("Unknown SmartGrid mode: ${mode}")
    }
}

private void _setSwitch(def sw, boolean onDesired, boolean forceApply = false) {
    if (!sw) return

    if (forceApply) {
        // Force send command at quarter boundary even if device reports stale state
        if (onDesired) sw.on()
        else sw.off()
        return
    }

    def cur = sw.currentValue("switch")
    if (onDesired && cur != "on")  sw.on()
    if (!onDesired && cur != "off") sw.off()
}

private boolean checkSwitchStateConsistency(String mode) {
    if (!smartA || !smartB) return true  // nothing to check

    def a = smartA.currentValue("switch") == "on"
    def b = smartB.currentValue("switch") == "on"

    switch (mode) {
        case "OVER_CAPACITY": return a && b
        case "LOW_PRICE":     return !a && b
        case "HIGH_PRICE":    return !a && !b
        case "BLOCKED":       return a && !b
        default: return true
    }
}



// Debounce plan rebuilds to avoid storms from rapid sensor/price events
def scheduleRebuildDailyPlan(Integer delaySec = 2) {
    try { unschedule("rebuildDailyPlan") } catch (ignored) { }
    runIn(delaySec, "rebuildDailyPlan")
}

/* ===================== Daily Plan (15-min Peak Cutting) ===================== */

def onTibberPriceListChanged(evt) {
    if (logEnable) log.debug("Tibber price list changed (${evt?.name}); scheduling plan rebuild")
    scheduleRebuildDailyPlan(2)
}

def onOutsideTempChanged(evt) {
    if (!(settings?.useDynamicBuffer && settings?.outsideTempSensor)) return
    BigDecimal t = safeDec(evt?.value)
    if (t == null) return
    BigDecimal last = state.lastOutsideTemp != null ? safeDec(state.lastOutsideTemp) : null
    BigDecimal delta = safeDec(settings?.tempRecalcDeltaC) ?: 0.5G
    if (delta < 0) delta = 0G
    if (last == null || (t.subtract(last)).abs() >= delta) {
        state.lastOutsideTemp = t

        // V2.2.6: Do not tear down an active, already-planned HIGH_PRICE block
        // just because the outdoor temperature crossed the replanning threshold.
        // The existing block was selected using the same day's prices and is already
        // in progress. Rebuild immediately after that block ends instead. This keeps
        // temperature adaptation, but prevents a mid-peak HIGH -> LOW transition such
        // as the one observed on 2026-09-22 at 18:30.
        Long highEndEpoch = _activePlannedHighEndExclusive()
        if (highEndEpoch != null) {
            long delayMs = Math.max(1000L, highEndEpoch - now() + 2000L)
            int delaySec = Math.max(1, (int)Math.ceil(delayMs / 1000.0D))
            if (logEnable) {
                log.info "Outdoor temp changed to ${t}°C (Δ>=${delta}) during active HIGH_PRICE; deferring plan rebuild until ${_fmtTime(new Date(highEndEpoch))}"
            }
            scheduleRebuildDailyPlan(delaySec)
            return
        }

        if (logEnable) log.info "Outdoor temp changed to ${t}°C (Δ>=${delta}), rebuilding daily plan"
        scheduleRebuildDailyPlan(2)
    }
}

/**
 * V2.2.6 continuation guard.
 * Returns the exclusive end epoch of the currently active planned HIGH_PRICE
 * run, but only when HIGH_PRICE is both the current commanded mode and the
 * current quarter in the existing daily plan. Otherwise returns null.
 *
 * This helper intentionally does not change the planner, recovery scoring,
 * daily HIGH budget, solar override, or precharge logic. It only prevents an
 * outdoor-temperature event from replacing an active HIGH block mid-run.
 */
private Long _activePlannedHighEndExclusive() {
    if (state?.currentSmartGridState != 'HIGH_PRICE') return null

    Map plan = (state?.dailyPlan ?: [:]) as Map
    if (!plan) return null

    long qMs = 15L * 60L * 1000L
    long slotEpoch = _floorToQuarter(now())
    if (plan[slotEpoch.toString()] != 'HIGH_PRICE') return null

    long e = slotEpoch
    while (plan[e.toString()] == 'HIGH_PRICE') {
        e += qMs
    }
    return e
}

def onTibberCurrentPriceChanged(evt) {
    // No-op; kept for future extensions.
}

def onTibberLiveNetPowerChanged(evt) {
    // Net grid import/export power from Tibber Pulse (kW).
    try {
        String s = (evt?.value != null) ? evt.value.toString().trim() : null
        if (s) {
            s = s.replace(",", ".")
            state.liveNetPowerkW = new BigDecimal(s)
            state.liveNetPowerLastTs = now()
            if (state.liveConsumptionkW == null) {
                state.livePowerkW = state.liveNetPowerkW
                state.livePowerLastTs = now()
            }
            // V2.2.4 uses the net-power event as the sampling clock. It is the signal that
            // continues to carry useful information while exporting solar power.
            if (settings?.enablePulsePassiveLogging) {
                _accumulatePulsePassiveSample()
            }
        }
    } catch (Throwable t) {
        logDebug("Failed to parse Tibber net power '${evt?.value}': ${t}")
    }
}

def onTibberLiveConsumptionChanged(evt) {
    // Raw household consumption before local production is subtracted (kW).
    // This is the preferred signal for future rebound/self-balancing logic.
    try {
        String s = (evt?.value != null) ? evt.value.toString().trim() : null
        if (s) {
            s = s.replace(",", ".")
            state.liveConsumptionkW = new BigDecimal(s)
            state.liveConsumptionLastTs = now()

            // Keep legacy state.livePowerkW pointing at the most useful signal.
            state.livePowerkW = state.liveConsumptionkW
            state.livePowerLastTs = now()

            // Passive aggregation is sampled by the net-power handler so one Pulse update
            // contributes only once even though the driver publishes several attributes.
        }
    } catch (Throwable t) {
        logDebug("Failed to parse Tibber consumption power '${evt?.value}': ${t}")
    }
}

def ensureDailyPlan() {
    String todayKey = _todayKey()
    if (state?.planDayKey != todayKey || !state?.dailyPlan || state?.planConfigSig != _planConfigSig()) {
        scheduleRebuildDailyPlan(2)
    }
}

def rebuildDailyPlan() {
    if (state._rebuildInProgress) {
        if (logEnable) log.debug("rebuildDailyPlan: already in progress, skipping")
        return
    }
    state._rebuildInProgress = true
    try {
        if (!tibber) return
        if (!settings?.tibberPriceListAttr) {
        log.warn("No Tibber price list attribute selected (tibberPriceListAttr). Cannot build 15-min plan.")
        return
    }

    def raw = tibber.currentValue(settings.tibberPriceListAttr as String)
    def parsed = _parsePriceList(raw)

    if (!parsed || parsed.size() < 8) {
        log.warn("Could not parse 15-min price list from attribute '${settings.tibberPriceListAttr}'. Value=${raw}")
        return
    }

    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    Date nowD = new Date()
    Date startDay = _startOfDay(nowD, tz)
    Date endDay = new Date(startDay.time + 24L*60L*60L*1000L)

    List<Map> slots = _normalizeToSlots(parsed, startDay, tz)

    if (slots && slots[0]?.epoch) {
        slots = slots.findAll { it.epoch >= startDay.time && it.epoch < endDay.time }
    }

    if (!slots || slots.size() < 8) {
        log.warn("Price list normalization produced too few slots for today (${slots?.size() ?: 0}).")
        return
    }

    slots = slots.sort { a, b -> (a.epoch <=> b.epoch) }

    // Ensure epochs match 15-minute boundaries used by plan lookup
    slots = _dedupeSlotsByEpoch(slots.collect { it.epoch = _floorToQuarter(it.epoch as Long); return it })

    // V2.2.3: Tibber priceListAll quarter alignment correction.
    // Observed runtime prices showed that the list value attached to quarter N
    // actually belongs to quarter N+1. Keep the wall-clock epochs intact and
    // move each list price forward one slot. Example: the raw value stored at
    // 07:15 is the price that is active from 07:30 to 07:45.
    //
    // This is deliberately applied only to priceListAll, the source where the
    // one-quarter offset was observed. The first slot has no preceding value in
    // today's list; when it is the current quarter we seed it from Tibber's live
    // `price` attribute, otherwise we retain the first raw value as a safe fallback.
    if ((settings?.tibberPriceListAttr as String) == 'priceListAll') {
        slots = _shiftPriceListAllForwardOneQuarter(slots)
    }

    List<BigDecimal> prices = slots.collect { (it.price as BigDecimal) }
    List<Long> epochs = slots.collect { (it.epoch as Long) }

    Map<String, Object> planResult = _buildPeakCutPlan(prices, epochs)

    state.dailyPlan = (planResult.plan as Map<String,String>)
    state.planMeta = (planResult.meta as Map)
    state.planDayKey = _todayKey()
    state.planGeneratedAt = now()
    state.planConfigSig = _planConfigSig()

    // Cache prices by epoch for preheat “jump” estimation
    state.planPricesByEpoch = [:]
    for (int i=0; i<epochs.size(); i++) { state.planPricesByEpoch[epochs[i].toString()] = prices[i] }

    // Price map for dashboard JSON (epochMs -> price)
    state.priceMap = [:]
    for (int i=0; i<epochs.size(); i++) { state.priceMap[epochs[i].toString()] = prices[i] }

    if ((settings?.planLogEnable ?: true) as Boolean) {
        _logPlanSummary(planResult, slots)
    }

    _publishPlanToDisplay(planResult, slots)


    // Publish full daily plan to driver for dashboard
    publishFullDailyPlanToDriver()
    // Apply immediately
    calculateSmartGridState()
    } finally {
        state._rebuildInProgress = false
    }
}

private Map<String, Object> _buildPeakCutPlan(List<BigDecimal> prices, List<Long> epochs) {
    /*
     * V2 recovery-aware planner
     * -------------------------
     * 1. Generate variable-length HIGH candidates (15-min resolution).
     * 2. For every candidate, search forward for a cheap recovery landing zone.
     * 3. Optionally extend HIGH until that landing zone so the heat pump is released
     *    when electricity is cheap rather than immediately after the price peak.
     * 4. Score the complete move: expensive energy avoided minus estimated recovery cost.
     * 5. Greedily choose non-overlapping candidates while reserving the recovery window.
     */
    int n = prices?.size() ?: 0
    int qPer = 15
    long qMs = 15L * 60L * 1000L
    if (n <= 0) return [plan:[:], meta:[strategy:'recovery_aware_v2_2_6', blocks:[]]]

    if (!epochs || epochs.size() != n) {
        def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
        long startDayMs = _startOfDay(new Date(), tz).time
        epochs = (0..<n).collect { int idx -> startDayMs + idx * qMs }
        log.warn("Epoch list missing/mismatched; synthesized 15-minute epochs for V2 planning.")
    }

    int effectiveMaxMin = effectiveMaxHighContMin() as int
    int minHighMin = Math.max(15, (settings?.minHighContMin ?: 30) as int)
    if (minHighMin > effectiveMaxMin) minHighMin = effectiveMaxMin
    int minHighQ = Math.max(1, minHighMin.intdiv(qPer))
    int maxContQ = Math.max(0, effectiveMaxMin.intdiv(qPer))
    int maxTotQ = Math.max(0, ((settings?.maxHighTotalMin ?: 120) as int).intdiv(qPer))
    int executedHighQ = _executedHighSlotsCountToday()
    int remainingTotQ = Math.max(0, maxTotQ - executedHighQ)

    int lookQ = Math.max(1, Math.min(16, (settings?.recoveryLookaheadQuarters ?: 8) as int))
    int recQ = Math.max(1, Math.min(8, (settings?.recoveryMinQuarters ?: 2) as int))
    BigDecimal recTolerance = (settings?.recoveryPriceToleranceOre ?: 15) as BigDecimal
    BigDecimal recWeight = (settings?.recoveryWeight ?: 0.70G) as BigDecimal
    if (recWeight < 0G) recWeight = 0G
    if (recWeight > 2G) recWeight = 2G
    BigDecimal minSpread = (settings?.minEconomicSpreadOre ?: 20) as BigDecimal
    boolean extendToRecovery = (settings?.extendHighToRecovery == null) ? true : (settings.extendHighToRecovery as Boolean)

    List<Boolean> isHigh = (0..<n).collect { false }
    List<Boolean> isOver = (0..<n).collect { false }

    // If the plan is rebuilt during the day (for example after saving settings),
    // never spend today's HIGH budget on slots that have already ended.
    long nowMs = now()
    int firstActiveIdx = 0
    while (firstActiveIdx < n && (epochs[firstActiveIdx] + qMs) <= nowMs) firstActiveIdx++
    if (firstActiveIdx >= n) firstActiveIdx = n

    if (settings?.planLogEnable && firstActiveIdx > 0 && firstActiveIdx < n) {
        Date d = new Date(epochs[firstActiveIdx])
        String fromTxt = d.format('HH:mm', location?.timeZone ?: TimeZone.getTimeZone('UTC'))
        log.info("V2.1 planner: ignoring ${firstActiveIdx} elapsed slots; planning from ${fromTxt}")
    }

    if (maxContQ <= 0 || remainingTotQ <= 0) {
        Map<String,String> plan = [:]
        epochs.each { Long e -> plan[e.toString()] = 'LOW_PRICE' }
        return [plan:plan, meta:[strategy:'recovery_aware_v2_2_6', maxContQ:maxContQ, maxTotQ:maxTotQ, executedHighQ:executedHighQ, remainingTotQ:remainingTotQ, blocks:[]]]
    }

    List<Map> candidates = []

    for (int s = firstActiveIdx; s < n; s++) {
        for (int nominalLen = minHighQ; nominalLen <= maxContQ; nominalLen++) {
            int nominalEnd = s + nominalLen - 1
            if (nominalEnd >= n) break

            int firstRecovery = nominalEnd + 1
            if (firstRecovery >= n) continue

            int latestRecovery = Math.min(Math.min(n - recQ, nominalEnd + lookQ), s + maxContQ)
            if (latestRecovery < firstRecovery) continue

            // Find cheapest recovery window in the look-ahead range.
            BigDecimal cheapestRecAvg = null
            int cheapestRecStart = firstRecovery
            for (int r = firstRecovery; r <= latestRecovery; r++) {
                BigDecimal sum = 0G
                for (int k = r; k < r + recQ; k++) sum += (prices[k] ?: 0G)
                BigDecimal avg = sum / recQ
                if (cheapestRecAvg == null || avg < cheapestRecAvg) {
                    cheapestRecAvg = avg
                    cheapestRecStart = r
                }
            }

            // Prefer the first recovery window that is close to the cheapest one.
            // This avoids unnecessarily long HIGH periods for a tiny extra price saving.
            int recoveryStart = cheapestRecStart
            BigDecimal allowedRecAvg = cheapestRecAvg + recTolerance
            for (int r = firstRecovery; r <= cheapestRecStart; r++) {
                BigDecimal sum = 0G
                for (int k = r; k < r + recQ; k++) sum += (prices[k] ?: 0G)
                BigDecimal avg = sum / recQ
                if (avg <= allowedRecAvg) {
                    recoveryStart = r
                    break
                }
            }

            int finalEnd = extendToRecovery ? (recoveryStart - 1) : nominalEnd
            int finalLen = finalEnd - s + 1
            if (finalLen < minHighQ || finalLen > maxContQ) continue
            if (finalLen > remainingTotQ) continue
            if (recoveryStart + recQ > n) continue

            BigDecimal highSum = 0G
            BigDecimal highMax = null
            for (int k=s; k<=finalEnd; k++) {
                BigDecimal p = prices[k] ?: 0G
                highSum += p
                if (highMax == null || p > highMax) highMax = p
            }
            BigDecimal highAvg = highSum / finalLen

            BigDecimal recSum = 0G
            for (int k=recoveryStart; k<recoveryStart+recQ; k++) recSum += (prices[k] ?: 0G)
            BigDecimal recAvg = recSum / recQ

            // Approximate economic value in price-quarter units. recWeight represents how much
            // of the shifted thermal energy is expected to return during the rebound period.
            BigDecimal score = highSum - (recAvg * finalLen * recWeight)
            BigDecimal spread = highAvg - recAvg
            BigDecimal scoreDensity = score / finalLen

            // Avoid cycling the heat pump for negligible price differences.
            if (spread < minSpread || score <= 0G) continue

            candidates << [
                start:s, end:finalEnd, len:finalLen,
                nominalEnd:nominalEnd,
                recoveryStart:recoveryStart, recoveryEnd:(recoveryStart + recQ - 1),
                avg:highAvg, max:(highMax ?: 0G),
                recoveryAvg:recAvg, spread:spread, score:score, scoreDensity:scoreDensity,
                startEpoch:epochs[s], endEpoch:epochs[finalEnd],
                endExclusiveEpoch:(epochs[finalEnd] + qMs),
                recoveryEpoch:epochs[recoveryStart]
            ]
        }
    }

    // Rank by saving quality per quarter first. This prevents one long mediocre block
    // from consuming the whole daily HIGH budget ahead of two stronger peaks.
    candidates.sort { a, b ->
        int c = ((b.scoreDensity as BigDecimal) <=> (a.scoreDensity as BigDecimal))
        if (c != 0) return c
        c = ((b.score as BigDecimal) <=> (a.score as BigDecimal))
        if (c != 0) return c
        c = ((b.spread as BigDecimal) <=> (a.spread as BigDecimal))
        if (c != 0) return c
        c = ((a.len as Integer) <=> (b.len as Integer))
        if (c != 0) return c
        return ((a.start as Integer) <=> (b.start as Integer))
    }

    List<Map> picked = []
    int usedHighQ = 0

    for (Map c : candidates) {
        int len = c.len as int
        if (usedHighQ + len > remainingTotQ) continue

        int occupiedStart = c.start as int
        int occupiedEnd = c.recoveryEnd as int
        boolean conflict = picked.any { p ->
            int ps = p.start as int
            int pe = p.recoveryEnd as int
            return !(occupiedEnd < ps || occupiedStart > pe)
        }
        if (conflict) continue

        picked << c
        usedHighQ += len
        if (usedHighQ >= remainingTotQ) break
    }

    picked.sort { it.start as int }
    picked.each { b ->
        for (int k=(b.start as int); k<=(b.end as int); k++) isHigh[k] = true
    }

    // Recovery slots are an explicit no-HIGH zone.
    picked.each { b ->
        int rs = b.recoveryStart as int
        int re = b.recoveryEnd as int
        for (int k=rs; k<=re && k<n; k++) isHigh[k] = false
    }

    // V2.2.5 strategic precharge timing. OVER_CAPACITY is still tied to a
    // selected HIGH block, but it is no longer forced into the quarter(s)
    // immediately adjacent to HIGH. Within a limited look-back window we
    // evaluate all contiguous candidate windows, require the configured price
    // spread, and choose the cheapest qualifying window. For equal prices we
    // prefer the candidate closest to HIGH so stored heat is not held longer
    // than necessary. This avoids charging on a local price spike when a cheaper
    // quarter exists shortly before it.
    boolean prechargeEnable = (settings?.preheatEnable == null) ? true : (settings.preheatEnable as Boolean)
    int prechargeMaxQ = Math.max(1, Math.min(16, (settings?.preheatWindowQuarters ?: 2) as int))
    int prechargeLookbackQ = Math.max(prechargeMaxQ, Math.min(16, (settings?.preheatLookbackQuarters ?: 4) as int))
    BigDecimal prechargeMinSpread = (settings?.preheatSpikeDelta ?: 50) as BigDecimal
    List<Map> prechargeBlocks = []

    if (prechargeEnable) {
        picked.eachWithIndex { b, bi ->
            int hs = b.start as int
            int earliest = Math.max(firstActiveIdx, hs - prechargeLookbackQ)
            Map chosen = null

            for (int len=Math.min(prechargeMaxQ, hs - earliest); len>=1; len--) {
                int latestStart = hs - len
                for (int ps=latestStart; ps>=earliest; ps--) {
                    int pe = ps + len - 1
                    if (pe >= hs) continue

                    // Do not overwrite another HIGH/recovery/precharge reservation.
                    boolean occupied = false
                    for (int k=ps; k<=pe; k++) {
                        if (isHigh[k] || isOver[k]) { occupied = true; break }
                    }
                    if (occupied) continue

                    // Also avoid using the recovery window of another selected HIGH block.
                    boolean recoveryConflict = picked.any { other ->
                        if (other.is(b)) return false
                        int ors = other.recoveryStart as int
                        int ore = other.recoveryEnd as int
                        return !(pe < ors || ps > ore)
                    }
                    if (recoveryConflict) continue

                    BigDecimal sum = 0G
                    for (int k=ps; k<=pe; k++) sum += (prices[k] ?: 0G)
                    BigDecimal avg = sum / len
                    BigDecimal spread = (b.avg as BigDecimal) - avg
                    if (spread < prechargeMinSpread) continue

                    int gapQ = hs - pe - 1
                    Map candidate = [start:ps, end:pe, len:len, avg:avg, spread:spread, gapQ:gapQ,
                                     startEpoch:epochs[ps], endEpoch:epochs[pe],
                                     endExclusiveEpoch:(epochs[pe] + qMs), highBlockIndex:bi]

                    if (chosen == null ||
                        (avg < (chosen.avg as BigDecimal)) ||
                        (avg == (chosen.avg as BigDecimal) && len > (chosen.len as int)) ||
                        (avg == (chosen.avg as BigDecimal) && len == (chosen.len as int) && gapQ < (chosen.gapQ as int))) {
                        chosen = candidate
                    }
                }
            }

            if (chosen) {
                for (int k=(chosen.start as int); k<=(chosen.end as int); k++) isOver[k] = true
                prechargeBlocks << chosen
                b.prechargeStartEpoch = chosen.startEpoch
                b.prechargeEndExclusiveEpoch = chosen.endExclusiveEpoch
                b.prechargeAvg = chosen.avg
                b.prechargeSpread = chosen.spread
                b.prechargeLen = chosen.len
                b.prechargeGapQ = chosen.gapQ
            }
        }
    }

    Map<String,String> plan = [:]
    for (int i=0; i<n; i++) {
        plan[epochs[i].toString()] = isHigh[i] ? 'HIGH_PRICE' : (isOver[i] ? 'OVER_CAPACITY' : 'LOW_PRICE')
    }

    Map meta = [
        n:n, qPer:qPer,
        maxContQ:maxContQ, maxTotQ:maxTotQ, executedHighQ:executedHighQ,
        remainingTotQ:remainingTotQ, pickedTotalQ:usedHighQ,
        recoveryLookaheadQ:lookQ, recoveryQ:recQ,
        recoveryWeight:recWeight, minEconomicSpreadOre:minSpread,
        blocks:picked, prechargeBlocks:prechargeBlocks,
        strategy:'recovery_aware_v2_2_6'
    ]
    return [plan:plan, meta:meta]
}

private void _publishPlanToDisplay(Map planResult, List<Map> slots) {
    try {
        def dev = settings?.displayDevice
        if (!dev) return

        // Build a compact, dashboard-friendly summary of HIGH periods
        List<Map> blocks = (planResult?.meta?.blocks ?: []) as List<Map>
        String periods = blocks.collect { b ->
            String s = (b.start ?: '?')
            String e = (b.end ?: '?')
            Integer m = (b.lenMin ?: 0) as Integer
            return "${s}-${e} (${m}m)"
        }.join("\n")

        dev.setStrategy(planResult?.meta?.strategy ?: '')
        dev.setHighPricePeriods(periods)
        dev.setHighPriceBlocks(blocks.size())
        dev.setHighPriceTotalMin(((planResult?.meta?.pickedTotalQ ?: 0) as Integer) * 15)
        dev.setHighPriceMaxContMin(((planResult?.meta?.maxContQ ?: 0) as Integer) * 15)

        // Optional: show the next upcoming HIGH block
        String next = ''
        def nowStr = (new Date()).format('HH:mm', location?.timeZone)
        for (b in blocks) {
            if ((b.start ?: '99:99') >= nowStr) { next = "${b.start}-${b.end}"; break }
        }
        dev.setNextHighPriceBlock(next)

        // Push live measurements if available
        if (state?.lastPulseKw != null) dev.setPulseKw(state.lastPulseKw as BigDecimal)
        if (state?.lastSolarKw != null) dev.setSolarKw(state.lastSolarKw as BigDecimal)
        dev.setLastPlanRebuild((new Date()).format('yyyy-MM-dd HH:mm', location?.timeZone))
    } catch (Throwable t) {
        logDebug("Display publish failed: ${t}")
    }
}

private void _logPlanSummary(Map<String,Object> planResult, List<Map> slots) {
    long qMs = 15L * 60L * 1000L
    def meta = planResult.meta ?: [:]
    def blocks = meta.blocks ?: []
    int executedMin = ((meta.executedHighQ ?: 0) as int) * 15
    int remainingMin = ((meta.remainingTotQ != null ? meta.remainingTotQ : meta.maxTotQ) as int) * 15
    log.info("PeakCut plan rebuilt for ${_todayKey()} with ${slots.size()} slots. " +
             "strategy=${meta.strategy ?: 'n/a'}, maxCont=${(meta.maxContQ as int)*15}min, " +
             "maxTotal=${(meta.maxTotQ as int)*15}min, usedToday=${executedMin}min, remaining=${remainingMin}min")

    if (!blocks || blocks.size() == 0) {
        log.info("PeakCut plan: no HIGH_PRICE blocks selected.")
        return
    }

    blocks.eachWithIndex { b, i ->
        Long se = (b?.startEpoch instanceof Number) ? ((Number)b.startEpoch).longValue() : null
        Long ee = (b?.endEpoch   instanceof Number) ? ((Number)b.endEpoch).longValue()   : null
        if (se == null || ee == null) {
            log.warn("HIGH block #${i+1} missing epoch(s): start=${b?.startEpoch} end=${b?.endEpoch}; skipping")
            return
        }

        int lenQ = 0
        if (b?.len instanceof Number) {
            lenQ = ((Number)b.len).intValue()
        } else if (b?.start instanceof Number && b?.end instanceof Number) {
            lenQ = (((Number)b.end).intValue() - ((Number)b.start).intValue() + 1)
        }
        if (lenQ <= 0) {
            log.warn("HIGH block #${i+1} has non-positive length (lenQ=${lenQ}); skipping")
            return
        }

        Date sd = new Date(se)
        // HIGH intervals are [start, endExclusive). At exactly endExclusive the
        // recovery slot starts and HIGH must be released.
        Long endExclusive = (b?.endExclusiveEpoch instanceof Number) ? ((Number)b.endExclusiveEpoch).longValue() : (ee + qMs)
        Date ed = new Date(endExclusive)

        String recovery = "n/a"
        if (b?.recoveryEpoch instanceof Number) recovery = _fmtTime(new Date(((Number)b.recoveryEpoch).longValue()))
        log.info(String.format("HIGH block #%d: %s → %s (%d min) avg=%s max=%s; recovery=%s avg=%s spread=%s score=%s",
                (i+1),
                _fmtTime(sd), _fmtTime(ed),
                lenQ * 15,
                _fmt(b.avg), _fmt(b.max),
                recovery, _fmt(b.recoveryAvg), _fmt(b.spread), _fmt(b.score)))
        if (b?.prechargeStartEpoch instanceof Number && b?.prechargeEndExclusiveEpoch instanceof Number) {
            log.info(String.format("  ↳ OVER_CAPACITY precharge: %s → %s (%d min) avg=%s; HIGH spread=%s",
                    _fmtTime(new Date(((Number)b.prechargeStartEpoch).longValue())),
                    _fmtTime(new Date(((Number)b.prechargeEndExclusiveEpoch).longValue())),
                    ((b.prechargeLen ?: 0) as int) * 15, _fmt(b.prechargeAvg), _fmt(b.prechargeSpread)))
            int gapMin = ((b?.prechargeGapQ ?: 0) as int) * 15
            if (gapMin > 0) log.info("    precharge gap before HIGH: ${gapMin} min")
        }
    }

    if ((settings?.planLogVerbose ?: false) as Boolean) {
        def plan = planResult.plan ?: [:]
        slots.each { s ->
            String mode = plan[(s.epoch as long).toString()]
            log.debug(String.format("PLAN %s  price=%s  mode=%s", _fmtTime(new Date(s.epoch as long)), _fmt(s.price), mode))
        }
    }

}

private boolean _plannedHighWithin(int winQ) {
    long nowE = _floorToQuarter(now())
    def plan = state?.dailyPlan ?: [:]
    for (int i=1; i<=winQ; i++) {
        long e = nowE + i*15L*60L*1000L
        if (plan[e.toString()] == "HIGH_PRICE") return true
    }
    return false
}

private BigDecimal _plannedMaxPriceWithin(int winQ) {
    long nowE = _floorToQuarter(now())
    Map priceByEpoch = (state?.planPricesByEpoch ?: [:]) as Map
    if (!priceByEpoch) return null
    BigDecimal m = null
    for (int i=1; i<=winQ; i++) {
        long e = nowE + i*15L*60L*1000L
        def v = priceByEpoch[e.toString()]
        if (v == null) continue
        BigDecimal p = (v as BigDecimal)
        if (m == null || p > m) m = p
    }
    return m
}

private void logDebug(String msg) { if ((settings?.debugLogging ?: false) as Boolean) log.debug(msg) }
private void logInfo(String msg) { log.info(msg) }
private void logWarn(String msg) { log.warn(msg) }

/* ===================== Utilities ===================== */

private BigDecimal safeDec(def v) {
    if (v == null) return null
    try { return new BigDecimal(v.toString()) } catch (e) { return null }
}


private BigDecimal getOutdoorTemp() {
    // Prefer live sensor value, fall back to last stored value
    def v = null
    try { v = outsideTempSensor?.currentValue("temperature") } catch (e) { v = null }
    if (v == null) v = state.lastOutsideTemp
    return safeDec(v)
}

private Integer effectiveMaxHighContMin() {
    int hardMax = (settings?.maxHighContMin ?: 120) as int
    if (hardMax <= 0) return 0
    if (!(settings?.useDynamicBuffer as Boolean) || !settings?.outsideTempSensor) return hardMax

    BigDecimal t = getOutdoorTemp()
    if (t == null) return hardMax

    BigDecimal cold = (settings?.coldTempC ?: -20) as BigDecimal
    BigDecimal warm = (settings?.warmTempC ?: 5) as BigDecimal
    int coldMins = Math.max(15, (settings?.bufferMinAtCold ?: 30) as int)
    int warmMins = Math.max(15, (settings?.bufferMinAtWarm ?: hardMax) as int)
    coldMins = Math.min(coldMins, hardMax)
    warmMins = Math.min(warmMins, hardMax)
    if (warm <= cold) return hardMax

    BigDecimal ratio
    if (t <= cold) ratio = 0G
    else if (t >= warm) ratio = 1G
    else ratio = (t - cold) / (warm - cold)

    BigDecimal curve = (settings?.bufferCurve ?: 1.0G) as BigDecimal
    if (curve <= 0G) curve = 1.0G
    // >1 keeps the allowed HIGH duration lower for longer as temperature falls.
    double shaped = Math.pow(ratio.doubleValue(), curve.doubleValue())
    BigDecimal shapedRatio = new BigDecimal(shaped.toString())
    BigDecimal minsBD = (coldMins as BigDecimal) + ((warmMins - coldMins) as BigDecimal) * shapedRatio

    int mins = Math.round((minsBD / 15G)) * 15
    mins = Math.max(15, Math.min(hardMax, mins))
    return mins as Integer
}

private BigDecimal _dec(dev, String attr, BigDecimal deflt = null) {
    try {
        def v = dev?.currentValue(attr)
        if (v == null) return deflt
        return new BigDecimal(v.toString())
    } catch (e) {
        return deflt
    }
}

private BigDecimal safeDivide(BigDecimal a, BigDecimal b) {
    if (!a || !b || b.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO
    return a.divide(b, 6, RoundingMode.HALF_UP)
}

// ---- Time helpers ----
private long _floorToQuarter(long epochMs) {
    long q = 15L*60L*1000L
    // Groovy "/" returns BigDecimal, so use intdiv to truly floor
    return epochMs.intdiv(q) * q
}

private String _todayKey() {
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    def d = new Date()
    def cal = Calendar.getInstance(tz)
    cal.setTime(d)
    return String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH)+1, cal.get(Calendar.DAY_OF_MONTH))
}

private String _planConfigSig() {
    return [
        settings?.tibberPriceListAttr,
        settings?.minHighContMin,
        settings?.maxHighContMin,
        settings?.maxHighTotalMin,
        settings?.recoveryLookaheadQuarters,
        settings?.recoveryMinQuarters,
        settings?.recoveryPriceToleranceOre,
        settings?.recoveryWeight,
        settings?.minEconomicSpreadOre,
        settings?.extendHighToRecovery,
        settings?.useDynamicBuffer,
        settings?.outsideTempSensor,
        settings?.coldTempC,
        settings?.warmTempC,
        settings?.bufferMinAtCold,
        settings?.bufferMinAtWarm,
        settings?.bufferCurve,
        settings?.preheatEnable,
        settings?.preheatSpikeDelta,
        settings?.preheatWindowQuarters,
        settings?.preheatLookbackQuarters
    ].join('|')
}

private Date _startOfDay(Date d, TimeZone tz) {
    Calendar cal = Calendar.getInstance(tz)
    cal.setTime(d)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.getTime()
}

private boolean _isRecoverySlot(long slotEpoch) {
    try {
        List<Map> blocks = (state?.planMeta?.blocks ?: []) as List<Map>
        int recQ = Math.max(1, ((state?.planMeta?.recoveryQ ?: settings?.recoveryMinQuarters ?: 2) as int))
        long qMs = 15L * 60L * 1000L
        return blocks.any { b ->
            if (!(b?.recoveryEpoch instanceof Number)) return false
            long rs = ((Number)b.recoveryEpoch).longValue()
            return slotEpoch >= rs && slotEpoch < (rs + recQ * qMs)
        }
    } catch (Throwable ignored) {
        return false
    }
}

private void _recordAppliedHighSlot(Long slotEpoch, String proposed) {
    if (slotEpoch == null || proposed == null) {
        log.warn("Skipping HIGH-slot accounting because slotEpoch/proposed is null")
        return
    }
    String day = _todayKey()
    if (state?.executedHighDay != day) {
        state.executedHighDay = day
        state.executedHighSlots = [:]
    }
    Map slots = (state?.executedHighSlots ?: [:]) as Map
    String key = slotEpoch.toString()
    if (proposed == 'HIGH_PRICE') slots[key] = true
    // If solar/preheat/recovery overrides HIGH before the slot is actually run,
    // remove it. Repeated calls in the same slot therefore reflect the latest
    // command rather than double-counting.
    else slots.remove(key)
    state.executedHighSlots = slots
}

private int _executedHighSlotsCountToday() {
    String day = _todayKey()
    if (state?.executedHighDay != day) {
        state.executedHighDay = day
        state.executedHighSlots = [:]
        return 0
    }
    Map slots = (state?.executedHighSlots ?: [:]) as Map
    long nowQuarter = _floorToQuarter(now())
    // Count only slots that started before the current planning quarter. The
    // current slot remains available to the new plan and is recorded again when applied.
    return slots.findAll { k, v ->
        try { return v && (k.toString().toLong() < nowQuarter) } catch (ignored) { return false }
    }.size()
}

private void _accumulatePulsePassiveSample() {
    long ts = now()
    int intervalSec = Math.max(30, Math.min(600, (settings?.pulseLogIntervalSec ?: 60) as int))

    BigDecimal consumptionKw = state?.liveConsumptionkW != null ? (state.liveConsumptionkW as BigDecimal) : null
    BigDecimal netKw = state?.liveNetPowerkW != null ? (state.liveNetPowerkW as BigDecimal) : null
    BigDecimal solarKw = _currentSolarKw()
    BigDecimal houseKw = (netKw != null) ? netKw + solarKw : null
    // Small timing/rounding differences between SAJ and Tibber can yield a tiny negative
    // estimate around zero. A physical whole-house load cannot be negative.
    if (houseKw != null && houseKw < 0G) houseKw = 0G

    if (consumptionKw != null) {
        BigDecimal sum = (state?.pulseAggSum != null) ? (state.pulseAggSum as BigDecimal) : 0G
        int count = (state?.pulseAggCount ?: 0) as int
        BigDecimal mn = (state?.pulseAggMin != null) ? (state.pulseAggMin as BigDecimal) : null
        BigDecimal mx = (state?.pulseAggMax != null) ? (state.pulseAggMax as BigDecimal) : null
        state.pulseAggSum = sum + consumptionKw
        state.pulseAggCount = count + 1
        state.pulseAggMin = (mn == null || consumptionKw < mn) ? consumptionKw : mn
        state.pulseAggMax = (mx == null || consumptionKw > mx) ? consumptionKw : mx
    }

    if (houseKw != null) {
        BigDecimal sum = (state?.pulseHouseAggSum != null) ? (state.pulseHouseAggSum as BigDecimal) : 0G
        int count = (state?.pulseHouseAggCount ?: 0) as int
        BigDecimal mn = (state?.pulseHouseAggMin != null) ? (state.pulseHouseAggMin as BigDecimal) : null
        BigDecimal mx = (state?.pulseHouseAggMax != null) ? (state.pulseHouseAggMax as BigDecimal) : null
        state.pulseHouseAggSum = sum + houseKw
        state.pulseHouseAggCount = count + 1
        state.pulseHouseAggMin = (mn == null || houseKw < mn) ? houseKw : mn
        state.pulseHouseAggMax = (mx == null || houseKw > mx) ? houseKw : mx
    }

    long last = (state?.pulseAggLastLogTs ?: 0L) as long
    if (last == 0L) {
        state.pulseAggLastLogTs = ts
        return
    }
    if ((ts - last) < intervalSec * 1000L) return

    int n = (state?.pulseAggCount ?: 0) as int
    if (n > 0) {
        BigDecimal total = (state?.pulseAggSum != null) ? (state.pulseAggSum as BigDecimal) : 0G
        BigDecimal avg = total / n
        // IMPORTANT: do not use Groovy's ?: here. BigDecimal.ZERO is false-ish and caused
        // the old mathematically impossible logs (for example avg < displayed min) during export.
        BigDecimal minV = (state?.pulseAggMin != null) ? (state.pulseAggMin as BigDecimal) : null
        BigDecimal maxV = (state?.pulseAggMax != null) ? (state.pulseAggMax as BigDecimal) : null
        if (minV != null && maxV != null && (avg < minV || avg > maxV)) {
            log.warn("Pulse aggregation inconsistency: consumption avg=${_fmt(avg)} min=${_fmt(minV)} max=${_fmt(maxV)} samples=${n}")
        }

        int hn = (state?.pulseHouseAggCount ?: 0) as int
        String housePart = "house=n/a"
        if (hn > 0) {
            BigDecimal htotal = (state?.pulseHouseAggSum != null) ? (state.pulseHouseAggSum as BigDecimal) : 0G
            BigDecimal havg = htotal / hn
            BigDecimal hmin = (state?.pulseHouseAggMin != null) ? (state.pulseHouseAggMin as BigDecimal) : null
            BigDecimal hmax = (state?.pulseHouseAggMax != null) ? (state.pulseHouseAggMax as BigDecimal) : null
            if (hmin != null && hmax != null && (havg < hmin || havg > hmax)) {
                log.warn("Pulse aggregation inconsistency: house avg=${_fmt(havg)} min=${_fmt(hmin)} max=${_fmt(hmax)} samples=${hn}")
            }
            housePart = "house avg=${_fmt(havg)}kW min=${_fmt(hmin)} max=${_fmt(hmax)} samples=${hn}"
        }

        log.debug("Pulse ${intervalSec}s summary: consumption avg=${_fmt(avg)}kW min=${_fmt(minV)} max=${_fmt(maxV)} samples=${n}; net=${_pulseNetKwStr()}kW; ${housePart}; solar=${_fmt(solarKw)}kW")
    }

    state.pulseAggLastLogTs = ts
    state.pulseAggSum = 0G
    state.pulseAggCount = 0
    state.pulseAggMin = null
    state.pulseAggMax = null
    state.pulseHouseAggSum = 0G
    state.pulseHouseAggCount = 0
    state.pulseHouseAggMin = null
    state.pulseHouseAggMax = null
}

private BigDecimal _currentSolarKw() {
    try {
        if (!solarPower) return 0G
        BigDecimal watts = _dec(solarPower, "power", BigDecimal.ZERO)
        return safeDivide(watts, new BigDecimal("1000")) ?: 0G
    } catch (Throwable ignored) {
        return 0G
    }
}

private String _pulseConsumptionKwStr() {
    if (!tibberLive) return "n/a"
    def kw = state?.liveConsumptionkW
    if (kw == null) return "n/a"
    return String.format(java.util.Locale.US, "%.2f", (kw as BigDecimal))
}

private String _pulseNetKwStr() {
    if (!tibberLive) return "n/a"
    def kw = state?.liveNetPowerkW
    if (kw == null) return "n/a"
    return String.format(java.util.Locale.US, "%.2f", (kw as BigDecimal))
}

// Backward-compatible helper used by any remaining dashboard code.
private String _pulseKwStr() {
    def c = state?.liveConsumptionkW
    if (c != null) return String.format(java.util.Locale.US, "%.2f", (c as BigDecimal))
    return _pulseNetKwStr()
}

private String _fmtTime(Date d) {
    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")
    return d.format("HH:mm", tz)
}

private String _fmt(def v) {
    if (v == null) return "null"
    try {
        return (v as BigDecimal).setScale(2, RoundingMode.HALF_UP).toString()
    } catch (e) {
        return v.toString()
    }
}

// ---- Price list parsing ----
// Supports: List<Number>, JSON string list, List<Map> with price + startsAt/time/epoch
private List _parsePriceList(def raw) {
    if (raw == null) return null
    if (raw instanceof List) return raw

    String s = raw.toString()
    if (!s) return null

    // Try JSON
    try {
        def slurper = new groovy.json.JsonSlurper()
        def obj = slurper.parseText(s)
        if (obj instanceof List) return obj
    } catch (e) {
        // ignore
    }

    return null
}

// Normalize parsed list into slots: [epoch:<ms>, price:<BigDecimal>]
private List<Map> _normalizeToSlots(List parsed, Date startDay, TimeZone tz) {
    if (!parsed) return []

    // Case A: list of numbers (assume today starting at midnight)
    if (parsed[0] instanceof Number || parsed[0] instanceof BigDecimal) {
        long base = startDay.time
        List<Map> out = []
        parsed.eachWithIndex { v, i ->
            out << [epoch: base + i*15L*60L*1000L, price: new BigDecimal(v.toString())]
        }
        return out
    }

    // Case B: list of maps with a time key
    if (parsed[0] instanceof Map) {
        List<Map> out = []
        parsed.each { m ->
            def priceVal = (m.price != null) ? m.price : (m.energyPrice != null ? m.energyPrice : m.total != null ? m.total : null)
            def tVal = m.startsAt ?: m.start ?: m.time ?: m.datetime ?: m.epoch
            if (priceVal == null) return
            Long epoch = null
            if (tVal instanceof Number) {
                epoch = (tVal as Long)
                // if it's seconds, convert to ms
                if (epoch < 10_000_000_000L) epoch = epoch * 1000L
            } else if (tVal != null) {
                try {
                    // Tibber often uses ISO-8601; Date.parse can handle many variants
                    Date dd = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", tVal.toString())
                    epoch = dd.time
                } catch (e1) {
                    try {
                        Date dd = Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSX", tVal.toString())
                        epoch = dd.time
                    } catch (e2) {
                        try {
                            Date dd = Date.parse("yyyy-MM-dd'T'HH:mm:ssXXX", tVal.toString())
                            epoch = dd.time
                        } catch (e2b) {
                            try {
                                Date dd = Date.parse("yyyy-MM-dd'T'HH:mm:ssX", tVal.toString())
                                epoch = dd.time
                            } catch (e2c) {

                            }
                        }
                        try {
                            // fallback: let Groovy parse
                            Date dd = new Date(tVal.toString())
                            epoch = dd.time
                        } catch (e3) { epoch = null }
                    }
                }
            }
            if (epoch == null) return
            // Align to 15-minute boundary to match _floorToQuarter(now()) lookups
            epoch = _floorToQuarter(epoch)
            out << [epoch: epoch, price: new BigDecimal(priceVal.toString())]
        }
        return out
    }

    return []
}

// V2.2.3 correction for Tibber priceListAll.
// The device's list is one quarter ahead of the wall-clock slot used by its live
// `price` attribute. Shift prices (not epochs) forward one quarter so planner,
// runtime lookup and plan logging all refer to the same 15-minute interval.
private List<Map> _shiftPriceListAllForwardOneQuarter(List<Map> slots) {
    if (!slots || slots.size() < 2) return slots ?: []

    List<Map> sorted = slots.collect { s -> [epoch:(s.epoch as Long), price:(s.price as BigDecimal)] }
                            .sort { a, b -> (a.epoch as Long) <=> (b.epoch as Long) }
    List<Map> out = []

    // No preceding list value exists for the first retained slot. Preserve it by
    // default. If that slot is the current quarter, the live Tibber price is the
    // authoritative value and is used instead.
    BigDecimal firstPrice = sorted[0].price as BigDecimal
    try {
        long nowQ = _floorToQuarter(now())
        if ((sorted[0].epoch as Long) == nowQ) {
            firstPrice = _dec(tibber, 'price', firstPrice)
        }
    } catch (ignored) { }
    out << [epoch:(sorted[0].epoch as Long), price:firstPrice]

    for (int i=1; i<sorted.size(); i++) {
        out << [epoch:(sorted[i].epoch as Long), price:(sorted[i-1].price as BigDecimal)]
    }

    if (logEnable) {
        long nowQ = _floorToQuarter(now())
        Map cur = out.find { (it.epoch as Long) == nowQ }
        BigDecimal live = _dec(tibber, 'price', null)
        if (cur && live != null) {
            BigDecimal d = ((cur.price as BigDecimal) - live).abs()
            log.debug("V2.2.3 price alignment: current slot=${_fmtTime(new Date(nowQ))}, plannedPrice=${_fmt(cur.price)}, livePrice=${_fmt(live)}, delta=${_fmt(d)}")
        }
    }
    return out
}

// De-duplicate slots by epoch (can happen if incoming price list has timestamps with seconds)
// Keeps the last occurrence for each epoch.
private List<Map> _dedupeSlotsByEpoch(List<Map> slots) {
    if (!slots) return []
    Map<Long, Map> m = [:]
    slots.each { s ->
        try {
            if (s?.epoch == null) return
            Long e = (s.epoch as Long)
            m[e] = s
        } catch (ignored) { }
    }
    return m.values().toList().sort { a, b -> (a.epoch as Long) <=> (b.epoch as Long) }
}

// Percentile (0–100) with nearest-rank
private BigDecimal _percentile(List<BigDecimal> vals, int pct) {
    if (!vals) return null
    List<BigDecimal> clean = vals.findAll { it != null }.sort()
    if (!clean) return null
    if (pct <= 0) return clean[0]
    if (pct >= 100) return clean[-1]
    int idx = (int) Math.ceil((pct/100.0d) * (clean.size()-1))
    idx = Math.max(0, Math.min(clean.size()-1, idx))
    return clean[idx]
}

private List<BigDecimal> _nextQuarters(dev) {
    def q1 = _dec(dev, "priceNext15m")
    def q2 = _dec(dev, "pricePlus30m")
    def q3 = _dec(dev, "pricePlus45m")
    def q4 = _dec(dev, "pricePlus60m")
    return [q1,q2,q3,q4].findAll { it != null }
}

private BigDecimal computeDynamicThreshold(BigDecimal nowP, BigDecimal minP, BigDecimal medP, BigDecimal maxP, BigDecimal lowP, BigDecimal highP) {
    if (nowP < lowP) return highP

    if (nowP <= highP) {
        BigDecimal span = maxP.subtract(medP)

        // Configurable “top cutting”: choose how far from median → max the threshold should be.
        // 0%  = medP
        // 100%= maxP
        BigDecimal cutPct = ((67 ?: 67) as BigDecimal)
        if (cutPct < 0) cutPct = 0
        if (cutPct > 100) cutPct = 100

        BigDecimal ratio = cutPct.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP)
        BigDecimal candidate = medP.add(span.multiply(ratio))

        // Clamp to user prefs
        if (candidate > highP) candidate = highP
        if (candidate < lowP)  candidate = lowP
        return candidate
    }
    return highP
}

private void logDebug(def msg) {
    if (settings?.debugLogging == true) log.debug "${msg}"
}

private void logInfo(def msg) {
    log.info "${msg}"
}

private void logWarn(def msg) {
    log.warn "${msg}"
}

/* ===================== Scheduler ===================== */



def quarterTick() {
    // Force apply outputs at quarter boundary using the same logic as calculateSmartGridState
    calculateSmartGridState(null, true)
}
def scheduleQuarterTick() {
    Date nowD = new Date()
    int mins = nowD.minutes
    int secs = nowD.seconds
    int minsToNextQuarter = (15 - (mins % 15)) % 15
    int secondsToNextQuarter = minsToNextQuarter * 60 + (60 - secs) + 3
    if (secondsToNextQuarter < 3) secondsToNextQuarter = 3
    runIn(secondsToNextQuarter, "calculateSmartGridState")
}

/* ===================== Child Device ===================== */

def getDeviceDriver() {
    def id = "CTC_GSi12_${app.id}"
    def dev = getChildDevice(id)
    if (!dev) {
        def name = "CTC GSi12 ${app.id}"
        log.info("Creating new child device: ${id}")
        dev = addChildDevice("ctc", "CTC GSi12 SmartGrid Dashboard", id, null, [label: name, name: name])
    }
    return dev
}


def publishDashboard() {
    def drv = getDeviceDriver()
    if (!drv) return

    def tz = location?.timeZone ?: TimeZone.getTimeZone("UTC")

    String mode = (state?.currentSmartGridState ?: "n/a").toString()

    // Find next HIGH_PRICE block from plan metadata
    Map meta = (state?.planMeta instanceof Map) ? (state.planMeta as Map) : [:]
    List blocks = (meta?.blocks instanceof List) ? (meta.blocks as List) : []
    long nowMs = now()

    Map next = blocks.findAll { it?.endEpoch != null && (it.endEpoch as Long) > nowMs }
                     .sort { (it?.startEpoch != null) ? (it.startEpoch as Long) : Long.MAX_VALUE }
                     .find { true }

    String nextHigh = "-"
    if (next?.startEpoch != null && next?.endEpoch != null) {
        // endEpoch is inclusive quarter start; display end+15min for user-friendly interval
        Date sd = new Date(next.startEpoch as Long)
        Date ed = new Date((next.endEpoch as Long) + 15L*60L*1000L)
        nextHigh = "${sd.format('HH:mm', tz)}–${ed.format('HH:mm', tz)}"
    }

    // Current 15-min slot for highlighting/lookup
    Long slotEpoch = _floorToQuarter(nowMs)
    String currentSlot = new Date(slotEpoch).format("HH:mm", tz)

    // Compact daily plan JSON for the dashboard driver (kept small; driver decides what to render)
    String planJson = _buildDashboardPlanJson()

    Map data = [
        smartGridMode : mode,
        nextHigh      : nextHigh,
        updatedAt     : new Date(nowMs).format("HH:mm:ss", tz),
        currentSlot   : currentSlot,
        dailyPlanJson : planJson
    ]

    drv.updateDashboard(data)
}

/**
 * Build a compact JSON list for the dashboard driver:
 *   [{t:"HH:mm", price:123.45, mode:"HIGH_PRICE"}, ...]
 */
private String _buildDashboardPlanJson() {
    try {
        // Expect: state.dailyPlan = Map<Long epochMs, String mode>
        //         state.priceMap = Map<Long epochMs, BigDecimal/Number price>
        def plan = (state.dailyPlan instanceof Map) ? state.dailyPlan : null
        if (!plan) return ""

        def prices = (state.priceMap instanceof Map) ? state.priceMap : [:]
        def tz = location?.timeZone

	    List rows = []
	    // NOTE: Depending on how the plan/price maps were built, epoch keys may be Longs *or* Strings.
	    // Coerce everything to Long safely so dashboards don't spam MissingMethodException.
	    def keys = (plan?.keySet() ?: []) as List
	    keys.sort { a, b ->
	        Long aa = (a instanceof Number) ? ((Number)a).longValue() : a.toString().toLong()
	        Long bb = (b instanceof Number) ? ((Number)b).longValue() : b.toString().toLong()
	        return aa <=> bb
	    }.each { k ->
	        Long epoch = (k instanceof Number) ? ((Number)k).longValue() : k.toString().toLong()
	        String t = new Date(epoch).format("HH:mm", tz)
	        def price = prices[epoch]
	        if (price == null) price = prices[epoch.toString()]
	        String mode = (plan[k] ?: plan[epoch] ?: plan[epoch.toString()] ?: "LOW_PRICE").toString()
	        rows << [t: t, epoch: epoch, price: price, mode: mode]
	    }

        return groovy.json.JsonOutput.toJson(rows)
    } catch (Throwable t) {
        log.warn "_buildDashboardPlanJson failed: ${t}"
        return ""
    }
}

/* ============================================================================
 *  FULL DAILY PLAN PUBLISHING (ADDED)
 * ============================================================================ */

private Map buildDailyPlanPayload() {
    // Payload consumed by the dashboard driver. Keep it explicit and stable.
    Map meta = (state.planMeta ?: [:])
    Map pricesByEpoch = (state.planPricesByEpoch ?: [:])
    List<Map> slots = []

    (state.dailyPlan ?: [:]).each { k, v ->
        Long epoch = null
        try {
            epoch = (k instanceof Number) ? k.longValue() : k.toString().toLong()
        } catch (ignored) {
            epoch = null
        }
        if (epoch == null) return

        Integer priceOre = null
        def p = pricesByEpoch[epoch]
        if (p == null) p = pricesByEpoch[epoch.toString()]
        if (p != null) {
            try { priceOre = (p as BigDecimal).intValue() } catch (ignored2) { priceOre = null }
        }

        String plannedMode = v?.toString()
        // 'mode' is the planned mode in the plan; the driver may later overwrite/compare against live mode.
        slots << [epoch: epoch, priceOre: priceOre, plannedMode: plannedMode, mode: plannedMode]
    }

    slots.sort { a, b -> (a.epoch <=> b.epoch) }

    return [
        dayKey     : state.planDayKey,
        timezone   : location.timeZone?.ID,
        generatedAt: (state.planGeneratedAt ?: now()),
        meta       : meta,
        slots      : slots
    ]
}

private void publishFullDailyPlanToDriver() {
    // Driver interface was simplified to a single updateDashboard(Map) call.
    // Publishing the plan is handled inside publishDashboard().
    publishDashboard()
}


private void publishCurrentPlanPointer() {
    // Kept for backward compatibility: refresh everything via publishDashboard().
    publishDashboard()
}
