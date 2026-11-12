package io.hammerhead.karooexttemplate.service

import java.util.ArrayDeque

class RollingAverage(private val windowMillis: Long) {
    private val samples = ArrayDeque<Pair<Long, Double>>()

    @Synchronized
    fun add(timestampMs: Long, value: Double) {
        samples.addLast(timestampMs to value)
        evict(timestampMs)
    }

    @Synchronized
    fun current(): Double? {
        evict(System.currentTimeMillis())
        if (samples.isEmpty()) return null
        return samples.sumOf { it.second } / samples.size
    }

    @Synchronized
    fun clear() {
        samples.clear()
    }

    private fun evict(nowMs: Long) {
        while (samples.isNotEmpty() && nowMs - samples.first().first > windowMillis) {
            samples.removeFirst()
        }
    }
}
