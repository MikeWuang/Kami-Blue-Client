package me.zeroeightsix.kami.module.modules.render

import me.zero.alpine.listener.EventHandler
import me.zero.alpine.listener.EventHook
import me.zero.alpine.listener.Listener
import me.zeroeightsix.kami.event.events.ConnectionEvent
import me.zeroeightsix.kami.event.events.RenderEvent
import me.zeroeightsix.kami.event.events.WaypointUpdateEvent
import me.zeroeightsix.kami.manager.mangers.WaypointManager
import me.zeroeightsix.kami.module.Module
import me.zeroeightsix.kami.setting.Setting
import me.zeroeightsix.kami.setting.Settings
import me.zeroeightsix.kami.util.TimerUtils
import me.zeroeightsix.kami.util.Waypoint
import me.zeroeightsix.kami.util.color.ColorHolder
import me.zeroeightsix.kami.util.graphics.*
import me.zeroeightsix.kami.util.math.Vec2d
import me.zeroeightsix.kami.util.math.VectorUtils.toVec3d
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import org.lwjgl.opengl.GL11.*
import java.util.*
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Module.Info(
        name = "WaypointRender",
        description = "Render saved waypoints",
        category = Module.Category.RENDER
)
object WaypointRender : Module() {
    private val page = register(Settings.e<Page>("Page", Page.INFO_BOX))

    /* Page one */
    private val dimension = register(Settings.enumBuilder(Dimension::class.java, "Dimension").withValue(Dimension.CURRENT).withVisibility { page.value == Page.INFO_BOX })
    private val showName = register(Settings.booleanBuilder("ShowName").withValue(true).withVisibility { page.value == Page.INFO_BOX }.build())
    private val showDate = register(Settings.booleanBuilder("ShowDate").withValue(false).withVisibility { page.value == Page.INFO_BOX }.build())
    private val showCoords = register(Settings.booleanBuilder("ShowCoords").withValue(true).withVisibility { page.value == Page.INFO_BOX }.build())
    private val showDist = register(Settings.booleanBuilder("ShowDistance").withValue(true).withVisibility { page.value == Page.INFO_BOX }.build())
    private val textScale = register(Settings.floatBuilder("TextScale").withValue(1.0f).withRange(0.0f, 2.0f).withVisibility { page.value == Page.INFO_BOX }.build())
    private val infoBoxRange = register(Settings.integerBuilder("InfoBoxRange").withValue(512).withRange(128, 2048).withVisibility { page.value == Page.INFO_BOX }.build())

    /* Page two */
    private val espRangeLimit = register(Settings.booleanBuilder("RenderRange").withValue(true).withVisibility { page.value == Page.ESP }.build())
    private val espRange = register(Settings.integerBuilder("Range").withValue(4096).withRange(1024, 16384).withVisibility { page.value == Page.ESP && espRangeLimit.value }.build())
    private val filled = register(Settings.booleanBuilder("Filled").withValue(true).withVisibility { page.value == Page.ESP }.build())
    private val outline = register(Settings.booleanBuilder("Outline").withValue(true).withVisibility { page.value == Page.ESP }.build())
    private val tracer = register(Settings.booleanBuilder("Tracer").withValue(true).withVisibility { page.value == Page.ESP }.build())
    private val r = register(Settings.integerBuilder("Red").withMinimum(0).withValue(31).withMaximum(255).withVisibility { page.value == Page.ESP }.build())
    private val g = register(Settings.integerBuilder("Green").withMinimum(0).withValue(200).withMaximum(255).withVisibility { page.value == Page.ESP }.build())
    private val b = register(Settings.integerBuilder("Blue").withMinimum(0).withValue(63).withMaximum(255).withVisibility { page.value == Page.ESP }.build())
    private val aFilled = register(Settings.integerBuilder("FilledAlpha").withValue(63).withRange(0, 255).withVisibility { page.value == Page.ESP && filled.value }.build())
    private val aOutline = register(Settings.integerBuilder("OutlineAlpha").withValue(160).withRange(0, 255).withVisibility { page.value == Page.ESP && outline.value }.build())
    private val aTracer = register(Settings.integerBuilder("TracerAlpha").withValue(200).withRange(0, 255).withVisibility { page.value == Page.ESP && tracer.value }.build())
    private val thickness = register(Settings.floatBuilder("LineThickness").withValue(2.0f).withRange(0.0f, 8.0f).build())

    private enum class Dimension {
        CURRENT, ANY
    }

    private enum class Page {
        INFO_BOX, ESP
    }

    private val waypointMap = TreeMap<BlockPos, TextComponent>(compareByDescending {
        it.distanceSq(mc.player?.position ?: BlockPos(0, -69420, 0)) // This has to be sorted so the further ones doesn't overlaps the closer ones
    })
    private var currentServer: String? = null
    private var timer = TimerUtils.TickTimer(TimerUtils.TimeUnit.SECONDS)
    private var prevDimension = -2

