package net.prototux.aare.car

import aap_protobuf.service.navigationstatus.message.NavigationCueOuterClass.NavigationCue
import aap_protobuf.service.navigationstatus.message.NavigationCurrentPositionOuterClass.NavigationCurrentPosition
import aap_protobuf.service.navigationstatus.message.NavigationDestinationDistanceOuterClass.NavigationDestinationDistance
import aap_protobuf.service.navigationstatus.message.NavigationDestinationOuterClass.NavigationDestination
import aap_protobuf.service.navigationstatus.message.NavigationDistanceOuterClass.NavigationDistance
import aap_protobuf.service.navigationstatus.message.NavigationManeuverOuterClass.NavigationManeuver
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnDistanceEventOuterClass.NavigationNextTurnDistanceEvent
import aap_protobuf.service.navigationstatus.message.NavigationNextTurnEventOuterClass.NavigationNextTurnEvent
import aap_protobuf.service.navigationstatus.message.NavigationRoadOuterClass.NavigationRoad
import aap_protobuf.service.navigationstatus.message.NavigationStateOuterClass.NavigationState
import aap_protobuf.service.navigationstatus.message.NavigationStatusOuterClass.NavigationStatus
import aap_protobuf.service.navigationstatus.message.NavigationStepDistanceOuterClass.NavigationStepDistance
import aap_protobuf.service.navigationstatus.message.NavigationStepOuterClass.NavigationStep
import androidx.car.app.model.Distance
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.Trip
import com.google.protobuf.ByteString
import net.prototux.aare.aap.channels.NavigationStatusChannel
import java.text.DateFormat
import java.util.Date

/**
 * Sends the navigation app's trip to the head unit (instrument cluster, head-up display, the
 * car's own widgets). The Car App Library's maneuver types and distance units use the same
 * numbering as the protocol, so most of this is copying fields.
 */
class NavStatusBridge(private val channel: NavigationStatusChannel, private val turnImage: (Maneuver, Int, Int) -> ByteArray?) {
    private var active = false

