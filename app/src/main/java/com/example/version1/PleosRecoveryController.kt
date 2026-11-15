package com.example.version1

import android.content.Context
import android.content.SharedPreferences
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Typed Pleos SDK calls. A successful setter is always followed by a readback. */
class PleosRecoveryController(
    context: Context,
    private val connectedDriver: () -> DriverSeatBinding?,
    private val uxAllowed: () -> Boolean = { true },
    private val log: (String) -> Unit,
    private val ensureControlConnection: suspend () -> Unit = {}
) {
    private val vehicle = Vehicle(context.applicationContext)
    private val hvac = vehicle.getHVAC()
    private val seat = vehicle.getSeat()
    private val steering = vehicle.getSteeringWheel()
    private val gearApi = vehicle.getGear()
    private val gearPreparation = SdkMetadataPreparation.forApis(gearApi.javaClass)
    internal val apiPreparation = SdkMetadataPreparation.forApis(seat.javaClass, steering.javaClass,hvac.javaClass)
    /** Borrowed read-only API; this controller alone owns and releases the Vehicle connection. */
    internal val occupancySeat: Seat get() = seat
    private val prefs = context.getSharedPreferences("recovery_restore", Context.MODE_PRIVATE)
    private val journal = journalFor(prefs)
    private val celsius = TemperatureUnit.CELSIUS
    private val readCalls = SdkReadCall()
    private data class Change(
        val key: String,
        val area: SeatArea,
        val before: String,
        val after: String,
        val id: String = UUID.randomUUID().toString(),
        val requestId: String = UUID.randomUUID().toString(),
        val phase: RecoveryJournalPhase = RecoveryJournalPhase.APPLY_UNCONFIRMED,
        val operation: VehicleSettingOperation = VehicleSettingOperation.RECOVERY
    )
    data class Feature(val area: SeatArea, val min: Double? = null, val max: Double? = null)
    /** A previous Activity's in-memory list must never hide another instance's journal. */
    val hasPending: Boolean get() = runCatching { journal.hasPending }.getOrDefault(true)
    val pendingOperation: VehicleSettingOperation? get() = runCatching {
        val operations = journal.snapshot().map { it.operation }.distinct()
        if (operations.isEmpty()) null else operations.singleOrNull() ?: VehicleSettingOperation.UNKNOWN
    }.getOrDefault(VehicleSettingOperation.UNKNOWN)

    val pendingSummary: String get() = runCatching {
        val phases = journal.snapshot().map { it.phase }
        when {
            phases.isEmpty() -> ""
            RecoveryJournalPhase.LEGACY_UNCONFIRMED in phases ->
                "이전 버전 요청의 완료 여부를 확인할 수 없어 자동 복원을 보류합니다. 차량 설정을 직접 확인해 주세요."
            RecoveryJournalPhase.APPLY_UNCONFIRMED in phases || RecoveryJournalPhase.RESTORE_UNCONFIRMED in phases ->
                "차량 요청의 완료 응답이 아직 없어 자동 복원을 보류합니다. 늦은 응답 이후 다시 확인해 주세요."
            RecoveryJournalPhase.APPLY_ACKNOWLEDGED in phases ->
                "적용 응답은 받았지만 요청 값 확인이 남아 있습니다. 차량 연결 후 다시 복원해 주세요."
            RecoveryJournalPhase.RESTORE_ACKNOWLEDGED in phases ->
                "복원 응답은 받았지만 원래 값 확인이 남아 있습니다. 차량 연결 후 다시 복원해 주세요."
            else -> "이전 차량 설정 복원이 남아 있습니다."
        }
    }.getOrDefault("복원 기록을 읽지 못해 차량 제어를 보류합니다. 기록을 확인해 주세요.")

    init {
        runCatching { journal.snapshot() }.onFailure { log("저장된 복원 정보 읽기 실패: ${it.javaClass.simpleName}") }
        vehicle.initialize()
    }

    private suspend fun <T> await(preparation: SdkApiPreparation = apiPreparation,
                                  call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T {
        preparation.await()
        return withTimeout(6000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    call({ if (continuation.isActive) continuation.resume(it) },
                        { if (continuation.isActive) continuation.resumeWithException(it) })
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        }
    }

    private suspend fun <T> awaitRead(preparation: SdkApiPreparation = apiPreparation,
                                      call: ((T) -> Unit, (Exception) -> Unit) -> Unit): T {
        preparation.await()
        return readCalls.await(call = call)
    }

    suspend fun gear(): VehicleGear = awaitRead(gearPreparation) { ok, fail ->
        gearApi.getCurrentGearSelectionState({ value ->
            if (value == null) fail(IllegalStateException("기어 정보 없음")) else ok(value)
        }, fail)
    }

    private suspend fun requirePark() {
        ensureControlConnection()
        check(uxAllowed()) { "차량 UX 제한 상태에서 제어할 수 없어요" }
        check(gear() == VehicleGear.GEAR_PARK) { "P단에서만 실행할 수 있어요" }
    }

    private fun driverArea(areas: Collection<SeatArea>, driver: DriverSeatBinding, key: String): SeatArea =
        selectDriverArea(areas, driver.position, key in setOf("heat", "vent", "memory")) { area -> area.values.map { it.name }.toSet() }
            ?: error("운전석 제어 영역 미지원")

    private fun requireAccess(config: CapabilityConfig, writable: Boolean) {
        val permitted = if (writable) config.accessType == AccessType.READ_WRITE
            else config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
        check(permitted) { if (writable) "읽기/쓰기 권한 또는 기능 미지원" else "읽기 권한 또는 기능 미지원" }
    }

    private suspend fun feature(key: String, driver: DriverSeatBinding, writable: Boolean = true): Feature = awaitRead { done, fail ->
        val flag: (CapabilityConfig, List<SeatArea>) -> Unit = { cfg, areas ->
            runCatching { requireAccess(cfg, writable); Feature(driverArea(areas, driver, key)) }
                .onSuccess(done).onFailure { fail(Exception(it.message, it)) }
        }
        val integer: (CapabilityConfig, Map<SeatArea, AreaBounds<Int>>) -> Unit = { cfg, areas ->
            runCatching {
                requireAccess(cfg, writable)
                val area = driverArea(areas.keys, driver, key)
                val bounds = areas.getValue(area)
                Feature(area, bounds.minValue.toDouble(), bounds.maxValue.toDouble())
            }.onSuccess(done).onFailure { fail(Exception(it.message, it)) }
        }
        val decimal: (CapabilityConfig, Map<SeatArea, AreaBounds<Float>>) -> Unit = { cfg, areas ->
            runCatching {
                requireAccess(cfg, writable)
                val area = driverArea(areas.keys, driver, key)
                val bounds = areas.getValue(area)
                Feature(area, bounds.minValue.toDouble(), bounds.maxValue.toDouble())
            }.onSuccess(done).onFailure { fail(Exception(it.message, it)) }
        }
        when (key) {
            "power" -> hvac.checkHvacPowerCapabilities(flag, fail)
            "auto" -> hvac.checkHvacAutoCapabilities(flag, fail)
            "autoRecirc" -> hvac.checkHvacAutoRecirculationCapabilities(flag, fail)
            "recirc" -> hvac.checkHvacRecirculationCapabilities(flag, fail)
            "ac" -> hvac.checkHvacAcCapabilities(flag, fail)
            "fan" -> hvac.checkHvacFanSpeedCapabilities(integer, fail)
            "direction" -> hvac.checkHvacFanDirectionCapabilities({ cfg, areas ->
                runCatching {
                    requireAccess(cfg, writable)
                    Feature(driverArea(areas.keys, driver, key))
                }.onSuccess(done).onFailure { fail(Exception(it)) }
            }, fail)
            "temperature" -> hvac.checkHvacTemperatureCapabilities(decimal, fail)
            "cabinActual" -> hvac.checkHvacTemperatureCurrentCapabilities(flag, fail)
            "heat" -> seat.checkSeatTemperatureCapabilities(integer, fail)
            "vent" -> seat.checkSeatVentilationCapabilities(integer, fail)
            "steeringHeat" -> steering.checkHvacSteeringWheelHeatCapabilities({ cfg, bounds ->
                runCatching {
                    requireAccess(cfg, writable)
                    Feature(SeatArea(SeatValue.valueOf(driver.position.name)), bounds.minValue.toDouble(), bounds.maxValue.toDouble())
                }.onSuccess(done).onFailure { fail(Exception(it)) }
            }, fail)
            else -> fail(IllegalArgumentException(key))
        }
    }

    private suspend fun read(key: String, area: SeatArea): String = awaitRead { ok, fail ->
        val flag: (Boolean?) -> Unit = { if (it == null) fail(Exception("상태 정보 없음")) else ok(it.toString()) }
        val integer: (Int?) -> Unit = { if (it == null) fail(Exception("상태 정보 없음")) else ok(it.toString()) }
        when (key) {
            "power" -> hvac.getHvacPowerState(area, flag, fail)
            "auto" -> hvac.getHvacAutoState(area, flag, fail)
            "autoRecirc" -> hvac.getHvacAutoRecirculationState(area, flag, fail)
            "recirc" -> hvac.getHvacRecirculationState(area, flag, fail)
            "ac" -> hvac.getHvacAcState(area, flag, fail)
            "fan" -> hvac.getHvacFanSpeed(area, integer, fail)
            "direction" -> hvac.getHvacFanDirection(area, { value ->
                if(value == null) fail(Exception("송풍 방향 없음")) else ok(value.name)
            }, fail)
            "heat" -> seat.getHvacSeatTemperature(area, integer, fail)
            "vent" -> seat.getHvacSeatVentilationState(area, integer, fail)
            "steeringHeat" -> steering.getHvacSteeringWheelHeatState(integer, fail)
            "temperature" -> hvac.getHvacTemperature(area, celsius, {
                if (it == null) fail(Exception("온도 정보 없음")) else ok(it.toString())
            }, fail)
            "cabinActual" -> hvac.getHvacTemperatureCurrent(area, {
                if (it == null) fail(Exception("실내 측정 온도 없음")) else ok(it.toString())
            }, fail)
            else -> fail(IllegalArgumentException(key))
        }
    }

    private suspend fun telemetryValue(key: String, driver: DriverSeatBinding): String? = try {
        val support = feature(key, driver, writable = false)
        read(key, support.area).takeIf { value ->
            connectedDriver() === driver && (key != "heat" ||
                value.toIntOrNull()?.let { level ->
                    support.min != null && support.max != null && level.toDouble() in support.min..support.max
                } == true)
        }
    } catch (cancelled: CancellationException) {
        // A property's own timeout is unavailable; cancellation of the caller still propagates.
        if (cancelled is kotlinx.coroutines.TimeoutCancellationException) null else throw cancelled
    } catch (_: Exception) {
        null
    }

    /**
     * Read-only telemetry remains available when recovery is off and reflects outside changes.
     * Vehicle 2.3.2 CarInfo exposes VIN/CRN only, so no model name or cabin geometry is invented.
     */
    suspend fun cabinTelemetry(): CabinTelemetry = coroutineScope {
        val driver = connectedDriver() ?: return@coroutineScope CabinTelemetry(null, null, null, null, null)
        val temperature = async { telemetryValue("temperature", driver)?.toFloatOrNull()?.takeIf(Float::isFinite) }
        val fan = async { telemetryValue("fan", driver)?.toIntOrNull()?.takeIf { it >= 0 } }
        val heat = async { telemetryValue("heat", driver)?.toIntOrNull() }
        val vent = async { telemetryValue("vent", driver)?.toIntOrNull()?.takeIf { it >= 0 } }
        val power = async { telemetryValue("power", driver)?.toBooleanStrictOrNull() }
        val direction = async { CabinAirDirection.fromSdkName(telemetryValue("direction", driver)) }
        val actualCabin = async { telemetryValue("cabinActual", driver)?.toFloatOrNull()?.takeIf { it.isFinite() && it in -40f..85f } }
        val steeringHeat = async {
            try {
                val bounds = awaitRead<AreaBounds<Int>> { ok, fail -> steering.checkHvacSteeringWheelHeatCapabilities({ config, range ->
                    runCatching { requireAccess(config, false); range }.onSuccess(ok).onFailure { fail(Exception(it)) }
                }, fail) }
                val level = awaitRead<Int?> { ok, fail -> steering.getHvacSteeringWheelHeatState(ok, fail) }
                level?.takeIf { it in bounds.minValue..bounds.maxValue } to bounds.maxValue.coerceAtLeast(1)
            } catch (cancelled: CancellationException) {
                if (cancelled is kotlinx.coroutines.TimeoutCancellationException) null to 3 else throw cancelled
            } catch (_: Exception) { null to 3 }
        }
        val snapshot = CabinTelemetry(temperature.await(), fan.await(), heat.await(), vent.await(), power.await(),
            measuredCabinTemperature = actualCabin.await(), steeringHeat = steeringHeat.await().first,
            steeringHeatMax = steeringHeat.await().second, airDirection = direction.await())
        if (connectedDriver() === driver) snapshot else CabinTelemetry(null, null, null, null, null)
    }

    private suspend fun write(change: Change, value: String) {
        val key = change.key
        val area = change.area
        await<Unit> { ok, fail ->
            val acknowledge = {
                // A timeout only ends our wait. A successful late SDK response must
                // still be reconciled against the latest durable id + request id.
                queueAcknowledgement(change)
                ok(Unit)
            }
            val flag: (SeatArea, Boolean?) -> Unit = { _, _ -> acknowledge() }
            val integer: (SeatArea, Int?) -> Unit = { _, _ -> acknowledge() }
            when (key) {
                "power" -> hvac.setHvacPower(area, value.toBooleanStrict(), flag, fail)
                "auto" -> hvac.setHvacAuto(area, value.toBooleanStrict(), flag, fail)
                "autoRecirc" -> hvac.setHvacAutoRecirculation(area, value.toBooleanStrict(), flag, fail)
                "recirc" -> hvac.setHvacRecirculation(area, value.toBooleanStrict(), flag, fail)
                "ac" -> hvac.setHvacAc(area, value.toBooleanStrict(), flag, fail)
                "fan" -> hvac.setHvacFanSpeed(area, value.toInt(), integer, fail)
                "heat" -> seat.setHvacSeatTemperature(area, value.toInt(), integer, fail)
                "vent" -> seat.setHvacSeatVentilation(area, value.toInt(), integer, fail)
                "steeringHeat" -> steering.setHvacSteeringWheelHeat(value.toInt(), { acknowledge() }, fail)
                "temperature" -> hvac.setHvacTemperature(area, value.toFloat(), celsius, { _, _ -> acknowledge() }, fail)
                else -> fail(IllegalArgumentException(key))
            }
        }
    }

    private fun queueAcknowledgement(change: Change) {
        val currentJournal = journal
        RecoveryTransactionGate.reconcileLater {
            currentJournal.transaction { acknowledgeRequest(this, change) }
        }
    }

    private fun acknowledgeRequest(transaction: RecoveryJournalTransaction<Change>, change: Change) {
        val index = transaction.entries.indexOfFirst { it.id == change.id }
        if (index < 0) return
        val latest = transaction.entries[index]
        val phase = acknowledgedRecoveryPhase(latest.requestId, latest.phase, change.requestId, change.phase) ?: return
        transaction.replace(index, transaction.entries[index].copy(phase = phase))
    }

    private fun equivalent(a: String, b: String): Boolean = vehicleValuesEquivalent(a, b)

    private suspend fun verify(key: String, area: SeatArea, expected: String,
                               beforeRead: suspend () -> Unit = {}) =
        verifyVehicleReadback(label(key), expected, { read(key, area) }, ::equivalent, beforeRead)

    suspend fun apply(plan: RecoveryPlan): Int {
        // Disable the mutually exclusive seat function first.
        require(plan.seatCooling in 0..3 && !(plan.seatCooling > 0 && (plan.heat > 0 || plan.ventilation > 0)))
        val seatTargets = if (!plan.controlsSeat) emptyList() else if (plan.seatCooling > 0) listOf("vent" to "0", "heat" to "${-plan.seatCooling}")
            else if (plan.heat > 0) listOf("vent" to "0", "heat" to "${plan.heat}")
            else listOf("heat" to "0", "vent" to "${plan.ventilation}")
        val targets = listOf("power" to "true", "auto" to "false", "autoRecirc" to "false",
            "recirc" to "false", "ac" to "true", "fan" to "${plan.fan}",
            "temperature" to "${plan.temperature}") + seatTargets +
            (plan.steeringHeat?.let { listOf("steeringHeat" to "$it") } ?: emptyList())
        return applyTargets(targets, false)
    }

    private suspend fun applyTargets(targets: List<Pair<String, String>>, permanent: Boolean, stillAllowed: () -> Boolean = { true }): Int = journal.transaction {
        check(stillAllowed()) { "화면 또는 프로필 상태 변경" }
        requirePark()
        val driver = connectedDriver() ?: error("운전석 제어 정보 없음")
        check(entries.isEmpty()) { "이전 설정 복원을 먼저 완료해 주세요" }
        val prepared = mutableListOf<Change>()
        // Read every original value before making any change.
        for ((key, target) in targets) {
            check(stillAllowed()) { "화면 또는 프로필 상태 변경" }
            requirePark()
            check(connectedDriver() === driver) { "운전석 연결 정보가 바뀌었습니다. 다시 확인해 주세요" }
            try {
                val support = feature(key, driver)
                val numeric = target.toDoubleOrNull()
                check(numeric == null || support.min == null || numeric in support.min..support.max!!) {
                    "제안 값 $target 이 차량 지원 범위 밖이에요"
                }
                val original = read(key, support.area)
                prepared += Change(key, support.area, original, target,
                    operation = if (permanent) VehicleSettingOperation.ZERO_TAP else VehicleSettingOperation.RECOVERY)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                log("${label(key)} 제외 · ${e.javaClass.simpleName}")
                if (key == "temperature" || permanent) throw e
            }
        }
        var verified = 0
        for (change in prepared) {
            check(stillAllowed()) { "화면 또는 프로필 상태 변경" }
            requirePark()
            check(connectedDriver() === driver) { "운전석 연결 정보가 바뀌었습니다. 다시 확인해 주세요" }
            if (equivalent(change.before, change.after)) {
                verified++
                log("${label(change.key)} · 이미 요청 값과 일치")
                continue
            }
            // Save BEFORE the setter, including ambiguous failure/timeout cases.
            entries += change
            save()
            write(change, change.after)
            acknowledgeRequest(this, change)
            verify(change.key, change.area, change.after) {
                check(stillAllowed()) { "화면 또는 프로필 상태 변경" }
                requirePark()
                check(connectedDriver() === driver) { "운전석 연결 정보 변경" }
            }
            val index = entries.indexOfFirst { it.id == change.id }
            check(index >= 0) { "적용 확인 중 복원 기록을 찾지 못했습니다" }
            replace(index, entries[index].copy(phase = RecoveryJournalPhase.APPLIED_VERIFIED))
            verified++
            log("${label(change.key)} · 차량 응답 확인")
        }
        if (permanent) {
            check(stillAllowed()) { "화면 또는 프로필 상태 변경" }
            requirePark()
            check(connectedDriver() === driver) { "운전석 연결 정보 변경" }
            // Recheck the complete baseline before committing it as the new normal state.
            for (change in prepared) verify(change.key, change.area, change.after) {
                check(stillAllowed()) { "화면 또는 프로필 상태 변경" }
                requirePark()
                check(connectedDriver() === driver) { "운전석 연결 정보 변경" }
            }
            requirePark()
            check(stillAllowed() && connectedDriver() === driver) { "화면 또는 운전석 연결 변경" }
            check(entries.all { it.phase == RecoveryJournalPhase.APPLIED_VERIFIED })
            entries.clear(); save()
        }
        verified
    }

    suspend fun captureZeroTapBaseline(): JSONObject {
        requirePark()
        check(!hasPending) { "복원을 먼저 완료해 주세요" }
        val driver = connectedDriver() ?: error("운전석 제어 정보 없음")
        val values = JSONObject()
        for (key in listOf("power", "auto", "autoRecirc", "recirc", "ac", "fan", "temperature", "heat", "vent", "steeringHeat")) {
            try {
                val support = feature(key, driver)
                values.put(key, read(key, support.area))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (key == "temperature" || key == "fan") throw error }
        }
        requirePark()
        check(connectedDriver() === driver)
        return JSONObject().put("vehicle", zeroTapVehicleKey()).put("seat", driver.position.name).put("values", values)
    }

    private suspend fun zeroTapVehicleKey(): String {
        val vin = awaitRead<String?> { ok, fail -> vehicle.getCarInfo().getVINInfo(ok, fail) }
        check(!vin.isNullOrBlank()) { "차량 식별 정보가 없어 제로탭 등록을 보류합니다" }
        return zeroTapDigest(vin)
    }

    suspend fun applyZeroTapBaseline(baseline: JSONObject, stillAllowed: () -> Boolean = { true }): Int {
        requirePark()
        val driver = connectedDriver() ?: error("운전석 제어 정보 없음")
        check(baseline.getString("vehicle") == zeroTapVehicleKey() && baseline.getString("seat") == driver.position.name) {
            "등록한 차량 또는 운전석과 다릅니다. 다시 등록해 주세요"
        }
        val values = baseline.getJSONObject("values")
        val seatOrder = if (values.optString("heat").toIntOrNull()?.let { it > 0 } == true) listOf("vent", "heat") else listOf("heat", "vent")
        val targets = (seatOrder + listOf("steeringHeat", "temperature", "fan", "autoRecirc", "recirc", "ac", "auto", "power"))
            .filter(values::has).map { it to values.getString(it) }
        return applyTargets(targets, true, stillAllowed)
    }

    suspend fun zeroTapMemorySlots(): IntRange {
        requirePark()
        val driver = connectedDriver() ?: error("운전석 제어 정보 없음")
        val capability = memoryCapability(driver)
        check(connectedDriver() === driver) { "운전석 연결 정보 변경" }
        return capability.second
    }

    /** Failure callback is observed even if it arrives after the dispatch; no completion is invented. */
    suspend fun requestZeroTapMemory(slot: Int, stillAllowed: () -> Boolean = { true },
                                     onFailure: (String) -> Unit = {}) {
        val driver = connectedDriver() ?: error("운전석 제어 정보 없음")
        val (area, slots) = memoryCapability(driver)
        check(slot in slots) { "지원하지 않는 메모리 번호" }
        requirePark()
        check(stillAllowed() && connectedDriver() === driver) { "화면 또는 운전석 연결 변경" }
        seat.selectSeatMemory(area, slot) {
            val failure = it.javaClass.simpleName
            log("제로탭 메모리 시트 요청 실패 · $failure")
            onFailure(failure)
        }
        log("제로탭 메모리 시트 $slot 요청 · 이동 완료 응답 API 없음")
    }

    private suspend fun memoryCapability(driver: DriverSeatBinding): Pair<SeatArea, IntRange> = awaitRead { ok, fail ->
        seat.checkSeatMemoryCapabilities({ cfg, areas ->
            runCatching {
                check(cfg.accessType == AccessType.WRITE || cfg.accessType == AccessType.READ_WRITE) { "메모리 시트 쓰기 미지원" }
                val area = driverArea(areas.keys, driver, "memory")
                val bounds = areas.getValue(area)
                area to (bounds.minValue..bounds.maxValue)
            }.onSuccess(ok).onFailure { fail(Exception(it)) }
        }, fail)
    }

    suspend fun restore(): Boolean = journal.transaction {
        if (entries.isEmpty()) return@transaction true
        requirePark()
        for (change in entries.toList().asReversed()) {
            requirePark()
            try {
                val current = try { Result.success(read(change.key, change.area)) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                when (decideJournalRestore(change.phase, change.before, change.after, current, ::equivalent)) {
                    RestoreDecision.RETRY -> throw current.exceptionOrNull()
                        ?: IllegalStateException("현재 차량 상태 조회 실패")
                    RestoreDecision.WAIT_FOR_REQUEST -> log("${label(change.key)} 복원 보류 · ${phaseReason(change.phase)}")
                    RestoreDecision.ALREADY_RESTORED -> finishRestore(this, change,
                        "${label(change.key)} · 이미 원래 값 ${change.before} · 복원 기록 정리")
                    RestoreDecision.PRESERVE_EXTERNAL_CHANGE -> finishRestore(this, change,
                        "${label(change.key)} · 사용자/차량 변경 값 ${current.getOrThrow()} 유지 · 복원 기록 정리")
                    RestoreDecision.RESTORE_ORIGINAL -> {
                        requirePark()
                        val restoring = change.copy(requestId = UUID.randomUUID().toString(),
                            phase = RecoveryJournalPhase.RESTORE_UNCONFIRMED)
                        val index = entries.indexOfFirst { it.id == change.id }
                        check(index >= 0) { "복원 요청 전 기록을 찾지 못했습니다" }
                        replace(index, restoring)
                        write(restoring, change.before)
                        acknowledgeRequest(this, restoring)
                        verify(change.key, change.area, change.before)
                        finishRestore(this, restoring,
                            "${label(change.key)} · 원래 값 ${change.before} 복원 확인")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) { log("${label(change.key)} 복원 대기 · ${e.javaClass.simpleName}") }
        }
        entries.isEmpty()
    }

    private fun finishRestore(transaction: RecoveryJournalTransaction<Change>, change: Change, message: String) {
        val latest = transaction.entries.firstOrNull { it.id == change.id } ?: return
        transaction.remove(latest)
        log(message)
    }

    fun release() { vehicle.release() }
    companion object {
        private const val JOURNAL_VERSION = 1
        private val journalKeys = setOf("power", "auto", "autoRecirc", "recirc", "ac", "fan",
            "temperature", "heat", "vent", "steeringHeat")
        private val journalStorageGate = Any()
        private val journalProtection = RecoveryJournalProtection()

        private fun journalFor(preferences: SharedPreferences): SerializedRecoveryJournal<Change> =
            SerializedRecoveryJournal(object : RecoveryJournalStore<Change> {
                override fun hasPending(): Boolean = synchronized(journalStorageGate) {
                    RecoveryJournalPresence.mayContainEntries(preferences.getString("pending", "[]"))
                }
                override fun read(): List<Change> = synchronized(journalStorageGate) {
                    val raw = preferences.getString("pending", "[]") ?: "[]"
                    val (saved, legacy) = journalProtection.decode(raw)
                    check(saved.length() <= 64) { "복원 기록 개수 초과" }
                    (0 until saved.length()).map { index ->
                        val item = saved.getJSONObject(index)
                        val version = item.optInt("journalVersion", 0)
                        check(version in 0..JOURNAL_VERSION) { "지원하지 않는 복원 기록 버전" }
                        val key = item.getString("key")
                        check(key in journalKeys) { "알 수 없는 복원 항목" }
                        val areas = item.getString("area").split(",").map { SeatValue.valueOf(it) }.toTypedArray()
                        check(areas.isNotEmpty() && areas.size <= SeatValue.entries.size) { "복원 좌석 영역 오류" }
                        val legacyId = UUID.nameUUIDFromBytes("$index:$item".toByteArray(Charsets.UTF_8)).toString()
                        val phase = recoveryJournalPhaseFromStoredName(if (item.has("phase")) item.getString("phase") else null)
                        Change(key, SeatArea(*areas), item.getString("before"), item.getString("after"),
                            item.optString("id", legacyId), item.optString("requestId", legacyId), phase,
                            vehicleSettingOperationFromStored(if (item.has("operation")) item.getString("operation") else null))
                    }.also { entries ->
                        check(entries.map { it.id }.toSet().size == entries.size) { "중복된 복원 기록 ID" }
                        if (legacy) write(entries)
                    }
                }

                override fun write(entries: List<Change>) = synchronized(journalStorageGate) {
                    val previous = preferences.getString("pending", "[]") ?: "[]"
                    val array = JSONArray()
                    entries.forEach { change -> array.put(JSONObject().put("key", change.key)
                        .put("area", change.area.values.joinToString(",") { it.name })
                        .put("before", change.before).put("after", change.after)
                        .put("journalVersion", JOURNAL_VERSION).put("id", change.id)
                        .put("requestId", change.requestId).put("phase", change.phase.name)
                        .put("operation", change.operation.name)) }
                    if (!preferences.edit().putString("pending", journalProtection.encode(array)).commit()) {
                        // SharedPreferences updates its memory map even when disk commit
                        // fails. Restore the visible old journal as well as the transaction
                        // list, so hasPending cannot advertise a failed deletion as success.
                        preferences.edit().putString("pending", previous).commit()
                        error("복원 정보 저장 실패 · 이전 기록을 유지합니다")
                    }
                }
            })

        private fun phaseReason(phase: RecoveryJournalPhase): String = when (phase) {
            RecoveryJournalPhase.LEGACY_UNCONFIRMED -> "이전 버전 요청의 완료 여부를 확인할 수 없습니다. 차량 설정을 직접 확인해 주세요."
            RecoveryJournalPhase.APPLY_UNCONFIRMED,
            RecoveryJournalPhase.RESTORE_UNCONFIRMED -> "요청 완료 미확정 · 늦은 차량 응답이 남아 있을 수 있어 기록을 유지합니다."
            RecoveryJournalPhase.APPLY_ACKNOWLEDGED -> "적용 응답 이후 요청 값 확인이 남아 있습니다."
            RecoveryJournalPhase.RESTORE_ACKNOWLEDGED -> "복원 응답 이후 원래 값 확인이 남아 있습니다."
            RecoveryJournalPhase.APPLIED_VERIFIED -> "차량 상태를 다시 확인해 주세요."
        }

        fun label(key: String) = when (key) {
            "power" -> "공조 전원"; "auto" -> "자동 공조"; "autoRecirc" -> "자동 순환"
            "recirc" -> "내기 순환"; "ac" -> "A/C"; "fan" -> "풍량"
            "temperature" -> "설정 온도"; "heat" -> "시트 열선"; "vent" -> "시트 통풍"
            "steeringHeat" -> "핸들 열선"
            else -> key
        }
    }
}
