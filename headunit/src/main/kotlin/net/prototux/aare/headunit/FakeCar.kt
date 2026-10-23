package net.prototux.aare.headunit

import aap_protobuf.service.sensorsource.message.AccelerometerDataOuterClass.AccelerometerData
import aap_protobuf.service.sensorsource.message.CompassDataOuterClass.CompassData
import aap_protobuf.service.sensorsource.message.DeadReckoningDataOuterClass.DeadReckoningData
import aap_protobuf.service.sensorsource.message.DoorDataOuterClass.DoorData
import aap_protobuf.service.sensorsource.message.EnvironmentDataOuterClass.EnvironmentData
import aap_protobuf.service.sensorsource.message.FuelDataOuterClass.FuelData
import aap_protobuf.service.sensorsource.message.FuelTypeOuterClass.FuelType
import aap_protobuf.service.sensorsource.message.GpsSatelliteOuterClass.GpsSatelliteData
import aap_protobuf.service.sensorsource.message.GyroscopeDataOuterClass.GyroscopeData
import aap_protobuf.service.sensorsource.message.HeadLightStateOuterClass.HeadLightState
import aap_protobuf.service.sensorsource.message.HvacDataOuterClass.HvacData
import aap_protobuf.service.sensorsource.message.LightDataOuterClass.LightData
import aap_protobuf.service.sensorsource.message.OdometerDataOuterClass.OdometerData
import aap_protobuf.service.sensorsource.message.PassengerDataOuterClass.PassengerData
import aap_protobuf.service.sensorsource.message.RpmDataOuterClass.RpmData
import aap_protobuf.service.sensorsource.message.SensorBatchOuterClass.SensorBatch
import aap_protobuf.service.sensorsource.message.SensorTypeOuterClass.SensorType
import aap_protobuf.service.sensorsource.message.TirePressureDataOuterClass.TirePressureData
import aap_protobuf.service.sensorsource.message.TollCardDataOuterClass.TollCardData
import aap_protobuf.service.sensorsource.message.TurnIndicatorStateOuterClass.TurnIndicatorState
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.sin

/**
 * A plausible petrol car behind the desktop head unit's sensors, so the phone side (car apps'
 * CarInfo/CarSensors, the assistant) can be tried without a car: engine speed, odometer and fuel
 * that follow the simulated drive, weather, climate, doors, lights, tyres, motion sensors.
 * One rear tyre is a little flat and the driver's door opens when parked, to exercise warnings.
 */
class FakeCar {
    private val started = AtomicBoolean(false)
    private var odometerKm = 48_213.4
    private var tripKm = 0.0
    private var fuelPercent = 64.0
    private var cabinC = 27.0
    private var lastTickMs = 0L
    private var lastSpeed = 0.0
    private var lastBearing: Double? = null

    /** Sends [tick] every second from the first request on (idempotent). */
    fun start(timer: ScheduledExecutorService, tick: () -> Unit) {
        if (started.compareAndSet(false, true)) runCatching { timer.scheduleAtFixedRate(tick, 1, 1, TimeUnit.SECONDS) }
    }

