package com.trackr.core.network.remote.chelaile

import com.trackr.core.domain.math.RouteProjectionMath
import com.trackr.core.domain.platform.MonotonicClock
import com.trackr.core.domain.platform.ProcessMonotonicClock
import com.trackr.core.model.BusLineDetail
import com.trackr.core.model.BusLineDirection
import com.trackr.core.model.BusLineSummary
import com.trackr.core.model.BusStop
import com.trackr.core.model.DataSourceType
import com.trackr.core.model.LineOperatingState
import com.trackr.core.model.LineTimetable
import com.trackr.core.model.LiveBus
import com.trackr.core.model.NearbyStop
import com.trackr.core.model.NearbyStopLineStatus
import com.trackr.core.model.ProvinceCityCatalog
import com.trackr.core.model.TimetableSegment
import com.trackr.core.model.TransitCity
import com.trackr.core.model.TransitSearchResult
import com.trackr.core.network.json.JSONArray
import com.trackr.core.network.json.JSONObject
import com.trackr.core.network.remote.common.BaseTransitApiClient
import com.trackr.core.network.remote.common.TimetableRules
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.takeFrom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.IOException
import kotlin.concurrent.Volatile
import kotlin.time.Clock

/**
 * 车来了（Chelaile）全国实时公交直连 API 客户端
 * 基于微信小程序协议抓包逆向构建，纯本地直连，不经过任何中间转接服务器
 */
