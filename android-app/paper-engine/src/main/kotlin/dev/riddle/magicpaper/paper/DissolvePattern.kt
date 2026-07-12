package dev.riddle.magicpaper.paper

class DissolvePattern(val stages: Int = 14) {
    init {
        require(stages > 0) { "stages must be positive" }
    }

    fun shouldErase(x: Int, y: Int, stage: Int): Boolean {
        if (stage < 0) return false
        if (stage >= stages - 1) return true
        return pixelHash(x, y).mod(stages) <= stage
    }

    private fun pixelHash(x: Int, y: Int): Int {
        var hash = x * 0x9E3779B1.toInt() xor (y * 0x85EBCA6B.toInt())
        hash = hash xor (hash ushr 13)
        hash *= 0xC2B2AE35.toInt()
        return hash xor (hash ushr 16)
    }
}
