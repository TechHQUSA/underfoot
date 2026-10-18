// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.data

import org.underfoot.protocol.FinalSession
import org.underfoot.protocol.Profile

fun FinalSession.toEntity() = SessionEntity(
    startMs = startMs, endMs = endMs, activeSec = activeSec,
    distanceM = distanceM, distanceSource = distanceSource.name,
    steps = steps, stepsSource = stepsSource.name,
    kcal = kcal, kcalSource = kcalSource.name,
)

fun ProfileEntity.toProfile() = Profile(weightKg, heightCm)
