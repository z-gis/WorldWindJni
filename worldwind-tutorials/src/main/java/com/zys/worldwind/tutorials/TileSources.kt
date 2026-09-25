package com.zys.worldwind.tutorials

/**
 * 演示用瓦片图源常量。
 *
 * 默认使用 OpenStreetMap 公共瓦片（无需 token，便于开箱即跑）；
 * 正式宿主请替换为自己有授权的图源 URL 模板（如天地图，Token 须已替换进模板）。
 */
object TileSources {

    /** OSM 标准瓦片：native 按 {z}/{x}/{y} 占位替换（口径同 tutorials/03） */
    const val OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png"

    /** OSM 标准瓦片图源最大级别 */
    const val OSM_MAX_LEVEL = 19
}
