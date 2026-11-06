package com.trackr.core.model

/** 上游响应无法可信地解释为业务数据。 */
interface TransitProtocolFailure

/** 上游服务拒绝或暂时无法处理请求，但响应格式本身可识别。 */
interface TransitServiceFailure
