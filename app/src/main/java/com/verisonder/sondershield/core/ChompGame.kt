package com.verisonder.sondershield.core

import kotlin.math.abs
import kotlin.math.hypot

/*
 * Shield-Man: the easter-egg game. You are the shield; ads wander the maze and are always
 * fair game, malware hunts you. Eat a power shield and the malware turns edible for a while.
 * Clear every dot to move up a level. Pure Kotlin (no Android), so it can be tested alone.
 */

enum class Dir(val dx: Int, val dy: Int) {
    NONE(0, 0), UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite: Dir
        get() = when (this) { UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT; NONE -> NONE }
}

/** Something that walks the maze tile by tile: on tile ([tx], [ty]), [f] of the way to the next one. */
class Mover(var tx: Int, var ty: Int, var dir: Dir = Dir.NONE) {
    var f = 0f
    val x: Float get() = tx + dir.dx * f
    val y: Float get() = ty + dir.dy * f
}

enum class EnemyKind { AD, MALWARE }

class Enemy(val kind: EnemyKind, val home: Pair<Int, Int>, val index: Int) {
    val m = Mover(home.first, home.second)
    /** Seconds before it comes back after being eaten (0 = in play). */
    var away = 0f
    var leftHouse = false
    /** The real blocked domain it stands for ("" = a generic one). */
    var label = ""
}

/** Points floating up where something was eaten. */
class Popup(val x: Float, val y: Float, val points: Int, val label: String)

class ChompGame(seed: Long = System.nanoTime()) {
    enum class State { READY, PLAY, DYING, CLEAR, OVER }

    /** What just happened, for sound and haptics; the screen clears it. */
    enum class Event { DOT, POWER, AD, MALWARE, DIED, CLEAR, OVER }

    private val rnd = java.util.Random(seed)
    val w = MAZE[0].length
    val h = MAZE.size
    private val walls = BooleanArray(w * h)
    private val doors = BooleanArray(w * h)
    val dots = BooleanArray(w * h)
    val powers = BooleanArray(w * h)
    private var start = 0 to 0
    private var doorY = 0
    val shield: Mover
    val enemies = mutableListOf<Enemy>()

    var state = State.READY; private set
    var stateTime = 0f; private set
    var score = 0; private set
    var lives = 3; private set
    var level = 1; private set
    var powerLeft = 0f; private set
    var eatenAds = 0; private set
    var eatenMalware = 0; private set
    private var chain = 0
    private var want = Dir.NONE
    val events = ArrayDeque<Event>()
    /** Last bite (time since): drives the chomp on screen. */
    var sinceBite = 9f; private set
    /** Where points last appeared, for the floating "+200 doubleclick.net". */
    var popup: Popup? = null; private set
    /** Names given to the enemies (the screen sets this to real blocked domains). */
    var labeler: (EnemyKind) -> String = { "" }
        set(v) { field = v; for (e in enemies) e.label = v(e.kind) }
    /** What you ate this game, newest last. */
    val eatenNames = mutableListOf<String>()
    var popupTime = 9f; private set

    init {
        val ads = mutableListOf<Pair<Int, Int>>()
        val mal = mutableListOf<Pair<Int, Int>>()
        for (y in 0 until h) for (x in 0 until w) {
            when (MAZE[y][x]) {
                '#' -> walls[y * w + x] = true
                '-' -> { doors[y * w + x] = true; doorY = y }
                '.' -> dots[y * w + x] = true
                'o' -> powers[y * w + x] = true
                'S' -> start = x to y
                'A' -> ads += x to y
                'M' -> mal += x to y
            }
        }
        shield = Mover(start.first, start.second)
        ads.forEachIndexed { i, p -> enemies += Enemy(EnemyKind.AD, p, i) }
        mal.forEachIndexed { i, p -> enemies += Enemy(EnemyKind.MALWARE, p, i) }
        resetPositions()
    }

    val dotsLeft: Int get() = dots.count { it } + powers.count { it }

    fun wall(x: Int, y: Int): Boolean = y in 0 until h && walls[y * w + wrap(x)]
    fun door(x: Int, y: Int): Boolean = y in 0 until h && doors[y * w + wrap(x)]
    private fun wrap(x: Int) = ((x % w) + w) % w

    /** Swipe: the shield turns as soon as it can. */
    fun steer(d: Dir) {
        if (d == Dir.NONE) return
        want = d
        if (state == State.READY && stateTime > 0.3f) setState(State.PLAY)
    }

    fun restart() {
        score = 0; lives = 3; level = 1; eatenAds = 0; eatenMalware = 0; eatenNames.clear()
        refill(); resetPositions(); setState(State.READY)
    }

