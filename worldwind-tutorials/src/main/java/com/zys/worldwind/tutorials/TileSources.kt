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

    /**
     * 内置离线世界图瓦片（仿 wwd worldtopobathy 基础瓦片）在 assets 下的目录：
     * 预切好的全球墨卡托 XYZ 瓦片，布局 `<z>/<x>_<y>.tile`（PNG 字节），与 native TileCache 磁盘缓存同构。
     * 因当前预编译 libgdal 裁剪了 PNG 驱动（无 GDAL 栅格即时重投影通路），改走已工作的瓦片磁盘缓存管线。
     */
    const val TOPO_ASSET_DIR = "tiles/topo"

    /** 内置离线世界图瓦片已生成的最大级别（源图 1024×512，超过此级由 native 拉伸末级瓦片） */
    const val TOPO_MAX_LEVEL = 3
}