    /** The next sample of every wanted sensor this car fakes, or null if none is wanted. Timer thread only. */
    fun batch(wanted: Set<SensorType>, fix: Fix?, driving: Boolean, night: Boolean): SensorBatch.Builder? {
        val now = System.currentTimeMillis()
        val dt = if (lastTickMs == 0L) 1.0 else ((now - lastTickMs) / 1000.0).coerceIn(0.1, 5.0)
        lastTickMs = now
        val speed = if (driving) fix?.speed ?: 0.0 else 0.0
        val accel = (speed - lastSpeed) / dt
        lastSpeed = speed
        val bearing = fix?.bearing
        val yawRate = if (bearing != null && lastBearing != null) Math.toRadians(((bearing - lastBearing!! + 540) % 360) - 180) / dt else 0.0
        lastBearing = bearing
        val km = speed * dt / 1000
        odometerKm += km; tripKm += km
        fuelPercent = (fuelPercent - km * 0.12).coerceAtLeast(3.0) // 6 l/100 km from a 50 l tank
        cabinC += (21.0 - cabinC) * 0.02 * dt
        val wave = sin(now / 600_000.0 * 2 * PI) // slow drift over ten minutes

        val b = SensorBatch.newBuilder()
        fun want(t: SensorType) = t in wanted
        if (want(SensorType.SENSOR_RPM)) {
            val rpm = if (speed < 0.5) 780.0 else (900 + speed * 55).coerceAtMost(3200.0)
            b.addRpmData(RpmData.newBuilder().setRpmE3((rpm * 1e3).toInt()))
        }
        if (want(SensorType.SENSOR_ODOMETER)) b.addOdometerData(OdometerData.newBuilder().setKmsE1((odometerKm * 10).toInt()).setTripKmsE1((tripKm * 10).toInt()))
        if (want(SensorType.SENSOR_FUEL)) {
            // range: km (see CarData.Fuel on the phone side).
            b.addFuelData(FuelData.newBuilder().setFuelLevel(fuelPercent.toInt()).setRange((fuelPercent * 50 / 100 / 6 * 100).toInt())
                .setLowFuelWarning(fuelPercent < 10))
        }
        if (want(SensorType.SENSOR_ENVIRONMENT_DATA)) {
            b.addEnvironmentData(EnvironmentData.newBuilder().setTemperatureE3(((18.5 + 1.5 * wave) * 1e3).toInt())
                .setPressureE3(101_300).setRain(0))
        }
        if (want(SensorType.SENSOR_HVAC_DATA)) {
            b.addHvacData(HvacData.newBuilder().setTargetTemperatureE3(21_000).setCurrentTemperatureE3((cabinC * 1e3).toInt()))
        }
        if (want(SensorType.SENSOR_DOOR_DATA)) {
            b.addDoorData(DoorData.newBuilder().setHoodOpen(false).setTrunkOpen(false)
                .addDoorOpen(!driving).addDoorOpen(false).addDoorOpen(false).addDoorOpen(false))
        }
        if (want(SensorType.SENSOR_LIGHT_DATA)) {
            val turn = when {
                yawRate > 0.05 -> TurnIndicatorState.TURN_INDICATOR_RIGHT
                yawRate < -0.05 -> TurnIndicatorState.TURN_INDICATOR_LEFT
                else -> TurnIndicatorState.TURN_INDICATOR_NONE
            }
            b.addLightData(LightData.newBuilder().setHeadLightState(if (night) HeadLightState.HEAD_LIGHT_STATE_ON else HeadLightState.HEAD_LIGHT_STATE_OFF)
                .setTurnIndicatorState(turn).setHazardLightsOn(false))
        }
        // kPa × 100: front left, front right, rear left, rear right (a bit flat).
        if (want(SensorType.SENSOR_TIRE_PRESSURE_DATA)) b.addTirePressureData(TirePressureData.newBuilder().addAllTirePressuresE2(listOf(23_000, 23_100, 22_000, 18_500)))
        if (want(SensorType.SENSOR_ACCELEROMETER_DATA)) {
            // x: lateral (centripetal), y: longitudinal, z: gravity; m/s² × 1000.
            b.addAccelerometerData(AccelerometerData.newBuilder().setAccelerationXE3((speed * yawRate * 1e3).toInt())
                .setAccelerationYE3((accel * 1e3).toInt()).setAccelerationZE3(9_810))
        }
        if (want(SensorType.SENSOR_GYROSCOPE_DATA)) {
            b.addGyroscopeData(GyroscopeData.newBuilder().setRotationSpeedXE3(0).setRotationSpeedYE3(0).setRotationSpeedZE3((yawRate * 1e3).toInt()))
        }
        if (want(SensorType.SENSOR_COMPASS) && bearing != null) b.addCompassData(CompassData.newBuilder().setBearingE6((bearing * 1e6).toInt()).setPitchE6(0).setRollE6(0))
        if (want(SensorType.SENSOR_DEAD_RECKONING_DATA)) {
            // Bicycle model, 2.7 m wheelbase: steering angle from the yaw rate.
            val steer = if (speed > 0.5) Math.toDegrees(kotlin.math.atan(2.7 * yawRate / speed)) * 15 else 0.0
            b.addDeadReckoningData(DeadReckoningData.newBuilder().setSteeringAngleE1((steer * 10).toInt())
                .addAllWheelSpeedE3(List(4) { (speed * 1e3).toInt() }))
        }
        if (want(SensorType.SENSOR_GPS_SATELLITE_DATA)) b.addGpsSatelliteData(GpsSatelliteData.newBuilder().setNumberInUse(9).setNumberInView(14))
        if (want(SensorType.SENSOR_PASSENGER_DATA)) b.addPassengerData(PassengerData.newBuilder().setPassengerPresent(false))
        if (want(SensorType.SENSOR_TOLL_CARD)) b.addTollCardData(TollCardData.newBuilder().setIsCardPresent(true))
        return b.takeIf { it.build().serializedSize > 0 }
    }

    companion object {
        /** Advertised on top of the head unit's basic sensors. */
        val SENSORS = listOf(
            SensorType.SENSOR_COMPASS, SensorType.SENSOR_RPM, SensorType.SENSOR_ODOMETER, SensorType.SENSOR_FUEL,
            SensorType.SENSOR_ENVIRONMENT_DATA, SensorType.SENSOR_HVAC_DATA, SensorType.SENSOR_DEAD_RECKONING_DATA,
            SensorType.SENSOR_PASSENGER_DATA, SensorType.SENSOR_DOOR_DATA, SensorType.SENSOR_LIGHT_DATA,
            SensorType.SENSOR_TIRE_PRESSURE_DATA, SensorType.SENSOR_ACCELEROMETER_DATA, SensorType.SENSOR_GYROSCOPE_DATA,
            SensorType.SENSOR_GPS_SATELLITE_DATA, SensorType.SENSOR_TOLL_CARD,
        )
        val FUEL_TYPES = listOf(FuelType.FUEL_TYPE_UNLEADED)
    }
}