    private fun refill() {
        for (y in 0 until h) for (x in 0 until w) {
            dots[y * w + x] = MAZE[y][x] == '.'
            powers[y * w + x] = MAZE[y][x] == 'o'
        }
    }

    private fun resetPositions() {
        shield.tx = start.first; shield.ty = start.second; shield.dir = Dir.NONE; shield.f = 0f
        want = Dir.NONE
        powerLeft = 0f
        for (e in enemies) {
            e.m.tx = e.home.first; e.m.ty = e.home.second; e.m.dir = Dir.NONE; e.m.f = 0f
            e.away = 0f
            e.leftHouse = false
        }
    }

    private fun setState(s: State) { state = s; stateTime = 0f }

    private val speedUp: Float get() = 1f + 0.08f * (level - 1)
    private val shieldSpeed: Float get() = 6.2f * speedUp
    private fun enemySpeed(e: Enemy): Float = when {
        !e.leftHouse -> 3.5f
        e.kind == EnemyKind.MALWARE && powerLeft > 0f -> 3.4f * speedUp
        e.kind == EnemyKind.AD -> 4.6f * speedUp
        else -> 5.4f * speedUp
    }

    /** The shield can't enter the enemies' house; they leave it only upwards. */
    private fun open(x: Int, y: Int, forEnemy: Boolean, d: Dir): Boolean {
        if (wall(x, y)) return false
        if (door(x, y)) return forEnemy && d == Dir.UP
        return true
    }

    fun tick(dtIn: Float) {
        val dt = dtIn.coerceAtMost(0.05f)
        stateTime += dt
        sinceBite += dt
        popupTime += dt
        when (state) {
            State.READY -> return
            State.DYING -> {
                if (stateTime > 1.4f) {
                    if (lives <= 0) { setState(State.OVER); events += Event.OVER } else { resetPositions(); setState(State.READY) }
                }
                return
            }
            State.CLEAR -> {
                if (stateTime > 1.6f) { level++; refill(); resetPositions(); setState(State.READY) }
                return
            }
            State.OVER -> return
            State.PLAY -> {}
        }
        if (powerLeft > 0f) { powerLeft -= dt; if (powerLeft <= 0f) { powerLeft = 0f; chain = 0 } }
        moveShield(dt)
        eatAt(shield.tx, shield.ty)
        for (e in enemies) {
            if (e.away > 0f) { e.away -= dt; continue }
            moveEnemy(e, dt)
        }
        collide()
        if (dotsLeft == 0 && state == State.PLAY) { setState(State.CLEAR); events += Event.CLEAR }
    }

    private fun moveShield(dt: Float) {
        val m = shield
        // Turn around at once, mid-tile.
        if (want == m.dir.opposite && m.dir != Dir.NONE) {
            m.tx = wrap(m.tx + m.dir.dx); m.ty += m.dir.dy; m.f = 1f - m.f; m.dir = want
        }
        if (m.dir == Dir.NONE || m.f == 0f) {
            if (want != Dir.NONE && open(m.tx + want.dx, m.ty + want.dy, false, want)) m.dir = want
            else if (m.dir != Dir.NONE && !open(m.tx + m.dir.dx, m.ty + m.dir.dy, false, m.dir)) m.dir = Dir.NONE
        }
        if (m.dir == Dir.NONE) return
        m.f += shieldSpeed * dt
        while (m.f >= 1f) {
            m.f -= 1f
            m.tx = wrap(m.tx + m.dir.dx); m.ty += m.dir.dy
            eatAt(m.tx, m.ty)
            if (want != Dir.NONE && open(m.tx + want.dx, m.ty + want.dy, false, want)) m.dir = want
            if (!open(m.tx + m.dir.dx, m.ty + m.dir.dy, false, m.dir)) { m.dir = Dir.NONE; m.f = 0f; break }
        }
    }

    private fun eatAt(x: Int, y: Int) {
        val i = y * w + wrap(x)
        if (dots[i]) { dots[i] = false; score += 10; events += Event.DOT; sinceBite = 0f }
        if (powers[i]) {
            powers[i] = false; score += 50; powerLeft = 7f - 0.4f * (level - 1).coerceAtMost(8); chain = 0
            events += Event.POWER; sinceBite = 0f
            // Frightened malware turns around, like the ghosts.
            for (e in enemies) if (e.kind == EnemyKind.MALWARE && e.leftHouse && e.m.dir != Dir.NONE) {
                e.m.tx = wrap(e.m.tx + e.m.dir.dx); e.m.ty += e.m.dir.dy; e.m.f = 1f - e.m.f; e.m.dir = e.m.dir.opposite
            }
        }
    }

