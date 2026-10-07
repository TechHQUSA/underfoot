package org.walkpadhealth.data

import org.walkpadhealth.protocol.FinalSession
import org.walkpadhealth.protocol.Profile

fun FinalSession.toEntity() = SessionEntity(
    startMs = startMs, endMs = endMs, activeSec = activeSec,
    distanceM = distanceM, distanceSource = distanceSource.name,
    steps = steps, stepsSource = stepsSource.name,
    kcal = kcal, kcalSource = kcalSource.name,
)

fun ProfileEntity.toProfile() = Profile(weightKg, heightCm)