    fun update(trip: Trip?) {
        if (trip == null || trip.steps.isEmpty()) {
            if (active) {
                active = false
                channel.status(NavigationStatus.NavigationStatusEnum.INACTIVE)
            }
            return
        }
        if (!active) {
            active = true
            channel.status(NavigationStatus.NavigationStatusEnum.ACTIVE)
        }
        if (trip.isLoading) {
            channel.status(NavigationStatus.NavigationStatusEnum.REROUTING)
            return
        }
        val step = trip.steps.first()
        val maneuver = step.maneuver
        val stepEstimate = trip.stepTravelEstimates.firstOrNull()
        val destEstimate = trip.destinationTravelEstimates.firstOrNull()
        val road = step.road?.toString() ?: trip.currentRoad?.toString() ?: ""

        val state = NavigationState.newBuilder()
        trip.steps.take(3).forEach { s ->
            state.addSteps(NavigationStep.newBuilder().apply {
                s.maneuver?.let { setManeuver(maneuverOf(it)) }
                s.road?.let { setRoad(NavigationRoad.newBuilder().setName(it.toString())) }
                s.cue?.let { setCue(NavigationCue.newBuilder().addAlternateText(it.toString())) }
            })
        }
        trip.destinations.forEach { d ->
            state.addDestinations(NavigationDestination.newBuilder().setAddress(listOfNotNull(d.name, d.address).joinToString(", ")))
        }

        val position = NavigationCurrentPosition.newBuilder()
        stepEstimate?.let { e ->
            position.setStepDistance(NavigationStepDistance.newBuilder().apply {
                e.remainingDistance?.let { setDistance(distanceOf(it)) }
                setTimeToStepSeconds(e.remainingTimeSeconds)
            })
        }
        destEstimate?.let { e ->
            position.addDestinationDistances(NavigationDestinationDistance.newBuilder()
                .apply { e.remainingDistance?.let { setDistance(distanceOf(it)) } }
                .setTimeToArrivalSeconds(e.remainingTimeSeconds)
                .setEstimatedTimeAtArrival(e.arrivalTimeAtDestination?.let {
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.timeSinceEpochMillis))
                } ?: ""))
        }
        trip.currentRoad?.let { position.setCurrentRoad(NavigationRoad.newBuilder().setName(it.toString())) }

        // The older events most head units still use for their cluster.
        val turn = NavigationNextTurnEvent.newBuilder().setRoad(road)
        maneuver?.let { m ->
            turn.setEvent(legacyEvent(m.type)).setTurnSide(sideOf(m.type))
            if (m.roundaboutExitNumber > 0) turn.setTurnNumber(m.roundaboutExitNumber)
            if (m.roundaboutExitAngle > 0) turn.setTurnAngle(m.roundaboutExitAngle)
            if (channel.wantsImages) {
                val o = channel.imageOptions
                turnImage(m, o.width.takeIf { it > 0 } ?: 64, o.height.takeIf { it > 0 } ?: 64)?.let { turn.setImage(ByteString.copyFrom(it)) }
            }
        }
        val distance = stepEstimate?.let { e ->
            val d = e.remainingDistance ?: return@let null
            NavigationNextTurnDistanceEvent.newBuilder()
                .setDistanceMeters(metersOf(d).toInt())
                .setTimeToTurnSeconds(e.remainingTimeSeconds.toInt().coerceAtLeast(0))
                .setDisplayDistanceE3((d.displayDistance * 1000).toInt())
                .setDisplayDistanceUnit(NavigationNextTurnDistanceEvent.DistanceUnits.forNumber(d.displayUnit)
                    ?: NavigationNextTurnDistanceEvent.DistanceUnits.UNKNOWN_DISTANCE_UNIT)
                .build()
        }
        channel.update(state.build(), position.build(), turn.build(), distance)
    }

    private fun maneuverOf(m: Maneuver): NavigationManeuver = NavigationManeuver.newBuilder().apply {
        setType(NavigationManeuver.NavigationType.forNumber(m.type) ?: NavigationManeuver.NavigationType.UNKNOWN)
        if (m.roundaboutExitNumber > 0) setRoundaboutExitNumber(m.roundaboutExitNumber)
        if (m.roundaboutExitAngle > 0) setRoundaboutExitAngle(m.roundaboutExitAngle)
    }.build()

    private fun distanceOf(d: Distance): NavigationDistance = NavigationDistance.newBuilder()
        .setMeters(metersOf(d).toInt())
        .setDisplayValue(formatDisplay(d))
        .setDisplayUnits(NavigationDistance.DistanceUnits.forNumber(d.displayUnit) ?: NavigationDistance.DistanceUnits.UNKNOWN_DISTANCE_UNIT)
        .build()

    companion object {
        fun metersOf(d: Distance): Double = d.displayDistance * when (d.displayUnit) {
            Distance.UNIT_KILOMETERS, Distance.UNIT_KILOMETERS_P1 -> 1000.0
            Distance.UNIT_MILES, Distance.UNIT_MILES_P1 -> 1609.344
            Distance.UNIT_FEET -> 0.3048
            Distance.UNIT_YARDS -> 0.9144
            else -> 1.0
        }

        fun formatDisplay(d: Distance): String = when (d.displayUnit) {
            Distance.UNIT_KILOMETERS_P1, Distance.UNIT_MILES_P1 -> "%.1f".format(d.displayDistance)
            else -> d.displayDistance.toLong().toString()
        }

        private fun sideOf(type: Int): NavigationNextTurnEvent.TurnSide {
            val name = maneuverName(type)
            return when {
                "LEFT" in name || "_CW" in name && "CCW" !in name -> NavigationNextTurnEvent.TurnSide.LEFT
                "RIGHT" in name || "CCW" in name -> NavigationNextTurnEvent.TurnSide.RIGHT
                else -> NavigationNextTurnEvent.TurnSide.UNSPECIFIED
            }
        }

        private fun legacyEvent(type: Int): NavigationNextTurnEvent.NextTurnEnum {
            val n = maneuverName(type)
            return when {
                n == "DEPART" -> NavigationNextTurnEvent.NextTurnEnum.DEPART
                n == "NAME_CHANGE" -> NavigationNextTurnEvent.NextTurnEnum.NAME_CHANGE
                n.startsWith("KEEP") || n.startsWith("TURN_SLIGHT") -> NavigationNextTurnEvent.NextTurnEnum.SLIGHT_TURN
                n.startsWith("TURN_NORMAL") -> NavigationNextTurnEvent.NextTurnEnum.TURN
                n.startsWith("TURN_SHARP") -> NavigationNextTurnEvent.NextTurnEnum.SHARP_TURN
                n.startsWith("U_TURN") -> NavigationNextTurnEvent.NextTurnEnum.U_TURN
                n.startsWith("ON_RAMP") -> NavigationNextTurnEvent.NextTurnEnum.ON_RAMP
                n.startsWith("OFF_RAMP") -> NavigationNextTurnEvent.NextTurnEnum.OFF_RAMP
                n.startsWith("FORK") -> NavigationNextTurnEvent.NextTurnEnum.FORK
                n.startsWith("MERGE") -> NavigationNextTurnEvent.NextTurnEnum.MERGE
                n == "ROUNDABOUT_ENTER" -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_ENTER
                n == "ROUNDABOUT_EXIT" -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_EXIT
                n.startsWith("ROUNDABOUT") -> NavigationNextTurnEvent.NextTurnEnum.ROUNDABOUT_ENTER_AND_EXIT
                n == "STRAIGHT" -> NavigationNextTurnEvent.NextTurnEnum.STRAIGHT
                n == "FERRY_BOAT" -> NavigationNextTurnEvent.NextTurnEnum.FERRY_BOAT
                n == "FERRY_TRAIN" -> NavigationNextTurnEvent.NextTurnEnum.FERRY_TRAIN
                n.startsWith("DESTINATION") -> NavigationNextTurnEvent.NextTurnEnum.DESTINATION
                else -> NavigationNextTurnEvent.NextTurnEnum.UNKNOWN
            }
        }

        /** Maneuver.TYPE_* and the protocol's NavigationType share numbers; use the protocol's names. */
        private fun maneuverName(type: Int): String = NavigationManeuver.NavigationType.forNumber(type)?.name ?: "UNKNOWN"
    }
}
