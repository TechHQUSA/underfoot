// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 TechHQ

package org.underfoot.protocol

/** A walk's pauses as stored with the walk: "start-end,start-end" in epoch ms, empty when it had none. */
sealed interface PauseList {
    data class Valid(val pauses: List<Pair<Long, Long>>) : PauseList
    data class Invalid(val reason: String) : PauseList

    companion object {
        fun encode(pauses: List<Pair<Long, Long>>): String = pauses.joinToString(",") { (a, b) -> "$a-$b" }

        /** Damaged data is Invalid, never "no pauses": treating it as unpaused would cover the pauses again. */
        fun decode(s: String, startMs: Long, endMs: Long): PauseList {
            if (s.isEmpty()) return Valid(emptyList())
            val out = mutableListOf<Pair<Long, Long>>()
            for (part in s.split(',')) {
                val ab = part.split('-')
                val a = ab.getOrNull(0)?.toLongOrNull(); val b = ab.getOrNull(1)?.toLongOrNull()
                if (ab.size != 2 || a == null || b == null) return Invalid("unreadable pause '$part'")
                if (b <= a) return Invalid("empty pause $a-$b")
                if (a < startMs || b > endMs) return Invalid("pause $a-$b outside the walk $startMs-$endMs")
                if (out.isNotEmpty() && a < out.last().second) return Invalid("pause $a-$b overlaps or is out of order")
                out += a to b
            }
            return Valid(out)
        }
    }
}

/**
 * What a walk becomes in Health Connect. Health Connect credits steps per time span to the app highest in its priority list, so
 * Underfoot covers only the stretches the belt moved: in a pause, steps from another app (a watch) count. Plain data, so the ids,
 * spans and values are tested here and the gateway only maps them to Health Connect records.
 */
data class HealthPlan(val session: Session, val steps: List<Amount>, val distance: List<Amount>, val kcal: List<Amount>) {
    data class Session(val id: String, val startMs: Long, val endMs: Long, val pauses: List<Pair<Long, Long>>)
    data class Amount(val id: String, val startMs: Long, val endMs: Long, val value: Double)

    companion object {
        /**
         * `pauses` must be valid for the walk (see [PauseList.decode]). A walk without pauses keeps the single records and ids it always
         * had; with pauses, each moving stretch n gets `<kind>-<n>`. Totals are shared by stretch duration and add up exactly.
         */
        fun of(idPrefix: String, startMs: Long, endMs: Long, pauses: List<Pair<Long, Long>>, steps: Int, distanceM: Double, kcal: Double): HealthPlan {
            val end = maxOf(endMs, startMs + 1000)                 // Health Connect needs start before end
            val stretches = mutableListOf<Pair<Long, Long>>()
            var from = startMs
            for ((a, b) in pauses) { if (a > from) stretches += from to a; from = maxOf(from, b) }
            if (end > from) stretches += from to end
            fun id(kind: String, n: Int) = if (pauses.isEmpty()) "$idPrefix-$kind" else "$idPrefix-$kind-$n"
            fun amounts(kind: String, values: List<Double>) =
                stretches.indices.filter { values[it] > 0.0 }.map { Amount(id(kind, it), stretches[it].first, stretches[it].second, values[it]) }
            val durations = stretches.map { (a, b) -> b - a }
            return HealthPlan(
                Session("$idPrefix-exercise", startMs, end, pauses),
                amounts("steps", shareSteps(steps, durations).map { it.toDouble() }),
                amounts("distance", share(distanceM, durations)),
                amounts("kcal", share(kcal, durations)),
            )
        }

        /** Whole steps by duration (largest remainder), then every stretch gets at least one if there are enough, taken from the largest. */
        private fun shareSteps(total: Int, durations: List<Long>): List<Long> {
            val n = durations.size; val sum = durations.sum()
            if (total <= 0 || n == 0 || sum <= 0) return List(n) { 0L }
            if (total < n) {                                       // not one each: the longest stretches get one
                val longest = durations.indices.sortedWith(compareByDescending<Int> { durations[it] }.thenBy { it }).take(total).toSet()
                return List(n) { if (it in longest) 1L else 0L }
            }
            val out = LongArray(n) { total.toLong() * durations[it] / sum }
            val left = total - out.sum()
            durations.indices.sortedWith(compareByDescending<Int> { total.toLong() * durations[it] % sum }.thenBy { it })
                .take(left.toInt()).forEach { out[it]++ }
            for (i in 0 until n) if (out[i] == 0L) { out[out.indices.maxBy { out[it] }]--; out[i] = 1 }
            return out.toList()
        }

        private fun share(total: Double, durations: List<Long>): List<Double> {
            val n = durations.size; val sum = durations.sum()
            if (!total.isFinite() || total <= 0.0 || n == 0 || sum <= 0) return List(n) { 0.0 }
            val out = DoubleArray(n) { total * durations[it] / sum }
            out[n - 1] = maxOf(0.0, total - out.take(n - 1).sum())  // the last takes the rounding, so the parts add up to the total
            return out.toList()
        }
    }
}
