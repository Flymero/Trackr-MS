package com.trackr.core.model

/**
 * 线路显示名：`name` 为空时退回线路号 `lineNo`。
 *
 * 数据源偶尔只给线路号不给线路名（车来了部分线路 `name` 是空串，只有 `lineNo`），
 * 按名字画线路号徽标时就会画成一个空白的圆角块（issue #20）。
 */
val BusLineDirection.displayLineName: String
    get() = name.trim().ifBlank { lineNo.trim() }

/**
 * 收藏卡的线路显示名。
 *
 * 收藏只存了收藏那一刻拿到的名字：数据源给了空名字、或者是更早的版本存的，名字就是空的，
 * 首页「收藏」、编辑收藏页、桌面小组件里都只剩一个空白徽标（issue #20）。
 * 名字和线路号都为空时返回空串，调用方可以再用实时数据里的名字兜底（见 `BusHomeMapper`）。
 */
val FavoriteStationLine.displayLineName: String
    get() = lineName.trim().ifBlank { lineNo.trim() }

/**
 * 临时改道标记（issue #41 后续）。
 *
 * 掌上公交改道期间不给单独的字段，只把线路名改成「153路（临时改道）」；改道刚开始那阵站序还可能乱（出现第 900 站）。
 * 页面据此提示用户「今天走向和站点可能和平时不同」，标题用去掉标记的名字。
 */
object TemporaryDiversion {

    /** 「（临时改道）」「(临时改道)」「（临时改道至 X）」这类括号标记，全角半角都认 */
    private val MARKER = Regex("""\s*[（(]\s*(临时改道[^）)]*?)\s*[）)]\s*""")

    /** 线路名里的改道说明（如「临时改道」）；没有改道标记时为 null */
    fun noteOf(lineName: String): String? =
        MARKER.find(lineName)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** 去掉改道标记后的线路名（「153路（临时改道）」→「153路」）；没有标记时原样返回 */
    fun strip(lineName: String): String = MARKER.replace(lineName, "").trim().ifEmpty { lineName.trim() }
}

/** 这个方向正在临时改道时的说明（如「临时改道」）；没有改道时为 null */
val BusLineDirection.diversionNote: String?
    get() = TemporaryDiversion.noteOf(name)

/** 去掉「（临时改道）」标记的显示名：改道另有提示条，标题不用再挂一截括号 */
val BusLineDirection.displayLineNameWithoutDiversion: String
    get() = TemporaryDiversion.strip(displayLineName)