    private fun moveEnemy(e: Enemy, dt: Float) {
        val m = e.m
        if (m.dir == Dir.NONE) m.dir = choose(e)
        m.f += enemySpeed(e) * dt
        while (m.f >= 1f) {
            m.f -= 1f
            m.tx = wrap(m.tx + m.dir.dx); m.ty += m.dir.dy
            if (m.ty < doorY) e.leftHouse = true
            m.dir = choose(e)
            if (m.dir == Dir.NONE) { m.f = 0f; break }
        }
    }

    /** Picks the next direction at a tile centre. */
    private fun choose(e: Enemy): Dir {
        val m = e.m
        val all = listOf(Dir.UP, Dir.LEFT, Dir.DOWN, Dir.RIGHT)
        var opts = all.filter { it != m.dir.opposite && enemyOpen(e, m.tx + it.dx, m.ty + it.dy, it) }
        if (opts.isEmpty()) opts = all.filter { enemyOpen(e, m.tx + it.dx, m.ty + it.dy, it) }
        if (opts.isEmpty()) return Dir.NONE
        // Leave the house first: head for the door.
        val target: Pair<Int, Int>? = when {
            !e.leftHouse -> MAZE_DOOR.first to (doorY - 1)
            e.kind == EnemyKind.AD -> null
            powerLeft > 0f -> null
            e.index == 0 -> shield.tx to shield.ty
            else -> (shield.tx + shield.dir.dx * 4) to (shield.ty + shield.dir.dy * 4)
        }
        if (target == null) {
            // Ads mostly wander but drift away when the shield is close; frightened malware flees.
            val fleeing = e.kind == EnemyKind.MALWARE || dist(m.tx, m.ty, shield.tx, shield.ty) < 5f
            if (fleeing && rnd.nextFloat() < 0.75f) return opts.maxBy { dist(m.tx + it.dx, m.ty + it.dy, shield.tx, shield.ty) }
            return opts[rnd.nextInt(opts.size)]
        }
        return opts.minBy { dist(m.tx + it.dx, m.ty + it.dy, target.first, target.second) }
    }

    private fun enemyOpen(e: Enemy, x: Int, y: Int, d: Dir): Boolean {
        if (wall(x, y)) return false
        // Out through the door, never back in.
        if (door(x, y)) return !e.leftHouse && d == Dir.UP
        return true
    }

    private fun dist(ax: Int, ay: Int, bx: Int, by: Int) = hypot((ax - bx).toFloat(), (ay - by).toFloat())

    private fun collide() {
        for (e in enemies) {
            if (e.away > 0f) continue
            var dx = abs(e.m.x - shield.x)
            if (dx > w / 2f) dx = w - dx
            if (dx < 0.7f && abs(e.m.y - shield.y) < 0.7f) {
                when {
                    e.kind == EnemyKind.AD -> {
                        score += 100; eatenAds++; events += Event.AD
                        bite(e, 100); e.away = 4f
                    }
                    powerLeft > 0f -> {
                        chain++
                        val pts = 200 * (1 shl (chain - 1).coerceAtMost(3))
                        score += pts; eatenMalware++; events += Event.MALWARE
                        bite(e, pts); e.away = 3f
                    }
                    else -> {
                        lives--; events += Event.DIED; setState(State.DYING); return
                    }
                }
            }
        }
    }

    private fun bite(e: Enemy, pts: Int) {
        sinceBite = 0f
        popup = Popup(e.m.x, e.m.y, pts, e.label); popupTime = 0f
        if (e.label.isNotEmpty()) eatenNames += e.label
        e.label = labeler(e.kind)
        e.m.tx = e.home.first; e.m.ty = e.home.second; e.m.dir = Dir.NONE; e.m.f = 0f
        e.leftHouse = false
    }

    companion object {
        /** 19 × 21. # wall, . dot, o power shield, - house door, S you, A ad, M malware. */
        val MAZE = listOf(
            "###################",
            "#o.......#.......o#",
            "#.##.###.#.###.##.#",
            "#.................#",
            "#.##.#.#####.#.##.#",
            "#....#...#...#....#",
            "####.###.#.###.####",
            "   #.#.......#.#   ",
            "####.#.##-##.#.####",
            "    ....#AMA#....  ",
            "####.#.#####.#.####",
            "   #.#.......#.#   ",
            "####.#.#####.#.####",
            "#........#........#",
            "#.##.###.#.###.##.#",
            "#o.#.....S.....#.o#",
            "##.#.#.#####.#.#.##",
            "#....#...#...#....#",
            "#.######.#.######.#",
            "#...........M.....#",
            "###################",
        )
        /** Column of the house door. */
        val MAZE_DOOR = 9 to 8
    }
}