    override fun onWorldRender(event: RenderEvent) {
        if (waypointMap.isEmpty()) return
        val color = ColorHolder(r.value, g.value, b.value)
        val renderer = ESPRenderer()
        renderer.aFilled = if (filled.value) aFilled.value else 0
        renderer.aOutline = if (outline.value) aOutline.value else 0
        renderer.aTracer = if (tracer.value) aTracer.value else 0
        renderer.thickness = thickness.value
        GlStateUtils.depth(false)
        for (pos in waypointMap.keys) {
            val distance = sqrt(mc.player.getDistanceSq(pos))
            if (espRangeLimit.value && distance > espRange.value) continue
            renderer.add(AxisAlignedBB(pos), color) /* Adds pos to ESPRenderer list */
            drawVerticalLines(pos, color, aOutline.value) /* Draw lines from y 0 to y 256 */
        }
        GlStateUtils.depth(true)
        renderer.render(true)
    }

    private fun drawVerticalLines(pos: BlockPos, color: ColorHolder, a: Int) {
        val box = AxisAlignedBB(pos.x.toDouble(), 0.0, pos.z.toDouble(),
                pos.x + 1.0, 256.0, pos.z + 1.0)
        KamiTessellator.begin(GL_LINES)
        KamiTessellator.drawOutline(box, color, a, GeometryMasks.Quad.ALL, thickness.value)
        KamiTessellator.render()
    }

    override fun onRender() {
        if (waypointMap.isEmpty()) return
        if (!showCoords.value && !showName.value && !showDate.value && !showDist.value) return
        GlStateUtils.rescaleActual()
        for ((pos, textComponent) in waypointMap) {
            val distance = sqrt(mc.player.getDistanceSqToCenter(pos))
            if (distance > infoBoxRange.value) continue
            drawText(pos, textComponent, distance.roundToInt())
        }
        GlStateUtils.rescaleMc()
    }

    private fun drawText(pos: BlockPos, textComponentIn: TextComponent, distance: Int) {
        glPushMatrix()

        val screenPos = ProjectionUtils.toScreenPos(pos.toVec3d())
        glTranslatef(screenPos.x.roundToInt() + 0.375f, screenPos.y.roundToInt() + 0.375f, 0f)
        glScalef(textScale.value * 2f, textScale.value * 2f, 0f)

        val textComponent = TextComponent(textComponentIn).apply { if (showDist.value) add("$distance m") }
        val stringWidth = textComponent.getWidth()
        val stringHeight = textComponent.getHeight(2)
        val vertexHelper = VertexHelper(GlStateUtils.useVbo())
        val pos1 = Vec2d(stringWidth * -0.5 - 4.0, stringHeight * -0.5 - 4.0)
        val pos2 = Vec2d(stringWidth * 0.5 + 4.0, stringHeight * 0.5 + 4.0)

        RenderUtils2D.drawRectFilled(vertexHelper, pos1, pos2, ColorHolder(32, 32, 32, 172))
        RenderUtils2D.drawRectOutline(vertexHelper, pos1, pos2, 2f, ColorHolder(80, 80, 80, 232))
        textComponent.draw(drawShadow = false, horizontalAlign = TextComponent.HAlign.CENTER, verticalAlign = TextComponent.VAlign.CENTER)

        glPopMatrix()
    }

    override fun onEnable() {
        timer.reset(-10000L) // Update the map immediately and thread safely
    }

    override fun onDisable() {
        currentServer = null
    }

    override fun onUpdate() {
        if (WaypointManager.genDimension() != prevDimension || timer.tick(10L, false)) {
            if (WaypointManager.genDimension() != prevDimension) waypointMap.clear()
            updateList()
        }
    }

    @EventHandler
    private val waypointUpdateListener = Listener(EventHook { event: WaypointUpdateEvent ->
        synchronized(waypointMap) { // This could be called from another thread so we have to synchronize the map
            when (event.type) {
                WaypointUpdateEvent.Type.ADD -> event.waypoint?.let { updateTextComponent(it) }
                WaypointUpdateEvent.Type.REMOVE -> waypointMap.remove(event.waypoint?.pos)
                WaypointUpdateEvent.Type.CLEAR -> waypointMap.clear()
                WaypointUpdateEvent.Type.RELOAD -> { waypointMap.clear(); updateList() }
                else -> { }
            }
        }
    })

    @EventHandler
    private val disconnectListener = Listener(EventHook { event: ConnectionEvent.Disconnect ->
        currentServer = null
    })

    private fun updateList() {
        timer.reset()
        prevDimension = WaypointManager.genDimension()
        if (currentServer == null) {
            waypointMap.clear()
            currentServer = WaypointManager.genServer()
        }

        val cacheList = WaypointManager.waypoints.filter { (it.server == null || it.server == currentServer) && (dimension.value == Dimension.ANY || it.dimension == prevDimension) }

        waypointMap.keys.removeIf { pos -> cacheList.firstOrNull { it.pos == pos } != null }

        for (waypoint in cacheList) updateTextComponent(waypoint)
    }

    private fun updateTextComponent(waypoint: Waypoint) {
        // Don't wanna update this continuously
        waypointMap.computeIfAbsent(waypoint.pos) {
            TextComponent().apply {
                if (showName.value) addLine(waypoint.name)
                if (showDate.value) addLine(waypoint.date)
                if (showCoords.value) addLine(waypoint.asString(true))
            }
        }
    }

    init {
        with(Setting.SettingListeners {
            synchronized(waypointMap) { waypointMap.clear(); updateList() } // This could be called from another thread so we have to synchronize the map
        }) {
            dimension.settingListener = this
            showName.settingListener = this
            showDate.settingListener = this
            showCoords.settingListener = this
            showDist.settingListener = this
        }
    }
}