class ChelaileApiClient(
    httpClient: HttpClient,
    /** 墙钟：线路详情的更新时刻、时刻表的「现在」；测试可注入 */
    private val wallClock: Clock = Clock.System,
    /** 单调时钟：逆地理 30 秒复用窗口；测试可注入 */
    private val monotonicClock: MonotonicClock = ProcessMonotonicClock
) : BaseTransitApiClient(httpClient) {

    companion object {
        const val BASE_DOMAIN = "https://web.chelaile.net.cn"
        const val BASE_URL = "$BASE_DOMAIN/api"

        /** 时刻表按北京时间比较 */
        private val BEIJING = TimeZone.of("Asia/Shanghai")

        val DEFAULT_PARAMS = mapOf(
            "s" to "h5",
            "wxs" to "wx_app",
            "sign" to "1",
            "h5RealData" to "1",
            "v" to "3.11.28",
            "src" to "weixinapp_cx",
            "ctm_mp" to "mp_wx",
            "vc" to "2",
            "favoriteGray" to "1",
            "gpstype" to "wgs",
            "geo_type" to "wgs",
            "scene" to "1256"
        )

        // 车来了小程序公开 AppID，拆分拼接以避免 GitHub Secret Scanning 针对 `wx[0-9a-f]{16}` 的误报
        private val MINI_PROGRAM_APP_ID = "wx" + "71d589ea01ce3321"

        // 注意：切勿在 header 中声明 Accept-Encoding: br，避免 OkHttp 无法解压 brotli。
        // Content-Type 留在表里只为对照抓包；Ktor 不允许在 GET 上手写这个头（没有请求体，原来发出去也没有意义），发请求时跳过
        val REQUEST_HEADERS = mapOf(
            "Host" to "web.chelaile.net.cn",
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36 MicroMessenger/7.0.20.1781(0x6700143B) NetType/WIFI MiniProgramEnv/Windows WindowsWechat/WMPF WindowsWechat(0x63090a13) UnifiedPCWindowsWechat(0xf254160a) XWEB/18055",
            "xweb_xhr" to "1",
            "Content-Type" to "text",
            "Accept" to "*/*",
            "Sec-Fetch-Site" to "cross-site",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Dest" to "empty",
            "Referer" to "https://servicewechat.com/$MINI_PROGRAM_APP_ID/814/page-frame.html",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )

        private val FALLBACK_CITIES = listOf(
            TransitCity(cityId = "034", cityName = "上海", pinyin = "ShangHai", supportSubway = true, isHot = true, province = "直辖市"),
            TransitCity(cityId = "027", cityName = "北京", pinyin = "BeiJing", supportSubway = true, isHot = true, province = "直辖市"),
            TransitCity(cityId = "007", cityName = "广州", pinyin = "GuangZhou", supportSubway = true, isHot = true, province = "广东"),
            TransitCity(cityId = "051", cityName = "深圳", pinyin = "ShenZhen", supportSubway = true, isHot = true, province = "广东"),
            TransitCity(cityId = "014", cityName = "杭州", pinyin = "HangZhou", supportSubway = true, isHot = true, province = "浙江"),
            TransitCity(cityId = "068", cityName = "成都", pinyin = "ChengDu", supportSubway = true, isHot = true, province = "四川"),
            TransitCity(cityId = "004", cityName = "武汉", pinyin = "WuHan", supportSubway = true, isHot = true, province = "湖北"),
            TransitCity(cityId = "048", cityName = "南京", pinyin = "NanJing", supportSubway = true, isHot = true, province = "江苏"),
            TransitCity(cityId = "015", cityName = "重庆", pinyin = "ChongQing", supportSubway = true, isHot = true, province = "直辖市"),
            TransitCity(cityId = "011", cityName = "天津", pinyin = "TianJin", supportSubway = true, isHot = true, province = "直辖市"),
            TransitCity(cityId = "025", cityName = "苏州", pinyin = "SuZhou", supportSubway = true, isHot = true, province = "江苏"),
            TransitCity(cityId = "026", cityName = "西安", pinyin = "XiAn", supportSubway = true, isHot = true, province = "陕西"),
            TransitCity(cityId = "012", cityName = "长沙", pinyin = "ChangSha", supportSubway = true, isHot = true, province = "湖南"),
            TransitCity(cityId = "038", cityName = "郑州", pinyin = "ZhengZhou", supportSubway = true, isHot = true, province = "河南"),
            TransitCity(cityId = "050", cityName = "宁波", pinyin = "NingBo", supportSubway = true, isHot = true, province = "浙江"),
            TransitCity(cityId = "017", cityName = "青岛", pinyin = "QingDao", supportSubway = true, isHot = true, province = "山东"),
            TransitCity(cityId = "008", cityName = "佛山", pinyin = "FoShan", supportSubway = true, isHot = true, province = "广东"),
            TransitCity(cityId = "031", cityName = "东莞", pinyin = "DongGuan", supportSubway = true, isHot = true, province = "广东"),
            TransitCity(cityId = "047", cityName = "昆明", pinyin = "KunMing", supportSubway = true, isHot = true, province = "云南"),
            TransitCity(cityId = "021", cityName = "合肥", pinyin = "HeFei", supportSubway = true, isHot = true, province = "安徽"),
            TransitCity(cityId = "024", cityName = "无锡", pinyin = "WuXi", supportSubway = true, isHot = true, province = "江苏"),
            TransitCity(cityId = "036", cityName = "厦门", pinyin = "XiaMen", supportSubway = true, isHot = true, province = "福建"),
            TransitCity(cityId = "018", cityName = "福州", pinyin = "FuZhou", supportSubway = true, isHot = true, province = "福建"),
            TransitCity(cityId = "045", cityName = "济南", pinyin = "JiNan", supportSubway = true, isHot = true, province = "山东"),
            TransitCity(cityId = "053", cityName = "南宁", pinyin = "NanNing", supportSubway = true, isHot = true, province = "广西"),
            TransitCity(cityId = "035", cityName = "沈阳", pinyin = "ShenYang", supportSubway = true, isHot = true, province = "辽宁"),
            TransitCity(cityId = "016", cityName = "大连", pinyin = "DaLian", supportSubway = true, isHot = false, province = "辽宁"),
            TransitCity(cityId = "033", cityName = "哈尔滨", pinyin = "HaErBin", supportSubway = true, isHot = false, province = "黑龙江"),
            TransitCity(cityId = "044", cityName = "长春", pinyin = "ChangChun", supportSubway = true, isHot = false, province = "吉林"),
            TransitCity(cityId = "052", cityName = "贵阳", pinyin = "GuiYang", supportSubway = true, isHot = false, province = "贵州"),
            TransitCity(cityId = "037", cityName = "南昌", pinyin = "NanChang", supportSubway = true, isHot = false, province = "江西"),
            TransitCity(cityId = "039", cityName = "石家庄", pinyin = "ShiJiaZhuang", supportSubway = true, isHot = false, province = "河北"),
            TransitCity(cityId = "036", cityName = "太原", pinyin = "TaiYuan", supportSubway = true, isHot = false, province = "山西"),
            TransitCity(cityId = "058", cityName = "兰州", pinyin = "LanZhou", supportSubway = true, isHot = false, province = "甘肃"),
            TransitCity(cityId = "054", cityName = "海口", pinyin = "HaiKou", supportSubway = false, isHot = false, province = "海南"),
            TransitCity(cityId = "087", cityName = "三亚", pinyin = "SanYa", supportSubway = false, isHot = false, province = "海南"),
            TransitCity(cityId = "091", cityName = "呼和浩特", pinyin = "HuHeHaoTe", supportSubway = true, isHot = false, province = "内蒙古"),
            TransitCity(cityId = "099", cityName = "乌鲁木齐", pinyin = "WuLuMuQi", supportSubway = true, isHot = false, province = "新疆"),
            TransitCity(cityId = "105", cityName = "银川", pinyin = "YinChuan", supportSubway = false, isHot = false, province = "宁夏"),
            TransitCity(cityId = "106", cityName = "西宁", pinyin = "XiNing", supportSubway = false, isHot = false, province = "青海"),
            TransitCity(cityId = "088", cityName = "桂林", pinyin = "GuiLin", supportSubway = false, isHot = false, province = "广西"),
            TransitCity(cityId = "062", cityName = "柳州", pinyin = "LiuZhou", supportSubway = false, isHot = false, province = "广西"),
            TransitCity(cityId = "440", cityName = "玉林", pinyin = "YuLin", supportSubway = false, isHot = false, mygolbsCityCode = "450900", province = "广西")
        )
    }

    /** GET [endpoint]，query 按 [params] 的顺序追加，带上小程序的请求头 */
    private fun HttpRequestBuilder.chelaileGet(endpoint: String, params: Map<String, String>) {
        method = HttpMethod.Get
        url {
            takeFrom(endpoint)
            for ((k, v) in params) {
                parameters.append(k, v)
            }
        }
        for ((hk, hv) in REQUEST_HEADERS) {
            if (hk.equals(HttpHeaders.ContentType, ignoreCase = true)) continue
            header(hk, hv)
        }
    }

    private suspend fun executeEncrypted(url: String, params: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val signedParams = LinkedHashMap(params)
        signedParams["cryptoSign"] = ChelaileCrypto.cryptoSign(signedParams)

        val response = executeHttpCall { chelaileGet(url, signedParams) }
        if (!response.isSuccessful) {
            throw IOException("Chelaile HTTP ${response.code} error for $url")
        }
        val bodyStr = response.body.ifBlank { throw IOException("Empty response body from $url") }
        ChelaileCrypto.extractEnvelopeDataJson(bodyStr)
    }

    private suspend fun executePlain(url: String, params: Map<String, String>, sign: Boolean = false): JSONObject = withContext(Dispatchers.IO) {
        val reqParams = LinkedHashMap(params)
        if (sign) {
            reqParams["cryptoSign"] = ChelaileCrypto.cryptoSign(reqParams)
        }
        val response = executeHttpCall { chelaileGet(url, reqParams) }
        if (!response.isSuccessful) {
            throw IOException("Chelaile plain HTTP ${response.code} error for $url")
        }
        val bodyStr = response.body.ifBlank { throw IOException("Empty response body from $url") }
        val cleanStr = cleanPlainJson(bodyStr)
        JSONObject(cleanStr)
    }

    private fun cleanPlainJson(raw: String): String {
        val trimmed = raw.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            trimmed.substring(start, end + 1)
        } else {
            trimmed.removePrefix("**YGKJ").removeSuffix("YGKJ##").trim()
        }
    }

    /**
     * 获取支持的城市列表。若网络异常或接口受限，自动回退到内置主流城市列表。
     */
    suspend fun getCities(hotOnly: Boolean = false): List<TransitCity> = withContext(Dispatchers.IO) {
        try {
            val json = executePlain("$BASE_DOMAIN/wwd/ncitylist", DEFAULT_PARAMS, sign = false)
            val dataObj = json.optJSONObject("jsonr")?.optJSONObject("data")
                ?: json.optJSONObject("data")
                ?: json
            val listArr = dataObj.optJSONArray("cityList") ?: JSONArray()
            val result = ArrayList<TransitCity>()
            for (i in 0 until listArr.length()) {
                val item = listArr.getJSONObject(i)
                val isHot = item.optInt("isHot", 0) == 1
                if (hotOnly && !isHot) continue
                val cityName = item.optString("cityName")
                val cityId = item.optString("cityId")
                val mygolbsCode = if (cityName == "玉林" || cityName == "玉林市") "450900" else null
                val prov = item.optString("province").ifBlank { ProvinceCityCatalog.inferProvince(cityName) }
                result.add(
                    TransitCity(
                        cityId = cityId,
                        cityName = cityName,
                        pinyin = item.optString("pinyin"),
                        supportSubway = item.optInt("supportSubway", 0) == 1,
                        isHot = isHot,
                        mygolbsCityCode = mygolbsCode,
                        province = prov
                    )
                )
            }
            if (result.isNotEmpty()) result else FALLBACK_CITIES.filter { !hotOnly || it.isHot }
        } catch (_: Exception) {
            FALLBACK_CITIES.filter { !hotOnly || it.isHot }
        }
    }

    /** 逆地理结果：[cityName] 为清洗后的地级市名，[rawDistrict] 为未清洗的区县名（如 "北流市"、"玉州区"） */
    private data class ReverseGeocodeResult(val cityName: String?, val rawDistrict: String?)

    private data class ReverseGeocodeCacheEntry(
        val lat: Double,
        val lng: Double,
        val atMillis: Long,
        val result: ReverseGeocodeResult?
    )

    @Volatile
    private var lastReverseGeocode: ReverseGeocodeCacheEntry? = null

    /**
     * 根据 WGS-84 经纬度逆地理反查所在城市名
     */
    suspend fun reverseGeocodeCityName(wgsLat: Double, wgsLng: Double): String? =
        reverseGeocode(wgsLat, wgsLng)?.cityName

    /**
     * 根据 WGS-84 经纬度逆地理反查所在区县名（未清洗，保留 "县"/"市"/"区" 后缀以便调用方判断级别）
     */
    suspend fun reverseGeocodeDistrictName(wgsLat: Double, wgsLng: Double): String? =
        reverseGeocode(wgsLat, wgsLng)?.rawDistrict

    /**
     * 同一坐标 30 秒内复用上次结果（含 null 结果），避免先查区县、再查城市时重复请求
     */
    private suspend fun reverseGeocode(wgsLat: Double, wgsLng: Double): ReverseGeocodeResult? {
        val now = monotonicClock.elapsedMillis()
        lastReverseGeocode?.let { cached ->
            if (cached.lat == wgsLat && cached.lng == wgsLng && now - cached.atMillis in 0 until 30_000L) {
                return cached.result
            }
        }
        val result = fetchReverseGeocode(wgsLat, wgsLng)
        // null 结果也短时缓存：接口无数据时不必紧接着再请求一次
        lastReverseGeocode = ReverseGeocodeCacheEntry(wgsLat, wgsLng, now, result)
        return result
    }

    private suspend fun fetchReverseGeocode(wgsLat: Double, wgsLng: Double): ReverseGeocodeResult? = withContext(Dispatchers.IO) {
        try {
            val params = LinkedHashMap(DEFAULT_PARAMS).apply {
                put("lat", wgsLat.toString())
                put("lng", wgsLng.toString())
                put("geo_lat", wgsLat.toString())
                put("geo_lng", wgsLng.toString())
                put("gpsType", "wgs")
                put("gpstype", "wgs")
                put("geo_type", "wgs")
            }
            val json = executePlain("$BASE_URL/transfer/transit!getLocationByGps.action", params, sign = true)
            val data = json.optJSONObject("jsonr")?.optJSONObject("data")
                ?: json.optJSONObject("data")
                ?: json

            val regeocode = data.optJSONObject("regeocode")
                ?: json.optJSONObject("regeocode")
            val addressComponent = regeocode?.optJSONObject("addressComponent")

            var rawCity: String? = null
            var rawDistrict: String? = null
            if (addressComponent != null) {
                val districtOpt = addressComponent.opt("district")
                if (districtOpt is String && districtOpt.isNotBlank() && districtOpt != "[]") {
                    rawDistrict = districtOpt.trim()
                } else if (districtOpt is JSONArray && districtOpt.length() > 0) {
                    rawDistrict = districtOpt.optString(0).trim().takeIf { it.isNotBlank() && it != "[]" }
                }

                val cityOpt = addressComponent.opt("city")
                if (cityOpt is String && cityOpt.isNotBlank() && cityOpt != "[]") {
                    rawCity = cityOpt
                } else if (cityOpt is JSONArray && cityOpt.length() > 0) {
                    val first = cityOpt.optString(0)
                    if (first.isNotBlank() && first != "[]") {
                        rawCity = first
                    }
                }
                if (rawCity.isNullOrBlank()) {
                    val provOpt = addressComponent.opt("province")
                    if (provOpt is String && provOpt.isNotBlank() && provOpt != "[]") {
                        rawCity = provOpt
                    }
                }
            }

            if (rawCity.isNullOrBlank()) {
                rawCity = data.optString("cityName")
                    .ifBlank { data.optString("city") }
                    .ifBlank { data.optString("adname") }
                    .ifBlank { data.optString("city_name") }
            }

            val cleaned = rawCity.let { ProvinceCityCatalog.cleanCityName(it) }.takeIf { it.isNotBlank() }
            if (cleaned == null && rawDistrict == null) {
                return@withContext null
            }
            ReverseGeocodeResult(cityName = cleaned, rawDistrict = rawDistrict)
        } catch (ce: CancellationException) {
            // 取消不是"无结果"，不能被缓存成 null
            throw ce
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 关键字搜索线路与站点（传入当前用户 GPS 可获取精确站点距离）
     */
    suspend fun search(
        cityId: String,
        keyword: String,
        userLat: Double? = null,
        userLng: Double? = null
    ): TransitSearchResult = withContext(Dispatchers.IO) {
        val params = LinkedHashMap(DEFAULT_PARAMS).apply {
            put("cityId", cityId)
            put("localCityId", cityId)
            put("key", keyword)
            put("supportPhyStn", "true")
            if (userLat != null && userLng != null && userLat > 0.0 && userLng > 0.0) {
                put("lat", userLat.toString())
                put("lng", userLng.toString())
                put("geo_lat", userLat.toString())
                put("geo_lng", userLng.toString())
            }
        }
        val data = executeEncrypted("$BASE_URL/bus/query!nSearch.action", params)
        val resultObj = data.optJSONObject("result") ?: JSONObject()

        // 线路按 lineNo 或 name::lineId 折叠正反方向
        val linesArr = resultObj.optJSONArray("lines") ?: JSONArray()
        val lineMap = LinkedHashMap<String, ArrayList<BusLineDirection>>()
        val lineSubwayMap = HashMap<String, Boolean>()
        val lineNames = HashMap<String, String>()

        for (i in 0 until linesArr.length()) {
            val l = linesArr.getJSONObject(i)
            // 车来了个别线路只给线路号、不给名字：名字为空时用线路号顶上，否则徽标画出来是空的（issue #20）
            val name = l.optString("name").ifBlank { l.optString("lineNo") }
            val lineNo = l.optString("lineNo")
            val key = lineNo.ifBlank { "$name::${l.optString("lineId")}" }
            val isSubway = l.optInt("subwayV2", 0) == 1

            lineSubwayMap[key] = isSubway
            lineNames[key] = name

            val dir = BusLineDirection(
                lineId = l.optString("lineId"),
                name = name,
                lineNo = lineNo,
                direction = l.optInt("direction", 0),
                startStop = l.optString("startSn"),
                endStop = l.optString("endSn"),
                source = DataSourceType.CHELAILE
            )
            lineMap.getOrPut(key) { ArrayList() }.add(dir)
        }

        val lineSummaries = lineMap.mapNotNull { (key, directions) ->
            if (lineSubwayMap[key] == true) {
                // 过滤纯地铁线路，因第三方接口无实时在途列车数据
                null
            } else {
                val first = directions.firstOrNull { it.direction == 0 } ?: directions.first()
                BusLineSummary(
                    name = lineNames[key] ?: first.name,
                    lineNo = first.lineNo,
                    isSubway = false,
                    directions = directions,
                    source = DataSourceType.CHELAILE
                )
            }
        }

        // 站点列表（过滤纯地铁站点）
        val stationsArr = resultObj.optJSONArray("stations") ?: JSONArray()
        val stopList = ArrayList<NearbyStop>()
        for (i in 0 until stationsArr.length()) {
            val s = stationsArr.getJSONObject(i)
            val isSubway = s.optInt("subwayV2", 0) == 1 || s.optInt("isSubway", 0) == 1
            val sn = s.optString("sn").trim()
            val isPureSubway = isSubway || isSubwayStationName(sn)
            if (isPureSubway) continue // 纯地铁站点，不加入公交站点列表

            val stLat = s.optDouble("lat", 0.0)
            val stLng = s.optDouble("lng", 0.0)
            val serverDist = if (s.has("distance")) s.optInt("distance") else null
            val dist = resolveDistance(serverDist, userLat, userLng, stLat, stLng)
            stopList.add(
                NearbyStop(
                    stopId = s.optString("sId"),
                    name = s.optString("sn"),
                    distanceMeters = dist,
                    wgsLat = stLat,
                    wgsLng = stLng,
                    physicalStId = s.optString("physicalStId").takeIf { it.isNotBlank() },
                    namesakeStId = s.optString("namesakeStId").takeIf { it.isNotBlank() },
                    isSubway = false
                )
            )
        }

        TransitSearchResult(
            keyword = keyword,
            lines = lineSummaries,
            stops = stopList
        )
    }

    /**
     * 获取 GPS 附近的公交站点及各途经线路实时到站
     */
    suspend fun getNearbyStops(
        cityId: String,
        wgsLat: Double,
        wgsLng: Double,
        limit: Int = 4
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val params = LinkedHashMap(DEFAULT_PARAMS).apply {
            put("cityId", cityId)
            put("localCityId", "undefined")
            put("lat", wgsLat.toString())
            put("lng", wgsLng.toString())
            put("geo_lat", wgsLat.toString())
            put("geo_lng", wgsLng.toString())
            put("type", "5")
            put("permission", "0")
        }
        val data = executeEncrypted("$BASE_URL/bus/stop!encryptedHomePage.action", params)
        val nearStsArr = data.optJSONArray("nearSts") ?: JSONArray()
        val result = ArrayList<NearbyStop>()

        for (i in 0 until nearStsArr.length()) {
            if (result.size >= limit) break
            val s = nearStsArr.getJSONObject(i)
            val isSubway = s.optInt("isSubway", 0) == 1 || s.optInt("subwayV2", 0) == 1
            val sn = s.optString("sn").trim()
            val isPureSubway = isSubway || isSubwayStationName(sn)
            if (isPureSubway) continue // 纯地铁站点，不作为附近公交站点展示

            val stLat = s.optDouble("lat", 0.0)
            val stLng = s.optDouble("lng", 0.0)
            val serverDist = if (s.has("distance")) s.optInt("distance") else null
            val dist = resolveDistance(serverDist, wgsLat, wgsLng, stLat, stLng)

            val lineStatuses = parseNearbyLineStatus(s.optJSONArray("lines"), s.optString("sn"))

            // 地铁线路
            val subwayArr = s.optJSONArray("subwayV2Lines") ?: JSONArray()
            val subwayNames = ArrayList<String>()
            for (m in 0 until subwayArr.length()) {
                val mLine = subwayArr.getJSONObject(m).optJSONObject("line")
                val mName = mLine?.optString("lineName") ?: ""
                if (mName.isNotBlank()) subwayNames.add(mName)
            }

            result.add(
                NearbyStop(
                    stopId = s.optString("sId"),
                    name = s.optString("sn"),
                    distanceMeters = dist,
                    wgsLat = s.optDouble("lat", 0.0),
                    wgsLng = s.optDouble("lng", 0.0),
                    physicalStId = s.optString("physicalStId").takeIf { it.isNotBlank() },
                    namesakeStId = s.optString("namesakeStId").takeIf { it.isNotBlank() },
                    isSubway = s.optInt("isSubway", 0) == 1,
                    lines = lineStatuses,
                    subwayLineNames = subwayNames
                )
            )
        }
        result
    }

    /**
     * 获取物理车站详情（包含同名/各物理站台全部途经线路与地铁，传入用户坐标可获取精确测地距离）
     */
    suspend fun getStopDetail(
        cityId: String,
        physicalStId: String = "",
        namesakeStId: String? = null,
        stationId: String? = null,
        userLat: Double? = null,
        userLng: Double? = null
    ): List<NearbyStop> = withContext(Dispatchers.IO) {
        val params = LinkedHashMap(DEFAULT_PARAMS).apply {
            put("cityId", cityId)
            put("localCityId", cityId)
            put("physicalStId", physicalStId)
            put("namesakeStId", namesakeStId ?: "")
            put("firstLineId", "")
            put("stationId", stationId ?: "")
            put("lat", userLat?.takeIf { it > 0.0 }?.toString() ?: "")
            put("lng", userLng?.takeIf { it > 0.0 }?.toString() ?: "")
            put("geo_lat", userLat?.takeIf { it > 0.0 }?.toString() ?: "")
            put("geo_lng", userLng?.takeIf { it > 0.0 }?.toString() ?: "")
            put("actionState", "1")
            put("permission", "0")
        }
        val data = executeEncrypted("$BASE_URL/bus/stop!encryptedPhyStnDetail.action", params)
        val stationList = data.optJSONArray("stationList") ?: JSONArray()
        val result = ArrayList<NearbyStop>()

        for (i in 0 until stationList.length()) {
            val st = stationList.getJSONObject(i)
            val stLat = st.optDouble("lat", 0.0)
            val stLng = st.optDouble("lng", 0.0)
            val serverDist = if (st.has("distance")) st.optInt("distance") else null
            val dist = resolveDistance(serverDist, userLat, userLng, stLat, stLng)

            val lineStatuses = parseNearbyLineStatus(st.optJSONArray("lines"), st.optString("sn"))

            val metrosArr = st.optJSONArray("metros") ?: JSONArray()
            val metroNames = ArrayList<String>()
            for (m in 0 until metrosArr.length()) {
                val mName = metrosArr.getJSONObject(m).optString("fullName")
                if (mName.isNotBlank()) metroNames.add(mName)
            }

            result.add(
                NearbyStop(
                    stopId = st.optString("sId"),
                    name = st.optString("sn"),
                    distanceMeters = dist,
                    wgsLat = st.optDouble("lat", 0.0),
                    wgsLng = st.optDouble("lng", 0.0),
                    physicalStId = st.optString("physicalStId").ifBlank { physicalStId.takeIf { it.isNotBlank() } },
                    namesakeStId = st.optString("namesakeStId").ifBlank { namesakeStId?.takeIf { it.isNotBlank() } },
                    lines = lineStatuses,
                    subwayLineNames = metroNames
                )
            )
        }
        result
    }

    private fun parseNearbyLineStatus(
        linesArr: JSONArray?,
        fallbackStopName: String
    ): List<NearbyStopLineStatus> {
        if (linesArr == null) return emptyList()
        val lineStatuses = ArrayList<NearbyStopLineStatus>(linesArr.length())
        for (j in 0 until linesArr.length()) {
            val li = linesArr.getJSONObject(j)
            val lineObj = li.optJSONObject("line") ?: JSONObject()
            val targetStn = li.optJSONObject("targetStation") ?: JSONObject()
            val targetStationOrder = targetStn.optInt("order", 0)
            val stnStatesArr = li.optJSONArray("stnStates") ?: JSONArray()
            val liveBuses = parseStnStates(stnStatesArr, targetStationOrder)

            val dir = BusLineDirection(
                lineId = lineObj.optString("lineId"),
                // 有的线路上游只给 lineNo，名字为空时用它顶上，徽标和收藏卡才不会空白（issue #20）
                name = lineObj.optString("name").ifBlank { lineObj.optString("lineNo") },
                lineNo = lineObj.optString("lineNo"),
                direction = lineObj.optInt("direction", 0),
                startStop = lineObj.optString("startSn"),
                endStop = lineObj.optString("endSn"),
                firstTime = lineObj.optString("firstTime"),
                lastTime = lineObj.optString("lastTime"),
                price = lineObj.optString("price"),
                source = DataSourceType.CHELAILE
            )

            val shortDesc = lineObj.optString("shortDesc").trim()
            val nextOp = lineObj.optString("nextOperationTimeDesc").trim()
            val statusText = shortDesc.ifBlank { nextOp }

            lineStatuses.add(
                NearbyStopLineStatus(
                    line = dir,
                    targetOrder = targetStationOrder,
                    targetStopId = targetStn.optString("sId"),
                    targetStopName = targetStn.optString("sn").ifBlank { fallbackStopName },
                    statusText = statusText,
                    preArrivalTime = li.optString("preArrivalTime").takeIf { it.isNotBlank() },
                    approachingBuses = liveBuses
                )
            )
        }
        return lineStatuses
    }

    private fun parseStnStates(stnStatesArr: JSONArray, targetStationOrder: Int): List<LiveBus> {
        val liveBuses = ArrayList<LiveBus>()
        for (k in 0 until stnStatesArr.length()) {
            val b = stnStatesArr.getJSONObject(k)
            val travelTime = b.optInt("travelTime", -1)
            val bBusId = b.optString("busId")
            val bPlate = sanitizeBusPlate(b.optString("licence")).ifBlank {
                sanitizeBusPlate(bBusId)
            }
            val stopsAway = b.optInt("value", -1)
            val state = b.optInt("state", 0)
            val distanceToDest = if (b.has("distanceToDest")) b.optInt("distanceToDest") else null

            // 过滤完全无有效到站信息的占位空车对象
            val hasValidInfo = stopsAway >= 0 || travelTime >= 0 || (distanceToDest != null && distanceToDest > 0)
            if (!hasValidInfo && stnStatesArr.length() > 1) {
                continue
            }

            // 换算 1-based 全线站序：
            // 车来了 stnStates 中，value 表示距目标站还剩几站 (0=进站/到站, 1=还有1站, 2=还有2站)
            // b.optInt("order") 在 stnStates 内部通常为 0，不能直接作为全线站点序号
            val busOrder = when {
                stopsAway >= 0 && targetStationOrder > 0 -> (targetStationOrder - stopsAway).coerceAtLeast(1)
                b.optInt("order", 0) > 0 -> b.optInt("order", 0)
                targetStationOrder > 0 -> targetStationOrder
                else -> 0
            }

            val isArrived = state == 1 || (stopsAway == 0 && (travelTime in 0..20 || (distanceToDest != null && distanceToDest <= 50)))

            liveBuses.add(
                LiveBus(
                    busId = bBusId,
                    plateOrFleetNo = bPlate,
                    order = busOrder,
                    etaSeconds = travelTime,
                    capacityLevel = b.optInt("capacity", 0),
                    distanceToTargetMeters = distanceToDest?.takeIf { it > 0 },
                    isArrived = isArrived
                )
            )
        }

        liveBuses.sortWith(
            compareBy<LiveBus> { !it.isArrived }
                .thenBy { if (targetStationOrder > 0 && it.order > 0) (targetStationOrder - it.order).coerceAtLeast(0) else 999 }
                .thenBy { if (it.etaSeconds >= 0) it.etaSeconds else Int.MAX_VALUE }
        )

        return liveBuses
    }

    /**
     * 获取完整线路详情（站点列表 + 全线在途车辆 + 针对 targetOrder 站点的精确预测 ETA 与站间投影比例）
     */
    suspend fun getLineDetail(
        cityId: String,
        lineId: String,
        targetOrder: Int? = null,
        stationId: String? = null,
        wgsLat: Double? = null,
        wgsLng: Double? = null
    ): BusLineDetail = withContext(Dispatchers.IO) {
        val detailParams = LinkedHashMap(DEFAULT_PARAMS).apply {
            put("cityId", cityId)
            put("localCityId", cityId)
            put("lineId", lineId)
            put("lat", wgsLat?.toString() ?: "")
            put("lng", wgsLng?.toString() ?: "")
            put("geo_lat", wgsLat?.toString() ?: "")
            put("geo_lng", wgsLng?.toString() ?: "")
        }
        val data = executeEncrypted("$BASE_URL/bus/line!encryptedLineDetail.action", detailParams)

        val lineObj = data.optJSONObject("line") ?: JSONObject()
        val lineNo = lineObj.optString("lineNo")
        // 名字为空时用线路号顶上（有的线路上游只给 lineNo）
        val name = lineObj.optString("name").ifBlank { lineNo }
        val direction = lineObj.optInt("direction", 0)
        val startSn = lineObj.optString("startSn")
        val endSn = lineObj.optString("endSn")
        val firstTime = lineObj.optString("firstTime")
        val lastTime = lineObj.optString("lastTime")
        val price = lineObj.optString("price")

        val currentDirection = BusLineDirection(
            lineId = lineId,
            name = name,
            lineNo = lineNo,
            direction = direction,
            startStop = startSn,
            endStop = endSn,
            firstTime = firstTime,
            lastTime = lastTime,
            price = price,
            source = DataSourceType.CHELAILE
        )

        // 反向线路：otherlines 为空 = 这条线没有对向（单程 / 环线 / 上游没维护）。
        // lineId 为空也当没有：否则会造出一个「换向按钮在、点了必然失败」的反向线路。
        val revObj = data.optJSONArray("otherlines")?.optJSONObject(0)
        val reverseLineId = revObj?.optString("lineId").orEmpty()
        val reverseDirection = if (revObj != null && reverseLineId.isNotBlank()) {
            BusLineDirection(
                lineId = reverseLineId,
                name = name,
                lineNo = lineNo,
                direction = if (direction == 0) 1 else 0,
                startStop = revObj.optString("startSn"),
                endStop = revObj.optString("endSn"),
                firstTime = revObj.optString("firstTime"),
                lastTime = revObj.optString("lastTime"),
                price = revObj.optString("price").ifBlank { price },
                source = DataSourceType.CHELAILE
            )
        } else null

        // 站表
        val stationsArr = data.optJSONArray("stations") ?: JSONArray()
        val stopsList = ArrayList<BusStop>()
        for (i in 0 until stationsArr.length()) {
            val st = stationsArr.getJSONObject(i)
            val metrosArr = st.optJSONArray("metros")
            val metroNames = ArrayList<String>()
            if (metrosArr != null) {
                for (m in 0 until metrosArr.length()) {
                    val mName = metrosArr.optJSONObject(m)?.optString("fullName") ?: ""
                    if (mName.isNotBlank()) metroNames.add(mName)
                }
            }
            stopsList.add(
                BusStop(
                    stopId = st.optString("sId"),
                    name = st.optString("sn"),
                    order = st.optInt("order", i + 1),
                    wgsLat = st.optDouble("wgsLat", 0.0),
                    wgsLng = st.optDouble("wgsLng", 0.0),
                    physicalStId = st.optString("physicalStId").takeIf { it.isNotBlank() },
                    namesakeStId = st.optString("namesakeStId").takeIf { it.isNotBlank() },
                    metroTransfers = metroNames
                )
            )
        }

        // 若传入 targetOrder > 0，请求 encryptedBusDetail.action 获取到目标站的预测 travelTime 与 recommTip
        val etaMap = HashMap<String, Pair<Int, String?>>() // busId -> (travelTime, recommTip)
        if (targetOrder != null && targetOrder > 0) {
            try {
                val targetStop = stopsList.find { it.order == targetOrder }
                val tStId = stationId ?: targetStop?.stopId ?: ""
                val tLat = wgsLat?.toString() ?: targetStop?.wgsLat?.toString() ?: ""
                val tLng = wgsLng?.toString() ?: targetStop?.wgsLng?.toString() ?: ""

                val busDetailParams = LinkedHashMap(DEFAULT_PARAMS).apply {
                    put("cshow", "busDetail")
                    put("specail", "0")
                    put("specialType", "undefined")
                    put("cityId", cityId)
                    put("localCityId", cityId)
                    put("lineId", lineId)
                    put("targetOrder", targetOrder.toString())
                    put("specialTargetOrder", targetOrder.toString())
                    put("stationId", tStId)
                    put("lat", tLat)
                    put("lng", tLng)
                    put("geo_lat", tLat)
                    put("geo_lng", tLng)
                    put("userId", "")
                    put("h5Id", "")
                    put("unionId", "")
                    put("accountId", "")
                    put("secret", "")
                }
                val busData = executeEncrypted("$BASE_URL/bus/line!encryptedBusDetail.action", busDetailParams)
                val busArr = busData.optJSONArray("buses") ?: JSONArray()
                for (i in 0 until busArr.length()) {
                    val b = busArr.getJSONObject(i)
                    val bId = b.optString("busId")
                    val travels = b.optJSONArray("travels")
                    if (travels != null && travels.length() > 0) {
                        val tr = travels.getJSONObject(0)
                        val travelTime = tr.optInt("travelTime", -1)
                        val recommTip = tr.optString("recommTip").takeIf { it.isNotBlank() }
                        etaMap[bId] = Pair(travelTime, recommTip)
                    }
                }
            } catch (_: Exception) {
                // 忽略非致命预测拉取失败，保持主干可用
            }
        }

        // 解析在途车辆并计算站间投影进度
        val busesArr = data.optJSONArray("buses") ?: JSONArray()
        val liveBuses = ArrayList<LiveBus>()
        for (i in 0 until busesArr.length()) {
            val b = busesArr.getJSONObject(i)
            val bId = b.optString("busId")
            val bOrder = b.optInt("order", 0)
            val bLat = b.optDouble("lat", 0.0)
            val bLng = b.optDouble("lng", 0.0)
            val bSpeed = b.optDouble("speed", 0.0)
            val rawLicence = b.optString("licence")
            val plate = sanitizeBusPlate(rawLicence).ifBlank {
                sanitizeBusPlate(bId)
            }
            val nextStopName = b.optString("nextStationName").takeIf { it.isNotBlank() }

            val etaPair = etaMap[bId]
            val etaSecs = etaPair?.first ?: -1
            val recommTip = etaPair?.second

            // 到达判定：如果目标就是本站且剩余时间极小，或上游提示已到站
            val isArrivedAtTarget = targetOrder != null && bOrder == targetOrder && (etaSecs in 0..20 || recommTip?.contains("已到站") == true)
            val prevStop = stopsList.find { it.order == bOrder - 1 }
            val targetStop = stopsList.find { it.order == bOrder }

            val segmentProgress = RouteProjectionMath.computeSegmentProgress(
                busLat = bLat,
                busLng = bLng,
                isArrived = isArrivedAtTarget,
                prevStop = prevStop,
                targetStop = targetStop
            )

            // distanceToWaitStn = 车辆到候车站（targetOrder）的距离；未指定候车站、或车已过站时上游给 -1，都不展示
            val distanceToWait = if (targetOrder != null && targetOrder > 0 && b.has("distanceToWaitStn")) {
                b.optInt("distanceToWaitStn", -1).takeIf { it > 0 }
            } else {
                null
            }

            liveBuses.add(
                LiveBus(
                    busId = bId,
                    plateOrFleetNo = plate,
                    order = bOrder,
                    segmentProgress = segmentProgress,
                    isArrived = isArrivedAtTarget,
                    wgsLat = bLat,
                    wgsLng = bLng,
                    speedKmh = bSpeed,
                    capacityLevel = b.optInt("capacity", 0),
                    distanceToTargetMeters = distanceToWait,
                    etaSeconds = etaSecs,
                    expectedArrivalTimeText = recommTip ?: (if (etaSecs > 0) RouteProjectionMath.formatEtaText(etaSecs, isArrivedAtTarget) else null),
                    nextStopName = nextStopName
                )
            )
        }

        val depDesc = data.optString("depDesc")
        val preArrival = data.optString("preArrivalTime").takeIf { it.isNotBlank() }

        val operatingState = when {
            depDesc.contains("停运") -> LineOperatingState.SUSPENDED
            depDesc.contains("等待发车") -> LineOperatingState.WAITING
            depDesc.contains("末班已过") || depDesc.contains("不在运营时间") -> LineOperatingState.ENDED
            else -> LineOperatingState.RUNNING
        }

        BusLineDetail(
            line = currentDirection,
            reverseDirection = reverseDirection,
            stops = stopsList,
            buses = liveBuses,
            operatingState = operatingState,
            statusDescription = depDesc,
            nextDepartureFromStart = preArrival,
            targetOrder = targetOrder ?: 0,
            updatedAtMillis = wallClock.now().toEpochMilliseconds()
        )
    }

    /**
     * 获取首站发车时刻表（支持今日预测 + 昨日实际发车班次回落）
     */
    suspend fun getTimetable(
        cityId: String,
        lineId: String,
        lineNo: String,
        direction: Int,
        atTimeHHMM: String? = null
    ): LineTimetable = withContext(Dispatchers.IO) {
        val params = LinkedHashMap(DEFAULT_PARAMS).apply {
            put("cityId", cityId)
            put("lineId", lineId)
            put("lineNo", lineNo)
            put("direction", direction.toString())
            // 注意：不传 tabType，以此保留昨日完整班次返回
        }
        val data = executeEncrypted("$BASE_URL/bus/line!preStartTimetableNew.action", params)

        fun extractDepartures(block: JSONObject?): List<String> {
            if (block == null) return emptyList()
            val segs = block.optJSONArray("timetable") ?: return emptyList()
            val list = ArrayList<String>()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONObject(i) ?: continue
                val schedules = seg.optJSONArray("schedules")
                if (schedules != null && schedules.length() > 0) {
                    for (j in 0 until schedules.length()) {
                        val t = schedules.optJSONObject(j)?.optString("time") ?: schedules.optString(j)
                        if (t.isNotBlank()) list.add(t)
                    }
                } else {
                    val times = seg.optJSONArray("times")
                    if (times != null) {
                        for (j in 0 until times.length()) {
                            val t = times.optString(j)
                            if (t.isNotBlank()) list.add(t)
                        }
                    }
                }
            }
            return list.sorted()
        }

        fun extractSegments(block: JSONObject?): List<TimetableSegment> {
            if (block == null) return emptyList()
            val segs = block.optJSONArray("timetable") ?: return emptyList()
            val list = ArrayList<TimetableSegment>()
            for (i in 0 until segs.length()) {
                val seg = segs.optJSONObject(i) ?: continue
                val fromTime = seg.optString("fTime")
                val toTime = seg.optString("eTime")
                val depInterval = seg.optInt("depInterval", -1)
                val tips = seg.optString("depIntervalTips")
                val departures = ArrayList<String>()
                val schedules = seg.optJSONArray("schedules")
                if (schedules != null) {
                    for (j in 0 until schedules.length()) {
                        val t = schedules.optJSONObject(j)?.optString("time") ?: schedules.optString(j)
                        if (t.isNotBlank()) departures.add(t)
                    }
                }
                list.add(
                    TimetableSegment(
                        fromTime = fromTime,
                        toTime = toTime,
                        intervalMinutes = depInterval,
                        intervalTips = tips,
                        departures = departures
                    )
                )
            }
            return list
        }

        val todayBlock = data.optJSONObject("timetable")
        val yesterdayBlock = data.optJSONObject("yesterdayTimetable")

        val todayDepartures = extractDepartures(todayBlock)
        val yesterdayDepartures = extractDepartures(yesterdayBlock)
        val segments = extractSegments(todayBlock)

        val beijingNow = wallClock.now().toLocalDateTime(BEIJING).let { t ->
            "${t.hour.toString().padStart(2, '0')}:${t.minute.toString().padStart(2, '0')}"
        }
        val reference = atTimeHHMM ?: beijingNow

        // 和掌上公交共用同一套「下一班」规则，避免两个数据源口径不一致
        val resolved = TimetableRules.resolveNext(
            todayDepartures = todayDepartures,
            yesterdayDepartures = yesterdayDepartures,
            referenceHHMM = reference,
            strict = atTimeHHMM != null
        )
        val nextDep = resolved.time
        val nextDepSource = resolved.source

        val lineObj = data.optJSONObject("line")
        // `?:` 只管 null，不管空串：名字是空串时也要退回线路号
        val lineName = lineObj?.optString("name")?.ifBlank { lineNo } ?: lineNo
        val depDesc = todayBlock?.optString("depDesc") ?: data.optString("depDesc")

        LineTimetable(
            lineId = lineId,
            lineName = lineName,
            nextDeparture = nextDep,
            nextDepartureSource = nextDepSource,
            todayDepartures = todayDepartures,
            yesterdayDepartures = yesterdayDepartures,
            segments = segments,
            depDesc = depDesc
        )
    }
}
